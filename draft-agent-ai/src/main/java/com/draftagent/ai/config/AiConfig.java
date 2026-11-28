package com.draftagent.ai.config;

import com.draftagent.common.core.exception.ServiceException;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.tool.execution.DefaultToolExecutionExceptionProcessor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * Spring AI 配置。
 *
 * <p>说明：官方 OpenAI Starter 的自动配置在缺少 api-key 时会在启动阶段抛出
 * {@code Assert.hasText} 导致应用无法启动。为支持"无 Key 也能启动"，本模块已在
 * application.yml 中通过 {@code spring.autoconfigure.exclude} 排除 OpenAI 自动配置，
 * 改为在此按条件手动装配。</p>
 *
 * <p>仅当存在非空 DEEPSEEK_API_KEY 时，才创建 OpenAI ChatModel 与 ChatClient；
 * 无 Key 时不创建任何模型 Bean，由 AiService 返回 HTTP 503。</p>
 */
@Configuration
@Conditional(ApiKeyPresentCondition.class)
public class AiConfig {

    @Bean
    public OpenAiApi openAiApi(
            @Value("${spring.ai.openai.base-url:https://api.deepseek.com}") String baseUrl,
            @Value("${spring.ai.openai.api-key}") String apiKey,
            RestClient.Builder restClientBuilder) {
        return OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .restClientBuilder(restClientBuilder)
                .build();
    }

    /**
     * 工具执行异常处理器：业务 {@link ServiceException} 原样抛出（保留 409/400/503 等语义），
     * 其它工具异常统一抛 {@code ToolExecutionException}，从而不会被降级为模型文本（HTTP 200）。
     */
    @Bean
    public ToolCallingManager toolCallingManager() {
        DefaultToolExecutionExceptionProcessor exceptionProcessor = DefaultToolExecutionExceptionProcessor.builder()
                .alwaysThrow(true)
                .rethrowExceptions(List.<Class<? extends RuntimeException>>of(ServiceException.class))
                .build();
        return ToolCallingManager.builder()
                .toolExecutionExceptionProcessor(exceptionProcessor)
                .build();
    }

    @Bean
    public OpenAiChatModel openAiChatModel(
            OpenAiApi openAiApi,
            @Value("${spring.ai.openai.chat.options.model:deepseek-chat}") String model,
            ToolCallingManager toolCallingManager) {
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(model)
                .build();
        return OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(options)
                .toolCallingManager(toolCallingManager)
                .build();
    }

    /**
     * 基于 Spring AI 自动配置的 JdbcChatMemoryRepository 构建 ChatMemory。
     *
     * <p>不要自行 new InMemoryChatMemoryRepository，也不重复创建 JdbcChatMemoryRepository；
     * 由 {@code JdbcChatMemoryRepositoryAutoConfiguration} 提供并持久化到
     * {@code SPRING_AI_CHAT_MEMORY}。此处仅控制上下文窗口大小。</p>
     */
    @Bean
    public ChatMemory chatMemory(JdbcChatMemoryRepository jdbcChatMemoryRepository) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(jdbcChatMemoryRepository)
                .maxMessages(20)
                .build();
    }

    /**
     * 不再挂载 MessageChatMemoryAdvisor：ChatMemory 由 {@code AiService} 手动管理读写。
     * 保留 ChatMemory Bean 与 JDBC Repository 配置不变。
     */
    @Bean
    public ChatClient chatClient(OpenAiChatModel chatModel) {
        return ChatClient.create(chatModel);
    }
}
