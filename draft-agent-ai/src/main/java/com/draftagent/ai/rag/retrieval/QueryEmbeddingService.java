package com.draftagent.ai.rag.retrieval;

import com.draftagent.ai.rag.embedding.EmbeddingDimensionMismatchException;
import com.draftagent.ai.rag.embedding.EmbeddingDimensionValidator;
import com.draftagent.business.document.DocumentSearchException;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 查询文本 → 1024 维查询向量。
 *
 * <p>复用文档入库时的同一个 {@link EmbeddingModel} Bean 与维度校验器；未配置
 * {@code EMBEDDING_API_KEY} 时不创建该 Bean，此处返回明确的业务异常，绝不伪装成“检索成功但空结果”。
 * 日志不记录问题原文、向量或 API Key。</p>
 */
@Service
public class QueryEmbeddingService {

    private final ObjectProvider<EmbeddingModel> embeddingModelProvider;

    private final EmbeddingDimensionValidator dimensionValidator;

    public QueryEmbeddingService(ObjectProvider<EmbeddingModel> embeddingModelProvider,
                                 EmbeddingDimensionValidator dimensionValidator) {
        this.embeddingModelProvider = embeddingModelProvider;
        this.dimensionValidator = dimensionValidator;
    }

    public float[] embedQuery(String query) {
        if (!StringUtils.hasText(query)) {
            throw new DocumentSearchException("检索问题不能为空");
        }
        EmbeddingModel embeddingModel = this.embeddingModelProvider.getIfAvailable();
        if (embeddingModel == null) {
            throw new DocumentSearchException("Embedding 未配置（缺少 EMBEDDING_API_KEY），无法执行检索");
        }
        float[] vector;
        try {
            vector = embeddingModel.embed(query);
        }
        catch (RuntimeException e) {
            throw new DocumentSearchException("查询向量生成失败", e);
        }
        try {
            this.dimensionValidator.validate(vector);
        }
        catch (EmbeddingDimensionMismatchException e) {
            throw new DocumentSearchException(e.getMessage(), e);
        }
        return vector;
    }
}
