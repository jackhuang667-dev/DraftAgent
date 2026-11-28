package com.draftagent.ai.rag.retrieval;

import com.draftagent.ai.rag.qdrant.QdrantChunkSearchService;
import com.draftagent.business.document.DocumentSearchException;
import com.draftagent.business.document.DocumentSearchResult;
import com.draftagent.business.document.DocumentSearchScope;
import com.draftagent.business.document.DocumentVectorSearcher;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 向量检索协调：校验参数 → 生成查询向量 → Qdrant Top-K 检索。
 *
 * <p>本批只返回检索结果元数据，不回查 MySQL 原文、不组装 Prompt、不调用 DeepSeek。</p>
 */
@Service
public class DocumentVectorSearchService implements DocumentVectorSearcher {

    private final QueryEmbeddingService queryEmbeddingService;

    private final QdrantChunkSearchService qdrantChunkSearchService;

    private final RagRetrievalProperties properties;

    public DocumentVectorSearchService(QueryEmbeddingService queryEmbeddingService,
                                       QdrantChunkSearchService qdrantChunkSearchService,
                                       RagRetrievalProperties properties) {
        this.queryEmbeddingService = queryEmbeddingService;
        this.qdrantChunkSearchService = qdrantChunkSearchService;
        this.properties = properties;
    }

    @Override
    public List<DocumentSearchResult> search(String query, DocumentSearchScope scope) {
        return search(query, scope, this.properties.getTopK());
    }

    @Override
    public List<DocumentSearchResult> search(String query, DocumentSearchScope scope, int topK) {
        if (scope == null) {
            throw new DocumentSearchException("检索范围不能为空，拒绝无过滤检索");
        }
        if (topK < 1 || topK > this.properties.getMaxTopK()) {
            throw new DocumentSearchException(
                    "topK 必须在 1.." + this.properties.getMaxTopK() + " 之间，实际为 " + topK);
        }
        float[] queryVector = this.queryEmbeddingService.embedQuery(query);
        return this.qdrantChunkSearchService.search(queryVector, scope, topK);
    }
}
