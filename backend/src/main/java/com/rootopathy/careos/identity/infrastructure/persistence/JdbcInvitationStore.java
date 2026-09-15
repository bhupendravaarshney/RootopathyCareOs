package com.rootopathy.careos.identity.infrastructure.persistence;

import com.rootopathy.careos.identity.application.InvitationException;
import com.rootopathy.careos.identity.application.InvitationStore;
import com.rootopathy.careos.identity.domain.OrganizationInvitation;
import com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext;
import com.rootopathy.careos.tenancy.infrastructure.AuthorizedTenantTransactionGuard;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcInvitationStore implements InvitationStore {
    private final JdbcTemplate jdbcTemplate;

    public JdbcInvitationStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public OrganizationInvitation issue(
            AuthorizedTenantContext context,
            UUID invitationId,
            String normalizedEmail,
            String displayName,
            String roleKey,
            String tokenHash,
            Instant expiresAt,
            String reason) {
        AuthorizedTenantTransactionGuard.requireWritable(jdbcTemplate, context);
        if (!canDelegate(context, roleKey)) {
            throw new InvitationException(
                    InvitationException.Reason.ROLE_NOT_ASSIGNABLE,
                    "The requested invitation role is not assignable by this account.");
        }
        try {
            return jdbcTemplate.query(
                            """
                            INSERT INTO invitations
                                (id, organization_id, email, display_name, role_key, token_hash,
                                 expires_at, invited_by, correlation_id, issued_reason)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            RETURNING id, organization_id, email, display_name, role_key,
                                      status, expires_at
                            """,
                            JdbcInvitationStore::mapInvitation,
                            invitationId,
                            context.organizationId(),
                            normalizedEmail,
                            displayName,
                            roleKey,
                            tokenHash,
                            Timestamp.from(expiresAt),
                            context.actorId(),
                            context.correlationId(),
                            reason)
                    .stream()
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("Invitation insert returned no row"));
        } catch (DuplicateKeyException exception) {
            throw new InvitationException(
                    InvitationException.Reason.ALREADY_PENDING,
                    "A pending invitation already exists for this email address.");
        }
    }

    @Override
    public OrganizationInvitation revoke(
            AuthorizedTenantContext context, UUID invitationId, String reason) {
        AuthorizedTenantTransactionGuard.requireWritable(jdbcTemplate, context);
        return jdbcTemplate.query(
                        """
                        UPDATE invitations
                        SET status = 'revoked', revoked_by = ?, revocation_reason = ?,
                            revocation_correlation_id = ?, lock_version = lock_version + 1
                        WHERE id = ? AND organization_id = ? AND status = 'pending'
                        RETURNING id, organization_id, email, display_name, role_key,
                                  status, expires_at
                        """,
                        JdbcInvitationStore::mapInvitation,
                        context.actorId(),
                        reason,
                        context.correlationId(),
                        invitationId,
                        context.organizationId())
                .stream()
                .findFirst()
                .orElseThrow(() -> new InvitationException(
                        InvitationException.Reason.INVITATION_NOT_FOUND,
                        "The pending invitation is unavailable."));
    }

    private boolean canDelegate(AuthorizedTenantContext context, String targetRoleKey) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                """
                SELECT EXISTS (
                    SELECT 1
                    FROM organization_memberships membership
                    JOIN authorization_roles delegator_role
                      ON delegator_role.role_key = membership.role_key
                    JOIN authorization_role_delegations delegation
                      ON delegation.delegator_role_key = delegator_role.role_key
                     AND delegation.target_role_key = ?
                     AND delegation.registry_version = delegator_role.registry_version
                    JOIN authorization_roles target_role
                      ON target_role.role_key = delegation.target_role_key
                     AND target_role.registry_version = delegation.registry_version
                    WHERE membership.organization_id = ?
                      AND membership.user_id = ?
                      AND membership.status = 'active'
                      AND membership.effective_from <= clock_timestamp()
                      AND (membership.effective_to IS NULL
                           OR membership.effective_to > clock_timestamp())
                      AND target_role.invitation_assignable
                      AND (delegator_role.status = 'active'
                           OR (coalesce(nullif(current_setting(
                                   'app.reference_authorization_policy_enabled', true), '')::boolean,
                                   false)
                               AND delegator_role.status = 'reference'))
                      AND (target_role.status = 'active'
                           OR (coalesce(nullif(current_setting(
                                   'app.reference_authorization_policy_enabled', true), '')::boolean,
                                   false)
                               AND target_role.status = 'reference'))
                )
                """,
                Boolean.class,
                targetRoleKey,
                context.organizationId(),
                context.actorId()));
    }

    private static OrganizationInvitation mapInvitation(ResultSet resultSet, int rowNumber)
            throws SQLException {
        return new OrganizationInvitation(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("organization_id", UUID.class),
                resultSet.getString("email"),
                resultSet.getString("display_name"),
                resultSet.getString("role_key"),
                resultSet.getString("status"),
                resultSet.getTimestamp("expires_at").toInstant());
    }
}
