package com.rootopathy.careos.administration.application;

import com.rootopathy.careos.administration.application.OrganizationIdentifierStore.IdentifierDraft;
import com.rootopathy.careos.administration.domain.OrganizationIdentifier;
import com.rootopathy.careos.administration.domain.OrganizationIdentifierCollection;
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
import com.rootopathy.careos.tenancy.domain.OperationKey;
import com.rootopathy.careos.tenancy.domain.TenantAuthorizationRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public final class OrganizationIdentifierService {
    public static final String READ_OPERATION = "organization.identifier.read";
    public static final String MANAGE_OPERATION = "organization.identifier.manage";
    public static final String VERIFY_OPERATION = "organization.identifier.verify";
    private static final String PURPOSE = "organization-administration";
    private static final String JSON = "application/json";
    private static final Duration IDEMPOTENCY_TTL = Duration.ofHours(24);
    private static final Pattern REGISTRY_KEY =
            Pattern.compile("[a-z][a-z0-9]*([._:-][a-z0-9]+)*");
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9._:-]{16,128}");
    private static final Pattern IDENTIFIER_ETAG = Pattern.compile(
            "\"organization-identifier:([0-9a-fA-F-]{36}):([0-9]{1,19})\"");
    private static final Pattern COUNTRY_CODE = Pattern.compile("[A-Z]{2}");
    private static final Set<String> ISO_COUNTRY_CODES = Set.of(Locale.getISOCountries());

    private final TenantAuthorizationOperations authorization;
    private final GovernedMutationExecutor governedMutations;
    private final OrganizationIdentifierStore store;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public OrganizationIdentifierService(
            TenantAuthorizationOperations authorization,
            GovernedMutationExecutor governedMutations,
            OrganizationIdentifierStore store,
            ObjectMapper objectMapper,
            Clock clock) {
        this.authorization = authorization;
        this.governedMutations = governedMutations;
        this.store = store;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public OrganizationIdentifierCollection identifiers(ReadCommand command) {
        Objects.requireNonNull(command, "command");
        return authorization.execute(
                authorization(command, READ_OPERATION, null, null, null), store::identifiers);
    }

    public MutationOutcome create(CreateCommand command) {
        Objects.requireNonNull(command, "command");
        var draft = draft(command.draft());
        var reason = requiredReason(command.reason());
        var idempotencyKey = idempotencyKey(command.idempotencyKey());
        var requestHash = draftHash(command.organizationId(), draft, reason);
        var outcome = governedMutations.execute(
                authorization(command.readCommand(), MANAGE_OPERATION, reason, null, null),
                idempotency(MANAGE_OPERATION, idempotencyKey, requestHash),
                context -> mutation(
                        store.create(context, draft),
                        201,
                        "organization.identifier.created",
                        reason));
        return outcome(outcome);
    }

    public MutationOutcome update(UpdateCommand command) {
        Objects.requireNonNull(command, "command");
        var identifierId = Objects.requireNonNull(command.identifierId(), "identifierId");
        var draft = draft(command.draft());
        var reason = requiredReason(command.reason());
        var expectedLockVersion = expectedLockVersion(command.ifMatch(), identifierId);
        var idempotencyKey = idempotencyKey(command.idempotencyKey());
        var requestHash = requestHash(
                draftHash(command.organizationId(), draft, reason),
                identifierId.toString(),
                Long.toString(expectedLockVersion),
                "update");
        var outcome = governedMutations.execute(
                authorization(command.readCommand(), MANAGE_OPERATION, reason, null, null),
                idempotency(MANAGE_OPERATION, idempotencyKey, requestHash),
                context -> mutation(
                        store.update(context, identifierId, draft, expectedLockVersion),
                        200,
                        "organization.identifier.updated",
                        reason));
        return outcome(outcome);
    }

    public MutationOutcome verify(VerifyCommand command) {
        Objects.requireNonNull(command, "command");
        var identifierId = Objects.requireNonNull(command.identifierId(), "identifierId");
        var evidenceReference = requiredText(
                command.evidenceReference(), "evidenceReference", 1, 160);
        var reason = requiredReason(command.reason());
        var expectedLockVersion = expectedLockVersion(command.ifMatch(), identifierId);
        var idempotencyKey = idempotencyKey(command.idempotencyKey());
        var requestHash = requestHash(
                command.organizationId().toString(),
                identifierId.toString(),
                evidenceReference,
                reason,
                Long.toString(expectedLockVersion),
                "verify");
        var outcome = governedMutations.execute(
                authorization(
                        command.readCommand(),
                        VERIFY_OPERATION,
                        reason,
                        command.recentAuthenticationAt(),
                        command.mfaAuthenticatedAt()),
                idempotency(VERIFY_OPERATION, idempotencyKey, requestHash),
                context -> mutation(
                        store.verify(
                                context,
                                identifierId,
                                evidenceReference,
                                expectedLockVersion),
                        200,
                        "organization.identifier.verified",
                        reason));
        return outcome(outcome);
    }

    public MutationOutcome revoke(RevokeCommand command) {
        Objects.requireNonNull(command, "command");
        var identifierId = Objects.requireNonNull(command.identifierId(), "identifierId");
        var reason = requiredReason(command.reason());
        var expectedLockVersion = expectedLockVersion(command.ifMatch(), identifierId);
        var idempotencyKey = idempotencyKey(command.idempotencyKey());
        var requestHash = requestHash(
                command.organizationId().toString(),
                identifierId.toString(),
                reason,
                Long.toString(expectedLockVersion),
                "revoke");
        var outcome = governedMutations.execute(
                authorization(command.readCommand(), MANAGE_OPERATION, reason, null, null),
                idempotency(MANAGE_OPERATION, idempotencyKey, requestHash),
                context -> {
                    var result = store.revoke(context, identifierId, expectedLockVersion);
                    var identifier = result.identifier();
                    var payload = new LinkedHashMap<String, Object>();
                    payload.put("fromState", result.fromState());
                    payload.put("identifierId", identifier.identifierId());
                    payload.put("lockVersion", identifier.lockVersion());
                    payload.put("replacementId", null);
                    payload.put("toState", identifier.status());
                    return mutation(
                            identifier,
                            200,
                            "organization.identifier.revoked",
                            reason,
                            payload);
                });
        return outcome(outcome);
    }

    public MutationOutcome supersede(SupersedeCommand command) {
        Objects.requireNonNull(command, "command");
        var identifierId = Objects.requireNonNull(command.identifierId(), "identifierId");
        var replacementId = command.replacementId();
        if (replacementId == null) {
            throw invalidField(
                    "replacementId",
                    "m1.field.required",
                    "replacementId is required.");
        }
        if (identifierId.equals(replacementId)) {
            throw invalidField(
                    "replacementId",
                    "m1.lifecycle.invalid_transition",
                    "replacementId must identify a different verified identifier.");
        }
        var reason = requiredReason(command.reason());
        var expectedLockVersion = expectedLockVersion(command.ifMatch(), identifierId);
        var expectedReplacementLockVersion =
                expectedLockVersion(command.replacementEtag(), replacementId);
        var idempotencyKey = idempotencyKey(command.idempotencyKey());
        var requestHash = requestHash(
                command.organizationId().toString(),
                identifierId.toString(),
                Long.toString(expectedLockVersion),
                replacementId.toString(),
                Long.toString(expectedReplacementLockVersion),
                reason,
                "supersede");
        var outcome = governedMutations.execute(
                authorization(command.readCommand(), MANAGE_OPERATION, reason, null, null),
                idempotency(MANAGE_OPERATION, idempotencyKey, requestHash),
                context -> {
                    var result = store.supersede(
                            context,
                            identifierId,
                            expectedLockVersion,
                            replacementId,
                            expectedReplacementLockVersion);
                    var identifier = result.identifier();
                    var payload = new LinkedHashMap<String, Object>();
                    payload.put("fromState", result.fromState());
                    payload.put("identifierId", identifier.identifierId());
                    payload.put("lockVersion", identifier.lockVersion());
                    payload.put("replacementId", result.replacementId());
                    payload.put("toState", identifier.status());
                    return mutation(
                            identifier,
                            200,
                            "organization.identifier.superseded",
                            reason,
                            payload);
                });
        return outcome(outcome);
    }

    public static String entityTag(OrganizationIdentifier identifier) {
        return entityTag(identifier.identifierId(), identifier.lockVersion());
    }

    private GovernedMutation mutation(
            OrganizationIdentifier identifier,
            int status,
            String eventName,
            String reason) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("identifierId", identifier.identifierId());
        payload.put("lockVersion", identifier.lockVersion());
        payload.put("state", identifier.status());
        return mutation(identifier, status, eventName, reason, payload);
    }

    private GovernedMutation mutation(
            OrganizationIdentifier identifier,
            int status,
            String eventName,
            String reason,
            LinkedHashMap<String, Object> payload) {
        var payloadJson = json(payload);
        return new GovernedMutation(
                new IdempotentResponse(status, JSON, json(identifier)),
                new GovernanceEvidence(
                        new AuditRecord(
                                eventName,
                                1,
                                "organization_identifier",
                                identifier.identifierId(),
                                reason,
                                payloadJson),
                        new OutboxRecord(
                                eventName,
                                1,
                                "organization_identifier",
                                identifier.identifierId(),
                                payloadJson)));
    }

    private MutationOutcome outcome(IdempotencyOutcome outcome) {
        try {
            var node = objectMapper.readTree(outcome.response().bodyJson());
            var identifierId = node.get("identifierId");
            var lockVersion = node.get("lockVersion");
            if (identifierId == null
                    || !identifierId.isString()
                    || lockVersion == null
                    || !lockVersion.isIntegralNumber()
                    || !lockVersion.canConvertToLong()) {
                throw new IllegalStateException(
                        "Identifier mutation response omitted revision evidence");
            }
            var id = UUID.fromString(identifierId.stringValue());
            var revision = lockVersion.longValue();
            if (revision < 0) {
                throw new IllegalStateException(
                        "Identifier mutation response has an invalid revision");
            }
            return new MutationOutcome(outcome, entityTag(id, revision), id);
        } catch (OrganizationIdentifierException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Identifier mutation response could not be read", exception);
        }
    }

    private static String entityTag(UUID identifierId, long lockVersion) {
        return "\"organization-identifier:" + identifierId + ":" + lockVersion + "\"";
    }

    private TenantAuthorizationRequest authorization(
            ReadCommand command,
            String operation,
            String reason,
            Instant recentAuthenticationAt,
            Instant mfaAuthenticatedAt) {
        return new TenantAuthorizationRequest(
                Objects.requireNonNull(command.organizationId(), "organizationId"),
                new AuthenticatedActorContext(
                        Objects.requireNonNull(command.actorId(), "actorId"),
                        PURPOSE,
                        command.correlationId()),
                new OperationKey(operation),
                reason,
                recentAuthenticationAt,
                mfaAuthenticatedAt,
                null);
    }

    private IdempotencyCommand idempotency(
            String operation, String key, String requestHash) {
        return new IdempotencyCommand(
                operation, key, requestHash, clock.instant().plus(IDEMPOTENCY_TTL));
    }

    private IdentifierDraft draft(DraftCommand command) {
        Objects.requireNonNull(command, "draft");
        var identifierType = requiredRegistryKey(command.identifierType());
        var authority = requiredText(
                command.assigningAuthority(), "assigningAuthority", 2, 160);
        var value = requiredText(command.value(), "value", 1, 128);
        var jurisdiction = optionalCountryCode(command.jurisdictionCountryCode());
        if (command.isPrimary() == null) {
            throw invalidField("isPrimary", "m1.field.required", "isPrimary is required.");
        }
        var issueDate = optionalDate(command.issueDate(), "issueDate");
        var expiryDate = optionalDate(command.expiryDate(), "expiryDate");
        if (issueDate != null && expiryDate != null && expiryDate.isBefore(issueDate)) {
            throw invalidField(
                    "expiryDate",
                    "m1.field.format",
                    "expiryDate must not precede issueDate.");
        }
        var effectiveFrom = requiredInstant(command.effectiveFrom(), "effectiveFrom");
        var effectiveTo = optionalInstant(command.effectiveTo(), "effectiveTo");
        if (effectiveTo != null && !effectiveTo.isAfter(effectiveFrom)) {
            throw invalidField(
                    "effectiveTo",
                    "m1.field.format",
                    "effectiveTo must be after effectiveFrom.");
        }
        return new IdentifierDraft(
                identifierType,
                authority,
                value,
                jurisdiction,
                command.isPrimary(),
                issueDate,
                expiryDate,
                effectiveFrom,
                effectiveTo);
    }

    private static String draftHash(
            UUID organizationId, IdentifierDraft draft, String reason) {
        return requestHash(
                organizationId.toString(),
                draft.identifierType(),
                draft.assigningAuthority(),
                draft.value(),
                value(draft.jurisdictionCountryCode()),
                Boolean.toString(draft.isPrimary()),
                value(draft.issueDate()),
                value(draft.expiryDate()),
                draft.effectiveFrom().toString(),
                value(draft.effectiveTo()),
                reason);
    }

    private static String value(Object value) {
        return value == null ? "" : value.toString();
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Organization identifier JSON serialization failed", exception);
        }
    }

    private static String requiredRegistryKey(String value) {
        var normalized = normalize(value);
        if (normalized.isEmpty()) {
            throw invalidField(
                    "identifierType", "m1.field.required", "identifierType is required.");
        }
        if (normalized.length() > 80 || !REGISTRY_KEY.matcher(normalized).matches()) {
            throw invalidField(
                    "identifierType",
                    "m1.field.format",
                    "identifierType must be an approved registry key.");
        }
        return normalized;
    }

    private static String requiredText(
            String value, String field, int minimum, int maximum) {
        var normalized = normalize(value);
        if (normalized.isEmpty()) {
            throw invalidField(field, "m1.field.required", field + " is required.");
        }
        var length = normalized.codePointCount(0, normalized.length());
        if (length < minimum || length > maximum) {
            throw invalidField(
                    field,
                    "m1.field.length",
                    field + " must contain between " + minimum + " and " + maximum + " characters.");
        }
        if (normalized.codePoints().anyMatch(Character::isISOControl)) {
            throw invalidField(
                    field, "m1.field.format", field + " must not contain control characters.");
        }
        return normalized;
    }

    private static String requiredReason(String value) {
        return requiredText(value, "reason", 10, 500);
    }

    private static String optionalCountryCode(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        var normalized = normalize(value).toUpperCase(Locale.ROOT);
        if (!COUNTRY_CODE.matcher(normalized).matches()) {
            throw invalidField(
                    "jurisdictionCountryCode",
                    "m1.field.format",
                    "jurisdictionCountryCode must be a two-letter ISO country code.");
        }
        if (!ISO_COUNTRY_CODES.contains(normalized)) {
            throw invalidField(
                    "jurisdictionCountryCode",
                    "m1.field.enum",
                    "jurisdictionCountryCode is not an ISO 3166-1 alpha-2 code.");
        }
        return normalized;
    }

    private static LocalDate optionalDate(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.strip());
        } catch (DateTimeException exception) {
            throw invalidField(
                    field, "m1.field.format", field + " must be an ISO 8601 calendar date.");
        }
    }

    private static Instant requiredInstant(String value, String field) {
        if (value == null || value.isBlank()) {
            throw invalidField(field, "m1.field.required", field + " is required.");
        }
        return instant(value, field);
    }

    private static Instant optionalInstant(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return instant(value, field);
    }

    private static Instant instant(String value, String field) {
        try {
            return Instant.parse(value.strip());
        } catch (DateTimeException exception) {
            throw invalidField(
                    field, "m1.field.format", field + " must be an ISO 8601 UTC instant.");
        }
    }

    private static String idempotencyKey(String value) {
        if (value == null || !IDEMPOTENCY_KEY.matcher(value).matches()) {
            throw invalid("Idempotency-Key has an invalid format.");
        }
        return value;
    }

    private static long expectedLockVersion(String value, UUID identifierId) {
        if (value == null || value.isBlank()) {
            throw new OrganizationIdentifierException(
                    OrganizationIdentifierException.Reason.PRECONDITION_REQUIRED,
                    "A strong If-Match value from the latest identifier is required.");
        }
        var matcher = IDENTIFIER_ETAG.matcher(value);
        if (!matcher.matches()) {
            throw invalid("If-Match must be the strong organization identifier entity tag.");
        }
        try {
            if (!UUID.fromString(matcher.group(1)).equals(identifierId)) {
                throw invalid("If-Match belongs to a different organization identifier.");
            }
            return Long.parseLong(matcher.group(2));
        } catch (IllegalArgumentException exception) {
            throw invalid("If-Match contains invalid organization identifier revision evidence.");
        }
    }

    private static String requestHash(String... values) {
        try {
            var canonical = new StringBuilder();
            for (var value : values) {
                canonical.append(value.length()).append(':').append(value).append(';');
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value.strip(), Normalizer.Form.NFC);
    }

    private static OrganizationIdentifierException invalid(String message) {
        return new OrganizationIdentifierException(
                OrganizationIdentifierException.Reason.INVALID_REQUEST, message);
    }

    private static OrganizationIdentifierException invalidField(
            String field, String code, String message) {
        return new OrganizationIdentifierException(
                OrganizationIdentifierException.Reason.INVALID_REQUEST,
                message,
                field,
                code);
    }

    public record ReadCommand(UUID organizationId, UUID actorId, String correlationId) {}

    public record DraftCommand(
            String identifierType,
            String assigningAuthority,
            String value,
            String jurisdictionCountryCode,
            Boolean isPrimary,
            String issueDate,
            String expiryDate,
            String effectiveFrom,
            String effectiveTo) {}

    public record CreateCommand(
            UUID organizationId,
            UUID actorId,
            String correlationId,
            String idempotencyKey,
            DraftCommand draft,
            String reason) {
        ReadCommand readCommand() {
            return new ReadCommand(organizationId, actorId, correlationId);
        }
    }

    public record UpdateCommand(
            UUID organizationId,
            UUID actorId,
            String correlationId,
            UUID identifierId,
            String ifMatch,
            String idempotencyKey,
            DraftCommand draft,
            String reason) {
        ReadCommand readCommand() {
            return new ReadCommand(organizationId, actorId, correlationId);
        }
    }

    public record VerifyCommand(
            UUID organizationId,
            UUID actorId,
            String correlationId,
            UUID identifierId,
            String ifMatch,
            String idempotencyKey,
            String evidenceReference,
            String reason,
            Instant recentAuthenticationAt,
            Instant mfaAuthenticatedAt) {
        ReadCommand readCommand() {
            return new ReadCommand(organizationId, actorId, correlationId);
        }
    }

    public record RevokeCommand(
            UUID organizationId,
            UUID actorId,
            String correlationId,
            UUID identifierId,
            String ifMatch,
            String idempotencyKey,
            String reason) {
        ReadCommand readCommand() {
            return new ReadCommand(organizationId, actorId, correlationId);
        }
    }

    public record SupersedeCommand(
            UUID organizationId,
            UUID actorId,
            String correlationId,
            UUID identifierId,
            String ifMatch,
            String idempotencyKey,
            UUID replacementId,
            String replacementEtag,
            String reason) {
        ReadCommand readCommand() {
            return new ReadCommand(organizationId, actorId, correlationId);
        }
    }

    public record MutationOutcome(
            IdempotencyOutcome outcome, String entityTag, UUID identifierId) {}
}
