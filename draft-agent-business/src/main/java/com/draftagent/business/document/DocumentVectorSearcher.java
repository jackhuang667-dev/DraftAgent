package com.draftagent.business.document;

import java.util.List;

/**
 * 文档向量检索端口：输入用户问题与服务端检索范围，返回 Top-K 相关 Chunk 元数据。
 *
 * <p>由 AI 模块实现（EmbeddingModel + Qdrant），业务模块只依赖该接口，保持
 * {@code business} 不反向依赖 {@code ai}。实现必须始终按 {@link DocumentSearchScope}
 * 构造向量库过滤条件，禁止无过滤检索。</p>
 */
public interface DocumentVectorSearcher {

    /**
     * 使用配置的默认 Top-K 检索。
     */
    List<DocumentSearchResult> search(String query, DocumentSearchScope scope);

    /**
     * 按指定 Top-K 检索。
     *
     * @throws DocumentSearchException 参数非法、Embedding 不可用/失败、Qdrant 检索失败或返回非法
     */
    List<DocumentSearchResult> search(String query, DocumentSearchScope scope, int topK);
}
