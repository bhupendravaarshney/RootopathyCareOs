package com.rootopathy.careos.tenancy.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rootopathy.careos.governance.application.ConsumerInboxException;
import com.rootopathy.careos.governance.application.ConsumerInboxExecutor;
import com.rootopathy.careos.governance.application.ConsumerInboxOperations;
import com.rootopathy.careos.governance.application.GovernedMutationExecutor;
import com.rootopathy.careos.governance.application.IdempotencyException;
import com.rootopathy.careos.governance.application.OutboxDeliveryOperations;
import com.rootopathy.careos.governance.application.OutboxPublisherService;
import com.rootopathy.careos.governance.application.OutboxTransportException;
import com.rootopathy.careos.governance.domain.AuditRecord;
import com.rootopathy.careos.governance.domain.GovernanceEvidence;
import com.rootopathy.careos.governance.domain.GovernedMutation;
import com.rootopathy.careos.governance.domain.IdempotencyCommand;
import com.rootopathy.careos.governance.domain.IdempotentResponse;
import com.rootopathy.careos.governance.domain.InboundOutboxEvent;
import com.rootopathy.careos.governance.domain.OutboxPublicationPolicy;
import com.rootopathy.careos.governance.domain.OutboxRecord;
import com.rootopathy.careos.tenancy.application.ActorTransactionOperations;
import com.rootopathy.careos.tenancy.application.ServiceIdentityAuthorizationException;
import com.rootopathy.careos.tenancy.application.ServiceIdentityAuthorizationOperations;
import com.rootopathy.careos.tenancy.application.TenantAuthorizationException;
import com.rootopathy.careos.tenancy.application.TenantAuthorizationOperations;
import com.rootopathy.careos.tenancy.domain.AuthenticatedActorContext;
import com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext;
import com.rootopathy.careos.tenancy.domain.IndependentApproval;
import com.rootopathy.careos.tenancy.domain.OperationKey;
import com.rootopathy.careos.tenancy.domain.ServiceIdentityAuthorizationRequest;
import com.rootopathy.careos.tenancy.domain.TenantAuthorizationRequest;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class TenantRlsIntegrationTest {
    private static final String MIGRATOR_USER = "careos_migrator";
    private static final String MIGRATOR_PASSWORD = "careos-migrator-test-only";
    private static final String APP_USER = "careos_app";
    private static final String APP_PASSWORD = "careos-app-test-only";

    private static final UUID ORG_ONE = UUID.fromString("01900000-0000-7000-8000-000000000001");
    private static final UUID ORG_TWO = UUID.fromString("01900000-0000-7000-8000-000000000002");
    private static final UUID FACILITY_TWO = UUID.fromString("01900000-0000-7000-8000-000000000102");
    private static final UUID ACTOR = UUID.fromString("01900000-0000-7000-8000-000000000201");
    private static final UUID REFERENCE_ORGANIZATION =
            UUID.fromString("01900000-0000-7000-8000-000000000003");
    private static final UUID REFERENCE_OWNER =
            UUID.fromString("01900000-0000-7000-8000-000000000203");
    private static final UUID INVITATION_ONE = UUID.fromString("01900000-0000-7000-8000-000000000401");
    private static final UUID INVITATION_TWO = UUID.fromString("01900000-0000-7000-8000-000000000402");
    private static final String TEST_EVENT = "test.entity.changed";
    private static final String TEST_CONSUMER = "test.projection";
    private static final Instant SOURCE_OCCURRED_AT = Instant.parse("2026-09-15T08:30:00.123456Z");
    private static final String REQUEST_HASH_A = "a".repeat(64);
    private static final String REQUEST_HASH_B = "b".repeat(64);
    private static final String SERVICE_CREDENTIAL =
            "Service_Credential-For-Exact-Test-1234567890";
    private static final String SERVICE_CREDENTIAL_PEPPER =
            "CareOS-Test-Service-Credential-Pepper-Never-Production";
    private static final UUID SERVICE_IDENTITY =
            UUID.fromString("01900000-0000-7000-8000-000000000501");

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
    static void databaseProperties(DynamicPropertyRegistry registry) {
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
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TenantAuthorizationOperations tenantAuthorization;

    @Autowired
    private ServiceIdentityAuthorizationOperations serviceIdentityAuthorization;

    @Autowired
    private ActorTransactionOperations actorTransactions;

    @Autowired
    private GovernedMutationExecutor governedMutations;

    @Autowired
    private OutboxDeliveryOperations outboxDelivery;

    @Autowired
    private ConsumerInboxExecutor consumerInboxExecutor;

    @Autowired
    private ConsumerInboxOperations consumerInboxOperations;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private Clock clock;

    @BeforeEach
    void seedSecondTenantAndActor() throws SQLException {
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), MIGRATOR_USER, MIGRATOR_PASSWORD);
                var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO users (id, email, display_name, status)
                    VALUES ('01900000-0000-7000-8000-000000000201', 'rls.actor@rootopathy.test', 'RLS Test Actor', 'active')
                    ON CONFLICT (id) DO NOTHING
                    """);
            statement.executeUpdate("""
                    INSERT INTO organizations
                        (id, legal_name, display_name, organization_type,
                         country_code, timezone, locale, status)
                    VALUES ('01900000-0000-7000-8000-000000000002',
                            'Second Synthetic Care Org', 'Second Care Org',
                            'care_provider', 'IN', 'Asia/Kolkata', 'en-IN', 'active')
                    ON CONFLICT (id) DO NOTHING
                    """);
            statement.executeUpdate("""
                    INSERT INTO facilities (id, organization_id, name, code, status)
                    VALUES ('01900000-0000-7000-8000-000000000102', '01900000-0000-7000-8000-000000000002', 'Second Tenant Facility', 'SECOND-01', 'active')
                    ON CONFLICT (id) DO NOTHING
                    """);
            statement.executeUpdate("""
                    INSERT INTO authorization_permissions
                        (permission_key, display_name, description, registry_version)
                    VALUES
                        ('test.rls-access', 'RLS test access',
                         'Synthetic test-only permission', 'test-v1'),
                        ('test.ungranted', 'Ungranted test access',
                         'Synthetic ungranted permission', 'test-v1')
                    ON CONFLICT (permission_key) DO NOTHING
                    """);
            statement.executeUpdate("""
                    INSERT INTO authorization_roles
                        (role_key, display_name, description, registry_version)
                    VALUES ('test_actor', 'RLS test actor', 'Synthetic test-only role', 'test-v1')
                    ON CONFLICT (role_key) DO NOTHING
                    """);
            statement.executeUpdate("""
                    INSERT INTO authorization_role_permissions (role_key, permission_key)
                    VALUES ('test_actor', 'test.rls-access')
                    ON CONFLICT (role_key, permission_key) DO NOTHING
                    """);
            statement.executeUpdate("""
                    INSERT INTO authorization_operations
                        (operation_key, permission_key, display_name, description, registry_version)
                    VALUES
                        ('test.rls-access', 'test.rls-access', 'RLS test access',
                         'Synthetic test-only read operation', 'test-v1'),
                        ('test.entity.change', 'test.rls-access', 'Entity test change',
                         'Synthetic test-only governed mutation', 'test-v1')
                    ON CONFLICT (operation_key) DO NOTHING
                    """);
            statement.executeUpdate("""
                    INSERT INTO authorization_operations
                        (operation_key, permission_key, display_name, description,
                         mutation, denial_mode, reason_required,
                         recent_authentication_required,
                         recent_authentication_max_age_seconds, maximum_future_skew_seconds,
                         maker_checker_required, registry_version)
                    VALUES
                        ('test.reason-required', 'test.rls-access', 'Reason test',
                         'Synthetic reason requirement', true, 'explicit', true,
                         false, NULL, NULL, false, 'test-v1'),
                        ('test.recent-required', 'test.rls-access', 'Recent authentication test',
                         'Synthetic recent authentication requirement', true, 'explicit', false,
                         true, 300, 5, false, 'test-v1'),
                        ('test.approval-required', 'test.rls-access', 'Approval test',
                         'Synthetic independent approval requirement', true, 'explicit', true,
                         true, 300, 5, true, 'test-v1'),
                        ('test.hidden-denial', 'test.ungranted', 'Hidden denial test',
                         'Synthetic hidden denial behavior', false, 'hidden', false,
                         false, NULL, NULL, false, 'test-v1'),
                        ('test.explicit-denial', 'test.ungranted', 'Explicit denial test',
                         'Synthetic explicit denial behavior', false, 'explicit', false,
                         false, NULL, NULL, false, 'test-v1')
                    ON CONFLICT (operation_key) DO NOTHING
                    """);
            statement.executeUpdate("""
                    INSERT INTO organization_memberships (id, organization_id, user_id, role_key, status)
                    VALUES
                        ('01900000-0000-7000-8000-000000000301', '01900000-0000-7000-8000-000000000001', '01900000-0000-7000-8000-000000000201', 'test_actor', 'active'),
                        ('01900000-0000-7000-8000-000000000302', '01900000-0000-7000-8000-000000000002', '01900000-0000-7000-8000-000000000201', 'test_actor', 'active')
                    ON CONFLICT (id) DO NOTHING
                    """);
            statement.executeUpdate("""
                    INSERT INTO audit_event_definitions
                        (event_name, schema_version, display_name, description, subject_type,
                         reason_required, required_payload_keys, allowed_payload_keys,
                         payload_schema, registry_version)
                    VALUES ('test.entity.changed', 1, 'Synthetic entity changed',
                            'Synthetic test-only audit definition', 'test_entity', true,
                            ARRAY['change'], ARRAY['change'],
                            '{"type":"object"}'::jsonb, 'test-v1')
                    ON CONFLICT (event_name, schema_version) DO NOTHING
                    """);
            statement.executeUpdate("""
                    INSERT INTO outbox_event_definitions
                        (event_name, schema_version, description, aggregate_type,
                         required_payload_keys, allowed_payload_keys, payload_schema,
                         registry_version)
                    VALUES ('test.entity.changed', 1,
                            'Synthetic test-only outbox definition', 'test_entity',
                            ARRAY['change'], ARRAY['change'],
                            '{"type":"object"}'::jsonb, 'test-v1')
                    ON CONFLICT (event_name, schema_version) DO NOTHING
                    """);
            statement.executeUpdate("""
                    INSERT INTO authorization_operation_events
                        (operation_key, event_kind, event_name, schema_version,
                         status, registry_version)
                    VALUES
                        ('test.rls-access', 'audit', 'test.entity.changed', 1,
                         'active', 'test-v1'),
                        ('test.rls-access', 'outbox', 'test.entity.changed', 1,
                         'active', 'test-v1'),
                        ('test.entity.change', 'audit', 'test.entity.changed', 1,
                         'active', 'test-v1'),
                        ('test.entity.change', 'outbox', 'test.entity.changed', 1,
                         'active', 'test-v1')
                    ON CONFLICT (operation_key, event_kind, event_name, schema_version) DO NOTHING
                    """);
            statement.executeUpdate("""
                    INSERT INTO outbox_consumer_definitions
                        (consumer_key, event_name, schema_version, description, registry_version)
                    VALUES ('test.projection', 'test.entity.changed', 1,
                            'Synthetic test-only consumer definition', 'test-v1')
                    ON CONFLICT (consumer_key, event_name, schema_version) DO NOTHING
                    """);
            statement.executeUpdate("""
                    INSERT INTO invitations
                        (id, organization_id, email, display_name, role_key, token_hash,
                         expires_at, invited_by, correlation_id)
                    VALUES
                        ('01900000-0000-7000-8000-000000000401',
                         '01900000-0000-7000-8000-000000000001',
                         'one.invitation@rootopathy.test', 'One Invitation', 'test_actor',
                         'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                         now() + interval '1 day', '01900000-0000-7000-8000-000000000201', 'rls-seed-one'),
                        ('01900000-0000-7000-8000-000000000402',
                         '01900000-0000-7000-8000-000000000002',
                         'two.invitation@rootopathy.test', 'Two Invitation', 'test_actor',
                         'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',
                         now() + interval '1 day', '01900000-0000-7000-8000-000000000201', 'rls-seed-two')
                    ON CONFLICT (id) DO NOTHING
                    """);
        }
    }

    @Test
    void usesAnIsolatedDatabaseAndARestrictedRuntimeRole() {
        assertThat(jdbcTemplate.queryForObject("select current_database()", String.class))
                .isEqualTo("careos_test");

        Map<String, Object> role = jdbcTemplate.queryForMap("""
                SELECT current_user AS role_name, roles.rolsuper, roles.rolcreaterole, roles.rolbypassrls,
                       tables.tableowner
                FROM pg_roles roles
                JOIN pg_tables tables ON tables.schemaname = 'public' AND tables.tablename = 'facilities'
                WHERE roles.rolname = current_user
                """);
        assertThat(role.get("role_name")).isEqualTo(APP_USER);
        assertThat(role.get("rolsuper")).isEqualTo(false);
        assertThat(role.get("rolcreaterole")).isEqualTo(false);
        assertThat(role.get("rolbypassrls")).isEqualTo(false);
        assertThat(role.get("tableowner")).isNotEqualTo(APP_USER);
    }

    @Test
    void usesPostgresUuidV7ForEveryDatabaseGeneratedIdentifier() throws SQLException {
        var identifierDefaults = jdbcTemplate.query(
                """
                SELECT relations.relname,
                       pg_get_expr(defaults.adbin, defaults.adrelid) AS default_expression
                FROM pg_attrdef defaults
                JOIN pg_attribute columns
                  ON columns.attrelid = defaults.adrelid
                 AND columns.attnum = defaults.adnum
                JOIN pg_class relations ON relations.oid = defaults.adrelid
                JOIN pg_namespace schemas ON schemas.oid = relations.relnamespace
                WHERE schemas.nspname = 'public'
                  AND columns.attname = 'id'
                ORDER BY relations.relname
                """,
                (result, rowNumber) -> Map.entry(
                        result.getString("relname"), result.getString("default_expression")));
        assertThat(identifierDefaults)
                .containsExactly(
                        Map.entry("audit_events", "uuidv7()"),
                        Map.entry("authentication_events", "uuidv7()"),
                        Map.entry("authorization_approval_requests", "uuidv7()"),
                        Map.entry("facilities", "uuidv7()"),
                        Map.entry("idempotency_records", "uuidv7()"),
                        Map.entry("invitations", "uuidv7()"),
                        Map.entry("membership_change_requests", "uuidv7()"),
                        Map.entry("mfa_methods", "uuidv7()"),
                        Map.entry("organization_memberships", "uuidv7()"),
                        Map.entry("organizations", "uuidv7()"),
                        Map.entry("outbox_events", "uuidv7()"),
                        Map.entry("owner_transfer_requests", "uuidv7()"),
                        Map.entry("password_reset_tokens", "uuidv7()"),
                        Map.entry("recovery_codes", "uuidv7()"),
                        Map.entry("service_identities", "uuidv7()"),
                        Map.entry("service_identity_credentials", "uuidv7()"),
                        Map.entry("users", "uuidv7()"));

        try (var connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), MIGRATOR_USER, MIGRATOR_PASSWORD);
                var statement = connection.prepareStatement(
                        """
                        INSERT INTO users (email, display_name, status)
                        VALUES ('uuidv7.default@rootopathy.test', 'UUIDv7 Default', 'active')
                        RETURNING id
                        """)) {
            connection.setAutoCommit(false);
            try (var result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                var generated = result.getObject(1, UUID.class);
                assertThat(generated.version()).isEqualTo(7);
                assertThat(generated.variant()).isEqualTo(2);
            } finally {
                connection.rollback();
            }
        }
    }

    @Test
    void refusesTenantRowsWithoutContextAndClearsContextAfterCommit() {
        assertThat(facilityCodesWithoutContext()).isEmpty();

        assertThat(tenantAuthorization.execute(request(ORG_ONE), this::facilityCodes))
                .containsExactly("GNO-01");

        assertThat(facilityCodesWithoutContext()).isEmpty();
    }

    @Test
    void exposesOnlyTheSelectedTenantAcrossScopedTables() {
        assertThat(tenantAuthorization.execute(request(ORG_ONE), this::facilityCodes))
                .containsExactly("GNO-01");
        assertThat(tenantAuthorization.execute(request(ORG_TWO), this::facilityCodes))
                .containsExactly("SECOND-01");

        assertThat(tenantAuthorization.execute(
                        request(ORG_ONE),
                        () -> jdbcTemplate.queryForList("select id from organizations", UUID.class)))
                .containsExactly(ORG_ONE);
        assertThat(tenantAuthorization.execute(
                        request(ORG_TWO),
                        () -> jdbcTemplate.queryForObject(
                                "select count(*) from organization_memberships", Integer.class)))
                .isEqualTo(1);
    }

    @Test
    void rejectsAForgedCrossTenantInsert() {
        assertThatThrownBy(() -> tenantAuthorization.execute(request(ORG_ONE), (Runnable) () -> jdbcTemplate.update(
                        "insert into facilities (organization_id, name, code) values (?, ?, ?)",
                        ORG_TWO,
                        "Forged Facility",
                        "FORGED-01")))
                .hasRootCauseInstanceOf(PSQLException.class);
    }

    @Test
    void hidesCrossTenantRowsFromUpdates() {
        int changed = tenantAuthorization.execute(
                request(ORG_ONE),
                () -> jdbcTemplate.update("update facilities set name = ? where id = ?", "Compromised", FACILITY_TWO));

        assertThat(changed).isZero();
        assertThat(tenantAuthorization.execute(
                        request(ORG_TWO),
                        () -> jdbcTemplate.queryForObject(
                                "select name from facilities where id = ?", String.class, FACILITY_TWO)))
                .isEqualTo("Second Tenant Facility");
    }

    @Test
    void bindsActorPurposeAndCorrelationMetadataToTheTransaction() {
        Map<String, Object> settings = tenantAuthorization.execute(request(ORG_ONE), () -> jdbcTemplate.queryForMap("""
                SELECT current_setting('app.current_organization_id', true) AS organization_id,
                       current_setting('app.current_actor_id', true) AS actor_id,
                       current_setting('app.current_purpose', true) AS purpose,
                       current_setting('app.current_correlation_id', true) AS correlation_id
                """));

        assertThat(settings)
                .containsEntry("organization_id", ORG_ONE.toString())
                .containsEntry("actor_id", ACTOR.toString())
                .containsEntry("purpose", "rls-verification")
                .containsEntry("correlation_id", "rls-test-42");
    }

    @Test
    void permitsReadOnlyActorDiscoveryWithoutTurningItIntoTenantContext() {
        var actorContext = new AuthenticatedActorContext(
                ACTOR, "organization-selection", "actor-discovery-42");

        assertThat(actorTransactions.execute(
                        actorContext,
                        () -> jdbcTemplate.queryForList(
                                "select id from organizations order by id", UUID.class)))
                .containsExactly(ORG_ONE, ORG_TWO);
        assertThat(actorTransactions.execute(
                        actorContext,
                        () -> jdbcTemplate.queryForList(
                                "select organization_id from organization_memberships order by organization_id",
                                UUID.class)))
                .containsExactly(ORG_ONE, ORG_TWO);
        assertThat(actorTransactions.execute(
                        actorContext,
                        () -> jdbcTemplate.queryForList("select id from facilities", UUID.class)))
                .isEmpty();
        assertThat(jdbcTemplate.queryForList("select id from organizations", UUID.class)).isEmpty();
    }

    @Test
    void rejectsMembershipWritesFromActorDiscoveryMode() {
        var actorContext = new AuthenticatedActorContext(
                ACTOR, "organization-selection", "actor-write-attack-42");

        assertThatThrownBy(() -> actorTransactions.execute(actorContext, () -> jdbcTemplate.update(
                        """
                        INSERT INTO organization_memberships
                            (organization_id, user_id, role_key, status)
                        VALUES (?, ?, 'forged-role', 'active')
                        """,
                        ORG_ONE,
                        ACTOR)))
                .hasRootCauseInstanceOf(PSQLException.class);
    }

    @Test
    void failsClosedForUnknownOperationsAndActorsWithoutMembership() {
        var unknownOperation = new TenantAuthorizationRequest(
                ORG_ONE,
                new AuthenticatedActorContext(ACTOR, "rls-verification", "unknown-operation-42"),
                new OperationKey("test.not-approved"));
        assertThatThrownBy(() -> tenantAuthorization.execute(unknownOperation, this::facilityCodes))
                .isInstanceOf(TenantAuthorizationException.class)
                .extracting(exception -> ((TenantAuthorizationException) exception).reason())
                .isEqualTo(TenantAuthorizationException.Reason.PERMISSION_DENIED);

        var unknownActor = new TenantAuthorizationRequest(
                ORG_ONE,
                new AuthenticatedActorContext(UUID.randomUUID(), "rls-verification", "unknown-actor-42"),
                new OperationKey("test.rls-access"));
        assertThatThrownBy(() -> tenantAuthorization.execute(unknownActor, this::facilityCodes))
                .isInstanceOf(TenantAuthorizationException.class)
                .extracting(exception -> ((TenantAuthorizationException) exception).reason())
                .isEqualTo(TenantAuthorizationException.Reason.MEMBERSHIP_NOT_FOUND);
    }

    @Test
    void enforcesOperationReasonRecentAuthenticationApprovalAndDisclosureRules() {
        var actor = new AuthenticatedActorContext(ACTOR, "policy-verification", "policy-rules-42");

        assertAuthorizationReason(
                new TenantAuthorizationRequest(
                        ORG_ONE, actor, new OperationKey("test.reason-required")),
                TenantAuthorizationException.Reason.REASON_REQUIRED);
        assertThat(tenantAuthorization.execute(
                        new TenantAuthorizationRequest(
                                ORG_ONE,
                                actor,
                                new OperationKey("test.reason-required"),
                                "Verified test reason",
                                null),
                        this::facilityCodes))
                .containsExactly("GNO-01");

        assertAuthorizationReason(
                new TenantAuthorizationRequest(
                        ORG_ONE, actor, new OperationKey("test.recent-required")),
                TenantAuthorizationException.Reason.RECENT_AUTHENTICATION_REQUIRED);
        assertAuthorizationReason(
                new TenantAuthorizationRequest(
                        ORG_ONE,
                        actor,
                        new OperationKey("test.recent-required"),
                        null,
                        clock.instant().minus(Duration.ofMinutes(6))),
                TenantAuthorizationException.Reason.RECENT_AUTHENTICATION_REQUIRED);
        assertThat(tenantAuthorization.execute(
                        new TenantAuthorizationRequest(
                                ORG_ONE,
                                actor,
                                new OperationKey("test.recent-required"),
                                null,
                                clock.instant()),
                        this::facilityCodes))
                .containsExactly("GNO-01");

        assertAuthorizationReason(
                new TenantAuthorizationRequest(
                        ORG_ONE,
                        actor,
                        new OperationKey("test.approval-required"),
                        "Verified high-risk reason",
                        clock.instant()),
                TenantAuthorizationException.Reason.INDEPENDENT_APPROVAL_REQUIRED);
        assertAuthorizationReason(
                new TenantAuthorizationRequest(
                        ORG_ONE, actor, new OperationKey("test.hidden-denial")),
                TenantAuthorizationException.Reason.MEMBERSHIP_NOT_FOUND);
        assertAuthorizationReason(
                new TenantAuthorizationRequest(
                        ORG_ONE, actor, new OperationKey("test.explicit-denial")),
                TenantAuthorizationException.Reason.PERMISSION_DENIED);
    }

    @Test
    void activatesTheChecksumBoundPolicyAndKeepsReferenceAccessExplicitlyOptIn() throws SQLException {
        seedReferenceOwner();
        var request = new TenantAuthorizationRequest(
                REFERENCE_ORGANIZATION,
                new AuthenticatedActorContext(
                        REFERENCE_OWNER, "reference-policy-test", "reference-policy-42"),
                new OperationKey("organization.profile.read"));

        assertThat(tenantAuthorization.execute(
                        request,
                        () -> jdbcTemplate.queryForObject(
                                "select current_setting('app.current_operation_key', true)",
                                String.class)))
                .isEqualTo("organization.profile.read");

        assertThat(jdbcTemplate.queryForMap(
                        """
                        SELECT approval_record_id, approval_package_sha256,
                               authorization_artifact_sha256, approved_by, status
                        FROM authorization_registry_releases
                        WHERE registry_version = 'm1-candidate-1'
                        """))
                .containsEntry("approval_record_id", "M1-APPROVAL-20260916-01")
                .containsEntry(
                        "approval_package_sha256",
                        "19aff5ce30516b7ee2101c093a8429d8a74394995ca90d486790bcc18a392946")
                .containsEntry(
                        "authorization_artifact_sha256",
                        "3d65f85fc39d2ddcca0bcdce4fb43c1c8f4ff55902fbfc1a455669671e2c308b")
                .containsEntry("approved_by", "bhupendra, developer")
                .containsEntry("status", "active");
        assertThat(jdbcTemplate.queryForMap(
                        """
                        SELECT permission_key, mutation, denial_mode, reason_required,
                               recent_authentication_required, mfa_required,
                               maker_checker_required, status, registry_version
                        FROM authorization_operations
                        WHERE operation_key = 'access.membership.read'
                        """))
                .containsEntry("permission_key", "access.membership.read")
                .containsEntry("mutation", false)
                .containsEntry("denial_mode", "hidden")
                .containsEntry("reason_required", false)
                .containsEntry("recent_authentication_required", false)
                .containsEntry("mfa_required", false)
                .containsEntry("maker_checker_required", false)
                .containsEntry("status", "active")
                .containsEntry("registry_version", "m1-candidate-1");

        assertThat(jdbcTemplate.queryForList(
                        """
                        SELECT role_key
                        FROM authorization_roles
                        WHERE status = 'reference'
                        ORDER BY role_key
                        """,
                        String.class))
                .containsExactly(
                        "local_bootstrap",
                        "organization_member",
                        "service_integration_consumer",
                        "service_job_worker",
                        "service_notification_delivery",
                        "service_outbox_publisher",
                        "service_scheduler");
        assertThat(jdbcTemplate.queryForList(
                        """
                        SELECT role_key
                        FROM authorization_roles
                        WHERE mfa_required
                        ORDER BY role_key
                        """,
                        String.class))
                .containsExactly(
                        "auditor",
                        "configuration_approver",
                        "export_approver",
                        "organization_administrator",
                        "organization_owner",
                        "security_administrator");
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM authorization_operations WHERE status = 'reference'",
                        Integer.class))
                .isEqualTo(13);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM authorization_role_delegations", Integer.class))
                .isEqualTo(17);
        executeAsMigrator("""
                UPDATE organization_memberships
                SET role_key = 'local_bootstrap'
                WHERE organization_id = '01900000-0000-7000-8000-000000000003'
                  AND user_id = '01900000-0000-7000-8000-000000000203'
                """);
        assertAuthorizationReason(request, TenantAuthorizationException.Reason.MEMBERSHIP_NOT_FOUND);
        var referenceAuthorization = new PostgresTenantAuthorizationOperations(
                jdbcTemplate, transactionManager, clock, true);
        assertThat(referenceAuthorization.execute(request, () -> true)).isTrue();
        assertThatThrownBy(() -> jdbcTemplate.update(
                        "UPDATE authorization_operations SET status = 'active' WHERE status = 'reference'"))
                .hasRootCauseInstanceOf(PSQLException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                        "UPDATE authorization_registry_releases SET status = 'retired'"))
                .hasRootCauseInstanceOf(PSQLException.class)
                .rootCause()
                .hasMessageContaining("permission denied");
        assertThatThrownBy(() -> executeAsMigrator(
                        "UPDATE authorization_registry_releases SET status = 'retired'"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("migration-owned and immutable");
    }

    @Test
    void derivesMandatoryRoleMfaWithoutDisclosingMembershipsAndRejectsDirectDisable()
            throws SQLException {
        var mandatoryUser = UUID.randomUUID();
        var membershipId = UUID.randomUUID();
        var methodId = UUID.randomUUID();
        executeAsMigrator("""
                INSERT INTO users (id, email, display_name, status)
                VALUES ('%s', 'mandatory.mfa.%s@rootopathy.test',
                        'Mandatory MFA User', 'active');
                INSERT INTO organization_memberships
                    (id, organization_id, user_id, role_key, status)
                VALUES ('%s', '%s', '%s', 'security_administrator', 'active');
                INSERT INTO mfa_methods
                    (id, user_id, method_type, status, encrypted_secret, verified_at)
                VALUES ('%s', '%s', 'totp', 'enabled', 'protected-test-secret', now());
                """.formatted(
                mandatoryUser,
                mandatoryUser,
                membershipId,
                ORG_ONE,
                mandatoryUser,
                methodId,
                mandatoryUser));

        assertThat(jdbcTemplate.queryForObject(
                        "select careos_user_requires_mfa(?)", Boolean.class, mandatoryUser))
                .isTrue();
        assertThatThrownBy(() -> jdbcTemplate.queryForObject(
                        "select count(*) from identity_mfa_role_requirements", Integer.class))
                .hasRootCauseInstanceOf(PSQLException.class)
                .rootCause()
                .hasMessageContaining("permission denied");
        assertThatThrownBy(() -> jdbcTemplate.update(
                        "update mfa_methods set status = 'revoked', encrypted_secret = null, "
                                + "revoked_at = now() where id = ?",
                        methodId))
                .hasRootCauseInstanceOf(PSQLException.class)
                .rootCause()
                .hasMessageContaining("exact governed administrative reset");

        executeAsMigrator("""
                UPDATE organization_memberships
                SET role_key = 'organization_viewer'
                WHERE id = '%s'
                """.formatted(membershipId));
        assertThat(jdbcTemplate.queryForObject(
                        "select careos_user_requires_mfa(?)", Boolean.class, mandatoryUser))
                .isFalse();
    }

    @Test
    void protectsOrganizationProfileUpdatesAtTheDatabaseBoundary() throws SQLException {
        seedReferenceOwner();
        var approvedAuthorization = new PostgresTenantAuthorizationOperations(
                jdbcTemplate, transactionManager, clock, false);
        var readRequest = new TenantAuthorizationRequest(
                REFERENCE_ORGANIZATION,
                new AuthenticatedActorContext(
                        REFERENCE_OWNER, "organization-administration", "profile-read-attack-42"),
                new OperationKey("organization.profile.read"));

        assertThatThrownBy(() -> approvedAuthorization.execute(
                        readRequest,
                        () -> jdbcTemplate.update(
                                """
                                UPDATE organizations
                                SET display_name = 'Forged through read operation',
                                    updated_by = ?, lock_version = lock_version + 1
                                WHERE id = ?
                                """,
                                REFERENCE_OWNER,
                                REFERENCE_ORGANIZATION)))
                .rootCause()
                .isInstanceOf(PSQLException.class)
                .hasMessageContaining("authorized approved operation");

        var updateRequest = new TenantAuthorizationRequest(
                REFERENCE_ORGANIZATION,
                new AuthenticatedActorContext(
                        REFERENCE_OWNER, "organization-administration", "profile-update-db-42"),
                new OperationKey("organization.profile.update"),
                "Approved database-boundary organization profile test",
                null);
        var shortReasonRequest = new TenantAuthorizationRequest(
                REFERENCE_ORGANIZATION,
                new AuthenticatedActorContext(
                        REFERENCE_OWNER,
                        "organization-administration",
                        "profile-short-reason-db-42"),
                new OperationKey("organization.profile.update"),
                "short",
                null);
        assertThatThrownBy(() -> approvedAuthorization.execute(
                        shortReasonRequest,
                        () -> jdbcTemplate.update(
                                """
                                UPDATE organizations
                                SET display_name = 'Short reason attack',
                                    updated_by = ?, lock_version = lock_version + 1
                                WHERE id = ?
                                """,
                                REFERENCE_OWNER,
                                REFERENCE_ORGANIZATION)))
                .rootCause()
                .isInstanceOf(PSQLException.class)
                .hasMessageContaining("authorized approved operation");

        assertThatThrownBy(() -> approvedAuthorization.execute(
                        updateRequest,
                        () -> jdbcTemplate.update(
                                """
                                UPDATE organizations
                                SET status = 'suspended', display_name = 'Protected lifecycle attack',
                                    updated_by = ?, lock_version = lock_version + 1
                                WHERE id = ?
                                """,
                                REFERENCE_OWNER,
                                REFERENCE_ORGANIZATION)))
                .rootCause()
                .isInstanceOf(PSQLException.class)
                .hasMessageContaining("protected lifecycle data");

        assertThatThrownBy(() -> approvedAuthorization.execute(
                        updateRequest,
                        () -> jdbcTemplate.update(
                                """
                                UPDATE organizations
                                SET organization_type = 'hospital',
                                    updated_by = ?, lock_version = lock_version + 1
                                WHERE id = ?
                                """,
                                REFERENCE_OWNER,
                                REFERENCE_ORGANIZATION)))
                .rootCause()
                .isInstanceOf(PSQLException.class)
                .hasMessageContaining("organizations_type_check");

        assertThatThrownBy(() -> approvedAuthorization.execute(
                        updateRequest,
                        () -> jdbcTemplate.update(
                                """
                                UPDATE organizations
                                SET legal_name = '<script>invalid</script>',
                                    updated_by = ?, lock_version = lock_version + 1
                                WHERE id = ?
                                """,
                                REFERENCE_OWNER,
                                REFERENCE_ORGANIZATION)))
                .rootCause()
                .isInstanceOf(PSQLException.class)
                .hasMessageContaining("organizations_legal_name_check");

        assertThatThrownBy(() -> approvedAuthorization.execute(
                        updateRequest,
                        () -> jdbcTemplate.update(
                                """
                                UPDATE organizations
                                SET display_name = 'Care' || chr(769) || ' Network',
                                    updated_by = ?, lock_version = lock_version + 1
                                WHERE id = ?
                                """,
                                REFERENCE_OWNER,
                                REFERENCE_ORGANIZATION)))
                .rootCause()
                .isInstanceOf(PSQLException.class)
                .hasMessageContaining("organizations_display_name_check");

        assertThat(approvedAuthorization.execute(
                        updateRequest,
                        () -> jdbcTemplate.update(
                                """
                                UPDATE organizations
                                SET display_name = 'Reference Organization Governed',
                                    updated_by = ?, lock_version = lock_version + 1
                                WHERE id = ?
                                """,
                                REFERENCE_OWNER,
                                REFERENCE_ORGANIZATION)))
                .isEqualTo(1);
        assertThat(approvedAuthorization.execute(
                        new TenantAuthorizationRequest(
                                REFERENCE_ORGANIZATION,
                                updateRequest.actor(),
                                new OperationKey("organization.profile.read")),
                        () -> jdbcTemplate.queryForMap(
                                """
                                SELECT display_name, updated_by, lock_version
                                FROM organizations WHERE id = ?
                                """,
                                REFERENCE_ORGANIZATION)))
                .containsEntry("display_name", "Reference Organization Governed")
                .containsEntry("updated_by", REFERENCE_OWNER)
                .containsEntry("lock_version", 1L);
    }

    @Test
    void rejectsMembershipChangesOutsideTheExactConsumedApprovalContext()
            throws SQLException {
        seedReferenceOwner();
        executeAsMigrator("""
                INSERT INTO organization_memberships
                    (id, organization_id, user_id, role_key, status, effective_from)
                VALUES
                    ('01900000-0000-7000-8000-000000000305',
                     '01900000-0000-7000-8000-000000000003',
                     '01900000-0000-7000-8000-000000000204',
                     'organization_viewer', 'active', now())
                ON CONFLICT (id) DO UPDATE
                    SET role_key = 'organization_viewer', status = 'active',
                        effective_from = now(), effective_to = NULL,
                        lock_version = 0
                """);
        var readRequest = new TenantAuthorizationRequest(
                REFERENCE_ORGANIZATION,
                new AuthenticatedActorContext(
                        REFERENCE_OWNER,
                        "membership-administration",
                        "membership-read-write-attack-42"),
                new OperationKey("access.membership.read"));

        assertThatThrownBy(() -> tenantAuthorization.execute(
                        readRequest,
                        () -> jdbcTemplate.update(
                                """
                                UPDATE organization_memberships
                                SET role_key = 'configuration_editor', updated_by = ?,
                                    lock_version = lock_version + 1
                                WHERE id = '01900000-0000-7000-8000-000000000305'
                                """,
                                REFERENCE_OWNER)))
                .rootCause()
                .isInstanceOf(PSQLException.class)
                .hasMessageContaining("approved operation");

        assertThatThrownBy(() -> tenantAuthorization.execute(
                        readRequest,
                        () -> jdbcTemplate.update(
                                """
                                UPDATE organization_memberships
                                SET role_key = 'organization_owner', updated_by = ?,
                                    lock_version = lock_version + 1
                                WHERE id = '01900000-0000-7000-8000-000000000305'
                                """,
                                REFERENCE_OWNER)))
                .rootCause()
                .isInstanceOf(PSQLException.class)
                .hasMessageContaining("approved owner transfer operation");
    }

    @Test
    void enforcesTheMfaMakerCheckerLifecycleAtTheDatabaseBoundary() throws SQLException {
        var organizationId = UUID.randomUUID();
        var makerId = UUID.randomUUID();
        var checkerId = UUID.randomUUID();
        var targetId = UUID.randomUUID();
        var approvalId = UUID.randomUUID();
        executeAsMigrator("""
                INSERT INTO users (id, email, display_name, status)
                VALUES
                    ('%s', 'maker.%s@rootopathy.test', 'MFA Maker', 'active'),
                    ('%s', 'checker.%s@rootopathy.test', 'MFA Checker', 'active'),
                    ('%s', 'target.%s@rootopathy.test', 'MFA Target', 'active')
                """.formatted(makerId, makerId, checkerId, checkerId, targetId, targetId));
        executeAsMigrator("""
                INSERT INTO organizations
                    (id, legal_name, display_name, organization_type,
                     country_code, timezone, locale, status)
                VALUES ('%s', 'MFA Approval Test Organization',
                        'MFA Approval Test Organization', 'care_provider',
                        'IN', 'Asia/Kolkata', 'en-IN', 'active')
                """.formatted(organizationId));
        executeAsMigrator("""
                INSERT INTO organization_memberships
                    (organization_id, user_id, role_key, status)
                VALUES
                    ('%s', '%s', 'organization_owner', 'active'),
                    ('%s', '%s', 'organization_owner', 'active'),
                    ('%s', '%s', 'organization_owner', 'active')
                """.formatted(
                organizationId,
                makerId,
                organizationId,
                checkerId,
                organizationId,
                targetId));

        var approvedAuthorization = new PostgresTenantAuthorizationOperations(
                jdbcTemplate, transactionManager, clock, false);
        var requestReason = "Verified lost authenticator on support case CARE-42";
        var requestCorrelation = "mfa-db-request-42";
        assertAuthorizationReason(
                new TenantAuthorizationRequest(
                        organizationId,
                        new AuthenticatedActorContext(
                                makerId, "identity-administration", "mfa-db-no-mfa-42"),
                        new OperationKey("identity.mfa.admin-reset.request"),
                        requestReason,
                        clock.instant()),
                TenantAuthorizationException.Reason.MFA_REQUIRED);
        var requestAuthorization = new TenantAuthorizationRequest(
                organizationId,
                new AuthenticatedActorContext(
                        makerId, "identity-administration", requestCorrelation),
                new OperationKey("identity.mfa.admin-reset.request"),
                requestReason,
                clock.instant(),
                clock.instant(),
                null);
        assertThat(approvedAuthorization.execute(requestAuthorization, () -> jdbcTemplate.update(
                        """
                        INSERT INTO authorization_approval_requests
                            (id, organization_id, operation_key, subject_type, subject_id,
                             requested_by_user_id, request_reason,
                             request_correlation_id, expires_at)
                        VALUES (?, ?, 'identity.mfa.admin-reset.execute', 'user', ?, ?, ?, ?, ?)
                        """,
                        approvalId,
                        organizationId,
                        targetId,
                        makerId,
                        requestReason,
                        requestCorrelation,
                        Timestamp.from(clock.instant().plus(Duration.ofMinutes(10))))))
                .isEqualTo(1);

        var makerDecision = new TenantAuthorizationRequest(
                organizationId,
                new AuthenticatedActorContext(
                        makerId, "identity-administration", "mfa-db-maker-decision-42"),
                new OperationKey("identity.mfa.admin-reset.approve"),
                "Maker attempted self approval",
                clock.instant(),
                clock.instant(),
                null);
        assertThatThrownBy(() -> approvedAuthorization.execute(
                        makerDecision,
                        () -> approveMfaReset(
                                approvalId, makerId, makerDecision, clock.instant())))
                .rootCause()
                .isInstanceOf(PSQLException.class)
                .hasMessageContaining("invalid authorization approval decision");

        var targetDecision = new TenantAuthorizationRequest(
                organizationId,
                new AuthenticatedActorContext(
                        targetId, "identity-administration", "mfa-db-target-decision-42"),
                new OperationKey("identity.mfa.admin-reset.approve"),
                "Target attempted own reset approval",
                clock.instant(),
                clock.instant(),
                null);
        assertThatThrownBy(() -> approvedAuthorization.execute(
                        targetDecision,
                        () -> approveMfaReset(
                                approvalId, targetId, targetDecision, clock.instant())))
                .rootCause()
                .isInstanceOf(PSQLException.class)
                .hasMessageContaining("invalid authorization approval decision");

        var checkerDecision = new TenantAuthorizationRequest(
                organizationId,
                new AuthenticatedActorContext(
                        checkerId, "identity-administration", "mfa-db-checker-decision-42"),
                new OperationKey("identity.mfa.admin-reset.approve"),
                "Independent identity and support case verification completed",
                clock.instant(),
                clock.instant(),
                null);
        assertThat(approvedAuthorization.execute(
                        checkerDecision,
                        () -> approveMfaReset(
                                approvalId, checkerId, checkerDecision, clock.instant())))
                .isEqualTo(1);

        var executionKey = "mfa-db-execution-" + UUID.randomUUID();
        var checkerExecution = new TenantAuthorizationRequest(
                organizationId,
                new AuthenticatedActorContext(
                        checkerId, "identity-administration", "mfa-db-wrong-executor-42"),
                new OperationKey("identity.mfa.admin-reset.execute"),
                requestReason,
                clock.instant(),
                clock.instant(),
                new IndependentApproval(approvalId, "user", targetId, executionKey));
        assertThatThrownBy(() -> approvedAuthorization.execute(checkerExecution, () -> true))
                .isInstanceOf(TenantAuthorizationException.class)
                .extracting(exception -> ((TenantAuthorizationException) exception).reason())
                .isEqualTo(TenantAuthorizationException.Reason.INDEPENDENT_APPROVAL_REQUIRED);

        var makerExecution = new TenantAuthorizationRequest(
                organizationId,
                new AuthenticatedActorContext(
                        makerId, "identity-administration", "mfa-db-execution-42"),
                new OperationKey("identity.mfa.admin-reset.execute"),
                requestReason,
                clock.instant(),
                clock.instant(),
                new IndependentApproval(approvalId, "user", targetId, executionKey));
        for (var attempt = 0; attempt < 2; attempt++) {
            assertThat(approvedAuthorization.execute(
                            makerExecution,
                            () -> jdbcTemplate.queryForObject(
                                    """
                                    SELECT status FROM authorization_approval_requests
                                    WHERE id = ?
                                    """,
                                    String.class,
                                    approvalId)))
                    .isEqualTo("consumed");
        }
        var changedReasonExecution = new TenantAuthorizationRequest(
                organizationId,
                makerExecution.actor(),
                makerExecution.requiredOperation(),
                "A changed reason must not consume or replay approval",
                clock.instant(),
                clock.instant(),
                makerExecution.independentApproval());
        assertThatThrownBy(() -> approvedAuthorization.execute(changedReasonExecution, () -> true))
                .isInstanceOf(TenantAuthorizationException.class)
                .extracting(exception -> ((TenantAuthorizationException) exception).reason())
                .isEqualTo(TenantAuthorizationException.Reason.INDEPENDENT_APPROVAL_REQUIRED);
        assertThatThrownBy(() -> executeAsMigratorInTenant(
                        "DELETE FROM authorization_approval_requests WHERE id = '" + approvalId + "'",
                        makerExecution))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("approval evidence cannot be deleted");

        var expiredApprovalId = UUID.randomUUID();
        assertThat(approvedAuthorization.execute(requestAuthorization, () -> jdbcTemplate.update(
                        """
                        INSERT INTO authorization_approval_requests
                            (id, organization_id, operation_key, subject_type, subject_id,
                             requested_by_user_id, request_reason, request_correlation_id,
                             expires_at, created_at)
                        VALUES (?, ?, 'identity.mfa.admin-reset.execute', 'user', ?, ?, ?, ?,
                                clock_timestamp() - interval '10 minutes',
                                clock_timestamp() - interval '20 minutes')
                        """,
                        expiredApprovalId,
                        organizationId,
                        targetId,
                        makerId,
                        requestReason,
                        requestCorrelation)))
                .isEqualTo(1);
        var replacementApprovalId = UUID.randomUUID();
        assertThat(approvedAuthorization.execute(requestAuthorization, () -> {
                    var expired = jdbcTemplate.update(
                            """
                            UPDATE authorization_approval_requests
                            SET status = 'expired', updated_at = clock_timestamp(),
                                lock_version = lock_version + 1
                            WHERE id = ? AND status = 'pending'
                              AND expires_at <= clock_timestamp()
                            """,
                            expiredApprovalId);
                    var replacement = jdbcTemplate.update(
                            """
                            INSERT INTO authorization_approval_requests
                                (id, organization_id, operation_key, subject_type, subject_id,
                                 requested_by_user_id, request_reason,
                                 request_correlation_id, expires_at)
                            VALUES (?, ?, 'identity.mfa.admin-reset.execute', 'user', ?, ?, ?, ?, ?)
                            """,
                            replacementApprovalId,
                            organizationId,
                            targetId,
                            makerId,
                            requestReason,
                            requestCorrelation,
                            Timestamp.from(clock.instant().plus(Duration.ofMinutes(10))));
                    return List.of(expired, replacement);
                }))
                .containsExactly(1, 1);
    }

    @Test
    void authenticatesServiceIdentitiesOnlyForTheirExactTenantPurposeRoleAndOperation()
            throws SQLException {
        seedServiceIdentity();
        var request = new ServiceIdentityAuthorizationRequest(
                ORG_ONE,
                SERVICE_CREDENTIAL,
                "outbox-publication",
                "service-auth-42",
                new OperationKey("platform.outbox.publish"));

        assertThatThrownBy(() -> serviceIdentityAuthorization.execute(request, this::facilityCodes))
                .isInstanceOf(ServiceIdentityAuthorizationException.class)
                .extracting(exception ->
                        ((ServiceIdentityAuthorizationException) exception).reason())
                .isEqualTo(ServiceIdentityAuthorizationException.Reason.DISABLED);

        var withoutReferencePolicy = new PostgresServiceIdentityAuthorizationOperations(
                jdbcTemplate,
                transactionManager,
                new ServiceIdentityProperties(true, SERVICE_CREDENTIAL_PEPPER),
                false);
        assertServiceAuthorizationDenied(withoutReferencePolicy, request);

        var referenceAuthorization = new PostgresServiceIdentityAuthorizationOperations(
                jdbcTemplate,
                transactionManager,
                new ServiceIdentityProperties(true, SERVICE_CREDENTIAL_PEPPER),
                true);
        var settings = referenceAuthorization.execute(request, () -> jdbcTemplate.queryForMap("""
                SELECT current_setting('app.current_organization_id', true) AS organization_id,
                       current_setting('app.current_actor_id', true) AS actor_id,
                       current_setting('app.current_actor_kind', true) AS actor_kind,
                       current_setting('app.current_purpose', true) AS purpose,
                       current_setting('app.current_operation_key', true) AS operation_key
                """));
        assertThat(settings)
                .containsEntry("organization_id", ORG_ONE.toString())
                .containsEntry("actor_id", SERVICE_IDENTITY.toString())
                .containsEntry("actor_kind", "service")
                .containsEntry("purpose", "outbox-publication")
                .containsEntry("operation_key", "platform.outbox.publish");

        assertServiceAuthorizationDenied(
                referenceAuthorization,
                new ServiceIdentityAuthorizationRequest(
                        ORG_ONE,
                        "Wrong_Service-Credential-12345678901234567890",
                        "outbox-publication",
                        "service-wrong-secret-42",
                        new OperationKey("platform.outbox.publish")));
        assertServiceAuthorizationDenied(
                referenceAuthorization,
                new ServiceIdentityAuthorizationRequest(
                        ORG_ONE,
                        SERVICE_CREDENTIAL,
                        "notification-delivery",
                        "service-wrong-purpose-42",
                        new OperationKey("platform.outbox.publish")));
        assertServiceAuthorizationDenied(
                referenceAuthorization,
                new ServiceIdentityAuthorizationRequest(
                        ORG_ONE,
                        SERVICE_CREDENTIAL,
                        "outbox-publication",
                        "service-wrong-operation-42",
                        new OperationKey("platform.job.execute")));
        assertServiceAuthorizationDenied(
                referenceAuthorization,
                new ServiceIdentityAuthorizationRequest(
                        ORG_TWO,
                        SERVICE_CREDENTIAL,
                        "outbox-publication",
                        "service-wrong-tenant-42",
                        new OperationKey("platform.outbox.publish")));

        assertThat(jdbcTemplate.queryForMap("""
                SELECT has_table_privilege('service_identities', 'SELECT') AS identity_select,
                       has_table_privilege('service_identity_credentials', 'SELECT') AS credential_select,
                       has_table_privilege('service_identities', 'INSERT') AS identity_insert,
                       has_table_privilege('service_identity_credentials', 'UPDATE') AS credential_update
                """))
                .containsEntry("identity_select", false)
                .containsEntry("credential_select", false)
                .containsEntry("identity_insert", false)
                .containsEntry("credential_update", false);
        assertThat(request.toString()).contains("presentedCredential=<redacted>").doesNotContain(SERVICE_CREDENTIAL);
    }

    @Test
    void rejectsNonInteractiveRolesFromUserMemberships() {
        assertThatThrownBy(() -> executeAsMigrator("""
                        INSERT INTO organization_memberships
                            (organization_id, user_id, role_key, status)
                        VALUES
                            ('01900000-0000-7000-8000-000000000001',
                             '01900000-0000-7000-8000-000000000201',
                             'service_job_worker', 'active')
                        """))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("non-interactive roles cannot be assigned");
    }

    @Test
    void enforcesRegisteredRolesAndProtectsTheFinalEffectiveOwner() throws SQLException {
        seedReferenceOwner();

        assertThatThrownBy(() -> executeAsMigrator("""
                        INSERT INTO organization_memberships
                            (organization_id, user_id, role_key, status)
                        VALUES
                            ('01900000-0000-7000-8000-000000000003',
                             '01900000-0000-7000-8000-000000000204',
                             'unregistered-role', 'active')
                        """))
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> executeAsMigrator("""
                        UPDATE organization_memberships
                        SET status = 'revoked'
                        WHERE id = '01900000-0000-7000-8000-000000000303'
                        """))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("final effective organization owner");

        executeAsMigrator("""
                INSERT INTO organization_memberships
                    (id, organization_id, user_id, role_key, status)
                VALUES
                    ('01900000-0000-7000-8000-000000000304',
                     '01900000-0000-7000-8000-000000000003',
                     '01900000-0000-7000-8000-000000000204',
                     'organization_owner', 'active')
                """);
        executeAsMigrator("""
                UPDATE organization_memberships
                SET status = 'revoked'
                WHERE id = '01900000-0000-7000-8000-000000000303'
                """);
        assertThatThrownBy(() -> executeAsMigrator("""
                        UPDATE organization_memberships
                        SET effective_to = now() + interval '1 hour'
                        WHERE id = '01900000-0000-7000-8000-000000000304'
                        """))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("final effective organization owner");
    }

    @Test
    void preventsTheRuntimeRoleFromChangingAuthorizationPolicy() {
        assertThatThrownBy(() -> jdbcTemplate.update(
                        """
                        INSERT INTO authorization_permissions
                            (permission_key, display_name, description, registry_version)
                        VALUES ('forged.permission', 'Forged', 'Runtime policy attack', 'forged')
                        """))
                .hasRootCauseInstanceOf(PSQLException.class);

        assertThatThrownBy(() -> jdbcTemplate.update(
                        """
                        INSERT INTO audit_event_definitions
                            (event_name, schema_version, display_name, description,
                             subject_type, registry_version)
                        VALUES ('forged.audit', 1, 'Forged', 'Runtime policy attack',
                                'forged_subject', 'forged')
                        """))
                .hasRootCauseInstanceOf(PSQLException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                        """
                        UPDATE outbox_event_definitions
                        SET status = 'retired'
                        WHERE event_name = 'test.entity.changed' AND schema_version = 1
                        """))
                .hasRootCauseInstanceOf(PSQLException.class);
    }

    @Test
    void commitsBusinessWorkAuditOutboxAndReplayEvidenceExactlyOnce() {
        var aggregateId = UUID.randomUUID();
        var idempotencyKey = "test-" + UUID.randomUUID();
        var facilityCode = "GOV-" + aggregateId.toString().substring(0, 8);
        var authorizationRequest = request(ORG_ONE, "governed-mutation", "governed-once-42");
        var command = command(idempotencyKey, REQUEST_HASH_A);
        var executions = new AtomicInteger();

        var first = governedMutations.execute(authorizationRequest, command, context -> {
            executions.incrementAndGet();
            jdbcTemplate.update(
                    "insert into facilities (organization_id, name, code) values (?, ?, ?)",
                    ORG_ONE,
                    "Governed synthetic facility",
                    facilityCode);
            return mutation(aggregateId, "created");
        });
        var replay = governedMutations.execute(authorizationRequest, command, context -> {
            executions.incrementAndGet();
            throw new AssertionError("replayed work must not execute");
        });

        assertThat(first.replayed()).isFalse();
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.response()).isEqualTo(first.response());
        assertThat(executions).hasValue(1);
        assertThat(tenantAuthorization.execute(authorizationRequest, () -> jdbcTemplate.queryForObject(
                        "select count(*) from facilities where code = ?", Integer.class, facilityCode)))
                .isEqualTo(1);
        assertThat(evidenceCounts(authorizationRequest, aggregateId)).containsExactly(1, 1);
        assertThat(tenantAuthorization.execute(authorizationRequest, () -> jdbcTemplate.queryForObject(
                        "select count(*) from idempotency_records where idempotency_key = ?",
                        Integer.class,
                        idempotencyKey)))
                .isEqualTo(1);

        var conflictingCommand = new IdempotencyCommand(
                command.operationKey(), command.idempotencyKey(), REQUEST_HASH_B, command.expiresAt());
        assertThatThrownBy(() -> governedMutations.execute(
                        authorizationRequest,
                        conflictingCommand,
                        context -> mutation(aggregateId, "forged")))
                .isInstanceOf(IdempotencyException.class)
                .extracting(exception -> ((IdempotencyException) exception).reason())
                .isEqualTo(IdempotencyException.Reason.KEY_REUSED);
        assertThat(executions).hasValue(1);
    }

    @Test
    void rollsBackBusinessWorkAndIdempotencyWhenEvidenceIsNotApproved() {
        var aggregateId = UUID.randomUUID();
        var idempotencyKey = "test-" + UUID.randomUUID();
        var facilityCode = "ROLL-" + aggregateId.toString().substring(0, 8);
        var authorizationRequest = request(ORG_ONE, "governed-mutation", "governed-rollback-42");
        var command = command(idempotencyKey, REQUEST_HASH_A);

        assertThatThrownBy(() -> governedMutations.execute(authorizationRequest, command, context -> {
                    jdbcTemplate.update(
                            "insert into facilities (organization_id, name, code) values (?, ?, ?)",
                            ORG_ONE,
                            "Must roll back",
                            facilityCode);
                    return new GovernedMutation(
                            new IdempotentResponse(201, "application/json", "{\"result\":\"created\"}"),
                            new GovernanceEvidence(
                                    new AuditRecord(
                                            "test.unapproved",
                                            1,
                                            "test_entity",
                                            aggregateId,
                                            "Synthetic rollback proof",
                                            "{\"change\":\"created\"}"),
                                    new OutboxRecord(
                                            TEST_EVENT,
                                            1,
                                            "test_entity",
                                            aggregateId,
                                            "{\"change\":\"created\"}")));
                }))
                .hasRootCauseInstanceOf(PSQLException.class);

        assertThat(tenantAuthorization.execute(authorizationRequest, () -> jdbcTemplate.queryForObject(
                        "select count(*) from facilities where code = ?", Integer.class, facilityCode)))
                .isZero();
        assertThat(tenantAuthorization.execute(authorizationRequest, () -> jdbcTemplate.queryForObject(
                        "select count(*) from idempotency_records where idempotency_key = ?",
                        Integer.class,
                        idempotencyKey)))
                .isZero();
        assertThat(evidenceCounts(authorizationRequest, aggregateId)).containsExactly(0, 0);

        var retry = governedMutations.execute(authorizationRequest, command, context -> {
            jdbcTemplate.update(
                    "insert into facilities (organization_id, name, code) values (?, ?, ?)",
                    ORG_ONE,
                    "Rollback retry",
                    facilityCode);
            return mutation(aggregateId, "created");
        });
        assertThat(retry.replayed()).isFalse();
        assertThat(evidenceCounts(authorizationRequest, aggregateId)).containsExactly(1, 1);
    }

    @Test
    void serializesConcurrentRetriesWithoutRunningTheMutationTwice() throws Exception {
        var aggregateId = UUID.randomUUID();
        var authorizationRequest = request(ORG_ONE, "governed-mutation", "concurrent-retry-42");
        var command = command("test-" + UUID.randomUUID(), REQUEST_HASH_A);
        var executions = new AtomicInteger();
        var workEntered = new CountDownLatch(1);
        var secondStarted = new CountDownLatch(1);
        var releaseWork = new CountDownLatch(1);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> governedMutations.execute(authorizationRequest, command, context -> {
                executions.incrementAndGet();
                workEntered.countDown();
                await(releaseWork);
                return mutation(aggregateId, "concurrent");
            }));
            assertThat(workEntered.await(10, TimeUnit.SECONDS)).isTrue();
            var second = executor.submit(() -> {
                secondStarted.countDown();
                return governedMutations.execute(authorizationRequest, command, context -> {
                    executions.incrementAndGet();
                    return mutation(aggregateId, "duplicate");
                });
            });
            assertThat(secondStarted.await(10, TimeUnit.SECONDS)).isTrue();
            releaseWork.countDown();

            assertThat(List.of(first.get(10, TimeUnit.SECONDS).replayed(), second.get(10, TimeUnit.SECONDS).replayed()))
                    .containsExactlyInAnyOrder(false, true);
        }

        assertThat(executions).hasValue(1);
        assertThat(evidenceCounts(authorizationRequest, aggregateId)).containsExactly(1, 1);
    }

    @Test
    void rejectsPayloadSchemaDriftAndRequiresAnAuthorizedTransaction() {
        var aggregateId = UUID.randomUUID();
        var authorizationRequest = request(ORG_ONE, "governed-mutation", "payload-drift-42");

        assertThatThrownBy(() -> governedMutations.execute(
                        authorizationRequest,
                        command("test-" + UUID.randomUUID(), REQUEST_HASH_A),
                        context -> new GovernedMutation(
                                new IdempotentResponse(200, "application/json", "{}"),
                                new GovernanceEvidence(
                                        new AuditRecord(
                                                TEST_EVENT,
                                                1,
                                                "test_entity",
                                                aggregateId,
                                                "Schema drift attack",
                                                "{\"unexpected\":true}"),
                                        new OutboxRecord(
                                                TEST_EVENT,
                                                1,
                                                "test_entity",
                                                aggregateId,
                                                "{\"change\":\"updated\"}")))))
                .hasRootCauseInstanceOf(PSQLException.class);

        var forgedContext = new AuthorizedTenantContext(
                ORG_ONE, ACTOR, "governed-mutation", "outside-transaction-42");
        assertThatThrownBy(() -> outboxDelivery.claimDue(
                        forgedContext, "forged-worker", 1, Duration.ofSeconds(30)))
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("An active writable tenant transaction is required");
    }

    @Test
    void makesGovernanceEvidenceImmutableEvenForTheMigrationOwner() throws SQLException {
        var aggregateId = UUID.randomUUID();
        var idempotencyKey = "test-" + UUID.randomUUID();
        var authorizationRequest = request(ORG_ONE, "governed-mutation", "immutable-governance-42");
        governedMutations.execute(
                authorizationRequest,
                command(idempotencyKey, REQUEST_HASH_A),
                context -> mutation(aggregateId, "immutable"));

        var ids = tenantAuthorization.execute(authorizationRequest, () -> jdbcTemplate.queryForMap(
                """
                SELECT audit.id AS audit_id, outbox.id AS outbox_id, idempotency.id AS idempotency_id
                FROM audit_events audit
                JOIN outbox_events outbox ON outbox.aggregate_id = audit.subject_id
                JOIN idempotency_records idempotency ON idempotency.idempotency_key = ?
                WHERE audit.subject_id = ?
                """,
                idempotencyKey,
                aggregateId));
        var auditId = (UUID) ids.get("audit_id");
        var outboxId = (UUID) ids.get("outbox_id");
        var idempotencyId = (UUID) ids.get("idempotency_id");

        assertThatThrownBy(() -> executeAsMigratorInTenant(
                        "update audit_events set reason = 'changed' where id = '" + auditId + "'",
                        authorizationRequest))
                .isInstanceOf(PSQLException.class)
                .hasMessageContaining("audit_events are append-only");
        assertThatThrownBy(() -> executeAsMigratorInTenant(
                        "delete from audit_events where id = '" + auditId + "'",
                        authorizationRequest))
                .isInstanceOf(PSQLException.class)
                .hasMessageContaining("audit_events are append-only");
        assertThatThrownBy(() -> executeAsMigratorInTenant(
                        "update outbox_events set payload = '{\"change\":\"forged\"}' where id = '"
                                + outboxId + "'",
                        authorizationRequest))
                .isInstanceOf(PSQLException.class)
                .hasMessageContaining("outbox event content is immutable");
        assertThatThrownBy(() -> executeAsMigratorInTenant(
                        "delete from outbox_events where id = '" + outboxId + "'",
                        authorizationRequest))
                .isInstanceOf(PSQLException.class)
                .hasMessageContaining("outbox_events cannot be deleted");
        assertThatThrownBy(() -> executeAsMigratorInTenant(
                        "update idempotency_records set request_hash = '" + REQUEST_HASH_B
                                + "' where id = '" + idempotencyId + "'",
                        authorizationRequest))
                .isInstanceOf(PSQLException.class)
                .hasMessageContaining("invalid idempotency completion transition");
        assertThatThrownBy(() -> executeAsMigratorInTenant(
                        "delete from idempotency_records where id = '" + idempotencyId + "'",
                        authorizationRequest))
                .isInstanceOf(PSQLException.class)
                .hasMessageContaining("unexpired idempotency evidence cannot be deleted");
    }

    @Test
    void permitsAKeyToBeReusedOnlyAfterItsReplayEvidenceExpires() {
        var aggregateId = UUID.randomUUID();
        var idempotencyKey = "test-" + UUID.randomUUID();
        var authorizationRequest = request(ORG_ONE, "governed-mutation", "expired-replay-42");
        tenantAuthorization.execute(authorizationRequest, () -> jdbcTemplate.update(
                """
                INSERT INTO idempotency_records
                    (organization_id, actor_user_id, operation_key, idempotency_key,
                     request_hash, expires_at, purpose, correlation_id, created_at, updated_at)
                VALUES (?, ?, 'test.entity.change', ?, ?, clock_timestamp() - interval '1 hour',
                        ?, ?, clock_timestamp() - interval '2 hours',
                        clock_timestamp() - interval '2 hours')
                """,
                ORG_ONE,
                ACTOR,
                idempotencyKey,
                REQUEST_HASH_A,
                authorizationRequest.actor().purpose(),
                authorizationRequest.actor().correlationId()));

        var outcome = governedMutations.execute(
                authorizationRequest,
                command(idempotencyKey, REQUEST_HASH_B),
                context -> mutation(aggregateId, "reused-after-expiry"));

        assertThat(outcome.replayed()).isFalse();
        assertThat(evidenceCounts(authorizationRequest, aggregateId)).containsExactly(1, 1);
        assertThat(tenantAuthorization.execute(authorizationRequest, () -> jdbcTemplate.queryForObject(
                        "select request_hash from idempotency_records where idempotency_key = ?",
                        String.class,
                        idempotencyKey)))
                .isEqualTo(REQUEST_HASH_B);
    }

    @Test
    void publishesWithLeasesRetriesAndDeadLettersWithoutRewritingEvents() {
        var authorizationRequest = request(ORG_TWO, "outbox-publication", "outbox-publisher-42");
        recordMutation(authorizationRequest, UUID.randomUUID(), "success");
        recordMutation(authorizationRequest, UUID.randomUUID(), "retry");
        recordMutation(authorizationRequest, UUID.randomUUID(), "permanent");

        var retryAttempts = new AtomicInteger();
        var publisher = new OutboxPublisherService(tenantAuthorization, outboxDelivery, event -> {
            if (event.payloadJson().contains("permanent")) {
                throw new OutboxTransportException("transport.permanent", false);
            }
            if (event.payloadJson().contains("retry") && retryAttempts.getAndIncrement() == 0) {
                throw new OutboxTransportException("transport.temporary", true);
            }
        });
        var policy = new OutboxPublicationPolicy(
                10, 3, Duration.ofSeconds(30), Duration.ZERO, Duration.ofSeconds(1));

        var first = publisher.publishBatch(authorizationRequest, "test-worker-1", policy);
        assertThat(first.claimed()).isEqualTo(3);
        assertThat(first.published()).isEqualTo(1);
        assertThat(first.rescheduled()).isEqualTo(1);
        assertThat(first.deadLettered()).isEqualTo(1);
        assertThat(first.leaseLost()).isZero();

        var second = publisher.publishBatch(authorizationRequest, "test-worker-1", policy);
        assertThat(second.claimed()).isEqualTo(1);
        assertThat(second.published()).isEqualTo(1);
        assertThat(second.rescheduled()).isZero();
        assertThat(second.deadLettered()).isZero();
        assertThat(second.leaseLost()).isZero();

        recordMutation(authorizationRequest, UUID.randomUUID(), "exhausted");
        var exhaustedPublisher = new OutboxPublisherService(
                tenantAuthorization,
                outboxDelivery,
                event -> {
                    throw new OutboxTransportException("transport.still-unavailable", true);
                });
        var exhausted = exhaustedPublisher.publishBatch(
                authorizationRequest,
                "test-worker-2",
                new OutboxPublicationPolicy(
                        10, 1, Duration.ofSeconds(30), Duration.ZERO, Duration.ofSeconds(1)));
        assertThat(exhausted.claimed()).isEqualTo(1);
        assertThat(exhausted.deadLettered()).isEqualTo(1);
        assertThat(exhausted.rescheduled()).isZero();

        var deliveryState = tenantAuthorization.execute(authorizationRequest, () -> jdbcTemplate.queryForMap("""
                SELECT count(*) FILTER (WHERE published_at IS NOT NULL) AS published,
                       count(*) FILTER (WHERE dead_lettered_at IS NOT NULL) AS dead_lettered,
                       count(*) FILTER (WHERE claim_token IS NOT NULL) AS still_claimed,
                       max(attempt_count) AS maximum_attempts
                FROM outbox_events
                """));
        assertThat(((Number) deliveryState.get("published")).intValue()).isEqualTo(2);
        assertThat(((Number) deliveryState.get("dead_lettered")).intValue()).isEqualTo(2);
        assertThat(((Number) deliveryState.get("still_claimed")).intValue()).isZero();
        assertThat(((Number) deliveryState.get("maximum_attempts")).intValue()).isEqualTo(2);
    }

    @Test
    void consumesAnEventOnceAndDeduplicatesItsCanonicalPayload() {
        var sourceEventId = UUID.randomUUID();
        var aggregateId = UUID.randomUUID();
        var authorizationRequest = request(ORG_ONE, "event-consumption", "consumer-once-42");
        var event = inboundEvent(
                ORG_ONE, sourceEventId, aggregateId, "{\"change\":\"consumed\"}");
        var executions = new AtomicInteger();

        var first = consumerInboxExecutor.execute(
                authorizationRequest, event, context -> executions.incrementAndGet());
        var canonicalReplay = inboundEvent(
                ORG_ONE,
                sourceEventId,
                aggregateId,
                "{  \"change\" : \"consumed\"  }");
        var replay = consumerInboxExecutor.execute(authorizationRequest, canonicalReplay, context -> {
            executions.incrementAndGet();
            throw new AssertionError("duplicate consumer work must not execute");
        });

        assertThat(first.replayed()).isFalse();
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.receivedAt()).isEqualTo(first.receivedAt());
        assertThat(executions).hasValue(1);
        assertThat(inboxCount(authorizationRequest, sourceEventId)).isEqualTo(1);
    }

    @Test
    void rollsBackAConsumerReceiptAndItsEffectBeforeAValidRetry() {
        var sourceEventId = UUID.randomUUID();
        var aggregateId = UUID.randomUUID();
        var facilityCode = "ROLLIN-" + aggregateId.toString().substring(0, 8);
        var authorizationRequest = request(ORG_ONE, "event-consumption", "consumer-rollback-42");
        var event = inboundEvent(
                ORG_ONE, sourceEventId, aggregateId, "{\"change\":\"rollback\"}");

        assertThatThrownBy(() -> consumerInboxExecutor.execute(authorizationRequest, event, context -> {
                    jdbcTemplate.update(
                            "insert into facilities (organization_id, name, code) values (?, ?, ?)",
                            context.organizationId(),
                            "Rolled-back inbox facility",
                            facilityCode);
                    throw new IllegalStateException("synthetic consumer failure");
                }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("synthetic consumer failure");

        assertThat(inboxCount(authorizationRequest, sourceEventId)).isZero();
        assertThat(tenantAuthorization.execute(authorizationRequest, () -> jdbcTemplate.queryForObject(
                        "select count(*) from facilities where code = ?",
                        Integer.class,
                        facilityCode)))
                .isZero();

        var retry = consumerInboxExecutor.execute(authorizationRequest, event, context -> {});
        assertThat(retry.replayed()).isFalse();
        assertThat(inboxCount(authorizationRequest, sourceEventId)).isEqualTo(1);
    }

    @Test
    void serializesConcurrentConsumerDeliveriesWithoutRepeatingTheEffect() throws Exception {
        var sourceEventId = UUID.randomUUID();
        var aggregateId = UUID.randomUUID();
        var authorizationRequest = request(ORG_ONE, "event-consumption", "consumer-concurrent-42");
        var event = inboundEvent(
                ORG_ONE, sourceEventId, aggregateId, "{\"change\":\"concurrent\"}");
        var executions = new AtomicInteger();
        var workEntered = new CountDownLatch(1);
        var secondStarted = new CountDownLatch(1);
        var releaseWork = new CountDownLatch(1);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> consumerInboxExecutor.execute(
                    authorizationRequest,
                    event,
                    context -> {
                        executions.incrementAndGet();
                        workEntered.countDown();
                        await(releaseWork);
                    }));
            assertThat(workEntered.await(10, TimeUnit.SECONDS)).isTrue();
            var second = executor.submit(() -> {
                secondStarted.countDown();
                return consumerInboxExecutor.execute(
                        authorizationRequest, event, context -> executions.incrementAndGet());
            });
            assertThat(secondStarted.await(10, TimeUnit.SECONDS)).isTrue();
            releaseWork.countDown();

            assertThat(List.of(
                            first.get(10, TimeUnit.SECONDS).replayed(),
                            second.get(10, TimeUnit.SECONDS).replayed()))
                    .containsExactlyInAnyOrder(false, true);
        }

        assertThat(executions).hasValue(1);
        assertThat(inboxCount(authorizationRequest, sourceEventId)).isEqualTo(1);
    }

    @Test
    void rejectsChangedOrUnapprovedConsumerDeliveriesBeforeTheirEffects() {
        var sourceEventId = UUID.randomUUID();
        var aggregateId = UUID.randomUUID();
        var authorizationRequest = request(ORG_ONE, "event-consumption", "consumer-conflict-42");
        var event = inboundEvent(
                ORG_ONE, sourceEventId, aggregateId, "{\"change\":\"original\"}");
        consumerInboxExecutor.execute(authorizationRequest, event, context -> {});

        var changed = inboundEvent(
                ORG_ONE, sourceEventId, aggregateId, "{\"change\":\"changed\"}");
        assertThatThrownBy(() -> consumerInboxExecutor.execute(
                        authorizationRequest,
                        changed,
                        context -> {
                            throw new AssertionError("conflicting consumer work must not execute");
                        }))
                .isInstanceOf(ConsumerInboxException.class)
                .extracting(exception -> ((ConsumerInboxException) exception).reason())
                .isEqualTo(ConsumerInboxException.Reason.CONTENT_CONFLICT);

        var unknownConsumer = new InboundOutboxEvent(
                ORG_ONE,
                "test.unapproved-consumer",
                UUID.randomUUID(),
                TEST_EVENT,
                1,
                "test_entity",
                UUID.randomUUID(),
                "{\"change\":\"blocked\"}",
                "source-unapproved-42",
                SOURCE_OCCURRED_AT);
        assertThatThrownBy(() -> consumerInboxExecutor.execute(
                        authorizationRequest,
                        unknownConsumer,
                        context -> {
                            throw new AssertionError("unapproved consumer work must not execute");
                        }))
                .isInstanceOf(ConsumerInboxException.class)
                .extracting(exception -> ((ConsumerInboxException) exception).reason())
                .isEqualTo(ConsumerInboxException.Reason.STORE_REJECTED);

        var invalidPayload = inboundEvent(
                ORG_ONE,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "{\"unexpected\":true}");
        assertThatThrownBy(() -> consumerInboxExecutor.execute(
                        authorizationRequest, invalidPayload, context -> {}))
                .isInstanceOf(ConsumerInboxException.class)
                .extracting(exception -> ((ConsumerInboxException) exception).reason())
                .isEqualTo(ConsumerInboxException.Reason.STORE_REJECTED);
    }

    @Test
    void requiresTheAuthorizedTenantTransactionAndIsolatesConsumerReceipts() {
        var sourceEventId = UUID.randomUUID();
        var event = inboundEvent(
                ORG_TWO,
                sourceEventId,
                UUID.randomUUID(),
                "{\"change\":\"tenant-two\"}");
        var organizationTwoRequest = request(
                ORG_TWO, "event-consumption", "consumer-isolation-two-42");
        consumerInboxExecutor.execute(organizationTwoRequest, event, context -> {});

        assertThat(jdbcTemplate.queryForList(
                        "select source_event_id from consumer_inbox_records", UUID.class))
                .isEmpty();
        assertThat(inboxCount(
                        request(ORG_ONE, "event-consumption", "consumer-isolation-one-42"),
                        sourceEventId))
                .isZero();
        assertThat(inboxCount(organizationTwoRequest, sourceEventId)).isEqualTo(1);

        var forgedContext = new AuthorizedTenantContext(
                ORG_TWO, ACTOR, "event-consumption", "consumer-outside-transaction-42");
        assertThatThrownBy(() -> consumerInboxOperations.execute(forgedContext, event, () -> {}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("An active writable tenant transaction is required");

        var crossTenantEvent = inboundEvent(
                ORG_TWO,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "{\"change\":\"forged\"}");
        assertThatThrownBy(() -> consumerInboxExecutor.execute(
                        request(ORG_ONE, "event-consumption", "consumer-tenant-mismatch-42"),
                        crossTenantEvent,
                        context -> {}))
                .isInstanceOf(ConsumerInboxException.class)
                .extracting(exception -> ((ConsumerInboxException) exception).reason())
                .isEqualTo(ConsumerInboxException.Reason.TENANT_MISMATCH);

        var forgedRequest = request(
                ORG_ONE, "event-consumption", "consumer-forged-insert-42");
        assertThatThrownBy(() -> tenantAuthorization.execute(
                        forgedRequest,
                        (Runnable) () -> jdbcTemplate.update(
                                """
                                INSERT INTO consumer_inbox_records
                                    (organization_id, consumer_key, source_event_id,
                                     event_name, schema_version, aggregate_type, aggregate_id,
                                     payload_sha256, source_correlation_id, occurred_at,
                                     received_by_actor_id, purpose, correlation_id)
                                VALUES (?, ?, ?, ?, 1, 'test_entity', ?, ?, ?, ?, ?, ?, ?)
                                """,
                                ORG_TWO,
                                TEST_CONSUMER,
                                UUID.randomUUID(),
                                TEST_EVENT,
                                UUID.randomUUID(),
                                "a".repeat(64),
                                "source-forged-42",
                                Timestamp.from(SOURCE_OCCURRED_AT),
                                ACTOR,
                                forgedRequest.actor().purpose(),
                                forgedRequest.actor().correlationId())))
                .hasRootCauseInstanceOf(PSQLException.class);
    }

    @Test
    void protectsConsumerDefinitionsAndReceiptsFromRuntimeAndOwnerMutation()
            throws SQLException {
        assertThatThrownBy(() -> jdbcTemplate.update("""
                        INSERT INTO outbox_consumer_definitions
                            (consumer_key, event_name, schema_version, description, registry_version)
                        VALUES ('forged.consumer', 'test.entity.changed', 1,
                                'Runtime policy attack', 'forged')
                        """))
                .hasRootCauseInstanceOf(PSQLException.class);

        var sourceEventId = UUID.randomUUID();
        var authorizationRequest = request(
                ORG_ONE, "event-consumption", "consumer-immutable-42");
        consumerInboxExecutor.execute(
                authorizationRequest,
                inboundEvent(
                        ORG_ONE,
                        sourceEventId,
                        UUID.randomUUID(),
                        "{\"change\":\"immutable\"}"),
                context -> {});

        var privileges = jdbcTemplate.queryForMap("""
                SELECT has_table_privilege('outbox_consumer_definitions', 'SELECT') AS registry_select,
                       has_table_privilege('outbox_consumer_definitions', 'INSERT') AS registry_insert,
                       has_table_privilege('consumer_inbox_records', 'SELECT') AS inbox_select,
                       has_table_privilege('consumer_inbox_records', 'INSERT') AS inbox_insert,
                       has_table_privilege('consumer_inbox_records', 'UPDATE') AS inbox_update,
                       has_table_privilege('consumer_inbox_records', 'DELETE') AS inbox_delete
                """);
        assertThat(privileges)
                .containsEntry("registry_select", true)
                .containsEntry("registry_insert", false)
                .containsEntry("inbox_select", true)
                .containsEntry("inbox_insert", true)
                .containsEntry("inbox_update", false)
                .containsEntry("inbox_delete", false);

        assertThatThrownBy(() -> executeAsMigratorInTenant(
                        "update consumer_inbox_records set payload_sha256 = '"
                                + "f".repeat(64)
                                + "' where source_event_id = '"
                                + sourceEventId
                                + "'",
                        authorizationRequest))
                .isInstanceOf(PSQLException.class)
                .hasMessageContaining("consumer inbox receipts are append-only");
        assertThatThrownBy(() -> executeAsMigratorInTenant(
                        "delete from consumer_inbox_records where source_event_id = '"
                                + sourceEventId
                                + "'",
                        authorizationRequest))
                .isInstanceOf(PSQLException.class)
                .hasMessageContaining("consumer inbox receipts are append-only");
    }

    @Test
    void isolatesInvitationsAndRejectsCrossTenantInvitationWrites() {
        assertThat(jdbcTemplate.queryForList("select id from invitations", UUID.class)).isEmpty();
        assertThat(tenantAuthorization.execute(
                        request(ORG_ONE),
                        () -> jdbcTemplate.queryForList("select id from invitations", UUID.class)))
                .containsExactly(INVITATION_ONE);
        assertThat(tenantAuthorization.execute(
                        request(ORG_TWO),
                        () -> jdbcTemplate.queryForList("select id from invitations", UUID.class)))
                .containsExactly(INVITATION_TWO);

        assertThatThrownBy(() -> tenantAuthorization.execute(request(ORG_ONE), (Runnable) () -> jdbcTemplate.update(
                        """
                        INSERT INTO invitations
                            (organization_id, email, display_name, role_key, token_hash,
                             expires_at, invited_by, correlation_id)
                        VALUES (?, 'forged.invitation@rootopathy.test', 'Forged Invitation', 'test_actor',
                                'cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc',
                                now() + interval '1 day', ?, 'rls-forged')
                        """,
                        ORG_TWO,
                        ACTOR)))
                .hasRootCauseInstanceOf(PSQLException.class);
    }

    @Test
    void enforcesAuthenticationEvidenceAsAppendOnlyEvenForTheMigrationOwner() throws SQLException {
        var evidenceId = UUID.randomUUID();
        executeAsMigrator("""
                INSERT INTO authentication_events
                    (id, user_id, event_name, subject_hash, remote_address_hash, correlation_id)
                VALUES ('%s', '%s', 'identity.test.recorded',
                        'dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd',
                        'eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee',
                        'append-only-test')
                """.formatted(evidenceId, ACTOR));

        assertThatThrownBy(() -> executeAsMigrator(
                        "update authentication_events set event_name = 'identity.test.changed' where id = '"
                                + evidenceId
                                + "'"))
                .isInstanceOf(PSQLException.class)
                .hasMessageContaining("authentication_events are append-only");
        assertThatThrownBy(() -> executeAsMigrator(
                        "delete from authentication_events where id = '" + evidenceId + "'"))
                .isInstanceOf(PSQLException.class)
                .hasMessageContaining("authentication_events are append-only");
    }

    private TenantAuthorizationRequest request(UUID organizationId) {
        return request(organizationId, "rls-verification", "rls-test-42");
    }

    private TenantAuthorizationRequest request(
            UUID organizationId, String purpose, String correlationId) {
        return new TenantAuthorizationRequest(
                organizationId,
                new AuthenticatedActorContext(ACTOR, purpose, correlationId),
                new OperationKey("test.rls-access"));
    }

    private IdempotencyCommand command(String idempotencyKey, String requestHash) {
        return new IdempotencyCommand(
                "test.entity.change", idempotencyKey, requestHash, Instant.now().plus(Duration.ofHours(1)));
    }

    private GovernedMutation mutation(UUID aggregateId, String change) {
        var payload = "{\"change\":\"" + change + "\"}";
        return new GovernedMutation(
                new IdempotentResponse(
                        201,
                        "application/json",
                        "{\"id\":\"" + aggregateId + "\",\"result\":\"" + change + "\"}"),
                new GovernanceEvidence(
                        new AuditRecord(
                                TEST_EVENT,
                                1,
                                "test_entity",
                                aggregateId,
                                "Synthetic governed mutation",
                                payload),
                        new OutboxRecord(
                                TEST_EVENT,
                                1,
                                "test_entity",
                                aggregateId,
                                payload)));
    }

    private void recordMutation(
            TenantAuthorizationRequest authorizationRequest, UUID aggregateId, String change) {
        governedMutations.execute(
                authorizationRequest,
                command("test-" + UUID.randomUUID(), REQUEST_HASH_A),
                context -> mutation(aggregateId, change));
    }

    private InboundOutboxEvent inboundEvent(
            UUID organizationId, UUID sourceEventId, UUID aggregateId, String payloadJson) {
        return new InboundOutboxEvent(
                organizationId,
                TEST_CONSUMER,
                sourceEventId,
                TEST_EVENT,
                1,
                "test_entity",
                aggregateId,
                payloadJson,
                "source-event-42",
                SOURCE_OCCURRED_AT);
    }

    private int inboxCount(
            TenantAuthorizationRequest authorizationRequest, UUID sourceEventId) {
        return tenantAuthorization.execute(authorizationRequest, () -> jdbcTemplate.queryForObject(
                "select count(*) from consumer_inbox_records where source_event_id = ?",
                Integer.class,
                sourceEventId));
    }

    private List<Integer> evidenceCounts(
            TenantAuthorizationRequest authorizationRequest, UUID aggregateId) {
        return tenantAuthorization.execute(authorizationRequest, () -> List.of(
                jdbcTemplate.queryForObject(
                        "select count(*) from audit_events where subject_id = ?",
                        Integer.class,
                        aggregateId),
                jdbcTemplate.queryForObject(
                        "select count(*) from outbox_events where aggregate_id = ?",
                        Integer.class,
                        aggregateId)));
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for concurrent test coordination");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Concurrent test was interrupted", exception);
        }
    }

    private List<String> facilityCodes() {
        return jdbcTemplate.queryForList("select code from facilities order by code", String.class);
    }

    private List<String> facilityCodesWithoutContext() {
        return jdbcTemplate.queryForList("select code from facilities order by code", String.class);
    }

    private void assertAuthorizationReason(
            TenantAuthorizationRequest request, TenantAuthorizationException.Reason reason) {
        assertThatThrownBy(() -> tenantAuthorization.execute(request, this::facilityCodes))
                .isInstanceOf(TenantAuthorizationException.class)
                .extracting(exception -> ((TenantAuthorizationException) exception).reason())
                .isEqualTo(reason);
    }

    private void seedReferenceOwner() throws SQLException {
        executeAsMigrator("""
                INSERT INTO users (id, email, display_name, status)
                VALUES
                    ('01900000-0000-7000-8000-000000000203',
                     'reference.owner@rootopathy.test', 'Reference Owner', 'active'),
                    ('01900000-0000-7000-8000-000000000204',
                     'reference.owner.two@rootopathy.test', 'Second Reference Owner', 'active')
                ON CONFLICT (id) DO NOTHING
                """);
        executeAsMigrator("""
                INSERT INTO organizations
                    (id, legal_name, display_name, organization_type,
                     country_code, timezone, locale, status)
                VALUES
                    ('01900000-0000-7000-8000-000000000003',
                     'Reference Policy Organization', 'Reference Organization',
                     'care_network', 'IN', 'Asia/Kolkata', 'en-IN', 'active')
                ON CONFLICT (id) DO UPDATE SET status = 'active'
                """);
        executeAsMigrator("""
                INSERT INTO organization_memberships
                    (id, organization_id, user_id, role_key, status, effective_from, effective_to)
                VALUES
                    ('01900000-0000-7000-8000-000000000303',
                     '01900000-0000-7000-8000-000000000003',
                     '01900000-0000-7000-8000-000000000203',
                     'organization_owner', 'active', now(), NULL)
                ON CONFLICT (id) DO UPDATE
                    SET role_key = 'organization_owner',
                        status = 'active',
                        effective_from = now(),
                        effective_to = NULL
                """);
        executeAsMigrator("""
                DELETE FROM organization_memberships
                WHERE id = '01900000-0000-7000-8000-000000000304'
                """);
    }

    private void seedServiceIdentity() throws SQLException {
        executeAsMigrator("""
                INSERT INTO service_identities
                    (id, organization_id, service_key, display_name, role_key,
                     allowed_purposes, status, provisioning_reference)
                VALUES
                    ('01900000-0000-7000-8000-000000000501',
                     '01900000-0000-7000-8000-000000000001',
                     'test.outbox-publisher', 'Test outbox publisher',
                     'service_outbox_publisher', ARRAY['outbox-publication'],
                     'active', 'TEST-SECURITY-42')
                ON CONFLICT (id) DO NOTHING
                """);
        executeAsMigrator("""
                INSERT INTO service_identity_credentials
                    (organization_id, service_identity_id, credential_version,
                     credential_hash, active_from, expires_at, provisioning_reference)
                VALUES
                    ('01900000-0000-7000-8000-000000000001',
                     '01900000-0000-7000-8000-000000000501', 1,
                     '%s', now() - interval '1 minute', now() + interval '1 day',
                     'TEST-SECURITY-42')
                ON CONFLICT (service_identity_id, credential_version) DO NOTHING
                """.formatted(serviceCredentialDigest(SERVICE_CREDENTIAL)));
    }

    private void assertServiceAuthorizationDenied(
            ServiceIdentityAuthorizationOperations authorization,
            ServiceIdentityAuthorizationRequest request) {
        assertThatThrownBy(() -> authorization.execute(request, this::facilityCodes))
                .isInstanceOf(ServiceIdentityAuthorizationException.class)
                .hasMessage("Service identity authorization failed.")
                .extracting(exception ->
                        ((ServiceIdentityAuthorizationException) exception).reason())
                .isEqualTo(
                        ServiceIdentityAuthorizationException.Reason.CREDENTIAL_OR_PERMISSION_DENIED);
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

    private int approveMfaReset(
            UUID approvalId,
            UUID decisionActorId,
            TenantAuthorizationRequest authorization,
            Instant decidedAt) {
        return jdbcTemplate.update(
                """
                UPDATE authorization_approval_requests
                SET status = 'approved', decided_by_user_id = ?, decision_reason = ?,
                    decision_correlation_id = ?, decided_at = ?, updated_at = ?,
                    lock_version = lock_version + 1
                WHERE id = ? AND status = 'pending'
                """,
                decisionActorId,
                authorization.reason(),
                authorization.actor().correlationId(),
                Timestamp.from(decidedAt),
                Timestamp.from(decidedAt),
                approvalId);
    }

    private void executeAsMigrator(String sql) throws SQLException {
        try (var connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), MIGRATOR_USER, MIGRATOR_PASSWORD);
                var statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private void executeAsMigratorInTenant(
            String sql, TenantAuthorizationRequest authorizationRequest) throws SQLException {
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), MIGRATOR_USER, MIGRATOR_PASSWORD)) {
            connection.setAutoCommit(false);
            try (var setting = connection.prepareStatement("select set_config(?, ?, true)")) {
                setTransactionSetting(
                        setting,
                        "app.current_organization_id",
                        authorizationRequest.organizationId().toString());
                setTransactionSetting(
                        setting,
                        "app.current_actor_id",
                        authorizationRequest.actor().actorId().toString());
                setTransactionSetting(
                        setting, "app.current_purpose", authorizationRequest.actor().purpose());
                setTransactionSetting(
                        setting,
                        "app.current_correlation_id",
                        authorizationRequest.actor().correlationId());
                setTransactionSetting(
                        setting,
                        "app.current_operation_key",
                        authorizationRequest.requiredOperation().value());
                try (var statement = connection.createStatement()) {
                    statement.executeUpdate(sql);
                }
                connection.commit();
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    private static void setTransactionSetting(
            java.sql.PreparedStatement statement, String name, String value) throws SQLException {
        statement.setString(1, name);
        statement.setString(2, value);
        statement.executeQuery().close();
    }
}
