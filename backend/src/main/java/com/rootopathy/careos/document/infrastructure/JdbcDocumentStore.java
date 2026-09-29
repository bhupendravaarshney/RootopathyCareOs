package com.rootopathy.careos.document.infrastructure;

import com.rootopathy.careos.document.application.DocumentException;
import com.rootopathy.careos.document.application.DocumentStore;
import com.rootopathy.careos.document.domain.DocumentScreen;
import com.rootopathy.careos.platform.domain.DocumentObjectReference;
import com.rootopathy.careos.platform.domain.DocumentPromotionEvidence;
import com.rootopathy.careos.platform.domain.DocumentQuarantineEvidence;
import com.rootopathy.careos.platform.domain.DocumentScanAttestation;
import com.rootopathy.careos.platform.domain.MalwareScanVerdict;
import com.rootopathy.careos.platform.domain.SignedDocumentAccess;
import com.rootopathy.careos.shared.domain.UuidV7Generator;
import com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** PostgreSQL-backed Module 7 document/result projections and governed mutations. */
@Repository
public class JdbcDocumentStore implements DocumentStore {
    private final JdbcTemplate jdbc;

    public JdbcDocumentStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Projection projection(AuthorizedTenantContext context, ScreenQuery query) {
        requireOperationScope(context);
        var rows = switch (query.screenId()) {
            case "P7-05", "P7-10" -> versionRows(context, query);
            case "P7-07" -> reportRows(context, query);
            case "P7-09" -> flagRows(context, query);
            default -> documentRows(context, query);
        };
        return new Projection(
                metrics(context),
                columns(query.screenId()),
                rows.stream()
                        .map(row -> withAllowedActions(query.screenId(), row))
                        .limit(query.limit())
                        .toList(),
                notices(query.screenId()),
                databaseNow());
    }

    @Override
    public Set<String> permissions(AuthorizedTenantContext context) {
        requireOperationScope(context);
        return Set.copyOf(jdbc.queryForList(
                "SELECT permission_key FROM careos_projected_interactive_permissions(?, ?)",
                String.class,
                context.organizationId(),
                context.actorId()));
    }

    @Override
    public MutationResult mutate(AuthorizedTenantContext context, MutationCommand command) {
        requireOperationScope(context);
        return switch (command.actionKey()) {
            case "classify-document" -> classify(context, command);
            case "record-diagnostic-report" -> recordReport(context, command);
            case "acknowledge-result", "resolve-result" -> reviewFlag(context, command);
            case "escalate-result" -> escalateFlag(context, command);
            case "record-access-intent" -> recordIntent(context, command);
            default -> throw notFound("The requested document or result action does not exist.");
        };
    }

    @Override
    public MutationResult bindUpload(
            AuthorizedTenantContext context,
            UploadCommand command,
            DocumentQuarantineEvidence evidence) {
        requireOperationScope(context);
        if (!evidence.document().organizationId().equals(context.organizationId())
                || !evidence.document().documentId().equals(command.documentId())
                || !evidence.document().objectVersionId().equals(command.documentVersionId())
                || evidence.declaredBytes() != command.declaredBytes()
                || !evidence.mediaType().equals(command.mediaType())
                || !evidence.sha256().equals(command.sha256())) {
            throw conflict("The quarantine evidence does not match the requested document version.");
        }
        var existing = jdbc.query(
                """
                SELECT patient_id,current_version_id,current_version_number,lock_version,status
                FROM documents WHERE organization_id=? AND id=? FOR UPDATE
                """,
                (resultSet, rowNumber) -> new DocumentRecord(
                        command.documentId(),
                        resultSet.getObject("patient_id", UUID.class),
                        resultSet.getObject("current_version_id", UUID.class),
                        resultSet.getInt("current_version_number"),
                        resultSet.getLong("lock_version"),
                        resultSet.getString("status")),
                context.organizationId(),
                command.documentId());
        var created = existing.isEmpty();
        DocumentRecord document;
        if (created) {
            if (command.expectedRevision() != null || command.replacementReason() != null) {
                throw conflict("A replacement can name only an existing current document.");
            }
            jdbc.update(
                    """
                    INSERT INTO documents(
                        id,organization_id,patient_id,title,document_type_key,source_key,
                        current_version_id,current_version_number,status,created_by,updated_by)
                    VALUES (?,?,?,?,?,?,NULL,0,'active',?,?)
                    """,
                    command.documentId(),
                    context.organizationId(),
                    command.patientId(),
                    command.title(),
                    command.documentTypeKey(),
                    command.sourceKey(),
                    context.actorId(),
                    context.actorId());
            document = new DocumentRecord(
                    command.documentId(), command.patientId(), null, 0, 0, "active");
        } else {
            document = existing.getFirst();
            requireRevision(document.revision(), command.expectedRevision());
            if (!document.patientId().equals(command.patientId()) || !document.status().equals("active")) {
                throw conflict("The replacement document does not match the patient or active state.");
            }
        }

        var versionNumber = document.versionNumber() + 1;
        jdbc.update(
                """
                INSERT INTO document_versions(
                    id,organization_id,document_id,version_number,prior_version_id,
                    original_file_name,declared_media_type,declared_bytes,sha256,status,
                    quarantined_at,replacement_reason,created_by,updated_by)
                VALUES (?,?,?,?,?,?,?,?,?,'quarantined',?,?,?,?)
                """,
                command.documentVersionId(),
                context.organizationId(),
                command.documentId(),
                versionNumber,
                document.versionId(),
                command.originalFileName(),
                command.mediaType(),
                command.declaredBytes(),
                command.sha256(),
                Timestamp.from(evidence.quarantinedAt()),
                command.replacementReason(),
                context.actorId(),
                context.actorId());
        if (created) {
            var linkType = command.assessmentSessionId() != null
                    ? "assessment"
                    : command.encounterId() != null ? "encounter" : "patient_record";
            jdbc.update(
                    """
                    INSERT INTO document_links(
                        id,organization_id,document_id,patient_id,encounter_id,
                        assessment_session_id,link_type,linked_at,created_by,updated_by)
                    VALUES (uuidv7(),?,?,?,?,?,?,clock_timestamp(),?,?)
                    """,
                    context.organizationId(),
                    command.documentId(),
                    command.patientId(),
                    command.encounterId(),
                    command.assessmentSessionId(),
                    linkType,
                    context.actorId(),
                    context.actorId());
        }
        var changed = jdbc.update(
                """
                UPDATE documents
                SET current_version_id=?,current_version_number=?,lock_version=lock_version+1,
                    updated_at=clock_timestamp(),updated_by=?
                WHERE organization_id=? AND id=? AND lock_version=?
                """,
                command.documentVersionId(),
                versionNumber,
                context.actorId(),
                context.organizationId(),
                command.documentId(),
                document.revision());
        requireChanged(changed, "The document changed before the version was bound.");
        var revision = document.revision() + 1;
        var audit = map(
                "documentId", command.documentId(),
                "documentVersionId", command.documentVersionId(),
                "patientId", command.patientId(),
                "encounterId", command.encounterId(),
                "assessmentSessionId", command.assessmentSessionId(),
                "versionNumber", versionNumber,
                "status", "quarantined",
                "digest", command.sha256());
        var outbox = map(
                "documentId", command.documentId(),
                "documentVersionId", command.documentVersionId(),
                "patientId", command.patientId(),
                "versionNumber", versionNumber,
                "status", "quarantined",
                "digest", command.sha256());
        return result(
                command.documentId(),
                command.patientId(),
                command.documentId(),
                command.documentVersionId(),
                null,
                null,
                "document",
                "document.version.uploaded",
                "m7.document.quarantined.v1",
                "document",
                audit,
                outbox,
                created ? 201 : 200,
                revision);
    }

