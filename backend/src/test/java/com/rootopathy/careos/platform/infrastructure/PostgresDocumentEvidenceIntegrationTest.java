package com.rootopathy.careos.platform.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rootopathy.careos.platform.application.DocumentEvidenceException;
import com.rootopathy.careos.platform.application.DocumentEvidenceOperations;
import com.rootopathy.careos.platform.domain.DocumentAccessAuthorization;
import com.rootopathy.careos.platform.domain.DocumentAccessPolicy;
import com.rootopathy.careos.platform.domain.DocumentObjectReference;
import com.rootopathy.careos.platform.domain.DocumentPromotionEvidence;
import com.rootopathy.careos.platform.domain.DocumentPromotionPolicy;
import com.rootopathy.careos.platform.domain.DocumentQuarantineRequest;
import com.rootopathy.careos.platform.domain.DocumentRetentionAuthorization;
import com.rootopathy.careos.platform.domain.DocumentRetentionPolicy;
import com.rootopathy.careos.platform.domain.DocumentRetentionReceipt;
import com.rootopathy.careos.platform.domain.MalwareScanResult;
import com.rootopathy.careos.platform.domain.MalwareScanVerdict;
import com.rootopathy.careos.tenancy.application.TenantAuthorizationOperations;
import com.rootopathy.careos.tenancy.domain.AuthenticatedActorContext;
import com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext;
import com.rootopathy.careos.tenancy.domain.OperationKey;
import com.rootopathy.careos.tenancy.domain.TenantAuthorizationRequest;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
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
class PostgresDocumentEvidenceIntegrationTest {
    private static final String MIGRATOR_USER = "careos_migrator";
    private static final String MIGRATOR_PASSWORD = "careos-migrator-test-only";
    private static final String APP_USER = "careos_app";
    private static final String APP_PASSWORD = "careos-app-test-only";
    private static final UUID ORG_ONE =
            UUID.fromString("01900000-0000-7000-8000-000000000001");
    private static final UUID ORG_TWO =
            UUID.fromString("01900000-0000-7000-8000-000000000002");
    private static final UUID ACTOR =
            UUID.fromString("01900000-0000-7000-8000-000000000201");
    private static final UUID DOCUMENT_ID =
            UUID.fromString("01900000-0000-7000-8000-000000000501");
    private static final UUID OBJECT_VERSION_ID =
            UUID.fromString("01900000-0000-7000-8000-000000000601");
    private static final String SHA_256 = "a".repeat(64);
    private static final Instant SCANNED_AT = Instant.parse("2026-09-15T06:30:00.123456Z");
    private static final DocumentPromotionPolicy PROMOTION_POLICY = new DocumentPromotionPolicy(
            "foundation.synthetic", Set.of("clamav"), Duration.ofDays(1), Duration.ofSeconds(5));
    private static final DocumentAccessPolicy ACCESS_POLICY = new DocumentAccessPolicy(
            "foundation.synthetic",
            Set.of("document-security"),
            Duration.ofMinutes(10),
            Duration.ofSeconds(30),
            Duration.ofSeconds(5));
    private static final DocumentRetentionPolicy RETENTION_POLICY = new DocumentRetentionPolicy(
            "foundation.synthetic",
            Set.of("document-security"),
            Duration.ofMinutes(1),
            Duration.ofDays(30),
            Duration.ofSeconds(30),
            Duration.ofSeconds(5));
    private static final String STORAGE_VERSION_SHA256 = "b".repeat(64);

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
    }

    @Autowired
    private DocumentEvidenceOperations evidence;

    @Autowired
    private TenantAuthorizationOperations tenantAuthorization;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void seedAuthorization() throws SQLException {
        try (var connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), MIGRATOR_USER, MIGRATOR_PASSWORD);
                var statement = connection.createStatement()) {
            statement.executeUpdate(
                    "TRUNCATE document_retention_evidence, document_access_grant_evidence, document_promotion_evidence, "
                            + "document_scan_attestations, document_quarantine_evidence");
            statement.executeUpdate("""
                    INSERT INTO users (id, email, display_name, status)
                    VALUES ('01900000-0000-7000-8000-000000000201',
                            'document.actor@rootopathy.test', 'Document Test Actor', 'active')
                    ON CONFLICT (id) DO NOTHING
                    """);
            statement.executeUpdate("""
                    INSERT INTO organizations
                        (id, legal_name, display_name, organization_type,
                         country_code, timezone, locale, status)
                    VALUES ('01900000-0000-7000-8000-000000000002',
                            'Second Document Test Org', 'Second Document Org',
                            'care_provider', 'IN', 'Asia/Kolkata', 'en-IN', 'active')
                    ON CONFLICT (id) DO UPDATE
                        SET organization_type = 'care_provider', locale = 'en-IN', status = 'active'
                    """);
            statement.executeUpdate("""
                    UPDATE organizations
                    SET organization_type = 'care_network', locale = 'en-IN', status = 'active'
                    WHERE id = '01900000-0000-7000-8000-000000000001'
                    """);
            statement.executeUpdate("""
                    INSERT INTO authorization_permissions
                        (permission_key, display_name, description, registry_version)
                    VALUES ('test.document-evidence', 'Document evidence test',
                            'Synthetic document evidence permission', 'test-v1')
                    ON CONFLICT (permission_key) DO NOTHING
                    """);
            statement.executeUpdate("""
                    INSERT INTO authorization_roles
                        (role_key, display_name, description, registry_version)
                    VALUES ('document_test_actor', 'Document test actor',
                            'Synthetic document evidence role', 'test-v1')
                    ON CONFLICT (role_key) DO NOTHING
                    """);
            statement.executeUpdate("""
                    INSERT INTO authorization_role_permissions (role_key, permission_key)
                    VALUES ('document_test_actor', 'test.document-evidence')
                    ON CONFLICT (role_key, permission_key) DO NOTHING
                    """);
            statement.executeUpdate("""
                    INSERT INTO authorization_operations
                        (operation_key, permission_key, display_name, description, registry_version)
                    VALUES ('test.document-evidence', 'test.document-evidence',
                            'Document evidence test operation',
                            'Synthetic document evidence operation', 'test-v1')
                    ON CONFLICT (operation_key) DO NOTHING
                    """);
            statement.executeUpdate("""
                    INSERT INTO organization_memberships
                        (organization_id, user_id, role_key, status, effective_from)
                    VALUES
                        ('01900000-0000-7000-8000-000000000001',
                         '01900000-0000-7000-8000-000000000201',
                         'document_test_actor', 'active',
                         clock_timestamp() - interval '1 minute'),
                        ('01900000-0000-7000-8000-000000000002',
                         '01900000-0000-7000-8000-000000000201',
                         'document_test_actor', 'active',
                         clock_timestamp() - interval '1 minute')
                    ON CONFLICT (organization_id, user_id, role_key)
                    DO UPDATE SET status = 'active',
                                  effective_from = EXCLUDED.effective_from,
                                  effective_to = NULL
                    """);
        }
    }

    @Test
    void requiresTheAuthorizedTransactionAndForcedRlsHidesEveryEvidenceRow() {
        var request = quarantineRequest();
        var reference = reference(ORG_ONE);
        assertThatIllegalStateException()
                .isThrownBy(() -> evidence.recordQuarantine(
                        context(ORG_ONE, "outside-transaction"), request, reference))
                .withMessageContaining("writable tenant transaction");

        authorize(ORG_ONE, "tenant-one-create", context -> {
            evidence.recordQuarantine(context, request, reference);
            return null;
        });

        assertThat(jdbcTemplate.queryForObject(
                        "select count(*) from document_quarantine_evidence", Long.class))
                .isZero();
        boolean tenantOneVisible = authorize(
                ORG_ONE,
                "tenant-one-read",
                context -> evidence.findQuarantine(context, reference).isPresent());
        boolean tenantTwoVisible = authorize(
                ORG_TWO,
                "tenant-two-read",
                context -> evidence.findQuarantine(context, reference(ORG_TWO)).isPresent());
        assertThat(tenantOneVisible).isTrue();
        assertThat(tenantTwoVisible).isFalse();
    }

    @Test
    void storesExactQuarantineMetadataIdempotentlyAndRejectsConflictingReplays()
            throws SQLException {
        var request = quarantineRequest();
        var reference = reference(ORG_ONE);
        var first = authorize(
                ORG_ONE,
                "quarantine-first",
                context -> evidence.recordQuarantine(context, request, reference));
        var replay = authorize(
                ORG_ONE,
                "quarantine-replay",
                context -> evidence.recordQuarantine(context, request, reference));

        assertThat(replay).isEqualTo(first);
        assertReason(
                () -> authorize(ORG_ONE, "quarantine-conflict", context -> evidence.recordQuarantine(
                        context,
                        new DocumentQuarantineRequest(
                                DOCUMENT_ID,
                                OBJECT_VERSION_ID,
                                request.declaredBytes() + 1,
                                request.mediaType(),
                                request.sha256()),
                        reference)),
                "document-quarantine-evidence-conflict");

        var stored = migratorRow("""
                SELECT declared_bytes, media_type, sha256, recorded_by_actor_id,
                       purpose, correlation_id
                FROM document_quarantine_evidence
                """);
        assertThat(stored)
                .containsEntry("declared_bytes", 128L)
                .containsEntry("media_type", "application/pdf")
                .containsEntry("sha256", SHA_256)
                .containsEntry("recorded_by_actor_id", ACTOR)
                .containsEntry("purpose", "document-security")
                .containsEntry("correlation_id", "quarantine-first");
    }

    @Test
    void appendsIdempotentScanObservationsAndReturnsOnlyTheLatest() {
        var reference = createQuarantine();
        var clean = scan(
                reference, MalwareScanVerdict.CLEAN, "clamav", "20260915.1", SHA_256, SCANNED_AT);
        var first = authorize(
                ORG_ONE, "scan-clean", context -> evidence.recordScan(context, clean));
        var replay = authorize(
                ORG_ONE, "scan-clean-replay", context -> evidence.recordScan(context, clean));
        assertThat(replay.attestationId()).isEqualTo(first.attestationId());

        var error = scan(
                reference,
                MalwareScanVerdict.ERROR,
                "clamav",
                "unknown",
                "0".repeat(64),
                SCANNED_AT.plusSeconds(1));
        var errorEvidence = authorize(
                ORG_ONE, "scan-error", context -> evidence.recordScan(context, error));
        var latest = authorize(
                        ORG_ONE,
                        "scan-latest",
                        context -> evidence.findLatestScan(context, reference))
                .orElseThrow();

        assertThat(latest).isEqualTo(errorEvidence);
        long scanCount = authorize(ORG_ONE, "scan-count", ignored -> jdbcTemplate.queryForObject(
                "select count(*) from document_scan_attestations", Long.class));
        assertThat(scanCount).isEqualTo(2L);
    }

    @Test
    void rejectsMissingMismatchedAndAmbiguousSuccessfulScanEvidence() {
        var reference = reference(ORG_ONE);
        var missing = scan(
                reference, MalwareScanVerdict.CLEAN, "clamav", "20260915.1", SHA_256, SCANNED_AT);
        assertReason(
                () -> authorize(
                        ORG_ONE, "scan-missing", context -> evidence.recordScan(context, missing)),
                "document-quarantine-evidence-not-found");

        createQuarantine();
        var digestMismatch = scan(
                reference,
                MalwareScanVerdict.INFECTED,
                "clamav",
                "20260915.1",
                "b".repeat(64),
                SCANNED_AT);
        assertReason(
                () -> authorize(ORG_ONE, "scan-digest-mismatch", context ->
                        evidence.recordScan(context, digestMismatch)),
                "document-scan-evidence-digest-mismatch");

        var firstError = scan(
                reference,
                MalwareScanVerdict.ERROR,
                "clamav",
                "unknown",
                "0".repeat(64),
                SCANNED_AT.plusSeconds(2));
        authorize(ORG_ONE, "scan-error-first", context -> evidence.recordScan(context, firstError));
        var conflictingObservation = scan(
                reference,
                MalwareScanVerdict.ERROR,
                "clamav",
                "different",
                "0".repeat(64),
                firstError.scannedAt());
        assertReason(
                () -> authorize(ORG_ONE, "scan-error-conflict", context ->
                        evidence.recordScan(context, conflictingObservation)),
                "document-scan-evidence-conflict");
    }

    @Test
    void rollsBackEvidenceWithTheAuthorizedTransaction() {
        var reference = reference(ORG_ONE);
        assertThatThrownBy(() -> authorize(ORG_ONE, "rollback", context -> {
                    evidence.recordQuarantine(context, quarantineRequest(), reference);
                    throw new IllegalStateException("synthetic rollback");
                }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("synthetic rollback");

        boolean persisted = authorize(
                ORG_ONE,
                "rollback-check",
                context -> evidence.findQuarantine(context, reference).isPresent());
        assertThat(persisted).isFalse();
    }

    @Test
    void recordsCleanPromotionEvidenceWithAnImmutablePolicySnapshot() throws SQLException {
        var reference = createQuarantine();
        var clean = scan(
                reference,
                MalwareScanVerdict.CLEAN,
                "clamav",
                "20260915.1",
                SHA_256,
                Instant.now().minusSeconds(30));
        var attestation = authorize(
                ORG_ONE, "promotion-scan", context -> evidence.recordScan(context, clean));

        assertThatThrownBy(() -> authorize(ORG_ONE, "promotion-unapproved-scanner", context -> {
                    jdbcTemplate.update(
                            """
                            INSERT INTO document_promotion_evidence
                                (organization_id, document_id, object_version_id,
                                 scan_attestation_id, promotion_policy_key,
                                 accepted_scanner_keys, maximum_scan_age_seconds,
                                 maximum_future_skew_seconds, promoted_by_actor_id,
                                 purpose, correlation_id)
                            VALUES (?, ?, ?, ?, 'foundation.synthetic', 'other-scanner',
                                    86400, 5, ?, ?, ?)
                            """,
                            context.organizationId(),
                            reference.documentId(),
                            reference.objectVersionId(),
                            attestation.attestationId(),
                            context.actorId(),
                            context.purpose(),
                            context.correlationId());
                    return null;
                }))
                .hasRootCauseInstanceOf(PSQLException.class);

        var first = authorize(ORG_ONE, "promotion-first", context ->
                evidence.recordPromotion(context, attestation, PROMOTION_POLICY));
        var replay = authorize(ORG_ONE, "promotion-replay", context ->
                evidence.recordPromotion(context, attestation, PROMOTION_POLICY));
        var found = authorize(ORG_ONE, "promotion-read", context ->
                        evidence.findPromotion(context, reference))
                .orElseThrow();

        assertThat(replay).isEqualTo(first);
        assertThat(found).isEqualTo(first);
        assertThat(first.scanAttestation()).isEqualTo(attestation);
        assertThat(first.policyKey()).isEqualTo("foundation.synthetic");
        assertThat(first.maximumScanAge()).isEqualTo(Duration.ofDays(1));
        assertThat(first.maximumFutureSkew()).isEqualTo(Duration.ofSeconds(5));

        var stored = migratorRow("""
                SELECT scan_attestation_id, promotion_policy_key,
                       accepted_scanner_keys,
                       maximum_scan_age_seconds, maximum_future_skew_seconds,
                       promoted_by_actor_id, purpose, correlation_id
                FROM document_promotion_evidence
                """);
        assertThat(stored)
                .containsEntry("scan_attestation_id", attestation.attestationId())
                .containsEntry("promotion_policy_key", "foundation.synthetic")
                .containsEntry("accepted_scanner_keys", "clamav")
                .containsEntry("maximum_scan_age_seconds", 86400)
                .containsEntry("maximum_future_skew_seconds", 5)
                .containsEntry("promoted_by_actor_id", ACTOR)
                .containsEntry("purpose", "document-security")
                .containsEntry("correlation_id", "promotion-first");
    }

    @Test
    void rejectsNonCleanStaleAndConflictingPromotionEvidence() {
        var reference = createQuarantine();
        var olderClean = authorize(ORG_ONE, "promotion-older-clean-scan", context -> evidence.recordScan(
                context,
                scan(
                        reference,
                        MalwareScanVerdict.CLEAN,
                        "clamav",
                        "20260915.0",
                        SHA_256,
                        Instant.now().minusSeconds(240))));
        var infected = authorize(ORG_ONE, "promotion-infected-scan", context -> evidence.recordScan(
                context,
                scan(
                        reference,
                        MalwareScanVerdict.INFECTED,
                        "clamav",
                        "20260915.1",
                        SHA_256,
                        Instant.now().minusSeconds(180))));
        assertReason(
                () -> authorize(ORG_ONE, "promotion-not-latest", context ->
                        evidence.recordPromotion(context, olderClean, PROMOTION_POLICY)),
                "document-evidence-store-rejected");
        assertReason(
                () -> authorize(ORG_ONE, "promotion-infected", context ->
                        evidence.recordPromotion(context, infected, PROMOTION_POLICY)),
                "document-evidence-store-rejected");

        var staleScan = authorize(ORG_ONE, "promotion-stale-scan", context -> evidence.recordScan(
                context,
                scan(
                        reference,
                        MalwareScanVerdict.CLEAN,
                        "clamav",
                        "20260915.2",
                        SHA_256,
                        Instant.now().minusSeconds(120))));
        var oneMinutePolicy = new DocumentPromotionPolicy(
                "foundation.short", Set.of("clamav"), Duration.ofMinutes(1), Duration.ZERO);
        assertReason(
                () -> authorize(ORG_ONE, "promotion-stale", context ->
                        evidence.recordPromotion(context, staleScan, oneMinutePolicy)),
                "document-evidence-store-rejected");

        var clean = authorize(ORG_ONE, "promotion-clean-scan", context -> evidence.recordScan(
                context,
                scan(
                        reference,
                        MalwareScanVerdict.CLEAN,
                        "clamav",
                        "20260915.3",
                        SHA_256,
                        Instant.now().minusSeconds(10))));
        authorize(ORG_ONE, "promotion-clean", context ->
                evidence.recordPromotion(context, clean, PROMOTION_POLICY));
        var anotherPolicy = new DocumentPromotionPolicy(
                "foundation.changed", Set.of("clamav"), Duration.ofHours(2), Duration.ZERO);
        assertReason(
                () -> authorize(ORG_ONE, "promotion-conflict", context ->
                        evidence.recordPromotion(context, clean, anotherPolicy)),
                "document-promotion-evidence-conflict");
    }

    @Test
    void recordsBoundedAccessGrantEvidenceWithoutPersistingTheBearerUrl() throws SQLException {
        var promotion = createPromotion();
        var grantId = UUID.randomUUID();
        var authorization = accessAuthorization(
                grantId, promotion, ACCESS_POLICY, Duration.ofMinutes(5), Instant.now());

        var first = authorize(ORG_ONE, "access-first", context ->
                evidence.recordAccessGrant(context, authorization));
        var replay = authorize(ORG_ONE, "access-first", context ->
                evidence.recordAccessGrant(context, authorization));
        var found = authorize(ORG_ONE, "access-read", context ->
                        evidence.findAccessGrant(context, promotion.document(), grantId))
                .orElseThrow();

        assertThat(replay).isEqualTo(first);
        assertThat(found).isEqualTo(first);
        assertThat(first.accessGrantId()).isEqualTo(grantId);
        assertThat(first.promotionEvidence()).isEqualTo(promotion);
        assertThat(first.policyKey()).isEqualTo("foundation.synthetic");
        assertThat(first.acceptedPurposes()).containsExactly("document-security");
        assertThat(first.requestedTtl()).isEqualTo(Duration.ofMinutes(5));
        assertThat(first.maximumTtl()).isEqualTo(Duration.ofMinutes(10));
        assertThat(first.actorId()).isEqualTo(ACTOR);
        assertThat(first.purpose()).isEqualTo("document-security");
        assertThat(first.correlationId()).isEqualTo("access-first");
        assertThat(first.expiresAt()).isEqualTo(first.authorizedAt().plus(Duration.ofMinutes(5)));

        var stored = migratorRow("""
                SELECT access_policy_key, accepted_purposes, requested_ttl_seconds,
                       maximum_ttl_seconds, maximum_authorization_age_seconds,
                       maximum_future_skew_seconds, granted_to_actor_id,
                       purpose, correlation_id
                FROM document_access_grant_evidence
                """);
        assertThat(stored)
                .containsEntry("access_policy_key", "foundation.synthetic")
                .containsEntry("accepted_purposes", "document-security")
                .containsEntry("requested_ttl_seconds", 300)
                .containsEntry("maximum_ttl_seconds", 600)
                .containsEntry("maximum_authorization_age_seconds", 30)
                .containsEntry("maximum_future_skew_seconds", 5)
                .containsEntry("granted_to_actor_id", ACTOR)
                .containsEntry("purpose", "document-security")
                .containsEntry("correlation_id", "access-first");
        assertThat(migratorRow("""
                        SELECT count(*) AS bearer_columns
                        FROM information_schema.columns
                        WHERE table_schema = 'public'
                          AND table_name = 'document_access_grant_evidence'
                          AND column_name ~ '(url|token|signature)'
                        """))
                .containsEntry("bearer_columns", 0L);

        var crossTenant = authorize(ORG_TWO, "access-cross-tenant-read", context ->
                evidence.findAccessGrant(context, reference(ORG_TWO), grantId));
        assertThat(crossTenant).isEmpty();
        assertThat(jdbcTemplate.queryForObject(
                        "select count(*) from document_access_grant_evidence", Long.class))
                .isZero();
    }

    @Test
    void rejectsUnpromotedUnapprovedStaleAndConflictingAccessEvidence() {
        var unpersistedPromotion = promotionEvidence(reference(ORG_ONE), Instant.now().minusSeconds(10));
        var unpersistedAuthorization = accessAuthorization(
                UUID.randomUUID(),
                unpersistedPromotion,
                ACCESS_POLICY,
                Duration.ofMinutes(5),
                Instant.now());
        assertReason(
                () -> authorize(ORG_ONE, "access-unpromoted", context ->
                        evidence.recordAccessGrant(context, unpersistedAuthorization)),
                "document-evidence-store-rejected");

        var promotion = createPromotion();
        var directGrantId = UUID.randomUUID();
        assertThatThrownBy(() -> authorize(ORG_ONE, "access-purpose-sql", context -> {
                    var authorizedAt = Instant.now();
                    jdbcTemplate.update(
                            """
                            INSERT INTO document_access_grant_evidence
                                (organization_id, access_grant_id, document_id, object_version_id,
                                 access_policy_key, accepted_purposes, requested_ttl_seconds,
                                 maximum_ttl_seconds, maximum_authorization_age_seconds,
                                 maximum_future_skew_seconds, authorized_at,
                                 granted_to_actor_id, purpose, correlation_id, expires_at)
                            VALUES (?, ?, ?, ?, 'foundation.synthetic', 'other-purpose',
                                    300, 600, 30, 5, ?, ?, ?, ?, ?)
                            """,
                            context.organizationId(),
                            directGrantId,
                            promotion.document().documentId(),
                            promotion.document().objectVersionId(),
                            java.sql.Timestamp.from(authorizedAt),
                            context.actorId(),
                            context.purpose(),
                            context.correlationId(),
                            java.sql.Timestamp.from(authorizedAt.plusSeconds(300)));
                    return null;
                }))
                .hasRootCauseInstanceOf(PSQLException.class);

        assertThatThrownBy(() -> authorize(ORG_ONE, "access-expiry-sql", context -> {
                    var authorizedAt = Instant.now();
                    jdbcTemplate.update(
                            """
                            INSERT INTO document_access_grant_evidence
                                (organization_id, access_grant_id, document_id, object_version_id,
                                 access_policy_key, accepted_purposes, requested_ttl_seconds,
                                 maximum_ttl_seconds, maximum_authorization_age_seconds,
                                 maximum_future_skew_seconds, authorized_at,
                                 granted_to_actor_id, purpose, correlation_id, expires_at)
                            VALUES (?, ?, ?, ?, 'foundation.synthetic', 'document-security',
                                    300, 600, 30, 5, ?, ?, ?, ?, ?)
                            """,
                            context.organizationId(),
                            UUID.randomUUID(),
                            promotion.document().documentId(),
                            promotion.document().objectVersionId(),
                            java.sql.Timestamp.from(authorizedAt),
                            context.actorId(),
                            context.purpose(),
                            context.correlationId(),
                            java.sql.Timestamp.from(authorizedAt.plusSeconds(301)));
                    return null;
                }))
                .hasRootCauseInstanceOf(PSQLException.class);

        var stale = accessAuthorization(
                UUID.randomUUID(),
                promotion,
                ACCESS_POLICY,
                Duration.ofMinutes(5),
                Instant.now().minusSeconds(120));
        assertReason(
                () -> authorize(ORG_ONE, "access-stale", context ->
                        evidence.recordAccessGrant(context, stale)),
                "document-evidence-store-rejected");

        var grantId = UUID.randomUUID();
        var first = accessAuthorization(
                grantId, promotion, ACCESS_POLICY, Duration.ofMinutes(5), Instant.now());
        authorize(ORG_ONE, "access-conflict", context ->
                evidence.recordAccessGrant(context, first));
        var changedPolicy = new DocumentAccessPolicy(
                "foundation.changed",
                Set.of("document-security"),
                Duration.ofMinutes(10),
                Duration.ofSeconds(30),
                Duration.ofSeconds(5));
        var changed = accessAuthorization(
                grantId,
                promotion,
                changedPolicy,
                Duration.ofMinutes(5),
                first.authorizedAt());
        assertReason(
                () -> authorize(ORG_ONE, "access-conflict", context ->
                        evidence.recordAccessGrant(context, changed)),
                "document-access-evidence-conflict");
    }

    @Test
    void recordsMonotonicRetentionAndLegalHoldEvidenceWithoutStorageIdentifiers()
            throws SQLException {
        var promotion = createPromotion();
        var firstId = UUID.randomUUID();
        var firstAuthorization = retentionAuthorization(
                firstId,
                promotion,
                null,
                Instant.now().plus(Duration.ofDays(7)),
                false,
                Instant.now());
        var firstReceipt = retentionReceipt(firstAuthorization);

        var first = authorize(ORG_ONE, "retention-first", context ->
                evidence.recordRetention(context, firstAuthorization, firstReceipt));
        var replay = authorize(ORG_ONE, "retention-replay", context ->
                evidence.recordRetention(context, firstAuthorization, firstReceipt));
        var found = authorize(ORG_ONE, "retention-find", context ->
                        evidence.findRetention(context, promotion.document(), firstId))
                .orElseThrow();

        assertThat(replay).isEqualTo(first);
        assertThat(found).isEqualTo(first);
        assertThat(first.previousRetentionDirectiveId()).isNull();
        assertThat(first.policyKey()).isEqualTo(RETENTION_POLICY.policyKey());
        assertThat(first.acceptedPurposes()).containsExactly("document-security");
        assertThat(first.minimumRetention()).isEqualTo(Duration.ofMinutes(1));
        assertThat(first.maximumRetention()).isEqualTo(Duration.ofDays(30));
        assertThat(first.storageVersionSha256()).isEqualTo(STORAGE_VERSION_SHA256);
        assertThat(first.legalHold()).isFalse();

        var secondId = UUID.randomUUID();
        var secondAuthorization = retentionAuthorization(
                secondId,
                promotion,
                firstId,
                first.retainUntil().plus(Duration.ofDays(1)),
                true,
                Instant.now());
        var second = authorize(ORG_ONE, "retention-second", context -> evidence.recordRetention(
                context, secondAuthorization, retentionReceipt(secondAuthorization)));
        var latest = authorize(ORG_ONE, "retention-latest", context ->
                        evidence.findLatestRetention(context, promotion.document()))
                .orElseThrow();
        assertThat(latest).isEqualTo(second);
        assertThat(second.previousRetentionDirectiveId()).isEqualTo(firstId);
        assertThat(second.retainUntil()).isAfter(first.retainUntil());
        assertThat(second.legalHold()).isTrue();

        var stored = migratorRow("""
                SELECT retention_policy_key, accepted_purposes,
                       minimum_retention_seconds, maximum_retention_seconds,
                       maximum_authorization_age_seconds, maximum_future_skew_seconds,
                       retention_mode, legal_hold, storage_version_sha256,
                       applied_by_actor_id, purpose, correlation_id
                FROM document_retention_evidence
                WHERE retention_directive_id = '%s'
                """.formatted(secondId));
        assertThat(stored)
                .containsEntry("retention_policy_key", "foundation.synthetic")
                .containsEntry("accepted_purposes", "document-security")
                .containsEntry("minimum_retention_seconds", 60L)
                .containsEntry("maximum_retention_seconds", 2_592_000L)
                .containsEntry("maximum_authorization_age_seconds", 30)
                .containsEntry("maximum_future_skew_seconds", 5)
                .containsEntry("retention_mode", "compliance")
                .containsEntry("legal_hold", true)
                .containsEntry("storage_version_sha256", STORAGE_VERSION_SHA256)
                .containsEntry("applied_by_actor_id", ACTOR)
                .containsEntry("purpose", "document-security")
                .containsEntry("correlation_id", "retention-second");
        assertThat(migratorRow("""
                        SELECT count(*) AS raw_storage_columns
                        FROM information_schema.columns
                        WHERE table_schema = 'public'
                          AND table_name = 'document_retention_evidence'
                          AND column_name IN ('bucket', 'object_key', 'storage_version_id')
                        """))
                .containsEntry("raw_storage_columns", 0L);

        var crossTenant = authorize(ORG_TWO, "retention-cross-tenant", context ->
                evidence.findRetention(context, reference(ORG_TWO), firstId));
        assertThat(crossTenant).isEmpty();
        assertThat(jdbcTemplate.queryForObject(
                        "select count(*) from document_retention_evidence", Long.class))
                .isZero();
    }

    @Test
    void databaseRejectsUnpromotedStaleNonMonotonicAndConflictingRetentionEvidence() {
        var unpersistedPromotion = promotionEvidence(reference(ORG_ONE), Instant.now().minusSeconds(10));
        var unpersisted = retentionAuthorization(
                UUID.randomUUID(),
                unpersistedPromotion,
                null,
                Instant.now().plus(Duration.ofDays(7)),
                false,
                Instant.now());
        assertReason(
                () -> authorize(ORG_ONE, "retention-unpromoted", context -> evidence.recordRetention(
                        context, unpersisted, retentionReceipt(unpersisted))),
                "document-evidence-store-rejected");

        var promotion = createPromotion();
        var stale = retentionAuthorization(
                UUID.randomUUID(),
                promotion,
                null,
                Instant.now().plus(Duration.ofDays(7)),
                false,
                Instant.now().minusSeconds(120));
        assertReason(
                () -> authorize(ORG_ONE, "retention-stale", context ->
                        evidence.recordRetention(context, stale, retentionReceipt(stale))),
                "document-evidence-store-rejected");

        assertThatThrownBy(() -> authorize(ORG_ONE, "retention-purpose-sql", context -> {
                    var authorizedAt = Instant.now();
                    jdbcTemplate.update(
                            """
                            INSERT INTO document_retention_evidence
                                (organization_id, retention_directive_id, document_id,
                                 object_version_id, previous_retention_directive_id,
                                 retention_policy_key, accepted_purposes,
                                 minimum_retention_seconds, maximum_retention_seconds,
                                 maximum_authorization_age_seconds, maximum_future_skew_seconds,
                                 retain_until, legal_hold, retention_mode,
                                 storage_version_sha256, authorized_at,
                                 applied_by_actor_id, purpose, correlation_id)
                            VALUES (?, ?, ?, ?, NULL, 'foundation.synthetic', 'other-purpose',
                                    60, 2592000, 30, 5, ?, false, 'compliance', ?, ?, ?, ?, ?)
                            """,
                            context.organizationId(),
                            UUID.randomUUID(),
                            promotion.document().documentId(),
                            promotion.document().objectVersionId(),
                            java.sql.Timestamp.from(authorizedAt.plus(Duration.ofDays(7))),
                            STORAGE_VERSION_SHA256,
                            java.sql.Timestamp.from(authorizedAt),
                            context.actorId(),
                            context.purpose(),
                            context.correlationId());
                    return null;
                }))
                .hasRootCauseInstanceOf(PSQLException.class);

        var firstId = UUID.randomUUID();
        var first = retentionAuthorization(
                firstId,
                promotion,
                null,
                Instant.now().plus(Duration.ofDays(10)),
                true,
                Instant.now());
        authorize(ORG_ONE, "retention-chain", context ->
                evidence.recordRetention(context, first, retentionReceipt(first)));

        var shorter = retentionAuthorization(
                UUID.randomUUID(),
                promotion,
                firstId,
                first.retainUntil().minus(Duration.ofDays(1)),
                true,
                Instant.now());
        assertReason(
                () -> authorize(ORG_ONE, "retention-shorter", context ->
                        evidence.recordRetention(context, shorter, retentionReceipt(shorter))),
                "document-evidence-store-rejected");

        var release = retentionAuthorization(
                UUID.randomUUID(),
                promotion,
                firstId,
                first.retainUntil().plus(Duration.ofDays(1)),
                false,
                Instant.now());
        assertReason(
                () -> authorize(ORG_ONE, "retention-release", context ->
                        evidence.recordRetention(context, release, retentionReceipt(release))),
                "document-evidence-store-rejected");

        var stalePredecessor = retentionAuthorization(
                UUID.randomUUID(),
                promotion,
                null,
                first.retainUntil().plus(Duration.ofDays(1)),
                true,
                Instant.now());
        assertReason(
                () -> authorize(ORG_ONE, "retention-predecessor", context -> evidence.recordRetention(
                        context, stalePredecessor, retentionReceipt(stalePredecessor))),
                "document-evidence-store-rejected");

        assertReason(
                () -> authorize(ORG_ONE, "retention-chain", context -> evidence.recordRetention(
                        context,
                        first,
                        new DocumentRetentionReceipt(
                                first.document(),
                                first.retainUntil(),
                                first.legalHold(),
                                "c".repeat(64)))),
                "document-retention-evidence-conflict");
    }

    @Test
    void databaseRejectsCrossTenantWritesAndMutationEvenForTheMigrationOwner()
            throws SQLException {
        var reference = createQuarantine();
        var clean = scan(
                reference,
                MalwareScanVerdict.CLEAN,
                "clamav",
                "20260915.1",
                SHA_256,
                Instant.now().minusSeconds(10));
        var attestation = authorize(
                ORG_ONE, "immutable-scan", context -> evidence.recordScan(context, clean));
        authorize(ORG_ONE, "immutable-promotion", context ->
                evidence.recordPromotion(context, attestation, PROMOTION_POLICY));
        var promotion = authorize(ORG_ONE, "immutable-promotion-read", context ->
                        evidence.findPromotion(context, reference))
                .orElseThrow();
        var accessAuthorization = accessAuthorization(
                UUID.randomUUID(), promotion, ACCESS_POLICY, Duration.ofMinutes(5), Instant.now());
        authorize(ORG_ONE, "immutable-access", context ->
                evidence.recordAccessGrant(context, accessAuthorization));
        var retentionAuthorization = retentionAuthorization(
                UUID.randomUUID(),
                promotion,
                null,
                Instant.now().plus(Duration.ofDays(7)),
                true,
                Instant.now());
        authorize(ORG_ONE, "immutable-retention", context -> evidence.recordRetention(
                context, retentionAuthorization, retentionReceipt(retentionAuthorization)));

        assertThatThrownBy(() -> authorize(ORG_ONE, "cross-tenant-insert", context -> {
                    jdbcTemplate.update(
                            """
                            INSERT INTO document_quarantine_evidence
                                (organization_id, document_id, object_version_id,
                                 declared_bytes, media_type, sha256, recorded_by_actor_id,
                                 purpose, correlation_id)
                            VALUES (?, ?, ?, 1, 'application/pdf', ?, ?, ?, ?)
                            """,
                            ORG_TWO,
                            UUID.randomUUID(),
                            UUID.randomUUID(),
                            SHA_256,
                            context.actorId(),
                            context.purpose(),
                            context.correlationId());
                    return null;
                }))
                .hasRootCauseInstanceOf(PSQLException.class);

        assertThatThrownBy(() -> executeAsMigrator(
                        "UPDATE document_quarantine_evidence SET media_type = 'text/plain'"))
                .isInstanceOf(PSQLException.class)
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> executeAsMigrator(
                        "DELETE FROM document_scan_attestations"))
                .isInstanceOf(PSQLException.class)
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> executeAsMigrator(
                        "DELETE FROM document_promotion_evidence"))
                .isInstanceOf(PSQLException.class)
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> executeAsMigrator(
                        "DELETE FROM document_access_grant_evidence"))
                .isInstanceOf(PSQLException.class)
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> executeAsMigrator(
                        "UPDATE document_retention_evidence SET legal_hold = false"))
                .isInstanceOf(PSQLException.class)
                .hasMessageContaining("append-only");

        var security = migratorRow("""
                SELECT objects.relrowsecurity AS objects_rls,
                       objects.relforcerowsecurity AS objects_forced,
                       scans.relrowsecurity AS scans_rls,
                       scans.relforcerowsecurity AS scans_forced,
                       promotions.relrowsecurity AS promotions_rls,
                       promotions.relforcerowsecurity AS promotions_forced,
                       grants.relrowsecurity AS access_rls,
                       grants.relforcerowsecurity AS access_forced,
                       retention.relrowsecurity AS retention_rls,
                       retention.relforcerowsecurity AS retention_forced,
                       has_table_privilege('careos_app', 'document_quarantine_evidence', 'UPDATE')
                           AS objects_update,
                       has_table_privilege('careos_app', 'document_scan_attestations', 'DELETE')
                           AS scans_delete,
                       has_table_privilege('careos_app', 'document_promotion_evidence', 'UPDATE')
                           AS promotions_update,
                       has_table_privilege('careos_app', 'document_access_grant_evidence', 'DELETE')
                           AS access_delete,
                       has_table_privilege('careos_app', 'document_retention_evidence', 'UPDATE')
                           AS retention_update,
                       has_table_privilege('careos_app', 'document_retention_evidence', 'DELETE')
                           AS retention_delete
                FROM pg_class objects
                JOIN pg_namespace object_schema ON object_schema.oid = objects.relnamespace
                JOIN pg_class scans ON scans.relname = 'document_scan_attestations'
                JOIN pg_namespace scan_schema ON scan_schema.oid = scans.relnamespace
                JOIN pg_class promotions ON promotions.relname = 'document_promotion_evidence'
                JOIN pg_namespace promotion_schema ON promotion_schema.oid = promotions.relnamespace
                JOIN pg_class grants ON grants.relname = 'document_access_grant_evidence'
                JOIN pg_namespace access_schema ON access_schema.oid = grants.relnamespace
                JOIN pg_class retention ON retention.relname = 'document_retention_evidence'
                JOIN pg_namespace retention_schema ON retention_schema.oid = retention.relnamespace
                WHERE object_schema.nspname = 'public'
                  AND scan_schema.nspname = 'public'
                  AND promotion_schema.nspname = 'public'
                  AND access_schema.nspname = 'public'
                  AND retention_schema.nspname = 'public'
                  AND objects.relname = 'document_quarantine_evidence'
                """);
        assertThat(security)
                .containsEntry("objects_rls", true)
                .containsEntry("objects_forced", true)
                .containsEntry("scans_rls", true)
                .containsEntry("scans_forced", true)
                .containsEntry("promotions_rls", true)
                .containsEntry("promotions_forced", true)
                .containsEntry("access_rls", true)
                .containsEntry("access_forced", true)
                .containsEntry("retention_rls", true)
                .containsEntry("retention_forced", true)
                .containsEntry("objects_update", false)
                .containsEntry("scans_delete", false)
                .containsEntry("promotions_update", false)
                .containsEntry("access_delete", false)
                .containsEntry("retention_update", false)
                .containsEntry("retention_delete", false);
    }

    private DocumentObjectReference createQuarantine() {
        var reference = reference(ORG_ONE);
        authorize(ORG_ONE, "quarantine-create", context -> {
            evidence.recordQuarantine(context, quarantineRequest(), reference);
            return null;
        });
        return reference;
    }

    private DocumentPromotionEvidence createPromotion() {
        var reference = createQuarantine();
        var clean = scan(
                reference,
                MalwareScanVerdict.CLEAN,
                "clamav",
                "20260915.access",
                SHA_256,
                Instant.now().minusSeconds(10));
        var attestation = authorize(ORG_ONE, "access-scan", context ->
                evidence.recordScan(context, clean));
        return authorize(ORG_ONE, "access-promotion", context ->
                evidence.recordPromotion(context, attestation, PROMOTION_POLICY));
    }

    private static DocumentPromotionEvidence promotionEvidence(
            DocumentObjectReference reference, Instant promotedAt) {
        var scannedAt = promotedAt.minusSeconds(1);
        return new DocumentPromotionEvidence(
                new com.rootopathy.careos.platform.domain.DocumentScanAttestation(
                        UUID.randomUUID(),
                        scan(
                                reference,
                                MalwareScanVerdict.CLEAN,
                                "clamav",
                                "20260915.access",
                                SHA_256,
                                scannedAt),
                        scannedAt.plusMillis(1)),
                PROMOTION_POLICY.policyKey(),
                PROMOTION_POLICY.acceptedScannerKeys(),
                PROMOTION_POLICY.maximumScanAge(),
                PROMOTION_POLICY.maximumFutureSkew(),
                promotedAt);
    }

    private static DocumentAccessAuthorization accessAuthorization(
            UUID grantId,
            DocumentPromotionEvidence promotion,
            DocumentAccessPolicy policy,
            Duration requestedTtl,
            Instant authorizedAt) {
        return new DocumentAccessAuthorization(
                grantId,
                promotion,
                policy.policyKey(),
                policy.acceptedPurposes(),
                requestedTtl,
                policy.maximumTtl(),
                policy.maximumAuthorizationAge(),
                policy.maximumFutureSkew(),
                "document-security",
                authorizedAt);
    }

    private static DocumentRetentionAuthorization retentionAuthorization(
            UUID directiveId,
            DocumentPromotionEvidence promotion,
            UUID previousDirectiveId,
            Instant retainUntil,
            boolean legalHold,
            Instant authorizedAt) {
        return new DocumentRetentionAuthorization(
                directiveId,
                promotion,
                previousDirectiveId,
                RETENTION_POLICY.policyKey(),
                RETENTION_POLICY.acceptedPurposes(),
                RETENTION_POLICY.minimumRetention(),
                RETENTION_POLICY.maximumRetention(),
                RETENTION_POLICY.maximumAuthorizationAge(),
                RETENTION_POLICY.maximumFutureSkew(),
                retainUntil,
                legalHold,
                "document-security",
                authorizedAt);
    }

    private static DocumentRetentionReceipt retentionReceipt(
            DocumentRetentionAuthorization authorization) {
        return new DocumentRetentionReceipt(
                authorization.document(),
                authorization.retainUntil(),
                authorization.legalHold(),
                STORAGE_VERSION_SHA256);
    }

    private <T> T authorize(
            UUID organizationId, String correlationId, Function<AuthorizedTenantContext, T> operation) {
        return tenantAuthorization.execute(request(organizationId, correlationId), operation);
    }

    private static TenantAuthorizationRequest request(UUID organizationId, String correlationId) {
        return new TenantAuthorizationRequest(
                organizationId,
                new AuthenticatedActorContext(ACTOR, "document-security", correlationId),
                new OperationKey("test.document-evidence"));
    }

    private static AuthorizedTenantContext context(UUID organizationId, String correlationId) {
        return new AuthorizedTenantContext(
                organizationId, ACTOR, "document-security", correlationId);
    }

    private static DocumentObjectReference reference(UUID organizationId) {
        return new DocumentObjectReference(organizationId, DOCUMENT_ID, OBJECT_VERSION_ID);
    }

    private static DocumentQuarantineRequest quarantineRequest() {
        return new DocumentQuarantineRequest(
                DOCUMENT_ID, OBJECT_VERSION_ID, 128, "application/pdf", SHA_256);
    }

    private static MalwareScanResult scan(
            DocumentObjectReference reference,
            MalwareScanVerdict verdict,
            String scannerKey,
            String definitionsVersion,
            String sha256,
            Instant scannedAt) {
        return new MalwareScanResult(
                reference, verdict, scannerKey, definitionsVersion, sha256, scannedAt);
    }

    private static void assertReason(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable invocation, String reasonCode) {
        assertThatThrownBy(invocation)
                .isInstanceOfSatisfying(
                        DocumentEvidenceException.class,
                        exception -> assertThat(exception.reasonCode()).isEqualTo(reasonCode));
    }

    private Map<String, Object> migratorRow(String sql) throws SQLException {
        try (var connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), MIGRATOR_USER, MIGRATOR_PASSWORD);
                var statement = connection.createStatement();
                var rows = statement.executeQuery(sql)) {
            assertThat(rows.next()).isTrue();
            var metadata = rows.getMetaData();
            var values = new java.util.LinkedHashMap<String, Object>();
            for (int column = 1; column <= metadata.getColumnCount(); column++) {
                values.put(metadata.getColumnLabel(column), rows.getObject(column));
            }
            return values;
        }
    }

    private void executeAsMigrator(String sql) throws SQLException {
        try (var connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), MIGRATOR_USER, MIGRATOR_PASSWORD);
                var statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }
}
