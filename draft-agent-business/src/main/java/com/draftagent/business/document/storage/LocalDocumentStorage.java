package com.draftagent.business.document.storage;

import com.draftagent.common.core.exception.ServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.UUID;

/**
 * 本地磁盘存储实现。
 *
 * <p>安全策略：磁盘文件名使用 UUID，按日期分目录，原始文件名只入库不参与路径拼接；
 * {@link #resolve(String)} 会校验解析结果必须位于根目录内，防止路径穿越。</p>
 */
@Component
public class LocalDocumentStorage implements DocumentStorage {

    private static final Logger log = LoggerFactory.getLogger(LocalDocumentStorage.class);

    private static final DateTimeFormatter DATE_PATH = DateTimeFormatter.ofPattern("yyyy/MM/dd");

    private static final String SHA_256 = "SHA-256";

    private final Path root;

    public LocalDocumentStorage(@Value("${rag.storage.root:./data/documents}") String storageRoot) {
        this.root = Paths.get(storageRoot).toAbsolutePath().normalize();
    }

    @Override
    public StoredDocumentFile store(MultipartFile file, String extension) {
        String relativePath = LocalDate.now().format(DATE_PATH) + "/" + UUID.randomUUID() + "." + extension;
        Path target = resolve(relativePath);
        try {
            Files.createDirectories(target.getParent());
            MessageDigest digest = MessageDigest.getInstance(SHA_256);
            long size;
            try (InputStream input = file.getInputStream();
                 DigestInputStream digestInput = new DigestInputStream(input, digest)) {
                size = Files.copy(digestInput, target, StandardCopyOption.REPLACE_EXISTING);
            }
            String checksum = HexFormat.of().formatHex(digest.digest());
            return new StoredDocumentFile(relativePath, target, size, checksum);
        }
        catch (IOException | NoSuchAlgorithmException e) {
            deleteQuietly(target);
            log.warn("文件保存失败: {}", e.getClass().getSimpleName());
            throw new ServiceException(503, "文件保存失败，暂无法上传文档", e);
        }
    }

    @Override
    public Path resolve(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("存储路径不能为空");
        }
        Path resolved = this.root.resolve(relativePath).normalize();
        if (!resolved.startsWith(this.root)) {
            throw new IllegalArgumentException("非法的存储路径");
        }
        return resolved;
    }

    @Override
    public void delete(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return;
        }
        try {
            Files.deleteIfExists(resolve(relativePath));
        }
        catch (IOException | RuntimeException e) {
            log.warn("删除文件失败: {}", e.getClass().getSimpleName());
        }
    }

    private void deleteQuietly(Path target) {
        try {
            Files.deleteIfExists(target);
        }
        catch (IOException ignored) {
            // 清理失败不影响向调用方返回统一错误
        }
    }
}
