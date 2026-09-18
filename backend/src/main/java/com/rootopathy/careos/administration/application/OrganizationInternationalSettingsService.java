package com.rootopathy.careos.administration.application;

import com.rootopathy.careos.administration.application.OrganizationInternationalSettingsStore.SettingsDraft;
import com.rootopathy.careos.administration.domain.OrganizationInternationalSettings;
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
public final class OrganizationInternationalSettingsService {
    public static final String READ_OPERATION = "organization.settings.read";
    public static final String UPDATE_OPERATION = "organization.settings.update";
    private static final String PURPOSE = "organization-administration";
    private static final String JSON = "application/json";
    private static final Duration IDEMPOTENCY_TTL = Duration.ofHours(24);
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9._:-]{16,128}");
    private static final Pattern ETAG =
            Pattern.compile("\"organization-international-settings:([0-9]{1,19})\"");
    private static final Set<String> ISO_COUNTRIES = Set.of(Locale.getISOCountries());
    private static final Set<String> CURRENCIES = Set.of((
                    "AED AFN ALL AMD ANG AOA ARS AUD AWG AZN BAM BBD BDT BGN BHD BIF BMD BND "
                            + "BOB BOV BRL BSD BTN BWP BYN BZD CAD CDF CHE CHF CHW CLF CLP CNY "
                            + "COP COU CRC CUC CUP CVE CZK DJF DKK DOP DZD EGP ERN ETB EUR FJD "
                            + "FKP GBP GEL GHS GIP GMD GNF GTQ GYD HKD HNL HRK HTG HUF IDR ILS "
                            + "INR IQD IRR ISK JMD JOD JPY KES KGS KHR KMF KPW KRW KWD KYD KZT "
                            + "LAK LBP LKR LRD LSL LYD MAD MDL MGA MKD MMK MNT MOP MRU MUR MVR "
                            + "MWK MXN MXV MYR MZN NAD NGN NIO NOK NPR NZD OMR PAB PEN PGK PHP "
                            + "PKR PLN PYG QAR RON RSD RUB RWF SAR SBD SCR SDG SEK SGD SHP SLE "
                            + "SOS SRD SSP STN SVC SYP SZL THB TJS TMT TND TOP TRY TTD TWD TZS "
                            + "UAH UGX USD USN UYI UYU UZS VED VES VND VUV WST XAF XAG XAU XBA "
                            + "XBB XBC XBD XCD XCG XDR XOF XPD XPF XPT XSU XTS XUA XXX YER ZAR "
                            + "ZMW ZWG ZWL")
            .split(" "));

    private final TenantAuthorizationOperations authorization;
    private final GovernedMutationExecutor governedMutations;
    private final OrganizationInternationalSettingsStore store;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public OrganizationInternationalSettingsService(
            TenantAuthorizationOperations authorization,
            GovernedMutationExecutor governedMutations,
            OrganizationInternationalSettingsStore store,
            ObjectMapper objectMapper,
            Clock clock) {
        this.authorization = authorization;
        this.governedMutations = governedMutations;
        this.store = store;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public OrganizationInternationalSettings settings(ReadCommand command) {
        Objects.requireNonNull(command, "command");
        return authorization.execute(
                authorization(command, READ_OPERATION, null), store::settings);
    }

    public MutationOutcome schedule(ScheduleCommand command) {
        Objects.requireNonNull(command, "command");
        var draft = draft(command.draft());
        var reason = requiredText(command.reason(), "reason", 10, 500);
        var expectedLockVersion = expectedLockVersion(command.ifMatch());
        var key = idempotencyKey(command.idempotencyKey());
        var hash = requestHash(
                command.organizationId().toString(),
                draft.countryCode(),
                draft.timezone(),
                draft.locale(),
                draft.language(),
                draft.currencyCode(),
                draft.weekStart(),
                draft.effectiveFrom().toString(),
                reason,
                Long.toString(expectedLockVersion));
        var outcome = governedMutations.execute(
                authorization(command.readCommand(), UPDATE_OPERATION, reason),
                new IdempotencyCommand(
                        UPDATE_OPERATION, key, hash, clock.instant().plus(IDEMPOTENCY_TTL)),
                context -> {
                    var result = store.schedule(context, draft, expectedLockVersion);
                    var payload = new LinkedHashMap<String, Object>();
                    payload.put("changedFields", result.changedFields());
                    payload.put("effectiveFrom", draft.effectiveFrom());
                    payload.put("lockVersion", result.settings().lockVersion());
                    var payloadJson = json(payload);
                    return new GovernedMutation(
                            new IdempotentResponse(200, JSON, json(result.settings())),
                            new GovernanceEvidence(
                                    new AuditRecord(
                                            "organization.settings.changed",
                                            1,
                                            "organization_international_settings",
                                            result.settingsId(),
                                            reason,
                                            payloadJson),
                                    new OutboxRecord(
                                            "organization.settings.changed",
                                            1,
                                            "organization_international_settings",
                                            result.settingsId(),
                                            payloadJson)));
                });
        return new MutationOutcome(outcome, entityTag(lockVersion(outcome)));
    }

    public static String entityTag(OrganizationInternationalSettings settings) {
        return entityTag(settings.lockVersion());
    }

    private static String entityTag(long lockVersion) {
        return "\"organization-international-settings:" + lockVersion + "\"";
    }

    private long lockVersion(IdempotencyOutcome outcome) {
        try {
            var node = objectMapper.readTree(outcome.response().bodyJson()).get("lockVersion");
            if (node == null || !node.isIntegralNumber() || !node.canConvertToLong()) {
                throw new IllegalStateException(
                        "International-settings mutation response omitted revision evidence");
            }
            var revision = node.longValue();
            if (revision < 0) {
                throw new IllegalStateException(
                        "International-settings mutation response has an invalid revision");
            }
            return revision;
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "International-settings mutation response could not be read", exception);
        }
    }

    private SettingsDraft draft(SettingsDraftCommand command) {
        Objects.requireNonNull(command, "draft");
        var countryCode = normalize(command.countryCode()).toUpperCase(Locale.ROOT);
        if (!ISO_COUNTRIES.contains(countryCode)) {
            throw invalidField(
                    "countryCode",
                    "m1.field.enum",
                    "countryCode must be an ISO 3166-1 alpha-2 code.");
        }
        var timezone = normalize(command.timezone());
        if (!ZoneId.getAvailableZoneIds().contains(timezone)) {
            throw invalidField(
                    "timezone", "m1.field.enum", "timezone must be an IANA timezone identifier.");
        }
        var locale = languageTag(command.locale(), "locale");
        var language = languageTag(command.language(), "language");
        var currencyCode = normalize(command.currencyCode()).toUpperCase(Locale.ROOT);
        if (!CURRENCIES.contains(currencyCode)) {
            throw invalidField(
                    "currencyCode", "m1.field.enum", "currencyCode must be an ISO currency code.");
        }
        var weekStart = normalize(command.weekStart()).toUpperCase(Locale.ROOT);
        if (!OrganizationInternationalSettings.APPROVED_WEEK_STARTS.contains(weekStart)) {
            throw invalidField(
                    "weekStart", "m1.field.enum", "weekStart is not an approved ISO weekday.");
        }
        var effectiveFrom = instant(command.effectiveFrom(), "effectiveFrom");
        if (!effectiveFrom.isAfter(clock.instant())) {
            throw invalidField(
                    "effectiveFrom",
                    "m1.field.format",
                    "effectiveFrom must be a future UTC instant.");
        }
        return new SettingsDraft(
                countryCode,
                timezone,
                locale,
                language,
                currencyCode,
                weekStart,
                effectiveFrom);
    }

    private static String languageTag(String value, String field) {
        var normalized = normalize(value);
        if (normalized.isEmpty() || normalized.length() > 100) {
            throw invalidField(
                    field, "m1.field.length", field + " must contain between 2 and 100 characters.");
        }
        try {
            var parsed = new Locale.Builder().setLanguageTag(normalized).build();
            var canonical = parsed.toLanguageTag();
            if (parsed.getLanguage().isBlank() || "und".equals(canonical)) {
                throw new IllformedLocaleException("language tag has no language");
            }
            return canonical;
        } catch (IllformedLocaleException exception) {
            throw invalidField(
                    field, "m1.field.format", field + " must be a well-formed BCP 47 language tag.");
        }
    }

    private static Instant instant(String value, String field) {
        if (value == null || value.isBlank()) {
            throw invalidField(field, "m1.field.required", field + " is required.");
        }
        try {
            return Instant.parse(value.strip());
        } catch (DateTimeException exception) {
            throw invalidField(
                    field, "m1.field.format", field + " must be an ISO 8601 UTC instant.");
        }
    }

    private static String requiredText(String value, String field, int minimum, int maximum) {
        var normalized = normalize(value);
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

    private static long expectedLockVersion(String value) {
        if (value == null || value.isBlank()) {
            throw new OrganizationInternationalSettingsException(
                    OrganizationInternationalSettingsException.Reason.PRECONDITION_REQUIRED,
                    "A strong If-Match value from the latest international settings is required.");
        }
        var matcher = ETAG.matcher(value);
        if (!matcher.matches()) {
            throw invalid("If-Match must be the strong international-settings entity tag.");
        }
        try {
            return Long.parseLong(matcher.group(1));
        } catch (NumberFormatException exception) {
            throw invalid("If-Match contains invalid international-settings revision evidence.");
        }
    }

    private static String idempotencyKey(String value) {
        if (value == null || !IDEMPOTENCY_KEY.matcher(value).matches()) {
            throw invalid("Idempotency-Key has an invalid format.");
        }
        return value;
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

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "International-settings JSON serialization failed", exception);
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

    private static OrganizationInternationalSettingsException invalid(String message) {
        return new OrganizationInternationalSettingsException(
                OrganizationInternationalSettingsException.Reason.INVALID_REQUEST, message);
    }

    private static OrganizationInternationalSettingsException invalidField(
            String field, String code, String message) {
        return new OrganizationInternationalSettingsException(
                OrganizationInternationalSettingsException.Reason.INVALID_REQUEST,
                message,
                field,
                code);
    }

    public record ReadCommand(UUID organizationId, UUID actorId, String correlationId) {}

    public record SettingsDraftCommand(
            String countryCode,
            String timezone,
            String locale,
            String language,
            String currencyCode,
            String weekStart,
            String effectiveFrom) {}

    public record ScheduleCommand(
            UUID organizationId,
            UUID actorId,
            String correlationId,
            String ifMatch,
            String idempotencyKey,
            SettingsDraftCommand draft,
            String reason) {
        ReadCommand readCommand() {
            return new ReadCommand(organizationId, actorId, correlationId);
        }
    }

    public record MutationOutcome(IdempotencyOutcome outcome, String entityTag) {}
}
