package com.draftagent.business.document;

/**
 * 通过 MySQL 权限复核后的 Chunk 原文结果。
 *
 * <p>包含 MySQL 中的权威原文与元数据，以及 Qdrant 返回的原始相似度分数。
 * 仅当 Qdrant 元数据与 MySQL 中的 document/chunk 完全一致且文档为 READY、
 * 当前用户有权访问时才会生成该对象。</p>
 */
public final class RetrievedDocumentChunk {

    private final String documentId;

    private final Long documentPk;

    private final Long chunkId;

    private final Integer chunkIndex;

    private final String content;

    private final float score;

    public RetrievedDocumentChunk(String documentId, Long documentPk, Long chunkId,
                                  Integer chunkIndex, String content, float score) {
        this.documentId = documentId;
        this.documentPk = documentPk;
        this.chunkId = chunkId;
        this.chunkIndex = chunkIndex;
        this.content = content;
        this.score = score;
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

    public String getContent() {
        return content;
    }

    public float getScore() {
        return score;
    }
}
