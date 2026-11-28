package com.draftagent.retrieval;

import com.draftagent.DraftAgentApplication;
import com.draftagent.business.document.DocumentChunkContentRetriever;
import com.draftagent.business.document.DocumentSearchResult;
import com.draftagent.business.document.DocumentSearchScope;
import com.draftagent.business.document.RetrievedDocumentChunk;
import com.draftagent.business.entity.Document;
import com.draftagent.business.entity.DocumentChunk;
import com.draftagent.business.mapper.DocumentChunkMapper;
import com.draftagent.business.mapper.DocumentMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实 MySQL 原文回查集成测试：租户/个人隔离、READY 状态、元数据交叉校验与排序。
 *
 * <p>使用随机 document_id 与自增 chunk_id 隔离数据；仅清理本测试创建的 document（外键级联删除 chunk），
 * 不调用 Qdrant、不调用 Embedding、不修改表结构。</p>
 */
@SpringBootTest(classes = DraftAgentApplication.class)
class DocumentChunkContentServiceMySqlIntegrationTest {

    private static final Long USER_A = 9301L;

    private static final Long USER_B = 9302L;

    private static final Long USER_C = 9303L;

    private static final Long TENANT_A = 9310L;

    private static final Long TENANT_B = 9320L;

    @Autowired
    private DocumentChunkContentRetriever retriever;

    @Autowired
    private DocumentMapper documentMapper;

    @Autowired
    private DocumentChunkMapper documentChunkMapper;

