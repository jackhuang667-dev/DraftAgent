package com.draftagent.ai.document;

import com.draftagent.business.document.DocumentContentParser;
import com.draftagent.business.document.DocumentFileType;
import com.draftagent.business.document.DocumentParseException;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 基于 Spring AI 1.0.9 的文档解析实现。
 *
 * <ul>
 *   <li>TXT / Markdown：按 UTF-8 直接读取文本；</li>
 *   <li>PDF：{@link PagePdfDocumentReader}（PDFBox，按页提取文本，仅支持文本型 PDF）；</li>
 *   <li>统一使用 {@link TokenTextSplitter} 切块。</li>
 * </ul>
 *
 * <p>本实现只做文本提取与分块，不涉及 Embedding 或向量存储。异常消息不含本地绝对路径。</p>
 */
@Component
public class SpringAiDocumentContentParser implements DocumentContentParser {

    private final TokenTextSplitter textSplitter;

    public SpringAiDocumentContentParser(
            @Value("${rag.document.chunk-size:800}") int chunkSize,
            @Value("${rag.document.min-chunk-size-chars:350}") int minChunkSizeChars,
            @Value("${rag.document.min-chunk-length-to-embed:5}") int minChunkLengthToEmbed,
            @Value("${rag.document.max-num-chunks:10000}") int maxNumChunks) {
        this.textSplitter = TokenTextSplitter.builder()
                .withChunkSize(chunkSize)
                .withMinChunkSizeChars(minChunkSizeChars)
                .withMinChunkLengthToEmbed(minChunkLengthToEmbed)
                .withMaxNumChunks(maxNumChunks)
                .withKeepSeparator(true)
                .build();
    }

    @Override
    public List<String> extractChunks(Path file, DocumentFileType fileType) {
        List<Document> documents = readDocuments(file, fileType);
        List<Document> chunks;
        try {
            chunks = this.textSplitter.apply(documents);
        }
        catch (RuntimeException e) {
            throw new DocumentParseException("文档分块失败", e);
        }
        List<String> contents = new ArrayList<>();
        if (chunks != null) {
            for (Document chunk : chunks) {
                if (StringUtils.hasText(chunk.getText())) {
                    contents.add(chunk.getText());
                }
            }
        }
        if (contents.isEmpty()) {
            throw new DocumentParseException("未能从文档中提取到有效文本（扫描版 PDF 暂不支持）");
        }
        return contents;
    }

    private List<Document> readDocuments(Path file, DocumentFileType fileType) {
        return switch (fileType) {
            case TXT, MARKDOWN -> readPlainText(file);
            case PDF -> readPdf(file);
        };
    }

    private List<Document> readPlainText(Path file) {
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        }
        catch (IOException e) {
            throw new DocumentParseException("文本读取失败（请确认文件为 UTF-8 编码）", e);
        }
        if (!StringUtils.hasText(text)) {
            throw new DocumentParseException("文档内容为空");
        }
        return List.of(new Document(text));
    }

    private List<Document> readPdf(Path file) {
        List<Document> documents;
        try {
            Resource resource = new FileSystemResource(file);
            documents = new PagePdfDocumentReader(resource).get();
        }
        catch (RuntimeException e) {
            throw new DocumentParseException("PDF 解析失败（仅支持文本型 PDF）", e);
        }
        boolean hasText = documents != null && documents.stream()
                .anyMatch(document -> StringUtils.hasText(document.getText()));
        if (!hasText) {
            throw new DocumentParseException("未能从 PDF 提取到有效文本（扫描版暂不支持 OCR）");
        }
        return documents;
    }
}
