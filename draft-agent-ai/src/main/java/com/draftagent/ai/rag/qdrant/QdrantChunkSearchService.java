package com.draftagent.ai.rag.qdrant;

import com.draftagent.business.document.DocumentSearchException;
import com.draftagent.business.document.DocumentSearchResult;
import com.draftagent.business.document.DocumentSearchScope;
import com.google.common.util.concurrent.ListenableFuture;
import io.qdrant.client.ConditionFactory;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.WithPayloadSelectorFactory;
import io.qdrant.client.grpc.JsonWithInt.Value;
import io.qdrant.client.grpc.Points.Filter;
import io.qdrant.client.grpc.Points.ScoredPoint;
import io.qdrant.client.grpc.Points.SearchPoints;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Qdrant 向量相似度检索（Cosine）。
 *
 * <p>始终按 {@link DocumentSearchScope} 构造过滤条件，禁止无过滤检索：
 * <ul>
 *   <li>企业范围（tenantId 非空）：{@code tenant_id == tenantId}；</li>
 *   <li>个人范围（tenantId 为空）：{@code tenant_id IS NULL AND user_id == userId}。</li>
 * </ul>
 * 返回 Point ID、Payload 元数据与 Qdrant 原始 score，不返回 Chunk 正文。</p>
 */
@Service
public class QdrantChunkSearchService {

    private static final String FIELD_DOCUMENT_ID = "document_id";

    private static final String FIELD_DOCUMENT_PK = "document_pk";

    private static final String FIELD_CHUNK_ID = "chunk_id";

    private static final String FIELD_CHUNK_INDEX = "chunk_index";

    private static final String FIELD_TENANT_ID = "tenant_id";

    private static final String FIELD_USER_ID = "user_id";

    private final QdrantClient qdrantClient;

    private final RagQdrantProperties properties;

    public QdrantChunkSearchService(QdrantClient qdrantClient, RagQdrantProperties properties) {
        this.qdrantClient = qdrantClient;
        this.properties = properties;
    }

    public List<DocumentSearchResult> search(float[] queryVector, DocumentSearchScope scope, int topK) {
        if (scope == null) {
            throw new DocumentSearchException("检索范围不能为空，拒绝无过滤检索");
        }
        if (queryVector == null || queryVector.length == 0) {
            throw new DocumentSearchException("查询向量不能为空");
        }
        if (queryVector.length != this.properties.getDimensions()) {
            throw new DocumentSearchException(
                    "查询向量维度不匹配：期望 " + this.properties.getDimensions() + " 维，实际 " + queryVector.length + " 维");
        }
        if (topK < 1) {
            throw new DocumentSearchException("topK 必须大于 0");
        }

        SearchPoints request = SearchPoints.newBuilder()
                .setCollectionName(this.properties.getCollection())
                .addAllVector(toBoxed(queryVector))
                .setFilter(buildFilter(scope))
                .setLimit(topK)
                .setWithPayload(WithPayloadSelectorFactory.enable(true))
                .build();

        List<ScoredPoint> points = await(this.qdrantClient.searchAsync(request, timeout()));
        if (points == null || points.isEmpty()) {
            return List.of();
        }
        List<DocumentSearchResult> results = new ArrayList<>(points.size());
        for (ScoredPoint point : points) {
            results.add(toResult(point));
        }
        return results;
    }

    /**
     * 按服务端检索范围构造 Qdrant Filter；任何分支都必须带过滤条件。
     */
    public Filter buildFilter(DocumentSearchScope scope) {
        if (scope == null) {
            throw new DocumentSearchException("检索范围不能为空，拒绝无过滤检索");
        }
        if (scope.getTenantId() != null) {
            return Filter.newBuilder()
                    .addMust(ConditionFactory.match(FIELD_TENANT_ID, scope.getTenantId()))
                    .build();
        }
        return Filter.newBuilder()
                .addMust(ConditionFactory.isNull(FIELD_TENANT_ID))
                .addMust(ConditionFactory.match(FIELD_USER_ID, scope.getUserId()))
                .build();
    }

    private DocumentSearchResult toResult(ScoredPoint point) {
        if (point == null || !point.hasId() || !point.getId().hasNum()) {
            throw new DocumentSearchException("Qdrant 检索结果缺少数字 Point ID");
        }
        long pointId = point.getId().getNum();
        Map<String, Value> payload = point.getPayloadMap();
        String documentId = requireDocumentId(payload, pointId);
        long documentPk = requirePositiveLong(payload, FIELD_DOCUMENT_PK, pointId);
        long chunkId = requirePositiveLong(payload, FIELD_CHUNK_ID, pointId);
        long chunkIndex = requireNonNegativeLong(payload, FIELD_CHUNK_INDEX, pointId);
        if (chunkIndex > Integer.MAX_VALUE) {
            throw new DocumentSearchException(
                    "Qdrant 检索结果 Payload 非法: pointId=" + pointId + ", field=" + FIELD_CHUNK_INDEX);
        }
        return new DocumentSearchResult(
                pointId, documentId, documentPk, chunkId, (int) chunkIndex, point.getScore());
    }

    private String requireDocumentId(Map<String, Value> payload, long pointId) {
        Value value = payload.get(FIELD_DOCUMENT_ID);
        if (value == null || !value.hasStringValue() || !StringUtils.hasText(value.getStringValue())) {
            throw invalidPayload(pointId, FIELD_DOCUMENT_ID);
        }
        String documentId = value.getStringValue();
        try {
            UUID.fromString(documentId);
        }
        catch (IllegalArgumentException e) {
            throw invalidPayload(pointId, FIELD_DOCUMENT_ID);
        }
        return documentId;
    }

    private long requirePositiveLong(Map<String, Value> payload, String field, long pointId) {
        long value = requireNonNegativeLong(payload, field, pointId);
        if (value <= 0) {
            throw invalidPayload(pointId, field);
        }
        return value;
    }

    private long requireNonNegativeLong(Map<String, Value> payload, String field, long pointId) {
        Value value = payload.get(field);
        if (value == null || !value.hasIntegerValue()) {
            throw invalidPayload(pointId, field);
        }
        long number = value.getIntegerValue();
        if (number < 0) {
            throw invalidPayload(pointId, field);
        }
        return number;
    }

    private DocumentSearchException invalidPayload(long pointId, String field) {
        return new DocumentSearchException(
                "Qdrant 检索结果 Payload 缺失或类型非法: pointId=" + pointId + ", field=" + field);
    }

    private List<Float> toBoxed(float[] vector) {
        List<Float> values = new ArrayList<>(vector.length);
        for (float value : vector) {
            values.add(value);
        }
        return values;
    }

    private Duration timeout() {
        return Duration.ofMillis(this.properties.getTimeoutMs());
    }

    private <T> T await(ListenableFuture<T> future) {
        try {
            return future.get(this.properties.getTimeoutMs(), TimeUnit.MILLISECONDS);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DocumentSearchException("Qdrant 检索被中断");
        }
        catch (TimeoutException e) {
            throw new DocumentSearchException("Qdrant 检索超时（" + this.properties.getTimeoutMs() + "ms）");
        }
        catch (ExecutionException e) {
            Throwable cause = e.getCause();
            String detail = cause != null && cause.getMessage() != null
                    ? cause.getMessage() : (cause != null ? cause.getClass().getSimpleName() : "unknown");
            if (detail.length() > 300) {
                detail = detail.substring(0, 300);
            }
            throw new DocumentSearchException("Qdrant 检索失败: " + detail);
        }
    }
}