    private final List<Long> documentPks = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (Long documentPk : this.documentPks) {
            try {
                this.documentChunkMapper.delete(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<DocumentChunk>()
                        .eq(DocumentChunk::getDocumentPk, documentPk));
            }
            catch (Exception ignored) {
                // ignore
            }
            try {
                this.documentMapper.deleteById(documentPk);
            }
            catch (Exception ignored) {
                // ignore
            }
        }
    }

    @Test
    void retrievesOnlyAuthorizedReadyChunksInQdrantOrder() {
        Document tenantAUserA = insertDocument(USER_A, TENANT_A, Document.STATUS_READY, "tenant-a-user-a.txt");
        Document tenantAUserB = insertDocument(USER_B, TENANT_A, Document.STATUS_READY, "tenant-a-user-b.txt");
        Document tenantBUserC = insertDocument(USER_C, TENANT_B, Document.STATUS_READY, "tenant-b-user-c.txt");
        Document personalUserA = insertDocument(USER_A, null, Document.STATUS_READY, "personal-user-a.txt");
        Document personalUserB = insertDocument(USER_B, null, Document.STATUS_READY, "personal-user-b.txt");
        Document processing = insertDocument(USER_A, TENANT_A, Document.STATUS_PROCESSING, "processing.txt");

        DocumentChunk chunkTenantAUserA = insertChunk(tenantAUserA, 0, "content tenant A user A");
        DocumentChunk chunkTenantAUserB = insertChunk(tenantAUserB, 0, "content tenant A user B");
        DocumentChunk chunkTenantBUserC = insertChunk(tenantBUserC, 0, "content tenant B user C");
        DocumentChunk chunkPersonalUserA = insertChunk(personalUserA, 0, "content personal user A");
        DocumentChunk chunkPersonalUserB = insertChunk(personalUserB, 0, "content personal user B");
        DocumentChunk chunkProcessing = insertChunk(processing, 0, "content processing");

        long unknownChunkId = 999_999_999L;
        List<DocumentSearchResult> hits = List.of(
                result(chunkTenantAUserB, 0.95f),
                result(chunkTenantAUserA, 0.90f),
                result(chunkTenantBUserC, 0.85f),
                result(chunkPersonalUserA, 0.80f),
                result(chunkPersonalUserB, 0.75f),
                result(chunkProcessing, 0.70f),
                new DocumentSearchResult(unknownChunkId, UUID.randomUUID().toString(), 999L, unknownChunkId, 0, 0.60f));

        List<RetrievedDocumentChunk> tenantResults =
                this.retriever.retrieve(hits, new DocumentSearchScope(USER_A, TENANT_A));
        assertThat(tenantResults).extracting(RetrievedDocumentChunk::getChunkId)
                .containsExactly(chunkTenantAUserB.getChunkId(), chunkTenantAUserA.getChunkId());
        RetrievedDocumentChunk first = tenantResults.get(0);
        assertThat(first.getDocumentId()).isEqualTo(tenantAUserB.getDocumentId());
        assertThat(first.getDocumentPk()).isEqualTo(tenantAUserB.getDocumentPk());
        assertThat(first.getChunkIndex()).isZero();
        assertThat(first.getContent()).isEqualTo("content tenant A user B");
        assertThat(first.getScore()).isEqualTo(0.95f);

        List<RetrievedDocumentChunk> personalA =
                this.retriever.retrieve(hits, new DocumentSearchScope(USER_A, null));
        assertThat(personalA).extracting(RetrievedDocumentChunk::getChunkId)
                .containsExactly(chunkPersonalUserA.getChunkId());

        List<RetrievedDocumentChunk> personalB =
                this.retriever.retrieve(hits, new DocumentSearchScope(USER_B, null));
        assertThat(personalB).extracting(RetrievedDocumentChunk::getChunkId)
                .containsExactly(chunkPersonalUserB.getChunkId());

        List<RetrievedDocumentChunk> otherTenant =
                this.retriever.retrieve(hits, new DocumentSearchScope(USER_C, TENANT_B));
        assertThat(otherTenant).extracting(RetrievedDocumentChunk::getChunkId)
                .containsExactly(chunkTenantBUserC.getChunkId());

        assertThat(this.retriever.retrieve(List.of(), new DocumentSearchScope(USER_A, TENANT_A))).isEmpty();
    }

    @Test
    void discardsQdrantMetadataMismatch() {
        Document document = insertDocument(USER_A, TENANT_A, Document.STATUS_READY, "mismatch.txt");
        DocumentChunk chunk = insertChunk(document, 0, "content mismatch");

        DocumentSearchResult wrongDocumentId = new DocumentSearchResult(
                chunk.getChunkId(), UUID.randomUUID().toString(), document.getDocumentPk(),
                chunk.getChunkId(), 0, 0.9f);
        DocumentSearchResult wrongDocumentPk = new DocumentSearchResult(
                chunk.getChunkId(), document.getDocumentId(), 999_999L,
                chunk.getChunkId(), 0, 0.9f);
        DocumentSearchResult wrongChunkIndex = new DocumentSearchResult(
                chunk.getChunkId(), document.getDocumentId(), document.getDocumentPk(),
                chunk.getChunkId(), 99, 0.9f);

        DocumentSearchScope scope = new DocumentSearchScope(USER_A, TENANT_A);
        assertThat(this.retriever.retrieve(List.of(wrongDocumentId), scope)).isEmpty();
        assertThat(this.retriever.retrieve(List.of(wrongDocumentPk), scope)).isEmpty();
        assertThat(this.retriever.retrieve(List.of(wrongChunkIndex), scope)).isEmpty();
    }

    private Document insertDocument(Long userId, Long tenantId, String status, String filename) {
        Document document = new Document();
        document.setDocumentId(UUID.randomUUID().toString());
        document.setUserId(userId);
        document.setTenantId(tenantId);
        document.setFilename(filename);
        document.setStoragePath("test/" + UUID.randomUUID());
        document.setMimeType("text/plain");
        document.setFileSize(1L);
        document.setStatus(status);
        document.setChunkCount(1);
        document.setCreatedAt(LocalDateTime.now());
        document.setUpdatedAt(LocalDateTime.now());
        this.documentMapper.insert(document);
        this.documentPks.add(document.getDocumentPk());
        return document;
    }

    private DocumentChunk insertChunk(Document document, int chunkIndex, String content) {
        DocumentChunk chunk = new DocumentChunk();
        chunk.setDocumentPk(document.getDocumentPk());
        chunk.setDocumentId(document.getDocumentId());
        chunk.setChunkIndex(chunkIndex);
        chunk.setContent(content);
        chunk.setCharCount(content.length());
        chunk.setCreatedAt(LocalDateTime.now());
        this.documentChunkMapper.insert(chunk);
        return chunk;
    }

    private static DocumentSearchResult result(DocumentChunk chunk, float score) {
        return new DocumentSearchResult(
                chunk.getChunkId(), chunk.getDocumentId(), chunk.getDocumentPk(),
                chunk.getChunkId(), chunk.getChunkIndex(), score);
    }
}
