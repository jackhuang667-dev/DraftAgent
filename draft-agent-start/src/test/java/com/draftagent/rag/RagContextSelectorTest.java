package com.draftagent.rag;

import com.draftagent.ai.rag.answer.RagContextSelector;
import com.draftagent.ai.rag.retrieval.RagRetrievalProperties;
import com.draftagent.business.document.RetrievedDocumentChunk;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RAG 上下文预算：按排名前缀选择、不跳过、不截断、单 Chunk 超预算策略。
 */
class RagContextSelectorTest {

    @Test
    void selectsAllChunksWhenWithinBudget() {
        List<RetrievedDocumentChunk> chunks = List.of(chunk(1L, "A"), chunk(2L, "B"), chunk(3L, "C"));

        List<RetrievedDocumentChunk> selected = selector(100_000).select(chunks);

        assertThat(selected).extracting(RetrievedDocumentChunk::getChunkId).containsExactly(1L, 2L, 3L);
    }

    @Test
    void stopsBeforeChunkThatExceedsBudgetAndDoesNotSkipToLowerRank() {
        RetrievedDocumentChunk first = chunk(1L, "A".repeat(10));
        RetrievedDocumentChunk second = chunk(2L, "B".repeat(500));
        RetrievedDocumentChunk third = chunk(3L, "C".repeat(10));

        List<RetrievedDocumentChunk> selected = selector(200).select(List.of(first, second, third));

        assertThat(selected).extracting(RetrievedDocumentChunk::getChunkId).containsExactly(1L);
    }

    @Test
    void doesNotTruncateSelectedChunk() {
        String content = "完整正文-" + "X".repeat(50);
        RetrievedDocumentChunk first = chunk(1L, content);

        List<RetrievedDocumentChunk> selected = selector(100_000).select(List.of(first));

        assertThat(selected).hasSize(1);
        assertThat(selected.get(0).getContent()).isEqualTo(content);
    }

    @Test
    void keepsFirstChunkEvenWhenItAloneExceedsBudget() {
        RetrievedDocumentChunk huge = chunk(1L, "H".repeat(2000));
        RetrievedDocumentChunk small = chunk(2L, "S");

        List<RetrievedDocumentChunk> selected = selector(50).select(List.of(huge, small));

        assertThat(selected).hasSize(1);
        assertThat(selected.get(0).getChunkId()).isEqualTo(1L);
        assertThat(selected.get(0).getContent()).isEqualTo("H".repeat(2000));
    }

    @Test
    void emptyInputReturnsEmpty() {
        assertThat(selector(1000).select(List.of())).isEmpty();
        assertThat(selector(1000).select(null)).isEmpty();
    }

    private static RagContextSelector selector(int maxContextChars) {
        RagRetrievalProperties properties = new RagRetrievalProperties();
        properties.setMaxContextChars(maxContextChars);
        return new RagContextSelector(properties);
    }

    private static RetrievedDocumentChunk chunk(long chunkId, String content) {
        return new RetrievedDocumentChunk(UUID.randomUUID().toString(), 1000L + chunkId,
                chunkId, 0, content, 0.9f);
    }
}
