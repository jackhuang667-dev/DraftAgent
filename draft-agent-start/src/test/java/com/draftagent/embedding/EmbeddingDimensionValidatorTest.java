package com.draftagent.embedding;

import com.draftagent.ai.rag.embedding.EmbeddingDimensionMismatchException;
import com.draftagent.ai.rag.embedding.EmbeddingDimensionValidator;
import com.draftagent.ai.rag.embedding.RagEmbeddingProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 向量维度校验：以真实向量长度为准，不截断、不补齐。
 */
class EmbeddingDimensionValidatorTest {

    @Test
    void acceptsExpectedDimensions() {
        EmbeddingDimensionValidator validator = validator(1024);

        validator.validate(new float[1024]);
        validator.validate(List.of(new float[1024], new float[1024]));
    }

    @Test
    void rejectsUnexpectedDimensions() {
        EmbeddingDimensionValidator validator = validator(1024);

        assertThatThrownBy(() -> validator.validate(new float[1536]))
                .isInstanceOf(EmbeddingDimensionMismatchException.class)
                .hasMessageContaining("1024")
                .hasMessageContaining("1536");
    }

    @Test
    void rejectsNullAndEmpty() {
        EmbeddingDimensionValidator validator = validator(1024);

        assertThatThrownBy(() -> validator.validate((float[]) null))
                .isInstanceOf(EmbeddingDimensionMismatchException.class);
        assertThatThrownBy(() -> validator.validate(new float[0]))
                .isInstanceOf(EmbeddingDimensionMismatchException.class);
        assertThatThrownBy(() -> validator.validate((List<float[]>) null))
                .isInstanceOf(EmbeddingDimensionMismatchException.class);
    }

    @Test
    void listRejectsAnyMismatchedVector() {
        EmbeddingDimensionValidator validator = validator(1024);

        assertThatThrownBy(() -> validator.validate(List.of(new float[1024], new float[768])))
                .isInstanceOf(EmbeddingDimensionMismatchException.class)
                .hasMessageContaining("1");
    }

    @Test
    void customExpectedDimensionsAreHonored() {
        EmbeddingDimensionValidator validator = validator(768);

        validator.validate(new float[768]);
        assertThatThrownBy(() -> validator.validate(new float[1024]))
                .isInstanceOf(EmbeddingDimensionMismatchException.class)
                .hasMessageContaining("768");
    }

    @Test
    void errorMessageContainsNoSecretLikeText() {
        EmbeddingDimensionValidator validator = validator(1024);

        assertThatThrownBy(() -> validator.validate(new float[8]))
                .isInstanceOf(EmbeddingDimensionMismatchException.class)
                .satisfies(error -> assertThat(error.getMessage().toLowerCase())
                        .doesNotContain("api-key")
                        .doesNotContain("api_key")
                        .doesNotContain("bearer"));
    }

    private static EmbeddingDimensionValidator validator(int dimensions) {
        RagEmbeddingProperties properties = new RagEmbeddingProperties();
        properties.setDimensions(dimensions);
        return new EmbeddingDimensionValidator(properties);
    }
}
