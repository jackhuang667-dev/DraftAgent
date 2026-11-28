package com.draftagent.document;

import com.draftagent.business.document.DocumentContentParser;
import com.draftagent.business.document.DocumentFileType;
import com.draftagent.business.document.DocumentParseException;
import com.draftagent.business.document.storage.DocumentStorage;
import com.draftagent.business.document.storage.StoredDocumentFile;
import com.draftagent.business.dto.DocumentUploadResponse;
import com.draftagent.business.entity.Document;
import com.draftagent.business.service.DocumentService;
import com.draftagent.business.service.DocumentTransactionService;
import com.draftagent.common.core.exception.ServiceException;
import com.draftagent.common.security.CurrentUser;
import com.draftagent.common.security.CurrentUserProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link DocumentService} 单元测试（Mockito，不连接数据库、不解析真实文件）。
 */
class DocumentServiceUnitTest {

    private static final long DOCUMENT_PK = 100L;

    private DocumentStorage storage;

    private DocumentContentParser parser;

    private DocumentTransactionService transactionService;

    private CurrentUserProvider currentUserProvider;

    private DocumentService documentService;

    @BeforeEach
    void setUp() {
        this.storage = mock(DocumentStorage.class);
        this.parser = mock(DocumentContentParser.class);
        this.transactionService = mock(DocumentTransactionService.class);
        this.currentUserProvider = mock(CurrentUserProvider.class);
        this.documentService = new DocumentService(this.storage, this.parser, this.transactionService,
                this.currentUserProvider, 20L * 1024L * 1024L);
    }

    @Test
    void missingUserIdIsRejected() {
        when(this.currentUserProvider.getCurrentUser()).thenReturn(new CurrentUser(null, null));

        ServiceException ex = catchThrowableOfType(
                () -> this.documentService.upload(txtFile("hello.txt", "hello world")), ServiceException.class);

        assertThat(ex.getCode()).isEqualTo(401);
        verifyNoInteractions(this.storage, this.parser, this.transactionService);
    }

    @Test
    void emptyFileIsRejected() {
        when(this.currentUserProvider.getCurrentUser()).thenReturn(new CurrentUser(1L, null));
        MockMultipartFile empty = new MockMultipartFile("file", "empty.txt", "text/plain", new byte[0]);

        ServiceException ex = catchThrowableOfType(() -> this.documentService.upload(empty), ServiceException.class);

        assertThat(ex.getCode()).isEqualTo(400);
        verifyNoInteractions(this.storage);
    }

    @Test
    void validUploadReturnsReadyAndPersistsChunks() {
        StoredDocumentFile stored = storedFile();
        when(this.currentUserProvider.getCurrentUser()).thenReturn(new CurrentUser(1L, null));
        when(this.storage.store(any(), eq("txt"))).thenReturn(stored);
        mockInsertPending();
        when(this.transactionService.markProcessing(DOCUMENT_PK)).thenReturn(true);
        when(this.parser.extractChunks(any(), eq(DocumentFileType.TXT)))
                .thenReturn(List.of("chunk one", "chunk two"));

        DocumentUploadResponse response = this.documentService.upload(txtFile("a.txt", "body"));

        assertThat(response.getStatus()).isEqualTo(Document.STATUS_READY);
        assertThat(response.getChunkCount()).isEqualTo(2);
        verify(this.transactionService).markReadyWithChunks(eq(DOCUMENT_PK), anyList());
        verify(this.storage, never()).delete(anyString());
    }

    @Test
    void filenameWithPathIsSanitized() {
        StoredDocumentFile stored = storedFile();
        when(this.currentUserProvider.getCurrentUser()).thenReturn(new CurrentUser(1L, null));
        when(this.storage.store(any(), eq("txt"))).thenReturn(stored);
        mockInsertPending();
        when(this.transactionService.markProcessing(DOCUMENT_PK)).thenReturn(true);
        when(this.parser.extractChunks(any(), eq(DocumentFileType.TXT))).thenReturn(List.of("chunk"));

        MockMultipartFile file = new MockMultipartFile("file", "../../etc/upload.txt", "text/plain",
                "content".getBytes(StandardCharsets.UTF_8));
        this.documentService.upload(file);

        ArgumentCaptor<Document> captor = ArgumentCaptor.forClass(Document.class);
        verify(this.transactionService).insertPending(captor.capture());
        assertThat(captor.getValue().getFilename()).isEqualTo("upload.txt");
    }

    @Test
    void parseFailureMarksFailedAndDeletesFile() {
        StoredDocumentFile stored = storedFile();
        when(this.currentUserProvider.getCurrentUser()).thenReturn(new CurrentUser(1L, null));
        when(this.storage.store(any(), eq("txt"))).thenReturn(stored);
        mockInsertPending();
        when(this.transactionService.markProcessing(DOCUMENT_PK)).thenReturn(true);
        when(this.parser.extractChunks(any(), eq(DocumentFileType.TXT)))
                .thenThrow(new DocumentParseException("未能提取有效文本"));

        ServiceException ex = catchThrowableOfType(
                () -> this.documentService.upload(txtFile("a.txt", "body")), ServiceException.class);

        assertThat(ex.getCode()).isEqualTo(422);
        verify(this.transactionService).markFailed(eq(DOCUMENT_PK), contains("未能提取有效文本"));
        verify(this.storage).delete(stored.getRelativePath());
    }

    @Test
    void chunkSaveFailureMarksFailedAndDoesNotStayReady() {
        StoredDocumentFile stored = storedFile();
        when(this.currentUserProvider.getCurrentUser()).thenReturn(new CurrentUser(1L, null));
        when(this.storage.store(any(), eq("txt"))).thenReturn(stored);
        mockInsertPending();
        when(this.transactionService.markProcessing(DOCUMENT_PK)).thenReturn(true);
        when(this.parser.extractChunks(any(), eq(DocumentFileType.TXT))).thenReturn(List.of("chunk"));
        doThrow(new RuntimeException("db down"))
                .when(this.transactionService).markReadyWithChunks(eq(DOCUMENT_PK), anyList());

        ServiceException ex = catchThrowableOfType(
                () -> this.documentService.upload(txtFile("a.txt", "body")), ServiceException.class);

        assertThat(ex.getCode()).isEqualTo(503);
        verify(this.transactionService).markFailed(eq(DOCUMENT_PK), anyString());
        verify(this.storage).delete(stored.getRelativePath());
    }

    private void mockInsertPending() {
        doAnswer(invocation -> {
            Document document = invocation.getArgument(0);
            document.setDocumentPk(DOCUMENT_PK);
            return null;
        }).when(this.transactionService).insertPending(any(Document.class));
    }

    private static StoredDocumentFile storedFile() {
        return new StoredDocumentFile("2026/01/01/file.txt", Paths.get("target/test-docs/file.txt"), 10L, "checksum");
    }

    private static MockMultipartFile txtFile(String filename, String content) {
        return new MockMultipartFile("file", filename, "text/plain", content.getBytes(StandardCharsets.UTF_8));
    }
}
