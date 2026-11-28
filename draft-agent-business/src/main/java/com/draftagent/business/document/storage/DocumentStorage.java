package com.draftagent.business.document.storage;

import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;

/**
 * 本地文件存储端口。V1 仅提供本地磁盘实现，后续可替换为对象存储而无需改动业务流程。
 */
public interface DocumentStorage {

    /**
     * 安全落盘（内部生成 UUID 文件名，不使用用户原始文件名拼接路径）。
     *
     * @param file      上传文件
     * @param extension 已校验的扩展名（pdf/txt/md）
     */
    StoredDocumentFile store(MultipartFile file, String extension);

    /**
     * 把相对路径解析为绝对路径，并确保结果位于存储根目录内（防路径穿越）。
     */
    Path resolve(String relativePath);

    /**
     * 删除相对路径对应的文件（不存在则忽略）。
     */
    void delete(String relativePath);
}
