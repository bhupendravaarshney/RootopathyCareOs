package com.rootopathy.careos.identity.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.rootopathy.careos.identity.application.SecurityNotificationPort;
import com.rootopathy.careos.identity.application.SecurityTokenPort;
import jakarta.servlet.http.Cookie;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@Import(IdentitySecurityIntegrationTest.NotificationTestConfiguration.class)
class IdentitySecurityIntegrationTest {
    private static final String MIGRATOR_USER = "careos_migrator";
    private static final String MIGRATOR_PASSWORD = "careos-migrator-test-only";
    private static final String APP_USER = "careos_app";
    private static final String APP_PASSWORD = "careos-app-test-only";
    private static final String ORIGIN = "http://localhost:4173";
    private static final String SESSION_COOKIE = "CAREOS_SESSION";
    private static final String CSRF_COOKIE = "XSRF-TOKEN";
    private static final String PASSWORD = "Correct-Horse-Battery-42";
    private static final UUID ORG_ONE = UUID.fromString("01900000-0000-7000-8000-000000000001");

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"))
            .withDatabaseName("careos_test")
            .withUsername(MIGRATOR_USER)
            .withPassword(MIGRATOR_PASSWORD)
            .withInitScript("db/test-init.sql");

    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse(
                    "redis:8-alpine@sha256:becdda6c7f4b3fb42e42fd7f120bbf5c54c4caaaf16f26da24e4563d2c1f0576"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void infrastructureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> APP_USER);
        registry.add("spring.datasource.password", () -> APP_PASSWORD);
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", () -> MIGRATOR_USER);
        registry.add("spring.flyway.password", () -> MIGRATOR_PASSWORD);
        registry.add("spring.flyway.placeholders.applicationRole", () -> APP_USER);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private SecurityTokenPort tokenCodec;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private CapturingSecurityNotification notifications;

    private UUID userId;
    private String email;

    @BeforeEach
    void seedActiveAccount() {
        redisTemplate.execute((RedisCallback<Void>) connection -> {
            connection.serverCommands().flushDb();
            return null;
        });
        notifications.clear();
        userId = UUID.randomUUID();
        email = "identity+" + userId + "@rootopathy.test";
        jdbcTemplate.update(
                "insert into users (id, email, display_name, status) values (?, ?, ?, 'active')",
                userId,
                email,
                "Identity Test User");
        jdbcTemplate.update(
                "insert into password_credentials (user_id, password_hash) values (?, ?)",
                userId,
                passwordEncoder.encode(PASSWORD));
    }

    @Test
    void rejectsUnsafeBrowserRequestsWithoutBothApprovedOriginAndCsrf() throws Exception {
        var csrf = csrf();
        mockMvc.perform(post("/api/v1/auth/login")
                        .cookie(csrf.cookie())
                        .header(csrf.headerName(), csrf.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson(email, PASSWORD)))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("invalid-request-origin"));

