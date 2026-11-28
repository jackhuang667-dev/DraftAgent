package com.draftagent.ai.rag.retrieval;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * RAG 向量检索配置。
 *
 * <p>{@code top-k} 为默认返回条数（5），{@code max-top-k} 为允许的硬上限（20）。
 * {@code max-context-chars} 为最终进入 Prompt 的参考资料字符预算（默认 12000），
 * 仅统计格式化后的 Chunk 引用块（含必要来源元数据）及其分隔符，不包含 System/问题。
 * 不设置相似度阈值：在没有实验依据前，避免把真实相关结果过滤掉。</p>
 */
@Component
@Validated
@ConfigurationProperties(prefix = "rag.retrieval")
public class RagRetrievalProperties {

    @Min(value = 1, message = "rag.retrieval.top-k 必须大于 0")
    private int topK = 5;

    @Min(value = 1, message = "rag.retrieval.max-top-k 必须大于 0")
    private int maxTopK = 20;

    @Min(value = 1, message = "rag.retrieval.max-context-chars 必须大于 0")
    private int maxContextChars = 12000;

    @AssertTrue(message = "rag.retrieval.top-k 不能大于 max-top-k")
    public boolean isTopKWithinMaxTopK() {
        return topK <= maxTopK;
    }

    public int getTopK() {
        return topK;
    }

    public void setTopK(int topK) {
        this.topK = topK;
    }

    public int getMaxTopK() {
        return maxTopK;
    }

    public void setMaxTopK(int maxTopK) {
        this.maxTopK = maxTopK;
    }

    public int getMaxContextChars() {
        return maxContextChars;
    }

    public void setMaxContextChars(int maxContextChars) {
        this.maxContextChars = maxContextChars;
    }
}
