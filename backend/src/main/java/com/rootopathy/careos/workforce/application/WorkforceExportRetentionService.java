package com.rootopathy.careos.workforce.application;

import com.rootopathy.careos.administration.application.EvidenceExportArtifactStore;
import com.rootopathy.careos.governance.application.ConsumerInboxOperations;
import com.rootopathy.careos.governance.application.GovernanceEvidenceOperations;
import com.rootopathy.careos.governance.domain.AuditRecord;
import com.rootopathy.careos.governance.domain.GovernanceEvidence;
import com.rootopathy.careos.governance.domain.InboundOutboxEvent;
import com.rootopathy.careos.governance.domain.OutboxEnvelope;
import com.rootopathy.careos.governance.domain.OutboxRecord;
import com.rootopathy.careos.tenancy.application.ServiceIdentityAuthorizationOperations;
import com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext;
import com.rootopathy.careos.tenancy.domain.OperationKey;
import com.rootopathy.careos.tenancy.domain.ServiceIdentityAuthorizationRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public final class WorkforceExportRetentionService {
    private static final String SERVICE_IDENTITY = "m2-retention-worker-v1";
    private static final String CONSUMER = "m2-retention-worker-v1";
    private final ServiceIdentityAuthorizationOperations authorization;
    private final WorkforceExportStore store;
    private final EvidenceExportArtifactStore artifacts;
    private final GovernanceEvidenceOperations evidence;
    private final ConsumerInboxOperations inbox;
    private final ObjectMapper objectMapper;

    public WorkforceExportRetentionService(
            ServiceIdentityAuthorizationOperations authorization,
            WorkforceExportStore store,
            EvidenceExportArtifactStore artifacts,
            GovernanceEvidenceOperations evidence,
            ConsumerInboxOperations inbox,
            ObjectMapper objectMapper) {
        this.authorization = authorization;
        this.store = store;
        this.artifacts = artifacts;
        this.evidence = evidence;
        this.inbox = inbox;
        this.objectMapper = objectMapper;
    }

    public Result expire(Command command) {
        return authorization.execute(request(command), context -> {
            var expired = store.expire(context, command.exportId(), command.revision());
            var lifecycle = lifecyclePayload(expired, null);
            var disposal = disposalPayload(expired);
            evidence.record(
                    context,
                    new GovernanceEvidence(
                            new AuditRecord(
                                    "workforce.export.expired",
                                    1,
                                    "workforce_export",
                                    expired.exportId(),
                                    null,
                                    json(lifecycle)),
                            new OutboxRecord(
                                    "workforce.export.expired",
                                    1,
                                    "workforce_export",
                                    expired.exportId(),
                                    json(disposal))));
            evidence.record(
                    context,
                    GovernanceEvidence.outboxOnly(new OutboxRecord(
                            "workforce.export.disposal_requested",
                            1,
                            "workforce_export",
                            expired.exportId(),
                            json(disposal))));
            return new Result(expired.exportId(), "expired", expired.revision());
        });
    }

    public Result consume(OutboxEnvelope envelope, String presentedCredential) {
        if (!"workforce.export.disposal_requested".equals(envelope.eventName())
                || envelope.schemaVersion() != 1
                || !"workforce_export".equals(envelope.aggregateType())) {
            throw new IllegalArgumentException("unsupported workforce-retention event");
        }
        var request = new ServiceIdentityAuthorizationRequest(
                envelope.organizationId(),
                presentedCredential,
                SERVICE_IDENTITY,
                envelope.correlationId(),
                new OperationKey("m2.retention.dispose"));
        return authorization.execute(request, context -> {
            var result = new Result[1];
            inbox.execute(
                    context,
                    InboundOutboxEvent.from(CONSUMER, envelope),
                    () -> result[0] = disposeCurrent(context, envelope.aggregateId()));
            return result[0] == null
                    ? new Result(envelope.aggregateId(), "duplicate", null)
                    : result[0];
        });
    }

    public Result dispose(Command command) {
        return authorization.execute(request(command), context -> dispose(context, command));
    }

    private Result dispose(AuthorizedTenantContext context, Command command) {
        var work = store.forDisposal(context, command.exportId(), command.revision());
        return dispose(context, work);
    }

    private Result disposeCurrent(AuthorizedTenantContext context, UUID exportId) {
        return dispose(context, store.forDisposal(context, exportId));
    }

    private Result dispose(
            AuthorizedTenantContext context, WorkforceExportStore.Work work) {
        if (work.legalHold()) {
            throw new IllegalArgumentException("legal hold blocks workforce export disposal");
        }
        if (work.artifactReference() == null || work.artifactDigest() == null) {
            throw new IllegalArgumentException("expired workforce export artifact evidence is incomplete");
        }
        artifacts.delete(
                context,
                work.exportId(),
                work.artifactReference(),
                work.artifactDigest());
        var disposed = store.disposed(context, work.exportId(), work.revision());
        evidence.record(
                context,
                GovernanceEvidence.auditOnly(new AuditRecord(
                        "workforce.export.disposed",
                        1,
                        "workforce_export",
                        disposed.exportId(),
                        null,
                        json(lifecyclePayload(disposed, null)))));
        return new Result(disposed.exportId(), "disposed", disposed.revision());
    }

    private ServiceIdentityAuthorizationRequest request(Command command) {
        return new ServiceIdentityAuthorizationRequest(
                command.organizationId(),
                command.presentedCredential(),
                SERVICE_IDENTITY,
                command.correlationId(),
                new OperationKey("m2.retention.dispose"));
    }

    private static Map<String, Object> lifecyclePayload(
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

    private static Map<String, Object> disposalPayload(WorkforceExportStore.Work work) {
        return Map.of(
                "exportId", work.exportId(),
                "artifactDigest", work.artifactDigest(),
                "expiryTime", work.expiresAt());
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to serialize retention evidence.", exception);
        }
    }

    public record Command(
            UUID organizationId,
            UUID exportId,
            long revision,
            String presentedCredential,
            String correlationId) {}

    public record Result(UUID exportId, String status, Long revision) {}
}
