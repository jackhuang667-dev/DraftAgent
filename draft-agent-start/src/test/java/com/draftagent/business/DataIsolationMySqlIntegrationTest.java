package com.draftagent.business;

import java.util.UUID;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.draftagent.DraftAgentApplication;
import com.draftagent.business.dto.DraftStatusView;
import com.draftagent.business.entity.Conversation;
import com.draftagent.business.entity.ConversationMessage;
import com.draftagent.business.entity.Draft;
import com.draftagent.business.mapper.ConversationMapper;
import com.draftagent.business.mapper.ConversationMessageMapper;
import com.draftagent.business.mapper.DraftMapper;
import com.draftagent.business.service.ConversationService;
import com.draftagent.business.service.DraftService;
import com.draftagent.common.core.exception.ServiceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 真实 MySQL 数据权限隔离测试（会话/草案）。
 *
 * <p>用户 A 无法继续用户 B 的会话；用户 A 无法查询用户 B 的草案。使用唯一数据并定向清理。</p>
 */
@SpringBootTest(classes = DraftAgentApplication.class)
class DataIsolationMySqlIntegrationTest {

    private static final Long USER_A = 9001L;

    private static final Long USER_B = 9002L;

    private static final Long TENANT_ID = 7L;

    @Autowired
    private ConversationService conversationService;

    @Autowired
    private DraftService draftService;

    @Autowired
    private ConversationMapper conversationMapper;

    @Autowired
    private ConversationMessageMapper conversationMessageMapper;

    @Autowired
    private DraftMapper draftMapper;

    private String conversationId;

    private Long draftId;

    @AfterEach
    void cleanupSafely() {
        if (this.conversationId != null) {
            Conversation conversation = this.conversationMapper.selectOne(new LambdaQueryWrapper<Conversation>()
                    .eq(Conversation::getConversationId, this.conversationId));
            if (conversation != null && conversation.getId() != null) {
                try {
                    this.conversationMessageMapper.delete(new LambdaQueryWrapper<ConversationMessage>()
                            .eq(ConversationMessage::getConversationPk, conversation.getId()));
                }
                catch (Exception ignored) {
                    // ignore
                }
                try {
                    this.conversationMapper.deleteById(conversation.getId());
                }
                catch (Exception ignored) {
                    // ignore
                }
            }
        }
        if (this.draftId != null) {
            try {
                this.draftMapper.deleteById(this.draftId);
            }
            catch (Exception ignored) {
                // ignore
            }
        }
    }

    @Test
    void userCannotContinueOthersConversation() {
        this.conversationId = UUID.randomUUID().toString();
        this.conversationService.beginTurn(this.conversationId, "owner message", USER_A, TENANT_ID);

        assertThatThrownBy(() -> this.conversationService.beginTurn(
                this.conversationId, "intruder message", USER_B, TENANT_ID))
                .isInstanceOfSatisfying(ServiceException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(403));
    }

    @Test
    void userCannotQueryOthersDraft() {
        Draft draft = this.draftService.createDraft(
                "ISOLATION-" + UUID.randomUUID(), "content", USER_A, TENANT_ID);
        this.draftId = draft.getId();

        DraftStatusView ownView = this.draftService.queryDraftStatus(this.draftId, USER_A, TENANT_ID);
        assertThat(ownView.isFound()).isTrue();

        assertThatThrownBy(() -> this.draftService.queryDraftStatus(this.draftId, USER_B, TENANT_ID))
                .isInstanceOfSatisfying(ServiceException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(403));
    }
}
