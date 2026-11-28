package com.draftagent.ai.rag.answer;

import com.draftagent.ai.rag.retrieval.RagRetrievalProperties;
import com.draftagent.business.document.RetrievedDocumentChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * RAG 参考资料上下文预算选择。
 *
 * <p>按 Qdrant 原始排名顺序加入 Chunk，直到达到 {@code rag.retrieval.max-context-chars}；
 * 下一个 Chunk 超预算时立即停止，不跳过高排名 Chunk 去选低排名 Chunk，也不截断任何 Chunk。
 * 为满足“至少允许一个合法 Chunk”，当最高排名 Chunk 本身超出预算时仍会保留它并记录告警，
 * 但不会再加入后续 Chunk。</p>
 *
 * <p>预算口径与 {@link RagReferenceFormatter} 完全一致：仅统计格式化后的引用块（含 [n]、
 * 来源元数据、正文）及块间分隔符，不包含 System Prompt 与用户问题。</p>
 */
@Component
public class RagContextSelector {

    private static final Logger log = LoggerFactory.getLogger(RagContextSelector.class);

    private final int maxContextChars;

    public RagContextSelector(RagRetrievalProperties properties) {
        this.maxContextChars = properties.getMaxContextChars();
    }

    public List<RetrievedDocumentChunk> select(List<RetrievedDocumentChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return List.of();
        }
        List<RetrievedDocumentChunk> selected = new ArrayList<>();
        int used = 0;
        for (RetrievedDocumentChunk chunk : chunks) {
            int referenceId = selected.size() + 1;
            int blockLength = RagReferenceFormatter.length(referenceId, chunk);
            int cost = selected.isEmpty() ? blockLength : blockLength + RagReferenceFormatter.SEPARATOR_CHARS;
            if (selected.isEmpty()) {
                selected.add(chunk);
                used = cost;
                if (cost > this.maxContextChars) {
                    log.warn("单个 Chunk 超出 RAG 上下文预算，按至少保留一个 Chunk 的策略继续: "
                                    + "chunkId={}, blockChars={}, budget={}",
                            chunk.getChunkId(), cost, this.maxContextChars);
                }
                continue;
            }
            if (used + cost > this.maxContextChars) {
                log.debug("RAG 上下文预算已满，停止加入后续 Chunk: selected={}, usedChars={}, budget={}",
                        selected.size(), used, this.maxContextChars);
                break;
            }
            selected.add(chunk);
            used += cost;
        }
        return selected;
    }
}
