package com.draftagent.business.document;

/**
 * 向量检索命中的单个 Chunk（不包含 Chunk 正文）。
 *
 * <p>字段均来自 Qdrant Search 结果的 Point ID 与 Payload；{@code score} 为 Qdrant 返回的原始相似度分数，
 * 不做百分比换算。Chunk 原文需由后续批次（3.5.2）按 {@code chunkId} 回查 MySQL 获取。</p>
 */
public final class DocumentSearchResult {

    private final Long pointId;

    private final String documentId;

    private final Long documentPk;

    private final Long chunkId;

    private final Integer chunkIndex;

    private final float score;

    public DocumentSearchResult(Long pointId, String documentId, Long documentPk,
                                Long chunkId, Integer chunkIndex, float score) {
        this.pointId = pointId;
        this.documentId = documentId;
        this.documentPk = documentPk;
        this.chunkId = chunkId;
        this.chunkIndex = chunkIndex;
        this.score = score;
    }

    public Long getPointId() {
        return pointId;
    }

    public String getDocumentId() {
        return documentId;
    }

    public Long getDocumentPk() {
        return documentPk;
    }

    public Long getChunkId() {
        return chunkId;
    }

    public Integer getChunkIndex() {
        return chunkIndex;
    }

    public float getScore() {
        return score;
    }
}
