package com.draftagent.retrieval;

import com.draftagent.business.document.DocumentChunkContent;
import com.draftagent.business.document.DocumentSearchException;
import com.draftagent.business.document.DocumentSearchResult;
import com.draftagent.business.document.DocumentSearchScope;
import com.draftagent.business.document.RetrievedDocumentChunk;
import com.draftagent.business.entity.Document;
import com.draftagent.business.mapper.DocumentChunkMapper;
import com.draftagent.business.service.DocumentChunkContentService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * MySQL 原文回查（Mock Mapper）：权限参数、元数据交叉校验、排序与异常。
 */
class DocumentChunkContentServiceTest {

    private static final Long USER_ID = 7L;

    private static final Long TENANT_ID = 100L;

    private final DocumentChunkMapper mapper = mock(DocumentChunkMapper.class);

    @Test
    void returnsContentInQdrantOrderWithScore() {
        String docA = UUID.randomUUID().toString();
        String docB = UUID.randomUUID().toString();
        String docC = UUID.randomUUID().toString();
        List<DocumentSearchResult> hits = List.of(
                result(1L, docA, 11L, 0, 0.92f),
                result(2L, docB, 12L, 1, 0.88f),
                result(3L, docC, 13L, 2, 0.85f));
        // MySQL IN 查询不保证顺序：故意返回 C、A、B
        when(this.mapper.selectReadyChunkContents(anyList(), any(), any(), anyString())).thenReturn(List.of(
                row(docC, 13L, 3L, 2, "content C"),
                row(docA, 11L, 1L, 0, "content A"),
                row(docB, 12L, 2L, 1, "content B")));

        List<RetrievedDocumentChunk> retrieved =
                service().retrieve(hits, new DocumentSearchScope(USER_ID, TENANT_ID));

        assertThat(retrieved).extracting(RetrievedDocumentChunk::getChunkId)
                .containsExactly(1L, 2L, 3L);
        RetrievedDocumentChunk first = retrieved.get(0);
        assertThat(first.getDocumentId()).isEqualTo(docA);
        assertThat(first.getDocumentPk()).isEqualTo(11L);
        assertThat(first.getChunkIndex()).isZero();
        assertThat(first.getContent()).isEqualTo("content A");
        assertThat(first.getScore()).isEqualTo(0.92f);
    }

    @Test
    void returnsOnlySubsetPresentInMysql() {
        String docA = UUID.randomUUID().toString();
        String docB = UUID.randomUUID().toString();
        String docC = UUID.randomUUID().toString();
        List<DocumentSearchResult> hits = List.of(
                result(1L, docA, 11L, 0, 0.9f),
                result(2L, docB, 12L, 0, 0.8f),
                result(3L, docC, 13L, 0, 0.7f));
        when(this.mapper.selectReadyChunkContents(anyList(), any(), any(), anyString())).thenReturn(List.of(
                row(docC, 13L, 3L, 0, "content C"),
                row(docA, 11L, 1L, 0, "content A")));

        List<RetrievedDocumentChunk> retrieved =
                service().retrieve(hits, new DocumentSearchScope(USER_ID, TENANT_ID));

        assertThat(retrieved).extracting(RetrievedDocumentChunk::getChunkId).containsExactly(1L, 3L);
    }

    @Test
    void discardsMetadataMismatchForEveryField() {
        String documentId = UUID.randomUUID().toString();
        List<DocumentSearchResult> hits = List.of(
                result(1L, documentId, 11L, 0, 0.9f),
                result(2L, documentId, 12L, 0, 0.9f),
                result(3L, documentId, 13L, 0, 0.9f),
                result(4L, documentId, 14L, 0, 0.9f));
        when(this.mapper.selectReadyChunkContents(anyList(), any(), any(), anyString())).thenReturn(List.of(
                // chunkId 不一致
                row(documentId, 11L, 999L, 0, "wrong chunk id"),
                // documentId 不一致
                row(UUID.randomUUID().toString(), 12L, 2L, 0, "wrong document id"),
                // documentPk 不一致
                row(documentId, 999L, 3L, 0, "wrong document pk"),
                // chunkIndex 不一致
                row(documentId, 14L, 4L, 9, "wrong chunk index")));

        List<RetrievedDocumentChunk> retrieved =
                service().retrieve(hits, new DocumentSearchScope(USER_ID, TENANT_ID));

        assertThat(retrieved).isEmpty();
    }

