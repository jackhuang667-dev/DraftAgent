package com.draftagent.ai.rag.qdrant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Qdrant Collection 初始化（幂等）。
 *
 * <ul>
 *   <li>不存在：按配置创建（size / distance）；</li>
 *   <li>已存在且配置一致：跳过创建；</li>
 *   <li>已存在但维度/距离不一致：抛出 {@link QdrantCollectionConfigMismatchException}，
 *       <b>不删除、不重建、不覆盖</b>；</li>
 *   <li>随后幂等创建 payload index：tenant_id(integer)、user_id(integer)、document_id(uuid)。</li>
 * </ul>
 *
 * <p>本组件只初始化 Collection 与索引，不写入任何向量、不修改 MySQL。</p>
 */
@Component
public class QdrantCollectionInitializer {

    private static final Logger log = LoggerFactory.getLogger(QdrantCollectionInitializer.class);

    private static final String FIELD_TENANT_ID = "tenant_id";

    private static final String FIELD_USER_ID = "user_id";

    private static final String FIELD_DOCUMENT_ID = "document_id";

    private final QdrantCollectionAdmin collectionAdmin;

    private final RagQdrantProperties properties;

    public QdrantCollectionInitializer(QdrantCollectionAdmin collectionAdmin, RagQdrantProperties properties) {
        this.collectionAdmin = collectionAdmin;
        this.properties = properties;
    }

    public void initialize() {
        String collection = this.properties.getCollection();
        int expectedDimensions = this.properties.getDimensions();
        String expectedDistance = normalize(this.properties.getDistance());

        if (!this.collectionAdmin.collectionExists(collection)) {
            this.collectionAdmin.createCollection(collection, expectedDimensions, this.properties.getDistance());
            log.info("Qdrant Collection 不存在，已创建: {} (size={}, distance={})",
                    collection, expectedDimensions, this.properties.getDistance());
        }
        else {
            QdrantCollectionState state = this.collectionAdmin.getCollectionState(collection);
            if (state.getVectorSize() != expectedDimensions) {
                throw new QdrantCollectionConfigMismatchException(
                        "Qdrant Collection [" + collection + "] 向量维度不匹配：期望 " + expectedDimensions
                                + "，实际 " + state.getVectorSize() + "；不会自动删除或重建");
            }
            if (!expectedDistance.equals(normalize(state.getDistance()))) {
                throw new QdrantCollectionConfigMismatchException(
                        "Qdrant Collection [" + collection + "] 距离不匹配：期望 " + this.properties.getDistance()
                                + "，实际 " + state.getDistance() + "；不会自动删除或重建");
            }
            log.info("Qdrant Collection 已存在且配置匹配，跳过创建: {}", collection);
        }

        this.collectionAdmin.createIntegerPayloadIndex(collection, FIELD_TENANT_ID);
        this.collectionAdmin.createIntegerPayloadIndex(collection, FIELD_USER_ID);
        this.collectionAdmin.createUuidPayloadIndex(collection, FIELD_DOCUMENT_ID);
        log.info("Qdrant Collection Payload Index 初始化完成: {}", collection);
    }

    private static String normalize(String distance) {
        return distance == null ? "" : distance.trim().toLowerCase(Locale.ROOT);
    }
}
