package com.draftagent.retrieval;

import com.draftagent.ai.rag.qdrant.QdrantChunkSearchService;
import com.draftagent.ai.rag.qdrant.RagQdrantProperties;
import com.draftagent.business.document.DocumentSearchException;
import com.draftagent.business.document.DocumentSearchResult;
import com.draftagent.business.document.DocumentSearchScope;
import com.google.common.util.concurrent.Futures;
import io.qdrant.client.PointIdFactory;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.ValueFactory;
import io.qdrant.client.grpc.JsonWithInt.Value;
import io.qdrant.client.grpc.Points.Condition;
import io.qdrant.client.grpc.Points.Filter;
import io.qdrant.client.grpc.Points.ScoredPoint;
import io.qdrant.client.grpc.Points.SearchPoints;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Qdrant Top-K 检索（Mock QdrantClient）：过滤条件、结果映射、异常传播。
 */
class QdrantChunkSearchServiceTest {

    private static final String COLLECTION = "draftagent_chunks_v1";

    @Test
    void enterpriseFilterMatchesOnlyTenant() {
        Filter filter = service(new RagQdrantProperties()).buildFilter(new DocumentSearchScope(7L, 100L));

        assertThat(filter.getMustCount()).isEqualTo(1);
        Condition condition = filter.getMust(0);
        assertThat(condition.hasField()).isTrue();
        assertThat(condition.getField().getKey()).isEqualTo("tenant_id");
        assertThat(condition.getField().getMatch().getInteger()).isEqualTo(100L);
        assertThat(filter.getMustList()).noneMatch(Condition::hasIsNull);
    }

    @Test
    void personalFilterRequiresTenantIsNullAndUser() {
        Filter filter = service(new RagQdrantProperties()).buildFilter(new DocumentSearchScope(7L, null));

        assertThat(filter.getMustCount()).isEqualTo(2);
        Condition tenantIsNull = filter.getMustList().stream()
                .filter(Condition::hasIsNull)
                .findFirst()
                .orElseThrow();
        assertThat(tenantIsNull.getIsNull().getKey()).isEqualTo("tenant_id");

        Condition user = filter.getMustList().stream()
                .filter(Condition::hasField)
                .findFirst()
                .orElseThrow();
        assertThat(user.getField().getKey()).isEqualTo("user_id");
        assertThat(user.getField().getMatch().getInteger()).isEqualTo(7L);
    }

    @Test
    void nullScopeIsRejected() {
        assertThatThrownBy(() -> service(new RagQdrantProperties()).buildFilter(null))
                .isInstanceOf(DocumentSearchException.class)
                .hasMessageContaining("检索范围");
    }

    @Test
    void searchAlwaysIncludesFilterAndTopK() {
        QdrantClient client = mock(QdrantClient.class);
        when(client.searchAsync(any(SearchPoints.class), any(Duration.class)))
                .thenReturn(Futures.immediateFuture(List.<ScoredPoint>of()));

        service(client, new RagQdrantProperties()).search(vector1024(), new DocumentSearchScope(7L, 100L), 5);

        ArgumentCaptor<SearchPoints> captor = ArgumentCaptor.forClass(SearchPoints.class);
        verify(client).searchAsync(captor.capture(), any(Duration.class));
        SearchPoints request = captor.getValue();
        assertThat(request.getCollectionName()).isEqualTo(COLLECTION);
        assertThat(request.getLimit()).isEqualTo(5);
        assertThat(request.getVectorCount()).isEqualTo(1024);
        assertThat(request.hasFilter()).isTrue();
        assertThat(request.getFilter().getMustCount()).isGreaterThanOrEqualTo(1);
        assertThat(request.hasWithPayload()).isTrue();
    }

    @Test
    void mapsPointIdPayloadAndScore() {
        String documentId = UUID.randomUUID().toString();
        ScoredPoint point = point(456L, documentId, 123L, 456L, 2L, 0.87f);
        QdrantClient client = mock(QdrantClient.class);
        when(client.searchAsync(any(SearchPoints.class), any(Duration.class)))
                .thenReturn(Futures.immediateFuture(List.of(point)));

        List<DocumentSearchResult> results =
                service(client, new RagQdrantProperties()).search(vector1024(), new DocumentSearchScope(7L, 100L), 5);

        assertThat(results).hasSize(1);
        DocumentSearchResult result = results.get(0);
        assertThat(result.getPointId()).isEqualTo(456L);
        assertThat(result.getDocumentId()).isEqualTo(documentId);
        assertThat(result.getDocumentPk()).isEqualTo(123L);
        assertThat(result.getChunkId()).isEqualTo(456L);
        assertThat(result.getChunkIndex()).isEqualTo(2);
        assertThat(result.getScore()).isEqualTo(0.87f);
    }

    @Test
    void returnsEmptyListWhenNoMatches() {
        QdrantClient client = mock(QdrantClient.class);
        when(client.searchAsync(any(SearchPoints.class), any(Duration.class)))
                .thenReturn(Futures.immediateFuture(List.<ScoredPoint>of()));

        List<DocumentSearchResult> results =
                service(client, new RagQdrantProperties()).search(vector1024(), new DocumentSearchScope(7L, 100L), 5);

        assertThat(results).isEmpty();
    }

