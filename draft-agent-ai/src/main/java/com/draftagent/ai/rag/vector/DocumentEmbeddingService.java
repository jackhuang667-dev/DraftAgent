package com.draftagent.ai.rag.vector;

import com.draftagent.ai.rag.embedding.EmbeddingDimensionMismatchException;
import com.draftagent.ai.rag.embedding.EmbeddingDimensionValidator;
import com.draftagent.business.document.DocumentVectorizationException;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Chunk 文本 → 向量（批量调用 EmbeddingModel，校验数量与维度）。
 *
 * <p>EmbeddingModel 来自已有独立 Bean；未配置 EMBEDDING_API_KEY 时该 Bean 不存在，
 * 此处抛出明确业务异常，而不是在启动阶段失败或自行创建模型。</p>
 */
@Service
public class DocumentEmbeddingService {

    private final ObjectProvider<EmbeddingModel> embeddingModelProvider;

    private final EmbeddingDimensionValidator dimensionValidator;

    public DocumentEmbeddingService(ObjectProvider<EmbeddingModel> embeddingModelProvider,
                                    EmbeddingDimensionValidator dimensionValidator) {
        this.embeddingModelProvider = embeddingModelProvider;
        this.dimensionValidator = dimensionValidator;
    }

    public List<float[]> embed(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        EmbeddingModel embeddingModel = this.embeddingModelProvider.getIfAvailable();
        if (embeddingModel == null) {
            throw new DocumentVectorizationException(
                    "Embedding 未配置（缺少 EMBEDDING_API_KEY），无法完成向量化");
        }
        List<float[]> vectors;
        try {
            vectors = embeddingModel.embed(texts);
        }
        catch (RuntimeException e) {
            throw new DocumentVectorizationException("Embedding 调用失败", e);
        }
        if (vectors == null || vectors.size() != texts.size()) {
            throw new DocumentVectorizationException(
                    "Embedding 返回数量与输入不一致: expected=" + texts.size()
                            + ", actual=" + (vectors == null ? 0 : vectors.size()));
        }
        try {
            this.dimensionValidator.validate(vectors);
        }
        catch (EmbeddingDimensionMismatchException e) {
            throw new DocumentVectorizationException(e.getMessage(), e);
        }
        return vectors;
    }
}
