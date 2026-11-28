package com.draftagent.common.security;

import java.util.Objects;

/**
 * 当前认证用户上下文（由 JWT 经过验证后写入 Spring Security principal）。
 *
 * <p>{@code userId} 必填；{@code tenantId} 可为空，保留多租户扩展能力。
 * 该对象只应来源于经过验证的 SecurityContext，禁止由客户端请求参数直接构造。</p>
 */
public final class CurrentUser {

    private final Long userId;

    private final Long tenantId;

    public CurrentUser(Long userId, Long tenantId) {
        this.userId = userId;
        this.tenantId = tenantId;
    }

    public Long getUserId() {
        return userId;
    }

    public Long getTenantId() {
        return tenantId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CurrentUser)) {
            return false;
        }
        CurrentUser that = (CurrentUser) o;
        return Objects.equals(userId, that.userId) && Objects.equals(tenantId, that.tenantId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, tenantId);
    }

    @Override
    public String toString() {
        return "CurrentUser{userId=" + userId + ", tenantId=" + tenantId + '}';
    }
}
