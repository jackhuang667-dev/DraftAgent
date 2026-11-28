package com.draftagent.vector;

import com.draftagent.ai.rag.embedding.EmbeddingDimensionMismatchException;
import com.draftagent.ai.rag.embedding.EmbeddingDimensionValidator;
import com.draftagent.ai.rag.vector.DocumentEmbeddingService;
import com.draftagent.business.document.DocumentVectorizationException;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 批量 Embedding 封装测试（Mock EmbeddingModel，不调用真实 API）。
 */
class DocumentEmbeddingServiceTest {

    @SuppressWarnings("unchecked")
    private static ObjectProvider<EmbeddingModel> provider(EmbeddingModel embeddingModel) {
        ObjectProvider<EmbeddingModel> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(embeddingModel);
        return provider;
    }

    @Test
    void embedsBatchInSingleModelCall() {
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        EmbeddingDimensionValidator validator = mock(EmbeddingDimensionValidator.class);
        List<String> texts = List.of("第一段", "第二段");
        List<float[]> vectors = List.of(new float[1024], new float[1024]);
        when(embeddingModel.embed(texts)).thenReturn(vectors);
        DocumentEmbeddingService service = new DocumentEmbeddingService(provider(embeddingModel), validator);

        List<float[]> result = service.embed(texts);

        assertThat(result).hasSize(2);
        verify(embeddingModel).embed(texts);
        verify(validator).validate(vectors);
    }

    @Test
    void missingEmbeddingModelFailsClearly() {
        DocumentEmbeddingService service = new DocumentEmbeddingService(provider(null), mock(EmbeddingDimensionValidator.class));

        assertThatThrownBy(() -> service.embed(List.of("文本")))
                .isInstanceOf(DocumentVectorizationException.class)
                .hasMessageContaining("EMBEDDING_API_KEY");
    }

    @Test
    void countMismatchFails() {
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        when(embeddingModel.embed(anyList())).thenReturn(List.of(new float[1024]));
        DocumentEmbeddingService service = new DocumentEmbeddingService(
                provider(embeddingModel), mock(EmbeddingDimensionValidator.class));

        assertThatThrownBy(() -> service.embed(List.of("一", "二")))
                .isInstanceOf(DocumentVectorizationException.class)
                .hasMessageContaining("数量");
    }

    @Test
    void dimensionMismatchFails() {
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        List<String> texts = List.of("文本");
        List<float[]> vectors = List.of(new float[768]);
        when(embeddingModel.embed(texts)).thenReturn(vectors);
        EmbeddingDimensionValidator validator = mock(EmbeddingDimensionValidator.class);
        doThrow(new EmbeddingDimensionMismatchException("期望 1024 维，实际 768 维"))
                .when(validator).validate(vectors);
        DocumentEmbeddingService service = new DocumentEmbeddingService(provider(embeddingModel), validator);

        assertThatThrownBy(() -> service.embed(texts))
                .isInstanceOf(DocumentVectorizationException.class)
                .hasMessageContaining("1024");
    }

    @Test
    void emptyInputDoesNotCallModel() {
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        DocumentEmbeddingService service = new DocumentEmbeddingService(
                provider(embeddingModel), mock(EmbeddingDimensionValidator.class));

        assertThat(service.embed(List.of())).isEmpty();
        verify(embeddingModel, never()).embed(anyList());
        verifyNoInteractions(embeddingModel);
    }
}
