package com.draftagent.business;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

import javax.sql.DataSource;

import com.draftagent.DraftAgentApplication;
import com.draftagent.business.entity.Conversation;
import com.draftagent.business.entity.ConversationMessage;
import com.draftagent.business.mapper.ConversationMapper;
import com.draftagent.business.mapper.ConversationMessageMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Conversation / ConversationMessage Mapper 的真实 MySQL 集成测试。
 *
 * <p>使用项目真实 DataSource 与 MyBatis-Plus Mapper，验证自增主键回填、按 ID 查询、
 * conversation_id / conversation_pk / sequence_no 等字段映射。不调用 DeepSeek、不经过 AiService。</p>
 *
 * <p>若数据库不可用或表不存在则跳过（Assumptions），绝不把跳过当通过。
 * 测试数据使用独立 UUID，且仅按本次生成的 id 定向清理，不清空既有数据。</p>
 */
@SpringBootTest(classes = DraftAgentApplication.class)
class ConversationMapperMySqlIntegrationTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private ConversationMapper conversationMapper;

    @Autowired
    private ConversationMessageMapper conversationMessageMapper;

    private String conversationId;

    private Long conversationPk;

    private Long messageId;

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
        try {
            if (this.messageId != null) {
                this.conversationMessageMapper.deleteById(this.messageId);
            }
        }
        catch (Exception ignored) {
            // 定向清理失败不掩盖原始测试结果
        }
        try {
            if (this.conversationPk != null) {
                this.conversationMapper.deleteById(this.conversationPk);
            }
        }
        catch (Exception ignored) {
            // 先删消息后删会话，避免外键约束问题
        }
    }

    @Test
    void insertConversationAndMessageAndReadBack() {
        // --- conversation 插入 + 自增主键回填 + 按 ID 查询 ---
        Conversation conversation = new Conversation();
        conversation.setConversationId(this.conversationId);
        conversation.setTitle("Mapper 集成验证");
        conversation.setStatus("ACTIVE");
        conversation.setLastMessageSeq(0L);

        int conversationRows = this.conversationMapper.insert(conversation);
        this.conversationPk = conversation.getId();

        assertThat(conversationRows).isEqualTo(1);
        assertThat(conversation.getId()).isNotNull();

        Conversation loadedConversation = this.conversationMapper.selectById(this.conversationPk);
        assertThat(loadedConversation).isNotNull();
        assertThat(loadedConversation.getId()).isEqualTo(this.conversationPk);
        assertThat(loadedConversation.getConversationId()).isEqualTo(this.conversationId);
        assertThat(loadedConversation.getStatus()).isEqualTo("ACTIVE");

        // --- conversation_message 插入 + 字段映射 ---
        String turnId = UUID.randomUUID().toString();
        ConversationMessage message = new ConversationMessage();
        message.setConversationPk(this.conversationPk);
        message.setTurnId(turnId);
        message.setSequenceNo(1L);
        message.setRole("USER");
        message.setMessageType("CHAT");
        message.setMessageStatus("SUCCESS");
        message.setContent("Mapper 集成验证消息 " + this.conversationId);

        int messageRows = this.conversationMessageMapper.insert(message);
        this.messageId = message.getId();

        assertThat(messageRows).isEqualTo(1);
        assertThat(message.getId()).isNotNull();

        ConversationMessage loadedMessage = this.conversationMessageMapper.selectById(this.messageId);
        assertThat(loadedMessage).isNotNull();
        assertThat(loadedMessage.getConversationPk()).isEqualTo(this.conversationPk);
        assertThat(loadedMessage.getSequenceNo()).isEqualTo(1L);
        assertThat(loadedMessage.getTurnId()).isEqualTo(turnId);
        assertThat(loadedMessage.getRole()).isEqualTo("USER");
        assertThat(loadedMessage.getMessageType()).isEqualTo("CHAT");
        assertThat(loadedMessage.getMessageStatus()).isEqualTo("SUCCESS");
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
