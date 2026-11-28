package com.draftagent.ai.rag.answer;

/**
 * RAG 模型调用边界（仅发送 System / User 文本并返回文本）。
 *
 * <p>抽离该小接口是为了便于在不调用真实 DeepSeek 的情况下测试；生产实现复用现有 ChatClient，
 * 不附加 Tools、ToolContext、ChatMemory 或额外 Advisor。</p>
 */
public interface RagChatModelInvoker {

    String invoke(String systemPrompt, String userPrompt);
}
