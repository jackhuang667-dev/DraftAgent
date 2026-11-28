package com.draftagent.retrieval;

import com.draftagent.ai.rag.retrieval.RagRetrievalProperties;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 检索配置默认值与校验。
 */
class RagRetrievalPropertiesTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void defaultsAreValid() {
        RagRetrievalProperties properties = new RagRetrievalProperties();

        assertThat(properties.getTopK()).isEqualTo(5);
        assertThat(properties.getMaxTopK()).isEqualTo(20);
        assertThat(properties.getMaxContextChars()).isEqualTo(12000);
        assertThat(this.validator.validate(properties)).isEmpty();
    }

    @Test
    void nonPositiveMaxContextCharsIsRejected() {
        RagRetrievalProperties properties = new RagRetrievalProperties();
        properties.setMaxContextChars(0);

        assertThat(this.validator.validate(properties)).anyMatch(violation ->
                violation.getPropertyPath().toString().equals("maxContextChars"));
    }

    @Test
    void nonPositiveTopKIsRejected() {
        RagRetrievalProperties properties = new RagRetrievalProperties();
        properties.setTopK(0);

        assertThat(this.validator.validate(properties)).anyMatch(violation ->
                violation.getPropertyPath().toString().equals("topK"));
    }

    @Test
    void nonPositiveMaxTopKIsRejected() {
        RagRetrievalProperties properties = new RagRetrievalProperties();
        properties.setMaxTopK(0);

        assertThat(this.validator.validate(properties)).anyMatch(violation ->
                violation.getPropertyPath().toString().equals("maxTopK"));
    }

    @Test
    void topKGreaterThanMaxTopKIsRejected() {
        RagRetrievalProperties properties = new RagRetrievalProperties();
        properties.setTopK(21);
        properties.setMaxTopK(20);

        Set<ConstraintViolation<RagRetrievalProperties>> violations = this.validator.validate(properties);

        assertThat(violations).anyMatch(violation ->
                violation.getPropertyPath().toString().equals("topKWithinMaxTopK"));
    }
}
