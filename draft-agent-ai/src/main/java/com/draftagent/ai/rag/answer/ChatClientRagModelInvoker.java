package com.draftagent.ai.rag.answer;

import com.draftagent.common.core.exception.ServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

/**
 * 基于现有 ChatClient Bean 的 RAG 模型调用。
 *
 * <p>只发送 System / User 文本：不传 tools、不传 toolContext、不挂 ChatMemory Advisor，
 * 因此不会触发既有 Agent Tools，也不会读写普通对话的 ChatMemory。若未配置 API Key
 * （ChatClient Bean 不存在），抛出明确的 503。</p>
 */
@Component
public class ChatClientRagModelInvoker implements RagChatModelInvoker {

    private static final Logger log = LoggerFactory.getLogger(ChatClientRagModelInvoker.class);

    private static final int STATUS_BAD_GATEWAY = 502;

    private static final int STATUS_SERVICE_UNAVAILABLE = 503;

    private final ObjectProvider<ChatClient> chatClientProvider;

    public ChatClientRagModelInvoker(ObjectProvider<ChatClient> chatClientProvider) {
        this.chatClientProvider = chatClientProvider;
    }

    @Override
    public String invoke(String systemPrompt, String userPrompt) {
        ChatClient chatClient = this.chatClientProvider.getIfAvailable();
        if (chatClient == null) {
            throw new ServiceException(STATUS_SERVICE_UNAVAILABLE,
                    "AI 服务未配置（缺少 DEEPSEEK_API_KEY），暂不可用");
        }
        try {
            return chatClient.prompt()
                    .system(systemPrompt)
                    .user(userPrompt)
                    .call()
                    .content();
        }
        catch (ToolExecutionException e) {
            log.warn("RAG 模型调用意外触发工具执行（已拒绝）");
            throw new ServiceException(STATUS_BAD_GATEWAY, "AI 服务调用失败");
        }
        catch (NonTransientAiException e) {
            log.warn("RAG 上游 AI 服务返回不可重试异常: {}", e.getClass().getSimpleName());
            throw new ServiceException(STATUS_BAD_GATEWAY, classifyUpstreamFailure(e.getMessage()));
        }
        catch (TransientAiException e) {
            log.warn("RAG 上游 AI 服务暂时不可用: {}", e.getClass().getSimpleName());
            throw new ServiceException(STATUS_BAD_GATEWAY, "上游 AI 服务暂时不可用，请稍后重试");
        }
        catch (ResourceAccessException e) {
            log.warn("RAG 无法连接上游 AI 服务: {}", e.getClass().getSimpleName());
            throw new ServiceException(STATUS_BAD_GATEWAY, "无法连接上游 AI 服务，请稍后重试");
        }
        catch (RestClientException e) {
            log.warn("RAG 上游 AI 服务调用异常: {}", e.getClass().getSimpleName());
            throw new ServiceException(STATUS_BAD_GATEWAY, "上游 AI 服务调用失败");
        }
    }

    private String classifyUpstreamFailure(String detail) {
        if (detail != null) {
            String lower = detail.toLowerCase();
            if (detail.contains("401") || detail.contains("403")
                    || lower.contains("authentication") || lower.contains("unauthorized")) {
                return "上游 AI 服务认证失败，请检查 API Key 配置";
            }
        }
        return "上游 AI 服务调用失败";
    }
}
