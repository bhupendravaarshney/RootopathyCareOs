package com.rootopathy.careos.billing.application;

import com.rootopathy.careos.billing.domain.BillingScreen;
import com.rootopathy.careos.governance.application.GovernedMutationExecutor;
import com.rootopathy.careos.governance.domain.AuditRecord;
import com.rootopathy.careos.governance.domain.GovernanceEvidence;
import com.rootopathy.careos.governance.domain.GovernedMutation;
import com.rootopathy.careos.governance.domain.IdempotencyCommand;
import com.rootopathy.careos.governance.domain.IdempotencyOutcome;
import com.rootopathy.careos.governance.domain.IdempotentResponse;
import com.rootopathy.careos.governance.domain.OutboxRecord;
import com.rootopathy.careos.tenancy.application.TenantAuthorizationOperations;
import com.rootopathy.careos.tenancy.domain.AuthenticatedActorContext;
import com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext;
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
public final class BillingService {
    private static final String JSON = "application/json";
    private static final String DEFAULT_PURPOSE = "billing_operations";
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9._:-]{16,128}");
    private static final Pattern ETAG = Pattern.compile(
            "\\\"m11:(P11-(?:0[1-9]|1[01])):([0-9a-fA-F-]{36}):([0-9]{1,19})\\\"");
    private static final Pattern PROHIBITED_PAYMENT_FIELD = Pattern.compile(
            "(?i).*(?:card|pan|cvv|cvc|track|expiry|expiration|bearer|secret|providerToken).*");
    private static final Pattern CARD_NUMBER_CANDIDATE = Pattern.compile("(?:\\d[ -]?){13,19}");

    private final TenantAuthorizationOperations authorization;
    private final GovernedMutationExecutor governedMutations;
    private final BillingStore store;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public BillingService(
            TenantAuthorizationOperations authorization,
            GovernedMutationExecutor governedMutations,
            BillingStore store,
            ObjectMapper objectMapper,
            Clock clock) {
        this.authorization = authorization;
        this.governedMutations = governedMutations;
        this.store = store;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public BillingScreen screen(ReadCommand command) {
        Objects.requireNonNull(command, "command");
        var spec = BillingScreenCatalogue.screen(command.screenId());
        if (command.cursor() != null && !command.cursor().isBlank()) {
            throw invalid("The billing cursor is invalid or expired; restart from the first page.");
        }
        var limit = command.limit() == null ? 50 : command.limit();
        if (limit < 1 || limit > 100) throw invalid("limit must be between 1 and 100.");
        var query = new BillingStore.ScreenQuery(
                command.screenId(), command.patientId(), command.invoiceId(),
                optional(command.search(), 100, "q"), optional(command.status(), 80, "status"), limit);
        return authorization.execute(
                authorization(
                        command.organizationId(), command.actorId(), command.correlationId(),
                        DEFAULT_PURPOSE, spec.readOperation(), null,
                        command.recentAuthenticationAt(), command.mfaAuthenticatedAt()),
                context -> project(context, spec, query));
    }

    public IdempotencyOutcome act(ActionCommand command) {
        Objects.requireNonNull(command, "command");
        var spec = BillingScreenCatalogue.mutation(command.screenId(), command.actionKey());
        var key = requireIdempotencyKey(command.idempotencyKey());
        var reason = requireReason(command.reason());
        rejectLikelyCardNumber(reason);
        if (spec.targetRequired() && command.targetId() == null) {
            throw invalid("targetId is required for this action.");
        }
        var revision = spec.ifMatchRequired()
                ? Long.valueOf(requireRevision(command.ifMatch(), command.screenId(), command.targetId()))
                : optionalRevision(command.ifMatch(), command.screenId(), command.targetId());
        var fields = normalizeFields(command.fields());
        requireFields(spec, fields);
        var now = clock.instant();
        var mutation = new BillingStore.MutationCommand(
                command.screenId(), command.actionKey(), command.targetId(), revision, reason, fields,
                command.recentAuthenticationAt(), command.mfaAuthenticatedAt(), now,
                command.correlationId());
        var idempotency = new IdempotencyCommand(
                spec.operation(), key,
                hash(spec.operation(), command.screenId(), command.actionKey(), command.targetId(),
                        revision, reason, canonicalFields(fields)),
                now.plus(24, ChronoUnit.HOURS));
        return governedMutations.execute(
                authorization(
                        command.organizationId(), command.actorId(), command.correlationId(),
                        DEFAULT_PURPOSE, spec.operation(), reason,
                        command.recentAuthenticationAt(), command.mfaAuthenticatedAt()),
                idempotency,
                context -> {
                    var result = store.mutate(context, mutation);
                    var response = projectResult(context, command.screenId(), result);
                    return governed(result, reason, response);
                });
    }

    private BillingScreen projectResult(
            AuthorizedTenantContext context,
            String screenId,
            BillingStore.MutationResult result) {
        return project(
                context,
                BillingScreenCatalogue.screen(screenId),
                new BillingStore.ScreenQuery(screenId, null, result.invoiceId(), null, null, 50));
    }

    private BillingScreen project(
            AuthorizedTenantContext context,
            BillingScreenCatalogue.ScreenSpec spec,
            BillingStore.ScreenQuery query) {
        var projection = store.projection(context, query);
        var actions = BillingScreenCatalogue.projectedActions(spec, store.permissions(context));
        var permittedTargets = actions.stream()
                .filter(BillingScreen.Action::targetRequired)
                .map(BillingScreen.Action::key)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        var rows = projection.rows().stream()
                .map(row -> new BillingScreen.Row(
                        row.id(), row.patientId(), row.appointmentId(), row.encounterId(),
                        row.priceBookId(), row.packageId(), row.estimateId(), row.invoiceId(),
                        row.paymentIntentId(), row.paymentId(), row.refundId(), row.adjustmentId(),
                        row.claimId(), row.remittanceId(), row.reconciliationId(),
                        row.financialExportId(), row.status(), row.revision(), row.etag(),
                        row.values(), row.allowedActionKeys().stream()
                                .filter(permittedTargets::contains)
                                .toList()))
                .toList();
        return new BillingScreen(
                context.organizationId(), spec.id(), spec.title(), spec.purpose(),
                projection.generatedAt(), projection.metrics(), projection.columns(), rows,
                actions, projection.notices(), null, query.limit());
    }

    private GovernedMutation governed(
            BillingStore.MutationResult result, String reason, Object response) {
        var audit = new AuditRecord(
                result.auditEvent(), 1, result.subjectType(), result.subjectId(), reason,
                json(result.auditPayload()));
        var evidence = result.outboxEvent() == null
                ? GovernanceEvidence.auditOnly(audit)
                : new GovernanceEvidence(
                        audit,
                        new OutboxRecord(
                                result.outboxEvent(), 1, result.aggregateType(),
                                result.outboxAggregateId() == null
                                        ? result.subjectId()
                                        : result.outboxAggregateId(),
                                json(result.outboxPayload())));
        return new GovernedMutation(
                new IdempotentResponse(result.statusCode(), JSON, json(response)), evidence);
    }

    private static void requireFields(
            BillingScreenCatalogue.ActionSpec spec, Map<String, String> fields) {
        var supported = spec.fields().stream()
                .map(BillingScreen.Field::key)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        fields.keySet().forEach(key -> {
            if (!supported.contains(key)) {
                throw invalid("The action field '" + key + "' is not supported.");
            }
        });
        spec.fields().forEach(field -> {
            if (field.required()
                    && (fields.get(field.key()) == null || fields.get(field.key()).isBlank())) {
                throw invalid(field.label() + " is required.");
            }
        });
        if ((fields.containsKey("priceItemId") && !fields.get("priceItemId").isBlank())
                == (fields.containsKey("packageId") && !fields.get("packageId").isBlank())
                && spec.key().equals("create-estimate")) {
            throw invalid("Exactly one of priceItemId or packageId is required.");
        }
    }

    private static Map<String, String> normalizeFields(Map<String, String> supplied) {
        if (supplied == null || supplied.size() > 32) {
            throw invalid("fields must contain at most 32 values.");
        }
        var normalized = new LinkedHashMap<String, String>();
        supplied.forEach((key, value) -> {
            if (key == null || value == null || !key.matches("[A-Za-z][A-Za-z0-9_.-]{0,79}")) {
                throw invalid("Action field names and values must be non-null and well formed.");
            }
            if (PROHIBITED_PAYMENT_FIELD.matcher(key).matches()) {
                throw invalid("Raw card, provider-token, bearer-link and payment-secret fields are prohibited.");
            }
            var clean = Normalizer.normalize(value, Normalizer.Form.NFC).strip();
            if (clean.length() > 20000 || clean.codePoints().anyMatch(Character::isISOControl)) {
                throw invalid("Action field values are too long or contain unsafe characters.");
            }
            rejectLikelyCardNumber(clean);
            normalized.put(key, clean);
        });
        return Map.copyOf(normalized);
    }

    private static void rejectLikelyCardNumber(String value) {
        var matcher = CARD_NUMBER_CANDIDATE.matcher(value);
        while (matcher.find()) {
            var digits = matcher.group().replaceAll("[^0-9]", "");
            if (digits.length() < 13 || digits.length() > 19) continue;
            var sum = 0;
            for (var index = digits.length() - 1; index >= 0; index--) {
                var digit = digits.charAt(index) - '0';
                if ((digits.length() - 1 - index) % 2 == 1) {
                    digit *= 2;
                    if (digit > 9) digit -= 9;
                }
                sum += digit;
            }
            if (sum % 10 == 0) {
                throw invalid("Raw card, provider-token, bearer-link and payment-secret data are prohibited.");
            }
        }
    }

    private static long requireRevision(String value, String screenId, UUID targetId) {
        var revision = optionalRevision(value, screenId, targetId);
        if (revision == null) {
            throw new BillingException(
                    BillingException.Reason.PRECONDITION_REQUIRED,
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
            throw invalid("If-Match does not identify the requested billing record.");
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
        if (normalized.codePointCount(0, normalized.length()) > maximum
                || normalized.codePoints().anyMatch(Character::isISOControl)) {
            throw invalid(field + " is too long or contains unsafe characters.");
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
        var canonical = new StringBuilder("m11-billing-payments-v1|");
        for (var value : values) {
            var text = Objects.toString(value, "<null>");
            canonical.append(text.length()).append(':').append(text).append(';');
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to calculate the billing request digest.", exception);
        }
    }

    private TenantAuthorizationRequest authorization(
            UUID organizationId,
            UUID actorId,
            String correlationId,
            String purpose,
            String operation,
            String reason,
            Instant recentAuthenticationAt,
            Instant mfaAuthenticatedAt) {
        return new TenantAuthorizationRequest(
                organizationId,
                new AuthenticatedActorContext(actorId, purpose, correlationId),
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
            throw new IllegalStateException("Unable to serialize the billing response.", exception);
        }
    }

    private static BillingException invalid(String message) {
        return new BillingException(BillingException.Reason.INVALID, message);
    }

    public record ReadCommand(
            UUID organizationId,
            UUID actorId,
            String correlationId,
            String screenId,
            UUID patientId,
            UUID invoiceId,
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
            String reason,
            Map<String, String> fields,
            String ifMatch,
            String idempotencyKey,
            Instant recentAuthenticationAt,
            Instant mfaAuthenticatedAt) {}
}
