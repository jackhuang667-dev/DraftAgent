package com.draftagent.business.document;

/**
 * `document_chunk JOIN document` 批量查询的数据库行（内部使用）。
 *
 * <p>{@code documentId} 来自 document 表（权威）；{@code chunkDocumentId} 为 document_chunk
 * 冗余列，用于检测两侧 document_id 是否一致。</p>
 */
public class DocumentChunkContent {

    private String documentId;

    private String chunkDocumentId;

    private Long documentPk;

    private Long chunkId;

    private Integer chunkIndex;

    private String content;

    public String getDocumentId() {
        return documentId;
    }

    public void setDocumentId(String documentId) {
        this.documentId = documentId;
    }

    public String getChunkDocumentId() {
        return chunkDocumentId;
    }

    public void setChunkDocumentId(String chunkDocumentId) {
        this.chunkDocumentId = chunkDocumentId;
    }

    public Long getDocumentPk() {
        return documentPk;
    }

    public void setDocumentPk(Long documentPk) {
        this.documentPk = documentPk;
    }

    public Long getChunkId() {
        return chunkId;
    }

    public void setChunkId(Long chunkId) {
        this.chunkId = chunkId;
    }

    public Integer getChunkIndex() {
        return chunkIndex;
    }

    public void setChunkIndex(Integer chunkIndex) {
        this.chunkIndex = chunkIndex;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }
}
