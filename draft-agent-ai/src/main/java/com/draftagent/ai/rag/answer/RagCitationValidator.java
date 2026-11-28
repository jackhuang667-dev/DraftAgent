package com.draftagent.ai.rag.answer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 模型回答中的引用编号校验。
 *
 * <p>只把 1~2 位方括号数字视为“引用候选”（例如 [1]、[12]），并与本次实际进入 Prompt 的
 * 引用集合（1..N）比对：有效编号原样保留，无效编号直接移除，绝不做编号重映射。3 位及以上
 * 的方括号数字（例如 [2024]）按普通文本保留，避免把年份/编号误判为引用。后端 sources 不依赖
 * 模型输出，始终来自真实 Chunk。</p>
 */
@Component
public class RagCitationValidator {

    private static final Logger log = LoggerFactory.getLogger(RagCitationValidator.class);

    private static final Pattern CITATION = Pattern.compile("\\[(\\d{1,2})]");

    public String sanitize(String answer, int validReferenceCount) {
        if (answer == null || answer.isEmpty()) {
            return answer;
        }
        Matcher matcher = CITATION.matcher(answer);
        StringBuilder sanitized = new StringBuilder(answer.length());
        List<Integer> invalidReferenceIds = new ArrayList<>();
        while (matcher.find()) {
            int referenceId = Integer.parseInt(matcher.group(1));
            boolean valid = referenceId >= 1 && referenceId <= validReferenceCount;
            String replacement = valid ? matcher.group() : "";
            if (!valid) {
                invalidReferenceIds.add(referenceId);
            }
            matcher.appendReplacement(sanitized, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sanitized);
        if (!invalidReferenceIds.isEmpty()) {
            log.warn("模型回答包含无效引用编号，已移除: invalidRefs={}, validMax={}",
                    invalidReferenceIds, validReferenceCount);
        }
        return sanitized.toString();
    }
}
