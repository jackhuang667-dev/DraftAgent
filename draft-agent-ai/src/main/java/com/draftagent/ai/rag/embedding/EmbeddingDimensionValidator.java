package com.draftagent.ai.rag.embedding;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Embedding 维度校验：以真实返回向量长度为准（不依赖 {@code EmbeddingModel.dimensions()}），
 * 与配置 {@code rag.embedding.dimensions} 严格比较；不一致直接失败，禁止静默截断/补齐。
 */
@Component
public class EmbeddingDimensionValidator {

    private final int expectedDimensions;

    public EmbeddingDimensionValidator(RagEmbeddingProperties properties) {
        this.expectedDimensions = properties.getDimensions();
    }

    public int expectedDimensions() {
        return this.expectedDimensions;
    }

    public void validate(float[] embedding) {
        if (embedding == null || embedding.length == 0) {
            throw new EmbeddingDimensionMismatchException(
                    "Embedding 维度校验失败：向量为空（期望 " + this.expectedDimensions + " 维）");
        }
        if (embedding.length != this.expectedDimensions) {
            throw new EmbeddingDimensionMismatchException(
                    "Embedding 维度校验失败：期望 " + this.expectedDimensions + " 维，实际 " + embedding.length + " 维");
        }
    }

    public void validate(List<float[]> embeddings) {
        if (embeddings == null) {
            throw new EmbeddingDimensionMismatchException(
                    "Embedding 维度校验失败：向量列表为空（期望 " + this.expectedDimensions + " 维）");
        }
        for (int index = 0; index < embeddings.size(); index++) {
            float[] embedding = embeddings.get(index);
            if (embedding == null || embedding.length == 0) {
                throw new EmbeddingDimensionMismatchException(
                        "Embedding 维度校验失败：第 " + index + " 个向量为空（期望 " + this.expectedDimensions + " 维）");
            }
            if (embedding.length != this.expectedDimensions) {
                throw new EmbeddingDimensionMismatchException(
                        "Embedding 维度校验失败：第 " + index + " 个向量期望 " + this.expectedDimensions
                                + " 维，实际 " + embedding.length + " 维");
            }
        }
    }
}
