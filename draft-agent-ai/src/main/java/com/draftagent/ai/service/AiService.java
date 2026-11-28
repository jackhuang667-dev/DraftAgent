package com.draftagent.ai.service;

import com.draftagent.ai.tool.DraftTools;
import com.draftagent.business.dto.TurnContext;
import com.draftagent.business.service.ConversationService;
import com.draftagent.common.core.exception.ServiceException;
import com.draftagent.common.security.CurrentUser;
import com.draftagent.common.security.CurrentUserProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * AI 对话服务（Agent 主流程）。
 *
 * <p>对同一 conversationId 加进程内串行锁后：beginTurn(T1) → 读取 ChatMemory 历史并组合 Prompt →
 * ChatClient（携带 tools 与 ToolContext）调用模型 → 成功后写 ChatMemory → completeTurn(T2)；
 * 失败时按当前消息状态 best-effort 调用 failTurn(T3)，并保留原始异常语义。</p>
 *
 * <p>模型调用不在任何数据库事务内；ChatMemory（SPRING_AI_CHAT_MEMORY）与 Conversation 业务表
 * 不是跨存储原子事务。会话锁仅保证单 JVM 内串行，多实例部署需分布式锁或数据库级并发控制。</p>
 */
@Service
public class AiService {

    private static final Logger log = LoggerFactory.getLogger(AiService.class);

    private static final int STATUS_BAD_REQUEST = 400;
    private static final int STATUS_BAD_GATEWAY = 502;
    private static final int STATUS_SERVICE_UNAVAILABLE = 503;

    private static final String ERR_AI_CALL_FAILED = "AI_CALL_FAILED";
    private static final String ERR_EMPTY_MODEL_REPLY = "EMPTY_MODEL_REPLY";
    private static final String ERR_CHAT_MEMORY_READ_FAILED = "CHAT_MEMORY_READ_FAILED";
    private static final String ERR_CHAT_MEMORY_WRITE_FAILED = "CHAT_MEMORY_WRITE_FAILED";
    private static final String ERR_COMPLETE_TURN_FAILED = "COMPLETE_TURN_FAILED";

    private final ChatMemory chatMemory;

    private final ObjectProvider<ChatClient> chatClientProvider;

    private final DraftTools draftTools;

    private final ConversationService conversationService;

    private final CurrentUserProvider currentUserProvider;

    /** 会话级进程内锁：引用计数归零即移除，避免无界增长。 */
    private final ConcurrentHashMap<String, ConversationLock> conversationLocks = new ConcurrentHashMap<>();

    public AiService(ChatMemory chatMemory,
                     ObjectProvider<ChatClient> chatClientProvider,
                     DraftTools draftTools,
                     ConversationService conversationService,
                     CurrentUserProvider currentUserProvider) {
        this.chatMemory = chatMemory;
        this.chatClientProvider = chatClientProvider;
        this.draftTools = draftTools;
        this.conversationService = conversationService;
        this.currentUserProvider = currentUserProvider;
    }

    public String chat(String conversationId, String requestId, String message) {
        ChatClient chatClient = chatClientProvider.getIfAvailable();
        if (chatClient == null) {
            throw new ServiceException(STATUS_SERVICE_UNAVAILABLE, "AI 服务未配置（缺少 DEEPSEEK_API_KEY），暂不可用");
        }
        if (!StringUtils.hasText(conversationId)) {
            throw new ServiceException(STATUS_BAD_REQUEST, "conversationId 不能为空");
        }
        if (!StringUtils.hasText(requestId)) {
            throw new ServiceException(STATUS_BAD_REQUEST, "requestId 不能为空");
        }
        CurrentUser currentUser = currentUserProvider.getCurrentUser();

        ConversationLock conversationLock = acquireConversationLock(conversationId);
        try {
            TurnContext turn = conversationService.beginTurn(
                    conversationId, message, currentUser.getUserId(), currentUser.getTenantId());
            Long assistantMessageId = turn.getAssistantMessageId();

            List<Message> history;
            try {
                history = chatMemory.get(conversationId);
            }
            catch (RuntimeException e) {
                safeFailTurn(assistantMessageId, ERR_CHAT_MEMORY_READ_FAILED);
                throw e;
            }
            List<Message> promptMessages = new ArrayList<>();
            if (history != null) {
                promptMessages.addAll(history);
            }
            UserMessage userMessage = new UserMessage(message);
            promptMessages.add(userMessage);

            String reply;
            try {
                reply = callModel(chatClient, promptMessages, requestId, turn, currentUser);
            }
            catch (RuntimeException e) {
                safeFailTurn(assistantMessageId, ERR_AI_CALL_FAILED);
                throw e;
            }
            if (reply == null) {
                safeFailTurn(assistantMessageId, ERR_EMPTY_MODEL_REPLY);
                throw new ServiceException(STATUS_BAD_GATEWAY, "上游 AI 服务返回空响应");
            }

            try {
                chatMemory.add(conversationId, List.of(userMessage, new AssistantMessage(reply)));
            }
            catch (RuntimeException e) {
                safeFailTurn(assistantMessageId, ERR_CHAT_MEMORY_WRITE_FAILED);
                throw e;
            }

            try {
                conversationService.completeTurn(assistantMessageId, reply);
            }
            catch (RuntimeException e) {
                safeFailTurn(assistantMessageId, ERR_COMPLETE_TURN_FAILED);
                throw e;
            }
            return reply;
        }
        finally {
            releaseConversationLock(conversationId);
        }
    }

