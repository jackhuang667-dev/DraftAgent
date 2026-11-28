package com.draftagent.document;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.draftagent.DraftAgentApplication;
import com.draftagent.business.document.DocumentVectorizer;
import com.draftagent.business.entity.Document;
import com.draftagent.business.entity.DocumentChunk;
import com.draftagent.business.mapper.DocumentChunkMapper;
import com.draftagent.business.mapper.DocumentMapper;
import com.draftagent.security.jwt.JwtService;
import com.jayway.jsonpath.JsonPath;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 文档上传 HTTP 集成测试。
 *
 * <p>使用真实 MySQL（document/document_chunk）、真实解析（TXT/MD/PDF）与本地临时存储；
 * 不调用 DeepSeek 或任何模型 API。测试数据在结束后清理。</p>
 */
@SpringBootTest(classes = DraftAgentApplication.class,
        properties = {
                "rag.storage.root=target/test-docs",
                "rag.document.max-file-size-bytes=100000"
        })
@AutoConfigureMockMvc
class DocumentUploadMockMvcTest {

    private static final Path STORAGE_ROOT = Paths.get("target/test-docs");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private DocumentMapper documentMapper;

    @Autowired
    private DocumentChunkMapper documentChunkMapper;

    @MockBean
    private DocumentVectorizer documentVectorizer;

    private final List<Long> createdDocumentPks = new ArrayList<>();

    @BeforeEach
    void stubVectorizer() {
        when(this.documentVectorizer.vectorize(any(Document.class), anyList()))
                .thenAnswer(invocation -> ((List<?>) invocation.getArgument(1)).size());
    }

    @AfterEach
    void cleanupDatabase() {
        for (Long pk : this.createdDocumentPks) {
            this.documentChunkMapper.delete(new LambdaQueryWrapper<DocumentChunk>()
                    .eq(DocumentChunk::getDocumentPk, pk));
            this.documentMapper.deleteById(pk);
        }
        this.createdDocumentPks.clear();
    }

