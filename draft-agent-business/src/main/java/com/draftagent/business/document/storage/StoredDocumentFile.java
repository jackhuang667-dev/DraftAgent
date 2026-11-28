package com.draftagent.business.document.storage;

import java.nio.file.Path;

/**
 * 已落盘文件的描述：相对路径（入库）、绝对路径（解析用）、大小与 SHA-256 校验和。
 */
public class StoredDocumentFile {

    private final String relativePath;

    private final Path absolutePath;

    private final long fileSize;

    private final String checksum;

    public StoredDocumentFile(String relativePath, Path absolutePath, long fileSize, String checksum) {
        this.relativePath = relativePath;
        this.absolutePath = absolutePath;
        this.fileSize = fileSize;
        this.checksum = checksum;
    }

    public String getRelativePath() {
        return relativePath;
    }

    public Path getAbsolutePath() {
        return absolutePath;
    }

    public long getFileSize() {
        return fileSize;
    }

    public String getChecksum() {
        return checksum;
    }
}
