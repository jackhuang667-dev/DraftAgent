package com.draftagent.ai.config;

import com.draftagent.ai.rag.qdrant.RagQdrantProperties;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * 独立 Qdrant Client 配置（与 Embedding 配置分离）。
 *
 * <p>构建 Client 不会发起网络连接（gRPC channel 懒连接）；实际连接发生在 Collection 初始化
 * 或后续业务调用时。超时通过 {@link QdrantGrpcClient.Builder#withTimeout(Duration)} 设置。</p>
 */
@Configuration
public class QdrantConfig {

    @Bean(destroyMethod = "close")
    public QdrantClient qdrantClient(RagQdrantProperties properties) {
        QdrantGrpcClient grpcClient = QdrantGrpcClient
                .newBuilder(properties.getHost(), properties.getGrpcPort(), false)
                .withTimeout(Duration.ofMillis(properties.getTimeoutMs()))
                .build();
        return new QdrantClient(grpcClient);
    }
}
