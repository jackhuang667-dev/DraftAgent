package com.draftagent.ai.rag.qdrant;

/**
 * 现有 Qdrant Collection 的向量配置快照（仅单向量配置）。
 */
public class QdrantCollectionState {

    private final long vectorSize;

    private final String distance;

    public QdrantCollectionState(long vectorSize, String distance) {
        this.vectorSize = vectorSize;
        this.distance = distance;
    }

    public long getVectorSize() {
        return vectorSize;
    }

    public String getDistance() {
        return distance;
    }
}
