package com.draftagent.ai.rag.embedding;

/**
 * Embedding 向量维度与配置不一致时抛出。消息为脱敏文本，不包含 API Key。
 */
public class EmbeddingDimensionMismatchException extends RuntimeException {

    public EmbeddingDimensionMismatchException(String message) {
        super(message);
    }
}