    @Override
    public VersionReference version(
            AuthorizedTenantContext context, UUID documentId, UUID documentVersionId) {
        requireOperationScope(context);
        var rows = jdbc.query(
                """
                SELECT version.document_id,version.id,document.patient_id,
                       version.declared_media_type,version.declared_bytes,version.sha256,
                       version.status,version.lock_version
                FROM document_versions version
                JOIN documents document ON document.organization_id=version.organization_id
                 AND document.id=version.document_id
                WHERE version.organization_id=?
                  AND (CAST(? AS uuid) IS NULL OR version.document_id=CAST(? AS uuid))
                  AND (CAST(? AS uuid) IS NULL OR version.id=CAST(? AS uuid))
                  AND (CAST(? AS uuid) IS NOT NULL OR version.id=document.current_version_id)
                """,
                (resultSet, rowNumber) -> {
                    var resolvedDocumentId = resultSet.getObject("document_id", UUID.class);
                    var resolvedVersionId = resultSet.getObject("id", UUID.class);
                    return new VersionReference(
                            resolvedDocumentId,
                            resolvedVersionId,
                            resultSet.getObject("patient_id", UUID.class),
                            resultSet.getString("declared_media_type"),
                            resultSet.getLong("declared_bytes"),
                            resultSet.getString("sha256"),
                            resultSet.getString("status"),
                            resultSet.getLong("lock_version"),
                            new DocumentObjectReference(
                                    context.organizationId(), resolvedDocumentId, resolvedVersionId));
                },
                context.organizationId(),
                documentId,
                documentId,
                documentVersionId,
                documentVersionId,
                documentVersionId);
        if (rows.size() != 1) throw notFound("The document version is unavailable.");
        return rows.getFirst();
    }