    @Test
    void qdrantFailurePropagatesAsBusinessException() {
        QdrantClient client = mock(QdrantClient.class);
        when(client.searchAsync(any(SearchPoints.class), any(Duration.class)))
                .thenReturn(Futures.immediateFailedFuture(new RuntimeException("connection refused")));

        assertThatThrownBy(() -> service(client, new RagQdrantProperties())
                .search(vector1024(), new DocumentSearchScope(7L, 100L), 5))
                .isInstanceOf(DocumentSearchException.class)
                .hasMessageContaining("Qdrant 检索失败");
    }

    @Test
    void missingPayloadFieldIsRejected() {
        Map<String, Value> payload = payload(UUID.randomUUID().toString(), 123L, 456L, 0L);
        payload.remove("document_pk");
        ScoredPoint point = ScoredPoint.newBuilder()
                .setId(PointIdFactory.id(456L))
                .setScore(0.5f)
                .putAllPayload(payload)
                .build();
        QdrantClient client = mock(QdrantClient.class);
        when(client.searchAsync(any(SearchPoints.class), any(Duration.class)))
                .thenReturn(Futures.immediateFuture(List.of(point)));

        assertThatThrownBy(() -> service(client, new RagQdrantProperties())
                .search(vector1024(), new DocumentSearchScope(7L, 100L), 5))
                .isInstanceOf(DocumentSearchException.class)
                .hasMessageContaining("document_pk");
    }

    @Test
    void wrongPayloadTypeIsRejected() {
        Map<String, Value> payload = payload(UUID.randomUUID().toString(), 123L, 456L, 0L);
        payload.put("document_pk", ValueFactory.value("not-a-number"));
        ScoredPoint point = ScoredPoint.newBuilder()
                .setId(PointIdFactory.id(456L))
                .setScore(0.5f)
                .putAllPayload(payload)
                .build();
        QdrantClient client = mock(QdrantClient.class);
        when(client.searchAsync(any(SearchPoints.class), any(Duration.class)))
                .thenReturn(Futures.immediateFuture(List.of(point)));

        assertThatThrownBy(() -> service(client, new RagQdrantProperties())
                .search(vector1024(), new DocumentSearchScope(7L, 100L), 5))
                .isInstanceOf(DocumentSearchException.class)
                .hasMessageContaining("Payload");
    }

    @Test
    void invalidDocumentIdIsRejected() {
        ScoredPoint point = point(456L, "not-a-uuid", 123L, 456L, 0L, 0.5f);
        QdrantClient client = mock(QdrantClient.class);
        when(client.searchAsync(any(SearchPoints.class), any(Duration.class)))
                .thenReturn(Futures.immediateFuture(List.of(point)));

        assertThatThrownBy(() -> service(client, new RagQdrantProperties())
                .search(vector1024(), new DocumentSearchScope(7L, 100L), 5))
                .isInstanceOf(DocumentSearchException.class)
                .hasMessageContaining("document_id");
    }

    @Test
    void wrongVectorDimensionIsRejectedBeforeCallingQdrant() {
        QdrantClient client = mock(QdrantClient.class);

        assertThatThrownBy(() -> service(client, new RagQdrantProperties())
                .search(new float[512], new DocumentSearchScope(7L, 100L), 5))
                .isInstanceOf(DocumentSearchException.class)
                .hasMessageContaining("维度");
        verifyNoInteractions(client);
    }

    @Test
    void nonPositiveTopKIsRejectedBeforeCallingQdrant() {
        QdrantClient client = mock(QdrantClient.class);

        assertThatThrownBy(() -> service(client, new RagQdrantProperties())
                .search(vector1024(), new DocumentSearchScope(7L, 100L), 0))
                .isInstanceOf(DocumentSearchException.class)
                .hasMessageContaining("topK");
        verifyNoInteractions(client);
    }

    private static QdrantChunkSearchService service(RagQdrantProperties properties) {
        return service(mock(QdrantClient.class), properties);
    }

    private static QdrantChunkSearchService service(QdrantClient client, RagQdrantProperties properties) {
        properties.setCollection(COLLECTION);
        properties.setDimensions(1024);
        properties.setTimeoutMs(10000);
        return new QdrantChunkSearchService(client, properties);
    }

    private static ScoredPoint point(long pointId, String documentId, long documentPk,
                                     long chunkId, long chunkIndex, float score) {
        return ScoredPoint.newBuilder()
                .setId(PointIdFactory.id(pointId))
                .setScore(score)
                .putAllPayload(payload(documentId, documentPk, chunkId, chunkIndex))
                .build();
    }

    private static Map<String, Value> payload(String documentId, long documentPk, long chunkId, long chunkIndex) {
        Map<String, Value> payload = new LinkedHashMap<>();
        payload.put("document_id", ValueFactory.value(documentId));
        payload.put("document_pk", ValueFactory.value(documentPk));
        payload.put("chunk_id", ValueFactory.value(chunkId));
        payload.put("chunk_index", ValueFactory.value(chunkIndex));
        return payload;
    }

    private static float[] vector1024() {
        float[] vector = new float[1024];
        vector[0] = 1.0f;
        return vector;
    }
}
