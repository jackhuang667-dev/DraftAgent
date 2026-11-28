package com.draftagent.ai.rag.retrieval;

import com.draftagent.business.document.DocumentChunkContentRetriever;
import com.draftagent.business.document.DocumentSearchResult;
import com.draftagent.business.document.DocumentSearchScope;
import com.draftagent.business.document.DocumentVectorSearcher;
import com.draftagent.business.document.RetrievedDocumentChunk;
import com.draftagent.business.rag.RagDocumentRetriever;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RAG 结果组装：Qdrant Top-K 检索 → MySQL 原文回查与权限复核 → 按 Qdrant 原始排名重组。
 *
 * <p>本批仍只返回 Chunk 原文与分数，不组装 Prompt、不调用 DeepSeek。</p>
 */
@Service
public class DocumentRetrievalService implements RagDocumentRetriever {

    private final DocumentVectorSearcher documentVectorSearcher;

    private final DocumentChunkContentRetriever contentRetriever;

    public DocumentRetrievalService(DocumentVectorSearcher documentVectorSearcher,
                                    DocumentChunkContentRetriever contentRetriever) {
        this.documentVectorSearcher = documentVectorSearcher;
        this.contentRetriever = contentRetriever;
    }

    @Override
    public List<RetrievedDocumentChunk> retrieve(String query, DocumentSearchScope scope) {
        return assemble(this.documentVectorSearcher.search(query, scope), scope);
    }

    @Override
    public List<RetrievedDocumentChunk> retrieve(String query, DocumentSearchScope scope, int topK) {
        return assemble(this.documentVectorSearcher.search(query, scope, topK), scope);
    }

    private List<RetrievedDocumentChunk> assemble(List<DocumentSearchResult> hits, DocumentSearchScope scope) {
        if (hits == null || hits.isEmpty()) {
            return List.of();
        }
        List<RetrievedDocumentChunk> retrieved = this.contentRetriever.retrieve(hits, scope);
        if (retrieved == null || retrieved.isEmpty()) {
            return List.of();
        }
        Map<Long, RetrievedDocumentChunk> byChunkId = new LinkedHashMap<>();
        for (RetrievedDocumentChunk chunk : retrieved) {
            if (chunk != null && chunk.getChunkId() != null) {
                byChunkId.putIfAbsent(chunk.getChunkId(), chunk);
            }
        }
        List<RetrievedDocumentChunk> ordered = new ArrayList<>(byChunkId.size());
        for (DocumentSearchResult hit : hits) {
            if (hit == null || hit.getChunkId() == null) {
                continue;
            }
            RetrievedDocumentChunk chunk = byChunkId.get(hit.getChunkId());
            if (chunk != null) {
                ordered.add(chunk);
            }
        }
        return ordered;
    }
}
