package com.rootopathy.careos.workforce.application;

import com.rootopathy.careos.administration.application.EvidenceExportArtifactStore;
import com.rootopathy.careos.governance.application.ConsumerInboxOperations;
import com.rootopathy.careos.governance.application.GovernanceEvidenceOperations;
import com.rootopathy.careos.governance.domain.AuditRecord;
import com.rootopathy.careos.governance.domain.GovernanceEvidence;
import com.rootopathy.careos.governance.domain.InboundOutboxEvent;
import com.rootopathy.careos.governance.domain.OutboxEnvelope;
import com.rootopathy.careos.tenancy.application.ServiceIdentityAuthorizationOperations;
import com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext;
import com.rootopathy.careos.tenancy.domain.OperationKey;
import com.rootopathy.careos.tenancy.domain.ServiceIdentityAuthorizationRequest;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public final class WorkforceExportWorkerService {
    private static final String SERVICE_IDENTITY = "m2-export-worker-v1";
    private static final String CONSUMER = "m2-export-worker-v1";
    private final ServiceIdentityAuthorizationOperations authorization;
    private final WorkforceExportStore store;
    private final EvidenceExportArtifactStore artifacts;
    private final GovernanceEvidenceOperations evidence;
    private final ConsumerInboxOperations inbox;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public WorkforceExportWorkerService(
            ServiceIdentityAuthorizationOperations authorization,
            WorkforceExportStore store,
            EvidenceExportArtifactStore artifacts,
            GovernanceEvidenceOperations evidence,
            ConsumerInboxOperations inbox,
            ObjectMapper objectMapper,
            Clock clock) {
        this.authorization = authorization;
        this.store = store;
        this.artifacts = artifacts;
        this.evidence = evidence;
        this.inbox = inbox;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public Result consume(
            OutboxEnvelope envelope, String workerId, String presentedCredential) {
        if (!"workforce.export.authorized".equals(envelope.eventName())
                || envelope.schemaVersion() != 1
                || !"workforce_export".equals(envelope.aggregateType())) {
            throw new IllegalArgumentException("unsupported workforce-export event");
        }
        var command = new Command(
                envelope.organizationId(),
                envelope.aggregateId(),
                workerId,
                presentedCredential,
                envelope.correlationId());
        return authorization.execute(request(command), context -> {
            var result = new Result[1];
            inbox.execute(
                    context,
                    InboundOutboxEvent.from(CONSUMER, envelope),
                    () -> result[0] = generate(context, command));
            return result[0] == null
                    ? new Result(command.exportId(), "duplicate", null, null, null)
                    : result[0];
        });
    }

    public Result generate(Command command) {
        return authorization.execute(request(command), context -> generate(context, command));
    }

    public List<UUID> dueExports(SweepCommand command) {
        if (command.maximumItems() < 1 || command.maximumItems() > 100) {
            throw new IllegalArgumentException("maximumItems must be between 1 and 100");
        }
        return authorization.execute(
                new ServiceIdentityAuthorizationRequest(
                        command.organizationId(),
                        command.presentedCredential(),
                        SERVICE_IDENTITY,
                        command.correlationId(),
                        new OperationKey("m2.export.generate")),
                context -> store.dueExportIds(
                        context, clock.instant(), command.maximumItems()));
    }

    private Result generate(AuthorizedTenantContext context, Command command) {
        var work = store.claim(context, command.exportId(), command.workerId());
        if ("failed".equals(work.status())) {
            return terminalFailure(
                    context, work, "workforce.export.lease_expired");
        }
        EvidenceExportArtifactStore.StoredArtifact stored = null;
        var readyPersisted = false;
        try {
            if (!artifacts.available()) {
                return failed(context, work, "workforce.export.storage_unavailable");
            }
            var rows = store.rows(context, work, work.rowLimit());
            if (rows.size() > work.rowLimit()) {
                return failed(context, work, "workforce.export.row_limit");
            }
            var artifact = serialize(work, rows);
            if (artifact.bytes().length > work.sizeLimitBytes()) {
                return failed(context, work, "workforce.export.byte_limit");
            }
            stored = artifacts.store(
                    context,
                    work.exportId(),
                    artifact.contentType(),
                    artifact.filename(),
                    artifact.bytes(),
                    artifact.digest());
            var ready = store.ready(
                    context,
                    work,
                    stored.opaqueReference(),
                    stored.sha256(),
                    artifact.contentType(),
                    artifact.filename(),
                    rows.size(),
                    stored.byteCount());
            readyPersisted = true;
            evidence.record(
                    context,
                    GovernanceEvidence.auditOnly(new AuditRecord(
                            "workforce.export.completed",
                            1,
                            "workforce_export",
                            ready.exportId(),
                            null,
                            json(lifecyclePayload(ready, null)))));
            return new Result(
                    ready.exportId(), "ready", ready.rowCount(), ready.byteCount(), null);
        } catch (ExportLimitException exception) {
            return failed(context, work, exception.code);
        } catch (SecurityException exception) {
            return failed(context,work,"workforce.export.authorization_changed");
        } catch (RuntimeException exception) {
            if (stored != null && !deleteAfterFailure(context, work, stored, exception)) {
                throw exception;
            }
            if (readyPersisted) throw exception;
            return failed(context, work, "workforce.export.generation_failed");
        }
    }

    private Result failed(
            AuthorizedTenantContext context,
            WorkforceExportStore.Work work,
            String code) {
        return terminalFailure(context, store.failed(context, work, code), code);
    }

    private Result terminalFailure(
            AuthorizedTenantContext context,
            WorkforceExportStore.Work failed,
            String code) {
        evidence.record(
                context,
                GovernanceEvidence.auditOnly(new AuditRecord(
                        "workforce.export.failed",
                        1,
                        "workforce_export",
                        failed.exportId(),
                        null,
                        json(lifecyclePayload(failed, code)))));
        return new Result(failed.exportId(), "failed", null, null, code);
    }

    private boolean deleteAfterFailure(
            AuthorizedTenantContext context,
            WorkforceExportStore.Work work,
            EvidenceExportArtifactStore.StoredArtifact stored,
            RuntimeException failure) {
        try {
            artifacts.delete(
                    context,
                    work.exportId(),
                    stored.opaqueReference(),
                    stored.sha256());
            return true;
        } catch (RuntimeException cleanup) {
            failure.addSuppressed(cleanup);
            return false;
        }
    }

    private Artifact serialize(
            WorkforceExportStore.Work work, List<Map<String, Object>> rows) {
        var date = DateTimeFormatter.BASIC_ISO_DATE.format(
                clock.instant().atZone(ZoneOffset.UTC).toLocalDate());
        var family = work.projection()
                .replace("-summary-v1", "")
                .replace("-detail-v1", "")
                .replace("-v1", "");
        var filename = "careos-" + family + "-" + date
                + ("csv".equals(work.format()) ? ".csv" : ".jsonl");
        var keys=projectionKeys(work.projection());
        for (var row:rows) {
            if (!row.keySet().equals(Set.copyOf(keys))) {
                throw new IllegalStateException("Workforce export row does not match its fixed projection schema.");
            }
        }
        var output = new ByteArrayOutputStream();
        try {
            if ("csv".equals(work.format())) {
                output.write(0xEF);
                output.write(0xBB);
                output.write(0xBF);
                line(output, keys.stream().map(this::csv).toList(), work.sizeLimitBytes());
                for (var row : rows) {
                    var values = new ArrayList<String>();
                    for (var key : keys) values.add(csv(display(row.get(key))));
                    line(output, values, work.sizeLimitBytes());
                }
            } else if ("jsonl".equals(work.format())) {
                var header = new LinkedHashMap<String, Object>();
                header.put("schema", work.projection());
                header.put("snapshotTime", work.snapshotAt());
                header.put("filterDigest", work.filterDigest());
                writeJsonLine(output, header, work.sizeLimitBytes());
                for (var row : rows) {
                    writeJsonLine(output, normalize(row,keys), work.sizeLimitBytes());
                }
            } else {
                throw new IllegalArgumentException("unsupported workforce export format");
            }
        } catch (ExportLimitException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("Workforce export serialization failed.", exception);
        }
        var bytes = output.toByteArray();
        return new Artifact(
                bytes,
                "csv".equals(work.format()) ? "text/csv" : "application/x-ndjson",
                filename,
                sha256(bytes));
    }

    private void writeJsonLine(
            ByteArrayOutputStream output, Object value, long maximumBytes) throws Exception {
        output.write(objectMapper.writeValueAsBytes(value));
        output.write('\n');
        requireSize(output, maximumBytes);
    }

    private static void line(
            ByteArrayOutputStream output, List<String> values, long maximumBytes) {
        output.writeBytes((String.join(",", values) + "\r\n").getBytes(StandardCharsets.UTF_8));
        requireSize(output, maximumBytes);
    }

    private static void requireSize(ByteArrayOutputStream output, long maximumBytes) {
        if (output.size() > maximumBytes) {
            throw new ExportLimitException("workforce.export.byte_limit");
        }
    }

    private String csv(String value) {
        var normalized = value
                .replace("\r\n", " ")
                .replace('\r', ' ')
                .replace('\n', ' ')
                .replace('\t', ' ')
                .codePoints()
                .filter(code -> !Character.isISOControl(code))
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString();
        if (normalized.stripLeading().matches("^[=+@-].*")) normalized = "'" + normalized;
        return "\"" + normalized.replace("\"", "\"\"") + "\"";
    }

    private String display(Object value) {
        if (value == null) return "";
        var normalized = normalizeValue(value);
        if (normalized instanceof String text) return text;
        try {
            return objectMapper.writeValueAsString(normalized);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to serialize an export value.", exception);
        }
    }

    private Map<String, Object> normalize(
            Map<String, Object> row, List<String> keys) {
        var normalized = new LinkedHashMap<String, Object>();
        keys.forEach(key -> normalized.put(key, normalizeValue(row.get(key))));
        return normalized;
    }

    private Object normalizeValue(Object value) {
        if (value == null || value instanceof Number || value instanceof Boolean) return value;
        if (value instanceof java.time.temporal.TemporalAccessor
                || value instanceof UUID
                || value instanceof java.sql.Date) {
            return value.toString();
        }
        return safeText(value.toString());
    }

    private static String safeText(String value) {
        return value.replaceAll("(?i)https?://\\S+|www\\.\\S+","[link-redacted]")
                .replace('<',' ').replace('>',' ')
                .replace("\r\n"," ").replace('\r',' ').replace('\n',' ').replace('\t',' ')
                .codePoints().filter(code -> !Character.isISOControl(code))
                .collect(StringBuilder::new,StringBuilder::appendCodePoint,StringBuilder::append)
                .toString();
    }

    private static List<String> projectionKeys(String projection) {
        return switch (projection) {
            case "workforce-directory-summary-v1" -> List.of(
                    "memberId","memberNumber","displayName","pathway","status",
                    "primaryFacilityId","activatedAt");
            case "credential-expiry-summary-v1" -> List.of(
                    "credentialId","memberId","memberNumber","credentialTypeCode",
                    "status","expiryDate","bucket");
            case "workforce-configuration-summary-v1" -> List.of(
                    "snapshotId","displayNumber","parentSnapshotId","snapshotDigest","status",
                    "effectiveAt","supersededAt","makerId","checkerId","activatorId");
            case "workforce-audit-summary-v1","member-timeline-summary-v1" -> List.of(
                    "eventId","occurredAt","actorId","actorKind","operation","eventName",
                    "schemaVersion","subjectType","subjectId","correlationId","outcome",
                    "risk","redactionMarker");
            case "workforce-audit-detail-v1","member-evidence-detail-v1" -> List.of(
                    "eventId","occurredAt","actorId","actorKind","operation","eventName",
                    "schemaVersion","subjectType","subjectId","correlationId","outcome",
                    "risk","redactionMarker","protectedReasonPresent","decisionReferenceId",
                    "evidenceReferenceId","policyVersion","evidenceDigest");
            case "credential-decision-detail-v1" -> List.of(
                    "verificationId","credentialId","memberId","decision","decisionReasonCode",
                    "credentialDigest","evidenceDigest","policyVersion","registryVersion",
                    "decidedAt","reviewerId");
            case "scope-decision-detail-v1" -> List.of(
                    "scopeId","memberId","definitionId","status","resultDigest","submittedBy",
                    "submittedAt","decidedBy","decisionCode","decidedAt","effectiveFrom",
                    "effectiveTo");
            default -> throw new IllegalArgumentException("unsupported workforce export projection");
        };
    }

    private Map<String, Object> lifecyclePayload(
            WorkforceExportStore.Work work, String failureCode) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("exportId", work.exportId());
        payload.put("state", work.status());
        payload.put("artifactDigest", work.artifactDigest());
        payload.put("rowCount", work.rowCount());
        payload.put("expiryTime", work.expiresAt());
        payload.put("failureCode", failureCode);
        return payload;
    }

    private ServiceIdentityAuthorizationRequest request(Command command) {
        return new ServiceIdentityAuthorizationRequest(
                command.organizationId(),
                command.presentedCredential(),
                SERVICE_IDENTITY,
                command.correlationId(),
                new OperationKey("m2.export.generate"));
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to serialize workforce export evidence.", exception);
        }
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    public record Command(
            UUID organizationId,
            UUID exportId,
            String workerId,
            String presentedCredential,
            String correlationId) {}

    public record SweepCommand(
            UUID organizationId,
            int maximumItems,
            String presentedCredential,
            String correlationId) {}

    public record Result(
            UUID exportId,
            String status,
            Integer rowCount,
            Long byteCount,
            String failureCode) {}

    private record Artifact(byte[] bytes, String contentType, String filename, String digest) {}

    private static final class ExportLimitException extends RuntimeException {
        private final String code;

        private ExportLimitException(String code) {
            this.code = code;
        }
    }
}
