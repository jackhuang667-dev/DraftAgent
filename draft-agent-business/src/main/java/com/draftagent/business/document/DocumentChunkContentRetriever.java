package com.draftagent.business.document;

/**
 * MySQL 原文回查端口：把 Qdrant 检索命中还原为经过权限复核的 Chunk 原文。
 *
 * <p>由 business 模块实现（MySQL 权威数据），ai 模块只依赖该接口。实现必须：
 * 以 {@link DocumentSearchScope} 作为唯一权限依据、只返回 READY 文档、批量查询、
 * 并按 Qdrant 原始结果顺序输出。</p>
 */
public interface DocumentChunkContentRetriever {

    /**
     * @return 按 Qdrant 原始顺序排列、通过权限与元数据校验的 Chunk 原文（可能少于输入）
     * @throws DocumentSearchException scope 非法或 MySQL 查询失败
     */
    java.util.List<RetrievedDocumentChunk> retrieve(
            java.util.List<DocumentSearchResult> results, DocumentSearchScope scope);
}
