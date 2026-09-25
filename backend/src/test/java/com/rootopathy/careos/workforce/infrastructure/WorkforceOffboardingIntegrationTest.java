package com.rootopathy.careos.workforce.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rootopathy.careos.tenancy.application.ServiceIdentityAuthorizationOperations;
import com.rootopathy.careos.tenancy.domain.OperationKey;
import com.rootopathy.careos.tenancy.domain.ServiceIdentityAuthorizationRequest;
import com.rootopathy.careos.workforce.application.WorkforceOffboardingStore;
import com.rootopathy.careos.workforce.application.WorkforceOffboardingWorkerService;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class WorkforceOffboardingIntegrationTest {
    private static final String MIGRATOR_USER = "careos_migrator";
    private static final String MIGRATOR_PASSWORD = "careos-migrator-test-only";
    private static final String APP_USER = "careos_app";
    private static final String APP_PASSWORD = "careos-app-test-only";
    private static final String SERVICE_CREDENTIAL =
            "M2_Offboarding-Worker-Integration-Credential-2026";
    private static final String SERVICE_CREDENTIAL_PEPPER =
            "CareOS-Test-M2-Offboarding-Pepper-Never-Production";
    private static final String CORRELATION_ID = "m2-offboarding-integration-001";

    private static final UUID ORGANIZATION_ID =
            UUID.fromString("01900000-0000-7000-8000-000000000001");
    private static final UUID MAKER_ID =
            UUID.fromString("01990000-0000-7000-8000-000000000101");
    private static final UUID CHECKER_ID =
            UUID.fromString("01990000-0000-7000-8000-000000000102");
    private static final UUID TARGET_USER_ID =
            UUID.fromString("01990000-0000-7000-8000-000000000103");
    private static final UUID PENDING_USER_ID =
            UUID.fromString("01990000-0000-7000-8000-000000000105");
    private static final UUID SERVICE_ID =
            UUID.fromString("01990000-0000-7000-8000-000000000104");
    private static final UUID PERSON_ID =
            UUID.fromString("01990000-0000-7000-8000-000000000201");
    private static final UUID PERSON_LINK_ID =
            UUID.fromString("01990000-0000-7000-8000-000000000202");
    private static final UUID MEMBER_ID =
            UUID.fromString("01990000-0000-7000-8000-000000000203");
    private static final UUID DIRECT_MEMBERSHIP_ID =
            UUID.fromString("01990000-0000-7000-8000-000000000301");
    private static final UUID SECOND_MEMBERSHIP_ID =
            UUID.fromString("01990000-0000-7000-8000-000000000302");
    private static final UUID PENDING_MEMBERSHIP_ID =
            UUID.fromString("01990000-0000-7000-8000-000000000303");
    private static final UUID ACCESS_SCOPE_ID =
            UUID.fromString("01990000-0000-7000-8000-000000000401");
    private static final UUID GRANT_REQUEST_ID =
            UUID.fromString("01990000-0000-7000-8000-000000000402");
    private static final UUID PENDING_ACCESS_SCOPE_ID =
            UUID.fromString("01990000-0000-7000-8000-000000000403");
    private static final UUID PENDING_GRANT_REQUEST_ID =
            UUID.fromString("01990000-0000-7000-8000-000000000404");
    private static final UUID REQUEST_ID =
            UUID.fromString("01990000-0000-7000-8000-000000000501");

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
    static void applicationProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> APP_USER);
        registry.add("spring.datasource.password", () -> APP_PASSWORD);
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", () -> MIGRATOR_USER);
        registry.add("spring.flyway.password", () -> MIGRATOR_PASSWORD);
        registry.add("spring.flyway.placeholders.applicationRole", () -> APP_USER);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("careos.service-identities.enabled", () -> true);
        registry.add(
                "careos.service-identities.credential-pepper",
                () -> SERVICE_CREDENTIAL_PEPPER);
    }

    @Autowired
    private WorkforceOffboardingWorkerService worker;

    @Autowired
    private WorkforceOffboardingStore store;

    @Autowired
    private ServiceIdentityAuthorizationOperations authorization;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Clock clock;

    private Instant effectiveAt;

    @BeforeEach
    void seedApprovedDuePlanWithOneDirectAndOneIndirectMembership() throws SQLException {
        effectiveAt = clock.instant().minusSeconds(60).truncatedTo(ChronoUnit.MICROS);
        var activatedAt = effectiveAt.minus(1, ChronoUnit.DAYS);
        var impactDigest = impactDigest(effectiveAt);

        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), MIGRATOR_USER, MIGRATOR_PASSWORD);
                var statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.executeUpdate("""
                    INSERT INTO users (id,email,display_name,status)
                    VALUES
                      ('%s','m2.offboarding.maker@example.test','Offboarding Maker','active'),
                      ('%s','m2.offboarding.checker@example.test','Offboarding Checker','active'),
                      ('%s','m2.offboarding.target@example.test','Offboarding Target','active'),
                      ('%s','m2.offboarding.pending@example.test','Pending Scope User','active')
                    """.formatted(MAKER_ID, CHECKER_ID, TARGET_USER_ID, PENDING_USER_ID));
            statement.executeUpdate("""
                    INSERT INTO person_profiles
                      (id,legal_given_name,legal_family_name,display_name,created_by,updated_by)
                    VALUES ('%s','Offboarding','Target','Offboarding Target','%s','%s')
                    """.formatted(PERSON_ID, MAKER_ID, MAKER_ID));
            statement.executeUpdate("""
                    INSERT INTO organization_person_links
                      (id,organization_id,person_id,display_label,relationship_status,
                       effective_from,status,created_by,updated_by)
                    VALUES ('%s','%s','%s','Offboarding Target','active','%s','active','%s','%s')
                    """.formatted(
                    PERSON_LINK_ID,
                    ORGANIZATION_ID,
                    PERSON_ID,
                    activatedAt,
                    MAKER_ID,
                    MAKER_ID));
            statement.executeUpdate("""
                    INSERT INTO workforce_members
                      (id,organization_id,organization_person_link_id,pathway,member_number,
                       account_access_intent,lifecycle_state,activated_at,status,created_by,updated_by)
                    VALUES ('%s','%s','%s','clinical','M2OFFBOARD001','existing_user',
                            'active','%s','active','%s','%s')
                    """.formatted(
                    MEMBER_ID,
                    ORGANIZATION_ID,
                    PERSON_LINK_ID,
                    activatedAt,
                    MAKER_ID,
                    MAKER_ID));
            statement.executeUpdate("""
                    INSERT INTO organization_memberships
                      (id,organization_id,user_id,role_key,status,effective_from,updated_by)
                    VALUES
                      ('%s','%s','%s','practitioner','active','%s','%s'),
                      ('%s','%s','%s','clinical_support_staff','active','%s','%s'),
                      ('%s','%s','%s','organization_viewer','active','%s','%s')
                    """.formatted(
                    DIRECT_MEMBERSHIP_ID,
                    ORGANIZATION_ID,
                    TARGET_USER_ID,
                    activatedAt,
                    CHECKER_ID,
                    SECOND_MEMBERSHIP_ID,
                    ORGANIZATION_ID,
                    TARGET_USER_ID,
                    activatedAt,
                    CHECKER_ID,
                    PENDING_MEMBERSHIP_ID,
                    ORGANIZATION_ID,
                    PENDING_USER_ID,
                    activatedAt,
                    CHECKER_ID));
            statement.executeUpdate("""
                    INSERT INTO access_assignment_scopes
                      (id,organization_id,access_assignment_id,workforce_member_id,
                       grant_request_id,effective_from,status,created_by,updated_by)
                    VALUES ('%s','%s','%s','%s','%s','%s','active','%s','%s')
                    """.formatted(
                    ACCESS_SCOPE_ID,
                    ORGANIZATION_ID,
                    DIRECT_MEMBERSHIP_ID,
                    MEMBER_ID,
                    GRANT_REQUEST_ID,
                    activatedAt,
                    MAKER_ID,
                    MAKER_ID));
            statement.executeUpdate("""
                    INSERT INTO access_assignment_scopes
                      (id,organization_id,access_assignment_id,workforce_member_id,
                       grant_request_id,effective_from,status,created_by,updated_by)
                    VALUES ('%s','%s','%s','%s','%s','%s','requested','%s','%s')
                    """.formatted(
                    PENDING_ACCESS_SCOPE_ID,
                    ORGANIZATION_ID,
                    PENDING_MEMBERSHIP_ID,
                    MEMBER_ID,
                    PENDING_GRANT_REQUEST_ID,
                    effectiveAt.plus(1, ChronoUnit.DAYS),
                    MAKER_ID,
                    MAKER_ID));
            statement.executeUpdate("""
                    INSERT INTO user_sessions
                      (session_id_hash,user_id,authenticated_at,recent_authentication_at,
                       absolute_expires_at,correlation_id)
                    VALUES ('%s','%s','%s','%s','%s','m2-offboarding-session')
                    """.formatted(
                    "d".repeat(64),
                    TARGET_USER_ID,
                    activatedAt,
                    activatedAt,
                    effectiveAt.plus(1, ChronoUnit.DAYS)));
            statement.executeUpdate("""
                    INSERT INTO service_identities
                      (id,organization_id,service_key,display_name,role_key,allowed_purposes,
                       status,active_from,provisioning_reference)
                    VALUES ('%s','%s','m2.offboarding.integration','M2 offboarding integration worker',
                            'service_m2_offboarding',ARRAY['m2-offboarding-worker-v1'],
                            'active','%s','M2-OFFBOARDING-INTEGRATION')
                    """.formatted(SERVICE_ID, ORGANIZATION_ID, activatedAt));
            statement.executeUpdate("""
                    INSERT INTO service_identity_credentials
                      (organization_id,service_identity_id,credential_version,credential_hash,
                       active_from,expires_at,provisioning_reference)
                    VALUES ('%s','%s',1,'%s','%s','%s','M2-OFFBOARDING-INTEGRATION')
                    """.formatted(
                    ORGANIZATION_ID,
                    SERVICE_ID,
                    serviceCredentialDigest(SERVICE_CREDENTIAL),
                    activatedAt,
                    effectiveAt.plus(30, ChronoUnit.DAYS)));

            statement.executeQuery(
                    "SELECT set_config('app.current_operation_key','workforce.offboarding.request',true)");
            statement.executeUpdate("""
                    INSERT INTO workforce_offboarding_requests
                      (id,organization_id,workforce_member_id,engagement_end_at,effective_at,
                       reason_entry_id,reason_version_id,impact_digest,maker_id,access_action,
                       assignment_action,service_action,status,created_by,updated_by)
                    SELECT '%s','%s','%s','%s','%s',entry.id,version.id,'%s','%s',
                           'revoke_at_effective','end_at_effective','end_at_effective',
                           'submitted','%s','%s'
                    FROM workforce_registry_definitions definition
                    JOIN workforce_registry_entries entry
                      ON entry.organization_id=definition.organization_id
                     AND entry.registry_definition_id=definition.id
                    JOIN workforce_registry_versions version
                      ON version.organization_id=entry.organization_id
                     AND version.registry_entry_id=entry.id
                    WHERE definition.organization_id='%s'
                      AND definition.category='offboarding_reason'
                      AND entry.status='active' AND version.status='active'
                    ORDER BY version.version_number DESC,version.id DESC
                    LIMIT 1
                    """.formatted(
                    REQUEST_ID,
                    ORGANIZATION_ID,
                    MEMBER_ID,
                    effectiveAt,
                    effectiveAt,
                    impactDigest,
                    MAKER_ID,
                    MAKER_ID,
                    MAKER_ID,
                    ORGANIZATION_ID));
            statement.executeQuery(
                    "SELECT set_config('app.current_operation_key','workforce.offboarding.approve',true)");
            statement.executeUpdate("""
                    UPDATE workforce_offboarding_requests
                    SET checker_id='%s',status='approved',lock_version=lock_version+1,
                        updated_at=clock_timestamp(),updated_by='%s'
                    WHERE organization_id='%s' AND id='%s' AND status='submitted'
                    """.formatted(CHECKER_ID, CHECKER_ID, ORGANIZATION_ID, REQUEST_ID));
            connection.commit();
        }
    }

    @Test
    void requiresExactRequestBindingAndAtomicEvidenceThenRevokesEveryLinkedRole() {
        assertThatThrownBy(() -> authorization.execute(authorizationRequest("unbound"), context -> {
                    jdbcTemplate.update(
                            """
                            UPDATE organization_memberships
                            SET status='revoked',effective_to=?,lock_version=lock_version+1,
                                updated_by=?
                            WHERE organization_id=? AND id=?
                            """,
                            Timestamp.from(effectiveAt),
                            CHECKER_ID,
                            ORGANIZATION_ID,
                            DIRECT_MEMBERSHIP_ID);
                    return null;
                }))
                .hasRootCauseInstanceOf(PSQLException.class)
                .rootCause()
                .hasMessageContaining("membership is outside the approved offboarding plan");

        assertThatThrownBy(() -> authorization.execute(authorizationRequest("incomplete"), context -> {
                    jdbcTemplate.queryForObject(
                            "SELECT set_config('app.current_offboarding_request_id',?,true)",
                            String.class,
                            REQUEST_ID.toString());
                    jdbcTemplate.update(
                            """
                            UPDATE workforce_offboarding_requests
                            SET status='completed',lock_version=lock_version+1,
                                updated_at=clock_timestamp(),updated_by=?
                            WHERE organization_id=? AND id=? AND status='approved'
                            """,
                            SERVICE_ID,
                            ORGANIZATION_ID,
                            REQUEST_ID);
                    return null;
                }))
                .hasRootCauseInstanceOf(PSQLException.class)
                .rootCause()
                .hasMessageContaining("completed offboarding is missing an atomic domain child effect");

        assertThatThrownBy(() -> authorization.execute(
                        authorizationRequest("store-preflight"),
                        context -> {
                            store.executeApproved(context, REQUEST_ID, clock.instant());
                            return null;
                        }))
                .hasRootCauseInstanceOf(PSQLException.class)
                .rootCause()
                .hasMessageContaining("completed offboarding lacks deterministic child evidence");

        var result = worker.execute(new WorkforceOffboardingWorkerService.Command(
                ORGANIZATION_ID, REQUEST_ID, SERVICE_CREDENTIAL, CORRELATION_ID));

        assertThat(result)
                .isEqualTo(new WorkforceOffboardingWorkerService.Result(REQUEST_ID, "completed"));
        var snapshot = authorization.execute(authorizationRequest("verify"), context -> new Snapshot(
                jdbcTemplate.queryForObject(
                        "SELECT status FROM workforce_offboarding_requests WHERE organization_id=? AND id=?",
                        String.class,
                        ORGANIZATION_ID,
                        REQUEST_ID),
                jdbcTemplate.queryForObject(
                        "SELECT lifecycle_state FROM workforce_members WHERE organization_id=? AND id=?",
                        String.class,
                        ORGANIZATION_ID,
                        MEMBER_ID),
                jdbcTemplate.queryForObject(
                        "SELECT offboarded_at FROM workforce_members WHERE organization_id=? AND id=?",
                        Timestamp.class,
                        ORGANIZATION_ID,
                        MEMBER_ID).toInstant(),
                jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM organization_memberships WHERE organization_id=? AND user_id=? AND status='revoked'",
                        Integer.class,
                        ORGANIZATION_ID,
                        TARGET_USER_ID),
                jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM organization_memberships WHERE organization_id=? AND user_id=? AND status='active'",
                        Integer.class,
                        ORGANIZATION_ID,
                        TARGET_USER_ID),
                jdbcTemplate.queryForObject(
                        "SELECT security_version FROM users WHERE id=?",
                        Long.class,
                        TARGET_USER_ID),
                jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM user_sessions WHERE user_id=? AND revoked_at IS NULL",
                        Integer.class,
                        TARGET_USER_ID),
                jdbcTemplate.queryForObject(
                        "SELECT status FROM access_assignment_scopes WHERE organization_id=? AND id=?",
                        String.class,
                        ORGANIZATION_ID,
                        ACCESS_SCOPE_ID),
                jdbcTemplate.queryForObject(
                        "SELECT status FROM access_assignment_scopes WHERE organization_id=? AND id=?",
                        String.class,
                        ORGANIZATION_ID,
                        PENDING_ACCESS_SCOPE_ID),
                jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM organization_memberships WHERE organization_id=? AND user_id=? AND status='active'",
                        Integer.class,
                        ORGANIZATION_ID,
                        PENDING_USER_ID),
                jdbcTemplate.queryForObject(
                        "SELECT security_version FROM users WHERE id=?",
                        Long.class,
                        PENDING_USER_ID),
                evidenceCount("audit_events", "identity.membership.revoked"),
                evidenceCount("outbox_events", "identity.membership.revoked"),
                evidenceCount("audit_events", "workforce.offboarding.started"),
                evidenceCount("audit_events", "workforce.offboarding.completed"),
                evidenceCount("outbox_events", "workforce.offboarding.completed")));

        assertThat(snapshot).isEqualTo(new Snapshot(
                "completed",
                "offboarded",
                effectiveAt,
                2,
                0,
                1L,
                0,
                "ended",
                "cancelled",
                1,
                0L,
                2,
                2,
                1,
                1,
                1));
    }

    private int evidenceCount(String table, String eventName) {
        if (!table.equals("audit_events") && !table.equals("outbox_events")) {
            throw new IllegalArgumentException("unsupported evidence table");
        }
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM " + table
                        + " WHERE organization_id=? AND event_name=? AND correlation_id=?",
                Integer.class,
                ORGANIZATION_ID,
                eventName,
                CORRELATION_ID);
    }

    private static ServiceIdentityAuthorizationRequest authorizationRequest(String suffix) {
        return new ServiceIdentityAuthorizationRequest(
                ORGANIZATION_ID,
                SERVICE_CREDENTIAL,
                "m2-offboarding-worker-v1",
                CORRELATION_ID + "-" + suffix,
                new OperationKey("m2.offboarding.execute"));
    }

    private static String impactDigest(Instant effectiveAt) {
        var membershipEvidence = DIRECT_MEMBERSHIP_ID + ":0:practitioner,"
                + SECOND_MEMBERSHIP_ID + ":0:clinical_support_staff";
        return sha256(MEMBER_ID
                + "|active|0|" + effectiveAt
                + "|" + effectiveAt
                + "|revoke_at_effective|end_at_effective|end_at_effective"
                + "|0|0|0|0|0|2|" + membershipEvidence
                + "|OffboardingAccessImpact[activeMemberships=2, finalOwnerMemberships=0, otherLiveMemberLinks=0]");
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String serviceCredentialDigest(String credential) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(
                    SERVICE_CREDENTIAL_PEPPER.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(credential.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private record Snapshot(
            String requestState,
            String memberState,
            Instant offboardedAt,
            int revokedMemberships,
            int activeMemberships,
            long securityVersion,
            int activeSessions,
            String accessScopeState,
            String pendingAccessScopeState,
            int pendingActiveMemberships,
            long pendingSecurityVersion,
            int membershipAudits,
            int membershipOutboxEvents,
            int startedAudits,
            int completedAudits,
            int completedOutboxEvents) {}
}