    @Override
    public MutationResult bindScan(
            AuthorizedTenantContext context,
            VersionReference version,
            DocumentScanAttestation scan,
            DocumentPromotionEvidence promotion) {
        requireOperationScope(context);
        if (!version.object().equals(scan.result().document())) {
            throw conflict("The scan attestation does not identify the requested document version.");
        }
        var verdict = scan.result().verdict();
        if (verdict != MalwareScanVerdict.ERROR
                && !version.sha256().equals(scan.result().sha256())) {
            throw conflict("The scan attestation digest does not match the document version.");
        }
        if (verdict == MalwareScanVerdict.CLEAN
                && (promotion == null || !version.object().equals(promotion.document()))) {
            throw conflict("A clean scan must be promoted before its business state advances.");
        }
        if (verdict != MalwareScanVerdict.CLEAN && promotion != null) {
            throw conflict("Only clean scan evidence can be promoted.");
        }
        var attempt = Objects.requireNonNull(jdbc.queryForObject(
                """
                SELECT coalesce(max(attempt_number),0)+1
                FROM document_scan_attempts
                WHERE organization_id=? AND document_version_id=?
                """,
                Integer.class,
                context.organizationId(),
                version.documentVersionId()));
        var attemptId = UuidV7Generator.randomUuid();
        var nextStatus = switch (verdict) {
            case CLEAN -> "clean";
            case INFECTED -> "infected";
            case ERROR -> "scan_failed";
        };
        var failureCode = verdict == MalwareScanVerdict.ERROR ? "scanner_error" : null;
        jdbc.update(
                """
                INSERT INTO document_scan_attempts(
                    id,organization_id,document_id,document_version_id,attempt_number,
                    platform_scan_attestation_id,verdict,scanner_key,definitions_version,
                    observed_sha256,scanned_at,recorded_at,failure_code,created_by,updated_by)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                attemptId,
                context.organizationId(),
                version.documentId(),
                version.documentVersionId(),
                attempt,
                scan.attestationId(),
                verdict.name().toLowerCase(),
                scan.result().scannerKey(),
                scan.result().definitionsVersion(),
                scan.result().sha256(),
                Timestamp.from(scan.result().scannedAt()),
                Timestamp.from(scan.recordedAt()),
                failureCode,
                context.actorId(),
                context.actorId());
        var changed = jdbc.update(
                """
                UPDATE document_versions
                SET status=?,platform_scan_attestation_id=?,scanned_at=?,promoted_at=?,
                    lock_version=lock_version+1,updated_at=clock_timestamp(),updated_by=?
                WHERE organization_id=? AND id=? AND lock_version=?
                  AND status IN ('quarantined','scan_failed')
                """,
                nextStatus,
                scan.attestationId(),
                Timestamp.from(scan.result().scannedAt()),
                promotion == null ? null : Timestamp.from(promotion.promotedAt()),
                context.actorId(),
                context.organizationId(),
                version.documentVersionId(),
                version.revision());
        requireChanged(changed, "The document version changed before scan evidence was bound.");
        var revision = version.revision() + 1;
        var audit = map(
                "documentId", version.documentId(),
                "documentVersionId", version.documentVersionId(),
                "scanAttemptId", attemptId,
                "verdict", verdict.name().toLowerCase(),
                "status", nextStatus,
                "revision", revision);
        var outbox = map(
                "documentId", version.documentId(),
                "documentVersionId", version.documentVersionId(),
                "verdict", verdict.name().toLowerCase(),
                "status", nextStatus);
        return result(
                version.documentVersionId(),
                version.patientId(),
                version.documentId(),
                version.documentVersionId(),
                null,
                null,
                "document_version",
                "document.scan.bound",
                "m7.document.scan-state-changed.v1",
                "document_version",
                audit,
                outbox,
                200,
                revision);
    }

    @Override
    public MutationResult bindAccess(
            AuthorizedTenantContext context,
            VersionReference version,
            String purposeKey,
            String reason,
            SignedDocumentAccess access) {
        requireOperationScope(context);
        if (!version.object().equals(access.document())) {
            throw conflict("The signed access grant does not identify the requested document version.");
        }
        var intentId = UuidV7Generator.randomUuid();
        jdbc.update(
                """
                INSERT INTO document_access_intents(
                    id,organization_id,document_id,document_version_id,patient_id,
                    intent_type,purpose_key,platform_access_grant_id,status,reason,
                    requested_at,expires_at,created_by,updated_by)
                VALUES (?,?,?,?,?,'view',?,?,'granted',?,clock_timestamp(),?,?,?)
                """,
                intentId,
                context.organizationId(),
                version.documentId(),
                version.documentVersionId(),
                version.patientId(),
                purposeKey,
                access.accessGrantId(),
                reason,
                Timestamp.from(access.expiresAt()),
                context.actorId(),
                context.actorId());
        var audit = map(
                "documentId", version.documentId(),
                "documentVersionId", version.documentVersionId(),
                "accessIntentId", intentId,
                "accessGrantId", access.accessGrantId(),
                "purposeKey", purposeKey,
                "expiresAt", access.expiresAt().toString());
        return result(
                intentId,
                version.patientId(),
                version.documentId(),
                version.documentVersionId(),
                null,
                null,
                "document_access_intent",
                "document.access.granted",
                null,
                null,
                audit,
                Map.of(),
                200,
                0);
    }

    @Override
    public AccessReference access(
            AuthorizedTenantContext context,
            UUID documentId,
            UUID documentVersionId,
            UUID accessIntentId,
            String purposeKey) {
        requireOperationScope(context);
        var rows = jdbc.query(
                """
                SELECT id,platform_access_grant_id,expires_at
                FROM document_access_intents
                WHERE organization_id=? AND id=? AND document_id=? AND document_version_id=?
                  AND intent_type='view' AND status='granted' AND purpose_key=?
                  AND expires_at>clock_timestamp()
                """,
                (resultSet, rowNumber) -> new AccessReference(
                        resultSet.getObject("id", UUID.class),
                        documentId,
                        documentVersionId,
                        resultSet.getObject("platform_access_grant_id", UUID.class),
                        purposeKey,
                        new DocumentObjectReference(
                                context.organizationId(), documentId, documentVersionId),
                        resultSet.getTimestamp("expires_at").toInstant()),
                context.organizationId(),
                accessIntentId,
                documentId,
                documentVersionId,
                purposeKey);
        if (rows.size() != 1) throw notFound("The document access grant is unavailable or expired.");
        return rows.getFirst();
    }

    private List<DocumentScreen.Row> documentRows(
            AuthorizedTenantContext context, ScreenQuery query) {
        requireSearch(query.search());
        return jdbc.query(
                """
                SELECT document.id,document.patient_id,document.title,document.document_type_key,
                       document.source_key,document.status document_status,document.lock_version,
                       document.current_version_number,version.id version_id,version.status version_status,
                       version.declared_media_type,version.declared_bytes,version.sha256,
                       version.quarantined_at,version.scanned_at,version.promoted_at,
                       patient.patient_number,patient.verification_state,
                       coalesce(patient.name_to_use,
                         nullif(concat_ws(' ',patient.official_given_name,patient.official_family_name),''),
                         'Patient '||left(patient.id::text,8)) patient_label,
                       (SELECT category_key FROM document_classifications classification
                         WHERE classification.organization_id=document.organization_id
                           AND classification.document_id=document.id
                         ORDER BY classification.classification_version DESC LIMIT 1) category_key,
                       (SELECT confidentiality_key FROM document_classifications classification
                         WHERE classification.organization_id=document.organization_id
                           AND classification.document_id=document.id
                         ORDER BY classification.classification_version DESC LIMIT 1) confidentiality_key,
                       (SELECT count(*) FROM diagnostic_reports report
                         WHERE report.organization_id=document.organization_id
                           AND report.document_id=document.id) result_count,
                       (SELECT count(*) FROM diagnostic_reports report
                         JOIN result_flags flag ON flag.organization_id=report.organization_id
                          AND flag.diagnostic_report_id=report.id
                         WHERE report.organization_id=document.organization_id
                           AND report.document_id=document.id AND flag.status<>'resolved') open_flags,
                       (SELECT left(report.summary_text,240) FROM diagnostic_reports report
                         WHERE report.organization_id=document.organization_id
                           AND report.document_id=document.id
                         ORDER BY report.received_at DESC,report.id DESC LIMIT 1) latest_result
                FROM documents document
                JOIN patient_profiles patient ON patient.organization_id=document.organization_id
                 AND patient.id=document.patient_id
                JOIN document_versions version ON version.organization_id=document.organization_id
                 AND version.id=document.current_version_id
                WHERE document.organization_id=?
                  AND (CAST(? AS uuid) IS NULL OR document.patient_id=CAST(? AS uuid))
                  AND (CAST(? AS uuid) IS NULL OR document.id=CAST(? AS uuid))
                  AND (CAST(? AS text) IS NULL OR version.status=CAST(? AS text)
                       OR document.status=CAST(? AS text))
                  AND (CAST(? AS text) IS NULL OR position(lower(CAST(? AS text)) in lower(
                    document.title||' '||document.document_type_key||' '||patient.patient_number||' '||
                    coalesce(patient.name_to_use,'')||' '||coalesce(patient.official_given_name,'')||' '||
                    coalesce(patient.official_family_name,'')))>0)
                ORDER BY document.updated_at DESC,document.id
                LIMIT ?
                """,
                (resultSet, rowNumber) -> row(
                        query.screenId(),
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("patient_id", UUID.class),
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("version_id", UUID.class),
                        null,
                        null,
                        resultSet.getString("version_status"),
                        resultSet.getLong("lock_version"),
                        values(
                                "$kind", "document",
                                "primary", resultSet.getString("title"),
                                "secondary", resultSet.getString("patient_label") + " · "
                                        + maskPatientNumber(resultSet.getString("patient_number")),
                                "patientVerification", resultSet.getString("verification_state"),
                                "documentType", resultSet.getString("document_type_key"),
                                "source", resultSet.getString("source_key"),
                                "version", Integer.toString(resultSet.getInt("current_version_number")),
                                "scanStatus", resultSet.getString("version_status"),
                                "mediaType", resultSet.getString("declared_media_type"),
                                "byteCount", Long.toString(resultSet.getLong("declared_bytes")),
                                "digest", resultSet.getString("sha256"),
                                "category", resultSet.getString("category_key"),
                                "confidentiality", resultSet.getString("confidentiality_key"),
                                "resultCount", Long.toString(resultSet.getLong("result_count")),
                                "openFlags", Long.toString(resultSet.getLong("open_flags")),
                                "latestResult", resultSet.getString("latest_result"),
                                "quarantinedAt", instant(resultSet.getTimestamp("quarantined_at")),
                                "scannedAt", instant(resultSet.getTimestamp("scanned_at")),
                                "promotedAt", instant(resultSet.getTimestamp("promoted_at")))),
                context.organizationId(),
                query.patientId(),
                query.patientId(),
                query.documentId(),
                query.documentId(),
                query.status(),
                query.status(),
                query.status(),
                query.search(),
                query.search(),
                query.limit());
    }

    private List<DocumentScreen.Row> versionRows(
            AuthorizedTenantContext context, ScreenQuery query) {
        requireSearch(query.search());
        return jdbc.query(
                """
                SELECT version.id,version.document_id,document.patient_id,document.title,
                       version.version_number,version.status,version.lock_version,
                       version.original_file_name,version.declared_media_type,version.declared_bytes,
                       version.sha256,version.quarantined_at,version.scanned_at,version.promoted_at,
                       version.prior_version_id,
                       (SELECT count(*) FROM document_scan_attempts attempt
                         WHERE attempt.organization_id=version.organization_id
                           AND attempt.document_version_id=version.id) scan_attempts,
                       patient.patient_number,
                       coalesce(patient.name_to_use,
                         nullif(concat_ws(' ',patient.official_given_name,patient.official_family_name),''),
                         'Patient '||left(patient.id::text,8)) patient_label
                FROM document_versions version
                JOIN documents document ON document.organization_id=version.organization_id
                 AND document.id=version.document_id
                JOIN patient_profiles patient ON patient.organization_id=document.organization_id
                 AND patient.id=document.patient_id
                WHERE version.organization_id=?
                  AND (CAST(? AS uuid) IS NULL OR document.patient_id=CAST(? AS uuid))
                  AND (CAST(? AS uuid) IS NULL OR document.id=CAST(? AS uuid))
                  AND (CAST(? AS text) IS NULL OR version.status=CAST(? AS text))
                  AND (CAST(? AS text) IS NULL OR position(lower(CAST(? AS text)) in lower(
                       document.title||' '||version.original_file_name||' '||patient.patient_number))>0)
                ORDER BY document.updated_at DESC,version.version_number DESC,version.id
                LIMIT ?
                """,
                (resultSet, rowNumber) -> row(
                        query.screenId(),
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("patient_id", UUID.class),
                        resultSet.getObject("document_id", UUID.class),
                        resultSet.getObject("id", UUID.class),
                        null,
                        null,
                        resultSet.getString("status"),
                        resultSet.getLong("lock_version"),
                        values(
                                "$kind", "document_version",
                                "primary", resultSet.getString("title"),
                                "secondary", resultSet.getString("patient_label") + " · "
                                        + maskPatientNumber(resultSet.getString("patient_number")),
                                "fileName", resultSet.getString("original_file_name"),
                                "version", Integer.toString(resultSet.getInt("version_number")),
                                "scanStatus", resultSet.getString("status"),
                                "scanAttempts", Long.toString(resultSet.getLong("scan_attempts")),
                                "mediaType", resultSet.getString("declared_media_type"),
                                "byteCount", Long.toString(resultSet.getLong("declared_bytes")),
                                "digest", resultSet.getString("sha256"),
                                "priorVersionId", string(resultSet.getObject("prior_version_id", UUID.class)),
                                "quarantinedAt", instant(resultSet.getTimestamp("quarantined_at")),
                                "scannedAt", instant(resultSet.getTimestamp("scanned_at")),
                                "promotedAt", instant(resultSet.getTimestamp("promoted_at")))),
                context.organizationId(),
                query.patientId(),
                query.patientId(),
                query.documentId(),
                query.documentId(),
                query.status(),
                query.status(),
                query.search(),
                query.search(),
                query.limit());
    }

    private List<DocumentScreen.Row> reportRows(
            AuthorizedTenantContext context, ScreenQuery query) {
        requireSearch(query.search());
        return jdbc.query(
                """
                SELECT report.id,report.document_id,report.document_version_id,report.patient_id,
                       report.report_type,report.status,report.source_key,report.source_identifier,
                       report.issued_at,report.received_at,report.summary_text,report.interpretation_status,
                       document.title,
                       (SELECT count(*) FROM lab_results value WHERE value.organization_id=report.organization_id
                         AND value.diagnostic_report_id=report.id) lab_count,
                       (SELECT count(*) FROM imaging_results value WHERE value.organization_id=report.organization_id
                         AND value.diagnostic_report_id=report.id) imaging_count,
                       (SELECT count(*) FROM result_flags flag WHERE flag.organization_id=report.organization_id
                         AND flag.diagnostic_report_id=report.id AND flag.status<>'resolved') open_flags,
                       patient.patient_number,
                       coalesce(patient.name_to_use,
                         nullif(concat_ws(' ',patient.official_given_name,patient.official_family_name),''),
                         'Patient '||left(patient.id::text,8)) patient_label
                FROM diagnostic_reports report
                JOIN documents document ON document.organization_id=report.organization_id
                 AND document.id=report.document_id
                JOIN patient_profiles patient ON patient.organization_id=report.organization_id
                 AND patient.id=report.patient_id
                WHERE report.organization_id=?
                  AND (CAST(? AS uuid) IS NULL OR report.patient_id=CAST(? AS uuid))
                  AND (CAST(? AS uuid) IS NULL OR report.document_id=CAST(? AS uuid))
                  AND (CAST(? AS uuid) IS NULL OR report.id=CAST(? AS uuid))
                  AND (CAST(? AS text) IS NULL OR report.status=CAST(? AS text))
                  AND (CAST(? AS text) IS NULL OR position(lower(CAST(? AS text)) in lower(
                       document.title||' '||report.source_identifier||' '||patient.patient_number))>0)
                ORDER BY report.received_at DESC,report.id
                LIMIT ?
                """,
                (resultSet, rowNumber) -> row(
                        query.screenId(),
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("patient_id", UUID.class),
                        resultSet.getObject("document_id", UUID.class),
                        resultSet.getObject("document_version_id", UUID.class),
                        resultSet.getObject("id", UUID.class),
                        null,
                        resultSet.getString("status"),
                        0,
                        values(
                                "$kind", "diagnostic_report",
                                "primary", resultSet.getString("source_identifier"),
                                "secondary", resultSet.getString("patient_label") + " · "
                                        + maskPatientNumber(resultSet.getString("patient_number")),
                                "documentTitle", resultSet.getString("title"),
                                "reportType", resultSet.getString("report_type"),
                                "source", resultSet.getString("source_key"),
                                "issuedAt", instant(resultSet.getTimestamp("issued_at")),
                                "receivedAt", instant(resultSet.getTimestamp("received_at")),
                                "summary", resultSet.getString("summary_text"),
                                "interpretationStatus", resultSet.getString("interpretation_status"),
                                "resultCount", Long.toString(
                                        resultSet.getLong("lab_count") + resultSet.getLong("imaging_count")),
                                "openFlags", Long.toString(resultSet.getLong("open_flags")))),
                context.organizationId(),
                query.patientId(),
                query.patientId(),
                query.documentId(),
                query.documentId(),
                query.diagnosticReportId(),
                query.diagnosticReportId(),
                query.status(),
                query.status(),
                query.search(),
                query.search(),
                query.limit());
    }

    private List<DocumentScreen.Row> flagRows(
            AuthorizedTenantContext context, ScreenQuery query) {
        requireSearch(query.search());
        return jdbc.query(
                """
                SELECT flag.id,flag.diagnostic_report_id,flag.lab_result_id,flag.imaging_result_id,
                       flag.flag_kind,flag.summary_text,flag.owner_practitioner_id,
                       flag.sla_policy_version,flag.detected_at,flag.acknowledgement_due_at,
                       flag.acknowledged_at,flag.resolved_at,flag.status,flag.lock_version,
                       report.document_id,report.document_version_id,report.patient_id,
                       report.source_identifier,document.title,
                       CASE WHEN flag.status='open' AND flag.acknowledgement_due_at<clock_timestamp()
                            THEN 'overdue' ELSE flag.status END projected_status,
                       (SELECT count(*) FROM result_escalations escalation
                         WHERE escalation.organization_id=flag.organization_id
                           AND escalation.result_flag_id=flag.id) escalation_count,
                       patient.patient_number,
                       coalesce(patient.name_to_use,
                         nullif(concat_ws(' ',patient.official_given_name,patient.official_family_name),''),
                         'Patient '||left(patient.id::text,8)) patient_label
                FROM result_flags flag
                JOIN diagnostic_reports report ON report.organization_id=flag.organization_id
                 AND report.id=flag.diagnostic_report_id
                JOIN documents document ON document.organization_id=report.organization_id
                 AND document.id=report.document_id
                JOIN patient_profiles patient ON patient.organization_id=report.organization_id
                 AND patient.id=report.patient_id
                WHERE flag.organization_id=?
                  AND (CAST(? AS uuid) IS NULL OR report.patient_id=CAST(? AS uuid))
                  AND (CAST(? AS uuid) IS NULL OR report.document_id=CAST(? AS uuid))
                  AND (CAST(? AS uuid) IS NULL OR report.id=CAST(? AS uuid))
                  AND (CAST(? AS text) IS NULL OR flag.status=CAST(? AS text)
                    OR (CAST(? AS text)='overdue' AND flag.status='open'
                        AND flag.acknowledgement_due_at<clock_timestamp()))
                  AND (CAST(? AS text) IS NULL OR position(lower(CAST(? AS text)) in lower(
                       document.title||' '||report.source_identifier||' '||patient.patient_number))>0)
                ORDER BY CASE flag.flag_kind WHEN 'critical' THEN 0 ELSE 1 END,
                         flag.acknowledgement_due_at,flag.id
                LIMIT ?
                """,
                (resultSet, rowNumber) -> row(
                        query.screenId(),
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("patient_id", UUID.class),
                        resultSet.getObject("document_id", UUID.class),
                        resultSet.getObject("document_version_id", UUID.class),
                        resultSet.getObject("diagnostic_report_id", UUID.class),
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("projected_status"),
                        resultSet.getLong("lock_version"),
                        values(
                                "$kind", "result_flag",
                                "primary", resultSet.getString("source_identifier"),
                                "secondary", resultSet.getString("patient_label") + " · "
                                        + maskPatientNumber(resultSet.getString("patient_number")),
                                "documentTitle", resultSet.getString("title"),
                                "flagKind", resultSet.getString("flag_kind"),
                                "summary", resultSet.getString("summary_text"),
                                "ownerPractitionerId", resultSet.getObject("owner_practitioner_id", UUID.class).toString(),
                                "slaPolicyVersion", resultSet.getString("sla_policy_version"),
                                "detectedAt", instant(resultSet.getTimestamp("detected_at")),
                                "acknowledgementDueAt", instant(resultSet.getTimestamp("acknowledgement_due_at")),
                                "acknowledgedAt", instant(resultSet.getTimestamp("acknowledged_at")),
                                "resolvedAt", instant(resultSet.getTimestamp("resolved_at")),
                                "escalationCount", Long.toString(resultSet.getLong("escalation_count")))),
                context.organizationId(),
                query.patientId(),
                query.patientId(),
                query.documentId(),
                query.documentId(),
                query.diagnosticReportId(),
                query.diagnosticReportId(),
                query.status(),
                query.status(),
                query.status(),
                query.search(),
                query.search(),
                query.limit());
    }

    private MutationResult classify(AuthorizedTenantContext context, MutationCommand command) {
        var document = lockDocument(context, command.targetId());
        requireRevision(document.revision(), command.expectedRevision());
        var version = requireCurrentVersion(document);
        var category = code(field(command, "categoryKey"), "categoryKey", 2, 80);
        var confidentiality = field(command, "confidentialityKey");
        requireOneOf(confidentiality, "confidentialityKey", "normal", "restricted", "very_restricted");
        var retentionClass = source(field(command, "retentionClassKey"), "retentionClassKey", 2, 80);
        var classificationSource = source(field(command, "sourceKey"), "sourceKey", 2, 120);
        var method = source(field(command, "methodKey"), "methodKey", 2, 80);
        var classificationVersion = Objects.requireNonNull(jdbc.queryForObject(
                """
                SELECT coalesce(max(classification_version),0)+1 FROM document_classifications
                WHERE organization_id=? AND document_id=?
                """,
                Integer.class,
                context.organizationId(),
                document.id()));
        var classificationId = UuidV7Generator.randomUuid();
        jdbc.update(
                """
                INSERT INTO document_classifications(
                    id,organization_id,document_id,document_version_id,classification_version,
                    category_key,confidentiality_key,retention_class_key,source_key,method_key,
                    classified_by,classified_at,reason,created_by,updated_by)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,clock_timestamp(),?,?,?)
                """,
                classificationId,
                context.organizationId(),
                document.id(),
                version,
                classificationVersion,
                category,
                confidentiality,
                retentionClass,
                classificationSource,
                method,
                context.actorId(),
                command.reason(),
                context.actorId(),
                context.actorId());
        var changed = jdbc.update(
                """
                UPDATE documents SET lock_version=lock_version+1,updated_at=clock_timestamp(),updated_by=?
                WHERE organization_id=? AND id=? AND lock_version=?
                """,
                context.actorId(),
                context.organizationId(),
                document.id(),
                document.revision());
        requireChanged(changed, "The document changed before classification was recorded.");
        var revision = document.revision() + 1;
        var audit = map(
                "documentId", document.id(),
                "documentVersionId", version,
                "classificationId", classificationId,
                "classificationVersion", classificationVersion,
                "categoryKey", category,
                "confidentialityKey", confidentiality,
                "retentionClassKey", retentionClass);
        return result(
                classificationId,
                document.patientId(),
                document.id(),
                version,
                null,
                null,
                "document_classification",
                "document.classification.appended",
                null,
                null,
                audit,
                Map.of(),
                200,
                revision);
    }

    private MutationResult recordReport(AuthorizedTenantContext context, MutationCommand command) {
        var document = lockDocument(context, command.targetId());
        requireRevision(document.revision(), command.expectedRevision());
        var versionId = requireCurrentVersion(document);
        if (command.documentVersionId() != null && !command.documentVersionId().equals(versionId)) {
            throw stale("The selected document version is no longer current.");
        }
        var versionStatus = jdbc.queryForObject(
                "SELECT status FROM document_versions WHERE organization_id=? AND id=?",
                String.class,
                context.organizationId(),
                versionId);
        if (!"clean".equals(versionStatus)) {
            throw conflict("Diagnostic results require a clean promoted document version.");
        }
        var reportType = field(command, "reportType");
        requireOneOf(reportType, "reportType", "laboratory", "imaging", "other");
        var reportStatus = field(command, "reportStatus");
        requireOneOf(reportStatus, "reportStatus", "preliminary", "final", "amended", "entered_in_error");
        var priorReportId = optionalUuid(command.fields().get("priorReportId"), "priorReportId");
        if (reportStatus.equals("amended") != (priorReportId != null)) {
            throw invalid("Only an amended report must identify its prior report.");
        }
        var encounterId = firstNonNull(
                optionalUuid(command.fields().get("encounterId"), "encounterId"), null);
        var authorId = optionalUuid(command.fields().get("authorPractitionerId"), "authorPractitionerId");
        var sourceKey = source(field(command, "sourceKey"), "sourceKey", 2, 120);
        var sourceIdentifier = bounded(field(command, "sourceIdentifier"), 2, 240, "sourceIdentifier");
        var issuedAt = instant(field(command, "issuedAt"), "issuedAt");
        var summary = bounded(field(command, "summary"), 2, 8000, "summary");
        var interpretation = field(command, "interpretationStatus");
        requireOneOf(
                interpretation,
                "interpretationStatus",
                "uninterpreted",
                "provisional",
                "reviewed",
                "not_applicable");
        var reportId = UuidV7Generator.randomUuid();
        var reportDigest = digest(summary);
        jdbc.update(
                """
                INSERT INTO diagnostic_reports(
                    id,organization_id,document_id,document_version_id,patient_id,encounter_id,
                    prior_report_id,report_type,status,source_key,source_identifier,issued_at,
                    received_at,summary_text,report_digest,author_practitioner_id,
                    interpretation_status,created_by,updated_by)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,clock_timestamp(),?,?,?,?,?,?)
                """,
                reportId,
                context.organizationId(),
                document.id(),
                versionId,
                document.patientId(),
                encounterId,
                priorReportId,
                reportType,
                reportStatus,
                sourceKey,
                sourceIdentifier,
                Timestamp.from(issuedAt),
                summary,
                reportDigest,
                authorId,
                interpretation,
                context.actorId(),
                context.actorId());

        var abnormalFlag = field(command, "abnormalFlag");
        UUID resultId = null;
        var resultCount = 0;
        if (reportType.equals("laboratory")) {
            requireOneOf(abnormalFlag, "abnormalFlag", "normal", "high", "low", "abnormal", "critical", "unknown");
            resultId = recordLabResult(context, command, reportId, sourceKey, authorId, abnormalFlag);
            resultCount = 1;
        } else if (reportType.equals("imaging")) {
            requireOneOf(abnormalFlag, "abnormalFlag", "normal", "abnormal", "critical", "unknown");
            resultId = recordImagingResult(context, command, reportId, sourceKey, authorId, abnormalFlag);
            resultCount = 1;
        }
        UUID flagId = null;
        var flagKind = "none";
        if (resultId != null && !Set.of("normal", "unknown").contains(abnormalFlag)) {
            if (reportStatus.equals("entered_in_error")) {
                throw invalid("A report entered in error cannot create an active result flag.");
            }
            flagKind = abnormalFlag.equals("critical") ? "critical" : "abnormal";
            flagId = recordFlag(context, command, reportId, reportType, resultId, flagKind);
        }
        var audit = map(
                "documentId", document.id(),
                "documentVersionId", versionId,
                "diagnosticReportId", reportId,
                "patientId", document.patientId(),
                "encounterId", encounterId,
                "reportType", reportType,
                "status", reportStatus,
                "resultCount", resultCount,
                "flagId", flagId,
                "flagKind", flagKind,
                "digest", reportDigest);
        var outbox = map(
                "diagnosticReportId", reportId,
                "patientId", document.patientId(),
                "reportType", reportType,
                "status", reportStatus,
                "flagKind", flagKind);
        return result(
                reportId,
                document.patientId(),
                document.id(),
                versionId,
                reportId,
                flagId,
                "diagnostic_report",
                "diagnostic.report.recorded",
                "m7.diagnostic-report.recorded.v1",
                "diagnostic_report",
                audit,
                outbox,
                201,
                0);
    }

    private UUID recordLabResult(
            AuthorizedTenantContext context,
            MutationCommand command,
            UUID reportId,
            String sourceKey,
            UUID reviewer,
            String abnormalFlag) {
        var resultId = UuidV7Generator.randomUuid();
        var testCode = bounded(field(command, "testCode"), 1, 120, "testCode");
        var testDisplay = bounded(field(command, "testDisplay"), 2, 240, "testDisplay");
        var value = bounded(field(command, "value"), 1, 1000, "value");
        var unit = optionalBounded(command.fields().get("unit"), 120, "unit");
        var range = optionalBounded(command.fields().get("referenceRange"), 1000, "referenceRange");
        var method = source(field(command, "methodKey"), "methodKey", 2, 120);
        var observedAt = instant(field(command, "observedAt"), "observedAt");
        var resultDigest = digest(String.join(
                "|",
                testCode,
                testDisplay,
                value,
                Objects.toString(unit, ""),
                Objects.toString(range, ""),
                abnormalFlag,
                sourceKey,
                method));
        jdbc.update(
                """
                INSERT INTO lab_results(
                    id,organization_id,diagnostic_report_id,test_code,test_display,value_text,
                    unit_text,reference_range_text,abnormal_flag,source_key,method_key,
                    observed_at,result_digest,reviewer_practitioner_id,created_by,updated_by)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                resultId,
                context.organizationId(),
                reportId,
                testCode,
                testDisplay,
                value,
                unit,
                range,
                abnormalFlag,
                sourceKey,
                method,
                Timestamp.from(observedAt),
                resultDigest,
                reviewer,
                context.actorId(),
                context.actorId());
        return resultId;
    }

    private UUID recordImagingResult(
            AuthorizedTenantContext context,
            MutationCommand command,
            UUID reportId,
            String sourceKey,
            UUID reviewer,
            String abnormalFlag) {
        var resultId = UuidV7Generator.randomUuid();
        var modality = code(field(command, "modalityKey"), "modalityKey", 2, 80);
        var bodySite = bounded(field(command, "bodySite"), 2, 240, "bodySite");
        var finding = bounded(field(command, "finding"), 2, 8000, "finding");
        var impression = bounded(field(command, "impression"), 2, 4000, "impression");
        var method = source(field(command, "methodKey"), "methodKey", 2, 120);
        var observedAt = instant(field(command, "observedAt"), "observedAt");
        var resultDigest = digest(String.join(
                "|",
                modality,
                bodySite,
                finding,
                impression,
                abnormalFlag,
                sourceKey,
                method));
        jdbc.update(
                """
                INSERT INTO imaging_results(
                    id,organization_id,diagnostic_report_id,modality_key,body_site_text,
                    finding_text,impression_text,abnormal_flag,source_key,method_key,
                    observed_at,result_digest,reviewer_practitioner_id,created_by,updated_by)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                resultId,
                context.organizationId(),
                reportId,
                modality,
                bodySite,
                finding,
                impression,
                abnormalFlag,
                sourceKey,
                method,
                Timestamp.from(observedAt),
                resultDigest,
                reviewer,
                context.actorId(),
                context.actorId());
        return resultId;
    }

    private UUID recordFlag(
            AuthorizedTenantContext context,
            MutationCommand command,
            UUID reportId,
            String reportType,
            UUID resultId,
            String flagKind) {
        var flagId = UuidV7Generator.randomUuid();
        var owner = fieldUuid(command, "ownerPractitionerId");
        var policy = source(field(command, "slaPolicyVersion"), "slaPolicyVersion", 2, 120);
        var dueAt = instant(field(command, "acknowledgementDueAt"), "acknowledgementDueAt");
        if (!dueAt.isAfter(command.now()) || dueAt.isAfter(command.now().plusSeconds(604800))) {
            throw invalid("acknowledgementDueAt must be after receipt and within seven days.");
        }
        var summary = bounded(field(command, "flagSummary"), 2, 2000, "flagSummary");
        jdbc.update(
                """
                INSERT INTO result_flags(
                    id,organization_id,diagnostic_report_id,lab_result_id,imaging_result_id,
                    flag_kind,summary_text,summary_digest,owner_practitioner_id,sla_policy_version,
                    detected_at,acknowledgement_due_at,status,created_by,updated_by)
                VALUES (?,?,?,?,?,?,?,?,?,?,clock_timestamp(),?,'open',?,?)
                """,
                flagId,
                context.organizationId(),
                reportId,
                reportType.equals("laboratory") ? resultId : null,
                reportType.equals("imaging") ? resultId : null,
                flagKind,
                summary,
                digest(summary),
                owner,
                policy,
                Timestamp.from(dueAt),
                context.actorId(),
                context.actorId());
        return flagId;
    }

    private MutationResult reviewFlag(AuthorizedTenantContext context, MutationCommand command) {
        var flag = lockFlag(context, command.targetId());
        requireRevision(flag.revision(), command.expectedRevision());
        var reviewType = command.actionKey().equals("acknowledge-result") ? "acknowledge" : "resolve";
        var expectedStatus = reviewType.equals("acknowledge") ? "open" : "acknowledged";
        var nextStatus = reviewType.equals("acknowledge") ? "acknowledged" : "resolved";
        if (!flag.status().equals(expectedStatus)) {
            throw conflict("The result flag is not in the required state for this review.");
        }
        var reviewer = fieldUuid(command, "reviewerPractitionerId");
        var reviewId = UuidV7Generator.randomUuid();
        var revision = flag.revision() + 1;
        jdbc.update(
                """
                INSERT INTO result_reviews(
                    id,organization_id,result_flag_id,diagnostic_report_id,review_type,
                    flag_revision,reviewer_practitioner_id,reason,reviewed_at,created_by,updated_by)
                VALUES (?,?,?,?,?,?,?,?,clock_timestamp(),?,?)
                """,
                reviewId,
                context.organizationId(),
                flag.id(),
                flag.reportId(),
                reviewType,
                revision,
                reviewer,
                command.reason(),
                context.actorId(),
                context.actorId());
        var changed = reviewType.equals("acknowledge")
                ? jdbc.update(
                        """
                        UPDATE result_flags SET status='acknowledged',acknowledged_at=clock_timestamp(),
                            lock_version=lock_version+1,updated_at=clock_timestamp(),updated_by=?
                        WHERE organization_id=? AND id=? AND lock_version=? AND status='open'
                        """,
                        context.actorId(), context.organizationId(), flag.id(), flag.revision())
                : jdbc.update(
                        """
                        UPDATE result_flags SET status='resolved',resolved_at=clock_timestamp(),
                            lock_version=lock_version+1,updated_at=clock_timestamp(),updated_by=?
                        WHERE organization_id=? AND id=? AND lock_version=? AND status='acknowledged'
                        """,
                        context.actorId(), context.organizationId(), flag.id(), flag.revision());
        requireChanged(changed, "The result flag changed before review evidence was bound.");
        var audit = map(
                "diagnosticReportId", flag.reportId(),
                "resultFlagId", flag.id(),
                "reviewId", reviewId,
                "fromStatus", flag.status(),
                "toStatus", nextStatus,
                "revision", revision);
        var outbox = map(
                "diagnosticReportId", flag.reportId(),
                "resultFlagId", flag.id(),
                "status", nextStatus);
        return result(
                flag.id(),
                flag.patientId(),
                flag.documentId(),
                flag.versionId(),
                flag.reportId(),
                flag.id(),
                "result_flag",
                reviewType.equals("acknowledge")
                        ? "result.flag.acknowledged"
                        : "result.flag.resolved",
                "m7.result-flag-reviewed.v1",
                "result_flag",
                audit,
                outbox,
                200,
                revision);
    }

    private MutationResult escalateFlag(AuthorizedTenantContext context, MutationCommand command) {
        var flag = lockFlag(context, command.targetId());
        if (flag.status().equals("resolved")) {
            throw conflict("A resolved result flag cannot receive a new escalation.");
        }
        var owner = fieldUuid(command, "ownerPractitionerId");
        var level = field(command, "escalationLevel");
        requireOneOf(level, "escalationLevel", "clinical_owner", "department_lead", "emergency_pathway");
        var channel = source(field(command, "channelKey"), "channelKey", 2, 80);
        var dueAt = instant(field(command, "dueAt"), "dueAt");
        if (dueAt.isBefore(command.now()) || dueAt.isAfter(command.now().plusSeconds(604800))) {
            throw invalid("dueAt must be current or future and within seven days.");
        }
        var escalationId = UuidV7Generator.randomUuid();
        jdbc.update(
                """
                INSERT INTO result_escalations(
                    id,organization_id,result_flag_id,diagnostic_report_id,escalation_level,
                    owner_practitioner_id,channel_key,reason,due_at,recorded_at,
                    delivery_status,created_by,updated_by)
                VALUES (?,?,?,?,?,?,?,?,?,clock_timestamp(),'not_dispatched',?,?)
                """,
                escalationId,
                context.organizationId(),
                flag.id(),
                flag.reportId(),
                level,
                owner,
                channel,
                command.reason(),
                Timestamp.from(dueAt),
                context.actorId(),
                context.actorId());
        var audit = map(
                "diagnosticReportId", flag.reportId(),
                "resultFlagId", flag.id(),
                "escalationId", escalationId,
                "escalationLevel", level,
                "deliveryStatus", "not_dispatched");
        var outbox = map(
                "diagnosticReportId", flag.reportId(),
                "resultFlagId", flag.id(),
                "escalationLevel", level,
                "deliveryStatus", "not_dispatched");
        return result(
                escalationId,
                flag.patientId(),
                flag.documentId(),
                flag.versionId(),
                flag.reportId(),
                flag.id(),
                "result_escalation",
                "result.flag.escalated",
                "m7.result-flag-escalated.v1",
                "result_flag",
                flag.id(),
                audit,
                outbox,
                201,
                flag.revision());
    }

    private MutationResult recordIntent(AuthorizedTenantContext context, MutationCommand command) {
        var document = lockDocument(context, command.targetId());
        var versionId = requireCurrentVersion(document);
        if (command.documentVersionId() != null && !command.documentVersionId().equals(versionId)) {
            throw stale("The selected document version is no longer current.");
        }
        var intentType = field(command, "intentType");
        requireOneOf(intentType, "intentType", "export", "share");
        var purpose = code(field(command, "purposeKey"), "purposeKey", 2, 80);
        var format = optionalBounded(command.fields().get("requestedFormat"), 80, "requestedFormat");
        var recipientType = optionalBounded(command.fields().get("recipientType"), 32, "recipientType");
        var recipient = optionalBounded(command.fields().get("recipientReference"), 240, "recipientReference");
        if ((recipientType == null) != (recipient == null)) {
            throw invalid("recipientType and recipientReference must be supplied together.");
        }
        if (recipientType != null) {
            requireOneOf(recipientType, "recipientType", "patient", "practitioner", "organization", "external_party");
        }
        var intentId = UuidV7Generator.randomUuid();
        jdbc.update(
                """
                INSERT INTO document_access_intents(
                    id,organization_id,document_id,document_version_id,patient_id,
                    intent_type,purpose_key,requested_format,recipient_type,recipient_reference,
                    status,reason,requested_at,created_by,updated_by)
                VALUES (?,?,?,?,?,?,?,?,?,?,'recorded',?,clock_timestamp(),?,?)
                """,
                intentId,
                context.organizationId(),
                document.id(),
                versionId,
                document.patientId(),
                intentType,
                purpose,
                format,
                recipientType,
                recipient,
                command.reason(),
                context.actorId(),
                context.actorId());
        var audit = map(
                "documentId", document.id(),
                "documentVersionId", versionId,
                "accessIntentId", intentId,
                "intentType", intentType,
                "purposeKey", purpose,
                "status", "recorded",
                "recipientType", recipientType,
                "requestedFormat", format);
        return result(
                intentId,
                document.patientId(),
                document.id(),
                versionId,
                null,
                null,
                "document_access_intent",
                "document.access_intent.recorded",
                null,
                null,
                audit,
                Map.of(),
                201,
                0);
    }

    private DocumentRecord lockDocument(AuthorizedTenantContext context, UUID documentId) {
        if (documentId == null) throw invalid("A document target is required.");
        var rows = jdbc.query(
                """
                SELECT id,patient_id,current_version_id,current_version_number,lock_version,status
                FROM documents WHERE organization_id=? AND id=? FOR UPDATE
                """,
                (resultSet, rowNumber) -> new DocumentRecord(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("patient_id", UUID.class),
                        resultSet.getObject("current_version_id", UUID.class),
                        resultSet.getInt("current_version_number"),
                        resultSet.getLong("lock_version"),
                        resultSet.getString("status")),
                context.organizationId(),
                documentId);
        if (rows.size() != 1) throw notFound("The document is unavailable.");
        return rows.getFirst();
    }

    private ResultFlagRecord lockFlag(AuthorizedTenantContext context, UUID flagId) {
        if (flagId == null) throw invalid("A result flag target is required.");
        var rows = jdbc.query(
                """
                SELECT flag.id,flag.diagnostic_report_id,flag.status,flag.lock_version,
                       report.patient_id,report.document_id,report.document_version_id
                FROM result_flags flag
                JOIN diagnostic_reports report ON report.organization_id=flag.organization_id
                 AND report.id=flag.diagnostic_report_id
                WHERE flag.organization_id=? AND flag.id=? FOR UPDATE OF flag
                """,
                (resultSet, rowNumber) -> new ResultFlagRecord(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("diagnostic_report_id", UUID.class),
                        resultSet.getObject("patient_id", UUID.class),
                        resultSet.getObject("document_id", UUID.class),
                        resultSet.getObject("document_version_id", UUID.class),
                        resultSet.getString("status"),
                        resultSet.getLong("lock_version")),
                context.organizationId(),
                flagId);
        if (rows.size() != 1) throw notFound("The result flag is unavailable.");
        return rows.getFirst();
    }

    private List<DocumentScreen.Metric> metrics(AuthorizedTenantContext context) {
        var values = jdbc.queryForMap(
                """
                SELECT (SELECT count(*) FROM documents WHERE organization_id=?) documents,
                       (SELECT count(*) FROM document_versions WHERE organization_id=? AND status='quarantined') quarantined,
                       (SELECT count(*) FROM document_versions WHERE organization_id=? AND status='clean') clean,
                       (SELECT count(*) FROM result_flags WHERE organization_id=? AND status<>'resolved') open_flags,
                       (SELECT count(*) FROM result_flags WHERE organization_id=? AND flag_kind='critical'
                         AND status='open' AND acknowledgement_due_at<clock_timestamp()) overdue_critical
                """,
                context.organizationId(),
                context.organizationId(),
                context.organizationId(),
                context.organizationId(),
                context.organizationId());
        return List.of(
                metric("documents", "Documents", number(values.get("documents")), "neutral"),
                metric("quarantined", "Awaiting scan", number(values.get("quarantined")), "warning"),
                metric("clean", "Clean promoted", number(values.get("clean")), "success"),
                metric("openFlags", "Open result flags", number(values.get("open_flags")), "warning"),
                metric("overdueCritical", "Overdue critical", number(values.get("overdue_critical")), "critical"));
    }

    private static List<DocumentScreen.Column> columns(String screenId) {
        if (screenId.equals("P7-09")) {
            return List.of(
                    column("primary", "Result"),
                    column("flagKind", "Flag"),
                    column("acknowledgementDueAt", "Acknowledge by"),
                    column("escalationCount", "Escalations"));
        }
        if (screenId.equals("P7-07")) {
            return List.of(
                    column("primary", "Result"),
                    column("reportType", "Type"),
                    column("issuedAt", "Issued"),
                    column("openFlags", "Open flags"));
        }
        if (Set.of("P7-05", "P7-10").contains(screenId)) {
            return List.of(
                    column("primary", "Document"),
                    column("version", "Version"),
                    column("scanStatus", "Scan state"),
                    column("scanAttempts", "Attempts"));
        }
        return List.of(
                column("primary", "Document"),
                column("secondary", "Patient"),
                column("version", "Version"),
                column("scanStatus", "Scan state"),
                column("openFlags", "Open flags"));
    }

    private static List<DocumentScreen.Notice> notices(String screenId) {
        var notices = new ArrayList<DocumentScreen.Notice>();
        notices.add(notice(
                "warning",
                "Providers remain fail closed",
                "Quarantine, scanning, clean promotion and signed viewing run only when their separately configured private providers are available."));
        notices.add(notice(
                "info",
                "Immutable provenance",
                "Replacement appends a version; file bytes, scan attempts and clinical result evidence are never overwritten."));
        if (Set.of("P7-07", "P7-08", "P7-09").contains(screenId)) {
            notices.add(notice(
                    "warning",
                    "Critical-result policy requires activation",
                    "SLA versions and owners are preserved, but local thresholds, schedules and notification delivery require target-environment approval."));
        }
        if (screenId.equals("P7-11")) {
            notices.add(notice(
                    "warning",
                    "Intent is not delivery",
                    "Recording an export/share intent does not authorize a recipient, generate an export or claim external delivery."));
        }
        return List.copyOf(notices);
    }

    private static DocumentScreen.Row withAllowedActions(
            String screenId, DocumentScreen.Row row) {
        var actions = new ArrayList<String>();
        switch (screenId) {
            case "P7-04" -> {
                if (row.values().get("$kind").equals("document")) actions.add("classify-document");
            }
            case "P7-05" -> {
                if (Set.of("quarantined", "scan_failed").contains(row.status())) actions.add("scan-document");
            }
            case "P7-06" -> {
                if (row.status().equals("clean")) actions.add("access-document");
            }
            case "P7-08" -> {
                if (row.status().equals("clean")) actions.add("record-diagnostic-report");
            }
            case "P7-09" -> {
                if (row.status().equals("open") || row.status().equals("overdue")) {
                    actions.add("acknowledge-result");
                    actions.add("escalate-result");
                } else if (row.status().equals("acknowledged")) {
                    actions.add("resolve-result");
                    actions.add("escalate-result");
                }
            }
            case "P7-11" -> {
                if (row.status().equals("clean")) actions.add("record-access-intent");
            }
            default -> {
                // Read-only screen.
            }
        }
        return new DocumentScreen.Row(
                row.id(),
                row.patientId(),
                row.documentId(),
                row.documentVersionId(),
                row.diagnosticReportId(),
                row.resultFlagId(),
                row.status(),
                row.revision(),
                row.etag(),
                row.values(),
                List.copyOf(actions));
    }

    private static DocumentScreen.Row row(
            String screenId,
            UUID id,
            UUID patientId,
            UUID documentId,
            UUID versionId,
            UUID reportId,
            UUID flagId,
            String status,
            long revision,
            Map<String, String> values) {
        return new DocumentScreen.Row(
                id,
                patientId,
                documentId,
                versionId,
                reportId,
                flagId,
                status,
                revision,
                "\"m7:" + screenId + ":" + id + ":" + revision + "\"",
                values,
                List.of());
    }

    private Instant databaseNow() {
        return Objects.requireNonNull(
                        jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class))
                .toInstant();
    }

    private void requireOperationScope(AuthorizedTenantContext context) {
        var bound = Boolean.TRUE.equals(jdbc.queryForObject(
                """
                SELECT nullif(current_setting('app.current_organization_id',true),'')::uuid=?
                   AND nullif(current_setting('app.current_actor_id',true),'')::uuid=?
                   AND nullif(current_setting('app.current_operation_key',true),'') IS NOT NULL
                """,
                Boolean.class,
                context.organizationId(),
                context.actorId()));
        if (!bound) {
            throw notFound("The document resource is unavailable or is not assigned to this account.");
        }
    }

    private static UUID requireCurrentVersion(DocumentRecord document) {
        if (document.versionId() == null || document.versionNumber() < 1) {
            throw conflict("The document does not have a current version.");
        }
        return document.versionId();
    }

    private static void requireRevision(long actual, Long expected) {
        if (expected == null) {
            throw new DocumentException(
                    DocumentException.Reason.PRECONDITION_REQUIRED,
                    "A strong If-Match revision is required.");
        }
        if (actual != expected) throw stale("The document revision is stale; refresh and retry.");
    }

    private static void requireChanged(int changed, String message) {
        if (changed != 1) throw stale(message);
    }

    private static void requireSearch(String value) {
        if (value == null) return;
        var meaningful = value.codePoints().filter(Character::isLetterOrDigit).count();
        if (value.codePointCount(0, value.length()) < 2
                || meaningful < 2
                || value.indexOf('%') >= 0
                || value.indexOf('_') >= 0
                || value.indexOf('\\') >= 0) {
            throw invalid("Document search requires bounded literal characters without wildcards.");
        }
    }

    private static String field(MutationCommand command, String key) {
        var value = command.fields().get(key);
        if (value == null || value.isBlank()) throw invalid(key + " is required.");
        return value.strip();
    }

    private static UUID fieldUuid(MutationCommand command, String key) {
        return requireUuid(field(command, key), key);
    }

    private static UUID optionalUuid(String value, String field) {
        if (value == null || value.isBlank()) return null;
        return requireUuid(value.strip(), field);
    }

    private static UUID requireUuid(String value, String field) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw invalid(field + " must contain a valid UUID.");
        }
    }

    private static Instant instant(String value, String field) {
        try {
            return Instant.parse(value);
        } catch (RuntimeException exception) {
            throw invalid(field + " must contain an ISO-8601 instant.");
        }
    }

    private static String bounded(String value, int minimum, int maximum, String field) {
        var normalized = value == null ? "" : value.strip();
        var length = normalized.codePointCount(0, normalized.length());
        if (length < minimum || length > maximum) {
            throw invalid(field + " must contain " + minimum + " to " + maximum + " characters.");
        }
        return normalized;
    }

    private static String optionalBounded(String value, int maximum, String field) {
        if (value == null || value.isBlank()) return null;
        return bounded(value, 1, maximum, field);
    }

    private static String code(String value, String field, int minimum, int maximum) {
        var normalized = bounded(value, minimum, maximum, field);
        if (!normalized.matches("[a-z][a-z0-9_]*")) throw invalid(field + " has an invalid format.");
        return normalized;
    }

    private static String source(String value, String field, int minimum, int maximum) {
        var normalized = bounded(value, minimum, maximum, field);
        if (!normalized.matches("[A-Za-z0-9][A-Za-z0-9._:-]*")) {
            throw invalid(field + " has an invalid format.");
        }
        return normalized;
    }

    private static void requireOneOf(String value, String field, String... options) {
        for (var option : options) if (option.equals(value)) return;
        throw invalid(field + " contains an unsupported value.");
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to calculate document evidence digest.", exception);
        }
    }

    private static Map<String, Object> map(Object... entries) {
        if (entries.length % 2 != 0) throw new IllegalArgumentException("Map entries must be paired.");
        var values = new LinkedHashMap<String, Object>();
        for (var index = 0; index < entries.length; index += 2) {
            if (entries[index + 1] != null) values.put((String) entries[index], entries[index + 1]);
        }
        return Map.copyOf(values);
    }

    private static Map<String, String> values(String... entries) {
        if (entries.length % 2 != 0) throw new IllegalArgumentException("Value entries must be paired.");
        var values = new LinkedHashMap<String, String>();
        for (var index = 0; index < entries.length; index += 2) {
            values.put(entries[index], safe(entries[index + 1], "Not recorded"));
        }
        return values;
    }

    private static MutationResult result(
            UUID subjectId,
            UUID patientId,
            UUID documentId,
            UUID documentVersionId,
            UUID diagnosticReportId,
            UUID resultFlagId,
            String subjectType,
            String auditEvent,
            String outboxEvent,
            String aggregateType,
            Map<String, Object> audit,
            Map<String, Object> outbox,
            int statusCode,
            long revision) {
        return result(
                subjectId,
                patientId,
                documentId,
                documentVersionId,
                diagnosticReportId,
                resultFlagId,
                subjectType,
                auditEvent,
                outboxEvent,
                aggregateType,
                null,
                audit,
                outbox,
                statusCode,
                revision);
    }

    private static MutationResult result(
            UUID subjectId,
            UUID patientId,
            UUID documentId,
            UUID documentVersionId,
            UUID diagnosticReportId,
            UUID resultFlagId,
            String subjectType,
            String auditEvent,
            String outboxEvent,
            String aggregateType,
            UUID outboxAggregateId,
            Map<String, Object> audit,
            Map<String, Object> outbox,
            int statusCode,
            long revision) {
        return new MutationResult(
                subjectId,
                patientId,
                documentId,
                documentVersionId,
                diagnosticReportId,
                resultFlagId,
                subjectType,
                auditEvent,
                outboxEvent,
                aggregateType,
                outboxAggregateId,
                audit,
                outbox,
                statusCode,
                revision);
    }

    private static DocumentScreen.Column column(String key, String label) {
        return new DocumentScreen.Column(key, label);
    }

    private static DocumentScreen.Metric metric(String key, String label, long value, String tone) {
        return new DocumentScreen.Metric(key, label, value, tone);
    }

    private static DocumentScreen.Notice notice(String tone, String title, String detail) {
        return new DocumentScreen.Notice(tone, title, detail);
    }

    private static String instant(Timestamp value) {
        return value == null ? "Not recorded" : value.toInstant().toString();
    }

    private static String string(Object value) {
        return value == null ? null : value.toString();
    }

    private static String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String maskPatientNumber(String value) {
        if (value == null || value.length() <= 4) return "••••";
        return "••••" + value.substring(value.length() - 4);
    }

    private static long number(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private static <T> T firstNonNull(T first, T second) {
        return first == null ? second : first;
    }

    private static DocumentException invalid(String message) {
        return new DocumentException(DocumentException.Reason.INVALID, message);
    }

    private static DocumentException notFound(String message) {
        return new DocumentException(DocumentException.Reason.NOT_FOUND, message);
    }

    private static DocumentException conflict(String message) {
        return new DocumentException(DocumentException.Reason.CONFLICT, message);
    }

    private static DocumentException stale(String message) {
        return new DocumentException(DocumentException.Reason.STALE, message);
    }

    private record DocumentRecord(
            UUID id,
            UUID patientId,
            UUID versionId,
            int versionNumber,
            long revision,
            String status) {}

    private record ResultFlagRecord(
            UUID id,
            UUID reportId,
            UUID patientId,
            UUID documentId,
            UUID versionId,
            String status,
            long revision) {}
}
