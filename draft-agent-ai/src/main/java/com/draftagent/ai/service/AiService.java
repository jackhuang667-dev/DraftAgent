package com.draftagent.ai.service;

import com.draftagent.ai.tool.DraftTools;
import com.draftagent.common.core.exception.ServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

/**
 * AI 对话服务。
 *
 * <p>无 API Key 时不注入 ChatClient，返回 503；上游调用异常统一转换为业务异常，
 * 由全局异常处理映射为对应 HTTP 状态码，且不向客户端或日志泄露敏感信息。</p>
 */
@Service
public class AiService {

    private static final Logger log = LoggerFactory.getLogger(AiService.class);

    private static final int STATUS_BAD_GATEWAY = 502;
    private static final int STATUS_SERVICE_UNAVAILABLE = 503;

    private final ObjectProvider<ChatClient> chatClientProvider;

    private final DraftTools draftTools;

    public AiService(ObjectProvider<ChatClient> chatClientProvider, DraftTools draftTools) {
        this.chatClientProvider = chatClientProvider;
        this.draftTools = draftTools;
    }

    public String chat(String message) {
        ChatClient chatClient = chatClientProvider.getIfAvailable();
        if (chatClient == null) {
            throw new ServiceException(STATUS_SERVICE_UNAVAILABLE, "AI 服务未配置（缺少 DEEPSEEK_API_KEY），暂不可用");
        }
        try {
            return chatClient.prompt()
                    .user(message)
                    .tools(draftTools)
                    .call()
                    .content();
        } catch (NonTransientAiException e) {
            log.warn("上游 AI 服务返回不可重试异常: {}", e.getClass().getSimpleName());
            throw new ServiceException(STATUS_BAD_GATEWAY, classifyUpstreamFailure(e.getMessage()));
        } catch (TransientAiException e) {
            log.warn("上游 AI 服务暂时不可用: {}", e.getClass().getSimpleName());
            throw new ServiceException(STATUS_BAD_GATEWAY, "上游 AI 服务暂时不可用，请稍后重试");
        } catch (ResourceAccessException e) {
            log.warn("无法连接上游 AI 服务: {}", e.getClass().getSimpleName());
            throw new ServiceException(STATUS_BAD_GATEWAY, "无法连接上游 AI 服务，请稍后重试");
        } catch (RestClientException e) {
            log.warn("上游 AI 服务调用异常: {}", e.getClass().getSimpleName());
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
