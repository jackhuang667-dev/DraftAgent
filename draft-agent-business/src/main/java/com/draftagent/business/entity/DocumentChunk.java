package com.draftagent.business.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * RAG 文档分块实体，对应数据库表 {@code document_chunk}。
 *
 * <p>{@code documentPk} 关联 {@link Document#getDocumentPk()}（内部主键），
 * {@code documentId} 为业务 UUID 冗余列，便于与向量存储关联。
 * 本实体仅承载分块文本与元数据，不包含 Embedding 或向量存储逻辑。</p>
 */
@TableName("document_chunk")
public class DocumentChunk {

    @TableId(value = "chunk_id", type = IdType.AUTO)
    private Long chunkId;

    @TableField("document_pk")
    private Long documentPk;

    @TableField("document_id")
    private String documentId;

    @TableField("chunk_index")
    private Integer chunkIndex;

    @TableField("content")
    private String content;

    @TableField("char_count")
    private Integer charCount;

    @TableField("created_at")
    private LocalDateTime createdAt;

    public Long getChunkId() {
        return chunkId;
    }

    public void setChunkId(Long chunkId) {
        this.chunkId = chunkId;
    }

    public Long getDocumentPk() {
        return documentPk;
    }

    public void setDocumentPk(Long documentPk) {
        this.documentPk = documentPk;
    }

    public String getDocumentId() {
        return documentId;
    }

    public void setDocumentId(String documentId) {
        this.documentId = documentId;
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

    public Integer getCharCount() {
        return charCount;
    }

    public void setCharCount(Integer charCount) {
        this.charCount = charCount;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
