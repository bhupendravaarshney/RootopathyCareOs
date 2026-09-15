package com.rootopathy.careos.identity.infrastructure.persistence;

import com.rootopathy.careos.governance.application.GovernanceEvidenceOperations;
import com.rootopathy.careos.governance.domain.GovernanceEvidence;
import com.rootopathy.careos.identity.application.InvitationAcceptanceOperations;
import com.rootopathy.careos.identity.application.InvitationException;
import com.rootopathy.careos.identity.application.SecurityTokenPort;
import com.rootopathy.careos.identity.domain.InvitationAcceptance;
import com.rootopathy.careos.shared.domain.UuidV7Generator;
import com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public final class PostgresInvitationAcceptanceOperations
        implements InvitationAcceptanceOperations {
    private static final String PURPOSE = "invitation-acceptance";
    private static final String OPERATION = "organization.invitation.accept";

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final GovernanceEvidenceOperations evidenceOperations;
    private final SecurityTokenPort tokens;
    private final boolean referencePolicyEnabled;

    public PostgresInvitationAcceptanceOperations(
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager,
            GovernanceEvidenceOperations evidenceOperations,
            SecurityTokenPort tokens,
            @Value("${careos.authorization.reference-policy-enabled:false}")
                    boolean referencePolicyEnabled) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.evidenceOperations = evidenceOperations;
        this.tokens = tokens;
        this.referencePolicyEnabled = referencePolicyEnabled;
    }

    @Override
    public InvitationAcceptance accept(
            AcceptanceCommand command,
            Function<InvitationAcceptance, GovernanceEvidence> evidenceFactory) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(evidenceFactory, "evidenceFactory");
        return transactionTemplate.execute(status -> acceptInTransaction(command, evidenceFactory));
    }

    private InvitationAcceptance acceptInTransaction(
            AcceptanceCommand command,
            Function<InvitationAcceptance, GovernanceEvidence> evidenceFactory) {
        var lookup = lookup(command.tokenHash());
        if (lookup.targetUserId() != null && command.authenticatedUserId() == null) {
            throw new InvitationException(
                    InvitationException.Reason.AUTHENTICATION_REQUIRED,
                    "Sign in to the invited account before accepting this invitation.");
        }
        if (lookup.targetUserId() != null
                && !lookup.targetUserId().equals(command.authenticatedUserId())) {
            throw accountMismatch();
        }

        var actorId = command.authenticatedUserId() == null
                ? UuidV7Generator.randomUuid()
                : command.authenticatedUserId();
        var context = new AuthorizedTenantContext(
                lookup.organizationId(), actorId, PURPOSE, command.correlationId());
        bindContext(context);
        var invitation = lockInvitation(lookup);
        var account = findAccount(invitation.email());
        var existingAccount = account != null;

        if (existingAccount) {
            requireMatchingAuthenticatedAccount(command, invitation, account);
        } else {
            if (command.authenticatedUserId() != null) {
                throw accountMismatch();
            }
            if (command.passwordHash() == null || command.passwordHash().isBlank()) {
                throw new InvitationException(
                        InvitationException.Reason.PASSWORD_REQUIRED,
                        "A password is required to create the invited account.");
            }
            createAccount(actorId, invitation, command.passwordHash());
        }

        createMembership(invitation, actorId);
        var updated = jdbcTemplate.update(
                """
                UPDATE invitations
                SET status = 'accepted', accepted_by = ?, acceptance_correlation_id = ?,
                    accepted_existing_account = ?, lock_version = lock_version + 1
                WHERE id = ? AND organization_id = ? AND status = 'pending'
                """,
                actorId,
                context.correlationId(),
                existingAccount,
                invitation.id(),
                invitation.organizationId());
        if (updated != 1) {
            throw invalidToken();
        }

        var acceptance = new InvitationAcceptance(
                invitation.id(),
                invitation.organizationId(),
                actorId,
                invitation.roleKey(),
                tokens.digest(invitation.email()),
                existingAccount);
        evidenceOperations.record(context, evidenceFactory.apply(acceptance));
        return acceptance;
    }

    private TokenLookup lookup(String tokenHash) {
        if (tokenHash == null || !tokenHash.matches("[0-9a-f]{64}")) {
            throw invalidToken();
        }
        return jdbcTemplate.query(
                        """
                        SELECT invitation_id, organization_id, target_user_id, expires_at
                        FROM careos_lookup_invitation_token(?)
                        """,
                        (resultSet, rowNumber) -> new TokenLookup(
                                resultSet.getObject("invitation_id", UUID.class),
                                resultSet.getObject("organization_id", UUID.class),
                                resultSet.getObject("target_user_id", UUID.class)),
                        tokenHash)
                .stream()
                .findFirst()
                .orElseThrow(PostgresInvitationAcceptanceOperations::invalidToken);
    }

    private PendingInvitation lockInvitation(TokenLookup lookup) {
        return jdbcTemplate.query(
                        """
                        SELECT id, organization_id, email, display_name, role_key
                        FROM invitations
                        WHERE id = ? AND organization_id = ? AND status = 'pending'
                          AND expires_at > clock_timestamp()
                        FOR UPDATE
                        """,
                        PostgresInvitationAcceptanceOperations::mapInvitation,
                        lookup.invitationId(),
                        lookup.organizationId())
                .stream()
                .findFirst()
                .orElseThrow(PostgresInvitationAcceptanceOperations::invalidToken);
    }

    private ExistingAccount findAccount(String normalizedEmail) {
        return jdbcTemplate.query(
                        """
                        SELECT users.id, users.email, users.status,
                               EXISTS (
                                   SELECT 1 FROM password_credentials credentials
                                   WHERE credentials.user_id = users.id
                               ) AS has_password
                        FROM users
                        WHERE lower(users.email) = lower(?)
                        FOR UPDATE OF users
                        """,
                        (resultSet, rowNumber) -> new ExistingAccount(
                                resultSet.getObject("id", UUID.class),
                                resultSet.getString("email"),
                                resultSet.getString("status"),
                                resultSet.getBoolean("has_password")),
                        normalizedEmail)
                .stream()
                .findFirst()
                .orElse(null);
    }

    private static void requireMatchingAuthenticatedAccount(
            AcceptanceCommand command,
            PendingInvitation invitation,
            ExistingAccount account) {
        if (command.authenticatedUserId() == null) {
            throw new InvitationException(
                    InvitationException.Reason.AUTHENTICATION_REQUIRED,
                    "Sign in to the invited account before accepting this invitation.");
        }
        if (!account.id().equals(command.authenticatedUserId())
                || command.authenticatedEmail() == null
                || !account.email().equalsIgnoreCase(command.authenticatedEmail())
                || !invitation.email().equalsIgnoreCase(command.authenticatedEmail())) {
            throw accountMismatch();
        }
        if (!"active".equals(account.status()) || !account.hasPassword()) {
            throw new InvitationException(
                    InvitationException.Reason.ACCOUNT_UNAVAILABLE,
                    "The invited account is unavailable.");
        }
    }

    private void createAccount(
            UUID userId, PendingInvitation invitation, String passwordHash) {
        try {
            jdbcTemplate.update(
                    """
                    INSERT INTO users (id, email, display_name, status)
                    VALUES (?, ?, ?, 'active')
                    """,
                    userId,
                    invitation.email(),
                    invitation.displayName());
            jdbcTemplate.update(
                    """
                    INSERT INTO password_credentials (user_id, password_hash)
                    VALUES (?, ?)
                    """,
                    userId,
                    passwordHash);
        } catch (DuplicateKeyException exception) {
            throw new InvitationException(
                    InvitationException.Reason.AUTHENTICATION_REQUIRED,
                    "Sign in to the invited account before accepting this invitation.");
        }
    }

    private void createMembership(PendingInvitation invitation, UUID actorId) {
        try {
            jdbcTemplate.update(
                    """
                    INSERT INTO organization_memberships
                        (id, organization_id, user_id, role_key, status)
                    VALUES (?, ?, ?, ?, 'active')
                    """,
                    UuidV7Generator.randomUuid(),
                    invitation.organizationId(),
                    actorId,
                    invitation.roleKey());
        } catch (DuplicateKeyException exception) {
            throw new InvitationException(
                    InvitationException.Reason.ALREADY_MEMBER,
                    "The invited account already has this organization role.");
        }
    }

    private void bindContext(AuthorizedTenantContext context) {
        setTransactionLocal("app.current_organization_id", context.organizationId().toString());
        setTransactionLocal("app.current_actor_id", context.actorId().toString());
        setTransactionLocal("app.current_actor_kind", "user");
        setTransactionLocal("app.current_purpose", context.purpose());
        setTransactionLocal("app.current_correlation_id", context.correlationId());
        setTransactionLocal("app.current_operation_key", OPERATION);
        setTransactionLocal("app.current_authorization_reason", "");
        setTransactionLocal(
                "app.reference_authorization_policy_enabled",
                Boolean.toString(referencePolicyEnabled));
    }

    private void setTransactionLocal(String setting, String value) {
        jdbcTemplate.queryForObject("select set_config(?, ?, true)", String.class, setting, value);
    }

    private static PendingInvitation mapInvitation(ResultSet resultSet, int rowNumber)
            throws SQLException {
        return new PendingInvitation(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("organization_id", UUID.class),
                resultSet.getString("email"),
                resultSet.getString("display_name"),
                resultSet.getString("role_key"));
    }

    private static InvitationException invalidToken() {
        return new InvitationException(
                InvitationException.Reason.INVALID_OR_EXPIRED_TOKEN,
                "The invitation token is invalid or expired.");
    }

    private static InvitationException accountMismatch() {
        return new InvitationException(
                InvitationException.Reason.ACCOUNT_MISMATCH,
                "This invitation cannot be linked to the authenticated account.");
    }

    private record TokenLookup(UUID invitationId, UUID organizationId, UUID targetUserId) {}

    private record PendingInvitation(
            UUID id, UUID organizationId, String email, String displayName, String roleKey) {}

    private record ExistingAccount(UUID id, String email, String status, boolean hasPassword) {}
}
