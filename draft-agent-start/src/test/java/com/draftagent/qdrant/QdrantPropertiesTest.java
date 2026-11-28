package com.draftagent.qdrant;

import com.draftagent.ai.rag.qdrant.RagQdrantProperties;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Qdrant 配置属性默认值与校验。
 */
class QdrantPropertiesTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void defaultsAreValid() {
        RagQdrantProperties properties = new RagQdrantProperties();

        assertThat(properties.getHost()).isEqualTo("localhost");
        assertThat(properties.getGrpcPort()).isEqualTo(6334);
        assertThat(properties.getCollection()).isEqualTo("draftagent_chunks_v1");
        assertThat(properties.getDimensions()).isEqualTo(1024);
        assertThat(properties.getDistance()).isEqualTo("Cosine");
        assertThat(properties.getTimeoutMs()).isEqualTo(10000);
        assertThat(properties.isInitializeOnStartup()).isFalse();
        assertThat(this.validator.validate(properties)).isEmpty();
    }

    @Test
    void zeroDimensionsIsRejected() {
        RagQdrantProperties properties = new RagQdrantProperties();
        properties.setDimensions(0);

        Set<ConstraintViolation<RagQdrantProperties>> violations = this.validator.validate(properties);

        assertThat(violations).anyMatch(violation ->
                violation.getPropertyPath().toString().equals("dimensions"));
    }

    @Test
    void invalidPortIsRejected() {
        RagQdrantProperties properties = new RagQdrantProperties();
        properties.setGrpcPort(0);
        assertThat(this.validator.validate(properties)).anyMatch(violation ->
                violation.getPropertyPath().toString().equals("grpcPort"));

        properties.setGrpcPort(70000);
        assertThat(this.validator.validate(properties)).anyMatch(violation ->
                violation.getPropertyPath().toString().equals("grpcPort"));
    }

    @Test
    void invalidDistanceIsRejected() {
        RagQdrantProperties properties = new RagQdrantProperties();
        properties.setDistance("Hamming");

        assertThat(this.validator.validate(properties)).anyMatch(violation ->
                violation.getPropertyPath().toString().equals("distance"));
    }

    @Test
    void nonPositiveTimeoutIsRejected() {
        RagQdrantProperties properties = new RagQdrantProperties();
        properties.setTimeoutMs(0);

        assertThat(this.validator.validate(properties)).anyMatch(violation ->
                violation.getPropertyPath().toString().equals("timeoutMs"));
    }
}
