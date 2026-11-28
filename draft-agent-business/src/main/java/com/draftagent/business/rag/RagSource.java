package com.draftagent.business.rag;

/**
 * RAG 回答的引用来源（由后端根据真实、已授权的 Chunk 生成，模型不能编造）。
 *
 * <p>{@code referenceId} 为本次请求内的引用编号（从 1 开始），与 Prompt 中的 [1]、[2] 对应。</p>
 */
public final class RagSource {

    private final int referenceId;

    private final String documentId;

    private final Long chunkId;

    private final Integer chunkIndex;

    private final float score;

    public RagSource(int referenceId, String documentId, Long chunkId,
                     Integer chunkIndex, float score) {
        this.referenceId = referenceId;
        this.documentId = documentId;
        this.chunkId = chunkId;
        this.chunkIndex = chunkIndex;
        this.score = score;
    }

    public int getReferenceId() {
        return referenceId;
    }

    public String getDocumentId() {
        return documentId;
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
