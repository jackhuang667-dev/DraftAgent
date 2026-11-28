package com.draftagent.ai.rag.embedding;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * 独立 Embedding 配置（与 DeepSeek Chat 的 {@code spring.ai.openai.*} 完全隔离）。
 *
 * <p>默认对接 SiliconFlow：{@code https://api.siliconflow.cn}，模型 {@code BAAI/bge-m3}（固定 1024 维）。
 * API Key 仅来自环境变量 {@code EMBEDDING_API_KEY}，禁止写入配置文件/源码/测试。</p>
 */
@Component
@Validated
@ConfigurationProperties(prefix = "rag.embedding")
public class RagEmbeddingProperties {

    private String baseUrl = "https://api.siliconflow.cn";

    private String apiKey = "";

    private String model = "BAAI/bge-m3";

    @Min(value = 1, message = "rag.embedding.dimensions 必须大于 0")
    private int dimensions = 1024;

    @Min(value = 1, message = "rag.embedding.connect-timeout-ms 必须大于 0")
    private int connectTimeoutMs = 5000;

    @Min(value = 1, message = "rag.embedding.read-timeout-ms 必须大于 0")
    private int readTimeoutMs = 30000;

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public int getDimensions() {
        return dimensions;
    }

    public void setDimensions(int dimensions) {
        this.dimensions = dimensions;
    }

    public int getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public void setConnectTimeoutMs(int connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public int getReadTimeoutMs() {
        return readTimeoutMs;
    }

    public void setReadTimeoutMs(int readTimeoutMs) {
        this.readTimeoutMs = readTimeoutMs;
    }
}
