package com.rootopathy.careos.identity.application;

import com.rootopathy.careos.governance.application.GovernedMutationExecutor;
import com.rootopathy.careos.governance.domain.AuditRecord;
import com.rootopathy.careos.governance.domain.GovernanceEvidence;
import com.rootopathy.careos.governance.domain.GovernedMutation;
import com.rootopathy.careos.governance.domain.IdempotencyCommand;
import com.rootopathy.careos.governance.domain.IdempotencyOutcome;
import com.rootopathy.careos.governance.domain.IdempotentResponse;
import com.rootopathy.careos.governance.domain.OutboxRecord;
import com.rootopathy.careos.identity.domain.MfaResetApproval;
import com.rootopathy.careos.shared.domain.UuidV7Generator;
import com.rootopathy.careos.tenancy.domain.AuthenticatedActorContext;
import com.rootopathy.careos.tenancy.domain.IndependentApproval;
import com.rootopathy.careos.tenancy.domain.OperationKey;
import com.rootopathy.careos.tenancy.domain.TenantAuthorizationRequest;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public final class MfaAdministrationService {
    public static final String REQUEST_OPERATION = "identity.mfa.admin-reset.request";
    public static final String APPROVE_OPERATION = "identity.mfa.admin-reset.approve";
    public static final String EXECUTE_OPERATION = "identity.mfa.admin-reset";
    private static final String PURPOSE = "identity-administration";
    private static final String JSON = "application/json";

    private final GovernedMutationExecutor governedMutations;
    private final MfaResetApprovalStore approvals;
    private final IdentitySecurityService identitySecurity;
    private final SecurityTokenPort tokens;
    private final MfaAdministrationPolicy policy;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public MfaAdministrationService(
            GovernedMutationExecutor governedMutations,
            MfaResetApprovalStore approvals,
            IdentitySecurityService identitySecurity,
            SecurityTokenPort tokens,
            MfaAdministrationPolicy policy,
            ObjectMapper objectMapper,
            Clock clock) {
        this.governedMutations = governedMutations;
        this.approvals = approvals;
        this.identitySecurity = identitySecurity;
        this.tokens = tokens;
        this.policy = policy;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public IdempotencyOutcome requestReset(RequestCommand command) {
        Objects.requireNonNull(command, "command");
        requireEnabled();
        var reason = requireReason(command.reason());
        var now = clock.instant();
        var authorization = authorization(
                command.organizationId(),
                command.actorId(),
                command.correlationId(),
                REQUEST_OPERATION,
                reason,
                command.recentAuthenticationAt(),
                null);
        var idempotency = idempotency(
                REQUEST_OPERATION,
                command.idempotencyKey(),
                now,
                command.organizationId().toString(),
                command.targetUserId().toString(),
                reason);

        return governedMutations.execute(authorization, idempotency, context -> {
            var approval = approvals.request(
                    context,
                    UuidV7Generator.randomUuid(),
                    command.targetUserId(),
                    reason,
                    now.plus(policy.approvalTtl()));
            return mutation(
                    approval,
                    reason,
                    "identity.mfa-admin-reset.requested",
                    201,
                    payload("targetUserId", approval.targetUserId()));
        });
    }

    public IdempotencyOutcome approveReset(ApproveCommand command) {
        Objects.requireNonNull(command, "command");
        requireEnabled();
        var reason = requireReason(command.reason());
        var now = clock.instant();
        var authorization = authorization(
                command.organizationId(),
                command.actorId(),
                command.correlationId(),
                APPROVE_OPERATION,
                reason,
                command.recentAuthenticationAt(),
                null);
        var idempotency = idempotency(
                APPROVE_OPERATION,
                command.idempotencyKey(),
                now,
                command.organizationId().toString(),
                command.targetUserId().toString(),
                command.approvalId().toString(),
                reason);

        return governedMutations.execute(authorization, idempotency, context -> {
            var approval = approvals.approve(
                    context,
                    command.approvalId(),
                    command.targetUserId(),
                    reason,
                    now);
            var payload = new LinkedHashMap<String, Object>();
            payload.put("requestedByUserId", approval.requestedByUserId());
            payload.put("targetUserId", approval.targetUserId());
            return mutation(
                    approval,
                    reason,
                    "identity.mfa-admin-reset.approved",
                    200,
                    payload);
        });
    }

    public IdempotencyOutcome executeReset(ExecuteCommand command) {
        Objects.requireNonNull(command, "command");
        requireEnabled();
        var reason = requireReason(command.reason());
        var now = clock.instant();
        var independentApproval = new IndependentApproval(
                command.approvalId(), "user", command.targetUserId(), command.idempotencyKey());
        var authorization = authorization(
                command.organizationId(),
                command.actorId(),
                command.correlationId(),
                EXECUTE_OPERATION,
                reason,
                command.recentAuthenticationAt(),
                independentApproval);
        var idempotency = idempotency(
                EXECUTE_OPERATION,
                command.idempotencyKey(),
                now,
                command.organizationId().toString(),
                command.targetUserId().toString(),
                command.approvalId().toString(),
                reason);

        var outcome = governedMutations.execute(authorization, idempotency, context -> {
            var approval = approvals.requireConsumed(
                    context,
                    command.approvalId(),
                    command.targetUserId(),
                    command.idempotencyKey());
            identitySecurity.administrativelyResetMfa(
                    command.targetUserId(), command.correlationId(), command.remoteAddress());
            var payload = new LinkedHashMap<String, Object>();
            payload.put("approvalId", approval.id());
            payload.put("approvedByUserId", approval.approvedByUserId());
            payload.put("requestedByUserId", approval.requestedByUserId());
            return new GovernedMutation(
                    new IdempotentResponse(
                            200,
                            JSON,
                            json(new MfaResetMutationResponse(
                                    approval.id(), approval.targetUserId(), "reset", approval.expiresAt()))),
                    evidence(
                            "identity.mfa-admin-reset.completed",
                            "user",
                            approval.targetUserId(),
                            reason,
                            payload));
        });
        if (!outcome.replayed()) {
            identitySecurity.completeAdministrativeResetSideEffects(command.targetUserId());
        }
        return outcome;
    }

    private GovernedMutation mutation(
            MfaResetApproval approval,
            String reason,
            String eventName,
            int statusCode,
            LinkedHashMap<String, Object> payload) {
        return new GovernedMutation(
                new IdempotentResponse(
                        statusCode,
                        JSON,
                        json(new MfaResetMutationResponse(
                                approval.id(),
                                approval.targetUserId(),
                                approval.status(),
                                approval.expiresAt()))),
                evidence(
                        eventName,
                        "authorization_approval",
                        approval.id(),
                        reason,
                        payload));
    }

    private GovernanceEvidence evidence(
            String eventName,
            String subjectType,
            UUID subjectId,
            String reason,
            LinkedHashMap<String, Object> payload) {
        var payloadJson = json(payload);
        return new GovernanceEvidence(
                new AuditRecord(eventName, 1, subjectType, subjectId, reason, payloadJson),
                new OutboxRecord(eventName, 1, subjectType, subjectId, payloadJson));
    }

    private TenantAuthorizationRequest authorization(
            UUID organizationId,
            UUID actorId,
            String correlationId,
            String operation,
            String reason,
            Instant recentAuthenticationAt,
            IndependentApproval approval) {
        return new TenantAuthorizationRequest(
                Objects.requireNonNull(organizationId, "organizationId"),
                new AuthenticatedActorContext(
                        Objects.requireNonNull(actorId, "actorId"), PURPOSE, correlationId),
                new OperationKey(operation),
                reason,
                recentAuthenticationAt,
                approval);
    }

    private IdempotencyCommand idempotency(
            String operation, String key, Instant now, String... values) {
        return new IdempotencyCommand(
                operation,
                key,
                requestHash(operation, values),
                now.plus(policy.idempotencyTtl()));
    }

    private String requestHash(String operation, String... values) {
        var canonical = new StringBuilder(operation.length() + 2).append(operation).append(';');
        for (var value : values) {
            canonical.append(value.length()).append(':').append(value).append(';');
        }
        return tokens.digest(canonical.toString());
    }

    private LinkedHashMap<String, Object> payload(String key, Object value) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put(key, value);
        return payload;
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("MFA administration JSON serialization failed", exception);
        }
    }

    private void requireEnabled() {
        if (!policy.enabled()) {
            throw new MfaAdministrationException(
                    MfaAdministrationException.Reason.UNAVAILABLE,
                    "Governed MFA administration is not enabled in this environment.");
        }
    }

    private static String requireReason(String value) {
        var normalized = value == null ? "" : value.strip();
        if (normalized.isEmpty() || normalized.length() > 2000) {
            throw new MfaAdministrationException(
                    MfaAdministrationException.Reason.INVALID_REQUEST,
                    "A reason of 1 to 2000 characters is required.");
        }
        return normalized;
    }

    public record RequestCommand(
            UUID organizationId,
            UUID actorId,
            UUID targetUserId,
            String reason,
            Instant recentAuthenticationAt,
            String correlationId,
            String idempotencyKey) {
        public RequestCommand {
            Objects.requireNonNull(targetUserId, "targetUserId");
        }
    }

    public record ApproveCommand(
            UUID organizationId,
            UUID actorId,
            UUID targetUserId,
            UUID approvalId,
            String reason,
            Instant recentAuthenticationAt,
            String correlationId,
            String idempotencyKey) {
        public ApproveCommand {
            Objects.requireNonNull(targetUserId, "targetUserId");
            Objects.requireNonNull(approvalId, "approvalId");
        }
    }

    public record ExecuteCommand(
            UUID organizationId,
            UUID actorId,
            UUID targetUserId,
            UUID approvalId,
            String reason,
            Instant recentAuthenticationAt,
            String correlationId,
            String idempotencyKey,
            String remoteAddress) {
        public ExecuteCommand {
            Objects.requireNonNull(targetUserId, "targetUserId");
            Objects.requireNonNull(approvalId, "approvalId");
        }
    }

    public record MfaResetMutationResponse(
            UUID approvalId, UUID targetUserId, String status, Instant expiresAt) {}
}
