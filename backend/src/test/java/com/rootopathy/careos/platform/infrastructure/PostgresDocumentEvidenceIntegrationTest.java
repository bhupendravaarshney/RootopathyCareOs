package com.rootopathy.careos.platform.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rootopathy.careos.platform.application.DocumentEvidenceException;
import com.rootopathy.careos.platform.application.DocumentEvidenceOperations;
import com.rootopathy.careos.platform.domain.DocumentObjectReference;
import com.rootopathy.careos.platform.domain.DocumentQuarantineRequest;
import com.rootopathy.careos.platform.domain.MalwareScanResult;
import com.rootopathy.careos.platform.domain.MalwareScanVerdict;
import com.rootopathy.careos.tenancy.application.TenantAuthorizationOperations;
import com.rootopathy.careos.tenancy.domain.AuthenticatedActorContext;
import com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext;
import com.rootopathy.careos.tenancy.domain.PermissionKey;
import com.rootopathy.careos.tenancy.domain.TenantAuthorizationRequest;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Map;
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
                    "TRUNCATE document_scan_attestations, document_quarantine_evidence");
            statement.executeUpdate("""
                    INSERT INTO users (id, email, display_name, status)
                    VALUES ('01900000-0000-7000-8000-000000000201',
                            'document.actor@rootopathy.test', 'Document Test Actor', 'active')
                    ON CONFLICT (id) DO NOTHING
                    """);
            statement.executeUpdate("""
                    INSERT INTO organizations
                        (id, legal_name, display_name, country_code, timezone, status)
                    VALUES ('01900000-0000-7000-8000-000000000002',
                            'Second Document Test Org', 'Second Document Org',
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
                    INSERT INTO organization_memberships
                        (organization_id, user_id, role_key, status)
                    VALUES
                        ('01900000-0000-7000-8000-000000000001',
                         '01900000-0000-7000-8000-000000000201',
                         'document_test_actor', 'active'),
                        ('01900000-0000-7000-8000-000000000002',
                         '01900000-0000-7000-8000-000000000201',
                         'document_test_actor', 'active')
                    ON CONFLICT (organization_id, user_id, role_key)
                    DO UPDATE SET status = 'active', effective_to = NULL
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
    void databaseRejectsCrossTenantWritesAndMutationEvenForTheMigrationOwner()
            throws SQLException {
        var reference = createQuarantine();
        var clean = scan(
                reference, MalwareScanVerdict.CLEAN, "clamav", "20260915.1", SHA_256, SCANNED_AT);
        authorize(ORG_ONE, "immutable-scan", context -> evidence.recordScan(context, clean));

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

        var security = migratorRow("""
                SELECT objects.relrowsecurity AS objects_rls,
                       objects.relforcerowsecurity AS objects_forced,
                       scans.relrowsecurity AS scans_rls,
                       scans.relforcerowsecurity AS scans_forced,
                       has_table_privilege('careos_app', 'document_quarantine_evidence', 'UPDATE')
                           AS objects_update,
                       has_table_privilege('careos_app', 'document_scan_attestations', 'DELETE')
                           AS scans_delete
                FROM pg_class objects
                JOIN pg_namespace object_schema ON object_schema.oid = objects.relnamespace
                JOIN pg_class scans ON scans.relname = 'document_scan_attestations'
                JOIN pg_namespace scan_schema ON scan_schema.oid = scans.relnamespace
                WHERE object_schema.nspname = 'public'
                  AND scan_schema.nspname = 'public'
                  AND objects.relname = 'document_quarantine_evidence'
                """);
        assertThat(security)
                .containsEntry("objects_rls", true)
                .containsEntry("objects_forced", true)
                .containsEntry("scans_rls", true)
                .containsEntry("scans_forced", true)
                .containsEntry("objects_update", false)
                .containsEntry("scans_delete", false);
    }

    private DocumentObjectReference createQuarantine() {
        var reference = reference(ORG_ONE);
        authorize(ORG_ONE, "quarantine-create", context -> {
            evidence.recordQuarantine(context, quarantineRequest(), reference);
            return null;
        });
        return reference;
    }

    private <T> T authorize(
            UUID organizationId, String correlationId, Function<AuthorizedTenantContext, T> operation) {
        return tenantAuthorization.execute(request(organizationId, correlationId), operation);
    }

    private static TenantAuthorizationRequest request(UUID organizationId, String correlationId) {
        return new TenantAuthorizationRequest(
                organizationId,
                new AuthenticatedActorContext(ACTOR, "document-security", correlationId),
                new PermissionKey("test.document-evidence"));
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
