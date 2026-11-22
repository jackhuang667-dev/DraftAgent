package com.draftagent.ai.controller;

import com.draftagent.ai.dto.ChatRequest;
import com.draftagent.ai.dto.ChatResponse;
import com.draftagent.ai.service.AiService;
import com.draftagent.common.core.domain.R;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 对话接口。
 */
@RestController
@RequestMapping("/ai")
public class AiController {

    private final AiService aiService;

    public AiController(AiService aiService) {
        this.aiService = aiService;
    }

    @PostMapping("/chat")
    public R<ChatResponse> chat(@Valid @RequestBody ChatRequest request) {
        String reply = aiService.chat(request.getMessage());
        return R.ok(new ChatResponse(reply));
    }
}
