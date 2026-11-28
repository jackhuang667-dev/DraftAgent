package com.draftagent.embedding;

import com.draftagent.DraftAgentApplication;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 配置伪 Key 时创建独立 EmbeddingModel（不发起任何网络请求）。
 */
@SpringBootTest(classes = DraftAgentApplication.class,
        properties = {
                "rag.embedding.api-key=test-embedding-key",
                "rag.embedding.base-url=https://api.siliconflow.cn"
        })
class EmbeddingConfigWithKeyTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void embeddingModelBeanIsCreated() {
        EmbeddingModel embeddingModel = this.applicationContext.getBean(EmbeddingModel.class);
        assertThat(embeddingModel).isInstanceOf(OpenAiEmbeddingModel.class);
        assertThat(this.applicationContext.getBeanNamesForType(EmbeddingModel.class))
                .containsExactly("embeddingModel");
    }
}
