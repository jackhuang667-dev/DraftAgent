package com.draftagent.business.document;

/**
 * 服务端确定的向量检索范围（用户 / 租户隔离边界）。
 *
 * <p>{@code userId} 必填且为正数；{@code tenantId} 可空。该对象只能由服务端根据已认证身份构造，
 * 禁止直接使用客户端传入的 userId / tenantId，避免检索范围被放大。语义与文档归属规则一致：
 * tenantId 非空表示租户共享范围，tenantId 为空表示个人私有范围。</p>
 */
public final class DocumentSearchScope {

    private final Long userId;

    private final Long tenantId;

    public DocumentSearchScope(Long userId, Long tenantId) {
        if (userId == null || userId <= 0) {
            throw new DocumentSearchException("检索范围非法：userId 必须为正数");
        }
        if (tenantId != null && tenantId <= 0) {
            throw new DocumentSearchException("检索范围非法：tenantId 必须为正数或为空");
        }
        this.userId = userId;
        this.tenantId = tenantId;
    }

    public Long getUserId() {
        return userId;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public boolean isTenantScope() {
        return tenantId != null;
    }
}
