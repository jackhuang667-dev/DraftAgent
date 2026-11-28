package com.draftagent.rag;

import com.draftagent.ai.rag.answer.RagCitationValidator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 模型回答引用编号校验：有效保留、无效移除、普通数字不误判。
 */
class RagCitationValidatorTest {

    private final RagCitationValidator validator = new RagCitationValidator();

    @Test
    void keepsSingleValidCitation() {
        assertThat(this.validator.sanitize("规则见 [1]。", 1)).isEqualTo("规则见 [1]。");
    }

    @Test
    void keepsMultipleValidCitations() {
        assertThat(this.validator.sanitize("A[1]，B[2]。", 2)).isEqualTo("A[1]，B[2]。");
    }

    @Test
    void keepsDuplicateValidCitations() {
        assertThat(this.validator.sanitize("重复[1][1]。", 1)).isEqualTo("重复[1][1]。");
    }

    @Test
    void removesInvalidCitation() {
        String sanitized = this.validator.sanitize("根据资料[99]，无法确认。", 2);

        assertThat(sanitized).isEqualTo("根据资料，无法确认。");
        assertThat(sanitized).doesNotContain("[99]");
        assertThat(sanitized).doesNotContain("[1]");
    }

    @Test
    void keepsAnswerWithoutCitationsUnchanged() {
        String answer = "当前知识库无法确认该问题。";
        assertThat(this.validator.sanitize(answer, 3)).isEqualTo(answer);
    }

    @Test
    void keepsNonContiguousButValidCitations() {
        assertThat(this.validator.sanitize("[1]...[3]", 3)).isEqualTo("[1]...[3]");
    }

    @Test
    void doesNotTreatNormalNumbersAsCitations() {
        String answer = "2024年价格[2024]为100元，编号[123]不是引用。";
        assertThat(this.validator.sanitize(answer, 2)).isEqualTo(answer);
    }

    @Test
    void doesNotRemapInvalidCitationToValidOne() {
        assertThat(this.validator.sanitize("[99]", 1)).isEmpty();
    }

    @Test
    void removesOnlyInvalidCitationInMixedAnswer() {
        assertThat(this.validator.sanitize("有效[1]，无效[99]。", 1)).isEqualTo("有效[1]，无效。");
    }

    @Test
    void removesZeroCitation() {
        assertThat(this.validator.sanitize("无效[0]", 2)).isEqualTo("无效");
    }

    @Test
    void nullOrEmptyIsHandled() {
        assertThat(this.validator.sanitize(null, 1)).isNull();
        assertThat(this.validator.sanitize("", 1)).isEmpty();
    }
}
