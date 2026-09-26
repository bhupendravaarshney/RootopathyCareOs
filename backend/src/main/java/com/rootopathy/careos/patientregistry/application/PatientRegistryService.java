package com.rootopathy.careos.patientregistry.application;

import com.rootopathy.careos.governance.application.GovernedMutationExecutor;
import com.rootopathy.careos.governance.domain.AuditRecord;
import com.rootopathy.careos.governance.domain.GovernanceEvidence;
import com.rootopathy.careos.governance.domain.GovernedMutation;
import com.rootopathy.careos.governance.domain.IdempotencyCommand;
import com.rootopathy.careos.governance.domain.IdempotencyOutcome;
import com.rootopathy.careos.governance.domain.IdempotentResponse;
import com.rootopathy.careos.governance.domain.OutboxRecord;
import com.rootopathy.careos.patientregistry.domain.PatientRegistryScreen;
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
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public final class PatientRegistryService {
    private static final String JSON = "application/json";
    private static final String PURPOSE = "patient-registry";
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9._:-]{16,128}");
    private static final Pattern ETAG = Pattern.compile(
            "\\\"m3:(P3-[0-9]{2}):([0-9a-fA-F-]{36}):([0-9]{1,19})\\\"");
    private static final Set<String> IMPACT_ACTIONS =
            Set.of("request-patient-merge", "execute-patient-merge");

    private final TenantAuthorizationOperations authorization;
    private final GovernedMutationExecutor governedMutations;
    private final PatientRegistryStore store;
    private final PatientScreenCursorCodec cursors;
    private final PatientImpactTokenCodec impactTokens;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public PatientRegistryService(
            TenantAuthorizationOperations authorization,
            GovernedMutationExecutor governedMutations,
            PatientRegistryStore store,
            PatientScreenCursorCodec cursors,
            PatientImpactTokenCodec impactTokens,
            ObjectMapper objectMapper,
            Clock clock) {
        this.authorization = authorization;
        this.governedMutations = governedMutations;
        this.store = store;
        this.cursors = cursors;
        this.impactTokens = impactTokens;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public PatientRegistryScreen screen(ReadCommand command) {
        Objects.requireNonNull(command, "command");
        var spec = PatientRegistryScreenCatalogue.screen(command.screenId());
        var query = pagedQuery(command);
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
        var spec = PatientRegistryScreenCatalogue.mutation(command.screenId(), command.actionKey());
        var key = requireIdempotencyKey(command.idempotencyKey());
        var reason = spec.reasonRequired()
                ? requireReason(command.reason())
                : optionalReason(command.reason());
        if (spec.targetRequired() && command.targetId() == null) {
            throw invalid("targetId is required for this action.");
        }
        var revision = spec.ifMatchRequired()
                ? Long.valueOf(requireRevision(
                        command.ifMatch(), command.screenId(), command.targetId()))
                : optionalRevision(command.ifMatch(), command.screenId(), command.targetId());
        var fields = normalizeFields(command.fields());
        requireFields(spec, fields);
        var now = clock.instant();
        if (!IMPACT_ACTIONS.contains(command.actionKey())
                && command.impactToken() != null
                && !command.impactToken().isBlank()) {
            throw invalid("This action does not accept an impact preview token.");
        }
        if (IMPACT_ACTIONS.contains(command.actionKey())) {
            if (command.targetId() == null
                    || revision == null
                    || command.impactToken() == null
                    || command.impactToken().isBlank()) {
                throw invalid("A fresh impact preview is required for this action.");
            }
            PatientImpactTokenCodec.Decoded preview;
            try {
                preview = impactTokens.decode(
                        command.impactToken(), impactBinding(command, revision, reason, fields));
            } catch (IllegalArgumentException exception) {
                throw invalid(exception.getMessage());
            }
            var impactBoundFields = new LinkedHashMap<>(fields);
            impactBoundFields.put("_impactDigest", preview.impactDigest());
            impactBoundFields.put("_impactExpiresAt", preview.expiresAt().toString());
            fields = Map.copyOf(impactBoundFields);
        }
        var mutation = new PatientRegistryStore.MutationCommand(
                command.screenId(),
                command.actionKey(),
                command.targetId(),
                command.patientId(),
                command.registrationId(),
                revision,
                command.decision(),
                reason,
                fields,
                now);
        var idempotency = new IdempotencyCommand(
                spec.operation(),
                key,
                hash(
                        spec.operation(),
                        command.screenId(),
                        command.actionKey(),
                        command.targetId(),
                        command.patientId(),
                        command.registrationId(),
                        revision,
                        command.decision(),
                        reason,
                        canonicalFields(fields)),
                now.plus(24, ChronoUnit.HOURS));
        var immutableFields = fields;
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
                    var result = store.mutate(
                            context,
                            new PatientRegistryStore.MutationCommand(
                                    mutation.screenId(),
                                    mutation.actionKey(),
                                    mutation.targetId(),
                                    mutation.patientId(),
                                    mutation.registrationId(),
                                    mutation.expectedRevision(),
                                    mutation.decision(),
                                    mutation.reason(),
                                    immutableFields,
                                    mutation.now()));
                    var registrationId = "patient_registration".equals(result.subjectType())
                            ? result.subjectId()
                            : command.registrationId();
                    var response = project(
                            context,
                            PatientRegistryScreenCatalogue.screen(command.screenId()),
                            new PagedQuery(
                                    new PatientRegistryStore.ScreenQuery(
                                            command.screenId(),
                                            result.patientId(),
                                            registrationId,
                                            null,
                                            null,
                                            10_001),
                                    new PatientScreenCursorCodec.Binding(
                                            context.organizationId(),
                                            context.actorId(),
                                            command.screenId(),
                                            result.patientId(),
                                            registrationId,
                                            null,
                                            null,
                                            50),
                                    new PatientScreenCursorCodec.Position(now, 0, null),
                                    50));
                    return governed(result, reason, response);
                });
    }

    public ImpactPreviewResponse previewImpact(ImpactPreviewCommand command) {
        Objects.requireNonNull(command, "command");
        if (!IMPACT_ACTIONS.contains(command.actionKey())) {
            throw invalid("This action does not define an impact preview.");
        }
        var spec = PatientRegistryScreenCatalogue.mutation(
                command.screenId(), command.actionKey());
        if (command.targetId() == null) {
            throw invalid("targetId is required for an impact preview.");
        }
        var revision = requireRevision(command.ifMatch(), command.screenId(), command.targetId());
        var fields = normalizeFields(command.fields());
        requireFields(spec, fields);
        var reason = spec.reasonRequired()
                ? requireReason(command.reason())
                : optionalReason(command.reason());
        var now = clock.instant();
        return authorization.execute(
                authorization(
                        command.organizationId(),
                        command.actorId(),
                        command.correlationId(),
                        spec.operation(),
                        reason,
                        command.recentAuthenticationAt(),
                        command.mfaAuthenticatedAt()),
                context -> {
                    var analysis = store.previewImpact(
                            context,
                            new PatientRegistryStore.MutationCommand(
                                    command.screenId(),
                                    command.actionKey(),
                                    command.targetId(),
                                    command.patientId(),
                                    command.registrationId(),
                                    revision,
                                    command.decision(),
                                    reason,
                                    fields,
                                    now));
                    var expiresAt = now.plusSeconds(600);
                    var token = impactTokens.encode(
                            impactBinding(command, revision, reason, fields),
                            analysis.digest(),
                            expiresAt);
                    return new ImpactPreviewResponse(
                            command.screenId(),
                            command.actionKey(),
                            command.targetId(),
                            revision,
                            analysis.digest(),
                            token,
                            expiresAt,
                            analysis.blocked(),
                            analysis.items());
                });
    }

    private PatientRegistryScreen project(
            com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext context,
            PatientRegistryScreenCatalogue.ScreenSpec spec,
            PagedQuery page) {
        var projection = store.projection(context, page.query());
        var actions = PatientRegistryScreenCatalogue.projectedActions(
                spec, store.permissions(context));
        var permittedTargetActions = actions.stream()
                .filter(PatientRegistryScreen.Action::targetRequired)
                .map(PatientRegistryScreen.Action::key)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        var authorizedRows = projection.rows().stream()
                .map(row -> new PatientRegistryScreen.Row(
                        row.id(),
                        row.patientId(),
                        row.status(),
                        row.revision(),
                        row.etag(),
                        row.values(),
                        row.allowedActionKeys().stream()
                                .filter(permittedTargetActions::contains)
                                .toList()))
                .toList();
        var snapshotDigest = screenSnapshotDigest(authorizedRows, actions);
        if (page.position().snapshotDigest() != null
                && !MessageDigest.isEqual(
                        page.position().snapshotDigest().getBytes(StandardCharsets.UTF_8),
                        snapshotDigest.getBytes(StandardCharsets.UTF_8))) {
            throw new PatientRegistryException(
                    PatientRegistryException.Reason.CONFLICT,
                    "The patient result set changed while paging; restart from the first page.");
        }
        var boundedSize = Math.min(authorizedRows.size(), 10_000);
        var from = Math.min(page.position().offset(), boundedSize);
        var to = Math.min(from + page.pageSize(), boundedSize);
        var rows = List.copyOf(authorizedRows.subList(from, to));
        var nextCursor = boundedSize > to
                ? cursors.encode(
                        page.binding(),
                        new PatientScreenCursorCodec.Position(
                                page.position().asOf(), to, snapshotDigest))
                : null;
        return new PatientRegistryScreen(
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
                nextCursor,
                page.pageSize());
    }

    private GovernedMutation governed(
            PatientRegistryStore.MutationResult result,
            String reason,
            PatientRegistryScreen response) {
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
        var additionalEvidence = result.additionalAudits().stream()
                .map(additional -> GovernanceEvidence.auditOnly(new AuditRecord(
                        additional.eventName(),
                        1,
                        additional.subjectType(),
                        additional.subjectId(),
                        additional.reason() == null ? reason : additional.reason(),
                        json(additional.payload()))))
                .toList();
        return new GovernedMutation(
                new IdempotentResponse(result.statusCode(), JSON, json(response)),
                evidence,
                additionalEvidence);
    }

    private PagedQuery pagedQuery(ReadCommand command) {
        var pageSize = command.limit() == null ? 50 : command.limit();
        if (pageSize < 1 || pageSize > 100) {
            throw invalid("limit must be between 1 and 100.");
        }
        var search = optionalText(command.search(), 100, "q");
        var status = optionalText(command.status(), 120, "status");
        var binding = new PatientScreenCursorCodec.Binding(
                command.organizationId(),
                command.actorId(),
                command.screenId(),
                command.patientId(),
                command.registrationId(),
                search,
                status,
                pageSize);
        PatientScreenCursorCodec.Position position;
        if (command.cursor() == null || command.cursor().isBlank()) {
            position = new PatientScreenCursorCodec.Position(clock.instant(), 0, null);
        } else {
            try {
                position = cursors.decode(command.cursor(), binding);
            } catch (IllegalArgumentException exception) {
                throw invalid(exception.getMessage());
            }
        }
        return new PagedQuery(
                new PatientRegistryStore.ScreenQuery(
                        command.screenId(),
                        command.patientId(),
                        command.registrationId(),
                        search,
                        status,
                        10_001),
                binding,
                position,
                pageSize);
    }

    private static void requireFields(
            PatientRegistryScreenCatalogue.ActionSpec spec, Map<String, String> fields) {
        var supported = spec.fields().stream()
                .map(PatientRegistryScreen.Field::key)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        for (var key : fields.keySet()) {
            if (!supported.contains(key) && !key.startsWith("_")) {
                throw invalid("The action field '" + key + "' is not supported.");
            }
        }
        for (var field : spec.fields()) {
            if (field.required()
                    && (fields.get(field.key()) == null || fields.get(field.key()).isBlank())) {
                throw invalid(field.label() + " is required.");
            }
        }
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
            if (clean.length() > 2000) {
                throw invalid("Action field values must not exceed 2000 characters.");
            }
            normalized.put(key, clean);
        });
        return Map.copyOf(normalized);
    }

    private static long requireRevision(String ifMatch, String screenId, UUID targetId) {
        var revision = optionalRevision(ifMatch, screenId, targetId);
        if (revision == null) {
            throw new PatientRegistryException(
                    PatientRegistryException.Reason.PRECONDITION_REQUIRED,
                    "A strong If-Match revision is required.");
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
                || !matcher.group(2).equalsIgnoreCase(targetId.toString())) {
            throw invalid("If-Match does not identify the requested patient resource.");
        }
        try {
            return Long.parseLong(matcher.group(3));
        } catch (NumberFormatException exception) {
            throw invalid("If-Match revision is invalid.");
        }
    }

    private static String requireReason(String reason) {
        var normalized = optionalReason(reason);
        if (normalized == null || normalized.codePointCount(0, normalized.length()) < 10) {
            throw invalid("reason must contain 10 to 500 characters.");
        }
        return normalized;
    }

    private static String optionalReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return null;
        }
        var normalized = Normalizer.normalize(reason, Normalizer.Form.NFC).strip();
        var size = normalized.codePointCount(0, normalized.length());
        if (size < 1 || size > 500) {
            throw invalid("reason must contain 1 to 500 characters.");
        }
        return normalized;
    }

    private static String requireIdempotencyKey(String value) {
        if (value == null || !IDEMPOTENCY_KEY.matcher(value).matches()) {
            throw invalid("Idempotency-Key must contain 16 to 128 safe characters.");
        }
        return value;
    }

    private static String optionalText(String value, int maximum, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        var normalized = Normalizer.normalize(value, Normalizer.Form.NFC).strip();
        if (normalized.codePointCount(0, normalized.length()) > maximum) {
            throw invalid(field + " is too long.");
        }
        return normalized;
    }

    private PatientImpactTokenCodec.Binding impactBinding(
            ActionCommand command,
            long revision,
            String reason,
            Map<String, String> fields) {
        return new PatientImpactTokenCodec.Binding(
                command.organizationId(),
                command.actorId(),
                command.screenId(),
                command.actionKey(),
                command.targetId(),
                revision,
                hash(reason, command.patientId(), command.registrationId(), canonicalFields(fields)));
    }

    private PatientImpactTokenCodec.Binding impactBinding(
            ImpactPreviewCommand command,
            long revision,
            String reason,
            Map<String, String> fields) {
        return new PatientImpactTokenCodec.Binding(
                command.organizationId(),
                command.actorId(),
                command.screenId(),
                command.actionKey(),
                command.targetId(),
                revision,
                hash(reason, command.patientId(), command.registrationId(), canonicalFields(fields)));
    }

    private static String canonicalFields(Map<String, String> fields) {
        return fields.entrySet().stream()
                .filter(entry -> !entry.getKey().startsWith("_"))
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    private static String screenSnapshotDigest(
            List<PatientRegistryScreen.Row> rows, List<PatientRegistryScreen.Action> actions) {
        var canonical = new StringBuilder();
        rows.forEach(row -> canonical.append(row.id())
                .append('|')
                .append(row.revision())
                .append('|')
                .append(row.status())
                .append('|')
                .append(row.allowedActionKeys())
                .append('\n'));
        actions.forEach(action -> canonical.append(action.key()).append('\n'));
        return hash(canonical);
    }

    private static String hash(Object... values) {
        var canonical = new StringBuilder();
        for (var value : values) {
            canonical.append(value == null ? "-" : value).append('\n');
        }
        return hash(canonical);
    }

    private static String hash(CharSequence value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256")
                            .digest(value.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to calculate patient evidence digest.", exception);
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
            throw new IllegalStateException("Unable to serialize the patient response.", exception);
        }
    }

    private static PatientRegistryException invalid(String message) {
        return new PatientRegistryException(PatientRegistryException.Reason.INVALID, message);
    }

    public record ReadCommand(
            UUID organizationId,
            UUID actorId,
            String correlationId,
            String screenId,
            UUID patientId,
            UUID registrationId,
            String search,
            String status,
            Integer limit,
            String cursor,
            Instant recentAuthenticationAt,
            Instant mfaAuthenticatedAt) {}

    private record PagedQuery(
            PatientRegistryStore.ScreenQuery query,
            PatientScreenCursorCodec.Binding binding,
            PatientScreenCursorCodec.Position position,
            int pageSize) {}

    public record ActionCommand(
            UUID organizationId,
            UUID actorId,
            String correlationId,
            String screenId,
            String actionKey,
            UUID targetId,
            UUID patientId,
            UUID registrationId,
            String decision,
            String reason,
            Map<String, String> fields,
            String impactToken,
            String ifMatch,
            String idempotencyKey,
            Instant recentAuthenticationAt,
            Instant mfaAuthenticatedAt) {}

    public record ImpactPreviewCommand(
            UUID organizationId,
            UUID actorId,
            String correlationId,
            String screenId,
            String actionKey,
            UUID targetId,
            UUID patientId,
            UUID registrationId,
            String decision,
            String reason,
            Map<String, String> fields,
            String ifMatch,
            Instant recentAuthenticationAt,
            Instant mfaAuthenticatedAt) {}

    public record ImpactPreviewResponse(
            String screenId,
            String actionKey,
            UUID targetId,
            long revision,
            String digest,
            String token,
            Instant expiresAt,
            boolean blocked,
            List<PatientRegistryStore.ImpactItem> items) {
        public ImpactPreviewResponse {
            items = List.copyOf(items == null ? List.of() : items);
        }
    }
}
