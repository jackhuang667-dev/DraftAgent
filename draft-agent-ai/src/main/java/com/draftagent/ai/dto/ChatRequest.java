package com.draftagent.ai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * AI 对话请求。
 *
 * <p>conversationId 与 requestId 均为标准 UUID（36 字符）：conversationId 标识会话，
 * requestId 由客户端在首次请求前生成、HTTP 超时重试时复用，用于工具执行幂等。
 * 二者都不是身份凭证，后续接入认证后应由服务端绑定用户归属。</p>
 */
public class ChatRequest {

    private static final String UUID_REGEX =
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$";

    @NotBlank(message = "会话ID不能为空")
    @Pattern(regexp = UUID_REGEX, message = "会话ID必须为标准的 UUID（36 字符）格式")
    private String conversationId;

    @NotBlank(message = "请求ID不能为空")
    @Pattern(regexp = UUID_REGEX, message = "请求ID必须为标准的 UUID（36 字符）格式")
    private String requestId;

    @NotBlank(message = "消息内容不能为空")
    private String message;

    public ChatRequest() {
    }

    public ChatRequest(String conversationId, String requestId, String message) {
        this.conversationId = conversationId;
        this.requestId = requestId;
        this.message = message;
    }

    public String getConversationId() {
        return conversationId;
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
