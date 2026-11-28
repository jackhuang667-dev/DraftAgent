package com.draftagent.rag;

import com.draftagent.ai.rag.answer.DeepSeekRagAnswerGenerator;
import com.draftagent.ai.rag.answer.RagChatModelInvoker;
import com.draftagent.ai.rag.answer.RagCitationValidator;
import com.draftagent.ai.rag.answer.RagContextSelector;
import com.draftagent.ai.rag.answer.RagPromptBuilder;
import com.draftagent.ai.rag.retrieval.RagRetrievalProperties;
import com.draftagent.business.document.RetrievedDocumentChunk;
import com.draftagent.business.rag.RagAnswer;
import com.draftagent.business.rag.RagSource;
import com.draftagent.common.core.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RAG 生成器：正常回答、空响应、异常转换与后端真实来源映射（Mock 模型调用边界）。
 */
class DeepSeekRagAnswerGeneratorTest {

    private final RagChatModelInvoker invoker = mock(RagChatModelInvoker.class);

    @Test
    void returnsAnswerAndRealSources() {
        String documentA = UUID.randomUUID().toString();
        String documentB = UUID.randomUUID().toString();
        List<RetrievedDocumentChunk> chunks = List.of(
                new RetrievedDocumentChunk(documentA, 11L, 101L, 0, "内容 A", 0.92f),
                new RetrievedDocumentChunk(documentB, 12L, 102L, 3, "内容 B", 0.81f));
        when(this.invoker.invoke(anyString(), anyString())).thenReturn("根据资料，A 的规则是……[1]，B 的情况见 [2]。");

        RagAnswer answer = generator().generate("问题", chunks);

        assertThat(answer.getAnswer()).contains("[1]", "[2]");
        assertThat(answer.isHasContext()).isTrue();
        assertThat(answer.getSources()).hasSize(2);
        RagSource first = answer.getSources().get(0);
        assertThat(first.getReferenceId()).isEqualTo(1);
        assertThat(first.getDocumentId()).isEqualTo(documentA);
        assertThat(first.getChunkId()).isEqualTo(101L);
        assertThat(first.getChunkIndex()).isZero();
        assertThat(first.getScore()).isEqualTo(0.92f);
        RagSource second = answer.getSources().get(1);
        assertThat(second.getReferenceId()).isEqualTo(2);
        assertThat(second.getDocumentId()).isEqualTo(documentB);
        assertThat(second.getChunkId()).isEqualTo(102L);
        assertThat(second.getChunkIndex()).isEqualTo(3);

        ArgumentCaptor<String> system = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> user = ArgumentCaptor.forClass(String.class);
        verify(this.invoker).invoke(system.capture(), user.capture());
        assertThat(system.getValue()).contains("知识库问答助手");
        assertThat(user.getValue()).contains("问题").contains("内容 A").contains("内容 B");
    }

