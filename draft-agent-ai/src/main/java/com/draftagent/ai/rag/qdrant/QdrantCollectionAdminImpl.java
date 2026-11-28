package com.draftagent.ai.rag.qdrant;

import com.google.common.util.concurrent.ListenableFuture;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Collections.CollectionInfo;
import io.qdrant.client.grpc.Collections.CollectionOperationResponse;
import io.qdrant.client.grpc.Collections.Distance;
import io.qdrant.client.grpc.Collections.PayloadSchemaType;
import io.qdrant.client.grpc.Collections.VectorParams;
import io.qdrant.client.grpc.Collections.VectorsConfig;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 基于官方 Qdrant Java Client（gRPC）的 Collection 管理实现。
 */
@Component
public class QdrantCollectionAdminImpl implements QdrantCollectionAdmin {

    private final QdrantClient qdrantClient;

    private final int timeoutMs;

    public QdrantCollectionAdminImpl(QdrantClient qdrantClient, RagQdrantProperties properties) {
        this.qdrantClient = qdrantClient;
        this.timeoutMs = properties.getTimeoutMs();
    }

    @Override
    public boolean collectionExists(String collection) {
        return await(this.qdrantClient.collectionExistsAsync(collection), "查询 Collection 是否存在");
    }

    @Override
    public QdrantCollectionState getCollectionState(String collection) {
        CollectionInfo collectionInfo = await(
                this.qdrantClient.getCollectionInfoAsync(collection), "读取 Collection 配置");
        VectorsConfig vectorsConfig = collectionInfo.getConfig().getParams().getVectorsConfig();
        if (!vectorsConfig.hasParams()) {
            throw new QdrantOperationException(
                    "Collection [" + collection + "] 使用命名向量/多向量配置，当前初始化器仅支持单向量配置");
        }
        VectorParams vectorParams = vectorsConfig.getParams();
        return new QdrantCollectionState(vectorParams.getSize(), vectorParams.getDistance().name());
    }

    @Override
    public void createCollection(String collection, int dimensions, String distance) {
        VectorParams vectorParams = VectorParams.newBuilder()
                .setSize(dimensions)
                .setDistance(toDistance(distance))
                .build();
        CollectionOperationResponse response = await(
                this.qdrantClient.createCollectionAsync(collection, vectorParams), "创建 Collection");
        if (!response.getResult()) {
            throw new QdrantOperationException("创建 Collection [" + collection + "] 未返回成功");
        }
    }

    @Override
    public void createIntegerPayloadIndex(String collection, String fieldName) {
        createPayloadIndex(collection, fieldName, PayloadSchemaType.Integer);
    }

    @Override
    public void createUuidPayloadIndex(String collection, String fieldName) {
        createPayloadIndex(collection, fieldName, PayloadSchemaType.Uuid);
    }

    private void createPayloadIndex(String collection, String fieldName, PayloadSchemaType schemaType) {
        // Qdrant 创建同类型 payload index 是幂等操作，重复调用不会破坏数据
        await(this.qdrantClient.createPayloadIndexAsync(collection, fieldName, schemaType,
                        null, true, null, Duration.ofMillis(this.timeoutMs)),
                "创建 Payload Index [" + fieldName + "]");
    }

    private Distance toDistance(String distance) {
        String normalized = distance == null ? "" : distance.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "cosine" -> Distance.Cosine;
            case "euclid" -> Distance.Euclid;
            case "dot" -> Distance.Dot;
            case "manhattan" -> Distance.Manhattan;
            default -> throw new QdrantOperationException("不支持的 Qdrant distance: " + distance);
        };
    }

    private <T> T await(ListenableFuture<T> future, String action) {
        try {
            return future.get(this.timeoutMs, TimeUnit.MILLISECONDS);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new QdrantOperationException(action + "被中断");
        }
        catch (TimeoutException e) {
            throw new QdrantOperationException(action + "超时（" + this.timeoutMs + "ms）");
        }
        catch (ExecutionException e) {
            Throwable cause = e.getCause();
            String detail = cause != null && cause.getMessage() != null
                    ? cause.getMessage() : (cause != null ? cause.getClass().getSimpleName() : "unknown");
            if (detail.length() > 300) {
                detail = detail.substring(0, 300);
            }
            throw new QdrantOperationException(action + "失败: " + detail);
        }
    }
}
