package com.draftagent.ai.config;

import com.draftagent.ai.rag.embedding.RagEmbeddingProperties;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.Assert;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * 独立 Embedding 装配（SiliconFlow OpenAI-compatible）。
 *
 * <p>仅当存在 {@code rag.embedding.api-key} 时创建 {@link EmbeddingModel}。使用独立的
 * {@link OpenAiApi}（不暴露为 Bean，避免与 DeepSeek Chat 的 OpenAiApi 冲突），独立超时，
 * 不影响现有 ChatModel/ChatClient。BAAI/bge-m3 固定 1024 维，不发送 dimensions 参数。</p>
 */
@Configuration
@Conditional(EmbeddingApiKeyPresentCondition.class)
public class EmbeddingConfig {

    @Bean
    public EmbeddingModel embeddingModel(RagEmbeddingProperties properties) {
        Assert.hasText(properties.getBaseUrl(), "rag.embedding.base-url 不能为空");
        Assert.hasText(properties.getModel(), "rag.embedding.model 不能为空");
        Assert.hasText(properties.getApiKey(), "rag.embedding.api-key 不能为空");

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()));
        requestFactory.setReadTimeout(Duration.ofMillis(properties.getReadTimeoutMs()));

        OpenAiApi embeddingApi = OpenAiApi.builder()
                .baseUrl(properties.getBaseUrl())
                .apiKey(properties.getApiKey())
                .restClientBuilder(RestClient.builder().requestFactory(requestFactory))
                .build();

        OpenAiEmbeddingOptions options = OpenAiEmbeddingOptions.builder()
                .model(properties.getModel())
                .build();

        return new OpenAiEmbeddingModel(embeddingApi, MetadataMode.EMBED, options);
    }
}
