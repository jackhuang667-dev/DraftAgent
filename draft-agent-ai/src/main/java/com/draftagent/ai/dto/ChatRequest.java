package com.draftagent.ai.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * AI 对话请求。
 */
public class ChatRequest {

    @NotBlank(message = "消息内容不能为空")
    private String message;

    public ChatRequest() {
    }

    public ChatRequest(String message) {
        this.message = message;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
