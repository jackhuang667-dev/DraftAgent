package com.draftagent.security;

import com.draftagent.common.security.CurrentUser;
import com.draftagent.security.jwt.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link JwtService} 密钥初始化与 Profile 行为测试。
 *
 * <p>不加载 Spring 容器、不连接数据库、不调用真实模型；仅用 {@link MockEnvironment} 模拟 Profile。</p>
 */
class JwtServiceTest {

    private static final String VALID_SECRET = "0123456789abcdef0123456789abcdef";

    private static MockEnvironment envWithActiveProfiles(String... profiles) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profiles);
        return environment;
    }

    @Test
    void devWithoutSecretGeneratesRandomKey() {
        JwtService jwtService = new JwtService("", envWithActiveProfiles("dev"));

        String token = jwtService.generateToken(7L, 9L);

        CurrentUser currentUser = jwtService.parse(token);
        assertThat(currentUser.getUserId()).isEqualTo(7L);
        assertThat(currentUser.getTenantId()).isEqualTo(9L);
    }

    @Test
    void nonDevWithoutSecretFailsFast() {
        assertThatThrownBy(() -> new JwtService("", envWithActiveProfiles("prod")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void noActiveProfileWithoutSecretFailsFast() {
        assertThatThrownBy(() -> new JwtService("", envWithActiveProfiles()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void shortSecretFails() {
        assertThatThrownBy(() -> new JwtService("too-short-secret", envWithActiveProfiles("dev")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void validSecretSucceedsInNonDev() {
        JwtService jwtService = new JwtService(VALID_SECRET, envWithActiveProfiles("prod"));

        String token = jwtService.generateToken(3L, null);

        assertThat(jwtService.parse(token).getUserId()).isEqualTo(3L);
    }
}
