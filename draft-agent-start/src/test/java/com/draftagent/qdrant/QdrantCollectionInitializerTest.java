package com.draftagent.qdrant;

import com.draftagent.ai.rag.qdrant.QdrantCollectionAdmin;
import com.draftagent.ai.rag.qdrant.QdrantCollectionConfigMismatchException;
import com.draftagent.ai.rag.qdrant.QdrantCollectionInitializer;
import com.draftagent.ai.rag.qdrant.QdrantCollectionState;
import com.draftagent.ai.rag.qdrant.RagQdrantProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Collection 初始化逻辑（Mock QdrantCollectionAdmin）。
 */
class QdrantCollectionInitializerTest {

    private static final String COLLECTION = "draftagent_chunks_v1";

    private QdrantCollectionAdmin collectionAdmin;

    private QdrantCollectionInitializer initializer;

    @BeforeEach
    void setUp() {
        this.collectionAdmin = mock(QdrantCollectionAdmin.class);
        RagQdrantProperties properties = new RagQdrantProperties();
        properties.setCollection(COLLECTION);
        properties.setDimensions(1024);
        properties.setDistance("Cosine");
        this.initializer = new QdrantCollectionInitializer(this.collectionAdmin, properties);
    }

    @Test
    void createsCollectionAndIndexesWhenMissing() {
        when(this.collectionAdmin.collectionExists(COLLECTION)).thenReturn(false);

        this.initializer.initialize();

        verify(this.collectionAdmin).createCollection(COLLECTION, 1024, "Cosine");
        verify(this.collectionAdmin).createIntegerPayloadIndex(COLLECTION, "tenant_id");
        verify(this.collectionAdmin).createIntegerPayloadIndex(COLLECTION, "user_id");
        verify(this.collectionAdmin).createUuidPayloadIndex(COLLECTION, "document_id");
        verify(this.collectionAdmin, never()).getCollectionState(anyString());
    }

    @Test
    void skipsCreateWhenConfigurationMatches() {
        when(this.collectionAdmin.collectionExists(COLLECTION)).thenReturn(true);
        when(this.collectionAdmin.getCollectionState(COLLECTION))
                .thenReturn(new QdrantCollectionState(1024, "Cosine"));

        this.initializer.initialize();

        verify(this.collectionAdmin, never()).createCollection(anyString(), anyInt(), anyString());
        verify(this.collectionAdmin).createIntegerPayloadIndex(COLLECTION, "tenant_id");
        verify(this.collectionAdmin).createIntegerPayloadIndex(COLLECTION, "user_id");
        verify(this.collectionAdmin).createUuidPayloadIndex(COLLECTION, "document_id");
    }

    @Test
    void failsWhenVectorSizeMismatchAndDoesNotRecreate() {
        when(this.collectionAdmin.collectionExists(COLLECTION)).thenReturn(true);
        when(this.collectionAdmin.getCollectionState(COLLECTION))
                .thenReturn(new QdrantCollectionState(1536, "Cosine"));

        assertThatThrownBy(() -> this.initializer.initialize())
                .isInstanceOf(QdrantCollectionConfigMismatchException.class)
                .hasMessageContaining("1024")
                .hasMessageContaining("1536");

        verify(this.collectionAdmin, never()).createCollection(anyString(), anyInt(), anyString());
        verify(this.collectionAdmin, never()).createIntegerPayloadIndex(anyString(), anyString());
        verify(this.collectionAdmin, never()).createUuidPayloadIndex(anyString(), anyString());
    }

    @Test
    void failsWhenDistanceMismatchAndDoesNotRecreate() {
        when(this.collectionAdmin.collectionExists(COLLECTION)).thenReturn(true);
        when(this.collectionAdmin.getCollectionState(COLLECTION))
                .thenReturn(new QdrantCollectionState(1024, "Euclid"));

        assertThatThrownBy(() -> this.initializer.initialize())
                .isInstanceOf(QdrantCollectionConfigMismatchException.class)
                .hasMessageContaining("Cosine")
                .hasMessageContaining("Euclid");

        verify(this.collectionAdmin, never()).createCollection(anyString(), anyInt(), anyString());
    }

    @Test
    void repeatedInitializeKeepsIndexCreationIdempotent() {
        when(this.collectionAdmin.collectionExists(COLLECTION)).thenReturn(true);
        when(this.collectionAdmin.getCollectionState(COLLECTION))
                .thenReturn(new QdrantCollectionState(1024, "Cosine"));

        this.initializer.initialize();
        this.initializer.initialize();

        verify(this.collectionAdmin, never()).createCollection(anyString(), anyInt(), anyString());
        verify(this.collectionAdmin, times(2)).createIntegerPayloadIndex(COLLECTION, "tenant_id");
        verify(this.collectionAdmin, times(2)).createIntegerPayloadIndex(COLLECTION, "user_id");
        verify(this.collectionAdmin, times(2)).createUuidPayloadIndex(COLLECTION, "document_id");
    }
}
