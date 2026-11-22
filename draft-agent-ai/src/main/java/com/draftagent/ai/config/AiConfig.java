package com.draftagent.ai.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

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

    @Bean
    public OpenAiChatModel openAiChatModel(
            OpenAiApi openAiApi,
            @Value("${spring.ai.openai.chat.options.model:deepseek-chat}") String model) {
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(model)
                .build();
        return OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(options)
                .build();
    }

    @Bean
    public ChatClient chatClient(OpenAiChatModel chatModel) {
        return ChatClient.create(chatModel);
    }
}
