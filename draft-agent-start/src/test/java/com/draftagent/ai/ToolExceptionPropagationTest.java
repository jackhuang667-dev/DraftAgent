package com.draftagent.ai;

import java.util.List;

import com.draftagent.ai.controller.AiController;
import com.draftagent.ai.service.AiService;
import com.draftagent.ai.tool.DraftTools;
import com.draftagent.business.dto.TurnContext;
import com.draftagent.business.service.ConversationService;
import com.draftagent.business.service.DraftService;
import com.draftagent.business.service.ToolExecutionService;
import com.draftagent.common.core.exception.GlobalExceptionHandler;
import com.draftagent.common.core.exception.ServiceException;
import com.draftagent.common.security.CurrentUser;
import com.draftagent.common.security.CurrentUserProvider;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.DefaultToolExecutionExceptionProcessor;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 工具异常传播测试（Spring AI 1.0.9 真实异常处理器 + HTTP 映射）。
 *
 * <p>验证：业务 ServiceException(409) 会从工具执行异常处理器原样抛出，并最终映射为 HTTP 409，
 * 而不是被降级成模型文本（HTTP 200）。不调用真实 DeepSeek、不连接数据库。</p>
 */
class ToolExceptionPropagationTest {

    private static final String CONVERSATION_ID = "33333333-3333-4333-8333-333333333333";

    private static final String REQUEST_ID = "44444444-4444-4444-8444-444444444444";

    @Test
    void processorRethrowsBusinessServiceExceptionUnchanged() {
        DefaultToolExecutionExceptionProcessor processor = DefaultToolExecutionExceptionProcessor.builder()
                .alwaysThrow(true)
                .rethrowExceptions(List.<Class<? extends RuntimeException>>of(ServiceException.class))
                .build();
        ToolDefinition definition = ToolDefinition.builder()
                .name("createDraft")
                .description("d")
                .inputSchema("{}")
                .build();

        ServiceException conflict = new ServiceException(409, "相同 requestId 但工具参数不同，拒绝复用旧结果");
        ToolExecutionException wrapped = new ToolExecutionException(definition, conflict);

        assertThatThrownBy(() -> processor.process(wrapped))
                .isSameAs(conflict);
    }

    @Test
    void processorThrowsWrapperForNonBusinessException() {
        DefaultToolExecutionExceptionProcessor processor = DefaultToolExecutionExceptionProcessor.builder()
                .alwaysThrow(true)
                .rethrowExceptions(List.<Class<? extends RuntimeException>>of(ServiceException.class))
                .build();
        ToolDefinition definition = ToolDefinition.builder()
                .name("createDraft")
                .description("d")
                .inputSchema("{}")
                .build();
        ToolExecutionException wrapped = new ToolExecutionException(definition, new IllegalStateException("boom"));

        assertThatThrownBy(() -> processor.process(wrapped))
                .isInstanceOf(ToolExecutionException.class);
    }

    @Test
    void businessToolConflictBecomesHttp409NotHttp200() throws Exception {
        AiService aiService = buildAiService(new ServiceException(409, "相同 requestId 属于其他会话，拒绝复用结果"));
        AiController controller = new AiController(aiService);

        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .build();

        mockMvc.perform(post("/ai/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conversationId\":\"" + CONVERSATION_ID
                                + "\",\"requestId\":\"" + REQUEST_ID + "\",\"message\":\"hello\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409));
    }

    @SuppressWarnings("unchecked")
    private AiService buildAiService(RuntimeException failure) {
        ChatMemory chatMemory = mock(ChatMemory.class);
        when(chatMemory.get(anyString())).thenReturn(List.of());

        ConversationService conversationService = mock(ConversationService.class);
        when(conversationService.beginTurn(anyString(), anyString(), anyLong(), any()))
                .thenReturn(new TurnContext(1L, CONVERSATION_ID, 2L, 3L, 1L, 2L,
                        "55555555-5555-4555-8555-555555555555"));

        CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
        when(currentUserProvider.getCurrentUser()).thenReturn(new CurrentUser(1L, null));

        ChatClient chatClient = ChatClient.builder(new FailingChatModel(failure)).build();
        ObjectProvider<ChatClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(chatClient);

        DraftTools draftTools = new DraftTools(mock(DraftService.class), mock(ToolExecutionService.class));
        return new AiService(chatMemory, provider, draftTools, conversationService, currentUserProvider);
    }

    private static final class FailingChatModel implements ChatModel {

        private final RuntimeException failure;

        private FailingChatModel(RuntimeException failure) {
            this.failure = failure;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            throw this.failure;
        }

        @Override
        public ToolCallingChatOptions getDefaultOptions() {
            return ToolCallingChatOptions.builder().build();
        }
    }
}
