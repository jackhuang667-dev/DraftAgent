package com.draftagent.ai.rag.answer;

import com.draftagent.business.document.RetrievedDocumentChunk;

/**
 * 单个 Chunk 引用块的统一格式化与长度计算。
 *
 * <p>Prompt 组装与上下文预算选择必须使用同一套格式化逻辑，避免“预算按一种口径计算、
 * Prompt 按另一种口径渲染”导致预算失真。长度仅包含引用块本身（[n]、来源元数据、正文），
 * 不包含 System Prompt、用户问题与参考资料标题。</p>
 */
final class RagReferenceFormatter {

    /** Prompt 中两个引用块之间的分隔符（一个换行）字符数。 */
    static final int SEPARATOR_CHARS = 1;

    private RagReferenceFormatter() {
    }

    static String format(int referenceId, RetrievedDocumentChunk chunk) {
        StringBuilder block = new StringBuilder();
        block.append('[').append(referenceId).append("]\n")
                .append("Document ID: ").append(nullSafe(chunk.getDocumentId())).append('\n')
                .append("Chunk ID: ").append(nullSafe(chunk.getChunkId())).append('\n')
                .append("Chunk Index: ").append(nullSafe(chunk.getChunkIndex())).append('\n')
                .append("正文：\n")
                .append(chunk.getContent() == null ? "" : chunk.getContent());
        return block.toString();
    }

    static int length(int referenceId, RetrievedDocumentChunk chunk) {
        return format(referenceId, chunk).length();
    }

    private static String nullSafe(Object value) {
        return value == null ? "" : value.toString();
    }
}
