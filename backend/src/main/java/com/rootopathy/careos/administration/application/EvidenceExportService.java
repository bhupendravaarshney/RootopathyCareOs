package com.rootopathy.careos.administration.application;

import com.rootopathy.careos.administration.domain.EvidenceExportDirectory;
import com.rootopathy.careos.governance.application.GovernedMutationExecutor;
import com.rootopathy.careos.governance.domain.*;
import com.rootopathy.careos.tenancy.application.TenantAuthorizationOperations;
import com.rootopathy.careos.tenancy.domain.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class EvidenceExportService {
  private static final Set<String> PROJECTIONS = Set.of(
      "history-summary-v1", "history-detail-v1", "audit-summary-v1", "audit-detail-v1");
  private static final Set<String> FORMATS = Set.of("csv", "jsonl");
  private static final Set<String> PURPOSES = Set.of(
      "configuration_review", "regulatory_evidence", "security_investigation", "data_correction");
  private static final Set<String> HISTORY_FILTERS = Set.of(
      "from", "to", "status", "changeType", "actorId", "subjectType", "subjectId", "correlationId");
  private static final Set<String> AUDIT_FILTERS = Set.of(
      "from", "to", "actorId", "operation", "eventName", "schemaVersion", "subjectType", "subjectId", "outcome", "risk", "correlationId");
  private static final Pattern KEY = Pattern.compile("[A-Za-z0-9._:-]{16,128}");
  private static final Pattern ETAG = Pattern.compile("\"evidence-export:([0-9a-fA-F-]{36}):([0-9]+)\"");
  private final TenantAuthorizationOperations authorization;
  private final GovernedMutationExecutor mutations;
  private final EvidenceExportStore store;
  private final EvidenceExportArtifactStore artifacts;
  private final ObjectMapper mapper;
  private final Clock clock;

  public EvidenceExportService(
      TenantAuthorizationOperations authorization,
      GovernedMutationExecutor mutations,
      EvidenceExportStore store,
      EvidenceExportArtifactStore artifacts,
      ObjectMapper mapper,
      Clock clock) {
    this.authorization = authorization;
    this.mutations = mutations;
    this.store = store;
    this.artifacts = artifacts;
    this.mapper = mapper;
    this.clock = clock;
  }

  public EvidenceExportDirectory directory(Read command) {
    return authorization.execute(
        request(command.organizationId(), command.actorId(), command.correlationId(),
            command.audit() ? "evidence.audit.read" : "evidence.history.read", null, null, null),
        context -> store.directory(context, command.audit()));
  }

  public IdempotencyOutcome request(Request command) {
    var reason = text(command.reason(), 10, 500);
    key(command.idempotencyKey());
    if (!PROJECTIONS.contains(command.projection())
        || !FORMATS.contains(command.format())
        || !PURPOSES.contains(command.purposeCode())
        || command.legalBasisKey() == null
        || !command.legalBasisKey().matches("[a-z][a-z0-9._:-]{1,79}")) {
      throw new IllegalArgumentException("invalid export policy selection");
    }
    var filters = validateFilters(command.projection(), command.filters());
    var filtersJson = json(filters);
    var filterDigest = hash(filtersJson);
    var draft = new EvidenceExportStore.Draft(
        command.projection(), command.format(), filtersJson, filterDigest,
        command.purposeCode(), command.legalBasisKey(), reason,
        command.projection().contains("-detail-"));
    return mutations.execute(
        request(command.organizationId(), command.actorId(), command.correlationId(),
            "evidence.export.request", reason,
            command.recentAuthenticationAt(), command.mfaAuthenticatedAt()),
        new IdempotencyCommand(
            "evidence.export.request", command.idempotencyKey(), hash(draft),
            clock.instant().plusSeconds(86400)),
        context -> {
          var result = store.request(context, draft);
          var audit = new AuditRecord(
              "evidence.export.requested", 1, "evidence_export", result.exportId(),
              reason, payload(result));
          var governance = "authorized".equals(result.status())
              ? new GovernanceEvidence(audit, new OutboxRecord(
                  "evidence.export.authorized", 1, "evidence_export", result.exportId(),
                  workerPayload(result)))
              : GovernanceEvidence.auditOnly(audit);
          return new GovernedMutation(
              new IdempotentResponse(201, "application/json", json(result.directory())),
              governance);
        });
  }

  public IdempotencyOutcome decide(Decision command) {
    var reason = text(command.reason(), 10, 500);
    key(command.idempotencyKey());
    var revision = revision(command.ifMatch(), command.exportId());
    return mutations.execute(
        request(command.organizationId(), command.actorId(), command.correlationId(),
            "evidence.export.approve", reason,
            command.recentAuthenticationAt(), command.mfaAuthenticatedAt()),
        new IdempotencyCommand(
            "evidence.export.approve", command.idempotencyKey(),
            hash(command.exportId(), revision, command.authorize(), reason),
            clock.instant().plusSeconds(86400)),
        context -> {
          var result = store.decide(
              context, command.exportId(), revision, command.authorize(), reason);
          var event = command.authorize()
              ? "evidence.export.authorized" : "evidence.export.denied";
          var audit = new AuditRecord(
              event, 1, "evidence_export", result.exportId(), reason, payload(result));
          var evidence = command.authorize()
              ? new GovernanceEvidence(audit, new OutboxRecord(
                  event, 1, "evidence_export", result.exportId(), workerPayload(result)))
              : GovernanceEvidence.auditOnly(audit);
          return new GovernedMutation(
              new IdempotentResponse(200, "application/json", json(result.directory())), evidence);
        });
  }

  public IdempotencyOutcome access(Access command) {
    var reason = text(command.reason(), 10, 500);
    key(command.idempotencyKey());
    if (!PURPOSES.contains(command.purposeCode())) {
      throw new IllegalArgumentException("invalid export access purpose");
    }
    var revision = revision(command.ifMatch(), command.exportId());
    var authorizationRequest = request(
        command.organizationId(), command.actorId(), command.correlationId(),
        "evidence.export.access", reason,
        command.recentAuthenticationAt(), command.mfaAuthenticatedAt());
    var persisted = mutations.execute(
        authorizationRequest,
        new IdempotencyCommand(
            "evidence.export.access", command.idempotencyKey(),
            hash(command.exportId(), revision, command.purposeCode(), reason),
            clock.instant().plusSeconds(600)),
        context -> {
          var access = store.access(context, command.exportId(), revision, command.purposeCode());
          var response = json(Map.of(
              "exportId", access.exportId(),
              "expiresAt", access.expiresAt(),
              "artifactReference", access.artifactReference(),
              "artifactDigest", access.artifactDigest(),
              "contentType", access.contentType(),
              "filename", access.filename()));
          var payload = json(Map.of(
              "artifactDigest", access.artifactDigest(),
              "exportId", access.exportId(),
              "expiryTime", grantExpiry(access.expiresAt()),
              "state", "access_authorized"));
          return new GovernedMutation(
              new IdempotentResponse(200, "application/json", response),
              GovernanceEvidence.auditOnly(new AuditRecord(
                  "evidence.export.accessed", 1, "evidence_export", access.exportId(),
                  reason, payload)));
        });
    var material = accessMaterial(persisted.response());
    return authorization.execute(authorizationRequest, context -> {
      var grant = artifacts.createReadGrant(
          context,
          material.exportId(),
          material.artifactReference(),
          material.artifactDigest(),
          material.filename(),
          grantTtl(material.expiresAt()));
      var response = json(Map.of(
          "exportId", material.exportId(),
          "downloadUrl", grant.readUri().toString(),
          "expiresAt", grant.expiresAt(),
          "artifactDigest", material.artifactDigest(),
          "contentType", material.contentType(),
          "filename", material.filename()));
      return new IdempotencyOutcome(
          new IdempotentResponse(
              persisted.response().statusCode(), persisted.response().mediaType(), response),
          persisted.replayed());
    });
  }

  private Duration grantTtl(Instant artifactExpiresAt) {
    var ttl = Duration.between(clock.instant(), artifactExpiresAt);
    if (ttl.compareTo(Duration.ofMinutes(10)) > 0) ttl = Duration.ofMinutes(10);
    if (ttl.compareTo(Duration.ofSeconds(1)) < 0) {
      throw new IllegalArgumentException("ready export is unavailable");
    }
    return ttl;
  }

  private Instant grantExpiry(Instant artifactExpiresAt) {
    return clock.instant().plus(grantTtl(artifactExpiresAt));
  }

  private AccessMaterial accessMaterial(IdempotentResponse response) {
    try {
      var values = mapper.readValue(
          response.bodyJson(), new tools.jackson.core.type.TypeReference<Map<String, String>>() {});
      return new AccessMaterial(
          UUID.fromString(values.get("exportId")),
          Objects.requireNonNull(values.get("artifactReference")),
          Objects.requireNonNull(values.get("artifactDigest")),
          Objects.requireNonNull(values.get("contentType")),
          Objects.requireNonNull(values.get("filename")),
          Instant.parse(values.get("expiresAt")));
    } catch (RuntimeException exception) {
      throw new IllegalStateException("Stored export access result is invalid", exception);
    }
  }

  private Map<String, Object> validateFilters(String projection, Map<String, Object> supplied) {
    var filters = new TreeMap<String, Object>();
    if (supplied != null) filters.putAll(supplied);
    var allowed = projection.startsWith("history-") ? HISTORY_FILTERS : AUDIT_FILTERS;
    if (!allowed.containsAll(filters.keySet())) {
      throw new IllegalArgumentException("unsupported export filter");
    }
    var from = requiredInstant(filters, "from");
    var to = requiredInstant(filters, "to");
    var maximum = projection.startsWith("history-") ? Duration.ofDays(366) : Duration.ofDays(90);
    if (!to.isAfter(from) || Duration.between(from, to).compareTo(maximum) > 0) {
      throw new IllegalArgumentException("export filter range is invalid");
    }
    filters.replaceAll((key, value) -> normalizeFilter(key, value));
    return Collections.unmodifiableMap(new LinkedHashMap<>(filters));
  }

  private Object normalizeFilter(String key, Object value) {
    if (value == null) throw new IllegalArgumentException("export filters cannot be null");
    if (Set.of("from", "to").contains(key)) return Instant.parse(value.toString()).toString();
    if (Set.of("actorId", "subjectId").contains(key)) return UUID.fromString(value.toString()).toString();
    if (key.equals("schemaVersion")) {
      var number = value instanceof Number n ? n.intValue() : Integer.parseInt(value.toString());
      if (number < 1 || number > 1000) throw new IllegalArgumentException("invalid schema version");
      return number;
    }
    var text = value.toString().strip();
    if (text.isEmpty() || text.length() > 180 || text.codePoints().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException("invalid export filter value");
    }
    if (key.equals("outcome") && !Set.of("success", "failure").contains(text)
        || key.equals("risk") && !Set.of("standard", "high", "restricted").contains(text)
        || key.equals("status") && !Set.of("draft", "validated", "submitted", "approved", "rejected", "active", "superseded").contains(text)
        || key.equals("changeType") && !Set.of("activated", "closed", "retired", "ended").contains(text)
        || Set.of("operation", "eventName", "subjectType", "correlationId").contains(key)
            && !text.matches("[A-Za-z0-9][A-Za-z0-9._:-]*")) {
      throw new IllegalArgumentException("invalid export filter value");
    }
    return text;
  }

  private static Instant requiredInstant(Map<String, Object> filters, String key) {
    if (!filters.containsKey(key)) throw new IllegalArgumentException("export time range is required");
    try { return Instant.parse(filters.get(key).toString()); }
    catch (RuntimeException exception) { throw new IllegalArgumentException("invalid export time range"); }
  }

  private String payload(EvidenceExportStore.Result result) {
    var values = new LinkedHashMap<String, Object>();
    values.put("exportId", result.exportId());
    values.put("projection", result.projection());
    values.put("format", result.format());
    values.put("filterDigest", result.filterDigest());
    values.put("purposeCode", result.purposeCode());
    values.put("approvalId", result.approvalId());
    return json(values);
  }

  private String workerPayload(EvidenceExportStore.Result result) {
    return json(Map.of(
        "exportId", result.exportId(),
        "projection", result.projection(),
        "format", result.format(),
        "filterDigest", result.filterDigest(),
        "purposeCode", result.purposeCode()));
  }

  private TenantAuthorizationRequest request(
      UUID organizationId, UUID actorId, String correlationId, String operation,
      String reason, Instant recent, Instant mfa) {
    return new TenantAuthorizationRequest(
        organizationId,
        new AuthenticatedActorContext(actorId, "evidence-export", correlationId),
        new OperationKey(operation), reason, recent, mfa, null);
  }

  private static long revision(String etag, UUID id) {
    var matcher = etag == null ? null : ETAG.matcher(etag);
    if (matcher == null || !matcher.matches() || !UUID.fromString(matcher.group(1)).equals(id)) {
      throw new IllegalArgumentException("strong export If-Match required");
    }
    return Long.parseLong(matcher.group(2));
  }

  private static void key(String value) {
    if (value == null || !KEY.matcher(value).matches()) {
      throw new IllegalArgumentException("invalid Idempotency-Key");
    }
  }

  private static String text(String value, int minimum, int maximum) {
    var normalized = value == null ? "" : value.strip();
    if (normalized.length() < minimum || normalized.length() > maximum
        || normalized.codePoints().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException("invalid reason");
    }
    return normalized;
  }

  private String json(Object value) {
    try { return mapper.writeValueAsString(value); }
    catch (Exception exception) { throw new IllegalStateException(exception); }
  }

  private static String hash(Object... values) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(Arrays.deepToString(values).getBytes(StandardCharsets.UTF_8)));
    } catch (Exception exception) {
      throw new IllegalStateException(exception);
    }
  }

  public record Read(UUID organizationId, UUID actorId, String correlationId, boolean audit) {}
  public record Request(
      UUID organizationId, UUID actorId, String correlationId, String idempotencyKey,
      String projection, String format, Map<String, Object> filters, String purposeCode,
      String legalBasisKey, String reason, Instant recentAuthenticationAt,
      Instant mfaAuthenticatedAt) {}
  public record Decision(
      UUID organizationId, UUID actorId, String correlationId, String idempotencyKey,
      String ifMatch, UUID exportId, boolean authorize, String reason,
      Instant recentAuthenticationAt, Instant mfaAuthenticatedAt) {}
  public record Access(
      UUID organizationId, UUID actorId, String correlationId, String idempotencyKey,
      String ifMatch, UUID exportId, String purposeCode, String reason,
      Instant recentAuthenticationAt, Instant mfaAuthenticatedAt) {}

  private record AccessMaterial(
      UUID exportId,
      String artifactReference,
      String artifactDigest,
      String contentType,
      String filename,
      Instant expiresAt) {}
}
