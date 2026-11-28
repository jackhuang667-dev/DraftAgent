package com.draftagent.common.security;

/**
 * 当前用户上下文提供者。
 *
 * <p>业务/AI 代码统一通过该接口获取当前用户，避免到处解析 JWT。
 * 具体实现由安全模块基于 Spring SecurityContext 提供；未认证时应抛出 401 业务异常。</p>
 */
public interface CurrentUserProvider {

    CurrentUser getCurrentUser();
}
