package com.draftagent.business;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import javax.sql.DataSource;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.draftagent.DraftAgentApplication;
import com.draftagent.business.dto.TurnContext;
import com.draftagent.business.entity.Conversation;
import com.draftagent.business.entity.ConversationMessage;
import com.draftagent.business.mapper.ConversationMapper;
import com.draftagent.business.mapper.ConversationMessageMapper;
import com.draftagent.business.service.ConversationService;
import com.draftagent.common.core.exception.ServiceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ConversationService 的真实 MySQL 集成测试（T1/T2/T3 与事务回滚）。
 *
 * <p>使用唯一 UUID 隔离数据，并在消息内容/会话标题写入 {@code SVCTEST} 标记，便于独立残留核查；
 * 仅按本测试生成的会话定向清理（先删消息、再删会话），不清空数据库、不触碰其他数据。
 * 不调用 DeepSeek、不经过 AiService、不创建草案。</p>
 */
@SpringBootTest(classes = DraftAgentApplication.class)
class ConversationServiceMySqlIntegrationTest {

    private static final String MARKER = "SVCTEST";

    private static final Long USER_ID = 1001L;

    private static final Long TENANT_ID = 10L;

    private static final Long OTHER_USER_ID = 2002L;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private ConversationService conversationService;

    @Autowired
    private ConversationMapper conversationMapper;

    @Autowired
    private ConversationMessageMapper conversationMessageMapper;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private String conversationId;

    @BeforeEach
    void ensureDatabaseReady() {
        try (Connection connection = dataSource.getConnection()) {
            boolean ready = tableExists(connection, "conversation")
                    && tableExists(connection, "conversation_message");
            Assumptions.assumeTrue(ready,
                    "conversation / conversation_message 表不存在，请先执行 docs/sql/conversation_schema.sql");
        }
        catch (SQLException e) {
            Assumptions.assumeTrue(false, "MySQL 不可用，跳过真实集成测试: " + e.getClass().getSimpleName());
        }
        this.conversationId = UUID.randomUUID().toString();
    }

    @AfterEach
    void cleanupSafely() {
        Conversation conversation = findByConversationId();
        if (conversation == null || conversation.getId() == null) {
            return;
        }
        try {
            this.conversationMessageMapper.delete(new LambdaQueryWrapper<ConversationMessage>()
                    .eq(ConversationMessage::getConversationPk, conversation.getId()));
        }
        catch (Exception ignored) {
            // 定向清理失败不掩盖原始结果
        }
        try {
            this.conversationMapper.deleteById(conversation.getId());
        }
        catch (Exception ignored) {
            // 先删消息后删会话
        }
    }

    @Test
    void beginTurnCreatesConversationAndUserAndPendingAssistant() {
        String content = markerText("hello");
        TurnContext context = this.conversationService.beginTurn(
                this.conversationId, content, USER_ID, TENANT_ID);
        markConversation(context.getConversationPk());

        assertThat(context).isNotNull();
        assertThat(context.getConversationPk()).isNotNull();
        assertThat(context.getUserMessageId()).isNotNull();
        assertThat(context.getAssistantMessageId()).isNotNull();
        assertThat(context.getTurnId()).isNotBlank();

        Conversation conversation = this.conversationMapper.selectById(context.getConversationPk());
        assertThat(conversation).isNotNull();
        assertThat(conversation.getConversationId()).isEqualTo(this.conversationId);
        assertThat(conversation.getStatus()).isEqualTo(ConversationService.STATUS_ACTIVE);
        assertThat(conversation.getLastMessageSeq()).isEqualTo(2L);
        assertThat(context.getUserMessageSequence()).isEqualTo(1L);
        assertThat(context.getAssistantMessageSequence()).isEqualTo(2L);

        List<ConversationMessage> messages = messagesOf(context.getConversationPk());
        assertThat(messages).hasSize(2);

        ConversationMessage user = messages.get(0);
        ConversationMessage assistant = messages.get(1);
        assertThat(user.getRole()).isEqualTo(ConversationService.ROLE_USER);
        assertThat(user.getMessageStatus()).isEqualTo(ConversationService.MESSAGE_STATUS_SUCCESS);
        assertThat(user.getContent()).isEqualTo(content);
        assertThat(user.getSequenceNo()).isEqualTo(context.getUserMessageSequence());

        assertThat(assistant.getRole()).isEqualTo(ConversationService.ROLE_ASSISTANT);
        assertThat(assistant.getMessageStatus()).isEqualTo(ConversationService.MESSAGE_STATUS_PENDING);
        assertThat(assistant.getContent()).isEmpty();
        assertThat(assistant.getSequenceNo()).isEqualTo(context.getAssistantMessageSequence());

        assertThat(user.getTurnId()).isEqualTo(context.getTurnId());
        assertThat(assistant.getTurnId()).isEqualTo(context.getTurnId());
        assertThat(user.getSequenceNo()).isNotEqualTo(assistant.getSequenceNo());
        assertThat(assistant.getSequenceNo()).isEqualTo(user.getSequenceNo() + 1);
    }

