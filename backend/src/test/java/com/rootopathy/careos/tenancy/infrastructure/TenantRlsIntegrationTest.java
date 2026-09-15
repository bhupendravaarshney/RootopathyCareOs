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
import com.rootopathy.careos.tenancy.application.TenantAuthorizationException;
import com.rootopathy.careos.tenancy.application.TenantAuthorizationOperations;
import com.rootopathy.careos.tenancy.domain.AuthenticatedActorContext;
import com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext;
import com.rootopathy.careos.tenancy.domain.PermissionKey;
import com.rootopathy.careos.tenancy.domain.TenantAuthorizationRequest;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
class TenantRlsIntegrationTest {
    private static final String MIGRATOR_USER = "careos_migrator";
    private static final String MIGRATOR_PASSWORD = "careos-migrator-test-only";
    private static final String APP_USER = "careos_app";
    private static final String APP_PASSWORD = "careos-app-test-only";

    private static final UUID ORG_ONE = UUID.fromString("01900000-0000-7000-8000-000000000001");
    private static final UUID ORG_TWO = UUID.fromString("01900000-0000-7000-8000-000000000002");
    private static final UUID FACILITY_TWO = UUID.fromString("01900000-0000-7000-8000-000000000102");
    private static final UUID ACTOR = UUID.fromString("01900000-0000-7000-8000-000000000201");
    private static final UUID INVITATION_ONE = UUID.fromString("01900000-0000-7000-8000-000000000401");
    private static final UUID INVITATION_TWO = UUID.fromString("01900000-0000-7000-8000-000000000402");
    private static final String TEST_EVENT = "test.entity.changed";
    private static final String TEST_CONSUMER = "test.projection";
    private static final Instant SOURCE_OCCURRED_AT = Instant.parse("2026-09-15T08:30:00.123456Z");
    private static final String REQUEST_HASH_A = "a".repeat(64);
    private static final String REQUEST_HASH_B = "b".repeat(64);

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
    private ActorTransactionOperations actorTransactions;

    @Autowired
    private GovernedMutationExecutor governedMutations;

    @Autowired
    private OutboxDeliveryOperations outboxDelivery;

    @Autowired
    private ConsumerInboxExecutor consumerInboxExecutor;

    @Autowired
    private ConsumerInboxOperations consumerInboxOperations;

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
                    INSERT INTO organizations (id, legal_name, display_name, country_code, timezone, status)
                    VALUES ('01900000-0000-7000-8000-000000000002', 'Second Synthetic Care Org', 'Second Care Org', 'IN', 'Asia/Kolkata', 'active')
                    ON CONFLICT (id) DO NOTHING
                    """);
            statement.executeUpdate("""
                    INSERT INTO facilities (id, organization_id, name, code, status)
                    VALUES ('01900000-0000-7000-8000-000000000102', '01900000-0000-7000-8000-000000000002', 'Second Tenant Facility', 'SECOND-01', 'active')
                    ON CONFLICT (id) DO NOTHING
                    """);
            statement.executeUpdate("""
                    INSERT INTO organization_memberships (id, organization_id, user_id, role_key, status)
                    VALUES
                        ('01900000-0000-7000-8000-000000000301', '01900000-0000-7000-8000-000000000001', '01900000-0000-7000-8000-000000000201', 'test_actor', 'active'),
                        ('01900000-0000-7000-8000-000000000302', '01900000-0000-7000-8000-000000000002', '01900000-0000-7000-8000-000000000201', 'test_actor', 'active')
                    ON CONFLICT (id) DO NOTHING
                    """);
            statement.executeUpdate("""
                    INSERT INTO authorization_permissions
                        (permission_key, display_name, description, registry_version)
                    VALUES ('test.rls-access', 'RLS test access', 'Synthetic test-only permission', 'test-v1')
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
    void failsClosedForUnknownPermissionsAndActorsWithoutMembership() {
        var unknownPermission = new TenantAuthorizationRequest(
                ORG_ONE,
                new AuthenticatedActorContext(ACTOR, "rls-verification", "unknown-permission-42"),
                new PermissionKey("test.not-approved"));
        assertThatThrownBy(() -> tenantAuthorization.execute(unknownPermission, this::facilityCodes))
                .isInstanceOf(TenantAuthorizationException.class)
                .extracting(exception -> ((TenantAuthorizationException) exception).reason())
                .isEqualTo(TenantAuthorizationException.Reason.PERMISSION_DENIED);

        var unknownActor = new TenantAuthorizationRequest(
                ORG_ONE,
                new AuthenticatedActorContext(UUID.randomUUID(), "rls-verification", "unknown-actor-42"),
                new PermissionKey("test.rls-access"));
        assertThatThrownBy(() -> tenantAuthorization.execute(unknownActor, this::facilityCodes))
                .isInstanceOf(TenantAuthorizationException.class)
                .extracting(exception -> ((TenantAuthorizationException) exception).reason())
                .isEqualTo(TenantAuthorizationException.Reason.MEMBERSHIP_NOT_FOUND);
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
                new PermissionKey("test.rls-access"));
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
