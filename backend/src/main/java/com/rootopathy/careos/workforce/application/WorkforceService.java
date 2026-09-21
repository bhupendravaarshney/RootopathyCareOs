package com.rootopathy.careos.workforce.application;

import com.rootopathy.careos.governance.application.GovernedMutationExecutor;
import com.rootopathy.careos.governance.domain.AuditRecord;
import com.rootopathy.careos.governance.domain.GovernanceEvidence;
import com.rootopathy.careos.governance.domain.GovernedMutation;
import com.rootopathy.careos.governance.domain.IdempotencyCommand;
import com.rootopathy.careos.governance.domain.IdempotencyOutcome;
import com.rootopathy.careos.governance.domain.IdempotentResponse;
import com.rootopathy.careos.governance.domain.OutboxRecord;
import com.rootopathy.careos.platform.application.DocumentSecurityOperations;
import com.rootopathy.careos.platform.domain.DocumentQuarantineRequest;
import com.rootopathy.careos.shared.domain.UuidV7Generator;
import com.rootopathy.careos.tenancy.application.TenantAuthorizationOperations;
import com.rootopathy.careos.tenancy.domain.AuthenticatedActorContext;
import com.rootopathy.careos.tenancy.domain.OperationKey;
import com.rootopathy.careos.tenancy.domain.TenantAuthorizationRequest;
import com.rootopathy.careos.workforce.domain.WorkforceScreen;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public final class WorkforceService {
    private static final String JSON = "application/json";
    private static final String PURPOSE = "workforce-administration";
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9._:-]{16,128}");
    private static final Pattern ETAG = Pattern.compile(
            "\\\"m2:(M2-[0-9]{2}):([0-9a-fA-F-]{36}):([0-9]{1,19})\\\"");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    private final TenantAuthorizationOperations authorization;
    private final GovernedMutationExecutor governedMutations;
    private final WorkforceStore store;
    private final DocumentSecurityOperations documents;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public WorkforceService(
            TenantAuthorizationOperations authorization,
            GovernedMutationExecutor governedMutations,
            WorkforceStore store,
            DocumentSecurityOperations documents,
            ObjectMapper objectMapper,
            Clock clock) {
        this.authorization = authorization;
        this.governedMutations = governedMutations;
        this.store = store;
        this.documents = documents;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public WorkforceScreen screen(ReadCommand command) {
        Objects.requireNonNull(command, "command");
        var spec = WorkforceScreenCatalogue.screen(command.screenId());
        var query = query(command);
        return authorization.execute(
                authorization(
                        command.organizationId(),
                        command.actorId(),
                        command.correlationId(),
                        spec.readOperation(),
                        null,
                        command.recentAuthenticationAt(),
                        command.mfaAuthenticatedAt()),
                context -> project(context, spec, query));
    }

    public IdempotencyOutcome act(ActionCommand command) {
        Objects.requireNonNull(command, "command");
        var spec = WorkforceScreenCatalogue.mutation(command.screenId(), command.actionKey());
        var key = requireIdempotencyKey(command.idempotencyKey());
        var reason = spec.reasonRequired() ? requireReason(command.reason()) : optionalReason(command.reason());
        var expectedRevision = spec.ifMatchRequired()
                ? requireRevision(command.ifMatch(), command.screenId(), command.targetId())
                : optionalRevision(command.ifMatch(), command.screenId(), command.targetId());
        if (spec.targetRequired() && command.targetId() == null) {
            throw invalid("targetId is required for this action.");
        }
        var fields = Map.copyOf(command.fields() == null ? Map.of() : command.fields());
        requireFields(spec, fields);
        var now = clock.instant();
        var mutation = new WorkforceStore.MutationCommand(
                command.screenId(),
                command.actionKey(),
                command.targetId(),
                command.memberId(),
                expectedRevision,
                command.decision(),
                reason,
                fields,
                command.evidenceIds(),
                now);
        var idempotency = new IdempotencyCommand(
                spec.operation(),
                key,
                hash(
                        spec.operation(),
                        command.screenId(),
                        command.actionKey(),
                        command.targetId(),
                        command.memberId(),
                        expectedRevision,
                        command.decision(),
                        reason,
                        canonicalFields(fields),
                        command.evidenceIds()),
                now.plus(24, ChronoUnit.HOURS));

        return governedMutations.execute(
                authorization(
                        command.organizationId(),
                        command.actorId(),
                        command.correlationId(),
                        spec.operation(),
                        reason,
                        command.recentAuthenticationAt(),
                        command.mfaAuthenticatedAt()),
                idempotency,
                context -> {
                    var result = store.mutate(context, mutation);
                    var memberId = command.memberId();
                    if (memberId == null && "workforce_member".equals(result.subjectType())) {
                        memberId = result.subjectId();
                    }
                    var response = project(
                            context,
                            WorkforceScreenCatalogue.screen(command.screenId()),
                            new WorkforceStore.ScreenQuery(
                                    command.screenId(), memberId, null, null, 50));
                    return governed(result, reason, response);
                });
    }

    public IdempotencyOutcome uploadCredentialDocument(DocumentUploadCommand command) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(command.content(), "content");
        var key = requireIdempotencyKey(command.idempotencyKey());
        var reason = requireReason(command.reason());
        var fileName = requireSafeFileName(command.fileName());
        var mediaType = requireMediaType(command.mediaType());
        var digest = command.sha256() == null ? "" : command.sha256().strip().toLowerCase();
        if (!SHA256.matcher(digest).matches()) {
            throw invalid("sha256 must contain a lowercase SHA-256 digest.");
        }
        if (command.declaredSize() < 1 || command.declaredSize() > 26_214_400L) {
            throw invalid("declaredSize must be between 1 byte and 25 MiB.");
        }
        var now = clock.instant();
        var documentId = UuidV7Generator.randomUuid();
        var objectVersionId = UuidV7Generator.randomUuid();
        var storeCommand = new WorkforceStore.DocumentCommand(
                Objects.requireNonNull(command.credentialId(), "credentialId"),
                command.memberId(),
                fileName,
                mediaType,
                command.declaredSize(),
                digest,
                requireToken(command.retentionClass(), "retentionClass", 2, 80),
                reason,
                command.content(),
                now);
        var idempotency = new IdempotencyCommand(
                "credential.document.upload",
                key,
                hash(
                        "credential.document.upload",
                        command.credentialId(),
                        command.memberId(),
                        fileName,
                        mediaType,
                        command.declaredSize(),
                        digest,
                        command.retentionClass(),
                        reason),
                now.plus(24, ChronoUnit.HOURS));

        return governedMutations.execute(
                authorization(
                        command.organizationId(),
                        command.actorId(),
                        command.correlationId(),
                        "credential.document.upload",
                        reason,
                        command.recentAuthenticationAt(),
                        command.mfaAuthenticatedAt()),
                idempotency,
                context -> {
                    var evidence = documents.quarantine(
                            context,
                            new DocumentQuarantineRequest(
                                    documentId,
                                    objectVersionId,
                                    command.declaredSize(),
                                    mediaType,
                                    digest),
                            command.content());
                    var result = store.bindCredentialDocument(
                            context,
                            storeCommand,
                            evidence.document().documentId(),
                            evidence.document().objectVersionId(),
                            evidence.quarantinedAt());
                    var response = project(
                            context,
                            WorkforceScreenCatalogue.screen("M2-10"),
                            new WorkforceStore.ScreenQuery(
                                    "M2-10", command.memberId(), null, null, 50));
                    return governed(result, reason, response);
                });
    }

    private WorkforceScreen project(
            com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext context,
            WorkforceScreenCatalogue.ScreenSpec spec,
            WorkforceStore.ScreenQuery query) {
        var projection = store.projection(context, query);
        var actions = WorkforceScreenCatalogue.projectedActions(spec, store.permissions(context));
        return new WorkforceScreen(
                context.organizationId(),
                spec.id(),
                spec.title(),
                spec.purpose(),
                projection.generatedAt(),
                projection.metrics(),
                projection.columns(),
                projection.rows(),
                actions,
                projection.notices());
    }

    private GovernedMutation governed(
            WorkforceStore.MutationResult result, String reason, WorkforceScreen response) {
        var auditPayload = json(result.auditPayload());
        var audit = new AuditRecord(
                result.auditEvent(),
                1,
                result.subjectType(),
                result.subjectId(),
                reason,
                auditPayload);
        GovernanceEvidence evidence;
        if (result.outboxEvent() == null) {
            evidence = GovernanceEvidence.auditOnly(audit);
        } else {
            evidence = new GovernanceEvidence(
                    audit,
                    new OutboxRecord(
                            result.outboxEvent(),
                            1,
                            result.aggregateType(),
                            result.subjectId(),
                            json(result.outboxPayload())));
        }
        return new GovernedMutation(
                new IdempotentResponse(result.statusCode(), JSON, json(response)), evidence);
    }

    private static WorkforceStore.ScreenQuery query(ReadCommand command) {
        var search = normalizeOptional(command.search(), 120);
        var status = normalizeOptional(command.status(), 40);
        var limit = command.limit() == null ? 50 : command.limit();
        if (limit < 1 || limit > 100) {
            throw invalid("limit must be between 1 and 100.");
        }
        return new WorkforceStore.ScreenQuery(
                command.screenId(), command.memberId(), search, status, limit);
    }

    private static TenantAuthorizationRequest authorization(
            UUID organizationId,
            UUID actorId,
            String correlationId,
            String operation,
            String reason,
            Instant recentAuthenticationAt,
            Instant mfaAuthenticatedAt) {
        return new TenantAuthorizationRequest(
                Objects.requireNonNull(organizationId, "organizationId"),
                new AuthenticatedActorContext(
                        Objects.requireNonNull(actorId, "actorId"), PURPOSE, correlationId),
                new OperationKey(operation),
                reason,
                recentAuthenticationAt,
                mfaAuthenticatedAt,
                null);
    }

    private static void requireFields(
            WorkforceScreenCatalogue.ActionSpec spec, Map<String, String> fields) {
        var allowed = spec.fields().stream().map(WorkforceScreen.Field::key).collect(java.util.stream.Collectors.toSet());
        if (!allowed.containsAll(fields.keySet())) {
            throw invalid("The action contains an unsupported field.");
        }
        for (var field : spec.fields()) {
            var value = fields.get(field.key());
            if (field.required() && (value == null || value.isBlank())) {
                throw invalid(field.label() + " is required.");
            }
            if (value != null && value.length() > 2000) {
                throw invalid(field.label() + " is too long.");
            }
            if (value != null
                    && !field.options().isEmpty()
                    && field.options().stream().noneMatch(option -> option.value().equals(value))) {
                throw invalid(field.label() + " has an unsupported value.");
            }
        }
    }

    private static long requireRevision(String ifMatch, String screenId, UUID targetId) {
        var revision = optionalRevision(ifMatch, screenId, targetId);
        if (revision == null) {
            throw new WorkforceException(
                    WorkforceException.Reason.PRECONDITION_REQUIRED,
                    "A strong If-Match value for the selected record is required.");
        }
        return revision;
    }

    private static Long optionalRevision(String ifMatch, String screenId, UUID targetId) {
        if (ifMatch == null || ifMatch.isBlank()) {
            return null;
        }
        var matcher = ETAG.matcher(ifMatch.strip());
        if (!matcher.matches()
                || !matcher.group(1).equals(screenId)
                || targetId == null
                || !UUID.fromString(matcher.group(2)).equals(targetId)) {
            throw new WorkforceException(
                    WorkforceException.Reason.STALE,
                    "If-Match does not identify the selected workforce record.");
        }
        return Long.parseLong(matcher.group(3));
    }

    private static String requireIdempotencyKey(String value) {
        if (value == null || !IDEMPOTENCY_KEY.matcher(value).matches()) {
            throw invalid("Idempotency-Key must contain 16 to 128 safe characters.");
        }
        return value;
    }

    private static String requireReason(String value) {
        var normalized = Normalizer.normalize(value == null ? "" : value.strip(), Normalizer.Form.NFC);
        var length = normalized.codePointCount(0, normalized.length());
        if (length < 10 || length > 500 || normalized.codePoints().anyMatch(Character::isISOControl)) {
            throw invalid("reason must contain between 10 and 500 safe characters.");
        }
        return normalized;
    }

    private static String optionalReason(String value) {
        return value == null || value.isBlank() ? null : requireReason(value);
    }

    private static String requireSafeFileName(String value) {
        var normalized = requireToken(value, "fileName", 1, 255);
        if (normalized.indexOf('/') >= 0
                || normalized.indexOf('\\') >= 0
                || normalized.codePoints().anyMatch(Character::isISOControl)) {
            throw invalid("fileName contains unsupported characters.");
        }
        return normalized;
    }

    private static String requireMediaType(String value) {
        var normalized = requireToken(value, "mediaType", 3, 120).toLowerCase();
        if (!normalized.matches("[a-z0-9.+-]+/[a-z0-9.+-]+")) {
            throw invalid("mediaType has an unsupported format.");
        }
        return normalized;
    }

    private static String requireToken(String value, String name, int min, int max) {
        var normalized = value == null ? "" : value.strip();
        if (normalized.length() < min || normalized.length() > max) {
            throw invalid(name + " has an invalid length.");
        }
        return normalized;
    }

    private static String normalizeOptional(String value, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        var normalized = value.strip();
        if (normalized.length() > max || normalized.codePoints().anyMatch(Character::isISOControl)) {
            throw invalid("A screen filter has an invalid value.");
        }
        return normalized;
    }

    private static String canonicalFields(Map<String, String> fields) {
        var values = new ArrayList<>(fields.entrySet());
        values.sort(Comparator.comparing(Map.Entry::getKey));
        var canonical = new StringBuilder();
        for (var entry : values) {
            canonical.append(entry.getKey().length())
                    .append(':')
                    .append(entry.getKey())
                    .append('=')
                    .append(entry.getValue().length())
                    .append(':')
                    .append(entry.getValue())
                    .append(';');
        }
        return canonical.toString();
    }

    private static String hash(Object... values) {
        try {
            var canonical = new StringBuilder();
            for (var value : values) {
                var string = Objects.toString(value, "<null>");
                canonical.append(string.length()).append(':').append(string).append(';');
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to hash the workforce command.", exception);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to serialize the workforce response.", exception);
        }
    }

    private static WorkforceException invalid(String message) {
        return new WorkforceException(WorkforceException.Reason.INVALID, message);
    }

    public record ReadCommand(
            UUID organizationId,
            UUID actorId,
            String correlationId,
            String screenId,
            UUID memberId,
            String search,
            String status,
            Integer limit,
            Instant recentAuthenticationAt,
            Instant mfaAuthenticatedAt) {}

    public record ActionCommand(
            UUID organizationId,
            UUID actorId,
            String correlationId,
            String screenId,
            String actionKey,
            UUID targetId,
            UUID memberId,
            String decision,
            String reason,
            Map<String, String> fields,
            List<UUID> evidenceIds,
            String ifMatch,
            String idempotencyKey,
            Instant recentAuthenticationAt,
            Instant mfaAuthenticatedAt) {
        public ActionCommand {
            evidenceIds = List.copyOf(evidenceIds == null ? List.of() : evidenceIds);
        }
    }

    public record DocumentUploadCommand(
            UUID organizationId,
            UUID actorId,
            String correlationId,
            UUID credentialId,
            UUID memberId,
            String fileName,
            String mediaType,
            long declaredSize,
            String sha256,
            String retentionClass,
            String reason,
            InputStream content,
            String idempotencyKey,
            Instant recentAuthenticationAt,
            Instant mfaAuthenticatedAt) {}
}
