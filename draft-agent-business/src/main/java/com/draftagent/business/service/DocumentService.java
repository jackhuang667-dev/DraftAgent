package com.draftagent.business.service;

import com.draftagent.business.document.DocumentContentParser;
import com.draftagent.business.document.DocumentFileType;
import com.draftagent.business.document.DocumentParseException;
import com.draftagent.business.document.DocumentVectorizationException;
import com.draftagent.business.document.DocumentVectorizer;
import com.draftagent.business.document.storage.DocumentStorage;
import com.draftagent.business.document.storage.StoredDocumentFile;
import com.draftagent.business.dto.DocumentUploadResponse;
import com.draftagent.business.entity.Document;
import com.draftagent.business.entity.DocumentChunk;
import com.draftagent.common.core.exception.ServiceException;
import com.draftagent.common.security.CurrentUser;
import com.draftagent.common.security.CurrentUserProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 文档上传与处理业务流程。
 *
 * <p>流程：校验 → 落盘 → 插入 PENDING → PROCESSING → 解析（事务外）→ 分块 + READY 原子提交。
 * 任一步失败：标记 FAILED（并清理残留分块）、删除已落盘文件，最终仅返回脱敏错误。
 * 身份只来自服务端 {@link CurrentUserProvider}，不接受任何客户端传入的 userId/tenantId。</p>
 */
