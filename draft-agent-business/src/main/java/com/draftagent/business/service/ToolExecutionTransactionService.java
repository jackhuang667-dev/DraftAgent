package com.draftagent.business.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.draftagent.business.dto.DraftCreateResponse;
import com.draftagent.business.entity.AgentToolExecution;
import com.draftagent.business.entity.Draft;
import com.draftagent.business.mapper.AgentToolExecutionMapper;
import com.draftagent.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * 工具执行的事务内服务。
 *
 * <p>单个事务内完成：插入 EXECUTING 执行记录 → 通过 {@link DraftService#createDraft} 创建草案
 * （REQUIRED 加入同一事务）→ 将执行记录更新为 SUCCESS 并写入 result_ref。
 * 草案插入与执行记录更新使用同一 DataSource / DataSourceTransactionManager，保证一起提交或回滚。</p>
 *
 * <p>禁止在本事务方法中调用大模型；execution_id 仅在新建执行记录时生成一次。</p>
 */
@Service
public class ToolExecutionTransactionService {

    public static final String STATUS_EXECUTING = "EXECUTING";
    public static final String STATUS_SUCCESS = "SUCCESS";

    private static final int STATUS_CONFLICT = 409;

    private final AgentToolExecutionMapper agentToolExecutionMapper;
    private final DraftService draftService;

    public ToolExecutionTransactionService(AgentToolExecutionMapper agentToolExecutionMapper,
                                           DraftService draftService) {
        this.agentToolExecutionMapper = agentToolExecutionMapper;
        this.draftService = draftService;
    }

    @Transactional
    public DraftCreateResponse executeCreateDraft(String requestId,
                                                  Long conversationPk,
                                                  String turnId,
                                                  String toolName,
                                                  String title,
                                                  String content,
                                                  String normalizedArguments,
                                                  Long userId,
                                                  Long tenantId) {
        AgentToolExecution execution = new AgentToolExecution();
        execution.setExecutionId(UUID.randomUUID().toString());
        execution.setConversationPk(conversationPk);
        execution.setTurnId(turnId);
        execution.setIdempotencyKey(requestId);
        execution.setToolName(toolName);
        execution.setArguments(normalizedArguments);
        execution.setStatus(STATUS_EXECUTING);
        agentToolExecutionMapper.insert(execution);

        Draft draft = draftService.createDraft(title, content, userId, tenantId);

        int updated = agentToolExecutionMapper.update(null,
                new LambdaUpdateWrapper<AgentToolExecution>()
                        .eq(AgentToolExecution::getId, execution.getId())
                        .eq(AgentToolExecution::getStatus, STATUS_EXECUTING)
                        .set(AgentToolExecution::getStatus, STATUS_SUCCESS)
                        .set(AgentToolExecution::getResultRef, String.valueOf(draft.getId())));
        if (updated != 1) {
            throw new ServiceException(STATUS_CONFLICT, "工具执行记录状态更新失败（受影响行数不为 1）");
        }

        return new DraftCreateResponse(draft.getId(), draft.getTitle(), draft.getStatus(), draft.getCreatedAt());
    }
}
