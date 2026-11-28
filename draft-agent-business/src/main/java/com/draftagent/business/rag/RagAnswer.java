package com.draftagent.business.rag;

import java.util.List;

/**
 * RAG 问答结果。
 *
 * <p>{@code sources} 表示本次提供给模型的、经过 MySQL 权限复核的参考资料，
 * 而不是模型明确引用的全部内容；模型未使用的来源仍可保留。</p>
 */
public final class RagAnswer {

    private final String answer;

    private final List<RagSource> sources;

    private final boolean hasContext;

    public RagAnswer(String answer, List<RagSource> sources, boolean hasContext) {
        this.answer = answer;
        this.sources = sources == null ? List.of() : List.copyOf(sources);
        this.hasContext = hasContext;
    }

    public String getAnswer() {
        return answer;
    }

    public List<RagSource> getSources() {
        return sources;
    }

    public boolean isHasContext() {
        return hasContext;
    }
}
