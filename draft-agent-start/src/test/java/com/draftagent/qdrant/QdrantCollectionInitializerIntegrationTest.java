package com.draftagent.qdrant;

import com.draftagent.ai.rag.qdrant.QdrantCollectionAdmin;
import com.draftagent.ai.rag.qdrant.QdrantCollectionAdminImpl;
import com.draftagent.ai.rag.qdrant.QdrantCollectionInitializer;
import com.draftagent.ai.rag.qdrant.RagQdrantProperties;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import io.qdrant.client.grpc.Collections.CollectionInfo;
import io.qdrant.client.grpc.Collections.Distance;
import io.qdrant.client.grpc.Collections.VectorParams;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实 Qdrant 集成测试：使用独立临时 Collection，验证创建与幂等；结束后仅删除自己创建的临时 Collection。
 * 不触碰正式 Collection（draftagent_chunks_v1）。
 */
class QdrantCollectionInitializerIntegrationTest {

    private static final String HOST = "localhost";

    private static final int GRPC_PORT = 6334;

    @Test
    void initializesTemporaryCollectionIdempotently() throws Exception {
        RagQdrantProperties properties = new RagQdrantProperties();
        String temporaryCollection = "draftagent_chunks_it_" + UUID.randomUUID().toString().replace("-", "");
        properties.setHost(HOST);
        properties.setGrpcPort(GRPC_PORT);
        properties.setCollection(temporaryCollection);
        properties.setDimensions(1024);
        properties.setDistance("Cosine");
        properties.setTimeoutMs(10000);

        QdrantClient client = new QdrantClient(
                QdrantGrpcClient.newBuilder(HOST, GRPC_PORT, false)
                        .withTimeout(Duration.ofSeconds(10))
                        .build());
        try {
            try {
                client.collectionExistsAsync("__draftagent_probe__").get(3, TimeUnit.SECONDS);
            }
            catch (Exception e) {
                Assumptions.abort("Qdrant 不可用，跳过集成测试: " + e.getClass().getSimpleName());
            }

            QdrantCollectionAdmin admin = new QdrantCollectionAdminImpl(client, properties);
            QdrantCollectionInitializer initializer = new QdrantCollectionInitializer(admin, properties);

            initializer.initialize();

            assertThat(client.collectionExistsAsync(temporaryCollection).get(5, TimeUnit.SECONDS)).isTrue();
            CollectionInfo info = client.getCollectionInfoAsync(temporaryCollection).get(5, TimeUnit.SECONDS);
            VectorParams vectorParams = info.getConfig().getParams().getVectorsConfig().getParams();
            assertThat(vectorParams.getSize()).isEqualTo(1024);
            assertThat(vectorParams.getDistance()).isEqualTo(Distance.Cosine);
            assertThat(info.getPayloadSchemaMap()).containsKeys("tenant_id", "user_id", "document_id");

            // 幂等：重复初始化不报错，索引仍存在
            initializer.initialize();
            CollectionInfo afterSecondRun = client.getCollectionInfoAsync(temporaryCollection).get(5, TimeUnit.SECONDS);
            assertThat(afterSecondRun.getPayloadSchemaMap()).containsKeys("tenant_id", "user_id", "document_id");
        }
        finally {
            try {
                client.deleteCollectionAsync(temporaryCollection).get(5, TimeUnit.SECONDS);
            }
            catch (Exception ignored) {
                // 只清理自己创建的临时 Collection；失败不影响测试结论
            }
            client.close();
        }
    }
}
