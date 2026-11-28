package com.draftagent.ai;

import com.draftagent.ai.controller.AiController;
import com.draftagent.ai.service.AiService;
import com.draftagent.common.core.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * conversationId / requestId 参数校验与透传测试（不启动容器、不连接数据库、不调用真实 DeepSeek）。
 */
class AiControllerConversationIdTest {

    private static final String VALID_CONVERSATION_ID = "11111111-1111-4111-8111-111111111111";

    private static final String VALID_REQUEST_ID = "22222222-2222-4222-8222-222222222222";

    private MockMvc mockMvc;

    private AiService aiService;

    @BeforeEach
    void setUp() {
        aiService = mock(AiService.class);
        AiController controller = new AiController(aiService);

        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        mockMvc = MockMvcBuilders
                .standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .build();
    }

    @Test
    void missingConversationId_returns400() throws Exception {
        mockMvc.perform(post("/ai/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + VALID_REQUEST_ID + "\",\"message\":\"hello\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        verifyNoInteractions(aiService);
    }

    @Test
    void malformedConversationId_returns400() throws Exception {
        mockMvc.perform(post("/ai/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conversationId\":\"not-a-uuid\",\"requestId\":\"" + VALID_REQUEST_ID
                                + "\",\"message\":\"hello\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        verifyNoInteractions(aiService);
    }

    @Test
    void missingRequestId_returns400() throws Exception {
        mockMvc.perform(post("/ai/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conversationId\":\"" + VALID_CONVERSATION_ID + "\",\"message\":\"hello\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        verifyNoInteractions(aiService);
    }

    @Test
    void malformedRequestId_returns400() throws Exception {
        mockMvc.perform(post("/ai/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conversationId\":\"" + VALID_CONVERSATION_ID
                                + "\",\"requestId\":\"not-a-uuid\",\"message\":\"hello\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        verifyNoInteractions(aiService);
    }

    @Test
    void validRequest_passesAllParametersToService() throws Exception {
        mockMvc.perform(post("/ai/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conversationId\":\"" + VALID_CONVERSATION_ID
                                + "\",\"requestId\":\"" + VALID_REQUEST_ID + "\",\"message\":\"hello\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(aiService).chat(VALID_CONVERSATION_ID, VALID_REQUEST_ID, "hello");
    }
}
