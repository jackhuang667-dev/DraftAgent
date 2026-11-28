package com.draftagent.ai;

import java.time.LocalDateTime;
import java.util.Map;

import com.draftagent.ai.tool.DraftTools;
import com.draftagent.business.dto.DraftCreateResponse;
import com.draftagent.business.dto.DraftStatusView;
import com.draftagent.business.service.DraftService;
import com.draftagent.business.service.ToolExecutionService;
import com.draftagent.common.core.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * DraftTools 的 ToolContext 读取、身份传递与委托行为测试（不连接数据库、不调用真实模型）。
 */
class DraftToolsToolContextTest {

    private DraftService draftService;

    private ToolExecutionService toolExecutionService;

    private DraftTools draftTools;

    @BeforeEach
    void setUp() {
        this.draftService = mock(DraftService.class);
        this.toolExecutionService = mock(ToolExecutionService.class);
        this.draftTools = new DraftTools(this.draftService, this.toolExecutionService);
    }

    @Test
    void createDraft_readsToolContextIncludingIdentity_andDelegates() {
        DraftCreateResponse expected = new DraftCreateResponse(1L, "标题", "DRAFT", LocalDateTime.now());
        when(this.toolExecutionService.executeCreateDraft(
                "rid", 9L, "tid", ToolExecutionService.TOOL_CREATE_DRAFT, "标题", "内容", 7L, 8L))
                .thenReturn(expected);
        ToolContext context = new ToolContext(Map.of(
                "requestId", "rid",
                "conversationPk", 9L,
                "turnId", "tid",
                "userId", 7L,
                "tenantId", 8L));

        DraftCreateResponse actual = this.draftTools.createDraft("标题", "内容", context);

        assertThat(actual).isSameAs(expected);
        verify(this.toolExecutionService).executeCreateDraft(
                "rid", 9L, "tid", ToolExecutionService.TOOL_CREATE_DRAFT, "标题", "内容", 7L, 8L);
    }

    @Test
    void createDraft_missingContext_throws400_andDoesNotDelegate() {
        assertThatThrownBy(() -> this.draftTools.createDraft("标题", "内容", null))
                .isInstanceOfSatisfying(ServiceException.class, ex -> assertThat(ex.getCode()).isEqualTo(400));

        verifyNoInteractions(this.toolExecutionService);
    }

    @Test
    void createDraft_missingRequestId_throws400_andDoesNotDelegate() {
        ToolContext context = new ToolContext(Map.of(
                "conversationPk", 9L,
                "turnId", "tid",
                "userId", 7L));

        assertThatThrownBy(() -> this.draftTools.createDraft("标题", "内容", context))
                .isInstanceOfSatisfying(ServiceException.class, ex -> assertThat(ex.getCode()).isEqualTo(400));

        verifyNoInteractions(this.toolExecutionService);
    }

    @Test
    void createDraft_missingUserId_throws400_andDoesNotDelegate() {
        ToolContext context = new ToolContext(Map.of(
                "requestId", "rid",
                "conversationPk", 9L,
                "turnId", "tid"));

        assertThatThrownBy(() -> this.draftTools.createDraft("标题", "内容", context))
                .isInstanceOfSatisfying(ServiceException.class, ex -> assertThat(ex.getCode()).isEqualTo(400));

        verifyNoInteractions(this.toolExecutionService);
    }

    @Test
    void queryDraftStatus_passesIdentityAndDelegates() {
        DraftStatusView view = new DraftStatusView(5L, true, "DRAFT", "查询成功");
        when(this.draftService.queryDraftStatus(5L, 7L, 8L)).thenReturn(view);
        ToolContext context = new ToolContext(Map.of("userId", 7L, "tenantId", 8L));

        DraftStatusView actual = this.draftTools.queryDraftStatus(5L, context);

        assertThat(actual).isSameAs(view);
        verify(this.draftService).queryDraftStatus(5L, 7L, 8L);
        verifyNoInteractions(this.toolExecutionService);
    }
}