    @Test
    void completeTurnUpdatesPendingToSuccessAndPreventsRepeat() {
        TurnContext context = this.conversationService.beginTurn(
                this.conversationId, markerText("q1"), USER_ID, TENANT_ID);
        markConversation(context.getConversationPk());
        String reply = markerText("ai-reply");

        this.conversationService.completeTurn(context.getAssistantMessageId(), reply);

        ConversationMessage assistant = this.conversationMessageMapper.selectById(context.getAssistantMessageId());
        assertThat(assistant.getMessageStatus()).isEqualTo(ConversationService.MESSAGE_STATUS_SUCCESS);
        assertThat(assistant.getContent()).isEqualTo(reply);

        assertThatThrownBy(() -> this.conversationService.completeTurn(context.getAssistantMessageId(), "再次覆盖"))
                .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> this.conversationService.failTurn(context.getAssistantMessageId(), "X"))
                .isInstanceOf(ServiceException.class);

        ConversationMessage after = this.conversationMessageMapper.selectById(context.getAssistantMessageId());
        assertThat(after.getMessageStatus()).isEqualTo(ConversationService.MESSAGE_STATUS_SUCCESS);
        assertThat(after.getContent()).isEqualTo(reply);
    }

    @Test
    void failTurnUpdatesPendingToFailedAndPreventsRepeat() {
        TurnContext context = this.conversationService.beginTurn(
                this.conversationId, markerText("q2"), USER_ID, TENANT_ID);
        markConversation(context.getConversationPk());

        this.conversationService.failTurn(context.getAssistantMessageId(), "UPSTREAM_TIMEOUT");

        ConversationMessage assistant = this.conversationMessageMapper.selectById(context.getAssistantMessageId());
        assertThat(assistant.getMessageStatus()).isEqualTo(ConversationService.MESSAGE_STATUS_FAILED);
        assertThat(assistant.getErrorCode()).isEqualTo("UPSTREAM_TIMEOUT");
        assertThat(assistant.getContent()).isNotNull();

        assertThatThrownBy(() -> this.conversationService.failTurn(context.getAssistantMessageId(), "AGAIN"))
                .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> this.conversationService.completeTurn(context.getAssistantMessageId(), "late"))
                .isInstanceOf(ServiceException.class);

        ConversationMessage after = this.conversationMessageMapper.selectById(context.getAssistantMessageId());
        assertThat(after.getMessageStatus()).isEqualTo(ConversationService.MESSAGE_STATUS_FAILED);
    }

    @Test
    void otherUserCannotContinueOthersConversation() {
        TurnContext context = this.conversationService.beginTurn(
                this.conversationId, markerText("owner"), USER_ID, TENANT_ID);
        markConversation(context.getConversationPk());

        assertThatThrownBy(() -> this.conversationService.beginTurn(
                this.conversationId, markerText("intruder"), OTHER_USER_ID, TENANT_ID))
                .isInstanceOfSatisfying(ServiceException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(403));

        assertThat(messagesOf(context.getConversationPk())).hasSize(2);
    }

    @Test
    void beginTurnRollsBackConversationMessagesAndSequenceTogether() {
        TransactionTemplate transactionTemplate = new TransactionTemplate(this.transactionManager);
        AtomicReference<Long> conversationPkRef = new AtomicReference<>();

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            TurnContext context = this.conversationService.beginTurn(
                    this.conversationId, markerText("rollback"), USER_ID, TENANT_ID);
            conversationPkRef.set(context.getConversationPk());
            throw new IllegalStateException("force rollback");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(conversationPkRef.get()).isNotNull();
        assertThat(this.conversationMapper.selectById(conversationPkRef.get())).isNull();
        assertThat(messagesOf(conversationPkRef.get())).isEmpty();
        assertThat(findByConversationId()).isNull();
    }

    private String markerText(String suffix) {
        return MARKER + "-" + this.conversationId + "-" + suffix;
    }

    private void markConversation(Long conversationPk) {
        this.conversationMapper.update(null, new LambdaUpdateWrapper<Conversation>()
                .eq(Conversation::getId, conversationPk)
                .set(Conversation::getTitle, MARKER + "-conversation"));
    }

    private Conversation findByConversationId() {
        return this.conversationMapper.selectOne(new LambdaQueryWrapper<Conversation>()
                .eq(Conversation::getConversationId, this.conversationId));
    }

    private List<ConversationMessage> messagesOf(Long conversationPk) {
        return this.conversationMessageMapper.selectList(new LambdaQueryWrapper<ConversationMessage>()
                .eq(ConversationMessage::getConversationPk, conversationPk)
                .orderByAsc(ConversationMessage::getSequenceNo));
    }

    private static boolean tableExists(Connection connection, String tableName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SHOW TABLES LIKE ?")) {
            statement.setString(1, tableName);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }
}
