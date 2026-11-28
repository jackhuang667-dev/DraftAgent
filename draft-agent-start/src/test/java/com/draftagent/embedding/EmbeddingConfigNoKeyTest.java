package com.draftagent.embedding;

import com.draftagent.DraftAgentApplication;
import com.draftagent.ai.rag.embedding.RagEmbeddingProperties;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 无 EMBEDDING_API_KEY 时的行为与默认配置。
 */
@SpringBootTest(classes = DraftAgentApplication.class,
        properties = { "rag.embedding.api-key=" })
class EmbeddingConfigNoKeyTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private Environment environment;

    @Autowired
    private RagEmbeddingProperties properties;

    @Test
    void contextStartsWithoutEmbeddingApiKey() {
        assertThat(this.applicationContext).isNotNull();
    }

    @Test
    void noEmbeddingModelBeanWhenApiKeyEmpty() {
        assertThat(this.applicationContext.getBeanNamesForType(EmbeddingModel.class)).isEmpty();
    }

    @Test
    void embeddingPropertiesHaveExpectedDefaults() {
        assertThat(this.properties.getBaseUrl()).isEqualTo("https://api.siliconflow.cn");
        assertThat(this.properties.getModel()).isEqualTo("BAAI/bge-m3");
        assertThat(this.properties.getDimensions()).isEqualTo(1024);
        assertThat(this.properties.getConnectTimeoutMs()).isEqualTo(5000);
        assertThat(this.properties.getReadTimeoutMs()).isEqualTo(30000);
    }

    @Test
    void deepSeekChatConfigurationIsUnchangedAndIndependent() {
        assertThat(this.environment.getProperty("spring.ai.openai.base-url")).isEqualTo("https://api.deepseek.com");
        assertThat(this.environment.getProperty("spring.ai.openai.chat.options.model")).isEqualTo("deepseek-chat");
        assertThat(this.properties.getBaseUrl())
                .isNotEqualTo(this.environment.getProperty("spring.ai.openai.base-url"));
    }
}
