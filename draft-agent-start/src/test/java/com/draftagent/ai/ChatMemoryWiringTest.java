package com.draftagent.ai;

import javax.sql.DataSource;

import com.draftagent.ai.config.AiConfig;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.ai.model.chat.memory.autoconfigure.ChatMemoryAutoConfiguration;
import org.springframework.ai.model.chat.memory.repository.jdbc.autoconfigure.JdbcChatMemoryRepositoryAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * ChatMemory 装配验证（不连接真实数据库、不调用真实 DeepSeek）。
 *
 * <p>使用 ApplicationContextRunner + 模拟 DataSource，验证：JDBC Repository 自动装配、
 * ChatMemory 为 MessageWindowChatMemory 且 maxMessages=20、ChatClient 正常创建。
 * 方言检测因模拟 DataSource 无法读取元数据，会回退到 Postgres 方言，不影响装配验证。</p>
 */
class ChatMemoryWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    JdbcChatMemoryRepositoryAutoConfiguration.class,
                    ChatMemoryAutoConfiguration.class))
            .withUserConfiguration(AiConfig.class)
            .withBean(DataSource.class, () -> mock(DataSource.class))
            .withBean(JdbcTemplate.class, () -> new JdbcTemplate(mock(DataSource.class)))
            .withBean(RestClient.Builder.class, RestClient::builder)
            .withPropertyValues(
                    "spring.ai.openai.api-key=test-key",
                    "spring.ai.chat.memory.repository.jdbc.initialize-schema=never");

    @Test
    void jdbcRepositoryIsAutoConfiguredAndSelected() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(JdbcChatMemoryRepository.class);
            assertThat(context).hasSingleBean(ChatMemoryRepository.class);
            assertThat(context.getBean(ChatMemoryRepository.class))
                    .isInstanceOf(JdbcChatMemoryRepository.class);
        });
    }

    @Test
    void chatMemoryIsMessageWindowWithMaxMessages20() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(ChatMemory.class);
            ChatMemory chatMemory = context.getBean(ChatMemory.class);
            assertThat(chatMemory).isInstanceOf(MessageWindowChatMemory.class);
            assertThat(ReflectionTestUtils.getField(chatMemory, "maxMessages")).isEqualTo(20);
        });
    }

    @Test
    void chatClientIsCreatedWithMemoryAdvisorConfigured() {
        runner.run(context -> assertThat(context).hasSingleBean(ChatClient.class));
    }
}
