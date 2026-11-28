package com.draftagent.business.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.draftagent.business.dto.TurnContext;
import com.draftagent.business.entity.Conversation;
import com.draftagent.business.entity.ConversationMessage;
import com.draftagent.business.mapper.ConversationMapper;
import com.draftagent.business.mapper.ConversationMessageMapper;
import com.draftagent.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 会话消息持久化服务。
 *
 * <p>事务边界：
 * <ul>
 *   <li>T1 {@link #beginTurn}: 创建/查询会话 + 原子分配两次序号 + 插入 USER 与 ASSISTANT(PENDING)。</li>
 *   <li>T2 {@link #completeTurn}: 将 PENDING 更新为 SUCCESS 并保存回复内容。</li>
 *   <li>T3 {@link #failTurn}: 将 PENDING 更新为 FAILED 并记录错误码。</li>
 * </ul>
 * 三个方法各自独立事务，均只做数据库操作，不调用大模型；避免 self-invocation 导致事务注解失效。</p>
 */
@Service
public class ConversationService {

    public static final String STATUS_ACTIVE = "ACTIVE";

    public static final String ROLE_USER = "USER";
    public static final String ROLE_ASSISTANT = "ASSISTANT";

    public static final String MESSAGE_TYPE_CHAT = "CHAT";

    public static final String MESSAGE_STATUS_PENDING = "PENDING";
    public static final String MESSAGE_STATUS_SUCCESS = "SUCCESS";
    public static final String MESSAGE_STATUS_FAILED = "FAILED";

    private static final int STATUS_BAD_REQUEST = 400;
    private static final int STATUS_UNAUTHORIZED = 401;
    private static final int STATUS_FORBIDDEN = 403;
    private static final int STATUS_NOT_FOUND = 404;
    private static final int STATUS_CONFLICT = 409;

    private static final Pattern UUID_PATTERN = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private final ConversationMapper conversationMapper;
    private final ConversationMessageMapper conversationMessageMapper;

    public ConversationService(ConversationMapper conversationMapper,
                               ConversationMessageMapper conversationMessageMapper) {
        this.conversationMapper = conversationMapper;
        this.conversationMessageMapper = conversationMessageMapper;
    }

    /**
     * T1：开始一轮对话。会话不存在则创建，随后原子分配 USER/ASSISTANT 两个序号并插入两条消息。
     * 提交后上层才可调用模型。
     */
    @Transactional
    public TurnContext beginTurn(String conversationId, String userMessage, Long userId, Long tenantId) {
        if (userId == null) {
            throw new ServiceException(STATUS_UNAUTHORIZED, "未认证，无法开始对话");
        }
        if (!isValidUuid(conversationId)) {
            throw new ServiceException(STATUS_BAD_REQUEST, "conversationId 必须为合法的 UUID");
        }
        if (!StringUtils.hasText(userMessage)) {
            throw new ServiceException(STATUS_BAD_REQUEST, "用户消息内容不能为空");
        }

        Conversation conversation = conversationMapper.selectOne(
                new LambdaQueryWrapper<Conversation>().eq(Conversation::getConversationId, conversationId));
        if (conversation == null) {
            conversation = new Conversation();
            conversation.setConversationId(conversationId);
            conversation.setUserId(userId);
            conversation.setTenantId(tenantId);
            conversation.setStatus(STATUS_ACTIVE);
            conversation.setLastMessageSeq(0L);
            conversationMapper.insert(conversation);
        }
        else if (!STATUS_ACTIVE.equals(conversation.getStatus())) {
            throw new ServiceException(STATUS_BAD_REQUEST, "会话当前状态不允许继续对话");
        }
        else if (!Objects.equals(conversation.getUserId(), userId)
                || !Objects.equals(conversation.getTenantId(), tenantId)) {
            // 会话属于其他用户/租户：拒绝继续，避免泄露或串写
            throw new ServiceException(STATUS_FORBIDDEN, "无权访问该会话");
        }

        Long conversationPk = conversation.getId();
        if (conversationPk == null) {
            throw new ServiceException(STATUS_CONFLICT, "会话主键缺失，无法继续");
        }

        String turnId = UUID.randomUUID().toString();

        long userSequence = allocateSequence(conversationPk);
        ConversationMessage userMessageEntity = new ConversationMessage();
        userMessageEntity.setConversationPk(conversationPk);
        userMessageEntity.setTurnId(turnId);
        userMessageEntity.setSequenceNo(userSequence);
        userMessageEntity.setRole(ROLE_USER);
        userMessageEntity.setMessageType(MESSAGE_TYPE_CHAT);
        userMessageEntity.setMessageStatus(MESSAGE_STATUS_SUCCESS);
        userMessageEntity.setContent(userMessage);
        conversationMessageMapper.insert(userMessageEntity);

        long assistantSequence = allocateSequence(conversationPk);
        ConversationMessage assistantMessageEntity = new ConversationMessage();
        assistantMessageEntity.setConversationPk(conversationPk);
        assistantMessageEntity.setTurnId(turnId);
        assistantMessageEntity.setSequenceNo(assistantSequence);
        assistantMessageEntity.setRole(ROLE_ASSISTANT);
        assistantMessageEntity.setMessageType(MESSAGE_TYPE_CHAT);
        assistantMessageEntity.setMessageStatus(MESSAGE_STATUS_PENDING);
        assistantMessageEntity.setContent("");
        conversationMessageMapper.insert(assistantMessageEntity);

        return new TurnContext(conversationPk, conversationId, userMessageEntity.getId(),
                assistantMessageEntity.getId(), userSequence, assistantSequence, turnId);
    }

    /**
     * T2：将 PENDING 的 ASSISTANT 消息完成，保存回复并置为 SUCCESS。已完成的消息不可重复更新。
     */
    @Transactional
    public void completeTurn(Long assistantMessageId, String content) {
        ConversationMessage message = requirePendingAssistant(assistantMessageId);
        String safeContent = content == null ? "" : content;

        int updated = conversationMessageMapper.update(null,
                new LambdaUpdateWrapper<ConversationMessage>()
                        .eq(ConversationMessage::getId, assistantMessageId)
                        .eq(ConversationMessage::getMessageStatus, MESSAGE_STATUS_PENDING)
                        .set(ConversationMessage::getContent, safeContent)
                        .set(ConversationMessage::getMessageStatus, MESSAGE_STATUS_SUCCESS));
        if (updated != 1) {
            throw new ServiceException(STATUS_CONFLICT, "消息已完成或已失败，无法重复更新");
        }
        touchConversation(message.getConversationPk());
    }

    /**
     * T3：将 PENDING 的 ASSISTANT 消息标记为 FAILED 并记录错误码；content 保持非 NULL，不写入敏感信息。
     */
    @Transactional
    public void failTurn(Long assistantMessageId, String errorCode) {
        ConversationMessage message = requirePendingAssistant(assistantMessageId);

        int updated = conversationMessageMapper.update(null,
                new LambdaUpdateWrapper<ConversationMessage>()
                        .eq(ConversationMessage::getId, assistantMessageId)
                        .eq(ConversationMessage::getMessageStatus, MESSAGE_STATUS_PENDING)
                        .set(ConversationMessage::getMessageStatus, MESSAGE_STATUS_FAILED)
                        .set(ConversationMessage::getErrorCode, errorCode));
        if (updated != 1) {
            throw new ServiceException(STATUS_CONFLICT, "消息已完成或已失败，无法重复更新");
        }
        touchConversation(message.getConversationPk());
    }

    private ConversationMessage requirePendingAssistant(Long assistantMessageId) {
        if (assistantMessageId == null) {
            throw new ServiceException(STATUS_NOT_FOUND, "消息不存在");
        }
        ConversationMessage message = conversationMessageMapper.selectById(assistantMessageId);
        if (message == null) {
            throw new ServiceException(STATUS_NOT_FOUND, "消息不存在");
        }
        if (!ROLE_ASSISTANT.equals(message.getRole())) {
            throw new ServiceException(STATUS_BAD_REQUEST, "只能更新 ASSISTANT 消息");
        }
        if (!MESSAGE_STATUS_PENDING.equals(message.getMessageStatus())) {
            throw new ServiceException(STATUS_CONFLICT, "消息已完成或已失败，无法重复更新");
        }
        return message;
    }

    /**
     * 事务内原子分配消息序号：UPDATE 自增后再读取，禁止使用 MAX(sequence_no) + 1。
     * 必须在调用方事务内执行（由 beginTurn 的 T1 保证）。
     */
    private long allocateSequence(Long conversationPk) {
        int updated = conversationMapper.update(null,
                new LambdaUpdateWrapper<Conversation>()
                        .eq(Conversation::getId, conversationPk)
                        .setSql("last_message_seq = last_message_seq + 1"));
        if (updated != 1) {
            throw new ServiceException(STATUS_CONFLICT, "消息序号分配失败：会话不存在或更新冲突");
        }
        Conversation conversation = conversationMapper.selectById(conversationPk);
        if (conversation == null || conversation.getLastMessageSeq() == null) {
            throw new ServiceException(STATUS_CONFLICT, "消息序号读取失败");
        }
        return conversation.getLastMessageSeq();
    }

    /**
     * 显式刷新会话 updated_at。conversation.updated_at 本身带有 ON UPDATE CURRENT_TIMESTAMP，
     * 但 T2/T3 只更新消息表、不会触发会话行自动刷新，故此处显式 touch（DATETIME 秒级精度，
     * 同一秒内可能无实际变化，属正常）。
     */
    private void touchConversation(Long conversationPk) {
        if (conversationPk == null) {
            return;
        }
        conversationMapper.update(null,
                new LambdaUpdateWrapper<Conversation>()
                        .eq(Conversation::getId, conversationPk)
                        .setSql("updated_at = CURRENT_TIMESTAMP"));
    }

    private boolean isValidUuid(String value) {
        return value != null && UUID_PATTERN.matcher(value).matches();
    }
}