        mockMvc.perform(post("/api/v1/auth/login")
                        .header("Origin", ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson(email, PASSWORD)))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("csrf-token-invalid"));

        mockMvc.perform(get("/api/v1/private").header("Authorization", "Basic dGVzdDp0ZXN0"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist("WWW-Authenticate"))
                .andExpect(jsonPath("$.code").value("authentication-required"));
    }

    @Test
    void rotatesAndPersistsSessionWhileKeepingCredentialFailuresGenericAndThrottled() throws Exception {
        var anonymous = mockMvc.perform(get("/api/v1/auth/session"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("anonymous"))
                .andReturn();
        var preAuthenticationSession = requireCookie(anonymous, SESSION_COOKIE);
        var csrf = csrf(preAuthenticationSession);

        var login = login(email, PASSWORD, csrf, "198.51.100.10", preAuthenticationSession)
                .andExpect(status().isOk())
                .andExpect(cookie().httpOnly(SESSION_COOKIE, true))
                .andExpect(cookie().secure(SESSION_COOKIE, true))
                .andExpect(jsonPath("$.state").value("authenticated"))
                .andExpect(jsonPath("$.recentAuthentication").value(true))
                .andReturn();
        var authenticatedSession = requireCookie(login, SESSION_COOKIE);
        assertThat(authenticatedSession.getValue()).isNotEqualTo(preAuthenticationSession.getValue());

        mockMvc.perform(get("/api/v1/auth/session").cookie(authenticatedSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("authenticated"))
                .andExpect(jsonPath("$.user.email").value(email));
        assertThat(redisTemplate.keys("careos:session:*")).isNotEmpty();

        var failureCsrf = csrf();
        var known = login(email, "Wrong-Password-42", failureCsrf, "198.51.100.20")
                .andExpect(status().isUnauthorized())
                .andReturn();
        var unknown = login("absent+" + userId + "@rootopathy.test", "Wrong-Password-42", failureCsrf, "198.51.100.21")
                .andExpect(status().isUnauthorized())
                .andReturn();
        assertThat(normalizedFailureBody(known)).isEqualTo(normalizedFailureBody(unknown));

        var throttleCsrf = csrf();
        // The earlier generic-failure assertion already consumed one attempt for this email.
        for (var attempt = 0; attempt < 4; attempt++) {
            login(email, "Wrong-Password-42", throttleCsrf, "198.51.100.30")
                    .andExpect(status().isUnauthorized());
        }
        login(email, "Wrong-Password-42", throttleCsrf, "198.51.100.30")
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("login-throttled"));

        browserPost(
                        "/api/v1/auth/logout",
                        "",
                        csrf(authenticatedSession),
                        "198.51.100.10",
                        authenticatedSession)
                .andExpect(status().isNoContent())
                .andExpect(cookie().maxAge(SESSION_COOKIE, 0));
        mockMvc.perform(get("/api/v1/private").cookie(authenticatedSession))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("authentication-required"));
        assertThat(jdbcTemplate.queryForObject(
                        "select revocation_reason from user_sessions where user_id = ?",
                        String.class,
                        userId))
                .isEqualTo("logout");
    }

    @Test
    void rejectsAStoredSessionImmediatelyWhenTheSecurityVersionChanges() throws Exception {
        var login = login(email, PASSWORD, csrf(), "198.51.100.40")
                .andExpect(status().isOk())
                .andReturn();
        var session = requireCookie(login, SESSION_COOKIE);
        jdbcTemplate.update("update users set security_version = security_version + 1 where id = ?", userId);

        mockMvc.perform(get("/api/v1/auth/session").cookie(session))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("session-revoked"));
    }

    @Test
    void consumesPasswordResetTokenOnceAndRevokesExistingSessions() throws Exception {
        var login = login(email, PASSWORD, csrf(), "198.51.100.50")
                .andExpect(status().isOk())
                .andReturn();
        var session = requireCookie(login, SESSION_COOKIE);
        var requestCsrf = csrf();
        browserPost(
                        "/api/v1/auth/password-reset-requests",
                        objectMapper.writeValueAsString(Map.of("email", email)),
                        requestCsrf,
                        "198.51.100.50")
                .andExpect(status().isAccepted())
                .andExpect(content().string(""));
        var rawToken = notifications.tokenFor(email);
        assertThat(rawToken).isNotBlank();
        assertThat(jdbcTemplate.queryForObject(
                        "select token_hash from password_reset_tokens where user_id = ? and consumed_at is null",
                        String.class,
                        userId))
                .isEqualTo(tokenCodec.digest(rawToken))
                .isNotEqualTo(rawToken);

        var absentEmail = "absent+" + userId + "@rootopathy.test";
        browserPost(
                        "/api/v1/auth/password-reset-requests",
                        objectMapper.writeValueAsString(Map.of("email", absentEmail)),
                        csrf(),
                        "198.51.100.54")
                .andExpect(status().isAccepted())
                .andExpect(content().string(""));
        assertThat(notifications.tokenFor(absentEmail)).isNull();

        var resetCsrf = csrf(session);
        browserPost(
                        "/api/v1/auth/password-resets",
                        objectMapper.writeValueAsString(new ResetBody(rawToken, "New-Correct-Horse-Password-43")),
                        resetCsrf,
                        "198.51.100.50",
                        session)
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/auth/session").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("anonymous"));
        mockMvc.perform(get("/api/v1/private").cookie(session))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("authentication-required"));
        login(email, PASSWORD, csrf(), "198.51.100.51")
                .andExpect(status().isUnauthorized());
        login(email, "New-Correct-Horse-Password-43", csrf(), "198.51.100.52")
                .andExpect(status().isOk());

        browserPost(
                        "/api/v1/auth/password-resets",
                        objectMapper.writeValueAsString(new ResetBody(rawToken, "Another-Correct-Password-44")),
                        csrf(),
                        "198.51.100.53")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid-or-expired-token"));
        assertThat(jdbcTemplate.queryForObject(
                        "select security_version from users where id = ?", Long.class, userId))
                .isEqualTo(1L);
        assertThat(authenticationEventCount("identity.sessions.revoked")).isEqualTo(1);

        notifications.failNext();
        browserPost(
                        "/api/v1/auth/password-reset-requests",
                        objectMapper.writeValueAsString(Map.of("email", email)),
                        csrf(),
                        "198.51.100.55")
                .andExpect(status().isAccepted())
                .andExpect(content().string(""));
    }

    @Test
    void encryptsTotpEnrollmentAndAllowsEachRecoveryCodeOnlyOnce() throws Exception {
        var firstLogin = login(email, PASSWORD, csrf(), "198.51.100.60")
                .andExpect(status().isOk())
                .andReturn();
        var firstSession = requireCookie(firstLogin, SESSION_COOKIE);
        var authenticatedCsrf = csrf(firstSession);
        var enrollment = browserPost(
                        "/api/v1/auth/mfa/enrollments",
                        "{\"label\":\"Primary authenticator\"}",
                        authenticatedCsrf,
                        "198.51.100.60",
                        firstSession)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.provisioningUri").value(org.hamcrest.Matchers.startsWith("otpauth://totp/")))
                .andReturn();
        var secret = objectMapper.readTree(enrollment.getResponse().getContentAsString()).get("secret").stringValue();
        var protectedSecret = jdbcTemplate.queryForObject(
                "select encrypted_secret from mfa_methods where user_id = ? and status = 'pending'",
                String.class,
                userId);
        assertThat(protectedSecret).startsWith("v1:").doesNotContain(secret);

        var verification = browserPost(
                        "/api/v1/auth/mfa/enrollments/verification",
                        objectMapper.writeValueAsString(new CodeBody(currentTotp(secret))),
                        authenticatedCsrf,
                        "198.51.100.60",
                        firstSession)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recoveryCodes.length()").value(10))
                .andReturn();
        var recoveryCode = objectMapper
                .readTree(verification.getResponse().getContentAsString())
                .get("recoveryCodes")
                .get(0)
                .stringValue();

        var pendingLogin = login(email, PASSWORD, csrf(), "198.51.100.61")
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.state").value("mfa_required"))
                .andReturn();
        var pendingSession = requireCookie(pendingLogin, SESSION_COOKIE);
        var challengeCsrf = csrf(pendingSession);
        browserPost(
                        "/api/v1/auth/mfa/challenges",
                        objectMapper.writeValueAsString(new CodeBody(recoveryCode)),
                        challengeCsrf,
                        "198.51.100.61",
                        pendingSession)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("authenticated"));

        var replayLogin = login(email, PASSWORD, csrf(), "198.51.100.62")
                .andExpect(status().isAccepted())
                .andReturn();
        var replaySession = requireCookie(replayLogin, SESSION_COOKIE);
        browserPost(
                        "/api/v1/auth/mfa/challenges",
                        objectMapper.writeValueAsString(new CodeBody(recoveryCode)),
                        csrf(replaySession),
                        "198.51.100.62",
                        replaySession)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("authentication-verification-failed"));
        assertThat(jdbcTemplate.queryForObject(
                        "select count(*) from recovery_codes where user_id = ? and used_at is not null",
                        Integer.class,
                        userId))
                .isEqualTo(1);
        assertThat(authenticationEventCount("identity.mfa-challenge.failed")).isEqualTo(1);
    }

    @Test
    void verifiesRecentAuthenticationAndPersistsFailedEvidence() throws Exception {
        var login = login(email, PASSWORD, csrf(), "198.51.100.70")
                .andExpect(status().isOk())
                .andReturn();
        var session = requireCookie(login, SESSION_COOKIE);

        browserPost(
                        "/api/v1/auth/recent-authentications",
                        objectMapper.writeValueAsString(Map.of("password", PASSWORD)),
                        csrf(session),
                        "198.51.100.70",
                        session)
                .andExpect(status().isNoContent());
        assertThat(jdbcTemplate.queryForObject(
                        "select recent_authentication_at is not null from user_sessions where user_id = ?",
                        Boolean.class,
                        userId))
                .isTrue();

        browserPost(
                        "/api/v1/auth/recent-authentications",
                        objectMapper.writeValueAsString(Map.of("password", "Wrong-Password-42")),
                        csrf(session),
                        "198.51.100.70",
                        session)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("authentication-verification-failed"));
        assertThat(authenticationEventCount("identity.recent-authentication.failed")).isEqualTo(1);
    }

    @Test
    void discoversAndSelectsOnlyLiveMembershipsAndIgnoresOrganizationHeaders() throws Exception {
        var unavailableOrganization = UUID.randomUUID();
        executeAsMigrator("""
                INSERT INTO organizations (id, legal_name, display_name, country_code, timezone, status)
                VALUES ('%s', 'Unavailable Test Organization', 'Unavailable Organization', 'IN', 'Asia/Kolkata', 'active')
                """.formatted(unavailableOrganization));
        executeAsMigrator("""
                INSERT INTO organization_memberships
                    (organization_id, user_id, role_key, status)
                VALUES
                    ('%s', '%s', 'foundation-test-role', 'active'),
                    ('%s', '%s', 'suspended-test-role', 'suspended')
                """.formatted(ORG_ONE, userId, unavailableOrganization, userId));

        var login = login(email, PASSWORD, csrf(), "198.51.100.80")
                .andExpect(status().isOk())
                .andReturn();
        var session = requireCookie(login, SESSION_COOKIE);

        mockMvc.perform(get("/api/v1/organizations")
                        .cookie(session)
                        .header("X-Organization-Id", unavailableOrganization))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(ORG_ONE.toString()))
                .andExpect(jsonPath("$[0].roleKeys[0]").value("foundation-test-role"))
                .andExpect(jsonPath("$[0].selected").value(false));

        browserPost(
                        "/api/v1/auth/organization-selections",
                        objectMapper.writeValueAsString(Map.of("organizationId", unavailableOrganization)),
                        csrf(session),
                        "198.51.100.80",
                        session)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("organization-not-found"));

        browserPost(
                        "/api/v1/auth/organization-selections",
                        objectMapper.writeValueAsString(Map.of("organizationId", ORG_ONE)),
                        csrf(session),
                        "198.51.100.80",
                        session)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(ORG_ONE.toString()))
                .andExpect(jsonPath("$.selected").value(true));

        mockMvc.perform(get("/api/v1/organizations").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].selected").value(true));

        executeAsMigrator("""
                UPDATE organization_memberships
                SET status = 'suspended'
                WHERE organization_id = '%s' AND user_id = '%s'
                """.formatted(ORG_ONE, userId));
        mockMvc.perform(get("/api/v1/organizations").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    private org.springframework.test.web.servlet.ResultActions login(
            String username, String password, CsrfMaterial csrf, String remoteAddress, Cookie... cookies)
            throws Exception {
        return browserPost("/api/v1/auth/login", loginJson(username, password), csrf, remoteAddress, cookies);
    }

    private org.springframework.test.web.servlet.ResultActions browserPost(
            String path, String json, CsrfMaterial csrf, String remoteAddress, Cookie... cookies)
            throws Exception {
        var builder = post(path)
                .header("Origin", ORIGIN)
                .header(csrf.headerName(), csrf.token())
                .cookie(csrf.cookie())
                .with(request -> {
                    request.setRemoteAddr(remoteAddress);
                    return request;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content(json);
        addCookies(builder, cookies);
        return mockMvc.perform(builder);
    }

    private CsrfMaterial csrf(Cookie... cookies) throws Exception {
        var builder = get("/api/v1/auth/csrf");
        addCookies(builder, cookies);
        var result = mockMvc.perform(builder)
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andReturn();
        var json = objectMapper.readTree(result.getResponse().getContentAsString());
        var material = new CsrfMaterial(
                json.get("headerName").stringValue(),
                json.get("token").stringValue(),
                requireCookie(result, CSRF_COOKIE));
        assertThat(material.cookie().getSecure()).isTrue();
        return material;
    }

    private String loginJson(String username, String password) throws Exception {
        return objectMapper.writeValueAsString(new LoginBody(username, password));
    }

    private String normalizedFailureBody(MvcResult result) throws Exception {
        var json = objectMapper.readTree(result.getResponse().getContentAsString());
        ((tools.jackson.databind.node.ObjectNode) json).remove("correlationId");
        return json.toString();
    }

    private int authenticationEventCount(String eventName) throws Exception {
        try (var connection = java.sql.DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), MIGRATOR_USER, MIGRATOR_PASSWORD);
                var statement = connection.prepareStatement(
                        "select count(*) from authentication_events where user_id = ? and event_name = ?")) {
            statement.setObject(1, userId);
            statement.setString(2, eventName);
            try (var result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        } catch (PSQLException exception) {
            throw exception;
        }
    }

    private void executeAsMigrator(String sql) throws Exception {
        try (var connection = java.sql.DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), MIGRATOR_USER, MIGRATOR_PASSWORD);
                var statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private static void addCookies(MockHttpServletRequestBuilder builder, Cookie... cookies) {
        if (cookies.length > 0) {
            builder.cookie(cookies);
        }
    }

    private static Cookie requireCookie(MvcResult result, String name) {
        var cookie = result.getResponse().getCookie(name);
        assertThat(cookie).as("response cookie %s", name).isNotNull();
        return cookie;
    }

    private static String currentTotp(String secret) {
        try {
            var counter = Instant.now().getEpochSecond() / 30;
            var mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(decodeBase32(secret), "HmacSHA1"));
            var digest = mac.doFinal(ByteBuffer.allocate(Long.BYTES).putLong(counter).array());
            var offset = digest[digest.length - 1] & 0x0f;
            var binary = ((digest[offset] & 0x7f) << 24)
                    | ((digest[offset + 1] & 0xff) << 16)
                    | ((digest[offset + 2] & 0xff) << 8)
                    | (digest[offset + 3] & 0xff);
            return "%06d".formatted(binary % 1_000_000);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static byte[] decodeBase32(String value) {
        var normalized = value.toUpperCase(Locale.ROOT);
        var result = new byte[normalized.length() * 5 / 8];
        var buffer = 0;
        var bitsRemaining = 0;
        var outputIndex = 0;
        for (var current : normalized.toCharArray()) {
            var decoded = current >= 'A' && current <= 'Z' ? current - 'A' : current - '2' + 26;
            buffer = (buffer << 5) | decoded;
            bitsRemaining += 5;
            if (bitsRemaining >= 8) {
                result[outputIndex++] = (byte) ((buffer >> (bitsRemaining - 8)) & 0xff);
                bitsRemaining -= 8;
            }
        }
        return result;
    }

    private record CsrfMaterial(String headerName, String token, Cookie cookie) {}

    private record LoginBody(String email, String password) {}

    private record ResetBody(String token, String newPassword) {}

    private record CodeBody(String code) {}

    @TestConfiguration(proxyBeanMethods = false)
    static class NotificationTestConfiguration {
        @Bean
        @Primary
        CapturingSecurityNotification capturingSecurityNotification() {
            return new CapturingSecurityNotification();
        }
    }

    static final class CapturingSecurityNotification implements SecurityNotificationPort {
        private final Map<String, String> passwordResetTokens = new ConcurrentHashMap<>();
        private boolean failNext;

        @Override
        public void sendPasswordReset(String email, String rawToken, Instant expiresAt) {
            if (failNext) {
                failNext = false;
                throw new IllegalStateException("synthetic notification outage");
            }
            passwordResetTokens.put(email, rawToken);
        }

        String tokenFor(String email) {
            return passwordResetTokens.get(email);
        }

        void clear() {
            passwordResetTokens.clear();
            failNext = false;
        }

        void failNext() {
            failNext = true;
        }
    }
}
