package com.draftagent.ai;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import com.draftagent.DraftAgentApplication;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实 MySQL 集成测试：验证 Spring AI 1.0.9 {@link JdbcChatMemoryRepository} 的实际读写删。
 *
 * <p>使用项目当前配置的真实 DataSource（application.yml + application-dev.yml）与自动配置的
 * JdbcChatMemoryRepository；不调用 DeepSeek、不经过 AiService、不创建业务草案。</p>
 *
 * <p>若数据库不可用或 SPRING_AI_CHAT_MEMORY 表不存在，测试会被跳过（Assumptions），
 * 并在报告中明确记为「未执行/阻塞」，绝不把跳过当作通过。</p>
 */
@SpringBootTest(classes = DraftAgentApplication.class)
class JdbcChatMemoryMySqlIntegrationTest {

    private static final String TABLE_NAME = "SPRING_AI_CHAT_MEMORY";

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcChatMemoryRepository jdbcChatMemoryRepository;

    @Autowired
    private ChatMemory chatMemory;

    private String conversationId;

    @BeforeEach
    void ensureDatabaseReady() {
        try (Connection connection = dataSource.getConnection()) {
            boolean tableExists;
            try (ResultSet tables = connection.getMetaData()
                    .getTables(connection.getCatalog(), null, TABLE_NAME, new String[] { "TABLE" })) {
                tableExists = tables.next();
            }
            Assumptions.assumeTrue(tableExists,
                    "SPRING_AI_CHAT_MEMORY 表不存在，请先手动执行 docs/sql/spring_ai_chat_memory.sql");
        }
        catch (SQLException e) {
            Assumptions.assumeTrue(false,
                    "MySQL 不可用，跳过真实集成测试: " + e.getClass().getSimpleName());
        }
        this.conversationId = UUID.randomUUID().toString();
    }

    @AfterEach
    void cleanupSafely() {
        if (this.conversationId != null && this.jdbcChatMemoryRepository != null) {
            try {
                this.jdbcChatMemoryRepository.deleteByConversationId(this.conversationId);
            }
            catch (Exception ignored) {
                // 定向清理失败也不掩盖原始测试结果
            }
        }
    }

    @Test
    void saveViaChatMemoryThenFindByConversationId() {
        String content = "DraftAgent ChatMemory 集成验证 " + this.conversationId;
        UserMessage userMessage = new UserMessage(content);

        // 1) 通过 ChatMemory 保存一条 USER 消息（内部走 JdbcChatMemoryRepository.saveAll）
        this.chatMemory.add(this.conversationId, userMessage);

        // 2) 通过 findByConversationId 读取并断言内容、角色
        List<Message> messages = this.jdbcChatMemoryRepository.findByConversationId(this.conversationId);
        assertThat(messages).hasSize(1);
        Message loaded = messages.get(0);
        assertThat(loaded).isInstanceOf(UserMessage.class);
        assertThat(loaded.getMessageType()).isEqualTo(MessageType.USER);
        assertThat(loaded.getText()).isEqualTo(content);

        // 3) 只删除本次测试 conversationId 的数据，并确认清理成功
        this.jdbcChatMemoryRepository.deleteByConversationId(this.conversationId);
        assertThat(this.jdbcChatMemoryRepository.findByConversationId(this.conversationId)).isEmpty();
    }
}
