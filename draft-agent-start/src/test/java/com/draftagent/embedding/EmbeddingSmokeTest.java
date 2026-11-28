package com.draftagent.embedding;

import com.draftagent.DraftAgentApplication;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实 SiliconFlow Embedding Smoke Test。
 *
 * <p>仅当环境变量 {@code EMBEDDING_API_KEY} 非空时运行；默认跳过，不在 CI 默认执行。
 * 不打印 Key，不写 MySQL / Qdrant。</p>
 */
@EnabledIfEnvironmentVariable(named = "EMBEDDING_API_KEY", matches = ".+")
@SpringBootTest(classes = DraftAgentApplication.class)
class EmbeddingSmokeTest {

    @Autowired
    private ObjectProvider<EmbeddingModel> embeddingModelProvider;

    @Test
    void realEmbeddingHasConfiguredDimensions() {
        EmbeddingModel embeddingModel = this.embeddingModelProvider.getIfAvailable();
        Assumptions.assumeTrue(embeddingModel != null, "未创建 EmbeddingModel，跳过真实调用");

        float[] vector = embeddingModel.embed("DraftAgent 真实向量维度冒烟测试：中文文本。");

        assertThat(vector).isNotNull();
        assertThat(vector.length).isEqualTo(1024);
    }
}
