package com.draftagent.ai.rag.answer;

/**
 * 已组装完成的 RAG Prompt（System / User 两段原始文本）。
 *
 * <p>文本直接作为消息内容传给 ChatClient，不经过 {@code PromptTemplate} 渲染，
 * 因此文档正文或用户问题中的花括号、美元符号、百分号等字符不会被当作模板占位符。</p>
 */
public final class RagPrompt {

    private final String system;

    private final String user;

    public RagPrompt(String system, String user) {
        this.system = system;
        this.user = user;
    }

    public String getSystem() {
        return system;
    }

    public String getUser() {
        return user;
    }
}
