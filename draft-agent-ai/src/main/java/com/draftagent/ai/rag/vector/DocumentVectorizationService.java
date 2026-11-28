package com.draftagent.ai.rag.vector;

import com.draftagent.ai.rag.qdrant.RagQdrantProperties;
import com.draftagent.business.document.DocumentVectorizationException;
import com.draftagent.business.document.DocumentVectorizer;
import com.draftagent.business.entity.Document;
import com.draftagent.business.entity.DocumentChunk;
import io.qdrant.client.grpc.Points.PointStruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 文档向量化协调：按批（32 条）批量 Embedding → 构建 Point → Qdrant Upsert。
 *
 * <p>远程 Embedding / Qdrant 调用均不在 MySQL 事务内。任一批失败时，若已发生 Upsert，
 * 会按 document_id 做有界补偿删除（不影响其他文档/租户）；补偿失败会保留 document_id
 * 与两侧错误原因后抛出。</p>
 */
@Service
public class DocumentVectorizationService implements DocumentVectorizer {

    private static final Logger log = LoggerFactory.getLogger(DocumentVectorizationService.class);

    private static final int EMBEDDING_BATCH_SIZE = 32;

    private final DocumentEmbeddingService embeddingService;

    private final QdrantChunkVectorService chunkVectorService;

    private final RagQdrantProperties qdrantProperties;

    public DocumentVectorizationService(DocumentEmbeddingService embeddingService,
                                        QdrantChunkVectorService chunkVectorService,
                                        RagQdrantProperties qdrantProperties) {
        this.embeddingService = embeddingService;
        this.chunkVectorService = chunkVectorService;
        this.qdrantProperties = qdrantProperties;
    }

    @Override
    public int vectorize(Document document, List<DocumentChunk> chunks) {
        if (document == null) {
            throw new DocumentVectorizationException("文档信息缺失，无法向量化");
        }
        if (chunks == null || chunks.isEmpty()) {
            return 0;
        }
        String documentId = document.getDocumentId();
        String collection = this.qdrantProperties.getCollection();
        int upserted = 0;
        int batches = 0;
        boolean upsertAttempted = false;
        try {
            for (int start = 0; start < chunks.size(); start += EMBEDDING_BATCH_SIZE) {
                int end = Math.min(start + EMBEDDING_BATCH_SIZE, chunks.size());
                List<DocumentChunk> batch = chunks.subList(start, end);
                List<String> texts = new ArrayList<>(batch.size());
                for (DocumentChunk chunk : batch) {
                    texts.add(chunk.getContent());
                }
                List<float[]> vectors = this.embeddingService.embed(texts);
                List<PointStruct> points = this.chunkVectorService.buildPoints(document, batch, vectors);
                upsertAttempted = true;
                this.chunkVectorService.upsert(collection, points);
                upserted += points.size();
                batches++;
            }
            log.info("文档向量写入完成: documentId={}, chunks={}, batches={}", documentId, chunks.size(), batches);
            return upserted;
        }
        catch (RuntimeException e) {
            if (upsertAttempted) {
                try {
                    this.chunkVectorService.deleteByDocumentId(collection, documentId);
                }
                catch (RuntimeException cleanup) {
                    log.error("Qdrant 向量补偿删除失败: documentId={}", documentId, cleanup);
                    throw new DocumentVectorizationException(
                            "向量入库失败且补偿删除失败: document_id=" + documentId
                                    + "; upsertError=" + safeMessage(e)
                                    + "; cleanupError=" + safeMessage(cleanup), e);
                }
            }
            throw new DocumentVectorizationException(
                    "文档向量化失败: document_id=" + documentId + "; " + safeMessage(e), e);
        }
    }

    @Override
    public void deleteByDocumentId(String documentId) {
        try {
            this.chunkVectorService.deleteByDocumentId(this.qdrantProperties.getCollection(), documentId);
        }
        catch (DocumentVectorizationException e) {
            throw e;
        }
        catch (RuntimeException e) {
            throw new DocumentVectorizationException(
                    "按 document_id 删除向量失败: document_id=" + documentId, e);
        }
    }

    private static String safeMessage(Throwable throwable) {
        if (throwable == null) {
            return "unknown";
        }
        String message = throwable.getMessage();
        if (message == null || message.isBlank()) {
            message = throwable.getClass().getSimpleName();
        }
        return message.length() > 200 ? message.substring(0, 200) : message;
    }
}
