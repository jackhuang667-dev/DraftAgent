package com.draftagent.retrieval;

import com.draftagent.ai.rag.embedding.EmbeddingDimensionValidator;
import com.draftagent.ai.rag.embedding.RagEmbeddingProperties;
import com.draftagent.ai.rag.retrieval.QueryEmbeddingService;
import com.draftagent.business.document.DocumentSearchException;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 查询文本 → 1024 维向量（Mock EmbeddingModel，不调用真实 API）。
 */
class QueryEmbeddingServiceTest {

    @SuppressWarnings("unchecked")
    private final ObjectProvider<EmbeddingModel> provider = mock(ObjectProvider.class);

    private final EmbeddingModel embeddingModel = mock(EmbeddingModel.class);

    @Test
    void embedsQueryTo1024Dimensions() {
        when(this.provider.getIfAvailable()).thenReturn(this.embeddingModel);
        when(this.embeddingModel.embed("如何创建草案")).thenReturn(new float[1024]);

        float[] vector = service().embedQuery("如何创建草案");

        assertThat(vector).hasSize(1024);
        verify(this.embeddingModel).embed("如何创建草案");
    }

    @Test
    void wrongDimensionIsRejected() {
        when(this.provider.getIfAvailable()).thenReturn(this.embeddingModel);
        when(this.embeddingModel.embed(anyString())).thenReturn(new float[512]);

        assertThatThrownBy(() -> service().embedQuery("问题"))
                .isInstanceOf(DocumentSearchException.class)
                .hasMessageContaining("维度");
    }

    @Test
    void missingEmbeddingModelFailsExplicitly() {
        when(this.provider.getIfAvailable()).thenReturn(null);

        assertThatThrownBy(() -> service().embedQuery("问题"))
                .isInstanceOf(DocumentSearchException.class)
                .hasMessageContaining("EMBEDDING_API_KEY");
        verifyNoInteractions(this.embeddingModel);
    }

    @Test
    void embeddingFailureIsWrapped() {
        when(this.provider.getIfAvailable()).thenReturn(this.embeddingModel);
        when(this.embeddingModel.embed(anyString())).thenThrow(new IllegalStateException("upstream down"));

        assertThatThrownBy(() -> service().embedQuery("问题"))
                .isInstanceOf(DocumentSearchException.class)
                .hasMessageContaining("查询向量生成失败");
    }

    @Test
    void blankQueryIsRejectedWithoutCallingModel() {
        assertThatThrownBy(() -> service().embedQuery("   "))
                .isInstanceOf(DocumentSearchException.class)
                .hasMessageContaining("不能为空");
        verify(this.embeddingModel, never()).embed(anyString());
    }

    private QueryEmbeddingService service() {
        RagEmbeddingProperties properties = new RagEmbeddingProperties();
        properties.setDimensions(1024);
        return new QueryEmbeddingService(this.provider, new EmbeddingDimensionValidator(properties));
    }
}
