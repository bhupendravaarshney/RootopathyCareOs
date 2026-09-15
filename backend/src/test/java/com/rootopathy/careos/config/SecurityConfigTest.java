package com.rootopathy.careos.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rootopathy.careos.identity.infrastructure.config.IdentitySecurityProperties;
import java.net.URI;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;

class SecurityConfigTest {
    @Test
    void corsSupportsCheckedConcurrencyHeadersWithoutTenantHeaderAmbiguity() {
        var source = new SecurityConfig().corsConfigurationSource(properties());
        var configuration = source.getCorsConfiguration(
                new MockHttpServletRequest("OPTIONS", "/api/v1/organizations/example/resources"));

        assertThat(configuration).isNotNull();
        assertThat(configuration.getAllowedHeaders())
                .contains("If-Match", "Idempotency-Key", "X-Correlation-Id", "X-XSRF-TOKEN")
                .doesNotContain("X-Organization-Id");
        assertThat(configuration.getExposedHeaders())
                .containsExactly(
                        "X-Correlation-Id", "Retry-After", "ETag", "X-CareOS-Session-Expires-In");
        assertThat(configuration.getAllowCredentials()).isTrue();
    }

    @Test
    void acceptsAProductionConfigurationWithVerifiedTransportAndFreshSecrets() {
        var environment = productionEnvironment();

        assertThatCode(() -> ProductionConfigurationGuard.validate(properties(), environment))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsLocalProfilesDocumentedSecretsAndUnverifiedTransportsInProduction() {
        var environment = productionEnvironment();
        environment.setActiveProfiles("production", "local");
        environment.setProperty("server.servlet.session.cookie.secure", "false");
        environment.setProperty("spring.data.redis.ssl.enabled", "false");
        environment.setProperty("spring.datasource.url", "jdbc:postgresql://localhost/careos");
        environment.setProperty("spring.datasource.password", "careos-app-local-only");
        environment.setProperty("spring.flyway.password", "careos-migrator-local-only");
        environment.setProperty("careos.storage.s3.allow-http", "true");
        environment.setProperty("careos.storage.s3.create-bucket-if-missing", "true");

        assertThatThrownBy(() -> ProductionConfigurationGuard.validate(localProperties(), environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("local and test profiles")
                .hasMessageContaining("secure session cookies")
                .hasMessageContaining("Redis TLS")
                .hasMessageContaining("sslmode=verify-full")
                .hasMessageContaining("documented local or test material")
                .hasMessageContaining("must be disabled")
                .hasMessageContaining("must not create");
    }

    @Test
    void rejectsNonHttpsBrowserAndTelemetryDestinationsInProduction() {
        var environment = productionEnvironment();
        environment.setProperty("management.tracing.export.otlp.enabled", "true");
        environment.setProperty(
                "management.opentelemetry.tracing.export.otlp.endpoint",
                "http://collector.internal:4318/v1/traces");

        assertThatThrownBy(() -> ProductionConfigurationGuard.validate(httpProperties(), environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("allowed browser origin must be a canonical HTTPS origin")
                .hasMessageContaining("application base URL must be a canonical HTTPS origin")
                .hasMessageContaining("OTLP tracing endpoint must use an HTTPS URL");
    }

    private static IdentitySecurityProperties properties() {
        return new IdentitySecurityProperties(
                List.of("https://careos.example"),
                URI.create("https://careos.example"),
                "security@careos.example",
                "test-token-pepper-that-is-at-least-32-characters",
                productionMfaKey(),
                Duration.ofHours(12),
                Duration.ofMinutes(10),
                Duration.ofMinutes(15),
                5,
                Duration.ofMinutes(5),
                5,
                Duration.ofMinutes(5),
                10);
    }

    private static IdentitySecurityProperties localProperties() {
        return new IdentitySecurityProperties(
                List.of("https://careos.example"),
                URI.create("https://careos.example"),
                "security@careos.example",
                "CareOS-Local-Token-Pepper-Change-Me",
                "Q2FyZU9TLUxvY2FsLU1GQS1LZXktTXVzdC1DaGFuZ2U=",
                Duration.ofHours(12),
                Duration.ofMinutes(10),
                Duration.ofMinutes(15),
                5,
                Duration.ofMinutes(5),
                5,
                Duration.ofMinutes(5),
                10);
    }

    private static IdentitySecurityProperties httpProperties() {
        return new IdentitySecurityProperties(
                List.of("http://careos.example"),
                URI.create("http://careos.example"),
                "security@careos.example",
                "production-token-pepper-that-is-at-least-32-characters",
                productionMfaKey(),
                Duration.ofHours(12),
                Duration.ofMinutes(10),
                Duration.ofMinutes(15),
                5,
                Duration.ofMinutes(5),
                5,
                Duration.ofMinutes(5),
                10);
    }

    private static MockEnvironment productionEnvironment() {
        var environment = new MockEnvironment();
        environment.setActiveProfiles("production");
        environment.setProperty("server.servlet.session.cookie.secure", "true");
        environment.setProperty("spring.data.redis.ssl.enabled", "true");
        environment.setProperty("spring.mail.properties.mail.smtp.auth", "true");
        environment.setProperty("spring.mail.properties.mail.smtp.starttls.enable", "true");
        environment.setProperty("spring.mail.properties.mail.smtp.starttls.required", "true");
        environment.setProperty(
                "spring.mail.properties.mail.smtp.ssl.checkserveridentity", "true");
        environment.setProperty(
                "spring.datasource.url",
                "jdbc:postgresql://database.internal:5432/careos?sslmode=verify-full");
        environment.setProperty(
                "spring.flyway.url",
                "jdbc:postgresql://database.internal:5432/careos?sslmode=verify-full");
        environment.setProperty("spring.datasource.password", "application-database-secret");
        environment.setProperty("spring.datasource.username", "careos_runtime");
        environment.setProperty("spring.flyway.password", "migration-database-secret");
        environment.setProperty("spring.flyway.user", "careos_migration");
        environment.setProperty("spring.data.redis.host", "redis.internal");
        environment.setProperty("spring.data.redis.password", "redis-production-secret");
        environment.setProperty("spring.data.redis.username", "careos_runtime");
        environment.setProperty("spring.mail.host", "smtp.internal");
        environment.setProperty("spring.mail.password", "smtp-production-secret");
        environment.setProperty("spring.mail.username", "careos_runtime");
        environment.setProperty("careos.storage.s3.allow-http", "false");
        environment.setProperty("careos.storage.s3.create-bucket-if-missing", "false");
        environment.setProperty("management.tracing.export.otlp.enabled", "false");
        return environment;
    }

    private static String productionMfaKey() {
        var key = new byte[32];
        for (int index = 0; index < key.length; index++) {
            key[index] = (byte) (index + 1);
        }
        return Base64.getEncoder().encodeToString(key);
    }
}
