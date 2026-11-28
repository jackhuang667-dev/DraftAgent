package com.draftagent.ai.rag.qdrant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 可选的启动初始化策略：仅当 {@code rag.qdrant.initialize-on-startup=true} 时执行。
 *
 * <p>默认关闭，避免在无 Qdrant 环境或测试中强制连接。初始化失败仅记录清晰错误，不阻断应用启动，
 * 便于后续在 Qdrant 可用时重试。</p>
 */
@Component
@ConditionalOnProperty(prefix = "rag.qdrant", name = "initialize-on-startup", havingValue = "true")
public class QdrantCollectionStartupRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(QdrantCollectionStartupRunner.class);

    private final QdrantCollectionInitializer collectionInitializer;

    public QdrantCollectionStartupRunner(QdrantCollectionInitializer collectionInitializer) {
        this.collectionInitializer = collectionInitializer;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            this.collectionInitializer.initialize();
        }
        catch (RuntimeException e) {
            log.error("Qdrant Collection 初始化失败，RAG 暂不可用: {}", e.getMessage());
        }
    }
}
