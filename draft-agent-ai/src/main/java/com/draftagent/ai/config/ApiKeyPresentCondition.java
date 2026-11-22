package com.draftagent.ai.config;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.util.StringUtils;

/**
 * 仅当配置了非空的 spring.ai.openai.api-key（来源于环境变量 DEEPSEEK_API_KEY）时条件成立。
 * 用于无 Key 时不创建需要 API Key 的 OpenAI ChatModel。
 */
public class ApiKeyPresentCondition implements Condition {

    private static final String API_KEY_PROPERTY = "spring.ai.openai.api-key";

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String apiKey = context.getEnvironment().getProperty(API_KEY_PROPERTY);
        return StringUtils.hasText(apiKey);
    }
}
