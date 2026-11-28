package com.draftagent.ai;

import java.util.List;

import com.draftagent.ai.service.AiService;
import com.draftagent.ai.tool.DraftTools;
import com.draftagent.business.dto.TurnContext;
import com.draftagent.business.service.ConversationService;
import com.draftagent.business.service.DraftService;
import com.draftagent.business.service.ToolExecutionService;
import com.draftagent.common.core.exception.ServiceException;
import com.draftagent.common.security.CurrentUser;
import com.draftagent.common.security.CurrentUserProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.client.ResourceAccessException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AiService Agent 主流程单元测试（T1/model/ChatMemory/T2、失败 T3、ToolContext、工具保留）。
 *
 * <p>使用桩 {@link ChatModel} 捕获发往模型的 Prompt；Mock 掉 ConversationService/ChatMemory；
 * 不调用真实 DeepSeek、不连接数据库、不创建草案。</p>
 */
class AiServiceManualChatMemoryTest {

    private static final String CONVERSATION_ID = "11111111-1111-4111-8111-111111111111";

    private static final String REQUEST_ID = "22222222-2222-4222-8222-222222222222";

    private static final String TURN_ID = "33333333-3333-4333-8333-333333333333";

    private static final String USER_TEXT = "当前用户消息";

    private static final Long CONVERSATION_PK = 99L;

    private static final Long ASSISTANT_MESSAGE_ID = 88L;

    private static final Long USER_ID = 101L;

    private static final Long TENANT_ID = 5L;

    private ChatMemory chatMemory;

    private ObjectProvider<ChatClient> chatClientProvider;

    private ConversationService conversationService;

    private CurrentUserProvider currentUserProvider;

    private AiService aiService;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        this.chatMemory = mock(ChatMemory.class);
        this.chatClientProvider = mock(ObjectProvider.class);
        this.conversationService = mock(ConversationService.class);
        this.currentUserProvider = mock(CurrentUserProvider.class);
        when(this.currentUserProvider.getCurrentUser()).thenReturn(new CurrentUser(USER_ID, TENANT_ID));
        DraftTools draftTools = new DraftTools(mock(DraftService.class), mock(ToolExecutionService.class));
        this.aiService = new AiService(this.chatMemory, this.chatClientProvider, draftTools,
                this.conversationService, this.currentUserProvider);

        when(this.conversationService.beginTurn(anyString(), anyString(), anyLong(), any()))
                .thenReturn(new TurnContext(CONVERSATION_PK, CONVERSATION_ID, 77L, ASSISTANT_MESSAGE_ID, 1L, 2L, TURN_ID));
    }

    @Test
    void successPath_sendsHistoryAndUser_writesMemory_andCompletesTurn() {
        List<Message> history = List.of(
                new UserMessage("历史用户消息"),
                new AssistantMessage("历史助手回复"));
        when(this.chatMemory.get(CONVERSATION_ID)).thenReturn(history);
        CapturingChatModel model = bindModel("模型回复");

        String reply = this.aiService.chat(CONVERSATION_ID, REQUEST_ID, USER_TEXT);

        assertThat(reply).isEqualTo("模型回复");

        verify(this.conversationService).beginTurn(CONVERSATION_ID, USER_TEXT, USER_ID, TENANT_ID);

        Prompt prompt = model.lastPrompt;
        assertThat(prompt).isNotNull();
        List<Message> sent = prompt.getInstructions();
        assertThat(sent).hasSize(3);
        assertThat(sent.get(0).getMessageType()).isEqualTo(MessageType.USER);
        assertThat(sent.get(0).getText()).isEqualTo("历史用户消息");
        assertThat(sent.get(1).getMessageType()).isEqualTo(MessageType.ASSISTANT);
        assertThat(sent.get(1).getText()).isEqualTo("历史助手回复");
        assertThat(sent.get(2).getMessageType()).isEqualTo(MessageType.USER);
        assertThat(sent.get(2).getText()).isEqualTo(USER_TEXT);

        assertThat(prompt.getOptions()).isInstanceOf(ToolCallingChatOptions.class);
        ToolCallingChatOptions options = (ToolCallingChatOptions) prompt.getOptions();
        assertThat(options.getToolCallbacks())
                .extracting(callback -> callback.getToolDefinition().name())
                .contains("createDraft", "queryDraftStatus");
        assertThat(options.getToolContext())
                .containsEntry("requestId", REQUEST_ID)
                .containsEntry("conversationPk", CONVERSATION_PK)
                .containsEntry("turnId", TURN_ID)
                .containsEntry("userId", USER_ID)
                .containsEntry("tenantId", TENANT_ID);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Message>> captor = ArgumentCaptor.forClass(List.class);
        verify(this.chatMemory).add(eq(CONVERSATION_ID), captor.capture());
        List<Message> added = captor.getValue();
        assertThat(added).hasSize(2);
        assertThat(added.get(0).getMessageType()).isEqualTo(MessageType.USER);
        assertThat(added.get(0).getText()).isEqualTo(USER_TEXT);
        assertThat(added.get(1).getMessageType()).isEqualTo(MessageType.ASSISTANT);
        assertThat(added.get(1).getText()).isEqualTo("模型回复");

        verify(this.conversationService).completeTurn(ASSISTANT_MESSAGE_ID, "模型回复");
        verify(this.conversationService, never()).failTurn(anyLong(), anyString());
    }

    @Test
    void failurePath_doesNotWriteMemory_andFailsTurn() {
        when(this.chatMemory.get(CONVERSATION_ID)).thenReturn(List.of());
        CapturingChatModel model = bindModel("unused");
        model.failure = new ResourceAccessException("connection refused");

        assertThatThrownBy(() -> this.aiService.chat(CONVERSATION_ID, REQUEST_ID, USER_TEXT))
                .isInstanceOfSatisfying(ServiceException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(502));

        verify(this.conversationService).beginTurn(CONVERSATION_ID, USER_TEXT, USER_ID, TENANT_ID);
        verify(this.conversationService).failTurn(ASSISTANT_MESSAGE_ID, "AI_CALL_FAILED");
        verify(this.conversationService, never()).completeTurn(anyLong(), anyString());
        verify(this.chatMemory, never()).add(anyString(), anyList());
    }

    private CapturingChatModel bindModel(String reply) {
        CapturingChatModel model = new CapturingChatModel(reply);
        ChatClient chatClient = ChatClient.builder(model).build();
        when(this.chatClientProvider.getIfAvailable()).thenReturn(chatClient);
        return model;
    }

    private static final class CapturingChatModel implements ChatModel {

        private final String reply;

        private RuntimeException failure;

        private Prompt lastPrompt;

        private CapturingChatModel(String reply) {
            this.reply = reply;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            this.lastPrompt = prompt;
            if (this.failure != null) {
                throw this.failure;
            }
            return new ChatResponse(List.of(new Generation(new AssistantMessage(this.reply))));
        }

        @Override
        public ToolCallingChatOptions getDefaultOptions() {
            return ToolCallingChatOptions.builder().build();
        }
    }
}
