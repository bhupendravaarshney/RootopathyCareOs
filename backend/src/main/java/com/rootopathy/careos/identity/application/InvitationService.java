package com.rootopathy.careos.identity.application;

import com.rootopathy.careos.governance.application.GovernedMutationExecutor;
import com.rootopathy.careos.governance.domain.AuditRecord;
import com.rootopathy.careos.governance.domain.GovernanceEvidence;
import com.rootopathy.careos.governance.domain.GovernedMutation;
import com.rootopathy.careos.governance.domain.IdempotencyCommand;
import com.rootopathy.careos.governance.domain.IdempotencyOutcome;
import com.rootopathy.careos.governance.domain.IdempotentResponse;
import com.rootopathy.careos.governance.domain.OutboxRecord;
import com.rootopathy.careos.identity.application.InvitationAcceptanceOperations.AcceptanceCommand;
import com.rootopathy.careos.identity.domain.InvitationAcceptance;
import com.rootopathy.careos.identity.domain.OrganizationInvitation;
import com.rootopathy.careos.shared.domain.UuidV7Generator;
import com.rootopathy.careos.tenancy.domain.AuthenticatedActorContext;
import com.rootopathy.careos.tenancy.domain.OperationKey;
import com.rootopathy.careos.tenancy.domain.TenantAuthorizationRequest;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public final class InvitationService {
    public static final String ISSUE_OPERATION = "organization.invitation.issue";
    public static final String REVOKE_OPERATION = "organization.invitation.revoke";
    private static final String PURPOSE = "organization-administration";
    private static final String JSON = "application/json";
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private static final Pattern ROLE_KEY =
            Pattern.compile("[a-z][a-z0-9]*([._:-][a-z0-9]+)*");

    private final GovernedMutationExecutor governedMutations;
    private final InvitationStore invitations;
    private final InvitationAcceptanceOperations acceptanceOperations;
    private final SecurityTokenPort tokens;
    private final PasswordHashingPort passwordHasher;
    private final SecurityNotificationPort notifications;
    private final SecurityRateLimitPort rateLimits;
    private final InvitationPolicy policy;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public InvitationService(
            GovernedMutationExecutor governedMutations,
            InvitationStore invitations,
            InvitationAcceptanceOperations acceptanceOperations,
            SecurityTokenPort tokens,
            PasswordHashingPort passwordHasher,
            SecurityNotificationPort notifications,
            SecurityRateLimitPort rateLimits,
            InvitationPolicy policy,
            ObjectMapper objectMapper,
            Clock clock) {
        this.governedMutations = governedMutations;
        this.invitations = invitations;
        this.acceptanceOperations = acceptanceOperations;
        this.tokens = tokens;
        this.passwordHasher = passwordHasher;
        this.notifications = notifications;
        this.rateLimits = rateLimits;
        this.policy = policy;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public IdempotencyOutcome issue(IssueCommand command) {
        Objects.requireNonNull(command, "command");
        requireEnabled();
        var normalizedEmail = requireEmail(command.email());
        var displayName = requireDisplayName(command.displayName());
        var roleKey = requireRoleKey(command.roleKey());
        var reason = requireReason(command.reason());
        var now = clock.instant();
        var expiresAt = now.plus(policy.tokenTtl());
        var rawToken = tokens.newOpaqueToken();
        var tokenHash = tokens.digest(rawToken);
        var authorization = authorization(
                command.organizationId(),
                command.actorId(),
                command.correlationId(),
                ISSUE_OPERATION,
                reason,
                command.recentAuthenticationAt());
        var idempotency = new IdempotencyCommand(
                ISSUE_OPERATION,
                command.idempotencyKey(),
                requestHash(
                        ISSUE_OPERATION,
                        command.organizationId().toString(),
                        normalizedEmail,
                        displayName,
                        roleKey,
                        reason),
                now.plus(policy.idempotencyTtl()));

        return governedMutations.execute(authorization, idempotency, context -> {
            var invitation = invitations.issue(
                    context,
                    UuidV7Generator.randomUuid(),
                    normalizedEmail,
                    displayName,
                    roleKey,
                    tokenHash,
                    expiresAt,
                    reason);
            notifications.sendInvitation(invitation.email(), rawToken, invitation.expiresAt());
            var response = new InvitationMutationResponse(
                    invitation.id(), invitation.status(), invitation.roleKey(), invitation.expiresAt());
            return governedMutation(invitation, reason, "issued", 201, response);
        });
    }

    public IdempotencyOutcome revoke(RevokeCommand command) {
        Objects.requireNonNull(command, "command");
        requireEnabled();
        var reason = requireReason(command.reason());
        var now = clock.instant();
        var authorization = authorization(
                command.organizationId(),
                command.actorId(),
                command.correlationId(),
                REVOKE_OPERATION,
                reason,
                command.recentAuthenticationAt());
        var idempotency = new IdempotencyCommand(
                REVOKE_OPERATION,
                command.idempotencyKey(),
                requestHash(
                        REVOKE_OPERATION,
                        command.organizationId().toString(),
                        command.invitationId().toString(),
                        reason),
                now.plus(policy.idempotencyTtl()));

        return governedMutations.execute(authorization, idempotency, context -> {
            var invitation = invitations.revoke(context, command.invitationId(), reason);
            var response = new InvitationMutationResponse(
                    invitation.id(), invitation.status(), invitation.roleKey(), invitation.expiresAt());
            return governedMutation(invitation, reason, "revoked", 200, response);
        });
    }

    public InvitationAcceptance accept(AcceptCommand command) {
        Objects.requireNonNull(command, "command");
        requireEnabled();
        var rawToken = requireToken(command.token());
        var tokenHash = tokens.digest(rawToken);
        var remoteHash = tokens.digest(normalizeRemoteAddress(command.remoteAddress()));
        var tokenAllowed = rateLimits.consume(
                "invitation-token",
                tokenHash,
                policy.acceptanceAttemptLimit(),
                policy.acceptanceAttemptWindow());
        var remoteAllowed = rateLimits.consume(
                "invitation-remote",
                remoteHash,
                policy.acceptanceAttemptLimit(),
                policy.acceptanceAttemptWindow());
        if (!tokenAllowed || !remoteAllowed) {
            throw new InvitationException(
                    InvitationException.Reason.THROTTLED,
                    "Invitation acceptance is temporarily unavailable. Try again later.");
        }

        String passwordHash = null;
        if (command.authenticatedUserId() != null) {
            if (command.newPassword() != null && !command.newPassword().isBlank()) {
                throw new InvitationException(
                        InvitationException.Reason.INVALID_REQUEST,
                        "A password cannot be supplied while linking an authenticated account.");
            }
        } else if (command.newPassword() != null && !command.newPassword().isBlank()) {
            try {
                IdentitySecurityService.validateNewPassword(command.newPassword());
            } catch (IdentitySecurityException exception) {
                throw new InvitationException(
                        InvitationException.Reason.INVALID_REQUEST, exception.getMessage());
            }
            passwordHash = passwordHasher.hash(command.newPassword());
        }

        var acceptance = acceptanceOperations.accept(
                new AcceptanceCommand(
                        tokenHash,
                        command.authenticatedUserId(),
                        command.authenticatedEmail() == null
                                ? null
                                : IdentitySecurityService.normalizeEmail(command.authenticatedEmail()),
                        passwordHash,
                        command.correlationId()),
                accepted -> {
                    var payload = new LinkedHashMap<String, Object>();
                    payload.put("accountLink", accepted.accountLink());
                    payload.put("emailHash", accepted.emailHash());
                    payload.put("roleKey", accepted.roleKey());
                    payload.put("userId", accepted.userId());
                    var payloadJson = json(payload);
                    return new GovernanceEvidence(
                            new AuditRecord(
                                    "organization.invitation.accepted",
                                    1,
                                    "invitation",
                                    accepted.invitationId(),
                                    null,
                                    payloadJson),
                            new OutboxRecord(
                                    "organization.invitation.accepted",
                                    1,
                                    "invitation",
                                    accepted.invitationId(),
                                    payloadJson));
                });
        rateLimits.clear("invitation-token", tokenHash);
        rateLimits.clear("invitation-remote", remoteHash);
        return acceptance;
    }

    private GovernedMutation governedMutation(
            OrganizationInvitation invitation,
            String reason,
            String transition,
            int statusCode,
            InvitationMutationResponse response) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("emailHash", tokens.digest(invitation.email()));
        if ("issued".equals(transition)) {
            payload.put("expiresAt", invitation.expiresAt());
        }
        payload.put("roleKey", invitation.roleKey());
        var payloadJson = json(payload);
        var eventName = "organization.invitation." + transition;
        return new GovernedMutation(
                new IdempotentResponse(statusCode, JSON, json(response)),
                new GovernanceEvidence(
                        new AuditRecord(
                                eventName,
                                1,
                                "invitation",
                                invitation.id(),
                                reason,
                                payloadJson),
                        new OutboxRecord(
                                eventName,
                                1,
                                "invitation",
                                invitation.id(),
                                payloadJson)));
    }

    private TenantAuthorizationRequest authorization(
            UUID organizationId,
            UUID actorId,
            String correlationId,
            String operation,
            String reason,
            Instant recentAuthenticationAt) {
        return new TenantAuthorizationRequest(
                Objects.requireNonNull(organizationId, "organizationId"),
                new AuthenticatedActorContext(
                        Objects.requireNonNull(actorId, "actorId"), PURPOSE, correlationId),
                new OperationKey(operation),
                reason,
                recentAuthenticationAt);
    }

    private String requestHash(String... values) {
        var canonical = new StringBuilder();
        for (var value : values) {
            canonical.append(value.length()).append(':').append(value).append(';');
        }
        return tokens.digest(canonical.toString());
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Invitation JSON serialization failed", exception);
        }
    }

    private void requireEnabled() {
        if (!policy.enabled()) {
            throw new InvitationException(
                    InvitationException.Reason.UNAVAILABLE,
                    "Governed invitations are not enabled in this environment.");
        }
    }

    private static String requireEmail(String value) {
        var normalized = IdentitySecurityService.normalizeEmail(value);
        if (normalized.length() > 320 || !EMAIL.matcher(normalized).matches()) {
            throw new InvitationException(
                    InvitationException.Reason.INVALID_REQUEST,
                    "The invitation email address is invalid.");
        }
        return normalized;
    }

    private static String requireDisplayName(String value) {
        var normalized = value == null ? "" : value.strip();
        if (normalized.isEmpty()
                || normalized.length() > 160
                || normalized.codePoints().anyMatch(Character::isISOControl)) {
            throw new InvitationException(
                    InvitationException.Reason.INVALID_REQUEST,
                    "The invitation display name is invalid.");
        }
        return normalized;
    }

    private static String requireRoleKey(String value) {
        if (value == null || value.length() > 100 || !ROLE_KEY.matcher(value).matches()) {
            throw new InvitationException(
                    InvitationException.Reason.INVALID_REQUEST,
                    "The invitation role key is invalid.");
        }
        return value;
    }

    private static String requireReason(String value) {
        var normalized = value == null ? "" : value.strip();
        if (normalized.isEmpty() || normalized.length() > 2_000) {
            throw new InvitationException(
                    InvitationException.Reason.INVALID_REQUEST,
                    "A reason of 1 to 2000 characters is required.");
        }
        return normalized;
    }

    private static String requireToken(String value) {
        if (value == null || value.length() < 32 || value.length() > 512) {
            throw new InvitationException(
                    InvitationException.Reason.INVALID_OR_EXPIRED_TOKEN,
                    "The invitation token is invalid or expired.");
        }
        return value;
    }

    private static String normalizeRemoteAddress(String value) {
        return value == null || value.isBlank() ? "unavailable" : value;
    }

    public record IssueCommand(
            UUID organizationId,
            UUID actorId,
            String email,
            String displayName,
            String roleKey,
            String reason,
            Instant recentAuthenticationAt,
            String correlationId,
            String idempotencyKey) {}

    public record RevokeCommand(
            UUID organizationId,
            UUID actorId,
            UUID invitationId,
            String reason,
            Instant recentAuthenticationAt,
            String correlationId,
            String idempotencyKey) {
        public RevokeCommand {
            Objects.requireNonNull(invitationId, "invitationId");
        }
    }

    public record AcceptCommand(
            String token,
            String newPassword,
            UUID authenticatedUserId,
            String authenticatedEmail,
            String correlationId,
            String remoteAddress) {}

    public record InvitationMutationResponse(
            UUID invitationId, String status, String roleKey, Instant expiresAt) {}
}
