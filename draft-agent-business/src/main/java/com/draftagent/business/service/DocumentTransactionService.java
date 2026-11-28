package com.draftagent.business.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.draftagent.business.entity.Document;
import com.draftagent.business.entity.DocumentChunk;
import com.draftagent.business.mapper.DocumentChunkMapper;
import com.draftagent.business.mapper.DocumentMapper;
import com.draftagent.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 文档状态与分块的事务内操作。
 *
 * <p>拆分为独立服务，确保 {@code @Transactional} 代理生效（避免同类内自调用失效）。
 * 耗时的文件解析、Embedding 调用与 Qdrant 写入均不放在事务内。分块先以独立事务落库，
 * 向量写入成功后再以独立事务标记 READY；失败由 {@link #markFailed} 清理分块。</p>
 */
@Service
public class DocumentTransactionService {

    private static final int STATUS_CONFLICT = 409;

    private final DocumentMapper documentMapper;

    private final DocumentChunkMapper documentChunkMapper;

    public DocumentTransactionService(DocumentMapper documentMapper, DocumentChunkMapper documentChunkMapper) {
        this.documentMapper = documentMapper;
        this.documentChunkMapper = documentChunkMapper;
    }

    @Transactional
    public void insertPending(Document document) {
        this.documentMapper.insert(document);
    }

    /**
     * PENDING → PROCESSING 的 CAS 更新；返回是否成功抢占处理权。
     */
    @Transactional
    public boolean markProcessing(Long documentPk) {
        int updated = this.documentMapper.update(null,
                new LambdaUpdateWrapper<Document>()
                        .eq(Document::getDocumentPk, documentPk)
                        .eq(Document::getStatus, Document.STATUS_PENDING)
                        .set(Document::getStatus, Document.STATUS_PROCESSING)
                        .set(Document::getErrorMessage, null)
                        .setSql("updated_at = CURRENT_TIMESTAMP"));
        return updated == 1;
    }

    /**
     * 分块写入（文档状态保持 PROCESSING，等待向量写入成功后再标记 READY）。
     */
    @Transactional
    public void saveChunks(List<DocumentChunk> chunks) {
        for (DocumentChunk chunk : chunks) {
            this.documentChunkMapper.insert(chunk);
        }
    }

    /**
     * 读取已持久化的分块（含数据库生成的 chunk_id），按 chunk_index 升序。
     */
    @Transactional(readOnly = true)
    public List<DocumentChunk> listChunks(Long documentPk) {
        return this.documentChunkMapper.selectList(new LambdaQueryWrapper<DocumentChunk>()
                .eq(DocumentChunk::getDocumentPk, documentPk)
                .orderByAsc(DocumentChunk::getChunkIndex));
    }

    /**
     * PROCESSING → READY，同时写入 chunkCount。必须在向量写入成功后调用。
     */
    @Transactional
    public void markReady(Long documentPk, int chunkCount) {
        int updated = this.documentMapper.update(null,
                new LambdaUpdateWrapper<Document>()
                        .eq(Document::getDocumentPk, documentPk)
                        .eq(Document::getStatus, Document.STATUS_PROCESSING)
                        .set(Document::getStatus, Document.STATUS_READY)
                        .set(Document::getChunkCount, chunkCount)
                        .set(Document::getErrorMessage, null)
                        .setSql("updated_at = CURRENT_TIMESTAMP"));
        if (updated != 1) {
            throw new ServiceException(STATUS_CONFLICT, "文档状态更新冲突，无法标记为 READY");
        }
    }

    /**
     * 标记 FAILED，并清理可能存在的残留分块，确保失败不遗留不完整数据。
     */
    @Transactional
    public void markFailed(Long documentPk, String errorMessage) {
        if (documentPk == null) {
            return;
        }
        this.documentChunkMapper.delete(new LambdaQueryWrapper<DocumentChunk>()
                .eq(DocumentChunk::getDocumentPk, documentPk));
        this.documentMapper.update(null,
                new LambdaUpdateWrapper<Document>()
                        .eq(Document::getDocumentPk, documentPk)
                        .set(Document::getStatus, Document.STATUS_FAILED)
                        .set(Document::getErrorMessage, errorMessage)
                        .set(Document::getChunkCount, 0)
                        .setSql("updated_at = CURRENT_TIMESTAMP"));
    }
}
