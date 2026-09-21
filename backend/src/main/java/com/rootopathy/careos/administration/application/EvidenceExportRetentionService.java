package com.rootopathy.careos.administration.application;

import com.rootopathy.careos.governance.application.GovernanceEvidenceOperations;
import com.rootopathy.careos.governance.application.ConsumerInboxOperations;
import com.rootopathy.careos.governance.domain.*;
import com.rootopathy.careos.tenancy.application.ServiceIdentityAuthorizationOperations;
import com.rootopathy.careos.tenancy.domain.*;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class EvidenceExportRetentionService {
  private final ServiceIdentityAuthorizationOperations authorization;
  private final EvidenceExportStore store;
  private final EvidenceExportArtifactStore artifacts;
  private final GovernanceEvidenceOperations evidence;
  private final ConsumerInboxOperations inbox;
  private final ObjectMapper mapper;

  public EvidenceExportRetentionService(
      ServiceIdentityAuthorizationOperations authorization,
      EvidenceExportStore store,
      EvidenceExportArtifactStore artifacts,
      GovernanceEvidenceOperations evidence,
      ConsumerInboxOperations inbox,
      ObjectMapper mapper) {
    this.authorization = authorization;
    this.store = store;
    this.artifacts = artifacts;
    this.evidence = evidence;
    this.inbox = inbox;
    this.mapper = mapper;
  }

  public Result consume(OutboxEnvelope envelope, long revision, String presentedCredential) {
    if (!"evidence.export.disposal_requested".equals(envelope.eventName())
        || envelope.schemaVersion() != 1 || !"evidence_export".equals(envelope.aggregateType())) {
      throw new IllegalArgumentException("unsupported retention-worker event");
    }
    var command = new Command(envelope.organizationId(), envelope.aggregateId(), revision,
        presentedCredential, envelope.correlationId());
    return authorization.execute(request(command, "evidence.export.dispose"), context -> {
      var result = new Result[1];
      inbox.execute(context, InboundOutboxEvent.from("m1-retention-worker-v1", envelope),
          () -> result[0] = dispose(context, command));
      return result[0] == null
          ? new Result(command.exportId(), "duplicate", command.revision())
          : result[0];
    });
  }

  public Result expire(Command command) {
    return authorization.execute(request(command, "evidence.export.expire"), context -> {
      var expired = store.expire(context, command.exportId(), command.revision());
      var lifecycle = json(Map.of(
          "artifactDigest", expired.artifactDigest(),
          "exportId", expired.exportId(),
          "expiryTime", expired.expiresAt(),
          "state", "expired"));
      evidence.record(context, new GovernanceEvidence(
          new AuditRecord("evidence.export.expired", 1, "evidence_export", expired.exportId(), null, lifecycle),
          new OutboxRecord("evidence.export.expired", 1, "evidence_export", expired.exportId(), lifecycle)));
      var disposal = json(Map.of(
          "artifactDigest", expired.artifactDigest(),
          "exportId", expired.exportId(),
          "expiryTime", expired.expiresAt()));
      evidence.record(context, GovernanceEvidence.outboxOnly(new OutboxRecord(
          "evidence.export.disposal_requested", 1, "evidence_export", expired.exportId(), disposal)));
      return new Result(expired.exportId(), "expired", expired.lockVersion());
    });
  }

  public Result dispose(Command command) {
    return authorization.execute(request(command, "evidence.export.dispose"),
        context -> dispose(context, command));
  }

  private Result dispose(AuthorizedTenantContext context, Command command) {
      var access = store.accessForDisposal(context, command.exportId(), command.revision());
      if (access.legalHold()) throw new IllegalArgumentException("legal hold blocks export disposal");
      artifacts.delete(
          context, access.exportId(), access.artifactReference(), access.artifactDigest());
      var disposed = store.dispose(context, command.exportId(), command.revision());
      var payload = json(Map.of(
          "artifactDigest", disposed.artifactDigest(),
          "exportId", disposed.exportId(),
          "expiryTime", disposed.expiresAt(),
          "state", "disposed"));
      evidence.record(context, GovernanceEvidence.auditOnly(new AuditRecord(
          "evidence.export.disposed", 1, "evidence_export", disposed.exportId(), null, payload)));
      return new Result(disposed.exportId(), "disposed", disposed.lockVersion());
  }

  private ServiceIdentityAuthorizationRequest request(Command command, String operation) {
    return new ServiceIdentityAuthorizationRequest(
        command.organizationId(), command.presentedCredential(), "m1-retention-worker-v1",
        command.correlationId(), new OperationKey(operation));
  }

  private String json(Object value) {
    try { return mapper.writeValueAsString(value); }
    catch (Exception exception) { throw new IllegalStateException(exception); }
  }

  public record Command(
      UUID organizationId, UUID exportId, long revision,
      String presentedCredential, String correlationId) {}
  public record Result(UUID exportId, String status, long lockVersion) {}
}
