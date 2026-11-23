package com.draftagent.ai.tool;

import com.draftagent.business.dto.DraftCreateResponse;
import com.draftagent.business.dto.DraftStatusView;
import com.draftagent.business.entity.Draft;
import com.draftagent.business.service.DraftService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 草案工具（查询 + 创建），供大模型 Tool Calling 调用。
 *
 * <p>工具层只做参数接收与委托，所有业务校验、权限、租户与状态判断均由
 * {@link DraftService} 负责；本类不直接访问 Mapper 或数据库。</p>
 */
@Component
public class DraftTools {

    private final DraftService draftService;

    public DraftTools(DraftService draftService) {
        this.draftService = draftService;
    }

    @Tool(name = "queryDraftStatus", description = "按草案ID查询草案当前状态（只读）")
    public DraftStatusView queryDraftStatus(
            @ToolParam(description = "草案ID", required = true) Long draftId) {
        return draftService.queryDraftStatus(draftId);
    }

    @Tool(name = "createDraft",
            description = "创建一份新的草案并保存到数据库，返回草案ID、标题、状态和创建时间。"
                    + "仅当用户明确要求创建草案时调用；必须使用用户明确提供的标题和内容，"
                    + "缺少标题或内容时应先向用户询问，不得自行编造；"
                    + "一次创建请求只调用一次本工具，避免重复创建。")
    public DraftCreateResponse createDraft(
            @ToolParam(description = "草案标题，必须由用户明确提供，不能为空", required = true) String title,
            @ToolParam(description = "草案内容，必须由用户明确提供，不能为空", required = true) String content) {
        Draft draft = draftService.createDraft(title, content);
        return new DraftCreateResponse(
                draft.getId(), draft.getTitle(), draft.getStatus(), draft.getCreatedAt());
    }
}
