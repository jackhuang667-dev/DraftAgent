package com.draftagent.ai.rag.qdrant;

/**
 * Qdrant Collection 管理端口（便于单元测试 Mock，隔离底层 Client）。
 *
 * <p>本端口不提供删除 Collection 的能力，初始化器因此无法执行任何删除/清空操作。</p>
 */
public interface QdrantCollectionAdmin {

    boolean collectionExists(String collection);

    QdrantCollectionState getCollectionState(String collection);

    void createCollection(String collection, int dimensions, String distance);

    void createIntegerPayloadIndex(String collection, String fieldName);

    void createUuidPayloadIndex(String collection, String fieldName);
}
