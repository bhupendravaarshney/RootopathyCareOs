package com.rootopathy.careos.identity.application;

import com.rootopathy.careos.governance.application.GovernedMutationExecutor;
import com.rootopathy.careos.governance.domain.AuditRecord;
import com.rootopathy.careos.governance.domain.GovernanceEvidence;
import com.rootopathy.careos.governance.domain.GovernedMutation;
import com.rootopathy.careos.governance.domain.IdempotencyCommand;
import com.rootopathy.careos.governance.domain.IdempotencyOutcome;
import com.rootopathy.careos.governance.domain.IdempotentResponse;
import com.rootopathy.careos.governance.domain.OutboxRecord;
import com.rootopathy.careos.identity.domain.MembershipChange;
import com.rootopathy.careos.shared.domain.UuidV7Generator;
import com.rootopathy.careos.tenancy.domain.AuthenticatedActorContext;
import com.rootopathy.careos.tenancy.domain.IndependentApproval;
import com.rootopathy.careos.tenancy.domain.OperationKey;
import com.rootopathy.careos.tenancy.domain.TenantAuthorizationRequest;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public final class MembershipAdministrationService {
    public static final String REQUEST_OPERATION = "access.membership.change.request";
    public static final String APPROVE_OPERATION = "access.membership.change.approve";
    public static final String EXECUTE_OPERATION = "access.membership.change";
    public static final String OWNER_TRANSFER_REQUEST_OPERATION = "access.owner-transfer.request";
    public static final String OWNER_TRANSFER_APPROVE_OPERATION = "access.owner-transfer.approve";
    public static final String OWNER_TRANSFER_EXECUTE_OPERATION = "access.owner-transfer.execute";
    private static final String PURPOSE = "membership-administration";
    private static final String JSON = "application/json";
    private static final Set<String> CHANGE_TYPES = Set.of("role_change", "revoke");
    private static final Pattern ROLE_KEY =
            Pattern.compile("[a-z][a-z0-9]*(?:[._:-][a-z0-9]+)*");
    private static final Pattern IDEMPOTENCY_KEY =
            Pattern.compile("[A-Za-z0-9._:-]{16,128}");

    private final GovernedMutationExecutor governedMutations;
    private final MembershipChangeStore changes;
    private final SecurityTokenPort tokens;
    private final MembershipAdministrationPolicy policy;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public MembershipAdministrationService(
            GovernedMutationExecutor governedMutations,
            MembershipChangeStore changes,
            SecurityTokenPort tokens,
            MembershipAdministrationPolicy policy,
            ObjectMapper objectMapper,
            Clock clock) {
        this.governedMutations = governedMutations;
        this.changes = changes;
        this.tokens = tokens;
        this.policy = policy;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public IdempotencyOutcome requestChange(RequestCommand command) {
        Objects.requireNonNull(command, "command");
        requireEnabled();
        var changeType = requireChangeType(command.changeType());
        var toRoleKey = requireToRole(changeType, command.toRoleKey());
        var reason = requireReason(command.reason());
        var idempotencyKey = requireIdempotencyKey(command.idempotencyKey());
        var expectedLockVersion = expectedLockVersion(command.membershipId(), command.ifMatch());
        var now = clock.instant();
        var authorization = authorization(
                command.organizationId(),
                command.actorId(),
                command.correlationId(),
                REQUEST_OPERATION,
                reason,
                command.recentAuthenticationAt(),
                command.mfaAuthenticatedAt(),
                null);
        var idempotency = idempotency(
                REQUEST_OPERATION,
                idempotencyKey,
                now,
                command.organizationId().toString(),
                command.membershipId().toString(),
                changeType,
                toRoleKey == null ? "" : toRoleKey,
                Long.toString(expectedLockVersion),
                reason);

        return governedMutations.execute(authorization, idempotency, context -> {
            var change = changes.request(
                    context,
                    UuidV7Generator.randomUuid(),
                    command.membershipId(),
                    changeType,
                    toRoleKey,
                    expectedLockVersion,
                    reason,
                    now.plus(policy.approvalTtl()));
            var payload = basePayload(change);
            payload.put("expectedLockVersion", change.lockVersion());
            return mutation(
                    change,
                    reason,
                    "identity.membership-change.requested",
                    "membership_change",
                    change.approvalId(),
                    201,
                    payload);
        });
    }

    public IdempotencyOutcome approveChange(ApproveCommand command) {
        Objects.requireNonNull(command, "command");
        requireEnabled();
        var reason = requireReason(command.reason());
        var idempotencyKey = requireIdempotencyKey(command.idempotencyKey());
        var now = clock.instant();
        var authorization = authorization(
                command.organizationId(),
                command.actorId(),
                command.correlationId(),
                APPROVE_OPERATION,
                reason,
                command.recentAuthenticationAt(),
                command.mfaAuthenticatedAt(),
                null);
        var idempotency = idempotency(
                APPROVE_OPERATION,
                idempotencyKey,
                now,
                command.organizationId().toString(),
                command.membershipId().toString(),
                command.approvalId().toString(),
                reason);

        return governedMutations.execute(authorization, idempotency, context -> {
            var change = changes.approve(
                    context,
                    command.approvalId(),
                    command.membershipId(),
                    reason,
                    now);
            var payload = basePayload(change);
            payload.put("requestedByUserId", change.requestedByUserId());
            return mutation(
                    change,
                    reason,
                    "identity.membership-change.approved",
                    "membership_change",
                    change.approvalId(),
                    200,
                    payload);
        });
    }

    public IdempotencyOutcome executeChange(ExecuteCommand command) {
        Objects.requireNonNull(command, "command");
        requireEnabled();
        var reason = requireReason(command.reason());
        var idempotencyKey = requireIdempotencyKey(command.idempotencyKey());
        var now = clock.instant();
        var approval = new IndependentApproval(
                command.approvalId(), "membership", command.membershipId(), idempotencyKey);
        var authorization = authorization(
                command.organizationId(),
                command.actorId(),
                command.correlationId(),
                EXECUTE_OPERATION,
                reason,
                command.recentAuthenticationAt(),
                command.mfaAuthenticatedAt(),
                approval);
        var idempotency = idempotency(
                EXECUTE_OPERATION,
                idempotencyKey,
                now,
                command.organizationId().toString(),
                command.membershipId().toString(),
                command.approvalId().toString(),
                reason);

        return governedMutations.execute(authorization, idempotency, context -> {
            var change = changes.execute(
                    context,
                    command.approvalId(),
                    command.membershipId(),
                    idempotencyKey,
                    now);
            var eventName = "revoke".equals(change.changeType())
                    ? "identity.membership.revoked"
                    : "identity.membership.changed";
            var payload = basePayload(change);
            payload.put("lockVersion", change.lockVersion());
            return mutation(
                    change,
                    reason,
                    eventName,
                    "membership",
                    change.membershipId(),
                    200,
                    payload);
        });
    }

    public IdempotencyOutcome requestOwnerTransfer(OwnerTransferRequestCommand command) {
        Objects.requireNonNull(command, "command");
        requireEnabled();
        var toRoleKey = requireRoleKey(command.toRoleKey());
        var reason = requireReason(command.reason());
        var idempotencyKey = requireIdempotencyKey(command.idempotencyKey());
        var expectedLockVersion = expectedLockVersion(command.membershipId(), command.ifMatch());
        var now = clock.instant();
        var authorization = authorization(
                command.organizationId(),
                command.actorId(),
                command.correlationId(),
                OWNER_TRANSFER_REQUEST_OPERATION,
                reason,
                command.recentAuthenticationAt(),
                command.mfaAuthenticatedAt(),
                null);
        var idempotency = idempotency(
                OWNER_TRANSFER_REQUEST_OPERATION,
                idempotencyKey,
                now,
                command.organizationId().toString(),
                command.membershipId().toString(),
                toRoleKey,
                Long.toString(expectedLockVersion),
                reason);

        return governedMutations.execute(authorization, idempotency, context -> {
            var change = changes.requestOwnerTransfer(
                    context,
                    UuidV7Generator.randomUuid(),
                    command.membershipId(),
                    toRoleKey,
                    expectedLockVersion,
                    reason,
                    now.plus(policy.approvalTtl()));
            var payload = basePayload(change);
            payload.put("expectedLockVersion", change.lockVersion());
            return mutation(
                    change,
                    reason,
                    "identity.owner-transfer.requested",
                    "owner_transfer",
                    change.approvalId(),
                    201,
                    payload);
        });
    }

    public IdempotencyOutcome approveOwnerTransfer(ApproveCommand command) {
        Objects.requireNonNull(command, "command");
        requireEnabled();
        var reason = requireReason(command.reason());
        var idempotencyKey = requireIdempotencyKey(command.idempotencyKey());
        var now = clock.instant();
        var authorization = authorization(
                command.organizationId(),
                command.actorId(),
                command.correlationId(),
                OWNER_TRANSFER_APPROVE_OPERATION,
                reason,
                command.recentAuthenticationAt(),
                command.mfaAuthenticatedAt(),
                null);
        var idempotency = idempotency(
                OWNER_TRANSFER_APPROVE_OPERATION,
                idempotencyKey,
                now,
                command.organizationId().toString(),
                command.membershipId().toString(),
                command.approvalId().toString(),
                reason);

        return governedMutations.execute(authorization, idempotency, context -> {
            var change = changes.approveOwnerTransfer(
                    context,
                    command.approvalId(),
                    command.membershipId(),
                    reason,
                    now);
            var payload = basePayload(change);
            payload.put("requestedByUserId", change.requestedByUserId());
            return mutation(
                    change,
                    reason,
                    "identity.owner-transfer.approved",
                    "owner_transfer",
                    change.approvalId(),
                    200,
                    payload);
        });
    }

    public IdempotencyOutcome executeOwnerTransfer(ExecuteCommand command) {
        Objects.requireNonNull(command, "command");
        requireEnabled();
        var reason = requireReason(command.reason());
        var idempotencyKey = requireIdempotencyKey(command.idempotencyKey());
        var now = clock.instant();
        var approval = new IndependentApproval(
                command.approvalId(), "membership", command.membershipId(), idempotencyKey);
        var authorization = authorization(
                command.organizationId(),
                command.actorId(),
                command.correlationId(),
                OWNER_TRANSFER_EXECUTE_OPERATION,
                reason,
                command.recentAuthenticationAt(),
                command.mfaAuthenticatedAt(),
                approval);
        var idempotency = idempotency(
                OWNER_TRANSFER_EXECUTE_OPERATION,
                idempotencyKey,
                now,
                command.organizationId().toString(),
                command.membershipId().toString(),
                command.approvalId().toString(),
                reason);

        return governedMutations.execute(authorization, idempotency, context -> {
            var change = changes.executeOwnerTransfer(
                    context,
                    command.approvalId(),
                    command.membershipId(),
                    idempotencyKey,
                    now);
            var payload = basePayload(change);
            payload.put("lockVersion", change.lockVersion());
            return mutation(
                    change,
                    reason,
                    "identity.owner.transferred",
                    "membership",
                    change.membershipId(),
                    200,
                    payload);
        });
    }

    private GovernedMutation mutation(
            MembershipChange change,
            String reason,
            String eventName,
            String subjectType,
            UUID subjectId,
            int statusCode,
            LinkedHashMap<String, Object> payload) {
        var payloadJson = json(payload);
        return new GovernedMutation(
                new IdempotentResponse(
                        statusCode,
                        JSON,
                        json(new MembershipChangeResponse(
                                change.approvalId(),
                                change.membershipId(),
                                change.targetUserId(),
                                change.changeType(),
                                change.fromRoleKey(),
                                change.toRoleKey(),
                                change.lockVersion(),
                                change.status(),
                                change.expiresAt()))),
                new GovernanceEvidence(
                        new AuditRecord(eventName, 1, subjectType, subjectId, reason, payloadJson),
                        new OutboxRecord(eventName, 1, subjectType, subjectId, payloadJson)));
    }

    private static LinkedHashMap<String, Object> basePayload(MembershipChange change) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("approvalId", change.approvalId());
        payload.put("changeType", change.changeType());
        payload.put("fromRole", change.fromRoleKey());
        payload.put("membershipId", change.membershipId());
        payload.put("toRole", change.toRoleKey());
        return payload;
    }

    private TenantAuthorizationRequest authorization(
            UUID organizationId,
            UUID actorId,
            String correlationId,
            String operation,
            String reason,
            Instant recentAuthenticationAt,
            Instant mfaAuthenticatedAt,
            IndependentApproval approval) {
        return new TenantAuthorizationRequest(
                Objects.requireNonNull(organizationId, "organizationId"),
                new AuthenticatedActorContext(
                        Objects.requireNonNull(actorId, "actorId"), PURPOSE, correlationId),
                new OperationKey(operation),
                reason,
                recentAuthenticationAt,
                mfaAuthenticatedAt,
                approval);
    }

    private IdempotencyCommand idempotency(
            String operation, String key, Instant now, String... values) {
        return new IdempotencyCommand(
                operation, key, requestHash(operation, values), now.plus(policy.idempotencyTtl()));
    }

    private String requestHash(String operation, String... values) {
        var canonical = new StringBuilder(operation.length() + 2).append(operation).append(';');
        for (var value : values) {
            canonical.append(value.length()).append(':').append(value).append(';');
        }
        return tokens.digest(canonical.toString());
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Membership administration JSON serialization failed", exception);
        }
    }

    private void requireEnabled() {
        if (!policy.enabled()) {
            throw new MembershipAdministrationException(
                    MembershipAdministrationException.Reason.UNAVAILABLE,
                    "Governed membership administration is not enabled in this environment.");
        }
    }

    private static String requireChangeType(String value) {
        var normalized = value == null ? "" : value.strip();
        if (!CHANGE_TYPES.contains(normalized)) {
            throw invalid("changeType must be role_change or revoke.");
        }
        return normalized;
    }

    private static String requireToRole(String changeType, String value) {
        var normalized = value == null ? null : value.strip();
        if ("revoke".equals(changeType)) {
            if (normalized != null && !normalized.isEmpty()) {
                throw invalid("toRoleKey must be omitted for a revocation.");
            }
            return null;
        }
        return requireRoleKey(normalized);
    }

    private static String requireRoleKey(String value) {
        var normalized = value == null ? "" : value.strip();
        if (normalized.length() > 100 || !ROLE_KEY.matcher(normalized).matches()) {
            throw invalid("toRoleKey has an invalid format.");
        }
        return normalized;
    }

    private static String requireReason(String value) {
        var normalized = Normalizer.normalize(value == null ? "" : value.strip(), Normalizer.Form.NFC);
        var length = normalized.codePointCount(0, normalized.length());
        if (length < 10
                || length > 500
                || normalized.codePoints().anyMatch(Character::isISOControl)) {
            throw invalid("reason must contain between 10 and 500 characters without control characters.");
        }
        return normalized;
    }

    private static String requireIdempotencyKey(String value) {
        if (value == null || !IDEMPOTENCY_KEY.matcher(value).matches()) {
            throw invalid("Idempotency-Key has an invalid format.");
        }
        return value;
    }

    private static long expectedLockVersion(UUID membershipId, String value) {
        if (value == null || value.isBlank()) {
            throw new MembershipAdministrationException(
                    MembershipAdministrationException.Reason.PRECONDITION_REQUIRED,
                    "A strong If-Match value from the latest membership is required.");
        }
        var pattern = Pattern.compile(
                "\\\"organization-membership:" + Pattern.quote(membershipId.toString())
                        + ":([0-9]{1,19})\\\"");
        var matcher = pattern.matcher(value);
        if (!matcher.matches()) {
            throw invalid("If-Match must be the strong organization membership entity tag.");
        }
        try {
            return Long.parseLong(matcher.group(1));
        } catch (NumberFormatException exception) {
            throw invalid("If-Match contains an invalid membership revision.");
        }
    }

    private static MembershipAdministrationException invalid(String message) {
        return new MembershipAdministrationException(
                MembershipAdministrationException.Reason.INVALID_REQUEST, message);
    }

    public record RequestCommand(
            UUID organizationId,
            UUID actorId,
            UUID membershipId,
            String changeType,
            String toRoleKey,
            String reason,
            String ifMatch,
            Instant recentAuthenticationAt,
            Instant mfaAuthenticatedAt,
            String correlationId,
            String idempotencyKey) {
        public RequestCommand {
            Objects.requireNonNull(membershipId, "membershipId");
        }
    }

    public record OwnerTransferRequestCommand(
            UUID organizationId,
            UUID actorId,
            UUID membershipId,
            String toRoleKey,
            String reason,
            String ifMatch,
            Instant recentAuthenticationAt,
            Instant mfaAuthenticatedAt,
            String correlationId,
            String idempotencyKey) {
        public OwnerTransferRequestCommand {
            Objects.requireNonNull(membershipId, "membershipId");
        }
    }

    public record ApproveCommand(
            UUID organizationId,
            UUID actorId,
            UUID membershipId,
            UUID approvalId,
            String reason,
            Instant recentAuthenticationAt,
            Instant mfaAuthenticatedAt,
            String correlationId,
            String idempotencyKey) {
        public ApproveCommand {
            Objects.requireNonNull(membershipId, "membershipId");
            Objects.requireNonNull(approvalId, "approvalId");
        }
    }

    public record ExecuteCommand(
            UUID organizationId,
            UUID actorId,
            UUID membershipId,
            UUID approvalId,
            String reason,
            Instant recentAuthenticationAt,
            Instant mfaAuthenticatedAt,
            String correlationId,
            String idempotencyKey) {
        public ExecuteCommand {
            Objects.requireNonNull(membershipId, "membershipId");
            Objects.requireNonNull(approvalId, "approvalId");
        }
    }

    public record MembershipChangeResponse(
            UUID approvalId,
            UUID membershipId,
            UUID targetUserId,
            String changeType,
            String fromRoleKey,
            String toRoleKey,
            long lockVersion,
            String status,
            Instant expiresAt) {}
}