    private String callModel(ChatClient chatClient, List<Message> promptMessages, String requestId,
                             TurnContext turn, CurrentUser currentUser) {
        Map<String, Object> toolContext = new LinkedHashMap<>();
        toolContext.put("requestId", requestId);
        toolContext.put("conversationPk", turn.getConversationPk());
        toolContext.put("turnId", turn.getTurnId());
        toolContext.put("userId", currentUser.getUserId());
        if (currentUser.getTenantId() != null) {
            toolContext.put("tenantId", currentUser.getTenantId());
        }
        try {
            return chatClient.prompt()
                    .messages(promptMessages)
                    .tools(draftTools)
                    .toolContext(toolContext)
                    .call()
                    .content();
        }
        catch (ToolExecutionException e) {
            // 业务异常（ServiceException）会被异常处理器原样抛出不进入此分支；
            // 此处仅处理被包装的非业务工具异常，统一脱敏为 502，避免泄露内部细节。
            log.warn("工具执行失败: {}",
                    e.getToolDefinition() != null ? e.getToolDefinition().name() : "unknown");
            throw new ServiceException(STATUS_BAD_GATEWAY, "工具执行失败");
        }
        catch (NonTransientAiException e) {
            log.warn("上游 AI 服务返回不可重试异常: {}", e.getClass().getSimpleName());
            throw new ServiceException(STATUS_BAD_GATEWAY, classifyUpstreamFailure(e.getMessage()));
        }
        catch (TransientAiException e) {
            log.warn("上游 AI 服务暂时不可用: {}", e.getClass().getSimpleName());
            throw new ServiceException(STATUS_BAD_GATEWAY, "上游 AI 服务暂时不可用，请稍后重试");
        }
        catch (ResourceAccessException e) {
            log.warn("无法连接上游 AI 服务: {}", e.getClass().getSimpleName());
            throw new ServiceException(STATUS_BAD_GATEWAY, "无法连接上游 AI 服务，请稍后重试");
        }
        catch (RestClientException e) {
            log.warn("上游 AI 服务调用异常: {}", e.getClass().getSimpleName());
            throw new ServiceException(STATUS_BAD_GATEWAY, "上游 AI 服务调用失败");
        }
    }

    private void safeFailTurn(Long assistantMessageId, String errorCode) {
        try {
            conversationService.failTurn(assistantMessageId, errorCode);
        }
        catch (RuntimeException e) {
            // 不掩盖原始异常；状态更新失败仅记录异常类型
            log.warn("标记 ASSISTANT 消息失败状态时发生异常: {}", e.getClass().getSimpleName());
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

    private ConversationLock acquireConversationLock(String conversationId) {
        ConversationLock entry = conversationLocks.compute(conversationId, (key, existing) -> {
            ConversationLock lock = existing != null ? existing : new ConversationLock();
            lock.references++;
            return lock;
        });
        entry.lock.lock();
        return entry;
    }

    private void releaseConversationLock(String conversationId) {
        ConversationLock entry = conversationLocks.get(conversationId);
        if (entry == null) {
            return;
        }
        try {
            entry.lock.unlock();
        }
        finally {
            conversationLocks.computeIfPresent(conversationId, (key, existing) -> {
                existing.references--;
                return existing.references <= 0 ? null : existing;
            });
        }
    }

    private static final class ConversationLock {

        private final ReentrantLock lock = new ReentrantLock();

        private int references;
    }
}
