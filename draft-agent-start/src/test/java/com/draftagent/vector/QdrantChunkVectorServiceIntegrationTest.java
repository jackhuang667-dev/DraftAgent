package com.draftagent.vector;

import com.draftagent.ai.rag.qdrant.QdrantCollectionAdmin;
import com.draftagent.ai.rag.qdrant.QdrantCollectionAdminImpl;
import com.draftagent.ai.rag.qdrant.QdrantCollectionInitializer;
import com.draftagent.ai.rag.qdrant.RagQdrantProperties;
import com.draftagent.ai.rag.vector.QdrantChunkVectorService;
import com.draftagent.business.entity.Document;
import com.draftagent.business.entity.DocumentChunk;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import io.qdrant.client.grpc.Points.PointStruct;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实 Qdrant 集成测试（独立临时 Collection）：
 * Point ID 稳定、重复 Upsert 不重复、企业/个人 Payload 正确（个人 tenant_id 显式 null）、按 document_id 删除。
 * 不触碰正式 Collection；结束后只删除本测试创建的临时 Collection。
 */
class QdrantChunkVectorServiceIntegrationTest {

    private static final String HOST = "localhost";

    private static final int GRPC_PORT = 6334;

    private static final String REST_BASE = "http://localhost:6333";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();

    @Test
    void upsertsPayloadAndDeletesOnlyByDocumentId() throws Exception {
        String temporaryCollection = "draftagent_chunks_it_" + UUID.randomUUID().toString().replace("-", "");
        RagQdrantProperties properties = new RagQdrantProperties();
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
            new QdrantCollectionInitializer(admin, properties).initialize();
            QdrantChunkVectorService vectorService = new QdrantChunkVectorService(client, properties);

            String enterpriseDocumentId = UUID.randomUUID().toString();
            Document enterprise = document(123L, enterpriseDocumentId, 7L, 100L);
            DocumentChunk enterpriseChunk = chunk(10001L, 0, "企业文档原文不得进入 payload");
            List<PointStruct> enterprisePoints = vectorService.buildPoints(
                    enterprise, List.of(enterpriseChunk), List.of(vector1024()));

            vectorService.upsert(temporaryCollection, enterprisePoints);
            vectorService.upsert(temporaryCollection, enterprisePoints); // 幂等：相同 chunk_id 覆盖同一 Point

            assertThat(pointsCount(temporaryCollection)).isEqualTo(1);
            JsonNode enterprisePayload = pointPayload(temporaryCollection, 10001L);
            assertThat(enterprisePayload.get("document_id").asText()).isEqualTo(enterpriseDocumentId);
            assertThat(enterprisePayload.get("document_pk").asLong()).isEqualTo(123L);
            assertThat(enterprisePayload.get("chunk_id").asLong()).isEqualTo(10001L);
            assertThat(enterprisePayload.get("chunk_index").asLong()).isZero();
            assertThat(enterprisePayload.get("user_id").asLong()).isEqualTo(7L);
            assertThat(enterprisePayload.get("tenant_id").asLong()).isEqualTo(100L);
            assertThat(enterprisePayload.has("content")).isFalse();
            assertThat(enterprisePayload.has("doc_content")).isFalse();

            Document personal = document(124L, UUID.randomUUID().toString(), 8L, null);
            DocumentChunk personalChunk = chunk(10002L, 0, "个人文档原文");
            vectorService.upsert(temporaryCollection, vectorService.buildPoints(
                    personal, List.of(personalChunk), List.of(vector1024())));

            assertThat(pointsCount(temporaryCollection)).isEqualTo(2);
            JsonNode personalPayload = pointPayload(temporaryCollection, 10002L);
            assertThat(personalPayload.has("tenant_id")).isTrue();
            assertThat(personalPayload.get("tenant_id").isNull()).isTrue();

            vectorService.deleteByDocumentId(temporaryCollection, enterpriseDocumentId);
            assertThat(pointsCount(temporaryCollection)).isEqualTo(1);
            JsonNode remaining = pointPayload(temporaryCollection, 10002L);
            assertThat(remaining.get("tenant_id").isNull()).isTrue();
        }
        finally {
            try {
                client.deleteCollectionAsync(temporaryCollection).get(5, TimeUnit.SECONDS);
            }
            catch (Exception ignored) {
                // 只清理自己创建的临时 Collection
            }
            client.close();
        }
    }

    private static long pointsCount(String collection) throws Exception {
        JsonNode root = getJson(REST_BASE + "/collections/" + collection);
        return root.path("result").path("points_count").asLong();
    }

    private static JsonNode pointPayload(String collection, long pointId) throws Exception {
        JsonNode root = getJson(REST_BASE + "/collections/" + collection + "/points/" + pointId);
        return root.path("result").path("payload");
    }

    private static JsonNode getJson(String url) throws Exception {
        HttpResponse<String> response = HTTP_CLIENT.send(
                HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return OBJECT_MAPPER.readTree(response.body());
    }

    private static Document document(long documentPk, String documentId, long userId, Long tenantId) {
        Document document = new Document();
        document.setDocumentPk(documentPk);
        document.setDocumentId(documentId);
        document.setUserId(userId);
        document.setTenantId(tenantId);
        return document;
    }

    private static DocumentChunk chunk(long chunkId, int chunkIndex, String content) {
        DocumentChunk chunk = new DocumentChunk();
        chunk.setChunkId(chunkId);
        chunk.setChunkIndex(chunkIndex);
        chunk.setContent(content);
        return chunk;
    }

    private static float[] vector1024() {
        float[] vector = new float[1024];
        vector[0] = 1.0f;
        return vector;
    }
}
