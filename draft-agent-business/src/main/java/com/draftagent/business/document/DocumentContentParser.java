package com.draftagent.business.document;

import java.nio.file.Path;
import java.util.List;

/**
 * 文档内容解析端口：把磁盘文件解析为按顺序排列的文本分块。
 *
 * <p>由 AI 模块基于 Spring AI（TokenTextSplitter / PDF Reader）实现，业务模块只依赖该接口，
 * 从而保持 {@code business} 不反向依赖 {@code ai}。实现不得把绝对路径写入异常消息。</p>
 */
public interface DocumentContentParser {

    /**
     * 解析文档并切分为文本块。
     *
     * @param file     已落盘的文档文件
     * @param fileType 文档类型
     * @return 按顺序排列的非空文本块列表
     * @throws DocumentParseException 解析失败或未提取到有效文本
     */
    List<String> extractChunks(Path file, DocumentFileType fileType);
}
