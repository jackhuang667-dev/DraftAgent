package com.draftagent.rag;

import com.draftagent.ai.rag.answer.ChatClientRagModelInvoker;
import com.draftagent.common.core.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.client.RestClientException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * ChatClient RAG 调用边界：仅 system/user 调用，不附加 Tools / ChatMemory / Advisor。
 */
class ChatClientRagModelInvokerTest {

    @SuppressWarnings("unchecked")
    private final ObjectProvider<ChatClient> provider = mock(ObjectProvider.class);

    private final ChatClient chatClient = mock(ChatClient.class);

    private final ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);

    private final ChatClient.CallResponseSpec responseSpec = mock(ChatClient.CallResponseSpec.class);

    @Test
    void invokesOnlySystemAndUserWithoutToolsOrAdvisors() {
        when(this.provider.getIfAvailable()).thenReturn(this.chatClient);
        when(this.chatClient.prompt()).thenReturn(this.requestSpec);
        when(this.requestSpec.system(anyString())).thenReturn(this.requestSpec);
        when(this.requestSpec.user(anyString())).thenReturn(this.requestSpec);
        when(this.requestSpec.call()).thenReturn(this.responseSpec);
        when(this.responseSpec.content()).thenReturn("回答内容");

        String result = invoker().invoke("system-text", "user-text");

        assertThat(result).isEqualTo("回答内容");
        verify(this.requestSpec).system("system-text");
        verify(this.requestSpec).user("user-text");
        verify(this.requestSpec).call();
        // 除 system/user/call 外不得调用 tools/toolContext/advisors/messages
        verifyNoMoreInteractions(this.requestSpec);
        verify(this.chatClient).prompt();
        verifyNoMoreInteractions(this.chatClient);
    }

    @Test
    void missingChatClientFailsWith503() {
        when(this.provider.getIfAvailable()).thenReturn(null);

        assertThatThrownBy(() -> invoker().invoke("system", "user"))
                .isInstanceOfSatisfying(ServiceException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(503);
                    assertThat(e.getMessage()).contains("DEEPSEEK_API_KEY");
                });
        verifyNoInteractions(this.chatClient);
    }

    @Test
    void nonTransientUpstreamFailureIsSanitized() {
        stubResponseThrowing(new NonTransientAiException("401 Unauthorized"));

        assertThatThrownBy(() -> invoker().invoke("system", "user"))
                .isInstanceOfSatisfying(ServiceException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(502);
                    assertThat(e.getMessage()).contains("认证失败");
                });
    }

    @Test
    void transientUpstreamFailureIsSanitized() {
        stubResponseThrowing(new TransientAiException("timeout"));

        assertThatThrownBy(() -> invoker().invoke("system", "user"))
                .isInstanceOfSatisfying(ServiceException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(502);
                    assertThat(e.getMessage()).contains("暂时不可用");
                });
    }

    @Test
    void restClientFailureIsSanitized() {
        stubResponseThrowing(new RestClientException("connection reset"));

        assertThatThrownBy(() -> invoker().invoke("system", "user"))
                .isInstanceOfSatisfying(ServiceException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(502);
                    assertThat(e.getMessage()).contains("调用失败");
                });
    }

    private void stubResponseThrowing(RuntimeException exception) {
        when(this.provider.getIfAvailable()).thenReturn(this.chatClient);
        when(this.chatClient.prompt()).thenReturn(this.requestSpec);
        when(this.requestSpec.system(anyString())).thenReturn(this.requestSpec);
        when(this.requestSpec.user(anyString())).thenReturn(this.requestSpec);
        when(this.requestSpec.call()).thenReturn(this.responseSpec);
        when(this.responseSpec.content()).thenThrow(exception);
    }

    private ChatClientRagModelInvoker invoker() {
        return new ChatClientRagModelInvoker(this.provider);
    }
}
