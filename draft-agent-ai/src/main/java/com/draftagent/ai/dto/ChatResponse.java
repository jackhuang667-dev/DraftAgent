package com.draftagent.ai.dto;

/**
 * AI 对话响应。
 */
public class ChatResponse {

    private String reply;

    public ChatResponse() {
    }

    public ChatResponse(String reply) {
        this.reply = reply;
    }

    public String getReply() {
        return reply;
    }

    public void setReply(String reply) {
        this.reply = reply;
    }
}
