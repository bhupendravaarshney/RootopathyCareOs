package com.rootopathy.careos.administration.application;

import com.rootopathy.careos.administration.application.OrganizationContactStore.AddressDraft;
import com.rootopathy.careos.administration.application.OrganizationContactStore.ContactDraft;
import com.rootopathy.careos.administration.domain.OrganizationAddress;
import com.rootopathy.careos.administration.domain.OrganizationContact;
import com.rootopathy.careos.administration.domain.OrganizationContactCollection;
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
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public final class OrganizationContactService {
    public static final String READ_OPERATION = "organization.contact.read";
    public static final String MANAGE_OPERATION = "organization.contact.manage";
    private static final String PURPOSE = "organization-administration";
    private static final String JSON = "application/json";
    private static final Duration IDEMPOTENCY_TTL = Duration.ofHours(24);
    private static final Set<String> ADDRESS_TYPES =
            Set.of("registered", "postal", "service", "billing");
    private static final Set<String> ADDRESS_VALIDATION_STATUSES =
            Set.of("unvalidated", "validated");
    private static final Set<String> CONTACT_CHANNELS = Set.of("email", "phone", "web");
    private static final Pattern REGISTRY_KEY =
            Pattern.compile("[a-z][a-z0-9]*([._:-][a-z0-9]+)*");
    private static final Pattern EMAIL =
            Pattern.compile("[^\\s@]+@[^\\s@]+\\.[^\\s@]+");
    private static final Pattern PHONE = Pattern.compile("\\+[1-9][0-9]{1,14}");
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9._:-]{16,128}");
    private static final Pattern ADDRESS_ETAG = Pattern.compile(
            "\"organization-address:([0-9a-fA-F-]{36}):([0-9]{1,19})\"");
    private static final Pattern CONTACT_ETAG = Pattern.compile(
            "\"organization-contact:([0-9a-fA-F-]{36}):([0-9]{1,19})\"");
    private static final Set<String> ISO_COUNTRY_CODES = Set.of(Locale.getISOCountries());

    private final TenantAuthorizationOperations authorization;
    private final GovernedMutationExecutor governedMutations;
    private final OrganizationContactStore store;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public OrganizationContactService(
            TenantAuthorizationOperations authorization,
            GovernedMutationExecutor governedMutations,
            OrganizationContactStore store,
            ObjectMapper objectMapper,
            Clock clock) {
        this.authorization = authorization;
        this.governedMutations = governedMutations;
        this.store = store;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public OrganizationContactCollection directory(ReadCommand command) {
        Objects.requireNonNull(command, "command");
        return authorization.execute(
                authorization(command, READ_OPERATION, null), store::directory);
    }

    public MutationOutcome createAddress(AddressCreateCommand command) {
        Objects.requireNonNull(command, "command");
        var draft = addressDraft(command.draft());
        var reason = requiredReason(command.reason());
        var key = idempotencyKey(command.idempotencyKey());
        var hash = requestHash(
                command.organizationId().toString(), addressDraftHash(draft), reason, "create");
        var outcome = governedMutations.execute(
                authorization(command.readCommand(), MANAGE_OPERATION, reason),
                idempotency(key, hash),
                context -> addressMutation(
                        store.createAddress(context, draft), 201, "created", reason));
        return outcome(outcome, RecordKind.ADDRESS);
    }

    public MutationOutcome supersedeAddress(AddressSupersedeCommand command) {
        Objects.requireNonNull(command, "command");
        var addressId = Objects.requireNonNull(command.addressId(), "addressId");
        var revision = expectedLockVersion(command.ifMatch(), addressId, RecordKind.ADDRESS);
        var draft = addressDraft(command.replacement());
        var reason = requiredReason(command.reason());
        var key = idempotencyKey(command.idempotencyKey());
        var hash = requestHash(
                command.organizationId().toString(),
                addressId.toString(),
                Long.toString(revision),
                addressDraftHash(draft),
                reason,
                "supersede");
        var outcome = governedMutations.execute(
                authorization(command.readCommand(), MANAGE_OPERATION, reason),
                idempotency(key, hash),
                context -> addressMutation(
                        store.supersedeAddress(context, addressId, revision, draft).replacement(),
                        201,
                        "superseded",
                        reason));
        return outcome(outcome, RecordKind.ADDRESS);
    }

    public MutationOutcome endAddress(AddressEndCommand command) {
        Objects.requireNonNull(command, "command");
        var addressId = Objects.requireNonNull(command.addressId(), "addressId");
        var revision = expectedLockVersion(command.ifMatch(), addressId, RecordKind.ADDRESS);
        var reason = requiredReason(command.reason());
        var key = idempotencyKey(command.idempotencyKey());
        var hash = requestHash(
                command.organizationId().toString(),
                addressId.toString(),
                Long.toString(revision),
                reason,
                "end");
        var outcome = governedMutations.execute(
                authorization(command.readCommand(), MANAGE_OPERATION, reason),
                idempotency(key, hash),
                context -> addressMutation(
                        store.endAddress(context, addressId, revision), 200, "ended", reason));
        return outcome(outcome, RecordKind.ADDRESS);
    }

    public MutationOutcome createContact(ContactCreateCommand command) {
        Objects.requireNonNull(command, "command");
        var draft = contactDraft(command.draft());
        var reason = requiredReason(command.reason());
        var key = idempotencyKey(command.idempotencyKey());
        var hash = requestHash(
                command.organizationId().toString(), contactDraftHash(draft), reason, "create");
        var outcome = governedMutations.execute(
                authorization(command.readCommand(), MANAGE_OPERATION, reason),
                idempotency(key, hash),
                context -> contactMutation(
                        store.createContact(context, draft), 201, "created", reason));
        return outcome(outcome, RecordKind.CONTACT);
    }

    public MutationOutcome verifyContact(ContactVerifyCommand command) {
        Objects.requireNonNull(command, "command");
        var contactId = Objects.requireNonNull(command.contactId(), "contactId");
        var revision = expectedLockVersion(command.ifMatch(), contactId, RecordKind.CONTACT);
        var reason = requiredReason(command.reason());
        var key = idempotencyKey(command.idempotencyKey());
        var hash = requestHash(
                command.organizationId().toString(),
                contactId.toString(),
                Long.toString(revision),
                reason,
                "verify");
        var outcome = governedMutations.execute(
                authorization(command.readCommand(), MANAGE_OPERATION, reason),
                idempotency(key, hash),
                context -> contactMutation(
                        store.verifyContact(context, contactId, revision),
                        200,
                        "verified",
                        reason));
        return outcome(outcome, RecordKind.CONTACT);
    }

    public MutationOutcome supersedeContact(ContactSupersedeCommand command) {
        Objects.requireNonNull(command, "command");
        var contactId = Objects.requireNonNull(command.contactId(), "contactId");
        var revision = expectedLockVersion(command.ifMatch(), contactId, RecordKind.CONTACT);
        var draft = contactDraft(command.replacement());
        var reason = requiredReason(command.reason());
        var key = idempotencyKey(command.idempotencyKey());
        var hash = requestHash(
                command.organizationId().toString(),
                contactId.toString(),
                Long.toString(revision),
                contactDraftHash(draft),
                reason,
                "supersede");
        var outcome = governedMutations.execute(
                authorization(command.readCommand(), MANAGE_OPERATION, reason),
                idempotency(key, hash),
                context -> contactMutation(
                        store.supersedeContact(context, contactId, revision, draft).replacement(),
                        201,
                        "superseded",
                        reason));
        return outcome(outcome, RecordKind.CONTACT);
    }

    public MutationOutcome endContact(ContactEndCommand command) {
        Objects.requireNonNull(command, "command");
        var contactId = Objects.requireNonNull(command.contactId(), "contactId");
        var revision = expectedLockVersion(command.ifMatch(), contactId, RecordKind.CONTACT);
        var reason = requiredReason(command.reason());
        var key = idempotencyKey(command.idempotencyKey());
        var hash = requestHash(
                command.organizationId().toString(),
                contactId.toString(),
                Long.toString(revision),
                reason,
                "end");
        var outcome = governedMutations.execute(
                authorization(command.readCommand(), MANAGE_OPERATION, reason),
                idempotency(key, hash),
                context -> contactMutation(
                        store.endContact(context, contactId, revision), 200, "ended", reason));
        return outcome(outcome, RecordKind.CONTACT);
    }

    public static String entityTag(OrganizationAddress address) {
        return entityTag(address.addressId(), address.lockVersion(), RecordKind.ADDRESS);
    }

    public static String entityTag(OrganizationContact contact) {
        return entityTag(contact.contactId(), contact.lockVersion(), RecordKind.CONTACT);
    }

    private GovernedMutation addressMutation(
            OrganizationAddress address, int status, String changeType, String reason) {
        return mutation(
                address,
                status,
                "organization.address.changed",
                "organization_address",
                address.addressId(),
                address.effectiveFrom(),
                address.lockVersion(),
                changeType,
                reason);
    }

    private GovernedMutation contactMutation(
            OrganizationContact contact, int status, String changeType, String reason) {
        return mutation(
                contact,
                status,
                "organization.contact.changed",
                "organization_contact",
                contact.contactId(),
                contact.effectiveFrom(),
                contact.lockVersion(),
                changeType,
                reason);
    }

    private GovernedMutation mutation(
            Object response,
            int status,
            String eventName,
            String subjectType,
            UUID recordId,
            Instant effectiveFrom,
            long lockVersion,
            String changeType,
            String reason) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("recordId", recordId);
        payload.put("changeType", changeType);
        payload.put("effectiveFrom", effectiveFrom);
        payload.put("lockVersion", lockVersion);
        var payloadJson = json(payload);
        return new GovernedMutation(
                new IdempotentResponse(status, JSON, json(response)),
                new GovernanceEvidence(
                        new AuditRecord(eventName, 1, subjectType, recordId, reason, payloadJson),
                        new OutboxRecord(eventName, 1, subjectType, recordId, payloadJson)));
    }

    private MutationOutcome outcome(IdempotencyOutcome outcome, RecordKind kind) {
        try {
            var node = objectMapper.readTree(outcome.response().bodyJson());
            var id = node.get(kind.idField);
            var lockVersion = node.get("lockVersion");
            if (id == null
                    || !id.isString()
                    || lockVersion == null
                    || !lockVersion.isIntegralNumber()
                    || !lockVersion.canConvertToLong()) {
                throw new IllegalStateException(
                        "Organization contact mutation response omitted revision evidence");
            }
            var recordId = UUID.fromString(id.stringValue());
            var revision = lockVersion.longValue();
            if (revision < 0) {
                throw new IllegalStateException(
                        "Organization contact mutation response has an invalid revision");
            }
            return new MutationOutcome(
                    outcome, entityTag(recordId, revision, kind), recordId, kind);
        } catch (OrganizationContactException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Organization contact mutation response could not be read", exception);
        }
    }

    private static String entityTag(UUID recordId, long lockVersion, RecordKind kind) {
        return "\"" + kind.etagPrefix + ":" + recordId + ":" + lockVersion + "\"";
    }

    private TenantAuthorizationRequest authorization(
            ReadCommand command, String operation, String reason) {
        return new TenantAuthorizationRequest(
                Objects.requireNonNull(command.organizationId(), "organizationId"),
                new AuthenticatedActorContext(
                        Objects.requireNonNull(command.actorId(), "actorId"),
                        PURPOSE,
                        command.correlationId()),
                new OperationKey(operation),
                reason,
                null,
                null,
                null);
    }

    private IdempotencyCommand idempotency(String key, String requestHash) {
        return new IdempotencyCommand(
                MANAGE_OPERATION, key, requestHash, clock.instant().plus(IDEMPOTENCY_TTL));
    }

    private static AddressDraft addressDraft(AddressDraftCommand command) {
        Objects.requireNonNull(command, "draft");
        var type = requiredMember(command.addressType(), ADDRESS_TYPES, "addressType");
        if (command.addressLines() == null
                || command.addressLines().isEmpty()
                || command.addressLines().size() > 4) {
            throw invalidField(
                    "addressLines",
                    "m1.field.length",
                    "addressLines must contain between one and four lines.");
        }
        var lines = command.addressLines().stream()
                .map(line -> requiredSafeText(line, "addressLines", 1, 120))
                .toList();
        var locality = requiredSafeText(command.locality(), "locality", 1, 100);
        var region = requiredSafeText(command.region(), "region", 1, 100);
        var postcode = requiredSafeText(command.postcode(), "postcode", 1, 24);
        var country = requiredCountryCode(command.countryCode(), "countryCode");
        var validationStatus = requiredMember(
                command.validationStatus(),
                ADDRESS_VALIDATION_STATUSES,
                "validationStatus");
        String validationSource = null;
        if ("validated".equals(validationStatus)) {
            validationSource = requiredSafeText(
                    command.validationSource(), "validationSource", 2, 160);
        } else if (command.validationSource() != null && !command.validationSource().isBlank()) {
            throw invalidField(
                    "validationSource",
                    "m1.field.forbidden",
                    "validationSource is only accepted for a validated address.");
        }
        if (command.isPrimary() == null) {
            throw invalidField("isPrimary", "m1.field.required", "isPrimary is required.");
        }
        var effectiveFrom = requiredInstant(command.effectiveFrom(), "effectiveFrom");
        var effectiveTo = optionalInstant(command.effectiveTo(), "effectiveTo");
        requireRange(effectiveFrom, effectiveTo);
        return new AddressDraft(
                type,
                lines,
                locality,
                region,
                postcode,
                country,
                validationStatus,
                validationSource,
                command.isPrimary(),
                effectiveFrom,
                effectiveTo);
    }

    private static ContactDraft contactDraft(ContactDraftCommand command) {
        Objects.requireNonNull(command, "draft");
        var channel = requiredMember(command.channel(), CONTACT_CHANNELS, "channel");
        var purpose = requiredRegistryKey(command.purpose(), "purpose");
        var value = switch (channel) {
            case "email" -> normalizedEmail(command.value());
            case "phone" -> normalizedPhone(command.value());
            case "web" -> normalizedWeb(command.value());
            default -> throw new IllegalStateException("Unexpected approved contact channel");
        };
        if (command.isPrimary() == null) {
            throw invalidField("isPrimary", "m1.field.required", "isPrimary is required.");
        }
        if (command.isPreferred() == null) {
            throw invalidField("isPreferred", "m1.field.required", "isPreferred is required.");
        }
        if (command.isPreferred() && !command.isPrimary()) {
            throw invalidField(
                    "isPreferred",
                    "m1.field.format",
                    "A preferred contact must also be primary.");
        }
        var effectiveFrom = requiredInstant(command.effectiveFrom(), "effectiveFrom");
        var effectiveTo = optionalInstant(command.effectiveTo(), "effectiveTo");
        requireRange(effectiveFrom, effectiveTo);
        return new ContactDraft(
                channel,
                purpose,
                value,
                command.isPrimary(),
                command.isPreferred(),
                effectiveFrom,
                effectiveTo);
    }

    private static String normalizedEmail(String value) {
        var normalized = requiredText(value, "value", 3, 254);
        var separator = normalized.lastIndexOf('@');
        if (!EMAIL.matcher(normalized).matches() || separator <= 0) {
            throw invalidField(
                    "value", "m1.field.format", "value must be a valid email address.");
        }
        return normalized.substring(0, separator + 1)
                + normalized.substring(separator + 1).toLowerCase(Locale.ROOT);
    }

    private static String normalizedPhone(String value) {
        var normalized = normalize(value).replaceAll("[\\s().-]", "");
        if (!PHONE.matcher(normalized).matches() || normalized.length() > 32) {
            throw invalidField(
                    "value", "m1.field.format", "value must be an E.164 phone number.");
        }
        return normalized;
    }

    private static String normalizedWeb(String value) {
        var normalized = requiredText(value, "value", 9, 2048);
        try {
            var parsed = new URI(normalized);
            if (!"https".equalsIgnoreCase(parsed.getScheme())
                    || parsed.getHost() == null
                    || parsed.getUserInfo() != null) {
                throw invalidField(
                        "value", "m1.field.format", "value must be an HTTPS URL.");
            }
            var canonical = new URI(
                            "https",
                            null,
                            parsed.getHost().toLowerCase(Locale.ROOT),
                            parsed.getPort(),
                            parsed.getPath(),
                            parsed.getQuery(),
                            parsed.getFragment())
                    .toASCIIString();
            if (canonical.length() > 2048) {
                throw invalidField(
                        "value", "m1.field.length", "value must not exceed 2048 characters.");
            }
            return canonical;
        } catch (URISyntaxException exception) {
            throw invalidField(
                    "value", "m1.field.format", "value must be an HTTPS URL.");
        }
    }

    private static String addressDraftHash(AddressDraft draft) {
        return requestHash(
                draft.addressType(),
                String.join("\u001f", draft.addressLines()),
                draft.locality(),
                draft.region(),
                draft.postcode(),
                draft.countryCode(),
                draft.validationStatus(),
                value(draft.validationSource()),
                Boolean.toString(draft.isPrimary()),
                draft.effectiveFrom().toString(),
                value(draft.effectiveTo()));
    }

    private static String contactDraftHash(ContactDraft draft) {
        return requestHash(
                draft.channel(),
                draft.purpose(),
                draft.value(),
                Boolean.toString(draft.isPrimary()),
                Boolean.toString(draft.isPreferred()),
                draft.effectiveFrom().toString(),
                value(draft.effectiveTo()));
    }

    private static String requiredMember(String value, Set<String> allowed, String field) {
        var normalized = requiredText(value, field, 1, 24).toLowerCase(Locale.ROOT);
        if (!allowed.contains(normalized)) {
            throw invalidField(field, "m1.field.enum", field + " is not an approved value.");
        }
        return normalized;
    }

    private static String requiredRegistryKey(String value, String field) {
        var normalized = normalize(value);
        if (normalized.isEmpty()) {
            throw invalidField(field, "m1.field.required", field + " is required.");
        }
        if (normalized.length() > 80 || !REGISTRY_KEY.matcher(normalized).matches()) {
            throw invalidField(
                    field, "m1.field.format", field + " must be an approved registry key.");
        }
        return normalized;
    }

    private static String requiredSafeText(
            String value, String field, int minimum, int maximum) {
        var normalized = requiredText(value, field, minimum, maximum);
        if (normalized.indexOf('<') >= 0 || normalized.indexOf('>') >= 0) {
            throw invalidField(
                    field, "m1.field.format", field + " must not contain markup delimiters.");
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

    private static String requiredCountryCode(String value, String field) {
        var normalized = normalize(value).toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z]{2}")) {
            throw invalidField(
                    field, "m1.field.format", field + " must be a two-letter ISO country code.");
        }
        if (!ISO_COUNTRY_CODES.contains(normalized)) {
            throw invalidField(
                    field, "m1.field.enum", field + " is not an ISO 3166-1 alpha-2 code.");
        }
        return normalized;
    }

    private static String requiredReason(String value) {
        return requiredText(value, "reason", 10, 500);
    }

    private static Instant requiredInstant(String value, String field) {
        if (value == null || value.isBlank()) {
            throw invalidField(field, "m1.field.required", field + " is required.");
        }
        return instant(value, field);
    }

    private static Instant optionalInstant(String value, String field) {
        return value == null || value.isBlank() ? null : instant(value, field);
    }

    private static Instant instant(String value, String field) {
        try {
            return Instant.parse(value.strip());
        } catch (DateTimeException exception) {
            throw invalidField(
                    field, "m1.field.format", field + " must be an ISO 8601 UTC instant.");
        }
    }

    private static void requireRange(Instant effectiveFrom, Instant effectiveTo) {
        if (effectiveTo != null && !effectiveTo.isAfter(effectiveFrom)) {
            throw invalidField(
                    "effectiveTo", "m1.field.format", "effectiveTo must be after effectiveFrom.");
        }
    }

    private static String idempotencyKey(String value) {
        if (value == null || !IDEMPOTENCY_KEY.matcher(value).matches()) {
            throw invalid("Idempotency-Key has an invalid format.");
        }
        return value;
    }

    private static long expectedLockVersion(
            String value, UUID recordId, RecordKind kind) {
        if (value == null || value.isBlank()) {
            throw new OrganizationContactException(
                    OrganizationContactException.Reason.PRECONDITION_REQUIRED,
                    "A strong If-Match value from the latest record is required.");
        }
        var matcher = kind.etagPattern.matcher(value);
        if (!matcher.matches()) {
            throw invalid("If-Match must be the strong " + kind.label + " entity tag.");
        }
        try {
            if (!UUID.fromString(matcher.group(1)).equals(recordId)) {
                throw invalid("If-Match belongs to a different " + kind.label + ".");
            }
            return Long.parseLong(matcher.group(2));
        } catch (IllegalArgumentException exception) {
            throw invalid("If-Match contains invalid record revision evidence.");
        }
    }

    private static String requestHash(String... values) {
        try {
            var canonical = new StringBuilder();
            for (var item : values) {
                canonical.append(item.length()).append(':').append(item).append(';');
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Organization address/contact JSON serialization failed", exception);
        }
    }

    private static String value(Object value) {
        return value == null ? "" : value.toString();
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value.strip(), Normalizer.Form.NFC);
    }

    private static OrganizationContactException invalid(String message) {
        return new OrganizationContactException(
                OrganizationContactException.Reason.INVALID_REQUEST, message);
    }

    private static OrganizationContactException invalidField(
            String field, String code, String message) {
        return new OrganizationContactException(
                OrganizationContactException.Reason.INVALID_REQUEST, message, field, code);
    }

    public record ReadCommand(UUID organizationId, UUID actorId, String correlationId) {}

    public record AddressDraftCommand(
            String addressType,
            List<String> addressLines,
            String locality,
            String region,
            String postcode,
            String countryCode,
            String validationStatus,
            String validationSource,
            Boolean isPrimary,
            String effectiveFrom,
            String effectiveTo) {}

    public record ContactDraftCommand(
            String channel,
            String purpose,
            String value,
            Boolean isPrimary,
            Boolean isPreferred,
            String effectiveFrom,
            String effectiveTo) {}

    public record AddressCreateCommand(
            UUID organizationId,
            UUID actorId,
            String correlationId,
            String idempotencyKey,
            AddressDraftCommand draft,
            String reason) {
        ReadCommand readCommand() {
            return new ReadCommand(organizationId, actorId, correlationId);
        }
    }

    public record AddressSupersedeCommand(
            UUID organizationId,
            UUID actorId,
            String correlationId,
            UUID addressId,
            String ifMatch,
            String idempotencyKey,
            AddressDraftCommand replacement,
            String reason) {
        ReadCommand readCommand() {
            return new ReadCommand(organizationId, actorId, correlationId);
        }
    }

    public record AddressEndCommand(
            UUID organizationId,
            UUID actorId,
            String correlationId,
            UUID addressId,
            String ifMatch,
            String idempotencyKey,
            String reason) {
        ReadCommand readCommand() {
            return new ReadCommand(organizationId, actorId, correlationId);
        }
    }

    public record ContactCreateCommand(
            UUID organizationId,
            UUID actorId,
            String correlationId,
            String idempotencyKey,
            ContactDraftCommand draft,
            String reason) {
        ReadCommand readCommand() {
            return new ReadCommand(organizationId, actorId, correlationId);
        }
    }

    public record ContactVerifyCommand(
            UUID organizationId,
            UUID actorId,
            String correlationId,
            UUID contactId,
            String ifMatch,
            String idempotencyKey,
            String reason) {
        ReadCommand readCommand() {
            return new ReadCommand(organizationId, actorId, correlationId);
        }
    }

    public record ContactSupersedeCommand(
            UUID organizationId,
            UUID actorId,
            String correlationId,
            UUID contactId,
            String ifMatch,
            String idempotencyKey,
            ContactDraftCommand replacement,
            String reason) {
        ReadCommand readCommand() {
            return new ReadCommand(organizationId, actorId, correlationId);
        }
    }

    public record ContactEndCommand(
            UUID organizationId,
            UUID actorId,
            String correlationId,
            UUID contactId,
            String ifMatch,
            String idempotencyKey,
            String reason) {
        ReadCommand readCommand() {
            return new ReadCommand(organizationId, actorId, correlationId);
        }
    }

    public record MutationOutcome(
            IdempotencyOutcome outcome, String entityTag, UUID recordId, RecordKind kind) {}

    public enum RecordKind {
        ADDRESS("addressId", "organization-address", "organization address", ADDRESS_ETAG),
        CONTACT("contactId", "organization-contact", "organization contact", CONTACT_ETAG);

        private final String idField;
        private final String etagPrefix;
        private final String label;
        private final Pattern etagPattern;

        RecordKind(String idField, String etagPrefix, String label, Pattern etagPattern) {
            this.idField = idField;
            this.etagPrefix = etagPrefix;
            this.label = label;
            this.etagPattern = etagPattern;
        }
    }
}
