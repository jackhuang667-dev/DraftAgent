package com.draftagent.business.dto;

/**
 * 一轮对话开始后的执行上下文（仅在 T1 提交后由上层用于后续 AI 调用与 T2/T3）。
 */
public class TurnContext {

    private final Long conversationPk;
    private final String conversationId;
    private final Long userMessageId;
    private final Long assistantMessageId;
    private final Long userMessageSequence;
    private final Long assistantMessageSequence;
    private final String turnId;

    public TurnContext(Long conversationPk, String conversationId, Long userMessageId, Long assistantMessageId,
                       Long userMessageSequence, Long assistantMessageSequence, String turnId) {
        this.conversationPk = conversationPk;
        this.conversationId = conversationId;
        this.userMessageId = userMessageId;
        this.assistantMessageId = assistantMessageId;
        this.userMessageSequence = userMessageSequence;
        this.assistantMessageSequence = assistantMessageSequence;
        this.turnId = turnId;
    }

    public Long getConversationPk() {
        return conversationPk;
    }

    public String getConversationId() {
        return conversationId;
    }

    public Long getUserMessageId() {
        return userMessageId;
    }

    public Long getAssistantMessageId() {
        return assistantMessageId;
    }

    public Long getUserMessageSequence() {
        return userMessageSequence;
    }

    public Long getAssistantMessageSequence() {
        return assistantMessageSequence;
    }

    public String getTurnId() {
        return turnId;
    }
}
