package com.draftagent.rag;

import com.draftagent.business.document.DocumentSearchException;
import com.draftagent.business.document.DocumentSearchScope;
import com.draftagent.business.document.RetrievedDocumentChunk;
import com.draftagent.business.rag.RagAnswer;
import com.draftagent.business.rag.RagAnswerGenerator;
import com.draftagent.business.rag.RagDocumentRetriever;
import com.draftagent.business.rag.RagSource;
import com.draftagent.business.service.RagAnswerService;
import com.draftagent.common.core.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * RAG 编排：参数校验、无命中短路、生成端口调用与真实来源透传。
 */
class RagAnswerServiceTest {

    private final RagDocumentRetriever retriever = mock(RagDocumentRetriever.class);

    private final RagAnswerGenerator generator = mock(RagAnswerGenerator.class);

    private final DocumentSearchScope scope = new DocumentSearchScope(7L, 100L);

    @Test
    void callsGeneratorWhenChunksExist() {
        List<RetrievedDocumentChunk> chunks = List.of(chunk(101L, "内容 A"), chunk(102L, "内容 B"));
        RagAnswer expected = answerWithSources(chunks, "回答 [1][2]");
        when(this.retriever.retrieve("问题", this.scope)).thenReturn(chunks);
        when(this.generator.generate("问题", chunks)).thenReturn(expected);

        RagAnswer actual = service().answer("问题", this.scope);

        assertThat(actual).isSameAs(expected);
        verify(this.generator).generate("问题", chunks);
    }

    @Test
    void passesRealChunksToGeneratorForSourceMapping() {
        List<RetrievedDocumentChunk> chunks = List.of(chunk(101L, "内容 A"));
        when(this.retriever.retrieve("问题", this.scope)).thenReturn(chunks);
        when(this.generator.generate(anyString(), any())).thenReturn(answerWithSources(chunks, "回答"));

        service().answer("问题", this.scope);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<RetrievedDocumentChunk>> captor = ArgumentCaptor.forClass(List.class);
        verify(this.generator).generate(anyString(), captor.capture());
        assertThat(captor.getValue()).isSameAs(chunks);
        assertThat(captor.getValue().get(0).getChunkId()).isEqualTo(101L);
        assertThat(captor.getValue().get(0).getContent()).isEqualTo("内容 A");
    }

    @Test
    void noChunksReturnsFixedNoContextAnswerWithoutCallingModel() {
        when(this.retriever.retrieve("问题", this.scope)).thenReturn(List.of());

        RagAnswer answer = service().answer("问题", this.scope);

        assertThat(answer.isHasContext()).isFalse();
        assertThat(answer.getSources()).isEmpty();
        assertThat(answer.getAnswer()).isEqualTo(RagAnswerService.NO_CONTEXT_ANSWER);
        verifyNoInteractions(this.generator);
    }

    @Test
    void nullChunksTreatedAsNoContext() {
        when(this.retriever.retrieve("问题", this.scope)).thenReturn(null);

        RagAnswer answer = service().answer("问题", this.scope);

        assertThat(answer.isHasContext()).isFalse();
        assertThat(answer.getSources()).isEmpty();
        verifyNoInteractions(this.generator);
    }

    @Test
    void blankQueryIsRejected() {
        assertThatThrownBy(() -> service().answer("   ", this.scope))
                .isInstanceOfSatisfying(ServiceException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(400);
                    assertThat(e.getMessage()).contains("问题不能为空");
                });
        verifyNoInteractions(this.retriever, this.generator);
    }

    @Test
    void tooLongQueryIsRejected() {
        String query = "a".repeat(4001);

        assertThatThrownBy(() -> service().answer(query, this.scope))
                .isInstanceOfSatisfying(ServiceException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(400);
                    assertThat(e.getMessage()).contains("长度");
                });
        verifyNoInteractions(this.retriever, this.generator);
    }

    @Test
    void nullScopeIsRejected() {
        assertThatThrownBy(() -> service().answer("问题", null))
                .isInstanceOfSatisfying(ServiceException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(400);
                    assertThat(e.getMessage()).contains("检索范围");
                });
        verifyNoInteractions(this.retriever, this.generator);
    }

    @Test
    void defaultTopKUsesRetrieverDefault() {
        when(this.retriever.retrieve("问题", this.scope)).thenReturn(List.of());

        service().answer("问题", this.scope);

        verify(this.retriever).retrieve("问题", this.scope);
    }

    @Test
    void explicitTopKIsDelegated() {
        when(this.retriever.retrieve("问题", this.scope, 3)).thenReturn(List.of());

        service().answer("问题", this.scope, 3);

        verify(this.retriever).retrieve("问题", this.scope, 3);
    }

    @Test
    void retrievalFailureIsMappedTo503WithoutCallingGenerator() {
        when(this.retriever.retrieve("问题", this.scope))
                .thenThrow(new DocumentSearchException("Embedding 未配置（缺少 EMBEDDING_API_KEY），无法执行检索"));

        assertThatThrownBy(() -> service().answer("问题", this.scope))
                .isInstanceOfSatisfying(ServiceException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(503);
                    assertThat(e.getMessage()).contains("EMBEDDING_API_KEY");
                });
        verifyNoInteractions(this.generator);
    }

    @Test
    void generatorFailureIsSanitizedAs502() {
        List<RetrievedDocumentChunk> chunks = List.of(chunk(101L, "内容"));
        when(this.retriever.retrieve("问题", this.scope)).thenReturn(chunks);
        when(this.generator.generate("问题", chunks)).thenThrow(new IllegalStateException("internal detail"));

        assertThatThrownBy(() -> service().answer("问题", this.scope))
                .isInstanceOfSatisfying(ServiceException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(502);
                    assertThat(e.getMessage()).isEqualTo("RAG 回答生成失败，请稍后重试");
                });
    }

    @Test
    void nullGeneratorResultIsRejected() {
        List<RetrievedDocumentChunk> chunks = List.of(chunk(101L, "内容"));
        when(this.retriever.retrieve("问题", this.scope)).thenReturn(chunks);
        when(this.generator.generate("问题", chunks)).thenReturn(null);

        assertThatThrownBy(() -> service().answer("问题", this.scope))
                .isInstanceOfSatisfying(ServiceException.class,
                        e -> assertThat(e.getCode()).isEqualTo(502));
    }

    private RagAnswerService service() {
        return new RagAnswerService(this.retriever, this.generator);
    }

    private static RetrievedDocumentChunk chunk(long chunkId, String content) {
        return new RetrievedDocumentChunk(UUID.randomUUID().toString(), 1000L + chunkId,
                chunkId, 0, content, 0.9f);
    }

    private static RagAnswer answerWithSources(List<RetrievedDocumentChunk> chunks, String answer) {
        List<RagSource> sources = new java.util.ArrayList<>();
        int referenceId = 1;
        for (RetrievedDocumentChunk chunk : chunks) {
            sources.add(new RagSource(referenceId++, chunk.getDocumentId(), chunk.getChunkId(),
                    chunk.getChunkIndex(), chunk.getScore()));
        }
        return new RagAnswer(answer, sources, true);
    }
}
