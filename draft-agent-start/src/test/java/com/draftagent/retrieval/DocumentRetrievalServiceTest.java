package com.draftagent.retrieval;

import com.draftagent.ai.rag.retrieval.DocumentRetrievalService;
import com.draftagent.business.document.DocumentChunkContentRetriever;
import com.draftagent.business.document.DocumentSearchException;
import com.draftagent.business.document.DocumentSearchResult;
import com.draftagent.business.document.DocumentSearchScope;
import com.draftagent.business.document.DocumentVectorSearcher;
import com.draftagent.business.document.RetrievedDocumentChunk;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * RAG 结果组装：零命中、按 Qdrant 顺序重组、部分丢弃与异常传播。
 */
class DocumentRetrievalServiceTest {

    private final DocumentVectorSearcher searcher = mock(DocumentVectorSearcher.class);

    private final DocumentChunkContentRetriever contentRetriever = mock(DocumentChunkContentRetriever.class);

    private final DocumentSearchScope scope = new DocumentSearchScope(7L, 100L);

    @Test
    void zeroHitsReturnsEmptyWithoutMysqlLookup() {
        when(this.searcher.search("问题", this.scope)).thenReturn(List.of());

        assertThat(service().retrieve("问题", this.scope)).isEmpty();
        verifyNoInteractions(this.contentRetriever);
    }

    @Test
    void restoresQdrantOrderWhenMysqlReturnsDifferentOrder() {
        String documentId = UUID.randomUUID().toString();
        DocumentSearchResult a = hit(1L, documentId, 11L, 0, 0.92f);
        DocumentSearchResult b = hit(2L, documentId, 12L, 0, 0.88f);
        DocumentSearchResult c = hit(3L, documentId, 13L, 0, 0.85f);
        when(this.searcher.search("问题", this.scope)).thenReturn(List.of(a, b, c));
        when(this.contentRetriever.retrieve(List.of(a, b, c), this.scope)).thenReturn(List.of(
                retrieved(c, "C"),
                retrieved(a, "A"),
                retrieved(b, "B")));

        List<RetrievedDocumentChunk> result = service().retrieve("问题", this.scope);

        assertThat(result).extracting(RetrievedDocumentChunk::getChunkId).containsExactly(1L, 2L, 3L);
        assertThat(result).extracting(RetrievedDocumentChunk::getContent).containsExactly("A", "B", "C");
    }

    @Test
    void keepsOnlyValidSubsetWithoutBackfilling() {
        String documentId = UUID.randomUUID().toString();
        DocumentSearchResult a = hit(1L, documentId, 11L, 0, 0.92f);
        DocumentSearchResult b = hit(2L, documentId, 12L, 0, 0.88f);
        DocumentSearchResult c = hit(3L, documentId, 13L, 0, 0.85f);
        when(this.searcher.search("问题", this.scope)).thenReturn(List.of(a, b, c));
        when(this.contentRetriever.retrieve(List.of(a, b, c), this.scope)).thenReturn(List.of(
                retrieved(a, "A"),
                retrieved(c, "C")));

        List<RetrievedDocumentChunk> result = service().retrieve("问题", this.scope);

        assertThat(result).extracting(RetrievedDocumentChunk::getChunkId).containsExactly(1L, 3L);
    }

    @Test
    void usesDefaultTopKThroughSearcher() {
        when(this.searcher.search("问题", this.scope)).thenReturn(List.of());

        service().retrieve("问题", this.scope);

        verify(this.searcher).search("问题", this.scope);
    }

    @Test
    void passesExplicitTopKThroughSearcher() {
        when(this.searcher.search("问题", this.scope, 3)).thenReturn(List.of());

        service().retrieve("问题", this.scope, 3);

        verify(this.searcher).search("问题", this.scope, 3);
    }

    @Test
    void mysqlLookupFailurePropagates() {
        DocumentSearchResult a = hit(1L, UUID.randomUUID().toString(), 11L, 0, 0.9f);
        when(this.searcher.search("问题", this.scope)).thenReturn(List.of(a));
        when(this.contentRetriever.retrieve(List.of(a), this.scope))
                .thenThrow(new DocumentSearchException("原文回查失败，请稍后重试"));

        assertThatThrownBy(() -> service().retrieve("问题", this.scope))
                .isInstanceOf(DocumentSearchException.class)
                .hasMessageContaining("原文回查失败");
    }

    private DocumentRetrievalService service() {
        return new DocumentRetrievalService(this.searcher, this.contentRetriever);
    }

    private static DocumentSearchResult hit(long chunkId, String documentId, long documentPk,
                                           int chunkIndex, float score) {
        return new DocumentSearchResult(chunkId, documentId, documentPk, chunkId, chunkIndex, score);
    }

    private static RetrievedDocumentChunk retrieved(DocumentSearchResult hit, String content) {
        return new RetrievedDocumentChunk(hit.getDocumentId(), hit.getDocumentPk(),
                hit.getChunkId(), hit.getChunkIndex(), content, hit.getScore());
    }
}
