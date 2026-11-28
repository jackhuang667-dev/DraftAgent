package com.draftagent.business.service;

import com.draftagent.business.document.DocumentChunkContent;
import com.draftagent.business.document.DocumentChunkContentRetriever;
import com.draftagent.business.document.DocumentSearchException;
import com.draftagent.business.document.DocumentSearchResult;
import com.draftagent.business.document.DocumentSearchScope;
import com.draftagent.business.document.RetrievedDocumentChunk;
import com.draftagent.business.entity.Document;
import com.draftagent.business.mapper.DocumentChunkMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * MySQL 原文回查与二次权限校验。
 *
 * <p>以 MySQL {@code document} / {@code document_chunk} 为权威：一次 JOIN 批量查询（按 chunk_id IN），
 * 只返回 READY 且属于服务端 {@link DocumentSearchScope} 的 Chunk。随后逐条核对 Qdrant 元数据
 * （chunkId/documentId/documentPk/chunkIndex）与 MySQL 是否一致，不一致、不存在、无权访问或
 * 非 READY 的记录一律丢弃，不补位、不泄露存在性。最终按 Qdrant 原始顺序输出。</p>
 */
@Service
public class DocumentChunkContentService implements DocumentChunkContentRetriever {

    private static final Logger log = LoggerFactory.getLogger(DocumentChunkContentService.class);

    private final DocumentChunkMapper documentChunkMapper;

    public DocumentChunkContentService(DocumentChunkMapper documentChunkMapper) {
        this.documentChunkMapper = documentChunkMapper;
    }

    @Override
    public List<RetrievedDocumentChunk> retrieve(List<DocumentSearchResult> results, DocumentSearchScope scope) {
        if (scope == null) {
            throw new DocumentSearchException("检索范围不能为空，拒绝原文回查");
        }
        if (results == null || results.isEmpty()) {
            return List.of();
        }

        LinkedHashSet<Long> chunkIds = new LinkedHashSet<>();
        for (DocumentSearchResult result : results) {
            if (result != null && result.getChunkId() != null) {
                chunkIds.add(result.getChunkId());
            }
        }
        if (chunkIds.isEmpty()) {
            return List.of();
        }

        List<DocumentChunkContent> rows;
        try {
            rows = this.documentChunkMapper.selectReadyChunkContents(
                    new ArrayList<>(chunkIds),
                    scope.getUserId(),
                    scope.getTenantId(),
                    Document.STATUS_READY);
        }
        catch (DataAccessException e) {
            log.error("MySQL 原文回查失败: chunkIds={}", chunkIds.size(), e);
            throw new DocumentSearchException("原文回查失败，请稍后重试", e);
        }

        Map<Long, DocumentChunkContent> byChunkId = new HashMap<>();
        for (DocumentChunkContent row : rows) {
            if (row != null && row.getChunkId() != null) {
                byChunkId.putIfAbsent(row.getChunkId(), row);
            }
        }

        List<RetrievedDocumentChunk> retrieved = new ArrayList<>();
        for (DocumentSearchResult result : results) {
            if (result == null || result.getChunkId() == null) {
                continue;
            }
            DocumentChunkContent row = byChunkId.get(result.getChunkId());
            if (row == null) {
                // 不存在、非 READY 或当前范围无权访问：统一按未命中处理，不向调用方区分原因
                log.debug("检索命中未通过 MySQL 权限/状态校验，已丢弃: chunkId={}", result.getChunkId());
                continue;
            }
            if (!internallyConsistent(row)) {
                log.warn("document 与 document_chunk 的 document_id 不一致，已丢弃: chunkId={}, documentPk={}",
                        row.getChunkId(), row.getDocumentPk());
                continue;
            }
            if (!metadataMatches(result, row)) {
                log.warn("Qdrant 与 MySQL 元数据不一致，已丢弃: chunkId={}, qdrantDocumentId={}, "
                                + "mysqlDocumentId={}, qdrantDocumentPk={}, mysqlDocumentPk={}, "
                                + "qdrantChunkIndex={}, mysqlChunkIndex={}",
                        result.getChunkId(), result.getDocumentId(), row.getDocumentId(),
                        result.getDocumentPk(), row.getDocumentPk(),
                        result.getChunkIndex(), row.getChunkIndex());
                continue;
            }
            if (row.getContent() == null) {
                log.warn("MySQL Chunk 正文缺失，已丢弃: chunkId={}", row.getChunkId());
                continue;
            }
            retrieved.add(new RetrievedDocumentChunk(
                    row.getDocumentId(), row.getDocumentPk(), row.getChunkId(),
                    row.getChunkIndex(), row.getContent(), result.getScore()));
        }
        return retrieved;
    }

    private boolean internallyConsistent(DocumentChunkContent row) {
        return row.getChunkDocumentId() == null
                || Objects.equals(row.getChunkDocumentId(), row.getDocumentId());
    }

    private boolean metadataMatches(DocumentSearchResult result, DocumentChunkContent row) {
        return Objects.equals(result.getChunkId(), row.getChunkId())
                && Objects.equals(result.getDocumentId(), row.getDocumentId())
                && Objects.equals(result.getDocumentPk(), row.getDocumentPk())
                && Objects.equals(result.getChunkIndex(), row.getChunkIndex());
    }
}
