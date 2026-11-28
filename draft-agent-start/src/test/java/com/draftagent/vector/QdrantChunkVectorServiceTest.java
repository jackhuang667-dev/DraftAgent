package com.draftagent.vector;

import com.draftagent.ai.rag.qdrant.RagQdrantProperties;
import com.draftagent.ai.rag.vector.QdrantChunkVectorService;
import com.draftagent.business.document.DocumentVectorizationException;
import com.draftagent.business.entity.Document;
import com.draftagent.business.entity.DocumentChunk;
import com.google.common.util.concurrent.Futures;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.JsonWithInt.Value;
import io.qdrant.client.grpc.Points.DeletePoints;
import io.qdrant.client.grpc.Points.PointStruct;
import io.qdrant.client.grpc.Points.UpdateResult;
import io.qdrant.client.grpc.Points.UpsertPoints;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Qdrant Point 构建 / Upsert / 按 document_id 删除（Mock QdrantClient）。
 */
class QdrantChunkVectorServiceTest {

    private static final String COLLECTION = "draftagent_chunks_v1";

    @Test
    void buildsEnterprisePayloadWithoutContent() {
        QdrantChunkVectorService service = service();
        Document document = document(123L, UUID.randomUUID().toString(), 7L, 100L);
        DocumentChunk chunk = chunk(456L, 0, "这段原文不能进入 payload");

        List<PointStruct> points = service.buildPoints(document, List.of(chunk), List.of(new float[1024]));

        assertThat(points).hasSize(1);
        PointStruct point = points.get(0);
        assertThat(point.getId().getNum()).isEqualTo(456L);
        Map<String, Value> payload = point.getPayloadMap();
        assertThat(payload.get("document_id").getStringValue()).isEqualTo(document.getDocumentId());
        assertThat(payload.get("document_pk").getIntegerValue()).isEqualTo(123L);
        assertThat(payload.get("chunk_id").getIntegerValue()).isEqualTo(456L);
        assertThat(payload.get("chunk_index").getIntegerValue()).isZero();
        assertThat(payload.get("user_id").getIntegerValue()).isEqualTo(7L);
        assertThat(payload.get("tenant_id").getIntegerValue()).isEqualTo(100L);
        assertThat(payload).doesNotContainKeys("content", "doc_content", "text");
    }

    @Test
    void personalDocumentPayloadHasExplicitNullTenant() {
        QdrantChunkVectorService service = service();
        Document document = document(123L, UUID.randomUUID().toString(), 7L, null);
        DocumentChunk chunk = chunk(456L, 0, "chunk");

        List<PointStruct> points = service.buildPoints(document, List.of(chunk), List.of(new float[1024]));

        Map<String, Value> payload = points.get(0).getPayloadMap();
        assertThat(payload).containsKey("tenant_id");
        assertThat(payload.get("tenant_id").hasNullValue()).isTrue();
    }

    @Test
    void upsertUsesWaitTrueAndBatchesPoints() {
        QdrantClient client = mock(QdrantClient.class);
        when(client.upsertAsync(any(UpsertPoints.class), any(Duration.class)))
                .thenReturn(Futures.immediateFuture(UpdateResult.getDefaultInstance()));
        RagQdrantProperties properties = new RagQdrantProperties();
        QdrantChunkVectorService service = new QdrantChunkVectorService(client, properties);
        List<PointStruct> points = List.of(PointStruct.getDefaultInstance(), PointStruct.getDefaultInstance());

        service.upsert(COLLECTION, points);

        ArgumentCaptor<UpsertPoints> captor = ArgumentCaptor.forClass(UpsertPoints.class);
        verify(client).upsertAsync(captor.capture(), any(Duration.class));
        assertThat(captor.getValue().getCollectionName()).isEqualTo(COLLECTION);
        assertThat(captor.getValue().getPointsCount()).isEqualTo(2);
        assertThat(captor.getValue().getWait()).isTrue();
    }

    @Test
    void deleteByDocumentIdUsesFilterAndWaitTrue() {
        QdrantClient client = mock(QdrantClient.class);
        when(client.deleteAsync(any(DeletePoints.class), any(Duration.class)))
                .thenReturn(Futures.immediateFuture(UpdateResult.getDefaultInstance()));
        RagQdrantProperties properties = new RagQdrantProperties();
        QdrantChunkVectorService service = new QdrantChunkVectorService(client, properties);

        service.deleteByDocumentId(COLLECTION, UUID.randomUUID().toString());

        ArgumentCaptor<DeletePoints> captor = ArgumentCaptor.forClass(DeletePoints.class);
        verify(client).deleteAsync(captor.capture(), any(Duration.class));
        DeletePoints deletePoints = captor.getValue();
        assertThat(deletePoints.getCollectionName()).isEqualTo(COLLECTION);
        assertThat(deletePoints.getWait()).isTrue();
        assertThat(deletePoints.getPoints().hasFilter()).isTrue();
        assertThat(deletePoints.getPoints().getFilter().getMustCount()).isEqualTo(1);
    }

    @Test
    void invalidDocumentIdIsRejectedBeforeDeleting() {
        QdrantClient client = mock(QdrantClient.class);
        QdrantChunkVectorService service = new QdrantChunkVectorService(client, new RagQdrantProperties());

        assertThatThrownBy(() -> service.deleteByDocumentId(COLLECTION, null))
                .isInstanceOf(DocumentVectorizationException.class);
        assertThatThrownBy(() -> service.deleteByDocumentId(COLLECTION, "not-a-uuid"))
                .isInstanceOf(DocumentVectorizationException.class);
        verifyNoInteractions(client);
    }

    @Test
    void missingChunkIdIsRejected() {
        QdrantChunkVectorService service = service();
        Document document = document(123L, UUID.randomUUID().toString(), 7L, null);
        DocumentChunk chunk = chunk(null, 0, "chunk");

        assertThatThrownBy(() -> service.buildPoints(document, List.of(chunk), List.of(new float[1024])))
                .isInstanceOf(DocumentVectorizationException.class)
                .hasMessageContaining("Chunk ID");
    }

    @Test
    void upsertWithEmptyPointsDoesNotCallClient() {
        QdrantClient client = mock(QdrantClient.class);
        QdrantChunkVectorService service = new QdrantChunkVectorService(client, new RagQdrantProperties());

        service.upsert(COLLECTION, List.of());

        verify(client, never()).upsertAsync(any(UpsertPoints.class), any(Duration.class));
    }

    private static QdrantChunkVectorService service() {
        return new QdrantChunkVectorService(mock(QdrantClient.class), new RagQdrantProperties());
    }

    private static Document document(long documentPk, String documentId, long userId, Long tenantId) {
        Document document = new Document();
        document.setDocumentPk(documentPk);
        document.setDocumentId(documentId);
        document.setUserId(userId);
        document.setTenantId(tenantId);
        return document;
    }

    private static DocumentChunk chunk(Long chunkId, int chunkIndex, String content) {
        DocumentChunk chunk = new DocumentChunk();
        chunk.setChunkId(chunkId);
        chunk.setChunkIndex(chunkIndex);
        chunk.setContent(content);
        return chunk;
    }
}
