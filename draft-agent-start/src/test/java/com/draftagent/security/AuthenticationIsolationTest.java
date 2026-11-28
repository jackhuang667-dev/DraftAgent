package com.draftagent.security;

import com.draftagent.DraftAgentApplication;
import com.draftagent.ai.service.AiService;
import com.draftagent.security.jwt.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP 认证测试：受保护的 AI 接口未认证返回 401，有效 JWT 可访问。
 *
 * <p>Mock AiService（不调用真实模型），验证安全过滤链行为。</p>
 */
@SpringBootTest(classes = DraftAgentApplication.class)
@AutoConfigureMockMvc
class AuthenticationIsolationTest {

    private static final String CONVERSATION_ID = "aaaaaaaa-1111-4111-8111-111111111111";

    private static final String REQUEST_ID = "bbbbbbbb-2222-4222-8222-222222222222";

    private static final String BODY = "{\"conversationId\":\"" + CONVERSATION_ID
            + "\",\"requestId\":\"" + REQUEST_ID + "\",\"message\":\"hello\"}";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @MockBean
    private AiService aiService;

    @Test
    void healthEndpointIsPublic() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("UP"));
    }

    @Test
    void draftsRootNoTokenReturns401() throws Exception {
        mockMvc.perform(post("/drafts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"t\",\"content\":\"c\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void unlistedPathAnonymousIsDenied() throws Exception {
        mockMvc.perform(get("/not-authorization-configured"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unlistedPathAuthenticatedIsDenied() throws Exception {
        String token = jwtService.generateToken(101L, null);

        mockMvc.perform(get("/not-authorization-configured")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void noTokenReturns401() throws Exception {
        mockMvc.perform(post("/ai/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void invalidTokenReturns401() throws Exception {
        mockMvc.perform(post("/ai/chat")
                        .header("Authorization", "Bearer not-a-valid-jwt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void validTokenCanAccessAiEndpoint() throws Exception {
        when(aiService.chat(eq(CONVERSATION_ID), eq(REQUEST_ID), eq("hello"))).thenReturn("ok");
        String token = jwtService.generateToken(101L, null);

        mockMvc.perform(post("/ai/chat")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.reply").value("ok"));
    }
}
