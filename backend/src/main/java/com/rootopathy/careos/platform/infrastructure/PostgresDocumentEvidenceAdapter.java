package com.rootopathy.careos.platform.infrastructure;

import com.rootopathy.careos.platform.application.DocumentEvidenceException;
import com.rootopathy.careos.platform.application.DocumentEvidenceOperations;
import com.rootopathy.careos.platform.domain.DocumentObjectReference;
import com.rootopathy.careos.platform.domain.DocumentPromotionEvidence;
import com.rootopathy.careos.platform.domain.DocumentPromotionPolicy;
import com.rootopathy.careos.platform.domain.DocumentQuarantineEvidence;
import com.rootopathy.careos.platform.domain.DocumentQuarantineRequest;
import com.rootopathy.careos.platform.domain.DocumentScanAttestation;
import com.rootopathy.careos.platform.domain.MalwareScanResult;
import com.rootopathy.careos.platform.domain.MalwareScanVerdict;
import com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext;
import com.rootopathy.careos.tenancy.infrastructure.AuthorizedTenantTransactionGuard;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** PostgreSQL-backed append-only quarantine metadata and malware-scan evidence. */
public final class PostgresDocumentEvidenceAdapter implements DocumentEvidenceOperations {
    private static final String NOT_INITIALIZED = "document-evidence-store-not-initialized";
    private static final String STORE_REJECTED = "document-evidence-store-rejected";
    private static final String QUARANTINE_CONFLICT = "document-quarantine-evidence-conflict";
    private static final String QUARANTINE_MISSING = "document-quarantine-evidence-not-found";
    private static final String SCAN_CONFLICT = "document-scan-evidence-conflict";
    private static final String SCAN_DIGEST_MISMATCH = "document-scan-evidence-digest-mismatch";
    private static final String PROMOTION_CONFLICT = "document-promotion-evidence-conflict";
    private static final String PROMOTION_SCANNER_REJECTED =
            "document-promotion-scanner-not-approved";
    private static final String TENANT_MISMATCH = "document-evidence-tenant-mismatch";
    private static final String REFERENCE_MISMATCH = "document-evidence-reference-mismatch";

    private final JdbcTemplate jdbcTemplate;
    private volatile boolean initialized;

    public PostgresDocumentEvidenceAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
    }

    public synchronized void initialize() {
        initialized = false;
        try {
            var role = jdbcTemplate.queryForMap("""
                    SELECT rolsuper, rolbypassrls
                    FROM pg_roles
                    WHERE rolname = current_user
                    """);
            if (Boolean.TRUE.equals(role.get("rolsuper"))
                    || Boolean.TRUE.equals(role.get("rolbypassrls"))) {
                throw new IllegalStateException("PostgreSQL document evidence runtime role is unsafe");
            }
            verifyTableSecurity(
                    "document_quarantine_evidence",
                    "document_quarantine_evidence_tenant_policy",
                    "document_quarantine_evidence_validate_insert",
                    "document_quarantine_evidence_reject_mutation");
            verifyTableSecurity(
                    "document_scan_attestations",
                    "document_scan_attestations_tenant_policy",
                    "document_scan_attestations_validate_insert",
                    "document_scan_attestations_reject_mutation");
            verifyTableSecurity(
                    "document_promotion_evidence",
                    "document_promotion_evidence_tenant_policy",
                    "document_promotion_evidence_validate_insert",
                    "document_promotion_evidence_reject_mutation");
            var hasRequiredConstraints = jdbcTemplate.queryForObject(
                    """
                    SELECT EXISTS (
                        SELECT 1
                        FROM pg_constraint constraints
                        JOIN pg_class tables ON tables.oid = constraints.conrelid
                        JOIN pg_class referenced_tables
                          ON referenced_tables.oid = constraints.confrelid
                        JOIN pg_namespace schemas ON schemas.oid = tables.relnamespace
                        WHERE schemas.nspname = 'public'
                          AND tables.relname = 'document_scan_attestations'
                          AND referenced_tables.relname = 'document_quarantine_evidence'
                          AND constraints.conname = 'document_scan_attestations_object_fk'
                          AND constraints.contype = 'f'
                          AND (
                              SELECT array_agg(attributes.attname::text ORDER BY keys.ordinality)
                              FROM unnest(constraints.conkey) WITH ORDINALITY keys(attribute_number, ordinality)
                              JOIN pg_attribute attributes
                                ON attributes.attrelid = constraints.conrelid
                               AND attributes.attnum = keys.attribute_number
                          ) = ARRAY['organization_id', 'document_id', 'object_version_id']::text[]
                          AND (
                              SELECT array_agg(attributes.attname::text ORDER BY keys.ordinality)
                              FROM unnest(constraints.confkey) WITH ORDINALITY keys(attribute_number, ordinality)
                              JOIN pg_attribute attributes
                                ON attributes.attrelid = constraints.confrelid
                               AND attributes.attnum = keys.attribute_number
                          ) = ARRAY['organization_id', 'document_id', 'object_version_id']::text[]
                    )
                    AND EXISTS (
                        SELECT 1
                        FROM pg_constraint constraints
                        JOIN pg_class tables ON tables.oid = constraints.conrelid
                        JOIN pg_namespace schemas ON schemas.oid = tables.relnamespace
                        WHERE schemas.nspname = 'public'
                          AND tables.relname = 'document_scan_attestations'
                          AND constraints.conname = 'document_scan_attestations_observation_unique'
                          AND constraints.contype = 'u'
                          AND (
                              SELECT array_agg(attributes.attname::text ORDER BY keys.ordinality)
                              FROM unnest(constraints.conkey) WITH ORDINALITY keys(attribute_number, ordinality)
                              JOIN pg_attribute attributes
                                ON attributes.attrelid = constraints.conrelid
                               AND attributes.attnum = keys.attribute_number
                          ) = ARRAY[
                              'organization_id', 'document_id', 'object_version_id',
                              'scanner_key', 'scanned_at'
                          ]::text[]
                    )
                    AND EXISTS (
                        SELECT 1
                        FROM pg_constraint constraints
                        JOIN pg_class tables ON tables.oid = constraints.conrelid
                        JOIN pg_class referenced_tables
                          ON referenced_tables.oid = constraints.confrelid
                        JOIN pg_namespace schemas ON schemas.oid = tables.relnamespace
                        WHERE schemas.nspname = 'public'
                          AND tables.relname = 'document_promotion_evidence'
                          AND referenced_tables.relname = 'document_scan_attestations'
                          AND constraints.conname = 'document_promotion_evidence_scan_fk'
                          AND constraints.contype = 'f'
                          AND (
                              SELECT array_agg(attributes.attname::text ORDER BY keys.ordinality)
                              FROM unnest(constraints.conkey) WITH ORDINALITY keys(attribute_number, ordinality)
                              JOIN pg_attribute attributes
                                ON attributes.attrelid = constraints.conrelid
                               AND attributes.attnum = keys.attribute_number
                          ) = ARRAY[
                              'organization_id', 'scan_attestation_id',
                              'document_id', 'object_version_id'
                          ]::text[]
                          AND (
                              SELECT array_agg(attributes.attname::text ORDER BY keys.ordinality)
                              FROM unnest(constraints.confkey) WITH ORDINALITY keys(attribute_number, ordinality)
                              JOIN pg_attribute attributes
                                ON attributes.attrelid = constraints.confrelid
                               AND attributes.attnum = keys.attribute_number
                          ) = ARRAY[
                              'organization_id', 'attestation_id',
                              'document_id', 'object_version_id'
                          ]::text[]
                    )
                    """,
                    Boolean.class);
            var visibleWithoutTenant = jdbcTemplate.queryForObject(
                    """
                    SELECT (SELECT count(*) FROM document_quarantine_evidence)
                         + (SELECT count(*) FROM document_scan_attestations)
                         + (SELECT count(*) FROM document_promotion_evidence)
                    """,
                    Long.class);
            if (!Boolean.TRUE.equals(hasRequiredConstraints)
                    || visibleWithoutTenant == null
                    || visibleWithoutTenant != 0L) {
                throw new IllegalStateException(
                        "PostgreSQL document evidence security readiness check failed");
            }
            initialized = true;
        } catch (RuntimeException exception) {
            throw new IllegalStateException(
                    "PostgreSQL document evidence readiness check failed", exception);
        }
    }

    @Override
    public DocumentQuarantineEvidence recordQuarantine(
            AuthorizedTenantContext context,
            DocumentQuarantineRequest request,
            DocumentObjectReference document) {
        Objects.requireNonNull(request, "request");
        requireContext(context, document);
        if (!request.documentId().equals(document.documentId())
                || !request.objectVersionId().equals(document.objectVersionId())) {
            throw new DocumentEvidenceException(REFERENCE_MISMATCH);
        }

        try {
            var inserted = jdbcTemplate.update(
                    """
                    INSERT INTO document_quarantine_evidence
                        (organization_id, document_id, object_version_id, declared_bytes,
                         media_type, sha256, recorded_by_actor_id, purpose, correlation_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT DO NOTHING
                    """,
                    context.organizationId(),
                    document.documentId(),
                    document.objectVersionId(),
                    request.declaredBytes(),
                    request.mediaType(),
                    request.sha256(),
                    context.actorId(),
                    context.purpose(),
                    context.correlationId());
            if (inserted != 0 && inserted != 1) {
                throw new DocumentEvidenceException(STORE_REJECTED);
            }
            var stored = readQuarantine(document)
                    .orElseThrow(() -> new DocumentEvidenceException(STORE_REJECTED));
            if (!matches(stored, request)) {
                throw new DocumentEvidenceException(QUARANTINE_CONFLICT);
            }
            return stored;
        } catch (DocumentEvidenceException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new DocumentEvidenceException(STORE_REJECTED, exception);
        }
    }

    @Override
    public DocumentScanAttestation recordScan(
            AuthorizedTenantContext context, MalwareScanResult result) {
        Objects.requireNonNull(result, "result");
        var document = result.document();
        requireContext(context, document);

        try {
            var quarantine = readQuarantine(document)
                    .orElseThrow(() -> new DocumentEvidenceException(QUARANTINE_MISSING));
            if (result.verdict() != MalwareScanVerdict.ERROR
                    && !quarantine.sha256().equals(result.sha256())) {
                throw new DocumentEvidenceException(SCAN_DIGEST_MISMATCH);
            }

            var normalizedResult = new MalwareScanResult(
                    document,
                    result.verdict(),
                    result.scannerKey(),
                    result.definitionsVersion(),
                    result.sha256(),
                    result.scannedAt().truncatedTo(ChronoUnit.MICROS));
            var attestationId = UUID.randomUUID();
            var inserted = jdbcTemplate.update(
                    """
                    INSERT INTO document_scan_attestations
                        (organization_id, attestation_id, document_id, object_version_id,
                         verdict, scanner_key, definitions_version, sha256, scanned_at,
                         recorded_by_actor_id, purpose, correlation_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT ON CONSTRAINT document_scan_attestations_observation_unique
                    DO NOTHING
                    """,
                    context.organizationId(),
                    attestationId,
                    document.documentId(),
                    document.objectVersionId(),
                    normalizedResult.verdict().name().toLowerCase(Locale.ROOT),
                    normalizedResult.scannerKey(),
                    normalizedResult.definitionsVersion(),
                    normalizedResult.sha256(),
                    Timestamp.from(normalizedResult.scannedAt()),
                    context.actorId(),
                    context.purpose(),
                    context.correlationId());
            if (inserted != 0 && inserted != 1) {
                throw new DocumentEvidenceException(STORE_REJECTED);
            }
            var stored = readScanObservation(normalizedResult)
                    .orElseThrow(() -> new DocumentEvidenceException(STORE_REJECTED));
            if (!stored.result().equals(normalizedResult)) {
                throw new DocumentEvidenceException(SCAN_CONFLICT);
            }
            return stored;
        } catch (DocumentEvidenceException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new DocumentEvidenceException(STORE_REJECTED, exception);
        }
    }

    @Override
    public DocumentPromotionEvidence recordPromotion(
            AuthorizedTenantContext context,
            DocumentScanAttestation scanAttestation,
            DocumentPromotionPolicy policy) {
        Objects.requireNonNull(scanAttestation, "scanAttestation");
        Objects.requireNonNull(policy, "policy");
        var document = scanAttestation.result().document();
        requireContext(context, document);
        if (!policy.acceptsScanner(scanAttestation.result().scannerKey())) {
            throw new DocumentEvidenceException(PROMOTION_SCANNER_REJECTED);
        }

        try {
            var existing = readPromotion(document);
            if (existing.isPresent()) {
                var stored = existing.orElseThrow();
                if (!matches(stored, scanAttestation, policy)) {
                    throw new DocumentEvidenceException(PROMOTION_CONFLICT);
                }
                return stored;
            }
            var inserted = jdbcTemplate.update(
                    """
                    INSERT INTO document_promotion_evidence
                        (organization_id, document_id, object_version_id,
                         scan_attestation_id, promotion_policy_key,
                         accepted_scanner_keys, maximum_scan_age_seconds,
                         maximum_future_skew_seconds,
                         promoted_by_actor_id, purpose, correlation_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT DO NOTHING
                    """,
                    context.organizationId(),
                    document.documentId(),
                    document.objectVersionId(),
                    scanAttestation.attestationId(),
                    policy.policyKey(),
                    policy.canonicalAcceptedScannerKeys(),
                    Math.toIntExact(policy.maximumScanAge().toSeconds()),
                    Math.toIntExact(policy.maximumFutureSkew().toSeconds()),
                    context.actorId(),
                    context.purpose(),
                    context.correlationId());
            if (inserted != 0 && inserted != 1) {
                throw new DocumentEvidenceException(STORE_REJECTED);
            }
            var stored = readPromotion(document)
                    .orElseThrow(() -> new DocumentEvidenceException(STORE_REJECTED));
            if (!matches(stored, scanAttestation, policy)) {
                throw new DocumentEvidenceException(PROMOTION_CONFLICT);
            }
            return stored;
        } catch (DocumentEvidenceException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new DocumentEvidenceException(STORE_REJECTED, exception);
        }
    }

    @Override
    public Optional<DocumentQuarantineEvidence> findQuarantine(
            AuthorizedTenantContext context, DocumentObjectReference document) {
        requireContext(context, document);
        try {
            return readQuarantine(document);
        } catch (DocumentEvidenceException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new DocumentEvidenceException(STORE_REJECTED, exception);
        }
    }

    @Override
    public Optional<DocumentScanAttestation> findLatestScan(
            AuthorizedTenantContext context, DocumentObjectReference document) {
        requireContext(context, document);
        try {
            var rows = jdbcTemplate.query(
                    """
                    SELECT organization_id, attestation_id, document_id, object_version_id,
                           verdict, scanner_key, definitions_version, sha256,
                           scanned_at, recorded_at
                    FROM document_scan_attestations
                    WHERE organization_id = ? AND document_id = ? AND object_version_id = ?
                    ORDER BY scanned_at DESC, recorded_at DESC, attestation_id DESC
                    LIMIT 1
                    """,
                    PostgresDocumentEvidenceAdapter::mapAttestation,
                    document.organizationId(),
                    document.documentId(),
                    document.objectVersionId());
            return rows.stream().findFirst();
        } catch (DocumentEvidenceException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new DocumentEvidenceException(STORE_REJECTED, exception);
        }
    }

    @Override
    public Optional<DocumentPromotionEvidence> findPromotion(
            AuthorizedTenantContext context, DocumentObjectReference document) {
        requireContext(context, document);
        try {
            return readPromotion(document);
        } catch (DocumentEvidenceException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new DocumentEvidenceException(STORE_REJECTED, exception);
        }
    }

    private void verifyTableSecurity(
            String tableName, String policyName, String insertTrigger, String immutableTrigger) {
        var readiness = jdbcTemplate.queryForMap(
                """
                SELECT tables.relrowsecurity,
                       tables.relforcerowsecurity,
                       pg_get_userbyid(tables.relowner) AS table_owner,
                       current_user AS runtime_role,
                       has_table_privilege(tables.oid, 'SELECT') AS can_select,
                       has_table_privilege(tables.oid, 'INSERT') AS can_insert,
                       has_table_privilege(tables.oid, 'UPDATE') AS can_update,
                       has_table_privilege(tables.oid, 'DELETE') AS can_delete,
                       EXISTS (
                           SELECT 1 FROM pg_policy policies
                           WHERE policies.polrelid = tables.oid AND policies.polname = ?
                       ) AS has_tenant_policy,
                       (
                           SELECT count(*) = 2
                           FROM pg_trigger triggers
                           WHERE triggers.tgrelid = tables.oid
                             AND NOT triggers.tgisinternal
                             AND triggers.tgenabled <> 'D'
                             AND triggers.tgname IN (?, ?)
                       ) AS has_evidence_triggers
                FROM pg_class tables
                JOIN pg_namespace schemas ON schemas.oid = tables.relnamespace
                WHERE schemas.nspname = 'public'
                  AND tables.relname = ?
                  AND tables.relkind = 'r'
                """,
                policyName,
                insertTrigger,
                immutableTrigger,
                tableName);
        if (!Boolean.TRUE.equals(readiness.get("relrowsecurity"))
                || !Boolean.TRUE.equals(readiness.get("relforcerowsecurity"))
                || Objects.equals(readiness.get("table_owner"), readiness.get("runtime_role"))
                || !Boolean.TRUE.equals(readiness.get("can_select"))
                || !Boolean.TRUE.equals(readiness.get("can_insert"))
                || Boolean.TRUE.equals(readiness.get("can_update"))
                || Boolean.TRUE.equals(readiness.get("can_delete"))
                || !Boolean.TRUE.equals(readiness.get("has_tenant_policy"))
                || !Boolean.TRUE.equals(readiness.get("has_evidence_triggers"))) {
            throw new IllegalStateException(
                    "PostgreSQL document evidence table security readiness check failed");
        }
    }

    private Optional<DocumentQuarantineEvidence> readQuarantine(
            DocumentObjectReference document) {
        var rows = jdbcTemplate.query(
                """
                SELECT organization_id, document_id, object_version_id,
                       declared_bytes, media_type, sha256, quarantined_at
                FROM document_quarantine_evidence
                WHERE organization_id = ? AND document_id = ? AND object_version_id = ?
                """,
                PostgresDocumentEvidenceAdapter::mapQuarantine,
                document.organizationId(),
                document.documentId(),
                document.objectVersionId());
        return rows.stream().findFirst();
    }

    private Optional<DocumentScanAttestation> readScanObservation(MalwareScanResult result) {
        var document = result.document();
        var rows = jdbcTemplate.query(
                """
                SELECT organization_id, attestation_id, document_id, object_version_id,
                       verdict, scanner_key, definitions_version, sha256,
                       scanned_at, recorded_at
                FROM document_scan_attestations
                WHERE organization_id = ? AND document_id = ? AND object_version_id = ?
                  AND scanner_key = ? AND scanned_at = ?
                """,
                PostgresDocumentEvidenceAdapter::mapAttestation,
                document.organizationId(),
                document.documentId(),
                document.objectVersionId(),
                result.scannerKey(),
                Timestamp.from(result.scannedAt()));
        return rows.stream().findFirst();
    }

    private Optional<DocumentPromotionEvidence> readPromotion(
            DocumentObjectReference document) {
        var rows = jdbcTemplate.query(
                """
                SELECT promotions.organization_id,
                       promotions.document_id,
                       promotions.object_version_id,
                       promotions.promotion_policy_key,
                       promotions.accepted_scanner_keys,
                       promotions.maximum_scan_age_seconds,
                       promotions.maximum_future_skew_seconds,
                       promotions.promoted_at,
                       scans.attestation_id,
                       scans.verdict,
                       scans.scanner_key,
                       scans.definitions_version,
                       scans.sha256,
                       scans.scanned_at,
                       scans.recorded_at
                FROM document_promotion_evidence promotions
                JOIN document_scan_attestations scans
                  ON scans.organization_id = promotions.organization_id
                 AND scans.attestation_id = promotions.scan_attestation_id
                 AND scans.document_id = promotions.document_id
                 AND scans.object_version_id = promotions.object_version_id
                WHERE promotions.organization_id = ?
                  AND promotions.document_id = ?
                  AND promotions.object_version_id = ?
                """,
                PostgresDocumentEvidenceAdapter::mapPromotion,
                document.organizationId(),
                document.documentId(),
                document.objectVersionId());
        return rows.stream().findFirst();
    }

    private void requireContext(
            AuthorizedTenantContext context, DocumentObjectReference document) {
        if (!initialized) {
            throw new DocumentEvidenceException(NOT_INITIALIZED);
        }
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(document, "document");
        if (!context.organizationId().equals(document.organizationId())) {
            throw new DocumentEvidenceException(TENANT_MISMATCH);
        }
        AuthorizedTenantTransactionGuard.requireWritable(jdbcTemplate, context);
    }

    private static boolean matches(
            DocumentQuarantineEvidence evidence, DocumentQuarantineRequest request) {
        return evidence.document().documentId().equals(request.documentId())
                && evidence.document().objectVersionId().equals(request.objectVersionId())
                && evidence.declaredBytes() == request.declaredBytes()
                && evidence.mediaType().equals(request.mediaType())
                && evidence.sha256().equals(request.sha256());
    }

    private static boolean matches(
            DocumentPromotionEvidence evidence,
            DocumentScanAttestation scanAttestation,
            DocumentPromotionPolicy policy) {
        return evidence.scanAttestation().equals(scanAttestation)
                && evidence.policyKey().equals(policy.policyKey())
                && evidence.acceptedScannerKeys().equals(policy.acceptedScannerKeys())
                && evidence.maximumScanAge().equals(policy.maximumScanAge())
                && evidence.maximumFutureSkew().equals(policy.maximumFutureSkew());
    }

    private static DocumentQuarantineEvidence mapQuarantine(ResultSet rows, int rowNumber)
            throws SQLException {
        var document = new DocumentObjectReference(
                rows.getObject("organization_id", UUID.class),
                rows.getObject("document_id", UUID.class),
                rows.getObject("object_version_id", UUID.class));
        return new DocumentQuarantineEvidence(
                document,
                rows.getLong("declared_bytes"),
                rows.getString("media_type"),
                rows.getString("sha256"),
                rows.getTimestamp("quarantined_at").toInstant());
    }

    private static DocumentScanAttestation mapAttestation(ResultSet rows, int rowNumber)
            throws SQLException {
        var document = new DocumentObjectReference(
                rows.getObject("organization_id", UUID.class),
                rows.getObject("document_id", UUID.class),
                rows.getObject("object_version_id", UUID.class));
        var result = new MalwareScanResult(
                document,
                MalwareScanVerdict.valueOf(rows.getString("verdict").toUpperCase(Locale.ROOT)),
                rows.getString("scanner_key"),
                rows.getString("definitions_version"),
                rows.getString("sha256"),
                rows.getTimestamp("scanned_at").toInstant());
        return new DocumentScanAttestation(
                rows.getObject("attestation_id", UUID.class),
                result,
                rows.getTimestamp("recorded_at").toInstant());
    }

    private static DocumentPromotionEvidence mapPromotion(ResultSet rows, int rowNumber)
            throws SQLException {
        var attestation = mapAttestation(rows, rowNumber);
        return new DocumentPromotionEvidence(
                attestation,
                rows.getString("promotion_policy_key"),
                Set.copyOf(java.util.Arrays.asList(
                        rows.getString("accepted_scanner_keys").split(","))),
                Duration.ofSeconds(rows.getInt("maximum_scan_age_seconds")),
                Duration.ofSeconds(rows.getInt("maximum_future_skew_seconds")),
                rows.getTimestamp("promoted_at").toInstant());
    }
}
