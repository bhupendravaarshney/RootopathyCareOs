package com.rootopathy.careos.administration.application;

import com.rootopathy.careos.administration.domain.ServiceLocationDirectory;
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
import java.text.Normalizer;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class ServiceLocationService {
    public static final String READ = "network.structure.read";
    public static final String MANAGE = "network.structure.manage";
    private static final Pattern CODE = Pattern.compile("[A-Z0-9][A-Z0-9_-]{1,31}");
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9._:-]{16,128}");
    private static final Pattern ETAG =
            Pattern.compile("\"service-location:([0-9a-fA-F-]{36}):([0-9]{1,19})\"");
    private final TenantAuthorizationOperations authorization;
    private final GovernedMutationExecutor mutations;
    private final ServiceLocationStore store;
    private final ObjectMapper mapper;
    private final Clock clock;

    public ServiceLocationService(
            TenantAuthorizationOperations authorization,
            GovernedMutationExecutor mutations,
            ServiceLocationStore store,
            ObjectMapper mapper,
            Clock clock) {
        this.authorization = authorization;
        this.mutations = mutations;
        this.store = store;
        this.mapper = mapper;
        this.clock = clock;
    }

    public ServiceLocationDirectory directory(Read command) {
        return authorization.execute(
                request(command.organizationId(), command.actorId(), command.correlationId(), READ, null),
                context -> store.directory(context, command.facilityId()));
    }

    public IdempotencyOutcome create(Create command) {
        var reason = text(command.reason(), "reason", 10, 500);
        if (command.idempotencyKey() == null || !KEY.matcher(command.idempotencyKey()).matches()) {
            throw new IllegalArgumentException("invalid Idempotency-Key");
        }
        var draft = draft(command);
        var idempotency = new IdempotencyCommand(
                MANAGE,
                command.idempotencyKey(),
                hash(draft, reason),
                clock.instant().plusSeconds(86400));
        return mutations.execute(
                request(command.organizationId(), command.actorId(), command.correlationId(), MANAGE, reason),
                idempotency,
                context -> {
                    var result = store.create(context, draft);
                    var payload = json(Map.of(
                            "recordId", result.locationId(),
                            "changeType", "created",
                            "parentId", command.parentId() == null ? "" : command.parentId().toString(),
                            "fromState", "none",
                            "toState", "draft",
                            "lockVersion", 0));
                    return new GovernedMutation(
                            new IdempotentResponse(201, "application/json", json(result.directory())),
                            new GovernanceEvidence(
                                    new AuditRecord(
                                            "network.location.changed",
                                            1,
                                            "service_location",
                                            result.locationId(),
                                            reason,
                                            payload),
                                    new OutboxRecord(
                                            "network.location.changed",
                                            1,
                                            "service_location",
                                            result.locationId(),
                                            payload)));
                });
    }

    public IdempotencyOutcome update(Update command) {
        var reason = text(command.reason(), "reason", 10, 500);
        validKey(command.idempotencyKey());
        var revision = revision(command.ifMatch(), command.locationId());
        var draft = draft(
                command.facilityId(), command.unitId(), null, command.addressId(), command.locationCode(),
                command.locationType(), command.name(), command.virtualServiceType(), command.capacity(),
                command.accessibilityNotes(), command.effectiveFrom(), command.effectiveTo());
        var idempotency = new IdempotencyCommand(
                MANAGE, command.idempotencyKey(), hash(command.locationId(), revision, draft, reason),
                clock.instant().plusSeconds(86400));
        return mutations.execute(
                request(command.organizationId(), command.actorId(), command.correlationId(), MANAGE, reason),
                idempotency,
                context -> {
                    var result = store.update(context, command.locationId(), revision, draft);
                    var payload = json(Map.of(
                            "recordId", result.locationId(), "changeType", "updated",
                            "parentId", result.parentId() == null ? "" : result.parentId().toString(),
                            "fromState", "draft", "toState", "draft", "lockVersion", result.lockVersion()));
                    return mutation(result.directory(), result.locationId(), reason, payload, "network.location.changed");
                });
    }

    public IdempotencyOutcome reparent(Reparent command) {
        var reason = text(command.reason(), "reason", 10, 500);
        validKey(command.idempotencyKey());
        var revision = revision(command.ifMatch(), command.locationId());
        var now = clock.instant();
        if (command.effectiveFrom() == null
                || command.effectiveFrom().isBefore(now.minusSeconds(300))
                || command.effectiveFrom().isAfter(now.plusSeconds(300)))
            throw new IllegalArgumentException("effectiveFrom must be current for this immediate reparenting boundary");
        var idempotency = new IdempotencyCommand(
                MANAGE, command.idempotencyKey(),
                hash(command.locationId(), revision, command.parentId(), command.effectiveFrom(), reason),
                now.plusSeconds(86400));
        return mutations.execute(
                request(command.organizationId(), command.actorId(), command.correlationId(), MANAGE, reason),
                idempotency,
                context -> {
                    var result = store.reparent(context, command.facilityId(), command.locationId(), revision,
                            command.parentId(), command.effectiveFrom());
                    var payload = json(Map.of(
                            "recordId", result.locationId(),
                            "previousParentId", result.previousParentId() == null ? "" : result.previousParentId().toString(),
                            "parentId", result.parentId() == null ? "" : result.parentId().toString(),
                            "effectiveFrom", result.effectiveFrom().toString(),
                            "lockVersion", result.lockVersion()));
                    return mutation(result.directory(), result.locationId(), reason, payload, "network.location.reparented");
                });
    }

    public IdempotencyOutcome activate(Lifecycle command) { return lifecycle(command, "draft", "active", "activated"); }
    public IdempotencyOutcome suspend(Lifecycle command) { return lifecycle(command, "active", "suspended", "suspended"); }
    public IdempotencyOutcome reactivate(Lifecycle command) { return lifecycle(command, "suspended", "active", "reactivated"); }

    private IdempotencyOutcome lifecycle(Lifecycle command, String fromState, String toState, String changeType) {
        var reason = text(command.reason(), "reason", 10, 500);
        validKey(command.idempotencyKey());
        var revision = revision(command.ifMatch(), command.locationId());
        var idempotency = new IdempotencyCommand(
                OrganizationUnitService.LIFECYCLE, command.idempotencyKey(),
                hash(command.locationId(), revision, toState, reason), clock.instant().plusSeconds(86400));
        return mutations.execute(new TenantAuthorizationRequest(
                        command.organizationId(), new AuthenticatedActorContext(command.actorId(), "service-location-administration", command.correlationId()),
                        new OperationKey(OrganizationUnitService.LIFECYCLE), reason,
                        command.recentAuthenticationAt(), command.mfaAuthenticatedAt(), null),
                idempotency, context -> {
                    var result = store.transition(context, command.facilityId(), command.locationId(), revision, fromState, toState);
                    var payload = lifecyclePayload(result, changeType, toState);
                    return mutation(result.directory(), result.locationId(), reason, payload, "network.location.changed");
                });
    }

    public IdempotencyOutcome close(Close command) {
        var reason = text(command.reason(), "reason", 10, 500);
        validKey(command.idempotencyKey());
        var revision = revision(command.ifMatch(), command.locationId());
        var now = clock.instant();
        if (command.effectiveTo() == null || command.effectiveTo().isBefore(now.minusSeconds(300)) || command.effectiveTo().isAfter(now.plusSeconds(300)))
            throw new IllegalArgumentException("effectiveTo must be current for this immediate closure boundary");
        var idempotency = new IdempotencyCommand(OrganizationUnitService.LIFECYCLE, command.idempotencyKey(),
                hash(command.locationId(), revision, "closed", command.effectiveTo(), reason), now.plusSeconds(86400));
        return mutations.execute(new TenantAuthorizationRequest(
                        command.organizationId(), new AuthenticatedActorContext(command.actorId(), "service-location-administration", command.correlationId()),
                        new OperationKey(OrganizationUnitService.LIFECYCLE), reason,
                        command.recentAuthenticationAt(), command.mfaAuthenticatedAt(), null),
                idempotency, context -> {
                    var result = store.close(context, command.facilityId(), command.locationId(), revision, command.effectiveTo());
                    return mutation(result.directory(), result.locationId(), reason,
                            lifecyclePayload(result, "closed", "closed"), "network.location.changed");
                });
    }

    private String lifecyclePayload(ServiceLocationStore.LifecycleResult result, String changeType, String toState) {
        return json(Map.of("recordId", result.locationId(), "changeType", changeType,
                "parentId", result.parentId() == null ? "" : result.parentId().toString(),
                "fromState", result.fromState(), "toState", toState, "lockVersion", result.lockVersion()));
    }

    private GovernedMutation mutation(
            ServiceLocationDirectory directory, UUID locationId, String reason, String payload, String eventName) {
        return new GovernedMutation(
                new IdempotentResponse(200, "application/json", json(directory)),
                new GovernanceEvidence(
                        new AuditRecord(eventName, 1, "service_location", locationId, reason, payload),
                        new OutboxRecord(eventName, 1, "service_location", locationId, payload)));
    }

    private static ServiceLocationStore.Draft draft(Create command) {
        return draft(command.facilityId(), command.unitId(), command.parentId(), command.addressId(),
                command.locationCode(), command.locationType(), command.name(), command.virtualServiceType(),
                command.capacity(), command.accessibilityNotes(), command.effectiveFrom(), command.effectiveTo());
    }

    private static ServiceLocationStore.Draft draft(
            UUID facilityId, UUID unitId, UUID parentId, UUID addressId, String locationCode,
            String locationType, String name, String virtualServiceType, Integer capacity,
            String accessibilityNotes, Instant effectiveFrom, Instant effectiveTo) {
        var code = text(locationCode, "locationCode", 2, 32).toUpperCase(Locale.ROOT);
        if (!CODE.matcher(code).matches()) throw new IllegalArgumentException("invalid locationCode");
        var type = text(locationType, "locationType", 7, 8);
        if (!Set.of("physical", "virtual").contains(type))
            throw new IllegalArgumentException("invalid locationType");
        var virtualType = optional(virtualServiceType, "virtualServiceType", 2, 80);
        var notes = optional(accessibilityNotes, "accessibilityNotes", 1, 500);
        if ("physical".equals(type) && (addressId == null || virtualType != null)
                || "virtual".equals(type) && (addressId != null || virtualType == null)) {
            throw new IllegalArgumentException("invalid physical or virtual location details");
        }
        if (capacity != null && (capacity < 1 || capacity > 100000))
            throw new IllegalArgumentException("invalid capacity");
        if (effectiveFrom == null || effectiveTo != null && !effectiveTo.isAfter(effectiveFrom)) {
            throw new IllegalArgumentException("invalid effective range");
        }
        return new ServiceLocationStore.Draft(
                facilityId,
                unitId,
                parentId,
                addressId,
                code,
                type,
                text(name, "name", 2, 120),
                virtualType,
                capacity,
                notes,
                effectiveFrom,
                effectiveTo);
    }

    private static void validKey(String key) {
        if (key == null || !KEY.matcher(key).matches()) throw new IllegalArgumentException("invalid Idempotency-Key");
    }

    private static long revision(String etag, UUID locationId) {
        var match = etag == null ? null : ETAG.matcher(etag);
        if (match == null || !match.matches())
            throw new ServiceLocationException(ServiceLocationException.Reason.PRECONDITION_REQUIRED,
                    "A strong service-location If-Match value is required.");
        if (!UUID.fromString(match.group(1)).equals(locationId))
            throw new ServiceLocationException(ServiceLocationException.Reason.STALE,
                    "The service-location entity tag does not match this resource.");
        return Long.parseLong(match.group(2));
    }

    private TenantAuthorizationRequest request(
            UUID organizationId, UUID actorId, String correlationId, String operation, String reason) {
        return new TenantAuthorizationRequest(
                organizationId,
                new AuthenticatedActorContext(actorId, "service-location-administration", correlationId),
                new OperationKey(operation),
                reason,
                null,
                null,
                null);
    }

    private static String text(String value, String field, int minimum, int maximum) {
        var normalized = value == null ? "" : Normalizer.normalize(value.strip(), Normalizer.Form.NFC);
        if (normalized.length() < minimum
                || normalized.length() > maximum
                || normalized.codePoints().anyMatch(Character::isISOControl)
                || normalized.contains("<")
                || normalized.contains(">")) throw new IllegalArgumentException("invalid " + field);
        return normalized;
    }

    private static String optional(String value, String field, int minimum, int maximum) {
        return value == null ? null : text(value, field, minimum, maximum);
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String hash(Object... values) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(Arrays.toString(values).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    public record Read(UUID organizationId, UUID actorId, String correlationId, UUID facilityId) {}

    public record Create(
            UUID organizationId,
            UUID actorId,
            String correlationId,
            String idempotencyKey,
            UUID facilityId,
            UUID unitId,
            UUID parentId,
            UUID addressId,
            String locationCode,
            String locationType,
            String name,
            String virtualServiceType,
            Integer capacity,
            String accessibilityNotes,
            Instant effectiveFrom,
            Instant effectiveTo,
            String reason) {}

    public record Update(
            UUID organizationId, UUID actorId, String correlationId, String idempotencyKey, String ifMatch,
            UUID facilityId, UUID locationId, UUID unitId, UUID addressId, String locationCode,
            String locationType, String name, String virtualServiceType, Integer capacity,
            String accessibilityNotes, Instant effectiveFrom, Instant effectiveTo, String reason) {}

    public record Reparent(
            UUID organizationId, UUID actorId, String correlationId, String idempotencyKey, String ifMatch,
            UUID facilityId, UUID locationId, UUID parentId, Instant effectiveFrom, String reason) {}

    public record Lifecycle(
            UUID organizationId, UUID actorId, String correlationId, String idempotencyKey, String ifMatch,
            UUID facilityId, UUID locationId, String reason, Instant recentAuthenticationAt, Instant mfaAuthenticatedAt) {}

    public record Close(
            UUID organizationId, UUID actorId, String correlationId, String idempotencyKey, String ifMatch,
            UUID facilityId, UUID locationId, Instant effectiveTo, String reason,
            Instant recentAuthenticationAt, Instant mfaAuthenticatedAt) {}
}
