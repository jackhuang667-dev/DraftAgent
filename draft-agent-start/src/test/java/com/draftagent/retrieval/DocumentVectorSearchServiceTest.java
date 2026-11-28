package com.draftagent.retrieval;

import com.draftagent.ai.rag.qdrant.QdrantChunkSearchService;
import com.draftagent.ai.rag.retrieval.DocumentVectorSearchService;
import com.draftagent.ai.rag.retrieval.QueryEmbeddingService;
import com.draftagent.ai.rag.retrieval.RagRetrievalProperties;
import com.draftagent.business.document.DocumentSearchException;
import com.draftagent.business.document.DocumentSearchResult;
import com.draftagent.business.document.DocumentSearchScope;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 检索协调器：Top-K 校验、默认值、向量生成与 Qdrant 委托。
 */
class DocumentVectorSearchServiceTest {

    private final QueryEmbeddingService queryEmbeddingService = mock(QueryEmbeddingService.class);

    private final QdrantChunkSearchService qdrantChunkSearchService = mock(QdrantChunkSearchService.class);

    private final RagRetrievalProperties properties = new RagRetrievalProperties();

    private final DocumentSearchScope scope = new DocumentSearchScope(7L, 100L);

    @Test
    void usesDefaultTopKWhenNotSpecified() {
        float[] vector = vector1024();
        List<DocumentSearchResult> expected = List.of();
        when(this.queryEmbeddingService.embedQuery("问题")).thenReturn(vector);
        when(this.qdrantChunkSearchService.search(vector, this.scope, 5)).thenReturn(expected);

        List<DocumentSearchResult> actual = service().search("问题", this.scope);

        assertThat(actual).isSameAs(expected);
        verify(this.qdrantChunkSearchService).search(vector, this.scope, 5);
    }

    @Test
    void delegatesWithExplicitTopK() {
        float[] vector = vector1024();
        when(this.queryEmbeddingService.embedQuery("问题")).thenReturn(vector);
        when(this.qdrantChunkSearchService.search(vector, this.scope, 10)).thenReturn(List.of());

        service().search("问题", this.scope, 10);

        verify(this.qdrantChunkSearchService).search(vector, this.scope, 10);
    }

    @Test
    void rejectsTopKAboveMaxBeforeEmbedding() {
        assertThatThrownBy(() -> service().search("问题", this.scope, 21))
                .isInstanceOf(DocumentSearchException.class)
                .hasMessageContaining("topK");
        verifyNoInteractions(this.queryEmbeddingService, this.qdrantChunkSearchService);
    }

    @Test
    void rejectsNonPositiveTopKBeforeEmbedding() {
        assertThatThrownBy(() -> service().search("问题", this.scope, 0))
                .isInstanceOf(DocumentSearchException.class)
                .hasMessageContaining("topK");
        verifyNoInteractions(this.queryEmbeddingService, this.qdrantChunkSearchService);
    }

    @Test
    void rejectsNullScope() {
        assertThatThrownBy(() -> service().search("问题", null, 5))
                .isInstanceOf(DocumentSearchException.class)
                .hasMessageContaining("检索范围");
        verifyNoInteractions(this.queryEmbeddingService, this.qdrantChunkSearchService);
    }

    private DocumentVectorSearchService service() {
        return new DocumentVectorSearchService(
                this.queryEmbeddingService, this.qdrantChunkSearchService, this.properties);
    }

    private static float[] vector1024() {
        float[] vector = new float[1024];
        vector[0] = 1.0f;
        return vector;
    }
}