    @Test
    void nullModelResponseIsRejected() {
        when(this.invoker.invoke(anyString(), anyString())).thenReturn(null);

        assertThatThrownBy(() -> generator().generate("问题", List.of(chunk())))
                .isInstanceOfSatisfying(ServiceException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(502);
                    assertThat(e.getMessage()).contains("空响应");
                });
    }

    @Test
    void blankModelResponseIsRejected() {
        when(this.invoker.invoke(anyString(), anyString())).thenReturn("   \n ");

        assertThatThrownBy(() -> generator().generate("问题", List.of(chunk())))
                .isInstanceOfSatisfying(ServiceException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(502);
                    assertThat(e.getMessage()).contains("空响应");
                });
    }

    @Test
    void modelRuntimeExceptionIsSanitized() {
        when(this.invoker.invoke(anyString(), anyString())).thenThrow(new IllegalStateException("internal detail"));

        assertThatThrownBy(() -> generator().generate("问题", List.of(chunk())))
                .isInstanceOfSatisfying(ServiceException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(502);
                    assertThat(e.getMessage()).isEqualTo("RAG 回答生成失败，请稍后重试");
                });
    }

    @Test
    void serviceExceptionFromModelBoundaryIsPreserved() {
        when(this.invoker.invoke(anyString(), anyString()))
                .thenThrow(new ServiceException(503, "AI 服务未配置（缺少 DEEPSEEK_API_KEY），暂不可用"));

        assertThatThrownBy(() -> generator().generate("问题", List.of(chunk())))
                .isInstanceOfSatisfying(ServiceException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(503);
                    assertThat(e.getMessage()).contains("DEEPSEEK_API_KEY");
                });
    }

    @Test
    void emptyChunksIsRejectedWithoutCallingModel() {
        assertThatThrownBy(() -> generator().generate("问题", List.of()))
                .isInstanceOfSatisfying(ServiceException.class,
                        e -> assertThat(e.getCode()).isEqualTo(400));
    }

    @Test
    void budgetSelectsPrefixAndSourcesMatchPrompt() {
        String document = UUID.randomUUID().toString();
        RetrievedDocumentChunk first = new RetrievedDocumentChunk(
                document, 11L, 101L, 0, "A".repeat(10), 0.9f);
        RetrievedDocumentChunk second = new RetrievedDocumentChunk(
                document, 12L, 102L, 0, "B".repeat(500), 0.8f);
        when(this.invoker.invoke(anyString(), anyString())).thenReturn("回答[1]");

        RagAnswer answer = generator(200).generate("问题", List.of(first, second));

        assertThat(answer.getSources()).hasSize(1);
        assertThat(answer.getSources().get(0).getChunkId()).isEqualTo(101L);
        ArgumentCaptor<String> user = ArgumentCaptor.forClass(String.class);
        verify(this.invoker).invoke(anyString(), user.capture());
        assertThat(user.getValue()).contains("A".repeat(10));
        assertThat(user.getValue()).doesNotContain("B".repeat(500));
    }

    @Test
    void singleOverBudgetChunkIsStillUsedInFull() {
        String content = "H".repeat(2000);
        when(this.invoker.invoke(anyString(), anyString())).thenReturn("回答[1]");

        RagAnswer answer = generator(50).generate("问题", List.of(
                new RetrievedDocumentChunk(UUID.randomUUID().toString(), 11L, 101L, 0, content, 0.9f)));

        assertThat(answer.getSources()).hasSize(1);
        ArgumentCaptor<String> user = ArgumentCaptor.forClass(String.class);
        verify(this.invoker).invoke(anyString(), user.capture());
        assertThat(user.getValue()).contains(content);
    }

    @Test
    void fakeDocumentAndChunkIdsInAnswerDoNotOverrideSources() {
        String realDocumentId = UUID.randomUUID().toString();
        when(this.invoker.invoke(anyString(), anyString()))
                .thenReturn("伪造 documentId=evil, chunkId=999，实际引用[1]。");

        RagAnswer answer = generator().generate("问题", List.of(
                new RetrievedDocumentChunk(realDocumentId, 11L, 101L, 0, "内容", 0.9f)));

        assertThat(answer.getSources()).hasSize(1);
        assertThat(answer.getSources().get(0).getDocumentId()).isEqualTo(realDocumentId);
        assertThat(answer.getSources().get(0).getChunkId()).isEqualTo(101L);
    }

    @Test
    void invalidCitationsAreRemovedWhileValidAndNormalNumbersAreKept() {
        when(this.invoker.invoke(anyString(), anyString()))
                .thenReturn("有效[1]，无效[99]，年份[2024]。");

        RagAnswer answer = generator().generate("问题", List.of(chunk()));

        assertThat(answer.getAnswer()).contains("[1]").doesNotContain("[99]").contains("[2024]");
        assertThat(answer.getSources()).hasSize(1);
    }

    @Test
    void answerWithOnlyInvalidCitationIsRejected() {
        when(this.invoker.invoke(anyString(), anyString())).thenReturn("[99]");

        assertThatThrownBy(() -> generator().generate("问题", List.of(chunk())))
                .isInstanceOfSatisfying(ServiceException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(502);
                    assertThat(e.getMessage()).contains("空响应");
                });
    }

    private DeepSeekRagAnswerGenerator generator() {
        return generator(12000);
    }

    private DeepSeekRagAnswerGenerator generator(int maxContextChars) {
        RagRetrievalProperties properties = new RagRetrievalProperties();
        properties.setMaxContextChars(maxContextChars);
        return new DeepSeekRagAnswerGenerator(
                new RagContextSelector(properties),
                new RagPromptBuilder(),
                this.invoker,
                new RagCitationValidator());
    }

    private static RetrievedDocumentChunk chunk() {
        return new RetrievedDocumentChunk(UUID.randomUUID().toString(), 11L, 101L, 0, "内容", 0.9f);
    }
}
