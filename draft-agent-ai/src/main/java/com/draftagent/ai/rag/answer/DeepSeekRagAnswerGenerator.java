package com.draftagent.ai.rag.answer;

import com.draftagent.business.document.RetrievedDocumentChunk;
import com.draftagent.business.rag.RagAnswer;
import com.draftagent.business.rag.RagAnswerGenerator;
import com.draftagent.business.rag.RagSource;
import com.draftagent.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 基于现有 DeepSeek ChatClient 的 RAG 回答生成。
 *
 * <p>先生成上下文预算选择（按 Qdrant 排名前缀选择、不截断），再组装 Prompt 并调用模型；
 * 模型返回后校验 [n] 引用编号，仅保留指向本次实际进入 Prompt 的引用。引用来源完全由后端
 * 根据选中的、已通过 MySQL 权限复核的 Chunk 生成；模型不能决定 documentId / chunkId。
 * 调用失败、空响应均转换为脱敏业务异常。</p>
 */
@Service
public class DeepSeekRagAnswerGenerator implements RagAnswerGenerator {

    private static final int STATUS_BAD_REQUEST = 400;

    private static final int STATUS_BAD_GATEWAY = 502;

    private final RagContextSelector contextSelector;

    private final RagPromptBuilder promptBuilder;

    private final RagChatModelInvoker modelInvoker;

    private final RagCitationValidator citationValidator;

    public DeepSeekRagAnswerGenerator(RagContextSelector contextSelector,
                                      RagPromptBuilder promptBuilder,
                                      RagChatModelInvoker modelInvoker,
                                      RagCitationValidator citationValidator) {
        this.contextSelector = contextSelector;
        this.promptBuilder = promptBuilder;
        this.modelInvoker = modelInvoker;
        this.citationValidator = citationValidator;
    }

    @Override
    public RagAnswer generate(String query, List<RetrievedDocumentChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            throw new ServiceException(STATUS_BAD_REQUEST, "检索上下文不能为空");
        }
        List<RetrievedDocumentChunk> selected = this.contextSelector.select(chunks);
        if (selected.isEmpty()) {
            throw new ServiceException(STATUS_BAD_REQUEST, "检索上下文不能为空");
        }
        RagPrompt prompt = this.promptBuilder.build(query, selected);
        String answer;
        try {
            answer = this.modelInvoker.invoke(prompt.getSystem(), prompt.getUser());
        }
        catch (ServiceException e) {
            throw e;
        }
        catch (RuntimeException e) {
            throw new ServiceException(STATUS_BAD_GATEWAY, "RAG 回答生成失败，请稍后重试", e);
        }
        if (answer == null || answer.isBlank()) {
            throw new ServiceException(STATUS_BAD_GATEWAY, "上游 AI 服务返回空响应");
        }
        String sanitized = this.citationValidator.sanitize(answer, selected.size());
        if (sanitized == null || sanitized.isBlank()) {
            throw new ServiceException(STATUS_BAD_GATEWAY, "上游 AI 服务返回空响应");
        }
        return new RagAnswer(sanitized, buildSources(selected), true);
    }

    private List<RagSource> buildSources(List<RetrievedDocumentChunk> chunks) {
        List<RagSource> sources = new ArrayList<>(chunks.size());
        int referenceId = 1;
        for (RetrievedDocumentChunk chunk : chunks) {
            sources.add(new RagSource(referenceId, chunk.getDocumentId(), chunk.getChunkId(),
                    chunk.getChunkIndex(), chunk.getScore()));
            referenceId++;
        }
        return sources;
    }
}
