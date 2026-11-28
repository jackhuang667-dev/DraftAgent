package com.draftagent.config;

import com.draftagent.security.jwt.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Spring Profile 默认配置与 JWT 密钥联动验证。
 *
 * <p>使用最小 {@link SpringApplication}（不加载自动配置、不连接数据库、不调用模型），
 * 真实经过 Spring Boot 的 ConfigData 配置加载与 Profile 解析。</p>
 */
class ProfileConfigurationTest {

    @Configuration
    static class JwtProbeConfig {

        @Bean
        JwtService jwtService(@Value("${jwt.secret:}") String secret, Environment environment) {
            return new JwtService(secret, environment);
        }
    }

    @Test
    void applicationYmlDeclaresDefaultDevWithoutActiveProfile() throws Exception {
        Resource resource = new DefaultResourceLoader().getResource("classpath:application.yml");
        List<PropertySource<?>> sources = new YamlPropertySourceLoader().load("application", resource);

        assertThat(property(sources, "spring.profiles.default")).isEqualTo("dev");
        assertThat(property(sources, "spring.profiles.active")).isNull();
    }

    @Test
    void noActiveProfileFallsBackToDevWithRandomKey() {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(JwtProbeConfig.class)
                .web(WebApplicationType.NONE)
                .run()) {
            assertThat(context.getEnvironment().getActiveProfiles()).isEmpty();
            assertThat(context.getEnvironment().getDefaultProfiles()).containsExactly("dev");
            assertThat(context.getBean(JwtService.class)).isNotNull();
        }
    }

    @Test
    void prodProfileWithoutSecretFailsFast() {
        SpringApplication application = new SpringApplicationBuilder(JwtProbeConfig.class)
                .web(WebApplicationType.NONE)
                .build();

        assertThatThrownBy(() -> application.run("--spring.profiles.active=prod"))
                .hasRootCauseInstanceOf(IllegalStateException.class);
    }

    private static Object property(List<PropertySource<?>> sources, String key) {
        for (PropertySource<?> source : sources) {
            Object value = source.getProperty(key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }
}
