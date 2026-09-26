package com.rootopathy.careos.scheduling.application;

import com.rootopathy.careos.governance.application.GovernedMutationExecutor;
import com.rootopathy.careos.governance.domain.AuditRecord;
import com.rootopathy.careos.governance.domain.GovernanceEvidence;
import com.rootopathy.careos.governance.domain.GovernedMutation;
import com.rootopathy.careos.governance.domain.IdempotencyCommand;
import com.rootopathy.careos.governance.domain.IdempotencyOutcome;
import com.rootopathy.careos.governance.domain.IdempotentResponse;
import com.rootopathy.careos.governance.domain.OutboxRecord;
import com.rootopathy.careos.scheduling.domain.SchedulingScreen;
import com.rootopathy.careos.tenancy.application.TenantAuthorizationOperations;
import com.rootopathy.careos.tenancy.domain.AuthenticatedActorContext;
import com.rootopathy.careos.tenancy.domain.OperationKey;
import com.rootopathy.careos.tenancy.domain.TenantAuthorizationRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public final class SchedulingService {
    private static final String JSON = "application/json";
    private static final String PURPOSE = "scheduling";
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9._:-]{16,128}");
    private static final Pattern ETAG = Pattern.compile(
            "\\\"m4:(P4-[0-9]{2}):([0-9a-fA-F-]{36}):([0-9]{1,19})\\\"");

    private final TenantAuthorizationOperations authorization;
    private final GovernedMutationExecutor governedMutations;
    private final SchedulingStore store;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public SchedulingService(
            TenantAuthorizationOperations authorization,
            GovernedMutationExecutor governedMutations,
            SchedulingStore store,
            ObjectMapper objectMapper,
            Clock clock) {
        this.authorization = authorization;
        this.governedMutations = governedMutations;
        this.store = store;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public SchedulingScreen screen(ReadCommand command) {
        Objects.requireNonNull(command, "command");
        var spec = SchedulingScreenCatalogue.screen(command.screenId());
        if (command.cursor() != null && !command.cursor().isBlank()) {
            throw invalid("The scheduling cursor is invalid or expired; restart from the first page.");
        }
        var limit = command.limit() == null ? 50 : command.limit();
        if (limit < 1 || limit > 100) throw invalid("limit must be between 1 and 100.");
        var query = new SchedulingStore.ScreenQuery(
                command.screenId(),
                command.patientId(),
                command.appointmentId(),
                command.requestId(),
                optional(command.search(), 100, "q"),
                optional(command.status(), 120, "status"),
                limit);
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
        var spec = SchedulingScreenCatalogue.mutation(command.screenId(), command.actionKey());
        var key = requireIdempotencyKey(command.idempotencyKey());
        var reason = spec.reasonRequired()
                ? requireReason(command.reason())
                : optional(command.reason(), 500, "reason");
        if (spec.targetRequired() && command.targetId() == null) {
            throw invalid("targetId is required for this action.");
        }
        var revision = spec.ifMatchRequired()
                ? Long.valueOf(
                        requireRevision(command.ifMatch(), command.screenId(), command.targetId()))
                : optionalRevision(command.ifMatch(), command.screenId(), command.targetId());
        var fields = normalizeFields(command.fields());
        requireFields(spec, fields);
        var now = clock.instant();
        var mutation = new SchedulingStore.MutationCommand(
                command.screenId(),
                command.actionKey(),
                command.targetId(),
                command.patientId(),
                command.appointmentId(),
                command.requestId(),
                revision,
                reason,
                fields,
                now,
                command.correlationId());
        var idempotency = new IdempotencyCommand(
                spec.operation(),
                key,
                hash(
                        spec.operation(),
                        command.screenId(),
                        command.actionKey(),
                        command.targetId(),
                        command.patientId(),
                        command.appointmentId(),
                        command.requestId(),
                        revision,
                        reason,
                        canonicalFields(fields)),
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
                    var response = project(
                            context,
                            SchedulingScreenCatalogue.screen(command.screenId()),
                            new SchedulingStore.ScreenQuery(
                                    command.screenId(),
                                    result.patientId(),
                                    result.appointmentId(),
                                    result.requestId(),
                                    null,
                                    null,
                                    50));
                    return governed(result, reason, response);
                });
    }

    private SchedulingScreen project(
            com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext context,
            SchedulingScreenCatalogue.ScreenSpec spec,
            SchedulingStore.ScreenQuery query) {
        var projection = store.projection(context, query);
        var actions = SchedulingScreenCatalogue.projectedActions(spec, store.permissions(context));
        var permittedTargets = actions.stream()
                .filter(SchedulingScreen.Action::targetRequired)
                .map(SchedulingScreen.Action::key)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        var rows = projection.rows().stream()
                .map(row -> new SchedulingScreen.Row(
                        row.id(),
                        row.patientId(),
                        row.appointmentId(),
                        row.status(),
                        row.revision(),
                        row.etag(),
                        row.values(),
                        row.allowedActionKeys().stream()
                                .filter(permittedTargets::contains)
                                .toList()))
                .toList();
        return new SchedulingScreen(
                context.organizationId(),
                spec.id(),
                spec.title(),
                spec.purpose(),
                projection.generatedAt(),
                projection.metrics(),
                projection.columns(),
                rows,
                actions,
                projection.notices(),
                null,
                query.limit());
    }

    private GovernedMutation governed(
            SchedulingStore.MutationResult result,
            String reason,
            SchedulingScreen response) {
        var audit = new AuditRecord(
                result.auditEvent(),
                1,
                result.subjectType(),
                result.subjectId(),
                reason,
                json(result.auditPayload()));
        var evidence = result.outboxEvent() == null
                ? GovernanceEvidence.auditOnly(audit)
                : new GovernanceEvidence(
                        audit,
                        new OutboxRecord(
                                result.outboxEvent(),
                                1,
                                result.aggregateType(),
                                result.subjectId(),
                                json(result.outboxPayload())));
        return new GovernedMutation(
                new IdempotentResponse(result.statusCode(), JSON, json(response)), evidence);
    }

    private static void requireFields(
            SchedulingScreenCatalogue.ActionSpec spec, Map<String, String> fields) {
        var supported = spec.fields().stream()
                .map(SchedulingScreen.Field::key)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        fields.keySet().forEach(key -> {
            if (!supported.contains(key)) throw invalid("The action field '" + key + "' is not supported.");
        });
        spec.fields().forEach(field -> {
            if (field.required()
                    && (fields.get(field.key()) == null || fields.get(field.key()).isBlank())) {
                throw invalid(field.label() + " is required.");
            }
        });
    }

    private static Map<String, String> normalizeFields(Map<String, String> supplied) {
        if (supplied == null || supplied.size() > 64) {
            throw invalid("fields must contain at most 64 values.");
        }
        var normalized = new LinkedHashMap<String, String>();
        supplied.forEach((key, value) -> {
            if (key == null
                    || value == null
                    || !key.matches("[A-Za-z][A-Za-z0-9_.-]{0,79}")) {
                throw invalid("Action field names and values must be non-null and well formed.");
            }
            var clean = Normalizer.normalize(value, Normalizer.Form.NFC).strip();
            if (clean.length() > 2000) throw invalid("Action field values are too long.");
            normalized.put(key, clean);
        });
        return Map.copyOf(normalized);
    }

    private static long requireRevision(String value, String screenId, UUID targetId) {
        var revision = optionalRevision(value, screenId, targetId);
        if (revision == null) {
            throw new SchedulingException(
                    SchedulingException.Reason.PRECONDITION_REQUIRED,
                    "A strong If-Match revision is required.");
        }
        return revision;
    }

    private static Long optionalRevision(String value, String screenId, UUID targetId) {
        if (value == null || value.isBlank()) return null;
        var matcher = ETAG.matcher(value.strip());
        if (!matcher.matches()
                || !matcher.group(1).equals(screenId)
                || targetId == null
                || !matcher.group(2).equalsIgnoreCase(targetId.toString())) {
            throw invalid("If-Match does not identify the requested scheduling resource.");
        }
        try {
            return Long.parseLong(matcher.group(3));
        } catch (NumberFormatException exception) {
            throw invalid("If-Match revision is invalid.");
        }
    }

    private static String requireReason(String reason) {
        var value = optional(reason, 500, "reason");
        if (value == null || value.codePointCount(0, value.length()) < 10) {
            throw invalid("reason must contain 10 to 500 characters.");
        }
        return value;
    }

    private static String requireIdempotencyKey(String value) {
        if (value == null || !IDEMPOTENCY_KEY.matcher(value).matches()) {
            throw invalid("Idempotency-Key must contain 16 to 128 safe characters.");
        }
        return value;
    }

    private static String optional(String value, int maximum, String field) {
        if (value == null || value.isBlank()) return null;
        var normalized = Normalizer.normalize(value, Normalizer.Form.NFC).strip();
        if (normalized.codePointCount(0, normalized.length()) > maximum) {
            throw invalid(field + " is too long.");
        }
        return normalized;
    }

    private static String canonicalFields(Map<String, String> fields) {
        return fields.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    private static String hash(Object... values) {
        var canonical = new StringBuilder();
        for (var value : values) canonical.append(value == null ? "-" : value).append('\n');
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256")
                            .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to calculate scheduling evidence digest.", exception);
        }
    }

    private TenantAuthorizationRequest authorization(
            UUID organizationId,
            UUID actorId,
            String correlationId,
            String operation,
            String reason,
            Instant recentAuthenticationAt,
            Instant mfaAuthenticatedAt) {
        return new TenantAuthorizationRequest(
                organizationId,
                new AuthenticatedActorContext(actorId, PURPOSE, correlationId),
                new OperationKey(operation),
                reason,
                recentAuthenticationAt,
                mfaAuthenticatedAt,
                null);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to serialize the scheduling response.", exception);
        }
    }

    private static SchedulingException invalid(String message) {
        return new SchedulingException(SchedulingException.Reason.INVALID, message);
    }

    public record ReadCommand(
            UUID organizationId,
            UUID actorId,
            String correlationId,
            String screenId,
            UUID patientId,
            UUID appointmentId,
            UUID requestId,
            String search,
            String status,
            Integer limit,
            String cursor,
            Instant recentAuthenticationAt,
            Instant mfaAuthenticatedAt) {}

    public record ActionCommand(
            UUID organizationId,
            UUID actorId,
            String correlationId,
            String screenId,
            String actionKey,
            UUID targetId,
            UUID patientId,
            UUID appointmentId,
            UUID requestId,
            String reason,
            Map<String, String> fields,
            String ifMatch,
            String idempotencyKey,
            Instant recentAuthenticationAt,
            Instant mfaAuthenticatedAt) {}
}
