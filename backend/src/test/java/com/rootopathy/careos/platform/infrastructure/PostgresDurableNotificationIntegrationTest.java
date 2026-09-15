package com.rootopathy.careos.platform.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.rootopathy.careos.platform.application.DurableNotificationException;
import com.rootopathy.careos.platform.application.DurableNotificationPort;
import com.rootopathy.careos.platform.application.PlatformCapabilityRegistry;
import com.rootopathy.careos.platform.domain.CapabilityAvailability;
import com.rootopathy.careos.platform.domain.DurableNotification;
import com.rootopathy.careos.platform.domain.NotificationFailureDisposition;
import com.rootopathy.careos.platform.domain.PlatformCapability;
import com.rootopathy.careos.tenancy.application.TenantAuthorizationOperations;
import com.rootopathy.careos.tenancy.domain.AuthenticatedActorContext;
import com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext;
import com.rootopathy.careos.tenancy.domain.PermissionKey;
import com.rootopathy.careos.tenancy.domain.TenantAuthorizationRequest;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class PostgresDurableNotificationIntegrationTest {
    private static final String MIGRATOR_USER = "careos_migrator";
    private static final String MIGRATOR_PASSWORD = "careos-migrator-test-only";
    private static final String APP_USER = "careos_app";
    private static final String APP_PASSWORD = "careos-app-test-only";
    private static final String KEY_ONE = Base64.getEncoder()
            .encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));
    private static final String KEY_TWO = Base64.getEncoder()
            .encodeToString("abcdef0123456789abcdef0123456789".getBytes(StandardCharsets.UTF_8));
    private static final UUID ORG_ONE =
            UUID.fromString("01900000-0000-7000-8000-000000000001");
    private static final UUID ORG_TWO =
            UUID.fromString("01900000-0000-7000-8000-000000000002");
    private static final UUID ACTOR =
            UUID.fromString("01900000-0000-7000-8000-000000000201");
    private static final UUID RECIPIENT =
            UUID.fromString("01900000-0000-7000-8000-000000000202");

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
        registry.add("careos.notifications.postgres.enabled", () -> "true");
        registry.add(
                "careos.notifications.postgres.active-encryption-key-id", () -> "test-v1");
        registry.add(
                "careos.notifications.postgres.encryption-keys",
                () -> "test-v1:" + KEY_ONE);
        registry.add(
                "careos.notifications.postgres.allowed-template-definitions",
                () -> "security.notice@1");
        registry.add("careos.notifications.postgres.lease-duration", () -> "300ms");
        registry.add("careos.notifications.postgres.maximum-attempts", () -> "2");
        registry.add("careos.notifications.postgres.initial-retry-delay", () -> "100ms");
        registry.add("careos.notifications.postgres.maximum-retry-delay", () -> "200ms");
        registry.add("careos.notifications.postgres.terminal-retention", () -> "1m");
        registry.add("careos.notifications.postgres.maximum-schedule-ahead", () -> "1h");
        registry.add("careos.notifications.postgres.maximum-claim-batch", () -> "10");
        registry.add("careos.notifications.postgres.cleanup-batch", () -> "25");
    }

    @Autowired
    private DurableNotificationPort notifications;

    @Autowired
    private PlatformCapabilityRegistry capabilities;

    @Autowired
    private TenantAuthorizationOperations tenantAuthorization;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MeterRegistry meterRegistry;

    @BeforeEach
    void seedAuthorizationAndRecipients() throws SQLException {
        try (var connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), MIGRATOR_USER, MIGRATOR_PASSWORD);
                var statement = connection.createStatement()) {
            statement.executeUpdate("TRUNCATE durable_notifications");
            statement.executeUpdate("""
                    INSERT INTO users (id, email, display_name, status)
                    VALUES
                        ('01900000-0000-7000-8000-000000000201',
                         'notification.actor@rootopathy.test', 'Notification Actor', 'active'),
                        ('01900000-0000-7000-8000-000000000202',
                         'notification.recipient@rootopathy.test', 'Notification Recipient', 'active')
                    ON CONFLICT (id) DO NOTHING
                    """);
            statement.executeUpdate("""
                    INSERT INTO organizations
                        (id, legal_name, display_name, country_code, timezone, status)
                    VALUES ('01900000-0000-7000-8000-000000000002',
                            'Second Notification Test Org', 'Second Notification Org',
                            'IN', 'Asia/Kolkata', 'active')
                    ON CONFLICT (id) DO UPDATE SET status = 'active'
                    """);
            statement.executeUpdate("""
                    UPDATE organizations SET status = 'active'
                    WHERE id = '01900000-0000-7000-8000-000000000001'
                    """);
            statement.executeUpdate("""
                    INSERT INTO authorization_permissions
                        (permission_key, display_name, description, registry_version)
                    VALUES ('test.notification-store', 'Notification store test',
                            'Synthetic notification mechanics permission', 'test-v1')
                    ON CONFLICT (permission_key) DO NOTHING
                    """);
            statement.executeUpdate("""
                    INSERT INTO authorization_roles
                        (role_key, display_name, description, registry_version)
                    VALUES ('notification_test_actor', 'Notification test actor',
                            'Synthetic notification mechanics role', 'test-v1')
                    ON CONFLICT (role_key) DO NOTHING
                    """);
            statement.executeUpdate("""
                    INSERT INTO authorization_role_permissions (role_key, permission_key)
                    VALUES ('notification_test_actor', 'test.notification-store')
                    ON CONFLICT (role_key, permission_key) DO NOTHING
                    """);
            statement.executeUpdate("""
                    INSERT INTO organization_memberships
                        (organization_id, user_id, role_key, status)
                    VALUES
                        ('01900000-0000-7000-8000-000000000001',
                         '01900000-0000-7000-8000-000000000201',
                         'notification_test_actor', 'active'),
                        ('01900000-0000-7000-8000-000000000002',
                         '01900000-0000-7000-8000-000000000201',
                         'notification_test_actor', 'active')
                    ON CONFLICT (organization_id, user_id, role_key)
                    DO UPDATE SET status = 'active', effective_to = NULL
                    """);
        }
    }

    @Test
    void activatesOnlyTheDurableNotificationMechanics() {
        assertThat(notifications).isInstanceOf(PostgresDurableNotificationAdapter.class);
        assertThat(capabilities.status(PlatformCapability.DURABLE_NOTIFICATION_DELIVERY)
                        .availability())
                .isEqualTo(CapabilityAvailability.AVAILABLE);
        assertThat(capabilities.status(PlatformCapability.WORKER_EXECUTION).availability())
                .isEqualTo(CapabilityAvailability.UNAVAILABLE);
        assertThat(capabilities.status(PlatformCapability.SCHEDULER_EXECUTION).availability())
                .isEqualTo(CapabilityAvailability.UNAVAILABLE);
        assertThat(capabilities.statuses().stream()
                        .filter(status ->
                                status.availability() == CapabilityAvailability.AVAILABLE))
                .extracting(status -> status.capability())
                .containsExactly(PlatformCapability.DURABLE_NOTIFICATION_DELIVERY);
    }

    @Test
    void requiresTheAuthorizedTransactionAndRlsHidesStoredRows() {
        var notification = dueNotification(UUID.randomUUID(), "tenant-bound", "alpha");
        assertThatIllegalStateException()
                .isThrownBy(() -> notifications.enqueue(context(ORG_ONE, "outside-transaction"), notification))
                .withMessageContaining("writable tenant transaction");

        authorize(ORG_ONE, "tenant-one", context -> {
            notifications.enqueue(context, notification);
            return null;
        });

        assertThat(jdbcTemplate.queryForObject(
                        "select count(*) from durable_notifications", Long.class))
                .isZero();
        assertThat(authorize(ORG_ONE, "tenant-one-read", this::notificationCount)).isEqualTo(1L);
        assertThat(authorize(ORG_TWO, "tenant-two-read", this::notificationCount)).isZero();
        assertThatThrownBy(() -> authorize(ORG_TWO, "cross-tenant-claim", context -> {
                    notifications.acknowledge(
                            context,
                            authorize(ORG_ONE, "claim-one", firstClaim()));
                    return null;
                }))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tenant");
    }

    @Test
    void databaseRejectsContentMutationAndPrematureDeletion() {
        var notificationId = UUID.randomUUID();
        authorize(ORG_ONE, "database-guard-enqueue", context -> {
            notifications.enqueue(
                    context,
                    dueNotification(notificationId, "database-guard", "immutable"));
            return null;
        });

        assertThatThrownBy(() -> authorize(ORG_ONE, "database-content-attack", context ->
                        jdbcTemplate.update(
                                """
                                UPDATE durable_notifications
                                SET encrypted_payload = encrypted_payload || decode('00', 'hex')
                                WHERE notification_id = ?
                                """,
                                notificationId)))
                .hasRootCauseInstanceOf(PSQLException.class);
        assertThatThrownBy(() -> authorize(ORG_ONE, "database-delete-attack", context ->
                        jdbcTemplate.update(
                                "delete from durable_notifications where notification_id = ?",
                                notificationId)))
                .hasRootCauseInstanceOf(PSQLException.class);
        assertThat(authorize(ORG_ONE, "database-guard-count", this::notificationCount))
                .isEqualTo(1L);
    }

    @Test
    void encryptsPayloadAndNeverPersistsDestinationOrRawDeduplicationData()
            throws SQLException {
        var notificationId = UUID.randomUUID();
        var rawDeduplicationKey = "notification-sensitive-dedup";
        var secret = "care-plan-secret-42";
        authorize(ORG_ONE, "encryption-evidence", context -> {
            notifications.enqueue(
                    context,
                    dueNotification(notificationId, rawDeduplicationKey, secret));
            return null;
        });

        try (var connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), MIGRATOR_USER, MIGRATOR_PASSWORD);
                var statement = connection.prepareStatement("""
                        SELECT encode(encrypted_payload, 'hex') AS ciphertext,
                               octet_length(payload_nonce) AS nonce_bytes,
                               encryption_key_id, deduplication_hash,
                               requested_by_user_id, purpose, correlation_id
                        FROM durable_notifications
                        WHERE organization_id = ? AND notification_id = ?
                        """)) {
            statement.setObject(1, ORG_ONE);
            statement.setObject(2, notificationId);
            try (var rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString("ciphertext")).doesNotContain(secret);
                assertThat(rows.getInt("nonce_bytes")).isEqualTo(12);
                assertThat(rows.getString("encryption_key_id")).isEqualTo("test-v1");
                assertThat(rows.getString("deduplication_hash"))
                        .hasSize(64)
                        .isNotEqualTo(rawDeduplicationKey);
                assertThat(rows.getObject("requested_by_user_id", UUID.class)).isEqualTo(ACTOR);
                assertThat(rows.getString("purpose")).isEqualTo("notification-mechanics");
                assertThat(rows.getString("correlation_id")).isEqualTo("encryption-evidence");
            }
        }

        var columns = migratorStrings("""
                SELECT column_name
                FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'durable_notifications'
                """);
        assertThat(columns)
                .noneMatch(name -> name.contains("email")
                        || name.contains("phone")
                        || name.contains("destination")
                        || name.equals("payload_json")
                        || name.equals("deduplication_key"));
    }

    @Test
    void deduplicatesConcurrentProducersAndRejectsConflictingReplays()
            throws Exception {
        var notificationId = UUID.randomUUID();
        var notification = dueNotification(notificationId, "concurrent-dedup", "same");
        var start = new CountDownLatch(1);
        var failures = new ArrayList<Throwable>();
        try (var executor = Executors.newFixedThreadPool(8)) {
            var futures = new ArrayList<java.util.concurrent.Future<?>>();
            for (var index = 0; index < 8; index++) {
                var correlation = "concurrent-" + index;
                futures.add(executor.submit(() -> {
                    start.await(5, TimeUnit.SECONDS);
                    authorize(ORG_ONE, correlation, context -> {
                        notifications.enqueue(context, notification);
                        return null;
                    });
                    return null;
                }));
            }
            start.countDown();
            for (var future : futures) {
                try {
                    future.get(10, TimeUnit.SECONDS);
                } catch (Exception exception) {
                    failures.add(exception);
                }
            }
        }
        assertThat(failures).isEmpty();
        assertThat(authorize(ORG_ONE, "dedup-count", this::notificationCount)).isEqualTo(1L);

        assertReason(
                () -> authorize(ORG_ONE, "id-conflict", context -> {
                    notifications.enqueue(
                            context,
                            dueNotification(notificationId, "concurrent-dedup", "changed"));
                    return null;
                }),
                "notification-id-conflict");
        assertReason(
                () -> authorize(ORG_ONE, "dedup-conflict", context -> {
                    notifications.enqueue(
                            context,
                            dueNotification(UUID.randomUUID(), "concurrent-dedup", "same"));
                    return null;
                }),
                "notification-deduplication-conflict");

        authorize(ORG_TWO, "other-tenant-same-dedup", context -> {
            notifications.enqueue(
                    context,
                    dueNotification(UUID.randomUUID(), "concurrent-dedup", "other-tenant"));
            return null;
        });
        assertThat(authorize(ORG_TWO, "other-tenant-count", this::notificationCount)).isEqualTo(1L);
    }

    @Test
    void claimsDueRequestsAndAcknowledgesTheSameLeaseIdempotently() {
        var due = dueNotification(UUID.randomUUID(), "claim-due", "deliver-me");
        var scheduled = new DurableNotification(
                UUID.randomUUID(),
                RECIPIENT,
                "security.notice",
                1,
                "{\"value\":\"later\"}",
                "claim-later",
                Instant.now().plusSeconds(30));
        authorize(ORG_ONE, "enqueue-claimables", context -> {
            notifications.enqueue(context, due);
            notifications.enqueue(context, scheduled);
            return null;
        });

        var claim = authorize(ORG_ONE, "claim-due", context ->
                        notifications.claim(context, 10))
                .getFirst();
        assertThat(claim.notificationId()).isEqualTo(due.notificationId());
        assertThat(claim.recipientUserId()).isEqualTo(RECIPIENT);
        assertThat(claim.payloadJson()).isEqualTo("{\"value\":\"deliver-me\"}");
        assertThat(claim.leaseToken()).matches("[0-9a-f]{64}");
        assertThat(migratorStrings("select claim_token_hash from durable_notifications"))
                .noneMatch(claim.leaseToken()::equals);

        authorize(ORG_ONE, "acknowledge", context -> {
            notifications.acknowledge(context, claim);
            return null;
        });
        authorize(ORG_ONE, "acknowledge-again", context -> {
            notifications.acknowledge(context, claim);
            return null;
        });

        var snapshot = authorize(ORG_ONE, "snapshot", notifications::snapshot);
        assertThat(snapshot.readyNotifications()).isEqualTo(1);
        assertThat(snapshot.leasedNotifications()).isZero();
        assertThat(snapshot.retainedCompletedNotifications()).isEqualTo(1);
        assertThat(snapshot.deadLetteredNotifications()).isZero();
    }

    @Test
    void appliesBoundedRetryThenDeadLettersAtThePersistedAttemptLimit() {
        authorize(ORG_ONE, "retry-enqueue", context -> {
            notifications.enqueue(
                    context,
                    dueNotification(UUID.randomUUID(), "retry-dedup", "retry"));
            return null;
        });
        var first = authorize(ORG_ONE, "retry-claim-one", firstClaim());
        var firstDisposition = authorize(ORG_ONE, "retry-one", context ->
                notifications.recordFailure(context, first, "provider.timeout"));
        assertThat(firstDisposition)
                .isEqualTo(NotificationFailureDisposition.RETRY_SCHEDULED);
        var notDue = authorize(
                ORG_ONE, "not-due-yet", context -> notifications.claim(context, 1));
        assertThat(notDue).isEmpty();

        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertThat(Boolean.TRUE.equals(
                        authorize(
                                ORG_ONE,
                                "retry-is-due",
                                ignored -> jdbcTemplate.queryForObject(
                                        "select available_at <= clock_timestamp() from durable_notifications",
                                        Boolean.class))))
                .isTrue());
        var second = authorize(ORG_ONE, "retry-claim-two", firstClaim());
        assertThat(second.attempt()).isEqualTo(2);
        var secondDisposition = authorize(ORG_ONE, "retry-two", context ->
                notifications.recordFailure(context, second, "provider.timeout"));
        assertThat(secondDisposition)
                .isEqualTo(NotificationFailureDisposition.DEAD_LETTERED);
        var duplicateDisposition = authorize(ORG_ONE, "retry-two-again", context ->
                notifications.recordFailure(context, second, "provider.timeout"));
        assertThat(duplicateDisposition)
                .isEqualTo(NotificationFailureDisposition.DEAD_LETTERED);

        var snapshot = authorize(ORG_ONE, "dead-snapshot", notifications::snapshot);
        assertThat(snapshot.deadLetteredNotifications()).isEqualTo(1);
        assertReason(
                () -> authorize(ORG_ONE, "late-ack", context -> {
                    notifications.acknowledge(context, second);
                    return null;
                }),
                "notification-lease-not-owned");
    }

    @Test
    void reclaimsExpiredLeasesAndRejectsTheStaleOwner() {
        authorize(ORG_ONE, "lease-enqueue", context -> {
            notifications.enqueue(
                    context,
                    dueNotification(UUID.randomUUID(), "lease-dedup", "lease"));
            return null;
        });
        var first = authorize(ORG_ONE, "lease-one", firstClaim());
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertThat(Boolean.TRUE.equals(
                        authorize(
                                ORG_ONE,
                                "lease-expired-check",
                                ignored -> jdbcTemplate.queryForObject(
                                        "select claimed_until <= clock_timestamp() from durable_notifications",
                                        Boolean.class))))
                .isTrue());
        var second = authorize(ORG_ONE, "lease-two", firstClaim());
        assertThat(second.attempt()).isEqualTo(2);
        assertThat(second.leaseToken()).isNotEqualTo(first.leaseToken());

        assertReason(
                () -> authorize(ORG_ONE, "stale-ack", context -> {
                    notifications.acknowledge(context, first);
                    return null;
                }),
                "notification-lease-not-owned");
        authorize(ORG_ONE, "current-ack", context -> {
            notifications.acknowledge(context, second);
            return null;
        });
    }

    @Test
    void deadLettersCiphertextCorruptionWithoutReturningParameters() throws SQLException {
        var notificationId = UUID.randomUUID();
        authorize(ORG_ONE, "corruption-enqueue", context -> {
            notifications.enqueue(
                    context,
                    dueNotification(notificationId, "corruption-dedup", "never-return"));
            return null;
        });
        mutateAsOwner("""
                UPDATE durable_notifications
                SET encrypted_payload = set_byte(
                    encrypted_payload, 0, get_byte(encrypted_payload, 0) # 1)
                WHERE notification_id = '%s'
                """.formatted(notificationId));

        var corruptClaims = authorize(
                ORG_ONE, "corruption-claim", context -> notifications.claim(context, 1));
        assertThat(corruptClaims).isEmpty();
        var corruptState = authorize(
                        ORG_ONE,
                        "corruption-state",
                        ignored -> jdbcTemplate.queryForMap(
                                "select status, last_error_code from durable_notifications"));
        assertThat(corruptState)
                .containsEntry("status", "dead_lettered")
                .containsEntry("last_error_code", "notification.payload-invalid");
    }

    @Test
    void supportsHistoricalDecryptionKeysAndFailsClosedWhenOneIsMissing() {
        var firstId = UUID.randomUUID();
        var secondId = UUID.randomUUID();
        authorize(ORG_ONE, "rotation-enqueue", context -> {
            notifications.enqueue(
                    context,
                    dueNotification(firstId, "rotation-one", "encrypted-with-v1"));
            notifications.enqueue(
                    context,
                    dueNotification(secondId, "rotation-two", "also-v1"));
            return null;
        });

        var rotated = adapter(properties("test-v2", List.of(
                "test-v1:" + KEY_ONE, "test-v2:" + KEY_TWO)));
        var rotatedClaim = authorize(ORG_ONE, "rotation-claim", context ->
                        rotated.claim(context, 1))
                .getFirst();
        assertThat(rotatedClaim.payloadJson()).contains("encrypted-with-v1");
        authorize(ORG_ONE, "rotation-ack", context -> {
            rotated.acknowledge(context, rotatedClaim);
            return null;
        });

        var missingHistoricalKey =
                adapter(properties("test-v2", List.of("test-v2:" + KEY_TWO)));
        assertReason(
                () -> authorize(ORG_ONE, "missing-key-claim", context ->
                        missingHistoricalKey.claim(context, 1)),
                "notification-encryption-key-unavailable");
        var missingKeyState = authorize(
                        ORG_ONE,
                        "missing-key-state",
                        ignored -> jdbcTemplate.queryForObject(
                                "select status from durable_notifications where notification_id = ?",
                                String.class,
                                secondId));
        assertThat(missingKeyState).isEqualTo("ready");
    }

    @Test
    void rejectsInvalidActivationAndUnapprovedTemplatesWithoutLeakingKeys() {
        var missingDefinitions = properties("test-v1", List.of("test-v1:" + KEY_ONE));
        missingDefinitions.setAllowedTemplateDefinitions(List.of());
        assertThatIllegalStateException()
                .isThrownBy(missingDefinitions::validateForActivation)
                .withMessageContaining("allowed definitions");

        var weakKey = properties("test-v1", List.of("test-v1:dG9vLXNob3J0"));
        assertThatIllegalStateException()
                .isThrownBy(weakKey::validateForActivation)
                .withMessageContaining("256-bit");
        assertThat(properties("test-v1", List.of("test-v1:" + KEY_ONE)).toString())
                .doesNotContain(KEY_ONE);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> authorize(ORG_ONE, "unapproved-template", context -> {
                    notifications.enqueue(
                            context,
                            new DurableNotification(
                                    UUID.randomUUID(),
                                    RECIPIENT,
                                    "security.other",
                                    1,
                                    "{}",
                                    "unapproved-template",
                                    Instant.now()));
                    return null;
                }))
                .withMessageContaining("not configured");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> authorize(ORG_ONE, "invalid-json", context -> {
                    notifications.enqueue(
                            context,
                            new DurableNotification(
                                    UUID.randomUUID(),
                                    RECIPIENT,
                                    "security.notice",
                                    1,
                                    "{not-json}",
                                    "invalid-json",
                                    Instant.now()));
                    return null;
                }))
                .withMessageContaining("valid JSON");
    }

    private PostgresDurableNotificationAdapter adapter(
            PostgresNotificationProperties properties) {
        var adapter = new PostgresDurableNotificationAdapter(
                jdbcTemplate, objectMapper, properties, meterRegistry);
        adapter.initialize();
        return adapter;
    }

    private static PostgresNotificationProperties properties(
            String activeKey, List<String> keys) {
        var properties = new PostgresNotificationProperties();
        properties.setEnabled(true);
        properties.setActiveEncryptionKeyId(activeKey);
        properties.setEncryptionKeys(keys);
        properties.setLeaseDuration(Duration.ofMillis(300));
        properties.setMaximumAttempts(2);
        properties.setInitialRetryDelay(Duration.ofMillis(100));
        properties.setMaximumRetryDelay(Duration.ofMillis(200));
        properties.setTerminalRetention(Duration.ofMinutes(1));
        properties.setMaximumScheduleAhead(Duration.ofHours(1));
        properties.setMaximumClaimBatch(10);
        properties.setCleanupBatch(25);
        properties.setAllowedTemplateDefinitions(List.of("security.notice@1"));
        return properties;
    }

    private java.util.function.Function<AuthorizedTenantContext, com.rootopathy.careos.platform.domain.DurableNotificationClaim>
            firstClaim() {
        return context -> notifications.claim(context, 1).getFirst();
    }

    private long notificationCount(AuthorizedTenantContext ignored) {
        return jdbcTemplate.queryForObject(
                "select count(*) from durable_notifications", Long.class);
    }

    private <T> T authorize(
            UUID organizationId,
            String correlationId,
            java.util.function.Function<AuthorizedTenantContext, T> operation) {
        return tenantAuthorization.execute(request(organizationId, correlationId), operation);
    }

    private static TenantAuthorizationRequest request(UUID organizationId, String correlationId) {
        return new TenantAuthorizationRequest(
                organizationId,
                new AuthenticatedActorContext(
                        ACTOR, "notification-mechanics", correlationId),
                new PermissionKey("test.notification-store"));
    }

    private static AuthorizedTenantContext context(UUID organizationId, String correlationId) {
        return new AuthorizedTenantContext(
                organizationId, ACTOR, "notification-mechanics", correlationId);
    }

    private static DurableNotification dueNotification(
            UUID notificationId, String deduplicationKey, String value) {
        return new DurableNotification(
                notificationId,
                RECIPIENT,
                "security.notice",
                1,
                "{\"value\":\"" + value + "\"}",
                deduplicationKey,
                Instant.now().minusSeconds(1));
    }

    private static void assertReason(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String reason) {
        assertThatThrownBy(call)
                .isInstanceOf(DurableNotificationException.class)
                .extracting(exception -> ((DurableNotificationException) exception).reasonCode())
                .isEqualTo(reason);
    }

    private List<String> migratorStrings(String sql) {
        try (var connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), MIGRATOR_USER, MIGRATOR_PASSWORD);
                var statement = connection.createStatement();
                var rows = statement.executeQuery(sql)) {
            var values = new ArrayList<String>();
            while (rows.next()) {
                values.add(rows.getString(1));
            }
            return values;
        } catch (SQLException exception) {
            throw new AssertionError(exception);
        }
    }

    private void mutateAsOwner(String sql) throws SQLException {
        try (var connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), MIGRATOR_USER, MIGRATOR_PASSWORD);
                var statement = connection.createStatement()) {
            statement.execute("ALTER TABLE durable_notifications DISABLE TRIGGER USER");
            try {
                statement.executeUpdate(sql);
            } finally {
                statement.execute("ALTER TABLE durable_notifications ENABLE TRIGGER USER");
            }
        }
    }
}
