package com.draftagent.exception;

import com.draftagent.business.controller.DraftController;
import com.draftagent.business.service.DraftService;
import com.draftagent.common.core.exception.GlobalExceptionHandler;
import com.draftagent.common.core.exception.ServiceException;
import com.draftagent.common.security.CurrentUser;
import com.draftagent.common.security.CurrentUserProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GlobalExceptionHandler 的 HTTP 状态映射测试。
 *
 * <p>使用 MockMvc standaloneSetup，不加载 Spring 容器、不连接数据库、不调用真实 AI。</p>
 */
class GlobalExceptionHandlerMockMvcTest {

    private MockMvc mockMvc;

    private DraftService draftService;

    @RestController
    static class ParamController {

        @GetMapping("/test/require-param")
        public String requireParam(@RequestParam("name") String name) {
            return name;
        }
    }

    @BeforeEach
    void setUp() {
        draftService = Mockito.mock(DraftService.class);
        CurrentUserProvider currentUserProvider = Mockito.mock(CurrentUserProvider.class);
        Mockito.when(currentUserProvider.getCurrentUser()).thenReturn(new CurrentUser(1L, null));
        DraftController draftController = new DraftController(draftService, currentUserProvider);

        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        mockMvc = MockMvcBuilders
                .standaloneSetup(draftController, new ParamController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .build();
    }

    @Test
    void typeMismatch_returns400() throws Exception {
        mockMvc.perform(get("/drafts/abc/status"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void missingRequestParam_returns400() throws Exception {
        mockMvc.perform(get("/test/require-param"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void unsupportedMediaType_returns415() throws Exception {
        mockMvc.perform(post("/drafts")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("hello"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value(415));
    }

    @Test
    void invalidJson_returns400() throws Exception {
        mockMvc.perform(post("/drafts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{invalid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void validationError_returns400() throws Exception {
        mockMvc.perform(post("/drafts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"\",\"content\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void baseException_preservesStatusAndCode() throws Exception {
        Mockito.when(draftService.createDraft(anyString(), anyString(), anyLong(), any()))
                .thenThrow(new ServiceException(503, "数据库连接失败，暂无法创建草案"));

        mockMvc.perform(post("/drafts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"t\",\"content\":\"c\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(503));
    }
}
