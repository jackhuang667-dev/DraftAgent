package com.draftagent.retrieval;

import com.draftagent.ai.rag.qdrant.QdrantChunkSearchService;
import com.draftagent.ai.rag.qdrant.QdrantCollectionAdmin;
import com.draftagent.ai.rag.qdrant.QdrantCollectionAdminImpl;
import com.draftagent.ai.rag.qdrant.QdrantCollectionInitializer;
import com.draftagent.ai.rag.qdrant.RagQdrantProperties;
import com.draftagent.ai.rag.vector.QdrantChunkVectorService;
import com.draftagent.business.document.DocumentSearchResult;
import com.draftagent.business.document.DocumentSearchScope;
import com.draftagent.business.entity.Document;
import com.draftagent.business.entity.DocumentChunk;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

/**
 * 真实 Qdrant 集成测试（独立临时 Collection）：
 * 企业/个人检索隔离、Top-K 结果字段与 score 映射、空结果。
 * 不触碰正式 Collection；结束后只删除本测试创建的临时 Collection。
 */
class DocumentVectorSearchIntegrationTest {

    private static final String HOST = "localhost";

    private static final int GRPC_PORT = 6334;

    @Test
    void retrievesOnlyWithinTenantOrPersonalScope() throws Exception {
        String temporaryCollection = "draftagent_search_it_" + UUID.randomUUID().toString().replace("-", "");
        RagQdrantProperties properties = new RagQdrantProperties();
        properties.setHost(HOST);
        properties.setGrpcPort(GRPC_PORT);
        properties.setCollection(temporaryCollection);
        properties.setDimensions(4);
        properties.setDistance("Cosine");
        properties.setTimeoutMs(10000);

        QdrantClient client = new QdrantClient(
                QdrantGrpcClient.newBuilder(HOST, GRPC_PORT, false)
                        .withTimeout(Duration.ofSeconds(10))
                        .build());
        try {
            try {
                client.collectionExistsAsync("__draftagent_search_probe__").get(3, TimeUnit.SECONDS);
            }
            catch (Exception e) {
                Assumptions.abort("Qdrant 不可用，跳过集成测试: " + e.getClass().getSimpleName());
            }

            QdrantCollectionAdmin admin = new QdrantCollectionAdminImpl(client, properties);
            new QdrantCollectionInitializer(admin, properties).initialize();
            QdrantChunkVectorService vectorService = new QdrantChunkVectorService(client, properties);
            QdrantChunkSearchService searchService = new QdrantChunkSearchService(client, properties);

            float[] query = vector4();
            String enterpriseADocumentId = UUID.randomUUID().toString();
            String enterpriseBDocumentId = UUID.randomUUID().toString();
            String personal7DocumentId = UUID.randomUUID().toString();
            String personal8DocumentId = UUID.randomUUID().toString();

            upsert(vectorService, temporaryCollection, query,
                    document(101L, enterpriseADocumentId, 7L, 100L), chunk(10001L, 0));
            upsert(vectorService, temporaryCollection, query,
                    document(102L, enterpriseBDocumentId, 9L, 200L), chunk(10002L, 0));
            upsert(vectorService, temporaryCollection, query,
                    document(103L, personal7DocumentId, 7L, null), chunk(10003L, 0));
            upsert(vectorService, temporaryCollection, query,
                    document(104L, personal8DocumentId, 8L, null), chunk(10004L, 0));

            List<DocumentSearchResult> tenantA =
                    searchService.search(query, new DocumentSearchScope(7L, 100L), 10);
            assertThat(tenantA).extracting(DocumentSearchResult::getChunkId).containsExactly(10001L);
            DocumentSearchResult tenantAResult = tenantA.get(0);
            assertThat(tenantAResult.getPointId()).isEqualTo(10001L);
            assertThat(tenantAResult.getDocumentId()).isEqualTo(enterpriseADocumentId);
            assertThat(tenantAResult.getDocumentPk()).isEqualTo(101L);
            assertThat(tenantAResult.getChunkIndex()).isZero();
            assertThat(tenantAResult.getScore()).isCloseTo(1.0f, offset(0.0001f));

            List<DocumentSearchResult> tenantB =
                    searchService.search(query, new DocumentSearchScope(9L, 200L), 10);
            assertThat(tenantB).extracting(DocumentSearchResult::getChunkId).containsExactly(10002L);

            List<DocumentSearchResult> personal7 =
                    searchService.search(query, new DocumentSearchScope(7L, null), 10);
            assertThat(personal7).extracting(DocumentSearchResult::getChunkId).containsExactly(10003L);

            List<DocumentSearchResult> personal8 =
                    searchService.search(query, new DocumentSearchScope(8L, null), 10);
            assertThat(personal8).extracting(DocumentSearchResult::getChunkId).containsExactly(10004L);

            assertThat(searchService.search(query, new DocumentSearchScope(7L, 999L), 10)).isEmpty();

            List<DocumentSearchResult> topOne = searchService.search(query, new DocumentSearchScope(7L, 100L), 1);
            assertThat(topOne).hasSize(1);
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

    private static void upsert(QdrantChunkVectorService vectorService, String collection, float[] vector,
                               Document document, DocumentChunk chunk) {
        vectorService.upsert(collection, vectorService.buildPoints(document, List.of(chunk), List.of(vector)));
    }

    private static Document document(long documentPk, String documentId, long userId, Long tenantId) {
        Document document = new Document();
        document.setDocumentPk(documentPk);
        document.setDocumentId(documentId);
        document.setUserId(userId);
        document.setTenantId(tenantId);
        return document;
    }

    private static DocumentChunk chunk(long chunkId, int chunkIndex) {
        DocumentChunk chunk = new DocumentChunk();
        chunk.setChunkId(chunkId);
        chunk.setChunkIndex(chunkIndex);
        chunk.setContent("原文不进入 payload");
        return chunk;
    }

    private static float[] vector4() {
        return new float[] {1.0f, 0.0f, 0.0f, 0.0f};
    }
}
