package com.draftagent.embedding;

import com.draftagent.ai.rag.embedding.RagEmbeddingProperties;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 配置属性约束：dimensions / 超时必须大于 0。
 */
class EmbeddingPropertiesValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void defaultsAreValid() {
        Set<ConstraintViolation<RagEmbeddingProperties>> violations =
                this.validator.validate(new RagEmbeddingProperties());
        assertThat(violations).isEmpty();
    }

    @Test
    void zeroDimensionsIsRejected() {
        RagEmbeddingProperties properties = new RagEmbeddingProperties();
        properties.setDimensions(0);

        Set<ConstraintViolation<RagEmbeddingProperties>> violations = this.validator.validate(properties);

        assertThat(violations).anyMatch(violation ->
                violation.getPropertyPath().toString().equals("dimensions"));
    }

    @Test
    void nonPositiveTimeoutsAreRejected() {
        RagEmbeddingProperties properties = new RagEmbeddingProperties();
        properties.setConnectTimeoutMs(0);
        properties.setReadTimeoutMs(-1);

        Set<ConstraintViolation<RagEmbeddingProperties>> violations = this.validator.validate(properties);

        assertThat(violations).extracting(violation -> violation.getPropertyPath().toString())
                .contains("connectTimeoutMs", "readTimeoutMs");
    }
}
