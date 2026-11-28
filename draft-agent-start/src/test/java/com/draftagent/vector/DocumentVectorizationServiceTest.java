package com.draftagent.vector;

import com.draftagent.ai.rag.qdrant.RagQdrantProperties;
import com.draftagent.ai.rag.vector.DocumentEmbeddingService;
import com.draftagent.ai.rag.vector.DocumentVectorizationService;
import com.draftagent.ai.rag.vector.QdrantChunkVectorService;
import com.draftagent.business.document.DocumentVectorizationException;
import com.draftagent.business.entity.Document;
import com.draftagent.business.entity.DocumentChunk;
import io.qdrant.client.grpc.Points.PointStruct;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 向量化编排测试（Mock EmbeddingService / QdrantChunkVectorService，不发起真实调用）。
 */
class DocumentVectorizationServiceTest {

    private static final String COLLECTION = "draftagent_chunks_v1";

    private DocumentEmbeddingService embeddingService;

    private QdrantChunkVectorService chunkVectorService;

    private DocumentVectorizationService vectorizationService;

    @BeforeEach
    void setUp() {
        this.embeddingService = mock(DocumentEmbeddingService.class);
        this.chunkVectorService = mock(QdrantChunkVectorService.class);
        RagQdrantProperties properties = new RagQdrantProperties();
        properties.setCollection(COLLECTION);
        this.vectorizationService = new DocumentVectorizationService(
                this.embeddingService, this.chunkVectorService, properties);
        when(this.chunkVectorService.buildPoints(any(), anyList(), anyList())).thenAnswer(invocation -> {
            List<DocumentChunk> batch = invocation.getArgument(1);
            List<PointStruct> points = new ArrayList<>(batch.size());
            for (int index = 0; index < batch.size(); index++) {
                points.add(PointStruct.getDefaultInstance());
            }
            return points;
        });
    }

    @Test
    void singleChunkIsEmbeddedAndUpserted() {
        stubEmbeddingEcho();
        Document document = document();
        List<DocumentChunk> chunks = chunks(5);

        int upserted = this.vectorizationService.vectorize(document, chunks);

        assertThat(upserted).isEqualTo(5);
        verify(this.embeddingService).embed(anyList());
        verify(this.chunkVectorService).upsert(eq(COLLECTION), anyList());
    }

    @Test
    void batchesAreLimitedTo32Chunks() {
        stubEmbeddingEcho();
        List<DocumentChunk> chunks = chunks(33);

        int upserted = this.vectorizationService.vectorize(document(), chunks);

        assertThat(upserted).isEqualTo(33);
        ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
        verify(this.embeddingService, times(2)).embed(captor.capture());
        assertThat(captor.getAllValues()).extracting(List::size).containsExactly(32, 1);
        verify(this.chunkVectorService, times(2)).upsert(eq(COLLECTION), anyList());
    }

    @Test
    void embeddingFailureDoesNotTriggerCompensationWhenNothingUpserted() {
        when(this.embeddingService.embed(anyList()))
                .thenThrow(new DocumentVectorizationException("Embedding 调用失败"));

        assertThatThrownBy(() -> this.vectorizationService.vectorize(document(), chunks(1)))
                .isInstanceOf(DocumentVectorizationException.class);

        verify(this.chunkVectorService, never()).upsert(anyString(), anyList());
        verify(this.chunkVectorService, never()).deleteByDocumentId(anyString(), anyString());
    }

    @Test
    void upsertFailureTriggersCompensationByDocumentId() {
        stubEmbeddingEcho();
        Document document = document();
        doThrow(new RuntimeException("qdrant down"))
                .when(this.chunkVectorService).upsert(anyString(), anyList());

        assertThatThrownBy(() -> this.vectorizationService.vectorize(document, chunks(1)))
                .isInstanceOf(DocumentVectorizationException.class)
                .hasMessageContaining(document.getDocumentId());

        verify(this.chunkVectorService).deleteByDocumentId(COLLECTION, document.getDocumentId());
    }

    @Test
    void compensationFailureIsReportedWithDocumentId() {
        stubEmbeddingEcho();
        Document document = document();
        doThrow(new RuntimeException("qdrant down"))
                .when(this.chunkVectorService).upsert(anyString(), anyList());
        doThrow(new RuntimeException("cleanup down"))
                .when(this.chunkVectorService).deleteByDocumentId(anyString(), anyString());

        assertThatThrownBy(() -> this.vectorizationService.vectorize(document, chunks(1)))
                .isInstanceOf(DocumentVectorizationException.class)
                .hasMessageContaining("补偿删除失败")
                .hasMessageContaining(document.getDocumentId());
    }

    @Test
    void failureAfterFirstBatchTriggersCompensation() {
        Document document = document();
        List<float[]> firstBatchVectors = new ArrayList<>();
        for (int index = 0; index < 32; index++) {
            firstBatchVectors.add(new float[1024]);
        }
        when(this.embeddingService.embed(anyList()))
                .thenReturn(firstBatchVectors)
                .thenThrow(new DocumentVectorizationException("second batch failed"));

        assertThatThrownBy(() -> this.vectorizationService.vectorize(document, chunks(33)))
                .isInstanceOf(DocumentVectorizationException.class)
                .hasMessageContaining(document.getDocumentId());

        verify(this.chunkVectorService).upsert(eq(COLLECTION), anyList());
        verify(this.chunkVectorService).deleteByDocumentId(COLLECTION, document.getDocumentId());
    }

    @Test
    void deleteByDocumentIdDelegatesToQdrantService() {
        String documentId = UUID.randomUUID().toString();

        this.vectorizationService.deleteByDocumentId(documentId);

        verify(this.chunkVectorService).deleteByDocumentId(COLLECTION, documentId);
    }

    private void stubEmbeddingEcho() {
        when(this.embeddingService.embed(anyList())).thenAnswer(invocation -> {
            List<String> texts = invocation.getArgument(0);
            List<float[]> vectors = new ArrayList<>(texts.size());
            for (int index = 0; index < texts.size(); index++) {
                vectors.add(new float[1024]);
            }
            return vectors;
        });
    }

    private static Document document() {
        Document document = new Document();
        document.setDocumentPk(123L);
        document.setDocumentId(UUID.randomUUID().toString());
        document.setUserId(7L);
        document.setTenantId(100L);
        return document;
    }

    private static List<DocumentChunk> chunks(int count) {
        List<DocumentChunk> chunks = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            DocumentChunk chunk = new DocumentChunk();
            chunk.setChunkId(1000L + index);
            chunk.setChunkIndex(index);
            chunk.setContent("chunk-" + index);
            chunks.add(chunk);
        }
        return chunks;
    }
}