    @AfterAll
    static void cleanupStorage() throws IOException {
        if (Files.exists(STORAGE_ROOT)) {
            try (var paths = Files.walk(STORAGE_ROOT)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    }
                    catch (IOException ignored) {
                        // 清理失败不影响测试结论
                    }
                });
            }
        }
    }

    @Test
    void uploadWithoutTokenReturns401() throws Exception {
        mockMvc.perform(multipart("/documents")
                        .file(new MockMultipartFile("file", "a.txt", "text/plain", bytes("hello world"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void uploadTxtSucceedsAndStoresPersonalDocument() throws Exception {
        long userId = 19001L;
        String filename = "txt-" + UUID.randomUUID() + ".txt";

        MvcResult result = mockMvc.perform(multipart("/documents")
                        .file(new MockMultipartFile("file", filename, "text/plain", bytes(longText())))
                        .header("Authorization", bearer(userId, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").value(Document.STATUS_READY))
                .andExpect(jsonPath("$.data.chunkCount", greaterThanOrEqualTo(1)))
                .andReturn();

        Document document = findByDocumentId(documentIdOf(result));
        assertThat(document).isNotNull();
        this.createdDocumentPks.add(document.getDocumentPk());
        assertThat(document.getUserId()).isEqualTo(userId);
        assertThat(document.getTenantId()).isNull();
        assertThat(document.getStatus()).isEqualTo(Document.STATUS_READY);
        assertThat(document.getStoragePath()).isNotBlank();
        assertThat(chunkCount(document.getDocumentPk())).isEqualTo(document.getChunkCount().longValue());
    }

    @Test
    void uploadMarkdownSucceedsAndStoresTenantId() throws Exception {
        long userId = 19002L;
        long tenantId = 9L;
        String filename = "md-" + UUID.randomUUID() + ".md";
        String content = "# 企业共享文档\n\n" + longText();

        MvcResult result = mockMvc.perform(multipart("/documents")
                        .file(new MockMultipartFile("file", filename, "text/markdown", bytes(content)))
                        .header("Authorization", bearer(userId, tenantId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value(Document.STATUS_READY))
                .andReturn();

        Document document = findByDocumentId(documentIdOf(result));
        assertThat(document).isNotNull();
        this.createdDocumentPks.add(document.getDocumentPk());
        assertThat(document.getUserId()).isEqualTo(userId);
        assertThat(document.getTenantId()).isEqualTo(tenantId);
        assertThat(chunkCount(document.getDocumentPk())).isGreaterThanOrEqualTo(1);
    }

    @Test
    void uploadPdfSucceeds() throws Exception {
        long userId = 19003L;
        String filename = "pdf-" + UUID.randomUUID() + ".pdf";
        byte[] pdf = createPdf("DraftAgent PDF upload integration test content.");

        MvcResult result = mockMvc.perform(multipart("/documents")
                        .file(new MockMultipartFile("file", filename, "application/pdf", pdf))
                        .header("Authorization", bearer(userId, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value(Document.STATUS_READY))
                .andExpect(jsonPath("$.data.chunkCount", greaterThanOrEqualTo(1)))
                .andReturn();

        Document document = findByDocumentId(documentIdOf(result));
        assertThat(document).isNotNull();
        this.createdDocumentPks.add(document.getDocumentPk());
        assertThat(document.getStatus()).isEqualTo(Document.STATUS_READY);
    }

    @Test
    void emptyFileIsRejected() throws Exception {
        mockMvc.perform(multipart("/documents")
                        .file(new MockMultipartFile("file", "empty.txt", "text/plain", new byte[0]))
                        .header("Authorization", bearer(19004L, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void invalidExtensionIsRejected() throws Exception {
        mockMvc.perform(multipart("/documents")
                        .file(new MockMultipartFile("file", "a.exe", "application/octet-stream", bytes("binary")))
                        .header("Authorization", bearer(19005L, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void mimeMismatchIsRejected() throws Exception {
        mockMvc.perform(multipart("/documents")
                        .file(new MockMultipartFile("file", "a.pdf", "text/plain", bytes("not a pdf")))
                        .header("Authorization", bearer(19006L, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void oversizeFileIsRejected() throws Exception {
        byte[] oversize = new byte[100_001];
        mockMvc.perform(multipart("/documents")
                        .file(new MockMultipartFile("file", "big.txt", "text/plain", oversize))
                        .header("Authorization", bearer(19007L, null)))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value(413));
    }

    @Test
    void missingFilePartIsRejected() throws Exception {
        mockMvc.perform(multipart("/documents")
                        .header("Authorization", bearer(19008L, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void pdfWithoutTextIsMarkedFailed() throws Exception {
        long userId = 19009L;
        String filename = "invalid-" + UUID.randomUUID() + ".pdf";

        mockMvc.perform(multipart("/documents")
                        .file(new MockMultipartFile("file", filename, "application/pdf", bytes("this is not a pdf")))
                        .header("Authorization", bearer(userId, null)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(422));

        Document document = this.documentMapper.selectOne(new LambdaQueryWrapper<Document>()
                .eq(Document::getFilename, filename));
        assertThat(document).isNotNull();
        this.createdDocumentPks.add(document.getDocumentPk());
        assertThat(document.getStatus()).isEqualTo(Document.STATUS_FAILED);
        assertThat(document.getChunkCount()).isZero();
        assertThat(chunkCount(document.getDocumentPk())).isZero();
    }

    private String bearer(Long userId, Long tenantId) {
        return "Bearer " + this.jwtService.generateToken(userId, tenantId);
    }

    private Document findByDocumentId(String documentId) {
        return this.documentMapper.selectOne(new LambdaQueryWrapper<Document>()
                .eq(Document::getDocumentId, documentId));
    }

    private long chunkCount(Long documentPk) {
        return this.documentChunkMapper.selectCount(new LambdaQueryWrapper<DocumentChunk>()
                .eq(DocumentChunk::getDocumentPk, documentPk));
    }

    private static String documentIdOf(MvcResult result) throws IOException {
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        return JsonPath.read(body, "$.data.documentId");
    }

    private static byte[] bytes(String content) {
        return content.getBytes(StandardCharsets.UTF_8);
    }

    private static String longText() {
        return ("DraftAgent 文档上传测试内容。This paragraph is long enough to satisfy the minimum token length "
                + "and to produce at least one meaningful chunk for persistence verification. "
                + "It contains multiple sentences. ").repeat(3);
    }

    private static byte[] createPdf(String text) throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            try (PDPageContentStream contentStream = new PDPageContentStream(document, page)) {
                contentStream.beginText();
                contentStream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                contentStream.newLineAtOffset(72, 720);
                contentStream.showText(text);
                contentStream.endText();
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            document.save(output);
            return output.toByteArray();
        }
    }
}
