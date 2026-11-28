package com.draftagent.ai.rag.vector;

import com.draftagent.ai.rag.qdrant.RagQdrantProperties;
import com.draftagent.business.document.DocumentVectorizationException;
import com.draftagent.business.entity.Document;
import com.draftagent.business.entity.DocumentChunk;
import com.google.common.util.concurrent.ListenableFuture;
import io.qdrant.client.ConditionFactory;
import io.qdrant.client.PointIdFactory;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.ValueFactory;
import io.qdrant.client.VectorsFactory;
import io.qdrant.client.grpc.JsonWithInt.Value;
import io.qdrant.client.grpc.Points.DeletePoints;
import io.qdrant.client.grpc.Points.Filter;
import io.qdrant.client.grpc.Points.PointStruct;
import io.qdrant.client.grpc.Points.PointsSelector;
import io.qdrant.client.grpc.Points.UpsertPoints;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Qdrant Chunk 向量写入/删除。
 *
 * <p>Point ID = MySQL {@code document_chunk.chunk_id}（稳定、重复 Upsert 覆盖同一 Point）。
 * Payload 不包含 Chunk 全文；个人文档 {@code tenant_id} 以显式 JSON null 写入。</p>
 */
@Service
public class QdrantChunkVectorService {

    private static final Logger log = LoggerFactory.getLogger(QdrantChunkVectorService.class);

    private static final String FIELD_DOCUMENT_ID = "document_id";

    private static final String FIELD_DOCUMENT_PK = "document_pk";

    private static final String FIELD_CHUNK_ID = "chunk_id";

    private static final String FIELD_CHUNK_INDEX = "chunk_index";

    private static final String FIELD_TENANT_ID = "tenant_id";

    private static final String FIELD_USER_ID = "user_id";

    private final QdrantClient qdrantClient;

    private final RagQdrantProperties properties;

    public QdrantChunkVectorService(QdrantClient qdrantClient, RagQdrantProperties properties) {
        this.qdrantClient = qdrantClient;
        this.properties = properties;
    }

    public List<PointStruct> buildPoints(Document document, List<DocumentChunk> chunks, List<float[]> vectors) {
        if (document == null) {
            throw new DocumentVectorizationException("文档信息缺失，无法构建向量 Point");
        }
        if (document.getDocumentPk() == null || document.getUserId() == null) {
            throw new DocumentVectorizationException(
                    "文档归属信息缺失，无法构建向量 Point: document_id=" + document.getDocumentId());
        }
        requireValidDocumentId(document.getDocumentId());
        if (chunks == null || vectors == null || chunks.size() != vectors.size()) {
            throw new DocumentVectorizationException(
                    "Chunk 与向量数量不一致: document_id=" + document.getDocumentId());
        }

        List<PointStruct> points = new ArrayList<>(chunks.size());
        for (int index = 0; index < chunks.size(); index++) {
            DocumentChunk chunk = chunks.get(index);
            float[] vector = vectors.get(index);
            if (chunk.getChunkId() == null || chunk.getChunkId() <= 0) {
                throw new DocumentVectorizationException(
                        "Chunk ID 缺失或不合法: document_id=" + document.getDocumentId());
            }
            if (chunk.getChunkIndex() == null || chunk.getChunkIndex() < 0) {
                throw new DocumentVectorizationException(
                        "Chunk 序号缺失或不合法: document_id=" + document.getDocumentId());
            }
            if (vector == null) {
                throw new DocumentVectorizationException(
                        "向量为空: document_id=" + document.getDocumentId() + ", chunk_id=" + chunk.getChunkId());
            }

            Map<String, Value> payload = new LinkedHashMap<>();
            payload.put(FIELD_DOCUMENT_ID, ValueFactory.value(document.getDocumentId()));
            payload.put(FIELD_DOCUMENT_PK, ValueFactory.value(document.getDocumentPk()));
            payload.put(FIELD_CHUNK_ID, ValueFactory.value(chunk.getChunkId()));
            payload.put(FIELD_CHUNK_INDEX, ValueFactory.value(chunk.getChunkIndex().longValue()));
            payload.put(FIELD_USER_ID, ValueFactory.value(document.getUserId()));
            if (document.getTenantId() == null) {
                // 个人文档：显式写入 JSON null，不能省略字段
                payload.put(FIELD_TENANT_ID, ValueFactory.nullValue());
            }
            else {
                payload.put(FIELD_TENANT_ID, ValueFactory.value(document.getTenantId()));
            }

            points.add(PointStruct.newBuilder()
                    .setId(PointIdFactory.id(chunk.getChunkId()))
                    .setVectors(VectorsFactory.vectors(vector))
                    .putAllPayload(payload)
                    .build());
        }
        return points;
    }

    public void upsert(String collection, List<PointStruct> points) {
        if (points == null || points.isEmpty()) {
            return;
        }
        UpsertPoints upsertPoints = UpsertPoints.newBuilder()
                .setCollectionName(collection)
                .addAllPoints(points)
                .setWait(true)
                .build();
        await(this.qdrantClient.upsertAsync(upsertPoints, timeout()), "Qdrant 批量 Upsert");
    }

    public void deleteByDocumentId(String collection, String documentId) {
        requireValidDocumentId(documentId);
        Filter filter = Filter.newBuilder()
                .addMust(ConditionFactory.matchKeyword(FIELD_DOCUMENT_ID, documentId))
                .build();
        PointsSelector selector = PointsSelector.newBuilder().setFilter(filter).build();
        DeletePoints deletePoints = DeletePoints.newBuilder()
                .setCollectionName(collection)
                .setPoints(selector)
                .setWait(true)
                .build();
        await(this.qdrantClient.deleteAsync(deletePoints, timeout()), "按 document_id 删除向量");
        log.info("已按 document_id 删除向量: documentId={}", documentId);
    }

    private void requireValidDocumentId(String documentId) {
        if (!StringUtils.hasText(documentId)) {
            throw new DocumentVectorizationException("document_id 不能为空，拒绝无过滤的向量删除");
        }
        try {
            UUID.fromString(documentId);
        }
        catch (IllegalArgumentException e) {
            throw new DocumentVectorizationException("document_id 必须为合法 UUID: " + documentId);
        }
    }

    private Duration timeout() {
        return Duration.ofMillis(this.properties.getTimeoutMs());
    }

    private <T> T await(ListenableFuture<T> future, String action) {
        try {
            return future.get(this.properties.getTimeoutMs(), TimeUnit.MILLISECONDS);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DocumentVectorizationException(action + "被中断");
        }
        catch (TimeoutException e) {
            throw new DocumentVectorizationException(action + "超时（" + this.properties.getTimeoutMs() + "ms）");
        }
        catch (ExecutionException e) {
            Throwable cause = e.getCause();
            String detail = cause != null && cause.getMessage() != null
                    ? cause.getMessage() : (cause != null ? cause.getClass().getSimpleName() : "unknown");
            if (detail.length() > 300) {
                detail = detail.substring(0, 300);
            }
            throw new DocumentVectorizationException(action + "失败: " + detail);
        }
    }
}
