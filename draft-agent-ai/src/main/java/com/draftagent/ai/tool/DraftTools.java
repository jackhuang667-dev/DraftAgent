package com.draftagent.ai.tool;

import com.draftagent.business.dto.DraftCreateResponse;
import com.draftagent.business.dto.DraftStatusView;
import com.draftagent.business.service.DraftService;
import com.draftagent.business.service.ToolExecutionService;
import com.draftagent.common.core.exception.ServiceException;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Map;

/**
 * 草案工具（查询 + 创建），供大模型 Tool Calling 调用。
 *
 * <p>通过 {@link ToolContext} 读取 requestId / conversationPk / turnId / userId / tenantId：
 * 创建委托 {@link ToolExecutionService}（幂等），查询委托 {@link DraftService}（归属校验）。
 * 身份来自已认证的 ToolContext，模型参数不能覆盖身份。ToolContext 不进入模型 JSON Schema。</p>
 */
@Component
public class DraftTools {

    private static final int STATUS_BAD_REQUEST = 400;

    private static final String CTX_REQUEST_ID = "requestId";
    private static final String CTX_CONVERSATION_PK = "conversationPk";
    private static final String CTX_TURN_ID = "turnId";
    private static final String CTX_USER_ID = "userId";
    private static final String CTX_TENANT_ID = "tenantId";

    private final DraftService draftService;
    private final ToolExecutionService toolExecutionService;

    public DraftTools(DraftService draftService, ToolExecutionService toolExecutionService) {
        this.draftService = draftService;
        this.toolExecutionService = toolExecutionService;
    }

    @Tool(name = "queryDraftStatus", description = "按草案ID查询草案当前状态（只读）")
    public DraftStatusView queryDraftStatus(
            @ToolParam(description = "草案ID", required = true) Long draftId,
            ToolContext toolContext) {
        Map<String, Object> context = extractContext(toolContext);
        Long userId = requireLong(context, CTX_USER_ID);
        Long tenantId = optionalLong(context, CTX_TENANT_ID);
        return draftService.queryDraftStatus(draftId, userId, tenantId);
    }

    @Tool(name = "createDraft",
            description = "创建一份新的草案并保存到数据库，返回草案ID、标题、状态和创建时间。"
                    + "仅当用户明确要求创建草案时调用；必须使用用户明确提供的标题和内容，"
                    + "缺少标题或内容时应先向用户询问，不得自行编造；"
                    + "一次创建请求只调用一次本工具，避免重复创建。")
    public DraftCreateResponse createDraft(
            @ToolParam(description = "草案标题，必须由用户明确提供，不能为空", required = true) String title,
            @ToolParam(description = "草案内容，必须由用户明确提供，不能为空", required = true) String content,
            ToolContext toolContext) {
        Map<String, Object> context = extractContext(toolContext);
        String requestId = requireString(context, CTX_REQUEST_ID);
        String turnId = requireString(context, CTX_TURN_ID);
        Long conversationPk = requireLong(context, CTX_CONVERSATION_PK);
        Long userId = requireLong(context, CTX_USER_ID);
        Long tenantId = optionalLong(context, CTX_TENANT_ID);
        return toolExecutionService.executeCreateDraft(
                requestId, conversationPk, turnId, ToolExecutionService.TOOL_CREATE_DRAFT,
                title, content, userId, tenantId);
    }

    private Map<String, Object> extractContext(ToolContext toolContext) {
        if (toolContext == null || toolContext.getContext() == null) {
            throw new ServiceException(STATUS_BAD_REQUEST, "工具上下文缺失");
        }
        return toolContext.getContext();
    }

    private String requireString(Map<String, Object> context, String key) {
        Object value = context.get(key);
        if (!(value instanceof String) || !StringUtils.hasText((String) value)) {
            throw new ServiceException(STATUS_BAD_REQUEST, "工具上下文缺少或非法的字段: " + key);
        }
        return (String) value;
    }

    private Long requireLong(Map<String, Object> context, String key) {
        Object value = context.get(key);
        if (!(value instanceof Number)) {
            throw new ServiceException(STATUS_BAD_REQUEST, "工具上下文缺少或非法的字段: " + key);
        }
        return ((Number) value).longValue();
    }

    private Long optionalLong(Map<String, Object> context, String key) {
        Object value = context.get(key);
        if (value == null) {
            return null;
        }
        if (!(value instanceof Number)) {
            throw new ServiceException(STATUS_BAD_REQUEST, "工具上下文字段类型非法: " + key);
        }
        return ((Number) value).longValue();
    }
}