@Service
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

    private static final int STATUS_BAD_REQUEST = 400;
    private static final int STATUS_UNAUTHORIZED = 401;
    private static final int STATUS_CONFLICT = 409;
    private static final int STATUS_UNPROCESSABLE = 422;
    private static final int STATUS_PAYLOAD_TOO_LARGE = 413;
    private static final int STATUS_SERVICE_UNAVAILABLE = 503;

    private static final int FILENAME_MAX_LENGTH = 255;

    private static final int ERROR_MESSAGE_MAX_LENGTH = 1000;

    private static final Set<String> PDF_MIME_TYPES = Set.of("application/pdf");

    private static final Set<String> TXT_MIME_TYPES = Set.of("text/plain");

    private static final Set<String> MARKDOWN_MIME_TYPES =
            Set.of("text/markdown", "text/x-markdown", "text/plain");

    private final DocumentStorage documentStorage;

    private final DocumentContentParser documentContentParser;

    private final DocumentTransactionService documentTransactionService;

    private final DocumentVectorizer documentVectorizer;

    private final CurrentUserProvider currentUserProvider;

    private final long maxFileSizeBytes;

    public DocumentService(DocumentStorage documentStorage,
                           DocumentContentParser documentContentParser,
                           DocumentTransactionService documentTransactionService,
                           DocumentVectorizer documentVectorizer,
                           CurrentUserProvider currentUserProvider,
                           @Value("${rag.document.max-file-size-bytes:20971520}") long maxFileSizeBytes) {
        this.documentStorage = documentStorage;
        this.documentContentParser = documentContentParser;
        this.documentTransactionService = documentTransactionService;
        this.documentVectorizer = documentVectorizer;
        this.currentUserProvider = currentUserProvider;
        this.maxFileSizeBytes = maxFileSizeBytes;
    }

    public DocumentUploadResponse upload(MultipartFile file) {
        CurrentUser currentUser = this.currentUserProvider.getCurrentUser();
        Long userId = currentUser == null ? null : currentUser.getUserId();
        if (userId == null) {
            throw new ServiceException(STATUS_UNAUTHORIZED, "未认证，无法上传文档");
        }
        Long tenantId = currentUser.getTenantId();

        ValidatedUpload validated = validate(file);
        StoredDocumentFile stored = this.documentStorage.store(file, validated.type.getExtension());

        Document document = null;
        Long documentPk = null;
        boolean ready = false;
        boolean vectorized = false;
        try {
            document = buildDocument(validated, stored, userId, tenantId);
            try {
                this.documentTransactionService.insertPending(document);
            }
            catch (DataAccessException e) {
                throw new ServiceException(STATUS_SERVICE_UNAVAILABLE, "数据库操作失败，暂无法上传文档", e);
            }
            documentPk = document.getDocumentPk();
            if (documentPk == null) {
                throw new ServiceException(STATUS_SERVICE_UNAVAILABLE, "文档创建失败，请稍后重试");
            }
            if (!this.documentTransactionService.markProcessing(documentPk)) {
                throw new ServiceException(STATUS_CONFLICT, "文档状态冲突，无法开始处理");
            }

            List<String> contents = parse(stored, validated.type);
            List<DocumentChunk> chunks = buildChunks(document, contents, LocalDateTime.now());

            // 1) 分块先落库，文档状态仍为 PROCESSING
            this.documentTransactionService.saveChunks(chunks);
            List<DocumentChunk> persistedChunks = this.documentTransactionService.listChunks(documentPk);
            if (persistedChunks.isEmpty() || persistedChunks.size() != chunks.size()) {
                throw new DocumentVectorizationException(
                        "文档分块持久化异常: document_id=" + document.getDocumentId());
            }

            // 2) 事务外：批量 Embedding + Qdrant Upsert
            int upserted = this.documentVectorizer.vectorize(document, persistedChunks);
            vectorized = true;
            if (upserted != persistedChunks.size()) {
                throw new DocumentVectorizationException(
                        "向量写入数量不一致: document_id=" + document.getDocumentId()
                                + ", expected=" + persistedChunks.size() + ", actual=" + upserted);
            }

            // 3) 向量写入成功后才标记 READY
            this.documentTransactionService.markReady(documentPk, persistedChunks.size());
            ready = true;
            return new DocumentUploadResponse(document.getDocumentId(), document.getFilename(),
                    document.getMimeType(), document.getFileSize(), Document.STATUS_READY,
                    persistedChunks.size());
        }
        catch (DocumentParseException e) {
            safeMarkFailed(documentPk, e.getMessage());
            throw new ServiceException(STATUS_UNPROCESSABLE, safeParseMessage(e));
        }
        catch (DocumentVectorizationException e) {
            // 向量实现侧已尽力按 document_id 补偿；此处仅处理“向量化成功后数量不一致”等场景
            if (vectorized && document != null) {
                safeDeleteVectors(document.getDocumentId());
            }
            safeMarkFailed(documentPk, e.getMessage());
            log.warn("文档向量化失败: documentId={}", document == null ? null : document.getDocumentId());
            throw new ServiceException(STATUS_SERVICE_UNAVAILABLE, "文档向量化失败，请稍后重试", e);
        }
        catch (RuntimeException e) {
            if (vectorized && document != null) {
                safeDeleteVectors(document.getDocumentId());
            }
            safeMarkFailed(documentPk, e.getMessage());
            if (e instanceof ServiceException serviceException) {
                throw serviceException;
            }
            log.warn("文档处理失败: {}", e.getClass().getSimpleName());
            throw new ServiceException(STATUS_SERVICE_UNAVAILABLE, "文档处理失败，请稍后重试", e);
        }
        finally {
            if (!ready) {
                this.documentStorage.delete(stored.getRelativePath());
            }
        }
    }

    private void safeDeleteVectors(String documentId) {
        try {
            this.documentVectorizer.deleteByDocumentId(documentId);
        }
        catch (RuntimeException e) {
            log.error("按 document_id 补偿删除向量失败: documentId={}", documentId);
        }
    }

    private Document buildDocument(ValidatedUpload validated, StoredDocumentFile stored, Long userId, Long tenantId) {
        LocalDateTime now = LocalDateTime.now();
        Document document = new Document();
        document.setDocumentId(UUID.randomUUID().toString());
        document.setUserId(userId);
        document.setTenantId(tenantId);
        document.setFilename(validated.filename);
        document.setStoragePath(stored.getRelativePath());
        document.setMimeType(validated.mimeType);
        document.setFileSize(stored.getFileSize());
        document.setChecksum(stored.getChecksum());
        document.setStatus(Document.STATUS_PENDING);
        document.setChunkCount(0);
        document.setCreatedAt(now);
        document.setUpdatedAt(now);
        return document;
    }

    private List<String> parse(StoredDocumentFile stored, DocumentFileType type) {
        List<String> parsed;
        try {
            parsed = this.documentContentParser.extractChunks(stored.getAbsolutePath(), type);
        }
        catch (DocumentParseException e) {
            throw e;
        }
        catch (RuntimeException e) {
            throw new DocumentParseException("文档解析失败", e);
        }
        List<String> contents = new ArrayList<>();
        if (parsed != null) {
            for (String chunk : parsed) {
                if (StringUtils.hasText(chunk)) {
                    contents.add(chunk);
                }
            }
        }
        if (contents.isEmpty()) {
            throw new DocumentParseException("未能从文档中提取到有效文本（扫描版 PDF 暂不支持）");
        }
        return contents;
    }

    private List<DocumentChunk> buildChunks(Document document, List<String> contents, LocalDateTime now) {
        List<DocumentChunk> chunks = new ArrayList<>(contents.size());
        for (int index = 0; index < contents.size(); index++) {
            String content = contents.get(index);
            DocumentChunk chunk = new DocumentChunk();
            chunk.setDocumentPk(document.getDocumentPk());
            chunk.setDocumentId(document.getDocumentId());
            chunk.setChunkIndex(index);
            chunk.setContent(content);
            chunk.setCharCount(content.length());
            chunk.setCreatedAt(now);
            chunks.add(chunk);
        }
        return chunks;
    }

    private ValidatedUpload validate(MultipartFile file) {
        if (file == null || file.isEmpty() || file.getSize() <= 0) {
            throw new ServiceException(STATUS_BAD_REQUEST, "上传文件不能为空");
        }
        String filename = sanitizeFilename(file.getOriginalFilename());
        String extension = extractExtension(filename);
        DocumentFileType type = DocumentFileType.fromExtension(extension)
                .orElseThrow(() -> new ServiceException(STATUS_BAD_REQUEST, "仅支持 TXT、Markdown、PDF 文件"));
        if (file.getSize() > this.maxFileSizeBytes) {
            throw new ServiceException(STATUS_PAYLOAD_TOO_LARGE, "文件大小超过限制（最大 " + maxSizeText() + "）");
        }
        String mimeType = normalizeMimeType(file.getContentType());
        if (!allowedMimeTypes(type).contains(mimeType)) {
            throw new ServiceException(STATUS_BAD_REQUEST, "文件 MIME 类型与扩展名不匹配");
        }
        return new ValidatedUpload(filename, mimeType, type);
    }

    private String sanitizeFilename(String originalFilename) {
        if (!StringUtils.hasText(originalFilename)) {
            throw new ServiceException(STATUS_BAD_REQUEST, "文件名不能为空");
        }
        String name = originalFilename.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        name = name.trim();
        if (!StringUtils.hasText(name)) {
            throw new ServiceException(STATUS_BAD_REQUEST, "文件名不能为空");
        }
        if (name.length() > FILENAME_MAX_LENGTH) {
            throw new ServiceException(STATUS_BAD_REQUEST, "文件名长度不能超过 " + FILENAME_MAX_LENGTH + " 个字符");
        }
        return name;
    }

    private String extractExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            throw new ServiceException(STATUS_BAD_REQUEST, "文件缺少扩展名");
        }
        return filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private String normalizeMimeType(String contentType) {
        if (!StringUtils.hasText(contentType)) {
            throw new ServiceException(STATUS_BAD_REQUEST, "无法识别文件 MIME 类型");
        }
        int semicolon = contentType.indexOf(';');
        String mimeType = semicolon >= 0 ? contentType.substring(0, semicolon) : contentType;
        return mimeType.trim().toLowerCase(Locale.ROOT);
    }

    private Set<String> allowedMimeTypes(DocumentFileType type) {
        return switch (type) {
            case PDF -> PDF_MIME_TYPES;
            case TXT -> TXT_MIME_TYPES;
            case MARKDOWN -> MARKDOWN_MIME_TYPES;
        };
    }

    private String maxSizeText() {
        long megabyte = 1024L * 1024L;
        if (this.maxFileSizeBytes > 0 && this.maxFileSizeBytes % megabyte == 0) {
            return (this.maxFileSizeBytes / megabyte) + "MB";
        }
        return this.maxFileSizeBytes + " 字节";
    }

    private void safeMarkFailed(Long documentPk, String message) {
        if (documentPk == null) {
            return;
        }
        try {
            this.documentTransactionService.markFailed(documentPk, sanitizeErrorMessage(message));
        }
        catch (RuntimeException e) {
            log.warn("标记文档 FAILED 时发生异常: {}", e.getClass().getSimpleName());
        }
    }

    private String safeParseMessage(DocumentParseException e) {
        String message = e.getMessage();
        return StringUtils.hasText(message) ? message : "文档解析失败";
    }

    private String sanitizeErrorMessage(String message) {
        String safe = StringUtils.hasText(message) ? message : "文档处理失败";
        return safe.length() > ERROR_MESSAGE_MAX_LENGTH ? safe.substring(0, ERROR_MESSAGE_MAX_LENGTH) : safe;
    }

    private static final class ValidatedUpload {

        private final String filename;

        private final String mimeType;

        private final DocumentFileType type;

        private ValidatedUpload(String filename, String mimeType, DocumentFileType type) {
            this.filename = filename;
            this.mimeType = mimeType;
            this.type = type;
        }
    }
}
