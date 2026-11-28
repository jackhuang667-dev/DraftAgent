package com.draftagent.business.dto;

/**
 * 文档上传结果。
 */
public class DocumentUploadResponse {

    private final String documentId;

    private final String filename;

    private final String mimeType;

    private final Long fileSize;

    private final String status;

    private final Integer chunkCount;

    public DocumentUploadResponse(String documentId, String filename, String mimeType,
                                  Long fileSize, String status, Integer chunkCount) {
        this.documentId = documentId;
        this.filename = filename;
        this.mimeType = mimeType;
        this.fileSize = fileSize;
        this.status = status;
        this.chunkCount = chunkCount;
    }

    public String getDocumentId() {
        return documentId;
    }

    public String getFilename() {
        return filename;
    }

    public String getMimeType() {
        return mimeType;
    }

    public Long getFileSize() {
        return fileSize;
    }

    public String getStatus() {
        return status;
    }

    public Integer getChunkCount() {
        return chunkCount;
    }
}
