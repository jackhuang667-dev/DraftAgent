package com.draftagent.business.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.draftagent.business.dto.DraftCreateResponse;
import com.draftagent.business.entity.AgentToolExecution;
import com.draftagent.business.entity.Draft;
import com.draftagent.business.mapper.AgentToolExecutionMapper;
import com.draftagent.business.mapper.DraftMapper;
import com.draftagent.common.core.exception.ServiceException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工具执行的编排服务（事务外）。
 *
 * <p>调用 {@link ToolExecutionTransactionService} 的事务方法完成实际写入；并在事务外处理
 * 幂等冲突（{@link DuplicateKeyException}）：查询 tool_name + idempotency_key 记录，
 * 仅当记录为 SUCCESS 且规范化参数一致时复用原草案结果，否则返回明确业务冲突。</p>
 *
 * <p>本类本身不加 {@code @Transactional}：事务方法返回/回滚之后，再在事务外查询，
 * 避免在已经回滚的事务中继续查询。V1 仅支持 createDraft；requestId 作为 idempotency_key。</p>
 */
@Service
public class ToolExecutionService {

    public static final String TOOL_CREATE_DRAFT = "createDraft";

    private static final int STATUS_BAD_REQUEST = 400;
    private static final int STATUS_UNAUTHORIZED = 401;
    private static final int STATUS_FORBIDDEN = 403;
    private static final int STATUS_CONFLICT = 409;
    private static final int STATUS_INTERNAL_ERROR = 500;

    private static final int IDEMPOTENCY_KEY_MAX_LENGTH = 36;
    private static final int TURN_ID_MAX_LENGTH = 36;

    private final ToolExecutionTransactionService toolExecutionTransactionService;
    private final AgentToolExecutionMapper agentToolExecutionMapper;
    private final DraftMapper draftMapper;
    private final ObjectMapper objectMapper;

    public ToolExecutionService(ToolExecutionTransactionService toolExecutionTransactionService,
                                AgentToolExecutionMapper agentToolExecutionMapper,
                                DraftMapper draftMapper,
                                ObjectMapper objectMapper) {
        this.toolExecutionTransactionService = toolExecutionTransactionService;
        this.agentToolExecutionMapper = agentToolExecutionMapper;
        this.draftMapper = draftMapper;
        this.objectMapper = objectMapper;
    }

    public DraftCreateResponse executeCreateDraft(String requestId,
                                                  Long conversationPk,
                                                  String turnId,
                                                  String toolName,
                                                  String title,
                                                  String content,
                                                  Long userId,
                                                  Long tenantId) {
        validateRequest(requestId, conversationPk, turnId, toolName, userId);
        String normalizedArguments = normalizeArguments(title, content);

        try {
            return toolExecutionTransactionService.executeCreateDraft(
                    requestId, conversationPk, turnId, toolName, title, content, normalizedArguments, userId, tenantId);
        }
        catch (DuplicateKeyException e) {
            AgentToolExecution existing = findExecution(toolName, requestId);
            if (existing == null) {
                // 不是本工具幂等键冲突（可能是其他唯一键），不得误判为幂等成功
                throw e;
            }
            return reuseOrConflict(existing, conversationPk, normalizedArguments, userId, tenantId);
        }
    }

    private void validateRequest(String requestId, Long conversationPk, String turnId, String toolName, Long userId) {
        if (userId == null) {
            throw new ServiceException(STATUS_UNAUTHORIZED, "未认证，无法执行工具");
        }
        if (!StringUtils.hasText(requestId) || requestId.length() > IDEMPOTENCY_KEY_MAX_LENGTH) {
            throw new ServiceException(STATUS_BAD_REQUEST, "requestId 不能为空且长度不能超过 " + IDEMPOTENCY_KEY_MAX_LENGTH);
        }
        if (conversationPk == null) {
            throw new ServiceException(STATUS_BAD_REQUEST, "conversationPk 不能为空");
        }
        if (!StringUtils.hasText(turnId) || turnId.length() > TURN_ID_MAX_LENGTH) {
            throw new ServiceException(STATUS_BAD_REQUEST, "turnId 不能为空且长度不能超过 " + TURN_ID_MAX_LENGTH);
        }
        if (!StringUtils.hasText(toolName)) {
            throw new ServiceException(STATUS_BAD_REQUEST, "toolName 不能为空");
        }
        if (!TOOL_CREATE_DRAFT.equals(toolName)) {
            throw new ServiceException(STATUS_BAD_REQUEST, "V1 仅支持 " + TOOL_CREATE_DRAFT + " 工具");
        }
    }

    private DraftCreateResponse reuseOrConflict(AgentToolExecution existing, Long conversationPk,
                                                String normalizedArguments, Long userId, Long tenantId) {
        if (!ToolExecutionTransactionService.STATUS_SUCCESS.equals(existing.getStatus())) {
            throw new ServiceException(STATUS_CONFLICT, "相同 requestId 的执行尚未成功或状态异常，暂不能复用结果");
        }
        if (existing.getConversationPk() == null || !existing.getConversationPk().equals(conversationPk)) {
            throw new ServiceException(STATUS_CONFLICT, "相同 requestId 属于其他会话，拒绝复用结果");
        }
        if (!sameArguments(existing.getArguments(), normalizedArguments)) {
            throw new ServiceException(STATUS_CONFLICT, "相同 requestId 但工具参数不同，拒绝复用旧结果");
        }
        Long draftId = parseDraftId(existing.getResultRef());
        Draft draft = draftMapper.selectById(draftId);
        if (draft == null) {
            throw new ServiceException(STATUS_CONFLICT, "幂等记录存在但对应草案不存在");
        }
        if (!java.util.Objects.equals(draft.getUserId(), userId)
                || !java.util.Objects.equals(draft.getTenantId(), tenantId)) {
            throw new ServiceException(STATUS_FORBIDDEN, "无权复用不属于当前用户/租户的草案结果");
        }
        return new DraftCreateResponse(draft.getId(), draft.getTitle(), draft.getStatus(), draft.getCreatedAt());
    }

    private AgentToolExecution findExecution(String toolName, String requestId) {
        return agentToolExecutionMapper.selectOne(
                new LambdaQueryWrapper<AgentToolExecution>()
                        .eq(AgentToolExecution::getToolName, toolName)
                        .eq(AgentToolExecution::getIdempotencyKey, requestId));
    }

    private Long parseDraftId(String resultRef) {
        if (!StringUtils.hasText(resultRef)) {
            throw new ServiceException(STATUS_CONFLICT, "幂等记录缺少结果引用");
        }
        try {
            return Long.valueOf(resultRef);
        }
        catch (NumberFormatException e) {
            throw new ServiceException(STATUS_CONFLICT, "幂等记录的结果引用格式非法");
        }
    }

    private boolean sameArguments(String storedArguments, String normalizedArguments) {
        if (storedArguments == null) {
            return false;
        }
        try {
            JsonNode stored = objectMapper.readTree(storedArguments);
            JsonNode current = objectMapper.readTree(normalizedArguments);
            return stored != null && stored.equals(current);
        }
        catch (JsonProcessingException e) {
            return false;
        }
    }

    private String normalizeArguments(String title, String content) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("title", title);
        arguments.put("content", content);
        try {
            return objectMapper.writeValueAsString(arguments);
        }
        catch (JsonProcessingException e) {
            throw new ServiceException(STATUS_INTERNAL_ERROR, "工具参数序列化失败");
        }
    }
}
