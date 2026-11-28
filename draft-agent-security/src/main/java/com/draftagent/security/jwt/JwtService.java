package com.draftagent.security.jwt;

import com.draftagent.common.security.CurrentUser;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Date;

/**
 * 无状态 JWT（HS256）签发与校验。
 *
 * <p>密钥优先从 {@code jwt.secret}（环境变量 {@code JWT_SECRET}）读取；未配置时**仅开发**
 * 使用进程内随机密钥并告警（重启后旧 token 失效）。禁止硬编码密钥。</p>
 */
@Service
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);

    private static final long DEFAULT_TTL_MILLIS = 60L * 60L * 1000L;

    private static final int MIN_KEY_BYTES = 32;

    private final SecretKey key;

    public JwtService(@Value("${jwt.secret:}") String secret, Environment environment) {
        if (StringUtils.hasText(secret)) {
            byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
            if (bytes.length < MIN_KEY_BYTES) {
                throw new IllegalStateException("jwt.secret(JWT_SECRET) 长度不足：HS256 至少需要 " + MIN_KEY_BYTES + " 字节");
            }
            this.key = Keys.hmacShaKeyFor(bytes);
        }
        else if (isDevProfile(environment)) {
            byte[] random = new byte[MIN_KEY_BYTES];
            new SecureRandom().nextBytes(random);
            this.key = Keys.hmacShaKeyFor(random);
            log.warn("未配置 jwt.secret(JWT_SECRET)：dev 环境已使用进程内随机密钥（仅开发用，重启后旧 token 失效）");
        }
        else {
            throw new IllegalStateException(
                    "非 dev 环境必须配置 jwt.secret(JWT_SECRET)，且 UTF-8 长度至少 " + MIN_KEY_BYTES + " 字节");
        }
    }

    /**
     * 判断当前是否 dev 环境。未显式激活任何 Profile 时使用 default profiles，
     * 均不包含 {@code dev} 则视为非 dev（启动失败），避免生产环境意外走开发默认配置。
     */
    private static boolean isDevProfile(Environment environment) {
        String[] profiles = environment.getActiveProfiles();
        if (profiles.length == 0) {
            profiles = environment.getDefaultProfiles();
        }
        return Arrays.asList(profiles).contains("dev");
    }

    /**
     * 签发 token（供测试/受信任签发方使用；不通过 HTTP 暴露无保护的发号接口）。
     */
    public String generateToken(Long userId, Long tenantId) {
        Assert.notNull(userId, "userId cannot be null");
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("tenantId", tenantId)
                .issuedAt(new Date(now))
                .expiration(new Date(now + DEFAULT_TTL_MILLIS))
                .signWith(this.key)
                .compact();
    }

    /**
     * 校验签名与有效期，解析出当前用户；非法/过期 token 抛 {@link JwtException} 或其子类。
     */
    public CurrentUser parse(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(this.key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        String subject = claims.getSubject();
        if (!StringUtils.hasText(subject)) {
            throw new JwtException("缺少 subject 声明");
        }
        Long userId;
        try {
            userId = Long.valueOf(subject);
        }
        catch (NumberFormatException e) {
            throw new JwtException("subject 声明非法");
        }
        Object tenantClaim = claims.get("tenantId");
        Long tenantId = (tenantClaim instanceof Number) ? ((Number) tenantClaim).longValue() : null;
        return new CurrentUser(userId, tenantId);
    }
}
