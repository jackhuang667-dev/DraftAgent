package com.draftagent.business.document;

import com.draftagent.business.entity.Document;
import com.draftagent.business.entity.DocumentChunk;

import java.util.List;

/**
 * 文档向量化端口：把已持久化的 Chunk 文本转换为向量并写入向量库。
 *
 * <p>由 AI 模块实现（EmbeddingModel + Qdrant），业务模块只依赖该接口，保持
 * {@code business} 不反向依赖 {@code ai}。实现内部负责失败补偿（按 document_id 删除已写入向量）。</p>
 */
public interface DocumentVectorizer {

    /**
     * 对文档的全部 Chunk 进行批量 Embedding 并写入向量库。
     *
     * @return 成功写入的向量数量
     * @throws DocumentVectorizationException 向量化或写入失败（已尽力补偿）
     */
    int vectorize(Document document, List<DocumentChunk> chunks);

    /**
     * 按 document_id 删除该文档的全部向量（用于向量化后续步骤失败时的补偿）。
     */
    void deleteByDocumentId(String documentId);
}
