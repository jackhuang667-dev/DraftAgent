package com.draftagent.ai;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.draftagent.ai.service.AiService;
import com.draftagent.ai.tool.DraftTools;
import com.draftagent.business.dto.TurnContext;
import com.draftagent.business.service.ConversationService;
import com.draftagent.business.service.DraftService;
import com.draftagent.business.service.ToolExecutionService;
import com.draftagent.common.security.CurrentUser;
import com.draftagent.common.security.CurrentUserProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 会话串行语义测试：同一 conversationId 串行，不同 conversationId 并行。
 *
 * <p>通过桩 ChatModel 统计“同时进入模型调用”的最大并发数。纯进程内、无真实模型/数据库。</p>
 */
class AiServiceConcurrencyTest {

    private static final String CONVERSATION_A = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";

    private static final String CONVERSATION_B = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";

    private ChatMemory chatMemory;

    private ObjectProvider<ChatClient> chatClientProvider;

    private ConversationService conversationService;

    private CurrentUserProvider currentUserProvider;

    private AiService aiService;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        this.chatMemory = mock(ChatMemory.class);
        when(this.chatMemory.get(anyString())).thenReturn(List.of());

        this.conversationService = mock(ConversationService.class);
        AtomicLong idGen = new AtomicLong(1000L);
        when(this.conversationService.beginTurn(anyString(), anyString(), anyLong(), any())).thenAnswer(invocation -> {
            long id = idGen.incrementAndGet();
            return new TurnContext(1L, invocation.getArgument(0), id, id + 1, 1L, 2L,
                    UUID.randomUUID().toString());
        });

        this.currentUserProvider = mock(CurrentUserProvider.class);
        when(this.currentUserProvider.getCurrentUser()).thenReturn(new CurrentUser(1L, null));

        this.chatClientProvider = mock(ObjectProvider.class);
        this.aiService = new AiService(this.chatMemory, this.chatClientProvider,
                new DraftTools(mock(DraftService.class), mock(ToolExecutionService.class)),
                this.conversationService, this.currentUserProvider);
    }

    @Test
    void sameConversationIsSerialized() throws Exception {
        TrackingChatModel model = new TrackingChatModel(200L);
        bind(model);

        runTwo(() -> this.aiService.chat(CONVERSATION_A, UUID.randomUUID().toString(), "hi"));

        assertThat(model.maxActive.get()).isEqualTo(1);
    }

    @Test
    void differentConversationsCanRunInParallel() throws Exception {
        TrackingChatModel model = new TrackingChatModel(300L);
        bind(model);

        runTwo(
                () -> this.aiService.chat(CONVERSATION_A, UUID.randomUUID().toString(), "a"),
                () -> this.aiService.chat(CONVERSATION_B, UUID.randomUUID().toString(), "b"));

        assertThat(model.maxActive.get()).isEqualTo(2);
    }

    private void bind(ChatModel model) {
        ChatClient chatClient = ChatClient.builder(model).build();
        when(this.chatClientProvider.getIfAvailable()).thenReturn(chatClient);
    }

    private void runTwo(Runnable task) throws Exception {
        runTwo(task, task);
    }

    private void runTwo(Runnable first, Runnable second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<?> f1 = pool.submit(() -> {
                awaitQuietly(start);
                first.run();
            });
            Future<?> f2 = pool.submit(() -> {
                awaitQuietly(start);
                second.run();
            });
            start.countDown();
            f1.get(15, TimeUnit.SECONDS);
            f2.get(15, TimeUnit.SECONDS);
        }
        finally {
            pool.shutdownNow();
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class TrackingChatModel implements ChatModel {

        private final long sleepMillis;

        private final AtomicInteger active = new AtomicInteger();

        private final AtomicInteger maxActive = new AtomicInteger();

        private TrackingChatModel(long sleepMillis) {
            this.sleepMillis = sleepMillis;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            int now = this.active.incrementAndGet();
            this.maxActive.accumulateAndGet(now, Math::max);
            try {
                Thread.sleep(this.sleepMillis);
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            finally {
                this.active.decrementAndGet();
            }
            return new ChatResponse(List.of(new Generation(new AssistantMessage("ok"))));
        }

        @Override
        public ToolCallingChatOptions getDefaultOptions() {
            return ToolCallingChatOptions.builder().build();
        }
    }
}
