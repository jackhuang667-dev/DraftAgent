package com.draftagent.ai.rag.qdrant;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * 独立 Qdrant 配置（与 Embedding、DeepSeek Chat 完全隔离）。
 *
 * <p>默认：{@code localhost:6334}、Collection {@code draftagent_chunks_v1}、1024 维、Cosine。
 * 默认不在启动时连接/初始化 Qdrant（{@code initialize-on-startup=false}），避免无 Qdrant
 * 环境下影响应用启动。</p>
 */
@Component
@Validated
@ConfigurationProperties(prefix = "rag.qdrant")
public class RagQdrantProperties {

    private String host = "localhost";

    @Min(value = 1, message = "rag.qdrant.grpc-port 必须在 1..65535")
    @Max(value = 65535, message = "rag.qdrant.grpc-port 必须在 1..65535")
    private int grpcPort = 6334;

    private String collection = "draftagent_chunks_v1";

    @Min(value = 1, message = "rag.qdrant.dimensions 必须大于 0")
    private int dimensions = 1024;

    @Pattern(regexp = "(?i)cosine|euclid|dot|manhattan",
            message = "rag.qdrant.distance 仅支持 Cosine/Euclid/Dot/Manhattan")
    private String distance = "Cosine";

    @Min(value = 1, message = "rag.qdrant.timeout-ms 必须大于 0")
    private int timeoutMs = 10000;

    private boolean initializeOnStartup = false;

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getGrpcPort() {
        return grpcPort;
    }

    public void setGrpcPort(int grpcPort) {
        this.grpcPort = grpcPort;
    }

    public String getCollection() {
        return collection;
    }

    public void setCollection(String collection) {
        this.collection = collection;
    }

    public int getDimensions() {
        return dimensions;
    }

    public void setDimensions(int dimensions) {
        this.dimensions = dimensions;
    }

    public String getDistance() {
        return distance;
    }

    public void setDistance(String distance) {
        this.distance = distance;
    }

    public int getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(int timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public boolean isInitializeOnStartup() {
        return initializeOnStartup;
    }

    public void setInitializeOnStartup(boolean initializeOnStartup) {
        this.initializeOnStartup = initializeOnStartup;
    }
}
