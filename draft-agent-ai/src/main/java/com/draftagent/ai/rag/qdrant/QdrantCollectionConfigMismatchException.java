package com.draftagent.ai.rag.qdrant;

/**
 * 已存在的 Qdrant Collection 与期望配置（维度/距离）不一致。
 *
 * <p>初始化器不会自动删除、重建或清空已有 Collection，遇到此异常需人工处理。</p>
 */
public class QdrantCollectionConfigMismatchException extends RuntimeException {

    public QdrantCollectionConfigMismatchException(String message) {
        super(message);
    }
}