    @Test
    void discardsInconsistentChunkDocumentId() {
        String documentId = UUID.randomUUID().toString();
        DocumentChunkContent row = row(documentId, 11L, 1L, 0, "content");
        row.setChunkDocumentId(UUID.randomUUID().toString());
        when(this.mapper.selectReadyChunkContents(anyList(), any(), any(), anyString())).thenReturn(List.of(row));

        List<RetrievedDocumentChunk> retrieved = service().retrieve(
                List.of(result(1L, documentId, 11L, 0, 0.9f)),
                new DocumentSearchScope(USER_ID, TENANT_ID));

        assertThat(retrieved).isEmpty();
    }

    @Test
    void emptyResultsDoesNotQueryDatabase() {
        assertThat(service().retrieve(List.of(), new DocumentSearchScope(USER_ID, TENANT_ID))).isEmpty();
        assertThat(service().retrieve(null, new DocumentSearchScope(USER_ID, TENANT_ID))).isEmpty();
        verifyNoInteractions(this.mapper);
    }

    @Test
    void nullScopeIsRejected() {
        assertThatThrownBy(() -> service().retrieve(List.of(), null))
                .isInstanceOf(DocumentSearchException.class)
                .hasMessageContaining("检索范围");
    }

    @Test
    void mysqlFailureIsNotDisguisedAsEmpty() {
        when(this.mapper.selectReadyChunkContents(anyList(), any(), any(), anyString()))
                .thenThrow(new DataAccessResourceFailureException("db down"));

        assertThatThrownBy(() -> service().retrieve(
                List.of(result(1L, UUID.randomUUID().toString(), 11L, 0, 0.9f)),
                new DocumentSearchScope(USER_ID, TENANT_ID)))
                .isInstanceOf(DocumentSearchException.class)
                .hasMessageContaining("原文回查失败");
    }

    @Test
    void passesServerScopeAndReadyStatusToBatchQuery() {
        String documentId = UUID.randomUUID().toString();
        when(this.mapper.selectReadyChunkContents(anyList(), any(), any(), anyString())).thenReturn(List.of());

        service().retrieve(List.of(result(1L, documentId, 11L, 0, 0.9f)),
                new DocumentSearchScope(USER_ID, TENANT_ID));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Long>> chunkIds = ArgumentCaptor.forClass(List.class);
        verify(this.mapper).selectReadyChunkContents(
                chunkIds.capture(), eq(USER_ID), eq(TENANT_ID), eq(Document.STATUS_READY));
        assertThat(chunkIds.getValue()).containsExactly(1L);
    }

    @Test
    void personalScopePassesNullTenantId() {
        when(this.mapper.selectReadyChunkContents(anyList(), any(), any(), anyString())).thenReturn(List.of());

        service().retrieve(List.of(result(1L, UUID.randomUUID().toString(), 11L, 0, 0.9f)),
                new DocumentSearchScope(USER_ID, null));

        verify(this.mapper).selectReadyChunkContents(anyList(), eq(USER_ID), isNull(), eq(Document.STATUS_READY));
    }

    private DocumentChunkContentService service() {
        return new DocumentChunkContentService(this.mapper);
    }

    private static DocumentSearchResult result(long chunkId, String documentId, long documentPk,
                                               int chunkIndex, float score) {
        return new DocumentSearchResult(chunkId, documentId, documentPk, chunkId, chunkIndex, score);
    }

    private static DocumentChunkContent row(String documentId, long documentPk,
                                            long chunkId, int chunkIndex, String content) {
        DocumentChunkContent row = new DocumentChunkContent();
        row.setDocumentId(documentId);
        row.setChunkDocumentId(documentId);
        row.setDocumentPk(documentPk);
        row.setChunkId(chunkId);
        row.setChunkIndex(chunkIndex);
        row.setContent(content);
        return row;
    }
}
