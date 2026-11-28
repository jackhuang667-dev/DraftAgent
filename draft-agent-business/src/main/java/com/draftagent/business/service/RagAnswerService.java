package com.draftagent.business.service;

import com.draftagent.business.document.DocumentSearchException;
import com.draftagent.business.document.DocumentSearchScope;
import com.draftagent.business.document.RetrievedDocumentChunk;
import com.draftagent.business.rag.RagAnswer;
import com.draftagent.business.rag.RagAnswerGenerator;
import com.draftagent.business.rag.RagDocumentRetriever;
import com.draftagent.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * RAG 问答编排（business 层）。
 *
 * <p>流程：校验参数 → 通过 {@link RagDocumentRetriever} 获取权限已复核的 Chunk 原文 →
 * 无上下文时直接返回固定提示且不调用模型 → 有上下文时调用 {@link RagAnswerGenerator} 生成回答。
 * business 不依赖 Spring AI / DeepSeek，也不直接接触 Qdrant。</p>
 */
@Service
public class RagAnswerService {

    public static final String NO_CONTEXT_ANSWER = "当前知识库没有检索到相关资料，暂时无法确认该问题。";

    private static final int STATUS_BAD_REQUEST = 400;

    private static final int STATUS_BAD_GATEWAY = 502;

    private static final int STATUS_SERVICE_UNAVAILABLE = 503;

    private static final int MAX_QUERY_LENGTH = 4000;

    private final RagDocumentRetriever documentRetriever;

    private final RagAnswerGenerator answerGenerator;

    public RagAnswerService(RagDocumentRetriever documentRetriever, RagAnswerGenerator answerGenerator) {
        this.documentRetriever = documentRetriever;
        this.answerGenerator = answerGenerator;
    }

    public RagAnswer answer(String query, DocumentSearchScope scope) {
        validate(query, scope);
        return assemble(query, retrieve(query, scope));
    }

    /**
     * topK 校验与默认值由 3.5.1 的检索链路（{@code RagRetrievalProperties} / {@code DocumentVectorSearchService}）负责。
     */
    public RagAnswer answer(String query, DocumentSearchScope scope, int topK) {
        validate(query, scope);
        return assemble(query, retrieve(query, scope, topK));
    }

    private List<RetrievedDocumentChunk> retrieve(String query, DocumentSearchScope scope) {
        try {
            return this.documentRetriever.retrieve(query, scope);
        }
        catch (DocumentSearchException e) {
            throw new ServiceException(STATUS_SERVICE_UNAVAILABLE, safeRetrievalMessage(e), e);
        }
    }

    private List<RetrievedDocumentChunk> retrieve(String query, DocumentSearchScope scope, int topK) {
        try {
            return this.documentRetriever.retrieve(query, scope, topK);
        }
        catch (DocumentSearchException e) {
            throw new ServiceException(STATUS_SERVICE_UNAVAILABLE, safeRetrievalMessage(e), e);
        }
    }

    private RagAnswer assemble(String query, List<RetrievedDocumentChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return new RagAnswer(NO_CONTEXT_ANSWER, List.of(), false);
        }
        try {
            RagAnswer answer = this.answerGenerator.generate(query, chunks);
            if (answer == null) {
                throw new ServiceException(STATUS_BAD_GATEWAY, "RAG 回答生成失败，请稍后重试");
            }
            return answer;
        }
        catch (ServiceException e) {
            throw e;
        }
        catch (RuntimeException e) {
            throw new ServiceException(STATUS_BAD_GATEWAY, "RAG 回答生成失败，请稍后重试", e);
        }
    }

    private void validate(String query, DocumentSearchScope scope) {
        if (!StringUtils.hasText(query)) {
            throw new ServiceException(STATUS_BAD_REQUEST, "问题不能为空");
        }
        if (query.length() > MAX_QUERY_LENGTH) {
            throw new ServiceException(STATUS_BAD_REQUEST, "问题长度不能超过 " + MAX_QUERY_LENGTH + " 个字符");
        }
        if (scope == null) {
            throw new ServiceException(STATUS_BAD_REQUEST, "检索范围不能为空");
        }
    }

    private String safeRetrievalMessage(DocumentSearchException e) {
        String message = e.getMessage();
        return StringUtils.hasText(message) ? message : "知识库检索失败，请稍后重试";
    }
}
