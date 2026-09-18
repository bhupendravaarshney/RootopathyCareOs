package com.rootopathy.careos.administration.application;

import com.rootopathy.careos.administration.application.OrganizationAdministrationStore.ProfileUpdate;
import com.rootopathy.careos.administration.application.OrganizationMembershipCursorCodec.CursorBinding;
import com.rootopathy.careos.administration.application.OrganizationMembershipCursorCodec.CursorPosition;
import com.rootopathy.careos.administration.domain.AdministrationReadiness;
import com.rootopathy.careos.administration.domain.OrganizationMembershipPage;
import com.rootopathy.careos.administration.domain.OrganizationMembershipPage.CursorPage;
import com.rootopathy.careos.administration.domain.OrganizationProfile;
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
import java.text.Normalizer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.IllformedLocaleException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public final class OrganizationAdministrationService {
    public static final String PROFILE_READ_OPERATION = "organization.profile.read";
    public static final String READINESS_READ_OPERATION = "organization.readiness.read";
    public static final String PROFILE_UPDATE_OPERATION = "organization.profile.update";
    public static final String MEMBERSHIP_READ_OPERATION = "access.membership.read";
    private static final String PURPOSE = "organization-administration";
    private static final String JSON = "application/json";
    private static final Duration IDEMPOTENCY_TTL = Duration.ofHours(24);
    private static final Pattern COUNTRY_CODE = Pattern.compile("[A-Z]{2}");
    private static final Set<String> ISO_COUNTRY_CODES = Set.of(Locale.getISOCountries());
    private static final Set<String> ORGANIZATION_TYPES =
            Set.of("care_provider", "care_network", "administrative");
    private static final Pattern IDEMPOTENCY_KEY =
            Pattern.compile("[A-Za-z0-9._:-]{16,128}");
    private static final Pattern PROFILE_ETAG =
            Pattern.compile("\"organization-profile:([0-9]{1,19})\"");
    private static final Pattern MEMBERSHIP_ROLE_KEY =
            Pattern.compile("[a-z][a-z0-9]*([._:-][a-z0-9]+)*");
    private static final Pattern MEMBERSHIP_CURSOR = Pattern.compile("[A-Za-z0-9_-]{1,512}");
    private static final Set<String> MEMBERSHIP_ACCESS_STATES =
            Set.of("active", "scheduled", "suspended", "expired", "revoked");

    private final TenantAuthorizationOperations authorization;
    private final GovernedMutationExecutor governedMutations;
    private final OrganizationAdministrationStore store;
    private final OrganizationMembershipCursorCodec membershipCursorCodec;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public OrganizationAdministrationService(
            TenantAuthorizationOperations authorization,
            GovernedMutationExecutor governedMutations,
            OrganizationAdministrationStore store,
            OrganizationMembershipCursorCodec membershipCursorCodec,
            ObjectMapper objectMapper,
            Clock clock) {
        this.authorization = authorization;
        this.governedMutations = governedMutations;
        this.store = store;
        this.membershipCursorCodec = membershipCursorCodec;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public OrganizationProfile profile(ReadCommand command) {
        Objects.requireNonNull(command, "command");
        return authorization.execute(
                authorization(command, PROFILE_READ_OPERATION, null), store::profile);
    }

    public AdministrationReadiness readiness(ReadCommand command) {
        Objects.requireNonNull(command, "command");
        return authorization.execute(
                authorization(command, READINESS_READ_OPERATION, null), store::readiness);
    }

    public OrganizationMembershipPage memberships(MembershipListCommand command) {
        Objects.requireNonNull(command, "command");
        var search = membershipSearch(command.search());
        var accessState = membershipAccessState(command.accessState());
        var roleKey = membershipRoleKey(command.roleKey());
        var limit = membershipLimit(command.limit());
        var cursor = membershipCursor(command.cursor());
        var binding = new CursorBinding(
                Objects.requireNonNull(command.organizationId(), "organizationId"),
                requestHash(
                        search == null ? "" : search,
                        accessState == null ? "" : accessState,
                        roleKey == null ? "" : roleKey),
                limit);

        return authorization.execute(
                authorization(command.readCommand(), MEMBERSHIP_READ_OPERATION, null),
                context -> {
                    CursorPosition after =
                            cursor == null ? null : membershipCursorCodec.decode(cursor, binding);
                    var asOf = after == null ? clock.instant() : after.asOf();
                    var slice = store.memberships(
                            context,
                            new OrganizationAdministrationStore.MembershipQuery(
                                    asOf, search, accessState, roleKey, limit, after));
                    String nextCursor = null;
                    if (slice.hasMore()) {
                        if (slice.items().isEmpty()) {
                            throw new IllegalStateException(
                                    "Membership store returned an empty continuing page");
                        }
                        var last = slice.items().getLast();
                        nextCursor = membershipCursorCodec.encode(
                                binding,
                                new CursorPosition(
                                        asOf, last.effectiveFrom(), last.membershipId()));
                    }
                    return new OrganizationMembershipPage(
                            context.organizationId(),
                            asOf,
                            slice.items(),
                            new CursorPage(limit, slice.hasMore(), nextCursor),
                            slice.availableActions());
                });
    }

    public ProfileMutationOutcome updateProfile(UpdateCommand command) {
        Objects.requireNonNull(command, "command");
        var legalName = requiredName(command.legalName(), "legalName", 2, 200);
        var displayName = requiredName(command.displayName(), "displayName", 2, 120);
        var tradingName = optionalName(command.tradingName(), "tradingName", 2, 160);
        var organizationType = organizationType(command.organizationType());
        var countryCode = countryCode(command.countryCode());
        var timezone = timezone(command.timezone());
        var locale = locale(command.locale());
        var reason = requiredReason(command.reason());
        var expectedLockVersion = expectedLockVersion(command.ifMatch());
        var idempotencyKey = idempotencyKey(command.idempotencyKey());
        var requestHash = requestHash(
                command.organizationId().toString(),
                legalName,
                displayName,
                tradingName == null ? "" : tradingName,
                organizationType,
                countryCode,
                timezone,
                locale,
                reason,
                Long.toString(expectedLockVersion));

        var outcome = governedMutations.execute(
                authorization(command.readCommand(), PROFILE_UPDATE_OPERATION, reason),
                new IdempotencyCommand(
                        PROFILE_UPDATE_OPERATION,
                        idempotencyKey,
                        requestHash,
                        clock.instant().plus(IDEMPOTENCY_TTL)),
                context -> {
                    var result = store.updateProfile(
                            context,
                            new ProfileUpdate(
                                    legalName,
                                    displayName,
                                    tradingName,
                                    organizationType,
                                    countryCode,
                                    timezone,
                                    locale,
                                    expectedLockVersion));
                    var payload = new LinkedHashMap<String, Object>();
                    payload.put("changedFields", result.changedFields());
                    payload.put("lockVersion", result.profile().lockVersion());
                    var payloadJson = json(payload);
                    return new GovernedMutation(
                            new IdempotentResponse(200, JSON, json(result.profile())),
                            new GovernanceEvidence(
                                    new AuditRecord(
                                            "organization.profile.updated",
                                            1,
                                            "organization",
                                            context.organizationId(),
                                            reason,
                                            payloadJson),
                                    new OutboxRecord(
                                            "organization.profile.updated",
                                            1,
                                            "organization",
                                            context.organizationId(),
                                            payloadJson)));
                });
        return new ProfileMutationOutcome(outcome, entityTag(lockVersion(outcome)));
    }

    public static String entityTag(OrganizationProfile profile) {
        return entityTag(profile.lockVersion());
    }

    private static String entityTag(long lockVersion) {
        return "\"organization-profile:" + lockVersion + "\"";
    }

    private long lockVersion(IdempotencyOutcome outcome) {
        try {
            var node = objectMapper.readTree(outcome.response().bodyJson());
            var value = node.get("lockVersion");
            if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
                throw new IllegalStateException("Profile mutation response omitted lockVersion");
            }
            var lockVersion = value.longValue();
            if (lockVersion < 0) {
                throw new IllegalStateException("Profile mutation response has an invalid lockVersion");
            }
            return lockVersion;
        } catch (OrganizationAdministrationException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("Profile mutation response could not be read", exception);
        }
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
                null);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Organization administration JSON serialization failed", exception);
        }
    }

    private static String requiredName(String value, String field, int minimum, int maximum) {
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
        if (normalized.indexOf('<') >= 0
                || normalized.indexOf('>') >= 0
                || normalized.codePoints().anyMatch(Character::isISOControl)) {
            throw invalidField(
                    field, "m1.field.format", field + " must not contain markup or control characters.");
        }
        return normalized;
    }

    private static String optionalName(String value, String field, int minimum, int maximum) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return requiredName(value, field, minimum, maximum);
    }

    private static String requiredFreeText(String value, String field, int minimum, int maximum) {
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
        if (normalized.codePoints().anyMatch(OrganizationAdministrationService::prohibitedFreeTextControl)) {
            throw invalidField(
                    field, "m1.field.format", field + " contains a prohibited control character.");
        }
        return normalized;
    }

    private static String requiredReason(String value) {
        var normalized = normalize(value);
        if (normalized.isEmpty()) {
            throw invalidField("reason", "m1.field.required", "reason is required.");
        }
        var length = normalized.codePointCount(0, normalized.length());
        if (length < 10 || length > 500) {
            throw invalidField(
                    "reason",
                    "m1.field.length",
                    "reason must contain between 10 and 500 characters.");
        }
        if (normalized.codePoints().anyMatch(Character::isISOControl)) {
            throw invalidField(
                    "reason", "m1.field.format", "reason must not contain control characters.");
        }
        return normalized;
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value.strip(), Normalizer.Form.NFC);
    }

    private static boolean prohibitedFreeTextControl(int codePoint) {
        return Character.isISOControl(codePoint) && codePoint != '\t' && codePoint != '\n';
    }

    private static String organizationType(String value) {
        var normalized = normalize(value);
        if (normalized.isEmpty()) {
            throw invalidField(
                    "organizationType", "m1.field.required", "organizationType is required.");
        }
        if (!ORGANIZATION_TYPES.contains(normalized)) {
            throw invalidField(
                    "organizationType",
                    "m1.field.enum",
                    "organizationType is not an approved organization type.");
        }
        return normalized;
    }

    private static String countryCode(String value) {
        var normalized = normalize(value).toUpperCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            throw invalidField("countryCode", "m1.field.required", "countryCode is required.");
        }
        if (!COUNTRY_CODE.matcher(normalized).matches()) {
            throw invalidField(
                    "countryCode",
                    "m1.field.format",
                    "countryCode must be a two-letter ISO country code.");
        }
        if (!ISO_COUNTRY_CODES.contains(normalized)) {
            throw invalidField(
                    "countryCode", "m1.field.enum", "countryCode is not an ISO 3166-1 alpha-2 code.");
        }
        return normalized;
    }

    private static String timezone(String value) {
        var normalized = requiredFreeText(value, "timezone", 1, 80);
        try {
            if (!ZoneId.getAvailableZoneIds().contains(normalized)) {
                throw new DateTimeException("not an available region identifier");
            }
            ZoneId.of(normalized);
        } catch (DateTimeException exception) {
            throw invalidField(
                    "timezone",
                    "m1.field.format",
                    "timezone must be an IANA timezone identifier.");
        }
        return normalized;
    }

    private static String locale(String value) {
        var normalized = requiredFreeText(value, "locale", 2, 255);
        try {
            var parsed = new Locale.Builder().setLanguageTag(normalized).build();
            var canonical = parsed.toLanguageTag();
            if (parsed.getLanguage().isBlank() || "und".equals(canonical)) {
                throw new IllformedLocaleException("locale has no language");
            }
            return canonical;
        } catch (IllformedLocaleException exception) {
            throw invalidField(
                    "locale", "m1.field.format", "locale must be a well-formed BCP 47 language tag.");
        }
    }

    private static String membershipSearch(String value) {
        if (value == null) {
            return null;
        }
        var normalized = Normalizer.normalize(value.strip(), Normalizer.Form.NFC);
        var length = normalized.codePointCount(0, normalized.length());
        if (length < 2
                || length > 100
                || normalized.codePoints().anyMatch(Character::isISOControl)) {
            throw invalidMembership(
                    "search must contain between 2 and 100 characters without control characters.");
        }
        return normalized;
    }

    private static String membershipAccessState(String value) {
        if (value == null) {
            return null;
        }
        var normalized = value.strip();
        if (!MEMBERSHIP_ACCESS_STATES.contains(normalized)) {
            throw invalidMembership("state is not an allowed membership access state.");
        }
        return normalized;
    }

    private static String membershipRoleKey(String value) {
        if (value == null) {
            return null;
        }
        var normalized = value.strip();
        if (normalized.length() > 100 || !MEMBERSHIP_ROLE_KEY.matcher(normalized).matches()) {
            throw invalidMembership("roleKey has an invalid format.");
        }
        return normalized;
    }

    private static int membershipLimit(int value) {
        if (value < 1 || value > 100) {
            throw invalidMembership("limit must be between 1 and 100.");
        }
        return value;
    }

    private static String membershipCursor(String value) {
        if (value == null) {
            return null;
        }
        if (!MEMBERSHIP_CURSOR.matcher(value).matches()) {
            throw invalidMembership("cursor has an invalid format.");
        }
        return value;
    }

    private static String idempotencyKey(String value) {
        if (value == null || !IDEMPOTENCY_KEY.matcher(value).matches()) {
            throw invalid("Idempotency-Key has an invalid format.");
        }
        return value;
    }

    private static long expectedLockVersion(String value) {
        if (value == null || value.isBlank()) {
            throw new OrganizationAdministrationException(
                    OrganizationAdministrationException.Reason.PRECONDITION_REQUIRED,
                    "A strong If-Match value from the latest profile is required.");
        }
        var matcher = PROFILE_ETAG.matcher(value);
        if (!matcher.matches()) {
            throw invalid("If-Match must be the strong organization profile entity tag.");
        }
        try {
            return Long.parseLong(matcher.group(1));
        } catch (NumberFormatException exception) {
            throw invalid("If-Match contains an invalid organization profile revision.");
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

    private static OrganizationAdministrationException invalid(String message) {
        return new OrganizationAdministrationException(
                OrganizationAdministrationException.Reason.INVALID_REQUEST, message);
    }

    private static OrganizationAdministrationException invalidField(
            String field, String code, String message) {
        return new OrganizationAdministrationException(
                OrganizationAdministrationException.Reason.INVALID_REQUEST,
                message,
                field,
                code);
    }

    private static OrganizationAdministrationException invalidMembership(String message) {
        return new OrganizationAdministrationException(
                OrganizationAdministrationException.Reason.MEMBERSHIP_LIST_INVALID, message);
    }

    public record ReadCommand(UUID organizationId, UUID actorId, String correlationId) {}

    public record MembershipListCommand(
            UUID organizationId,
            UUID actorId,
            String correlationId,
            String search,
            String accessState,
            String roleKey,
            String cursor,
            int limit) {
        ReadCommand readCommand() {
            return new ReadCommand(organizationId, actorId, correlationId);
        }
    }

    public record UpdateCommand(
            UUID organizationId,
            UUID actorId,
            String correlationId,
            String ifMatch,
            String idempotencyKey,
            String legalName,
            String displayName,
            String tradingName,
            String organizationType,
            String countryCode,
            String timezone,
            String locale,
            String reason) {
        ReadCommand readCommand() {
            return new ReadCommand(organizationId, actorId, correlationId);
        }
    }

    public record ProfileMutationOutcome(IdempotencyOutcome outcome, String entityTag) {}
}
