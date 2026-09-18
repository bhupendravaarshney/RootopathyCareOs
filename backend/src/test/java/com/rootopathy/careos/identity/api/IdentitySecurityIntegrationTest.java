package com.rootopathy.careos.identity.api;

import static com.rootopathy.careos.identity.infrastructure.security.CareOsAuthorities.AUTHENTICATED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import java.util.List;
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
import org.springframework.security.core.authority.SimpleGrantedAuthority;
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
            new PostgreSQLContainer(DockerImageName.parse(
                            "postgres:18-alpine@sha256:d3e1620b530c944afa6e887d22eb899824da68e19c52024bf98f5220c88a65b2")
                    .asCompatibleSubstituteFor("postgres"))
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
        registry.add("careos.authorization.reference-policy-enabled", () -> true);
        registry.add("careos.invitations.enabled", () -> true);
        registry.add("careos.mfa-administration.enabled", () -> true);
        registry.add("careos.membership-administration.enabled", () -> true);
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
    void emitsExplicitSecurityHeadersAndLimitsHstsToSecureRequests() throws Exception {
        mockMvc.perform(get("/api/public/system-summary").secure(true))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        "Content-Security-Policy",
                        "default-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'"))
                .andExpect(header().string(
                        "Permissions-Policy",
                        "camera=(), geolocation=(), microphone=(), payment=(), usb=()"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string("Strict-Transport-Security", "max-age=31536000"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"));

        mockMvc.perform(get("/api/public/system-summary"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Strict-Transport-Security"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
    }

    @Test
    void rotatesAndPersistsSessionWhileKeepingCredentialFailuresGenericAndThrottled() throws Exception {
        var anonymous = mockMvc.perform(get("/api/v1/auth/session"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(SessionExpiryHeaderWriter.HEADER_NAME))
                .andExpect(jsonPath("$.state").value("anonymous"))
                .andExpect(jsonPath("$.mfaEnabled").value(false))
                .andReturn();
        var preAuthenticationSession = requireCookie(anonymous, SESSION_COOKIE);
        var csrf = csrf(preAuthenticationSession);

        var login = login(email, PASSWORD, csrf, "198.51.100.10", preAuthenticationSession)
                .andExpect(status().isOk())
                .andExpect(header().string(SessionExpiryHeaderWriter.HEADER_NAME, "1800"))
                .andExpect(cookie().httpOnly(SESSION_COOKIE, true))
                .andExpect(cookie().secure(SESSION_COOKIE, true))
                .andExpect(jsonPath("$.state").value("authenticated"))
                .andExpect(jsonPath("$.recentAuthentication").value(true))
                .andExpect(jsonPath("$.mfaEnabled").value(false))
                .andReturn();
        var authenticatedSession = requireCookie(login, SESSION_COOKIE);
        assertThat(authenticatedSession.getValue()).isNotEqualTo(preAuthenticationSession.getValue());

        mockMvc.perform(get("/api/v1/auth/session").cookie(authenticatedSession))
                .andExpect(status().isOk())
                .andExpect(header().string(SessionExpiryHeaderWriter.HEADER_NAME, "1800"))
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

        mockMvc.perform(get("/api/v1/auth/session").cookie(firstSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("authenticated"))
                .andExpect(jsonPath("$.mfaEnabled").value(true));

        var pendingLogin = login(email, PASSWORD, csrf(), "198.51.100.61")
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.state").value("mfa_required"))
                .andExpect(jsonPath("$.mfaEnabled").value(true))
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
                .andExpect(jsonPath("$.state").value("authenticated"))
                .andExpect(jsonPath("$.mfaEnabled").value(true));

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
    void requiresMandatoryRoleMfaEnrollmentBeforeOrganizationAccessAndReadinessCompletion()
            throws Exception {
        seedApprovedOwner();
        var observerId = UUID.randomUUID();
        var observerEmail = "mfa.readiness+" + observerId + "@rootopathy.test";
        seedAccount(observerId, observerEmail, "MFA Readiness Observer");
        executeAsMigrator("""
                INSERT INTO organization_memberships
                    (organization_id, user_id, role_key, status)
                VALUES ('%s', '%s', 'local_bootstrap', 'active')
                """.formatted(ORG_ONE, observerId));
        var observerLogin = login(observerEmail, PASSWORD, csrf(), "198.51.100.63")
                .andExpect(status().isOk())
                .andReturn();
        var observerSession = requireCookie(observerLogin, SESSION_COOKIE);
        var readinessPath = "/api/v1/organizations/" + ORG_ONE + "/setup-readiness";
        mockMvc.perform(get(readinessPath).cookie(observerSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gates[?(@.key == 'access.mfa_enforced')].outcome")
                        .value(org.hamcrest.Matchers.contains("blocked")));

        var requiredLogin = login(email, PASSWORD, csrf(), "198.51.100.64")
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.state").value("mfa_enrollment_required"))
                .andExpect(jsonPath("$.recentAuthentication").value(true))
                .andExpect(jsonPath("$.mfaEnabled").value(false))
                .andExpect(jsonPath("$.mfaRequired").value(true))
                .andReturn();
        var requiredSession = requireCookie(requiredLogin, SESSION_COOKIE);
        var requiredCsrf = csrf(requiredSession);
        mockMvc.perform(get("/api/v1/organizations").cookie(requiredSession))
                .andExpect(status().isForbidden());

        var enrollment = browserPost(
                        "/api/v1/auth/mfa/enrollments",
                        "{\"label\":\"Required role authenticator\"}",
                        requiredCsrf,
                        "198.51.100.64",
                        requiredSession)
                .andExpect(status().isOk())
                .andReturn();
        var secret = objectMapper
                .readTree(enrollment.getResponse().getContentAsString())
                .get("secret")
                .stringValue();
        var verification = browserPost(
                        "/api/v1/auth/mfa/enrollments/verification",
                        objectMapper.writeValueAsString(new CodeBody(currentTotp(secret))),
                        requiredCsrf,
                        "198.51.100.64",
                        requiredSession)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recoveryCodes.length()").value(10))
                .andReturn();
        var upgradedSession = verification.getResponse().getCookie(SESSION_COOKIE);
        if (upgradedSession == null) {
            upgradedSession = requiredSession;
        }

        mockMvc.perform(get("/api/v1/auth/session").cookie(upgradedSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("authenticated"))
                .andExpect(jsonPath("$.mfaEnabled").value(true))
                .andExpect(jsonPath("$.mfaRequired").value(true));
        mockMvc.perform(get(readinessPath).cookie(observerSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gates[?(@.key == 'access.mfa_enforced')].outcome")
                        .value(org.hamcrest.Matchers.contains("complete")));

        login(email, PASSWORD, csrf(), "198.51.100.65")
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.state").value("mfa_required"))
                .andExpect(jsonPath("$.mfaRequired").value(true));
    }

    @Test
    void revokesAnExistingPasswordOnlySessionWhenAMandatoryRoleBecomesEffective()
            throws Exception {
        var initialLogin = login(email, PASSWORD, csrf(), "198.51.100.66")
                .andExpect(status().isOk())
                .andReturn();
        var session = requireCookie(initialLogin, SESSION_COOKIE);

        seedApprovedOwner();

        mockMvc.perform(get("/api/v1/auth/session").cookie(session))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("mfa-enrollment-required"));
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
    void requiresIndependentApprovalBeforeAdministrativelyResettingMfa() throws Exception {
        seedApprovedOwner();
        var checkerId = UUID.randomUUID();
        var checkerEmail = "mfa.checker+" + checkerId + "@rootopathy.test";
        var targetId = UUID.randomUUID();
        var targetEmail = "mfa.target+" + targetId + "@rootopathy.test";
        seedAccount(checkerId, checkerEmail, "MFA Reset Checker");
        seedAccount(targetId, targetEmail, "MFA Reset Target");
        executeAsMigrator("""
                INSERT INTO organization_memberships
                    (organization_id, user_id, role_key, status)
                VALUES
                    ('%s', '%s', 'organization_owner', 'active'),
                    ('%s', '%s', 'organization_viewer', 'active')
                """.formatted(ORG_ONE, checkerId, ORG_ONE, targetId));

        var targetLogin = login(targetEmail, PASSWORD, csrf(), "198.51.100.72")
                .andExpect(status().isOk())
                .andReturn();
        var targetSession = requireCookie(targetLogin, SESSION_COOKIE);
        var targetCsrf = csrf(targetSession);
        var enrollment = browserPost(
                        "/api/v1/auth/mfa/enrollments",
                        "{\"label\":\"Administrative reset test\"}",
                        targetCsrf,
                        "198.51.100.72",
                        targetSession)
                .andExpect(status().isOk())
                .andReturn();
        var secret = objectMapper
                .readTree(enrollment.getResponse().getContentAsString())
                .get("secret")
                .stringValue();
        browserPost(
                        "/api/v1/auth/mfa/enrollments/verification",
                        objectMapper.writeValueAsString(new CodeBody(currentTotp(secret))),
                        targetCsrf,
                        "198.51.100.72",
                        targetSession)
                .andExpect(status().isOk());
        var securityVersionBeforeReset = jdbcTemplate.queryForObject(
                "select security_version from users where id = ?", Long.class, targetId);

        var makerSession = enrollMfaAndAuthenticate(email, "198.51.100.73");
        var requestReason = "Verified lost authenticator on support case CARE-42";
        var request = browserIdempotentPost(
                        "/api/v1/organizations/" + ORG_ONE + "/users/" + targetId
                                + "/mfa-reset-requests",
                        objectMapper.writeValueAsString(Map.of("reason", requestReason)),
                        "mfa-reset-request-" + UUID.randomUUID(),
                        csrf(makerSession),
                        "198.51.100.73",
                        makerSession)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.targetUserId").value(targetId.toString()))
                .andExpect(jsonPath("$.status").value("pending"))
                .andReturn();
        var approvalId = objectMapper
                .readTree(request.getResponse().getContentAsString())
                .get("approvalId")
                .stringValue();
        var approvalPath = "/api/v1/organizations/" + ORG_ONE + "/users/" + targetId
                + "/mfa-reset-requests/" + approvalId;

        browserIdempotentPost(
                        approvalPath + "/approvals",
                        objectMapper.writeValueAsString(Map.of("reason", "Maker cannot self-approve")),
                        "mfa-reset-self-approval-" + UUID.randomUUID(),
                        csrf(makerSession),
                        "198.51.100.73",
                        makerSession)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("mfa-reset-approval-unavailable"));

        var checkerSession = enrollMfaAndAuthenticate(checkerEmail, "198.51.100.74");
        browserIdempotentPost(
                        approvalPath + "/approvals",
                        objectMapper.writeValueAsString(Map.of(
                                "reason", "Independently verified support case and target identity")),
                        "mfa-reset-approval-" + UUID.randomUUID(),
                        csrf(checkerSession),
                        "198.51.100.74",
                        checkerSession)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("approved"));

        browserIdempotentPost(
                        approvalPath + "/executions",
                        objectMapper.writeValueAsString(Map.of("reason", requestReason)),
                        "mfa-reset-wrong-executor-" + UUID.randomUUID(),
                        csrf(checkerSession),
                        "198.51.100.74",
                        checkerSession)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("independent-approval-required"));

        var executionKey = "mfa-reset-execution-" + UUID.randomUUID();
        for (var attempt = 0; attempt < 2; attempt++) {
            browserIdempotentPost(
                            approvalPath + "/executions",
                            objectMapper.writeValueAsString(Map.of("reason", requestReason)),
                            executionKey,
                            csrf(makerSession),
                            "198.51.100.73",
                            makerSession)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("reset"));
        }

        mockMvc.perform(get("/api/v1/private").cookie(targetSession))
                .andExpect(status().isUnauthorized());
        assertThat(jdbcTemplate.queryForObject(
                        "select security_version from users where id = ?", Long.class, targetId))
                .isEqualTo(securityVersionBeforeReset + 1);
        assertThat(migratorCount("""
                        SELECT count(*) FROM mfa_methods
                        WHERE user_id = '%s' AND status = 'revoked'
                          AND encrypted_secret IS NULL AND revoked_at IS NOT NULL
                        """.formatted(targetId)))
                .isEqualTo(1);
        assertThat(migratorCount("""
                        SELECT count(*) FROM recovery_codes
                        WHERE user_id = '%s' AND used_at IS NULL AND revoked_at IS NULL
                        """.formatted(targetId)))
                .isZero();
        assertThat(migratorCount("""
                        SELECT count(*) FROM user_sessions
                        WHERE user_id = '%s' AND revoked_at IS NULL
                        """.formatted(targetId)))
                .isZero();
        assertThat(migratorCount("""
                        SELECT count(*) FROM authorization_approval_requests
                        WHERE id = '%s' AND organization_id = '%s'
                          AND subject_id = '%s' AND requested_by_user_id = '%s'
                          AND decided_by_user_id = '%s' AND consumed_by_user_id = '%s'
                          AND status = 'consumed' AND consumed_idempotency_key = '%s'
                        """.formatted(
                        approvalId, ORG_ONE, targetId, userId, checkerId, userId, executionKey)))
                .isEqualTo(1);
        assertThat(migratorCount("""
                        SELECT count(*) FROM audit_events
                        WHERE (subject_id = '%s' AND event_name IN (
                            'identity.mfa-admin-reset.requested',
                            'identity.mfa-admin-reset.approved'))
                           OR (subject_id = '%s'
                               AND event_name = 'identity.mfa-admin-reset.completed')
                        """.formatted(approvalId, targetId)))
                .isEqualTo(3);
        assertThat(migratorCount("""
                        SELECT count(*) FROM outbox_events
                        WHERE (aggregate_id = '%s' AND event_name IN (
                            'identity.mfa-admin-reset.requested',
                            'identity.mfa-admin-reset.approved'))
                           OR (aggregate_id = '%s'
                               AND event_name = 'identity.mfa-admin-reset.completed')
                        """.formatted(approvalId, targetId)))
                .isEqualTo(3);
        assertThat(migratorCount("""
                        SELECT count(*) FROM authentication_events
                        WHERE user_id = '%s' AND event_name IN (
                            'identity.mfa-admin-reset.completed', 'identity.sessions.revoked')
                        """.formatted(targetId)))
                .isEqualTo(2);
        assertThat(notifications.mfaResetCount(targetEmail)).isEqualTo(1);
    }

    @Test
    void requiresExactIndependentApprovalForMembershipRoleChangesAndRevocation()
            throws Exception {
        seedApprovedOwner();
        var checkerId = UUID.randomUUID();
        var checkerEmail = "membership.checker+" + checkerId + "@rootopathy.test";
        var targetId = UUID.randomUUID();
        var targetEmail = "membership.target+" + targetId + "@rootopathy.test";
        var membershipId = UUID.randomUUID();
        seedAccount(checkerId, checkerEmail, "Membership Change Checker");
        seedAccount(targetId, targetEmail, "Membership Change Target");
        executeAsMigrator("""
                INSERT INTO organization_memberships
                    (id, organization_id, user_id, role_key, status)
                VALUES
                    ('%s', '%s', '%s', 'organization_owner', 'active'),
                    ('%s', '%s', '%s', 'security_administrator', 'active')
                """.formatted(UUID.randomUUID(), ORG_ONE, checkerId, membershipId, ORG_ONE, targetId));

        var makerSession = enrollMfaAndAuthenticate(email, "198.51.100.75");
        var checkerSession = enrollMfaAndAuthenticate(checkerEmail, "198.51.100.76");
        var basePath = "/api/v1/organizations/" + ORG_ONE + "/memberships/" + membershipId
                + "/change-requests";
        var roleChangeReason = "Approved least-privilege role adjustment CARE-52";
        var roleChangeJson = objectMapper.writeValueAsString(Map.of(
                "changeType", "role_change",
                "toRoleKey", "organization_viewer",
                "reason", roleChangeReason));

        browserIdempotentPost(
                        basePath,
                        roleChangeJson,
                        "membership-missing-revision-" + UUID.randomUUID(),
                        csrf(makerSession),
                        "198.51.100.75",
                        makerSession)
                .andExpect(status().isPreconditionRequired())
                .andExpect(jsonPath("$.code").value("membership-revision-required"));

        browserConditionalIdempotentPost(
                        basePath,
                        roleChangeJson,
                        "\"organization-membership:" + membershipId + ":1\"",
                        "membership-stale-" + UUID.randomUUID(),
                        csrf(makerSession),
                        "198.51.100.75",
                        makerSession)
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.code").value("membership-revision-stale"));

        var request = browserConditionalIdempotentPost(
                        basePath,
                        roleChangeJson,
                        "\"organization-membership:" + membershipId + ":0\"",
                        "membership-request-" + UUID.randomUUID(),
                        csrf(makerSession),
                        "198.51.100.75",
                        makerSession)
                .andExpect(status().isCreated())
                .andExpect(header().string(
                        "Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.membershipId").value(membershipId.toString()))
                .andExpect(jsonPath("$.targetUserId").value(targetId.toString()))
                .andExpect(jsonPath("$.changeType").value("role_change"))
                .andExpect(jsonPath("$.fromRoleKey").value("security_administrator"))
                .andExpect(jsonPath("$.toRoleKey").value("organization_viewer"))
                .andExpect(jsonPath("$.lockVersion").value(0))
                .andExpect(jsonPath("$.status").value("pending"))
                .andReturn();
        var approvalId = objectMapper
                .readTree(request.getResponse().getContentAsString())
                .get("approvalId")
                .stringValue();
        var workflowPath = basePath + "/" + approvalId;

        browserIdempotentPost(
                        workflowPath + "/approvals",
                        objectMapper.writeValueAsString(Map.of(
                                "reason", "Maker cannot approve the same access request")),
                        "membership-self-approval-" + UUID.randomUUID(),
                        csrf(makerSession),
                        "198.51.100.75",
                        makerSession)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("membership-change-approval-unavailable"));

        browserIdempotentPost(
                        workflowPath + "/approvals",
                        objectMapper.writeValueAsString(Map.of(
                                "reason", "Independent least-privilege review completed")),
                        "membership-approval-" + UUID.randomUUID(),
                        csrf(checkerSession),
                        "198.51.100.76",
                        checkerSession)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("approved"));

        browserIdempotentPost(
                        workflowPath + "/executions",
                        objectMapper.writeValueAsString(Map.of("reason", roleChangeReason)),
                        "membership-wrong-executor-" + UUID.randomUUID(),
                        csrf(checkerSession),
                        "198.51.100.76",
                        checkerSession)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("independent-approval-required"));

        var roleExecutionKey = "membership-execution-" + UUID.randomUUID();
        for (var attempt = 0; attempt < 2; attempt++) {
            browserIdempotentPost(
                            workflowPath + "/executions",
                            objectMapper.writeValueAsString(Map.of("reason", roleChangeReason)),
                            roleExecutionKey,
                            csrf(makerSession),
                            "198.51.100.75",
                            makerSession)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("changed"))
                    .andExpect(jsonPath("$.lockVersion").value(1));
        }
        assertThat(migratorCount("""
                        SELECT count(*) FROM organization_memberships
                        WHERE id = '%s' AND role_key = 'organization_viewer'
                          AND status = 'active' AND lock_version = 1
                          AND updated_by = '%s'
                        """.formatted(membershipId, userId)))
                .isEqualTo(1);

        var revocationReason = "Approved access revocation after offboarding CARE-53";
        var revocation = browserConditionalIdempotentPost(
                        basePath,
                        objectMapper.writeValueAsString(Map.of(
                                "changeType", "revoke", "reason", revocationReason)),
                        "\"organization-membership:" + membershipId + ":1\"",
                        "membership-revoke-request-" + UUID.randomUUID(),
                        csrf(makerSession),
                        "198.51.100.75",
                        makerSession)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.changeType").value("revoke"))
                .andExpect(jsonPath("$.toRoleKey").value(org.hamcrest.Matchers.nullValue()))
                .andReturn();
        var revocationApprovalId = objectMapper
                .readTree(revocation.getResponse().getContentAsString())
                .get("approvalId")
                .stringValue();
        var revocationPath = basePath + "/" + revocationApprovalId;
        browserIdempotentPost(
                        revocationPath + "/approvals",
                        objectMapper.writeValueAsString(Map.of(
                                "reason", "Independent offboarding evidence verified")),
                        "membership-revoke-approval-" + UUID.randomUUID(),
                        csrf(checkerSession),
                        "198.51.100.76",
                        checkerSession)
                .andExpect(status().isOk());
        var revokeExecutionKey = "membership-revoke-execution-" + UUID.randomUUID();
        browserIdempotentPost(
                        revocationPath + "/executions",
                        objectMapper.writeValueAsString(Map.of("reason", revocationReason)),
                        revokeExecutionKey,
                        csrf(makerSession),
                        "198.51.100.75",
                        makerSession)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("revoked"))
                .andExpect(jsonPath("$.lockVersion").value(2));

        assertThat(migratorCount("""
                        SELECT count(*) FROM organization_memberships
                        WHERE id = '%s' AND role_key = 'organization_viewer'
                          AND status = 'revoked' AND effective_to IS NOT NULL
                          AND lock_version = 2 AND updated_by = '%s'
                        """.formatted(membershipId, userId)))
                .isEqualTo(1);
        assertThat(migratorCount("""
                        SELECT count(*) FROM membership_change_requests
                        WHERE organization_id = '%s' AND membership_id = '%s'
                        """.formatted(ORG_ONE, membershipId)))
                .isEqualTo(2);
        assertThat(migratorCount("""
                        SELECT count(*) FROM authorization_approval_requests
                        WHERE organization_id = '%s' AND subject_id = '%s'
                          AND operation_key = 'access.membership.change'
                          AND status = 'consumed' AND consumed_by_user_id = '%s'
                        """.formatted(ORG_ONE, membershipId, userId)))
                .isEqualTo(2);
        assertThat(migratorCount("""
                        SELECT count(*) FROM audit_events
                        WHERE organization_id = '%s'
                          AND event_name IN (
                            'identity.membership-change.requested',
                            'identity.membership-change.approved',
                            'identity.membership.changed',
                            'identity.membership.revoked')
                          AND (subject_id = '%s' OR subject_id = '%s' OR subject_id = '%s')
                        """.formatted(ORG_ONE, membershipId, approvalId, revocationApprovalId)))
                .isEqualTo(6);
        assertThat(migratorCount("""
                        SELECT count(*) FROM outbox_events
                        WHERE organization_id = '%s'
                          AND event_name IN (
                            'identity.membership-change.requested',
                            'identity.membership-change.approved',
                            'identity.membership.changed',
                            'identity.membership.revoked')
                          AND (aggregate_id = '%s' OR aggregate_id = '%s'
                               OR aggregate_id = '%s')
                        """.formatted(ORG_ONE, membershipId, approvalId, revocationApprovalId)))
                .isEqualTo(6);
    }

    @Test
    void requiresExactIndependentApprovalForOwnerPromotionAndDemotion()
            throws Exception {
        seedApprovedOwner();
        var checkerId = UUID.randomUUID();
        var checkerEmail = "owner-transfer.checker+" + checkerId + "@rootopathy.test";
        var targetId = UUID.randomUUID();
        var targetEmail = "owner-transfer.target+" + targetId + "@rootopathy.test";
        var membershipId = UUID.randomUUID();
        seedAccount(checkerId, checkerEmail, "Owner Transfer Checker");
        seedAccount(targetId, targetEmail, "Owner Transfer Target");
        executeAsMigrator("""
                INSERT INTO organization_memberships
                    (id, organization_id, user_id, role_key, status)
                VALUES
                    ('%s', '%s', '%s', 'organization_owner', 'active'),
                    ('%s', '%s', '%s', 'security_administrator', 'active')
                """.formatted(UUID.randomUUID(), ORG_ONE, checkerId, membershipId, ORG_ONE, targetId));

        var makerSession = enrollMfaAndAuthenticate(email, "198.51.100.77");
        var checkerSession = enrollMfaAndAuthenticate(checkerEmail, "198.51.100.78");
        enrollMfaAndAuthenticate(targetEmail, "198.51.100.79");
        var basePath = "/api/v1/organizations/" + ORG_ONE + "/memberships/" + membershipId
                + "/owner-transfer-requests";
        var promotionReason = "Approved owner promotion for succession plan CARE-54";
        var promotion = browserConditionalIdempotentPost(
                        basePath,
                        objectMapper.writeValueAsString(Map.of(
                                "toRoleKey", "organization_owner",
                                "reason", promotionReason)),
                        "\"organization-membership:" + membershipId + ":0\"",
                        "owner-promotion-request-" + UUID.randomUUID(),
                        csrf(makerSession),
                        "198.51.100.77",
                        makerSession)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.membershipId").value(membershipId.toString()))
                .andExpect(jsonPath("$.targetUserId").value(targetId.toString()))
                .andExpect(jsonPath("$.changeType").value("owner_promotion"))
                .andExpect(jsonPath("$.fromRoleKey").value("security_administrator"))
                .andExpect(jsonPath("$.toRoleKey").value("organization_owner"))
                .andExpect(jsonPath("$.lockVersion").value(0))
                .andExpect(jsonPath("$.status").value("pending"))
                .andReturn();
        var promotionApprovalId = objectMapper
                .readTree(promotion.getResponse().getContentAsString())
                .get("approvalId")
                .stringValue();
        var promotionPath = basePath + "/" + promotionApprovalId;

        browserIdempotentPost(
                        promotionPath + "/approvals",
                        objectMapper.writeValueAsString(Map.of(
                                "reason", "Maker cannot approve an owner promotion")),
                        "owner-promotion-self-approval-" + UUID.randomUUID(),
                        csrf(makerSession),
                        "198.51.100.77",
                        makerSession)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("membership-change-approval-unavailable"));

        browserIdempotentPost(
                        promotionPath + "/approvals",
                        objectMapper.writeValueAsString(Map.of(
                                "reason", "Independent succession review completed")),
                        "owner-promotion-approval-" + UUID.randomUUID(),
                        csrf(checkerSession),
                        "198.51.100.78",
                        checkerSession)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("approved"));

        browserIdempotentPost(
                        promotionPath + "/executions",
                        objectMapper.writeValueAsString(Map.of("reason", promotionReason)),
                        "owner-promotion-wrong-executor-" + UUID.randomUUID(),
                        csrf(checkerSession),
                        "198.51.100.78",
                        checkerSession)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("independent-approval-required"));

        var promotionExecutionKey = "owner-promotion-execution-" + UUID.randomUUID();
        for (var attempt = 0; attempt < 2; attempt++) {
            browserIdempotentPost(
                            promotionPath + "/executions",
                            objectMapper.writeValueAsString(Map.of("reason", promotionReason)),
                            promotionExecutionKey,
                            csrf(makerSession),
                            "198.51.100.77",
                            makerSession)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("transferred"))
                    .andExpect(jsonPath("$.lockVersion").value(1));
        }
        assertThat(migratorCount("""
                        SELECT count(*) FROM organization_memberships
                        WHERE id = '%s' AND role_key = 'organization_owner'
                          AND status = 'active' AND effective_to IS NULL
                          AND lock_version = 1 AND updated_by = '%s'
                        """.formatted(membershipId, userId)))
                .isEqualTo(1);

        var demotionReason = "Approved owner demotion after succession handover CARE-55";
        var demotion = browserConditionalIdempotentPost(
                        basePath,
                        objectMapper.writeValueAsString(Map.of(
                                "toRoleKey", "organization_viewer",
                                "reason", demotionReason)),
                        "\"organization-membership:" + membershipId + ":1\"",
                        "owner-demotion-request-" + UUID.randomUUID(),
                        csrf(makerSession),
                        "198.51.100.77",
                        makerSession)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.changeType").value("owner_demotion"))
                .andExpect(jsonPath("$.fromRoleKey").value("organization_owner"))
                .andExpect(jsonPath("$.toRoleKey").value("organization_viewer"))
                .andReturn();
        var demotionApprovalId = objectMapper
                .readTree(demotion.getResponse().getContentAsString())
                .get("approvalId")
                .stringValue();
        var demotionPath = basePath + "/" + demotionApprovalId;
        browserIdempotentPost(
                        demotionPath + "/approvals",
                        objectMapper.writeValueAsString(Map.of(
                                "reason", "Independent handover evidence verified")),
                        "owner-demotion-approval-" + UUID.randomUUID(),
                        csrf(checkerSession),
                        "198.51.100.78",
                        checkerSession)
                .andExpect(status().isOk());
        var demotionExecutionKey = "owner-demotion-execution-" + UUID.randomUUID();
        browserIdempotentPost(
                        demotionPath + "/executions",
                        objectMapper.writeValueAsString(Map.of("reason", demotionReason)),
                        demotionExecutionKey,
                        csrf(makerSession),
                        "198.51.100.77",
                        makerSession)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("transferred"))
                .andExpect(jsonPath("$.lockVersion").value(2));

        assertThat(migratorCount("""
                        SELECT count(*) FROM organization_memberships
                        WHERE id = '%s' AND role_key = 'organization_viewer'
                          AND status = 'active' AND lock_version = 2
                          AND updated_by = '%s'
                        """.formatted(membershipId, userId)))
                .isEqualTo(1);
        assertThat(migratorCount("""
                        SELECT count(*) FROM owner_transfer_requests
                        WHERE organization_id = '%s' AND membership_id = '%s'
                        """.formatted(ORG_ONE, membershipId)))
                .isEqualTo(2);
        assertThat(migratorCount("""
                        SELECT count(*) FROM authorization_approval_requests
                        WHERE organization_id = '%s' AND subject_id = '%s'
                          AND operation_key = 'access.owner-transfer.execute'
                          AND status = 'consumed' AND consumed_by_user_id = '%s'
                        """.formatted(ORG_ONE, membershipId, userId)))
                .isEqualTo(2);
        assertThat(migratorCount("""
                        SELECT count(*) FROM audit_events
                        WHERE organization_id = '%s'
                          AND event_name IN (
                            'identity.owner-transfer.requested',
                            'identity.owner-transfer.approved',
                            'identity.owner.transferred')
                          AND (subject_id = '%s' OR subject_id = '%s' OR subject_id = '%s')
                        """.formatted(
                        ORG_ONE, membershipId, promotionApprovalId, demotionApprovalId)))
                .isEqualTo(6);
        assertThat(migratorCount("""
                        SELECT count(*) FROM outbox_events
                        WHERE organization_id = '%s'
                          AND event_name IN (
                            'identity.owner-transfer.requested',
                            'identity.owner-transfer.approved',
                            'identity.owner.transferred')
                          AND (aggregate_id = '%s' OR aggregate_id = '%s'
                               OR aggregate_id = '%s')
                        """.formatted(
                        ORG_ONE, membershipId, promotionApprovalId, demotionApprovalId)))
                .isEqualTo(6);
    }

    @Test
    void rejectsApprovedInvitationAdministrationWithoutARecentMfaAssertion() throws Exception {
        var loginResult = login(email, PASSWORD, csrf(), "198.51.100.90")
                .andExpect(status().isOk())
                .andReturn();
        var session = requireCookie(loginResult, SESSION_COOKIE);
        var sessionCsrf = csrf(session);
        seedApprovedOwner();
        executeAsMigrator("""
                INSERT INTO mfa_methods
                    (user_id, method_type, status, encrypted_secret, verified_at)
                VALUES ('%s', 'totp', 'enabled', 'integration-test-secret', now())
                """.formatted(userId));
        var invitedEmail = "missing.mfa+" + UUID.randomUUID() + "@rootopathy.test";

        browserIdempotentPost(
                        "/api/v1/organizations/" + ORG_ONE + "/invitations",
                        objectMapper.writeValueAsString(Map.of(
                                "email", invitedEmail,
                                "displayName", "Missing MFA Invitation",
                                "roleKey", "organization_viewer",
                                "reason", "Approved access request without MFA proof")),
                        "missing-mfa-" + UUID.randomUUID(),
                        sessionCsrf,
                        "198.51.100.90",
                        session)
                .andExpect(status().isPreconditionRequired())
                .andExpect(jsonPath("$.code").value("mfa-required"));

        assertThat(notifications.invitationCount(invitedEmail)).isZero();
        assertThat(migratorCount("""
                        SELECT count(*) FROM invitations
                        WHERE lower(email) = lower('%s')
                        """.formatted(invitedEmail)))
                .isZero();
    }

    @Test
    void issuesIdempotentlyAndAcceptsAOneTimeInvitationForANewAccount() throws Exception {
        seedApprovedOwner();
        var session = enrollMfaAndAuthenticate(email, "198.51.100.91");
        var invitedEmail = "new.invitee+" + UUID.randomUUID() + "@rootopathy.test";
        var invitationBody = objectMapper.writeValueAsString(Map.of(
                "email", invitedEmail,
                "displayName", "New Invitation Account",
                "roleKey", "organization_viewer",
                "reason", "Approved workforce onboarding"));
        var idempotencyKey = "invitation-issue-" + UUID.randomUUID();

        var issued = browserIdempotentPost(
                        "/api/v1/organizations/" + ORG_ONE + "/invitations",
                        invitationBody,
                        idempotencyKey,
                        csrf(session),
                        "198.51.100.91",
                        session)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("pending"))
                .andExpect(jsonPath("$.roleKey").value("organization_viewer"))
                .andReturn();
        var invitationId = objectMapper
                .readTree(issued.getResponse().getContentAsString())
                .get("invitationId")
                .stringValue();

        browserIdempotentPost(
                        "/api/v1/organizations/" + ORG_ONE + "/invitations",
                        invitationBody,
                        idempotencyKey,
                        csrf(session),
                        "198.51.100.91",
                        session)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.invitationId").value(invitationId));
        assertThat(notifications.invitationCount(invitedEmail)).isEqualTo(1);

        var rawToken = notifications.invitationTokenFor(invitedEmail);
        var newPassword = "New-Invited-Account-42";
        browserPost(
                        "/api/v1/auth/invitation-acceptances",
                        objectMapper.writeValueAsString(Map.of(
                                "token", rawToken, "newPassword", newPassword)),
                        csrf(),
                        "198.51.100.92")
                .andExpect(status().isCreated())
                .andExpect(header().string(
                        "Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.invitationId").value(invitationId))
                .andExpect(jsonPath("$.organizationId").value(ORG_ONE.toString()))
                .andExpect(jsonPath("$.roleKey").value("organization_viewer"))
                .andExpect(jsonPath("$.accountLink").value("created"));

        browserPost(
                        "/api/v1/auth/invitation-acceptances",
                        objectMapper.writeValueAsString(Map.of(
                                "token", rawToken, "newPassword", newPassword)),
                        csrf(),
                        "198.51.100.92")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid-or-expired-invitation"));
        login(invitedEmail, newPassword, csrf(), "198.51.100.93")
                .andExpect(status().isOk());

        assertThat(migratorCount("""
                        SELECT count(*) FROM invitations
                        WHERE id = '%s' AND status = 'accepted'
                          AND accepted_existing_account = false
                        """.formatted(invitationId)))
                .isEqualTo(1);
        assertThat(migratorCount("""
                        SELECT count(*) FROM organization_memberships memberships
                        JOIN users ON users.id = memberships.user_id
                        WHERE memberships.organization_id = '%s'
                          AND lower(users.email) = lower('%s')
                          AND memberships.role_key = 'organization_viewer'
                          AND memberships.status = 'active'
                        """.formatted(ORG_ONE, invitedEmail)))
                .isEqualTo(1);
        assertThat(migratorCount("""
                        SELECT count(*) FROM audit_events
                        WHERE subject_id = '%s'
                          AND event_name IN (
                              'identity.invitation.issued',
                              'identity.invitation.accepted')
                        """.formatted(invitationId)))
                .isEqualTo(2);
        assertThat(migratorCount("""
                        SELECT count(*) FROM outbox_events
                        WHERE aggregate_id = '%s'
                          AND event_name IN (
                              'identity.invitation.issued',
                              'identity.invitation.accepted')
                        """.formatted(invitationId)))
                .isEqualTo(2);
    }

    @Test
    void requiresTheExactAuthenticatedExistingAccountAndRevokesIdempotently() throws Exception {
        seedApprovedOwner();
        var session = enrollMfaAndAuthenticate(email, "198.51.100.94");
        var body = objectMapper.writeValueAsString(Map.of(
                "email", email,
                "displayName", "Existing Invitation Account",
                "roleKey", "organization_viewer",
                "reason", "Approved existing-account linkage"));

        var issued = browserIdempotentPost(
                        "/api/v1/organizations/" + ORG_ONE + "/invitations",
                        body,
                        "existing-link-" + UUID.randomUUID(),
                        csrf(session),
                        "198.51.100.94",
                        session)
                .andExpect(status().isCreated())
                .andReturn();
        var invitationId = objectMapper
                .readTree(issued.getResponse().getContentAsString())
                .get("invitationId")
                .stringValue();
        var rawToken = notifications.invitationTokenFor(email);

        browserPost(
                        "/api/v1/auth/invitation-acceptances",
                        objectMapper.writeValueAsString(Map.of("token", rawToken)),
                        csrf(),
                        "198.51.100.95")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("invited-account-authentication-required"));
        browserPost(
                        "/api/v1/auth/invitation-acceptances",
                        objectMapper.writeValueAsString(Map.of("token", rawToken)),
                        csrf(session),
                        "198.51.100.94",
                        session)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountLink").value("existing"))
                .andExpect(jsonPath("$.userId").value(userId.toString()));

        var secondEmail = "revoked+" + UUID.randomUUID() + "@rootopathy.test";
        var secondIssue = browserIdempotentPost(
                        "/api/v1/organizations/" + ORG_ONE + "/invitations",
                        objectMapper.writeValueAsString(Map.of(
                                "email", secondEmail,
                                "displayName", "Revoked Invitation",
                                "roleKey", "organization_viewer",
                                "reason", "Temporary access request")),
                        "revocation-issue-" + UUID.randomUUID(),
                        csrf(session),
                        "198.51.100.94",
                        session)
                .andExpect(status().isCreated())
                .andReturn();
        var secondInvitationId = objectMapper
                .readTree(secondIssue.getResponse().getContentAsString())
                .get("invitationId")
                .stringValue();
        var revokeBody = objectMapper.writeValueAsString(Map.of(
                "reason", "Onboarding request withdrawn"));
        var revokeKey = "invitation-revoke-" + UUID.randomUUID();
        for (var attempt = 0; attempt < 2; attempt++) {
            browserIdempotentPost(
                            "/api/v1/organizations/" + ORG_ONE + "/invitations/"
                                    + secondInvitationId + "/revocations",
                            revokeBody,
                            revokeKey,
                            csrf(session),
                            "198.51.100.94",
                            session)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("revoked"));
        }
        browserPost(
                        "/api/v1/auth/invitation-acceptances",
                        objectMapper.writeValueAsString(Map.of(
                                "token", notifications.invitationTokenFor(secondEmail),
                                "newPassword", "Revoked-Invitation-42")),
                        csrf(),
                        "198.51.100.96")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid-or-expired-invitation"));
        assertThat(migratorCount("""
                        SELECT count(*) FROM audit_events
                        WHERE subject_id = '%s'
                          AND event_name = 'identity.invitation.revoked'
                        """.formatted(secondInvitationId)))
                .isEqualTo(1);
    }

    @Test
    void readsReadinessAndUpdatesTheOrganizationProfileWithAtomicReplayEvidence()
            throws Exception {
        seedReferenceInviter();
        var login = login(email, PASSWORD, csrf(), "198.51.100.71")
                .andExpect(status().isOk())
                .andReturn();
        var session = requireCookie(login, SESSION_COOKIE);
        var profilePath = "/api/v1/organizations/" + ORG_ONE + "/profile";

        mockMvc.perform(get("/api/v1/organizations/" + ORG_ONE + "/setup-readiness")
                        .cookie(session))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        "Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.organizationId").value(ORG_ONE.toString()))
                .andExpect(jsonPath("$.catalogueVersion").value("m1-readiness-v1"))
                .andExpect(jsonPath("$.organizationRevision").isNumber())
                .andExpect(jsonPath("$.evaluatedAt").isString())
                .andExpect(jsonPath("$.expiresAt").isString())
                .andExpect(jsonPath("$.completedGates")
                        .value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.blockedGates")
                        .value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.warningGates").value(1))
                .andExpect(jsonPath("$.notApplicableGates").value(1))
                .andExpect(jsonPath("$.totalGates").value(15))
                .andExpect(jsonPath("$.activeMemberships")
                        .value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.facilityCount").value(1))
                .andExpect(jsonPath("$.draftFacilityCount").value(1))
                .andExpect(jsonPath("$.gates[0].key")
                        .value("organization.profile.complete"))
                .andExpect(jsonPath("$.gates[0].outcome").value("blocked"))
                .andExpect(jsonPath("$.gates[0].version").value("m1-readiness-v1"))
                .andExpect(jsonPath("$.gates[0].reasonCode")
                        .value("m1.readiness.profile_incomplete"))
                .andExpect(jsonPath("$.gates[4].key").value("access.final_owner"))
                .andExpect(jsonPath("$.gates[5].key").value("access.mfa_enforced"))
                .andExpect(jsonPath("$.gates[10].outcome").value("warning"))
                .andExpect(jsonPath("$.gates[11].outcome").value("not_applicable"))
                .andExpect(jsonPath("$.gates[14].key")
                        .value("platform.dependencies.ready"))
                .andExpect(jsonPath("$.gates[14].outcome").value("blocked"));

        var readinessViewerId = UUID.randomUUID();
        var readinessViewerEmail = "readiness.viewer+" + readinessViewerId + "@rootopathy.test";
        seedAccount(readinessViewerId, readinessViewerEmail, "Readiness Viewer");
        executeAsMigrator("""
                INSERT INTO organization_memberships
                    (organization_id, user_id, role_key, status)
                VALUES ('%s', '%s', 'organization_viewer', 'active')
                """.formatted(ORG_ONE, readinessViewerId));
        var readinessViewerSession = requireCookie(login(
                                readinessViewerEmail,
                                PASSWORD,
                                csrf(),
                                "198.51.100.72")
                        .andExpect(status().isOk())
                        .andReturn(),
                SESSION_COOKIE);
        mockMvc.perform(get("/api/v1/organizations/" + ORG_ONE + "/setup-readiness")
                        .cookie(readinessViewerSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gates[0].href").value("#/M1-07"))
                .andExpect(jsonPath("$.gates[4].href").value("#/M1-06"))
                .andExpect(jsonPath("$.gates[12].href").value("#/M1-06"));
        mockMvc.perform(get(profilePath).cookie(readinessViewerSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.editable").value(false));

        var initial = mockMvc.perform(get(profilePath).cookie(session))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        "Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(header().exists("ETag"))
                .andExpect(jsonPath("$.organizationId").value(ORG_ONE.toString()))
                .andExpect(jsonPath("$.tradingName").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.organizationType").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.locale").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.editable").value(true))
                .andReturn();
        var initialJson = objectMapper.readTree(initial.getResponse().getContentAsString());
        var initialEtag = initial.getResponse().getHeader("ETag");
        var initialLockVersion = initialJson.get("lockVersion").longValue();
        var updateBody = objectMapper.writeValueAsString(Map.of(
                "legalName", initialJson.get("legalName").stringValue(),
                "displayName", "Organization Profile " + userId,
                "tradingName", "ROOTOPATHY Synthetic Care",
                "organizationType", "care_network",
                "countryCode", initialJson.get("countryCode").stringValue(),
                "timezone", initialJson.get("timezone").stringValue(),
                "locale", "en-IN",
                "reason", "Approved organization identity review CARE-71"));
        var requestCsrf = csrf(session);

        var invalidTypeBody = objectMapper.writeValueAsString(Map.of(
                "legalName", initialJson.get("legalName").stringValue(),
                "displayName", "Organization Profile " + userId,
                "tradingName", "ROOTOPATHY Synthetic Care",
                "organizationType", "hospital",
                "countryCode", initialJson.get("countryCode").stringValue(),
                "timezone", initialJson.get("timezone").stringValue(),
                "locale", "en-IN",
                "reason", "Approved organization identity review CARE-71"));
        browserIdempotentPut(
                        profilePath,
                        invalidTypeBody,
                        initialEtag,
                        "profile-invalid-type-" + UUID.randomUUID(),
                        requestCsrf,
                        "198.51.100.71",
                        session)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("organization-profile-invalid"))
                .andExpect(jsonPath("$.errors[0].path").value("/organizationType"))
                .andExpect(jsonPath("$.errors[0].code").value("m1.field.enum"));

        var shortReasonBody = objectMapper.writeValueAsString(Map.of(
                "legalName", initialJson.get("legalName").stringValue(),
                "displayName", "Organization Profile " + userId,
                "tradingName", "ROOTOPATHY Synthetic Care",
                "organizationType", "care_network",
                "countryCode", initialJson.get("countryCode").stringValue(),
                "timezone", initialJson.get("timezone").stringValue(),
                "locale", "en-IN",
                "reason", "short"));
        browserIdempotentPut(
                        profilePath,
                        shortReasonBody,
                        initialEtag,
                        "profile-invalid-reason-" + UUID.randomUUID(),
                        requestCsrf,
                        "198.51.100.71",
                        session)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("organization-profile-invalid"))
                .andExpect(jsonPath("$.errors[0].path").value("/reason"))
                .andExpect(jsonPath("$.errors[0].code").value("m1.field.length"));

        browserIdempotentPut(
                        profilePath,
                        updateBody,
                        null,
                        "profile-precondition-" + UUID.randomUUID(),
                        requestCsrf,
                        "198.51.100.71",
                        session)
                .andExpect(status().isPreconditionRequired())
                .andExpect(jsonPath("$.code").value("profile-precondition-required"));

        var idempotencyKey = "profile-update-" + UUID.randomUUID();
        var updated = browserIdempotentPut(
                        profilePath,
                        updateBody,
                        initialEtag,
                        idempotencyKey,
                        requestCsrf,
                        "198.51.100.71",
                        session)
                .andExpect(status().isOk())
                .andExpect(header().exists("ETag"))
                .andExpect(jsonPath("$.displayName").value("Organization Profile " + userId))
                .andExpect(jsonPath("$.tradingName").value("ROOTOPATHY Synthetic Care"))
                .andExpect(jsonPath("$.organizationType").value("care_network"))
                .andExpect(jsonPath("$.locale").value("en-IN"))
                .andExpect(jsonPath("$.editable").value(true))
                .andExpect(jsonPath("$.lockVersion").value(initialLockVersion + 1))
                .andReturn();
        var updatedEtag = updated.getResponse().getHeader("ETag");
        assertThat(updatedEtag).isNotEqualTo(initialEtag);

        browserIdempotentPut(
                        profilePath,
                        updateBody,
                        initialEtag,
                        idempotencyKey,
                        requestCsrf,
                        "198.51.100.71",
                        session)
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", updatedEtag))
                .andExpect(jsonPath("$.lockVersion").value(initialLockVersion + 1));

        var changedReplayBody = objectMapper.writeValueAsString(Map.of(
                "legalName", initialJson.get("legalName").stringValue(),
                "displayName", "Reused key with different payload",
                "tradingName", "ROOTOPATHY Synthetic Care",
                "organizationType", "care_network",
                "countryCode", initialJson.get("countryCode").stringValue(),
                "timezone", initialJson.get("timezone").stringValue(),
                "locale", "en-IN",
                "reason", "Approved organization identity review CARE-71"));
        browserIdempotentPut(
                        profilePath,
                        changedReplayBody,
                        initialEtag,
                        idempotencyKey,
                        requestCsrf,
                        "198.51.100.71",
                        session)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("idempotency-key-reused"));

        browserIdempotentPut(
                        profilePath,
                        changedReplayBody,
                        initialEtag,
                        "profile-stale-" + UUID.randomUUID(),
                        requestCsrf,
                        "198.51.100.71",
                        session)
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.code").value("profile-stale-revision"));

        mockMvc.perform(get(profilePath).cookie(session))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", updatedEtag))
                .andExpect(jsonPath("$.lockVersion").value(initialLockVersion + 1));
        mockMvc.perform(get("/api/v1/organizations/" + ORG_ONE + "/setup-readiness")
                        .cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gates[0].outcome").value("complete"))
                .andExpect(jsonPath("$.gates[0].reasonCode")
                        .value("m1.readiness.profile_complete"));
        assertThat(migratorCount("""
                        SELECT count(*) FROM audit_events
                        WHERE organization_id = '%s' AND subject_id = '%s'
                          AND event_name = 'organization.profile.updated'
                        """.formatted(ORG_ONE, ORG_ONE)))
                .isEqualTo(1);
        assertThat(migratorCount("""
                        SELECT count(*) FROM audit_events
                        WHERE organization_id = '%s' AND subject_id = '%s'
                          AND event_name = 'organization.profile.updated'
                          AND payload -> 'changedFields' =
                              '["displayName", "locale", "organizationType", "tradingName"]'::jsonb
                        """.formatted(ORG_ONE, ORG_ONE)))
                .isEqualTo(1);
        assertThat(migratorCount("""
                        SELECT count(*) FROM outbox_events
                        WHERE organization_id = '%s' AND aggregate_id = '%s'
                          AND event_name = 'organization.profile.updated'
                        """.formatted(ORG_ONE, ORG_ONE)))
                .isEqualTo(1);
        assertThat(migratorCount("""
                        SELECT count(*) FROM organizations
                        WHERE id = '%s' AND updated_by = '%s'
                          AND lock_version = %d
                        """.formatted(ORG_ONE, userId, initialLockVersion + 1)))
                .isEqualTo(1);
    }

    @Test
    void governsOrganizationIdentifierDraftVerificationRevocationAndReadinessEvidence()
            throws Exception {
        seedApprovedOwner();
        var session = enrollMfaAndAuthenticate(email, "198.51.100.79");
        var collectionPath = "/api/v1/organizations/" + ORG_ONE + "/identifiers";
        var requestCsrf = csrf(session);
        var primaryValue = "REG-" + userId;
        var primaryCreateBody = identifierBody(
                primaryValue,
                true,
                "Approved primary registration intake CARE-79");

        mockMvc.perform(get(collectionPath).cookie(session))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        "Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.organizationId").value(ORG_ONE.toString()))
                .andExpect(jsonPath("$.canCreate").value(true))
                .andExpect(jsonPath("$.types[0].key").value("registration"))
                .andExpect(jsonPath("$.types[0].primaryRequired").value(true));

        var primaryCreateKey = "identifier-create-" + UUID.randomUUID();
        var created = browserIdempotentPost(
                        collectionPath,
                        primaryCreateBody,
                        primaryCreateKey,
                        requestCsrf,
                        "198.51.100.79",
                        session)
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(header().exists("ETag"))
                .andExpect(jsonPath("$.identifierType").value("registration"))
                .andExpect(jsonPath("$.value").value(primaryValue))
                .andExpect(jsonPath("$.isPrimary").value(true))
                .andExpect(jsonPath("$.status").value("draft"))
                .andExpect(jsonPath("$.verificationStatus").value("unverified"))
                .andExpect(jsonPath("$.availableActions[0]").value("edit"))
                .andExpect(jsonPath("$.availableActions[1]").value("verify"))
                .andReturn();
        var primaryJson = objectMapper.readTree(created.getResponse().getContentAsString());
        var primaryId = UUID.fromString(primaryJson.get("identifierId").stringValue());
        var primaryEtag = created.getResponse().getHeader("ETag");
        assertThat(primaryEtag).isEqualTo(
                "\"organization-identifier:" + primaryId + ":0\"");

        browserIdempotentPost(
                        collectionPath,
                        primaryCreateBody,
                        primaryCreateKey,
                        requestCsrf,
                        "198.51.100.79",
                        session)
                .andExpect(status().isCreated())
                .andExpect(header().string("ETag", primaryEtag))
                .andExpect(jsonPath("$.identifierId").value(primaryId.toString()));

        var updatedValue = primaryValue + "-UPDATED";
        var updateBody = identifierBody(
                updatedValue,
                true,
                "Corrected primary registration after source review CARE-79");
        browserIdempotentPut(
                        collectionPath + "/" + primaryId,
                        updateBody,
                        null,
                        "identifier-missing-precondition-" + UUID.randomUUID(),
                        requestCsrf,
                        "198.51.100.79",
                        session)
                .andExpect(status().isPreconditionRequired())
                .andExpect(jsonPath("$.code").value("identifier-precondition-required"));

        var updated = browserIdempotentPut(
                        collectionPath + "/" + primaryId,
                        updateBody,
                        primaryEtag,
                        "identifier-update-" + UUID.randomUUID(),
                        requestCsrf,
                        "198.51.100.79",
                        session)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.value").value(updatedValue))
                .andExpect(jsonPath("$.lockVersion").value(1))
                .andReturn();
        var updatedEtag = updated.getResponse().getHeader("ETag");

        var verificationBody = objectMapper.writeValueAsString(Map.of(
                "evidenceReference", "NPR-CASE-" + userId,
                "reason", "Authority verification completed for CARE-79"));
        var verifyKey = "identifier-verify-" + UUID.randomUUID();
        var verified = browserConditionalIdempotentPost(
                        collectionPath + "/" + primaryId + "/verifications",
                        verificationBody,
                        updatedEtag,
                        verifyKey,
                        requestCsrf,
                        "198.51.100.79",
                        session)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("verified"))
                .andExpect(jsonPath("$.verificationStatus").value("verified"))
                .andExpect(jsonPath("$.evidenceReference").value("NPR-CASE-" + userId))
                .andExpect(jsonPath("$.lockVersion").value(2))
                .andExpect(jsonPath("$.availableActions[0]").value("supersede"))
                .andReturn();
        var verifiedEtag = verified.getResponse().getHeader("ETag");

        browserConditionalIdempotentPost(
                        collectionPath + "/" + primaryId + "/verifications",
                        verificationBody,
                        updatedEtag,
                        verifyKey,
                        requestCsrf,
                        "198.51.100.79",
                        session)
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", verifiedEtag))
                .andExpect(jsonPath("$.lockVersion").value(2));

        browserConditionalIdempotentPost(
                        collectionPath + "/" + primaryId + "/revocations",
                        objectMapper.writeValueAsString(Map.of(
                                "reason", "Attempted primary revocation without replacement")),
                        verifiedEtag,
                        "identifier-primary-revoke-" + UUID.randomUUID(),
                        requestCsrf,
                        "198.51.100.79",
                        session)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code")
                        .value("identifier-primary-replacement-required"));

        browserIdempotentPost(
                        collectionPath,
                        identifierBody(
                                updatedValue,
                                false,
                                "Duplicate registration rejection test CARE-79"),
                        "identifier-duplicate-" + UUID.randomUUID(),
                        requestCsrf,
                        "198.51.100.79",
                        session)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("m1.duplicate"));

        var secondaryValue = "SECONDARY-" + userId;
        var secondaryCreated = browserIdempotentPost(
                        collectionPath,
                        identifierBody(
                                secondaryValue,
                                false,
                                "Approved secondary registration intake CARE-79"),
                        "identifier-secondary-create-" + UUID.randomUUID(),
                        requestCsrf,
                        "198.51.100.79",
                        session)
                .andExpect(status().isCreated())
                .andReturn();
        var secondaryJson = objectMapper.readTree(
                secondaryCreated.getResponse().getContentAsString());
        var secondaryId = UUID.fromString(secondaryJson.get("identifierId").stringValue());
        var secondaryDraftEtag = secondaryCreated.getResponse().getHeader("ETag");
        var secondaryVerified = browserConditionalIdempotentPost(
                        collectionPath + "/" + secondaryId + "/verifications",
                        objectMapper.writeValueAsString(Map.of(
                                "evidenceReference", "NPR-SECONDARY-" + userId,
                                "reason", "Secondary authority verification completed CARE-79")),
                        secondaryDraftEtag,
                        "identifier-secondary-verify-" + UUID.randomUUID(),
                        requestCsrf,
                        "198.51.100.79",
                        session)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableActions[0]").value("revoke"))
                .andReturn();
        var secondaryVerifiedEtag = secondaryVerified.getResponse().getHeader("ETag");
        browserConditionalIdempotentPost(
                        collectionPath + "/" + secondaryId + "/revocations",
                        objectMapper.writeValueAsString(Map.of(
                                "reason", "Secondary registration retired after review")),
                        secondaryVerifiedEtag,
                        "identifier-secondary-revoke-" + UUID.randomUUID(),
                        requestCsrf,
                        "198.51.100.79",
                        session)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("revoked"))
                .andExpect(jsonPath("$.availableActions.length()").value(0));

        var replacementCreated = browserIdempotentPost(
                        collectionPath,
                        identifierBody(
                                "REPLACEMENT-" + userId,
                                false,
                                "Approved replacement registration intake CARE-79"),
                        "identifier-replacement-create-" + UUID.randomUUID(),
                        requestCsrf,
                        "198.51.100.79",
                        session)
                .andExpect(status().isCreated())
                .andReturn();
        var replacementJson = objectMapper.readTree(
                replacementCreated.getResponse().getContentAsString());
        var replacementId = UUID.fromString(replacementJson.get("identifierId").stringValue());
        var replacementVerified = browserConditionalIdempotentPost(
                        collectionPath + "/" + replacementId + "/verifications",
                        objectMapper.writeValueAsString(Map.of(
                                "evidenceReference", "NPR-REPLACEMENT-" + userId,
                                "reason", "Replacement authority verification completed CARE-79")),
                        replacementCreated.getResponse().getHeader("ETag"),
                        "identifier-replacement-verify-" + UUID.randomUUID(),
                        requestCsrf,
                        "198.51.100.79",
                        session)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isPrimary").value(false))
                .andReturn();
        var replacementVerifiedEtag = replacementVerified.getResponse().getHeader("ETag");
        var supersedeBody = objectMapper.writeValueAsString(Map.of(
                "replacementId", replacementId,
                "replacementEtag", replacementVerifiedEtag,
                "reason", "Superseded registration after verified replacement CARE-79"));

        browserConditionalIdempotentPost(
                        collectionPath + "/" + primaryId + "/supersessions",
                        objectMapper.writeValueAsString(Map.of(
                                "replacementEtag", replacementVerifiedEtag,
                                "reason", "Rejected supersession without replacement identity CARE-79")),
                        verifiedEtag,
                        "identifier-supersede-missing-replacement-" + UUID.randomUUID(),
                        requestCsrf,
                        "198.51.100.79",
                        session)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("organization-identifier-invalid"))
                .andExpect(jsonPath("$.errors[0].path").value("/replacementId"))
                .andExpect(jsonPath("$.errors[0].code").value("m1.field.required"));

        browserConditionalIdempotentPost(
                        collectionPath + "/" + primaryId + "/supersessions",
                        objectMapper.writeValueAsString(Map.of(
                                "replacementId", replacementId,
                                "replacementEtag",
                                        "\"organization-identifier:" + replacementId + ":0\"",
                                "reason", "Rejected supersession with stale replacement CARE-79")),
                        verifiedEtag,
                        "identifier-supersede-stale-replacement-" + UUID.randomUUID(),
                        requestCsrf,
                        "198.51.100.79",
                        session)
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.code").value("identifier-stale-revision"));

        var supersedeKey = "identifier-supersede-" + UUID.randomUUID();
        var superseded = browserConditionalIdempotentPost(
                        collectionPath + "/" + primaryId + "/supersessions",
                        supersedeBody,
                        verifiedEtag,
                        supersedeKey,
                        requestCsrf,
                        "198.51.100.79",
                        session)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.identifierId").value(primaryId.toString()))
                .andExpect(jsonPath("$.status").value("superseded"))
                .andExpect(jsonPath("$.lockVersion").value(3))
                .andExpect(jsonPath("$.availableActions.length()").value(0))
                .andReturn();
        var supersededEtag = superseded.getResponse().getHeader("ETag");

        browserConditionalIdempotentPost(
                        collectionPath + "/" + primaryId + "/supersessions",
                        supersedeBody,
                        verifiedEtag,
                        supersedeKey,
                        requestCsrf,
                        "198.51.100.79",
                        session)
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", supersededEtag))
                .andExpect(jsonPath("$.status").value("superseded"));
        assertThat(supersededEtag)
                .isEqualTo("\"organization-identifier:" + primaryId + ":3\"");

        mockMvc.perform(get(collectionPath).cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.items[0].identifierId").value(replacementId.toString()))
                .andExpect(jsonPath("$.items[0].status").value("verified"))
                .andExpect(jsonPath("$.items[0].isPrimary").value(true));
        mockMvc.perform(get("/api/v1/organizations/" + ORG_ONE + "/setup-readiness")
                        .cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gates[1].outcome").value("complete"))
                .andExpect(jsonPath("$.gates[1].reasonCode")
                        .value("m1.readiness.primary_identifier_verified"))
                .andExpect(jsonPath("$.gates[1].evidenceReferences[0]")
                        .value("required-identifier-type-count:1"))
                .andExpect(jsonPath("$.gates[1].evidenceReferences[1]")
                        .value("verified-primary-identifier-count:1"));

        assertThat(migratorCount("""
                        SELECT count(*) FROM audit_events
                        WHERE organization_id = '%s'
                          AND subject_id IN ('%s', '%s', '%s')
                          AND event_name IN (
                               'organization.identifier.created',
                               'organization.identifier.updated',
                               'organization.identifier.verified',
                               'organization.identifier.revoked',
                               'organization.identifier.superseded')
                        """.formatted(ORG_ONE, primaryId, secondaryId, replacementId)))
                .isEqualTo(9);
        assertThat(migratorCount("""
                        SELECT count(*) FROM outbox_events
                        WHERE organization_id = '%s'
                          AND aggregate_id IN ('%s', '%s', '%s')
                          AND event_name IN (
                               'organization.identifier.created',
                               'organization.identifier.updated',
                               'organization.identifier.verified',
                               'organization.identifier.revoked',
                               'organization.identifier.superseded')
                        """.formatted(ORG_ONE, primaryId, secondaryId, replacementId)))
                .isEqualTo(9);
        assertThat(migratorCount("""
                        SELECT count(*) FROM organization_identifiers
                        WHERE organization_id = '%s' AND id = '%s'
                          AND supersedes_id = '%s' AND is_primary
                          AND status = 'verified' AND lock_version = 2
                        """.formatted(ORG_ONE, replacementId, primaryId)))
                .isEqualTo(1);
    }

    @Test
    void governsMaskedOrganizationContactsEffectiveRangesAndImmutableSupersessions()
            throws Exception {
        seedApprovedOwner();
        var session = enrollMfaAndAuthenticate(email, "198.51.100.80");
        var directoryPath = "/api/v1/organizations/" + ORG_ONE + "/contacts";
        var addressPath = "/api/v1/organizations/" + ORG_ONE + "/addresses";
        var requestCsrf = csrf(session);

        mockMvc.perform(get(directoryPath).cookie(session))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        "Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.organizationId").value(ORG_ONE.toString()))
                .andExpect(jsonPath("$.canCreate").value(true))
                .andExpect(jsonPath("$.addressTypes[0]").value("registered"))
                .andExpect(jsonPath("$.purposes[0].key").value("operational"))
                .andExpect(jsonPath("$.purposes[0].publicProjectionAllowed").value(false))
                .andExpect(jsonPath("$.addresses.length()").value(0))
                .andExpect(jsonPath("$.contacts.length()").value(0));

        var addressCreateKey = "address-create-" + UUID.randomUUID();
        var addressRequest = addressBody(
                "42 Care Street",
                "Approved registered-address intake for organization coverage");
        var createdAddress = browserIdempotentPost(
                        addressPath,
                        addressRequest,
                        addressCreateKey,
                        requestCsrf,
                        "198.51.100.80",
                        session)
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(header().exists("ETag"))
                .andExpect(jsonPath("$.addressType").value("registered"))
                .andExpect(jsonPath("$.addressLines[0]").value("42 Care Street"))
                .andExpect(jsonPath("$.validationStatus").value("validated"))
                .andExpect(jsonPath("$.isPrimary").value(true))
                .andExpect(jsonPath("$.status").value("active"))
                .andExpect(jsonPath("$.lockVersion").value(0))
                .andReturn();
        var addressJson = objectMapper.readTree(
                createdAddress.getResponse().getContentAsString());
        var addressId = UUID.fromString(addressJson.get("addressId").stringValue());
        var addressEtag = createdAddress.getResponse().getHeader("ETag");
        assertThat(addressEtag).isEqualTo("\"organization-address:" + addressId + ":0\"");

        browserIdempotentPost(
                        addressPath,
                        addressRequest,
                        addressCreateKey,
                        requestCsrf,
                        "198.51.100.80",
                        session)
                .andExpect(status().isCreated())
                .andExpect(header().string("ETag", addressEtag))
                .andExpect(jsonPath("$.addressId").value(addressId.toString()));

        mockMvc.perform(get("/api/v1/organizations/" + ORG_ONE + "/setup-readiness")
                        .cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gates[2].key")
                        .value("organization.contact.coverage"))
                .andExpect(jsonPath("$.gates[2].outcome").value("warning"))
                .andExpect(jsonPath("$.gates[2].reasonCode")
                        .value("m1.readiness.operational_contact_unverified"))
                .andExpect(jsonPath("$.gates[2].evidenceReferences[0]")
                        .value("current-registered-address-count:1"))
                .andExpect(jsonPath("$.gates[2].href").value("#/M1-09"));

        browserIdempotentPost(
                        addressPath,
                        addressBody(
                                "84 Overlap Avenue",
                                "Rejected overlapping primary registered address test"),
                        "address-overlap-" + UUID.randomUUID(),
                        requestCsrf,
                        "198.51.100.80",
                        session)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("m1.effective.overlap"));

        var rawContact = "CareOps+" + userId + "@Example.COM";
        var contactCreateKey = "contact-create-" + UUID.randomUUID();
        var contactRequest = contactBody(
                rawContact,
                "Approved primary operational contact intake for organization coverage");
        var createdContact = browserIdempotentPost(
                        directoryPath,
                        contactRequest,
                        contactCreateKey,
                        requestCsrf,
                        "198.51.100.80",
                        session)
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(header().exists("ETag"))
                .andExpect(jsonPath("$.channel").value("email"))
                .andExpect(jsonPath("$.purpose").value("operational"))
                .andExpect(jsonPath("$.maskedValue").value("C***@***.com"))
                .andExpect(jsonPath("$.value").doesNotExist())
                .andExpect(jsonPath("$.verificationStatus").value("unverified"))
                .andExpect(jsonPath("$.status").value("active"))
                .andExpect(jsonPath("$.lockVersion").value(0))
                .andReturn();
        assertThat(createdContact.getResponse().getContentAsString())
                .doesNotContain(rawContact)
                .doesNotContain("Example.COM");
        var contactJson = objectMapper.readTree(
                createdContact.getResponse().getContentAsString());
        var contactId = UUID.fromString(contactJson.get("contactId").stringValue());
        var contactEtag = createdContact.getResponse().getHeader("ETag");
        assertThat(contactEtag).isEqualTo("\"organization-contact:" + contactId + ":0\"");

        browserIdempotentPost(
                        directoryPath,
                        contactRequest,
                        contactCreateKey,
                        requestCsrf,
                        "198.51.100.80",
                        session)
                .andExpect(status().isCreated())
                .andExpect(header().string("ETag", contactEtag))
                .andExpect(jsonPath("$.contactId").value(contactId.toString()))
                .andExpect(jsonPath("$.value").doesNotExist());

        var verificationBody = objectMapper.writeValueAsString(Map.of(
                "reason", "Operational contact ownership verified through approved workflow"));
        browserIdempotentPost(
                        directoryPath + "/" + contactId + "/verifications",
                        verificationBody,
                        "contact-missing-precondition-" + UUID.randomUUID(),
                        requestCsrf,
                        "198.51.100.80",
                        session)
                .andExpect(status().isPreconditionRequired())
                .andExpect(jsonPath("$.code").value("contact-precondition-required"));

        var verifyKey = "contact-verify-" + UUID.randomUUID();
        var verifiedContact = browserConditionalIdempotentPost(
                        directoryPath + "/" + contactId + "/verifications",
                        verificationBody,
                        contactEtag,
                        verifyKey,
                        requestCsrf,
                        "198.51.100.80",
                        session)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maskedValue").value("C***@***.com"))
                .andExpect(jsonPath("$.value").doesNotExist())
                .andExpect(jsonPath("$.verificationStatus").value("verified"))
                .andExpect(jsonPath("$.lockVersion").value(1))
                .andReturn();
        var verifiedContactEtag = verifiedContact.getResponse().getHeader("ETag");

        browserConditionalIdempotentPost(
                        directoryPath + "/" + contactId + "/verifications",
                        verificationBody,
                        contactEtag,
                        verifyKey,
                        requestCsrf,
                        "198.51.100.80",
                        session)
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", verifiedContactEtag))
                .andExpect(jsonPath("$.lockVersion").value(1));

        browserConditionalIdempotentPost(
                        directoryPath + "/" + contactId + "/verifications",
                        verificationBody,
                        contactEtag,
                        "contact-stale-" + UUID.randomUUID(),
                        requestCsrf,
                        "198.51.100.80",
                        session)
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.code").value("contact-stale-revision"));

        mockMvc.perform(get("/api/v1/organizations/" + ORG_ONE + "/setup-readiness")
                        .cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gates[2].outcome").value("complete"))
                .andExpect(jsonPath("$.gates[2].reasonCode")
                        .value("m1.readiness.contact_coverage_complete"))
                .andExpect(jsonPath("$.gates[2].evidenceReferences[2]")
                        .value("verified-primary-operational-contact-count:1"));

        browserIdempotentPost(
                        directoryPath,
                        contactBody(
                                "overlap." + userId + "@example.com",
                                "Rejected overlapping primary operational contact test"),
                        "contact-overlap-" + UUID.randomUUID(),
                        requestCsrf,
                        "198.51.100.80",
                        session)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("m1.effective.overlap"));

        var replacementAddress = browserConditionalIdempotentPost(
                        addressPath + "/" + addressId + "/supersessions",
                        addressBody(
                                "108 Replacement Road",
                                "Registered address superseded through approved workflow"),
                        addressEtag,
                        "address-supersede-" + UUID.randomUUID(),
                        requestCsrf,
                        "198.51.100.80",
                        session)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.addressLines[0]").value("108 Replacement Road"))
                .andExpect(jsonPath("$.supersedesId").value(addressId.toString()))
                .andExpect(jsonPath("$.status").value("active"))
                .andExpect(jsonPath("$.lockVersion").value(0))
                .andReturn();
        var replacementAddressId = UUID.fromString(objectMapper
                .readTree(replacementAddress.getResponse().getContentAsString())
                .get("addressId")
                .stringValue());

        var replacementRawContact = "Replacement+" + userId + "@Example.COM";
        var replacementContactRequest = contactBody(
                replacementRawContact,
                "Operational contact superseded through approved workflow");
        var replacementContact = browserConditionalIdempotentPost(
                        directoryPath + "/" + contactId + "/supersessions",
                        replacementContactRequest,
                        verifiedContactEtag,
                        "contact-supersede-" + UUID.randomUUID(),
                        requestCsrf,
                        "198.51.100.80",
                        session)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.maskedValue").value("R***@***.com"))
                .andExpect(jsonPath("$.value").doesNotExist())
                .andExpect(jsonPath("$.verificationStatus").value("unverified"))
                .andExpect(jsonPath("$.supersedesId").value(contactId.toString()))
                .andExpect(jsonPath("$.status").value("active"))
                .andExpect(jsonPath("$.lockVersion").value(0))
                .andReturn();
        assertThat(replacementContact.getResponse().getContentAsString())
                .doesNotContain(replacementRawContact);
        var replacementContactJson = objectMapper.readTree(
                replacementContact.getResponse().getContentAsString());
        var replacementContactId = UUID.fromString(
                replacementContactJson.get("contactId").stringValue());
        var replacementContactEtag = replacementContact.getResponse().getHeader("ETag");

        browserConditionalIdempotentPost(
                        directoryPath + "/" + replacementContactId + "/verifications",
                        verificationBody,
                        replacementContactEtag,
                        "replacement-contact-verify-" + UUID.randomUUID(),
                        requestCsrf,
                        "198.51.100.80",
                        session)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verificationStatus").value("verified"))
                .andExpect(jsonPath("$.lockVersion").value(1));

        var directory = mockMvc.perform(get(directoryPath).cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.addresses.length()").value(2))
                .andExpect(jsonPath("$.contacts.length()").value(2))
                .andExpect(jsonPath("$.addresses[0].addressId")
                        .value(replacementAddressId.toString()))
                .andExpect(jsonPath("$.addresses[0].supersedesId")
                        .value(addressId.toString()))
                .andExpect(jsonPath("$.contacts[0].contactId")
                        .value(replacementContactId.toString()))
                .andExpect(jsonPath("$.contacts[0].maskedValue").value("R***@***.com"))
                .andExpect(jsonPath("$.contacts[0].value").doesNotExist())
                .andExpect(jsonPath("$.contacts[1].status").value("superseded"))
                .andReturn();
        assertThat(directory.getResponse().getContentAsString())
                .doesNotContain(rawContact)
                .doesNotContain(replacementRawContact)
                .doesNotContain("@example.com");

        assertThat(migratorCount("""
                        SELECT count(*) FROM organization_addresses
                        WHERE organization_id = '%s'
                          AND ((id = '%s' AND status = 'superseded' AND lock_version = 1)
                            OR (id = '%s' AND supersedes_id = '%s'
                                AND status = 'active' AND lock_version = 0))
                        """.formatted(
                        ORG_ONE, addressId, replacementAddressId, addressId)))
                .isEqualTo(2);
        assertThat(migratorCount("""
                        SELECT count(*) FROM organization_contacts
                        WHERE organization_id = '%s'
                          AND ((id = '%s' AND status = 'superseded'
                                AND verification_status = 'verified' AND lock_version = 2)
                            OR (id = '%s' AND supersedes_id = '%s'
                                AND status = 'active'
                                AND verification_status = 'verified' AND lock_version = 1))
                        """.formatted(
                        ORG_ONE, contactId, replacementContactId, contactId)))
                .isEqualTo(2);
        assertThat(migratorCount("""
                        SELECT count(*) FROM audit_events
                        WHERE organization_id = '%s'
                          AND subject_id IN ('%s', '%s', '%s', '%s')
                          AND event_name IN (
                              'organization.address.changed',
                              'organization.contact.changed')
                          AND (SELECT count(*) FROM jsonb_object_keys(payload)) = 4
                          AND NOT (payload ? 'value')
                          AND NOT (payload ? 'maskedValue')
                          AND payload::text NOT LIKE '%%@example.com%%'
                        """.formatted(
                        ORG_ONE,
                        addressId,
                        replacementAddressId,
                        contactId,
                        replacementContactId)))
                .isEqualTo(6);
        assertThat(migratorCount("""
                        SELECT count(*) FROM outbox_events
                        WHERE organization_id = '%s'
                          AND aggregate_id IN ('%s', '%s', '%s', '%s')
                          AND event_name IN (
                              'organization.address.changed',
                              'organization.contact.changed')
                          AND (SELECT count(*) FROM jsonb_object_keys(payload)) = 4
                          AND NOT (payload ? 'value')
                          AND NOT (payload ? 'maskedValue')
                          AND payload::text NOT LIKE '%%@example.com%%'
                        """.formatted(
                        ORG_ONE,
                        addressId,
                        replacementAddressId,
                        contactId,
                        replacementContactId)))
                .isEqualTo(6);
    }

    @Test
    void schedulesVersionedInternationalSettingsWithSafeEvidence() throws Exception {
        seedApprovedOwner();
        var session = enrollMfaAndAuthenticate(email, "198.51.100.81");
        var path = "/api/v1/organizations/" + ORG_ONE + "/international-settings";
        var initial = mockMvc.perform(get(path).cookie(session))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(header().exists("ETag"))
                .andExpect(jsonPath("$.organizationId").value(ORG_ONE.toString()))
                .andExpect(jsonPath("$.editable").value(true))
                .andExpect(jsonPath("$.canSchedule").value(true))
                .andExpect(jsonPath("$.weekStarts.length()").value(7))
                .andExpect(jsonPath("$.impactRules.length()").value(6))
                .andExpect(jsonPath("$.versions.length()").value(1))
                .andExpect(jsonPath("$.versions[0].source").value("organization_default"))
                .andExpect(jsonPath("$.versions[0].countryCode").value("IN"))
                .andExpect(jsonPath("$.versions[0].timezone").value("Asia/Kolkata"))
                .andExpect(jsonPath("$.versions[0].locale").value("en-IN"))
                .andExpect(jsonPath("$.versions[0].currencyCode").value("INR"))
                .andExpect(jsonPath("$.versions[0].lifecycle").value("default"))
                .andExpect(jsonPath("$.versions[0].formatPreview.localeLibraryDerived")
                        .value(true))
                .andReturn();
        var initialJson = objectMapper.readTree(initial.getResponse().getContentAsString());
        var initialEtag = initial.getResponse().getHeader("ETag");
        var initialRevision = initialJson.get("lockVersion").longValue();
        var effectiveFrom = Instant.now().plusSeconds(172800).toString();
        var requestCsrf = csrf(session);
        var body = objectMapper.writeValueAsString(Map.of(
                "countryCode", "IN",
                "timezone", "Asia/Kolkata",
                "locale", "en-GB",
                "language", "en",
                "currencyCode", "GBP",
                "weekStart", "MONDAY",
                "effectiveFrom", effectiveFrom,
                "reason", "Approved international settings change CARE-110"));

        browserIdempotentPut(
                        path,
                        body,
                        null,
                        "settings-precondition-" + UUID.randomUUID(),
                        requestCsrf,
                        "198.51.100.81",
                        session)
                .andExpect(status().isPreconditionRequired())
                .andExpect(jsonPath("$.code").value("settings-precondition-required"));

        var idempotencyKey = "settings-schedule-" + UUID.randomUUID();
        var scheduled = browserIdempotentPut(
                        path,
                        body,
                        initialEtag,
                        idempotencyKey,
                        requestCsrf,
                        "198.51.100.81",
                        session)
                .andExpect(status().isOk())
                .andExpect(header().exists("ETag"))
                .andExpect(jsonPath("$.canSchedule").value(false))
                .andExpect(jsonPath("$.lockVersion").value(initialRevision + 1))
                .andExpect(jsonPath("$.versions.length()").value(2))
                .andExpect(jsonPath("$.versions[0].lifecycle").value("scheduled"))
                .andExpect(jsonPath("$.versions[0].locale").value("en-GB"))
                .andExpect(jsonPath("$.versions[0].currencyCode").value("GBP"))
                .andExpect(jsonPath("$.versions[0].weekStart").value("MONDAY"))
                .andExpect(jsonPath("$.versions[1].lifecycle").value("active"))
                .andReturn();
        var scheduledEtag = scheduled.getResponse().getHeader("ETag");

        browserIdempotentPut(
                        path,
                        body,
                        initialEtag,
                        idempotencyKey,
                        requestCsrf,
                        "198.51.100.81",
                        session)
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", scheduledEtag));
        browserIdempotentPut(
                        path,
                        body.replace("GBP", "USD"),
                        initialEtag,
                        idempotencyKey,
                        requestCsrf,
                        "198.51.100.81",
                        session)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("idempotency-key-reused"));
        browserIdempotentPut(
                        path,
                        body,
                        initialEtag,
                        "settings-stale-" + UUID.randomUUID(),
                        requestCsrf,
                        "198.51.100.81",
                        session)
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.code").value("settings-stale-revision"));

        assertThat(migratorCount("""
                        SELECT count(*) FROM organization_international_settings
                        WHERE organization_id = '%s' AND lock_version = %d
                        """.formatted(ORG_ONE, initialRevision + 1)))
                .isEqualTo(2);
        assertThat(migratorCount("""
                        SELECT count(*) FROM audit_events
                        WHERE organization_id = '%s'
                          AND event_name = 'organization.settings.changed'
                          AND (SELECT count(*) FROM jsonb_object_keys(payload)) = 3
                          AND payload -> 'changedFields' =
                              '["currencyCode", "locale", "weekStart"]'::jsonb
                          AND NOT (payload ? 'reason')
                        """.formatted(ORG_ONE)))
                .isEqualTo(1);
        assertThat(migratorCount("""
                        SELECT count(*) FROM outbox_events
                        WHERE organization_id = '%s'
                          AND event_name = 'organization.settings.changed'
                          AND (SELECT count(*) FROM jsonb_object_keys(payload)) = 3
                        """.formatted(ORG_ONE)))
                .isEqualTo(1);
    }

    @Test
    void governsConfidentialEffectiveGovernanceCoverage() throws Exception {
        seedApprovedOwner();
        var session = enrollMfaAndAuthenticate(email, "198.51.100.82");
        var path = "/api/v1/organizations/" + ORG_ONE + "/governance-responsibilities";
        var initial = mockMvc.perform(get(path).cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canManage").value(true))
                .andExpect(jsonPath("$.responsibilityTypes.length()").value(4))
                .andExpect(jsonPath("$.eligibleAssignees.length()")
                        .value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.responsibilities.length()").value(0))
                .andReturn();
        var initialJson = objectMapper.readTree(initial.getResponse().getContentAsString());
        var membershipId = initialJson.get("eligibleAssignees").get(0).get("id").stringValue();
        var csrf = csrf(session);
        for (var type : List.of("clinical", "privacy", "security", "billing")) {
            var body = objectMapper.writeValueAsString(Map.of(
                    "responsibilityType", type,
                    "membershipId", membershipId,
                    "escalationEmail", type + ".escalation@rootopathy.test",
                    "effectiveFrom", Instant.now().toString(),
                    "reason", "Assign approved " + type + " governance responsibility"));
            browserIdempotentPost(
                            path,
                            body,
                            "governance-create-" + type + "-" + UUID.randomUUID(),
                            csrf,
                            "198.51.100.82",
                            session)
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.responsibilities.length()")
                            .value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)))
                    .andExpect(jsonPath("$.responsibilities[0].escalationEmailMasked")
                            .value(org.hamcrest.Matchers.containsString("***")));
        }
        mockMvc.perform(get("/api/v1/organizations/" + ORG_ONE + "/setup-readiness")
                        .cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gates[3].outcome").value("complete"))
                .andExpect(jsonPath("$.gates[3].evidenceReferences[0]")
                        .value("covered-governance-responsibility-type-count:4"));
        assertThat(migratorCount("""
                        SELECT count(*) FROM audit_events
                        WHERE organization_id='%s'
                          AND event_name='organization.governance.changed'
                          AND (SELECT count(*) FROM jsonb_object_keys(payload))=5
                          AND NOT (payload ? 'escalationEmail')
                        """.formatted(ORG_ONE)))
                .isEqualTo(4);
        assertThat(migratorCount("""
                        SELECT count(*) FROM outbox_events
                        WHERE organization_id='%s'
                          AND event_name='organization.governance.changed'
                        """.formatted(ORG_ONE)))
                .isEqualTo(4);
    }

    @Test
    void createsAndFiltersGovernedFacilityDraftsWithExactEvidence() throws Exception {
        seedApprovedOwner();
        var session = enrollMfaAndAuthenticate(email, "198.51.100.83");
        var path = "/api/v1/organizations/" + ORG_ONE + "/facilities";
        mockMvc.perform(get(path).cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.organizationId").value(ORG_ONE.toString()))
                .andExpect(jsonPath("$.canCreate").value(true))
                .andExpect(jsonPath("$.facilityTypes[0].key").value("care_site"))
                .andExpect(jsonPath("$.facilities.length()").value(1))
                .andExpect(jsonPath("$.facilities[0].legalName").isString())
                .andExpect(jsonPath("$.facilities[0].facilityType").value("care_site"));

        var csrf = csrf(session);
        var key = "facility-create-" + UUID.randomUUID();
        var body = objectMapper.writeValueAsString(Map.of(
                "facilityCode", "CARE-01",
                "legalName", "Care One Facility Limited",
                "displayName", "Care One",
                "facilityType", "care_site",
                "timezone", "Asia/Kolkata",
                "reason", "Create approved facility draft CARE-120"));
        var created = browserIdempotentPost(path, body, key, csrf, "198.51.100.83", session)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.facilities.length()").value(2))
                .andExpect(jsonPath("$.facilities[0].facilityCode").value("CARE-01"))
                .andExpect(jsonPath("$.facilities[0].status").value("draft"))
                .andReturn();
        browserIdempotentPost(path, body, key, csrf, "198.51.100.83", session)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.facilities.length()").value(2));
        mockMvc.perform(get(path).cookie(session).param("query", "care one").param("status", "draft"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.facilities.length()").value(1));
        mockMvc.perform(get(path).cookie(session).param("query", "missing"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.facilities.length()").value(0));
        var createdFacility = objectMapper.readTree(created.getResponse().getContentAsString()).path("facilities").get(0);
        var facilityId = createdFacility.path("facilityId").asText();
        var etag = "\"facility:" + facilityId + ":0\"";
        var updateKey = "facility-update-" + UUID.randomUUID();
        var updateBody = objectMapper.writeValueAsString(Map.of(
                "facilityCode", "CARE-01",
                "legalName", "Care One Facility Limited",
                "displayName", "Care One Updated",
                "facilityType", "care_site",
                "timezone", "Asia/Kolkata",
                "reason", "Correct the approved facility draft display name"));
        browserIdempotentPut(path + "/" + facilityId, updateBody, etag, updateKey, csrf, "198.51.100.83", session)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.facilities[0].displayName").value("Care One Updated"))
                .andExpect(jsonPath("$.facilities[0].lockVersion").value(1));
        browserIdempotentPut(path + "/" + facilityId, updateBody, etag, updateKey, csrf, "198.51.100.83", session)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.facilities[0].lockVersion").value(1));
        browserIdempotentPut(path + "/" + facilityId, updateBody, etag,
                        "facility-update-stale-" + UUID.randomUUID(), csrf, "198.51.100.83", session)
                .andExpect(status().isPreconditionFailed());
        var addressId = UUID.randomUUID();
        executeAsMigrator("""
                INSERT INTO organization_addresses
                  (id,organization_id,address_type,address_line_1,locality,region,postcode,country_code,
                   validation_status,validation_source,is_primary,effective_from,status,created_by,updated_by)
                VALUES ('%s','%s','service','1 Care Street','Pune','Maharashtra','411001','IN',
                        'validated','integration-test',true,now()-interval '1 hour','active','%s','%s')
                """.formatted(addressId, ORG_ONE, userId, userId));
        var completeBody = objectMapper.writeValueAsString(Map.of(
                "facilityCode", "CARE-01",
                "legalName", "Care One Facility Limited",
                "displayName", "Care One Updated",
                "facilityType", "care_site",
                "addressId", addressId,
                "timezone", "Asia/Kolkata",
                "reason", "Attach the validated service address before submission"));
        browserIdempotentPut(path + "/" + facilityId, completeBody,
                        "\"facility:" + facilityId + ":1\"", "facility-complete-" + UUID.randomUUID(),
                        csrf, "198.51.100.83", session)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.facilities[0].lockVersion").value(2));
        var submitKey = "facility-submit-" + UUID.randomUUID();
        var submitBody = objectMapper.writeValueAsString(Map.of(
                "reason", "Submit the complete facility for independent review"));
        browserConditionalIdempotentPost(path + "/" + facilityId + "/submissions", submitBody,
                        "\"facility:" + facilityId + ":2\"", submitKey, csrf, "198.51.100.83", session)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.facilities[0].status").value("under_review"))
                .andExpect(jsonPath("$.facilities[0].lockVersion").value(3));
        browserConditionalIdempotentPost(path + "/" + facilityId + "/submissions", submitBody,
                        "\"facility:" + facilityId + ":2\"", submitKey, csrf, "198.51.100.83", session)
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/organizations/" + ORG_ONE + "/setup-readiness").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gates[6].outcome").value("complete"))
                .andExpect(jsonPath("$.gates[6].evidenceReferences[1]").value("draft-facility-count:1"));
        assertThat(migratorCount("""
                SELECT count(*) FROM audit_events WHERE organization_id='%s'
                  AND event_name='facility.created'
                  AND (SELECT count(*) FROM jsonb_object_keys(payload))=4
                  AND payload->>'fromState'='none' AND payload->>'toState'='draft'
                """.formatted(ORG_ONE))).isEqualTo(1);
        assertThat(migratorCount("""
                SELECT count(*) FROM outbox_events WHERE organization_id='%s'
                  AND event_name='facility.created'
                """.formatted(ORG_ONE))).isEqualTo(1);
        assertThat(migratorCount("""
                SELECT count(*) FROM audit_events WHERE organization_id='%s'
                  AND event_name='facility.updated'
                  AND (SELECT count(*) FROM jsonb_object_keys(payload))=4
                  AND payload->>'facilityId'='%s'
                  AND payload->>'fromState'='draft' AND payload->>'toState'='draft'
                  AND payload->>'lockVersion'='1'
                """.formatted(ORG_ONE, facilityId))).isEqualTo(1);
        assertThat(migratorCount("""
                SELECT count(*) FROM outbox_events WHERE organization_id='%s'
                  AND event_name='facility.updated'
                """.formatted(ORG_ONE))).isEqualTo(2);
        assertThat(migratorCount("""
                SELECT count(*) FROM audit_events WHERE organization_id='%s'
                  AND event_name='facility.submitted'
                  AND (SELECT count(*) FROM jsonb_object_keys(payload))=4
                  AND payload->>'facilityId'='%s'
                  AND payload->>'fromState'='draft' AND payload->>'toState'='under_review'
                  AND payload->>'lockVersion'='3'
                """.formatted(ORG_ONE, facilityId))).isEqualTo(1);
        assertThat(migratorCount("""
                SELECT count(*) FROM outbox_events WHERE organization_id='%s'
                  AND event_name='facility.submitted'
                """.formatted(ORG_ONE))).isEqualTo(1);
    }

    @Test
    void listsMinimumNecessaryMembershipPagesWithBoundCursorsAndServerActions()
            throws Exception {
        seedApprovedOwner();
        var search = "cursor" + userId.toString().replace("-", "").substring(0, 10);
        var firstTarget = UUID.randomUUID();
        var secondTarget = UUID.randomUUID();
        seedAccount(firstTarget, search + ".first@rootopathy.test", search + " First");
        seedAccount(secondTarget, search + ".second@rootopathy.test", search + " Second");
        executeAsMigrator("""
                INSERT INTO organization_memberships
                    (organization_id, user_id, role_key, status, effective_from)
                VALUES
                    ('%s', '%s', 'security_administrator', 'active', now() - interval '1 hour'),
                    ('%s', '%s', 'organization_viewer', 'active', now() - interval '2 hours')
                """.formatted(ORG_ONE, firstTarget, ORG_ONE, secondTarget));
        executeAsMigrator("""
                INSERT INTO mfa_methods
                    (id, user_id, method_type, status, encrypted_secret, verified_at)
                VALUES ('%s', '%s', 'totp', 'enabled', 'integration-test-secret', now())
                """.formatted(UUID.randomUUID(), firstTarget));

        var session = enrollMfaAndAuthenticate(email, "198.51.100.72");
        var path = "/api/v1/organizations/" + ORG_ONE + "/memberships";
        var firstPage = mockMvc.perform(get(path)
                        .cookie(session)
                        .param("search", search)
                        .param("state", "active")
                        .param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        "Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.organizationId").value(ORG_ONE.toString()))
                .andExpect(jsonPath("$.asOf").isString())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].userId").value(firstTarget.toString()))
                .andExpect(jsonPath("$.items[0].email")
                        .value(search + ".first@rootopathy.test"))
                .andExpect(jsonPath("$.items[0].mfaEnabled").value(true))
                .andExpect(jsonPath("$.items[0].lockVersion").value(0))
                .andExpect(jsonPath("$.items[0].availableActions[0]")
                        .value("requestMfaReset"))
                .andExpect(jsonPath("$.items[0].availableActions[1]")
                        .value("requestRoleChange"))
                .andExpect(jsonPath("$.items[0].availableActions[2]")
                        .value("requestRevocation"))
                .andExpect(jsonPath("$.items[0].availableActions[3]")
                        .value("requestOwnerTransfer"))
                .andExpect(jsonPath("$.page.limit").value(1))
                .andExpect(jsonPath("$.page.hasMore").value(true))
                .andExpect(jsonPath("$.page.nextCursor").isString())
                .andExpect(jsonPath("$.availableActions[0]").value("issueInvitation"))
                .andExpect(jsonPath("$.availableActions[1]")
                        .value("approveMembershipChange"))
                .andExpect(jsonPath("$.availableActions[2]")
                        .value("executeMembershipChange"))
                .andExpect(jsonPath("$.availableActions[3]")
                        .value("approveOwnerTransfer"))
                .andExpect(jsonPath("$.availableActions[4]")
                        .value("executeOwnerTransfer"))
                .andReturn();
        var cursor = objectMapper
                .readTree(firstPage.getResponse().getContentAsString())
                .get("page")
                .get("nextCursor")
                .stringValue();

        mockMvc.perform(get(path)
                        .cookie(session)
                        .param("search", search)
                        .param("state", "active")
                        .param("limit", "1")
                        .param("cursor", cursor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].userId").value(secondTarget.toString()))
                .andExpect(jsonPath("$.items[0].availableActions.length()").value(3))
                .andExpect(jsonPath("$.items[0].availableActions[0]")
                        .value("requestRoleChange"))
                .andExpect(jsonPath("$.items[0].availableActions[1]")
                        .value("requestRevocation"))
                .andExpect(jsonPath("$.items[0].availableActions[2]")
                        .value("requestOwnerTransfer"))
                .andExpect(jsonPath("$.page.hasMore").value(false))
                .andExpect(jsonPath("$.page.nextCursor").value(org.hamcrest.Matchers.nullValue()));

        var replacement = cursor.endsWith("A") ? "B" : "A";
        var tamperedCursor = cursor.substring(0, cursor.length() - 1) + replacement;
        mockMvc.perform(get(path)
                        .cookie(session)
                        .param("search", search)
                        .param("state", "active")
                        .param("limit", "1")
                        .param("cursor", tamperedCursor))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("membership-list-invalid"));

        mockMvc.perform(get(path)
                        .cookie(session)
                        .param("search", search)
                        .param("state", "scheduled")
                        .param("limit", "1")
                        .param("cursor", cursor))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("membership-list-invalid"));
        mockMvc.perform(get(path).cookie(session).param("unsupported", "value"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("membership-list-invalid"));
    }

    @Test
    void hidesMembershipPagesFromNonMembersAndRolesWithoutReadPermission()
            throws Exception {
        var viewerId = UUID.randomUUID();
        var viewerEmail = "membership-viewer+" + viewerId + "@rootopathy.test";
        seedAccount(viewerId, viewerEmail, "Membership Viewer");
        executeAsMigrator("""
                INSERT INTO organization_memberships
                    (organization_id, user_id, role_key, status, effective_from)
                VALUES ('%s', '%s', 'organization_viewer', 'active', now() - interval '1 hour')
                """.formatted(ORG_ONE, viewerId));
        var login = login(viewerEmail, PASSWORD, csrf(), "198.51.100.73")
                .andExpect(status().isOk())
                .andReturn();
        var session = requireCookie(login, SESSION_COOKIE);

        mockMvc.perform(get("/api/v1/organizations/" + ORG_ONE + "/memberships")
                        .cookie(session))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("resource-not-found"));
        mockMvc.perform(get("/api/v1/organizations/" + UUID.randomUUID() + "/memberships")
                        .cookie(session))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("resource-not-found"));
    }

    @Test
    void discoversAndSelectsOnlyLiveMembershipsAndIgnoresOrganizationHeaders() throws Exception {
        var unavailableOrganization = UUID.randomUUID();
        executeAsMigrator("""
                INSERT INTO organizations
                    (id, legal_name, display_name, organization_type,
                     country_code, timezone, locale, status)
                VALUES ('%s', 'Unavailable Test Organization', 'Unavailable Organization',
                        'care_provider', 'IN', 'Asia/Kolkata', 'en-IN', 'active')
                """.formatted(unavailableOrganization));
        executeAsMigrator("""
                INSERT INTO authorization_roles
                    (role_key, display_name, description, registry_version)
                VALUES
                    ('foundation-test-role', 'Foundation test role',
                     'Synthetic active membership discovery role', 'test-v1'),
                    ('suspended-test-role', 'Suspended test role',
                     'Synthetic suspended membership discovery role', 'test-v1')
                ON CONFLICT (role_key) DO NOTHING
                """);
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

    @Test
    void exposesMinimalDependencyAwareProbesAndKeepsMetricsBehindAuthentication() throws Exception {
        for (var path : new String[] {
            "/livez", "/readyz", "/actuator/health/liveness", "/actuator/health/readiness"
        }) {
            mockMvc.perform(get(path).header("X-Correlation-Id", "probe-42"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("X-Correlation-Id", "probe-42"))
                    .andExpect(jsonPath("$.status").value("UP"))
                    .andExpect(jsonPath("$.components").doesNotExist());
        }

        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("authentication-required"));
        mockMvc.perform(get("/actuator/prometheus")
                        .with(user("observability-test")
                                .authorities(new SimpleGrantedAuthority(AUTHENTICATED))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("application=\"careos-backend\"")))
                .andExpect(content().string(containsString("jvm_")));

        REDIS.getDockerClient().pauseContainerCmd(REDIS.getContainerId()).exec();
        try {
            mockMvc.perform(get("/readyz"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.status").value("DOWN"))
                    .andExpect(jsonPath("$.components").doesNotExist());
            mockMvc.perform(get("/livez"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("UP"));
        } finally {
            REDIS.getDockerClient().unpauseContainerCmd(REDIS.getContainerId()).exec();
        }
        mockMvc.perform(get("/readyz"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
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

    private org.springframework.test.web.servlet.ResultActions browserIdempotentPost(
            String path,
            String json,
            String idempotencyKey,
            CsrfMaterial csrf,
            String remoteAddress,
            Cookie... cookies)
            throws Exception {
        var builder = post(path)
                .header("Origin", ORIGIN)
                .header("Idempotency-Key", idempotencyKey)
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

    private org.springframework.test.web.servlet.ResultActions browserConditionalIdempotentPost(
            String path,
            String json,
            String ifMatch,
            String idempotencyKey,
            CsrfMaterial csrf,
            String remoteAddress,
            Cookie... cookies)
            throws Exception {
        var builder = post(path)
                .header("Origin", ORIGIN)
                .header("If-Match", ifMatch)
                .header("Idempotency-Key", idempotencyKey)
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

    private org.springframework.test.web.servlet.ResultActions browserIdempotentPut(
            String path,
            String json,
            String ifMatch,
            String idempotencyKey,
            CsrfMaterial csrf,
            String remoteAddress,
            Cookie... cookies)
            throws Exception {
        var builder = put(path)
                .header("Origin", ORIGIN)
                .header("Idempotency-Key", idempotencyKey)
                .header(csrf.headerName(), csrf.token())
                .cookie(csrf.cookie())
                .with(request -> {
                    request.setRemoteAddr(remoteAddress);
                    return request;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content(json);
        if (ifMatch != null) {
            builder.header("If-Match", ifMatch);
        }
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

    private String identifierBody(String value, boolean primary, String reason) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "identifierType", "registration",
                "assigningAuthority", "National Provider Registry",
                "value", value,
                "jurisdictionCountryCode", "IN",
                "isPrimary", primary,
                "issueDate", "2026-09-01",
                "expiryDate", "2036-09-01",
                "effectiveFrom", Instant.now().minusSeconds(3600).toString(),
                "effectiveTo", Instant.now().plusSeconds(315_360_000).toString(),
                "reason", reason));
    }

    private String addressBody(String firstLine, String reason) throws Exception {
        return objectMapper.writeValueAsString(Map.ofEntries(
                Map.entry("addressType", "registered"),
                Map.entry("addressLines", List.of(firstLine, "Clinical District")),
                Map.entry("locality", "Pune"),
                Map.entry("region", "Maharashtra"),
                Map.entry("postcode", "411001"),
                Map.entry("countryCode", "IN"),
                Map.entry("validationStatus", "validated"),
                Map.entry("validationSource", "National postal registry"),
                Map.entry("isPrimary", true),
                Map.entry("effectiveFrom", Instant.now().minusSeconds(3600).toString()),
                Map.entry("effectiveTo", Instant.now().plusSeconds(315_360_000).toString()),
                Map.entry("reason", reason)));
    }

    private String contactBody(String value, String reason) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "channel", "email",
                "purpose", "operational",
                "value", value,
                "isPrimary", true,
                "isPreferred", true,
                "effectiveFrom", Instant.now().minusSeconds(3600).toString(),
                "effectiveTo", Instant.now().plusSeconds(315_360_000).toString(),
                "reason", reason));
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

    private int migratorCount(String sql) throws Exception {
        try (var connection = java.sql.DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), MIGRATOR_USER, MIGRATOR_PASSWORD);
                var statement = connection.createStatement();
                var result = statement.executeQuery(sql)) {
            result.next();
            return result.getInt(1);
        }
    }

    private Cookie enrollMfaAndAuthenticate(String accountEmail, String remoteAddress)
            throws Exception {
        var loginResult = login(accountEmail, PASSWORD, csrf(), remoteAddress)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.state").value("mfa_enrollment_required"))
                .andReturn();
        var session = requireCookie(loginResult, SESSION_COOKIE);
        var sessionCsrf = csrf(session);
        var enrollment = browserPost(
                        "/api/v1/auth/mfa/enrollments",
                        "{\"label\":\"Approved M1 administrator assurance\"}",
                        sessionCsrf,
                        remoteAddress,
                        session)
                .andExpect(status().isOk())
                .andReturn();
        var secret = objectMapper
                .readTree(enrollment.getResponse().getContentAsString())
                .get("secret")
                .stringValue();
        var verification = browserPost(
                        "/api/v1/auth/mfa/enrollments/verification",
                        objectMapper.writeValueAsString(new CodeBody(currentTotp(secret))),
                        sessionCsrf,
                        remoteAddress,
                        session)
                .andExpect(status().isOk())
                .andReturn();
        var upgradedSession = verification.getResponse().getCookie(SESSION_COOKIE);
        if (upgradedSession == null) {
            upgradedSession = session;
        }
        var upgradedCsrf = csrf(upgradedSession);
        browserPost(
                        "/api/v1/auth/recent-authentications",
                        objectMapper.writeValueAsString(Map.of(
                                "password", PASSWORD,
                                "secondFactor", currentTotp(secret))),
                        upgradedCsrf,
                        remoteAddress,
                        upgradedSession)
                .andExpect(status().isNoContent());
        return upgradedSession;
    }

    private void seedApprovedOwner() throws Exception {
        executeAsMigrator("""
                INSERT INTO organization_memberships
                    (organization_id, user_id, role_key, status)
                VALUES ('%s', '%s', 'organization_owner', 'active')
                ON CONFLICT (organization_id, user_id, role_key) DO UPDATE
                    SET status = 'active', effective_from = now(), effective_to = NULL
                """.formatted(ORG_ONE, userId));
    }

    private void seedReferenceInviter() throws Exception {
        executeAsMigrator("""
                INSERT INTO organization_memberships
                    (organization_id, user_id, role_key, status)
                VALUES ('%s', '%s', 'local_bootstrap', 'active')
                ON CONFLICT (organization_id, user_id, role_key) DO UPDATE
                    SET status = 'active', effective_from = now(), effective_to = NULL
                """.formatted(ORG_ONE, userId));
    }

    private void seedAccount(UUID accountId, String accountEmail, String displayName) {
        jdbcTemplate.update(
                "insert into users (id, email, display_name, status) values (?, ?, ?, 'active')",
                accountId,
                accountEmail,
                displayName);
        jdbcTemplate.update(
                "insert into password_credentials (user_id, password_hash) values (?, ?)",
                accountId,
                passwordEncoder.encode(PASSWORD));
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
        private final Map<String, String> invitationTokens = new ConcurrentHashMap<>();
        private final Map<String, Integer> invitationCounts = new ConcurrentHashMap<>();
        private final Map<String, Integer> mfaResetCounts = new ConcurrentHashMap<>();
        private boolean failNext;

        @Override
        public void sendPasswordReset(String email, String rawToken, Instant expiresAt) {
            if (failNext) {
                failNext = false;
                throw new IllegalStateException("synthetic notification outage");
            }
            passwordResetTokens.put(email, rawToken);
        }

        @Override
        public void sendInvitation(String email, String rawToken, Instant expiresAt) {
            if (failNext) {
                failNext = false;
                throw new IllegalStateException("synthetic notification outage");
            }
            invitationTokens.put(email, rawToken);
            invitationCounts.merge(email, 1, Integer::sum);
        }

        @Override
        public void sendMfaAdministrativelyReset(String email) {
            if (failNext) {
                failNext = false;
                throw new IllegalStateException("synthetic notification outage");
            }
            mfaResetCounts.merge(email, 1, Integer::sum);
        }

        String tokenFor(String email) {
            return passwordResetTokens.get(email);
        }

        String invitationTokenFor(String email) {
            return invitationTokens.get(email);
        }

        int invitationCount(String email) {
            return invitationCounts.getOrDefault(email, 0);
        }

        int mfaResetCount(String email) {
            return mfaResetCounts.getOrDefault(email, 0);
        }

        void clear() {
            passwordResetTokens.clear();
            invitationTokens.clear();
            invitationCounts.clear();
            mfaResetCounts.clear();
            failNext = false;
        }

        void failNext() {
            failNext = true;
        }
    }
}
