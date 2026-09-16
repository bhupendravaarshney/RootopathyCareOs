package com.rootopathy.careos.identity.infrastructure.persistence;

import com.rootopathy.careos.identity.application.MfaAdministrationException;
import com.rootopathy.careos.identity.application.MfaAdministrationException.Reason;
import com.rootopathy.careos.identity.application.MfaResetApprovalStore;
import com.rootopathy.careos.identity.domain.MfaResetApproval;
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
public class JdbcMfaResetApprovalStore implements MfaResetApprovalStore {
    private static final String TARGET_OPERATION = "identity.mfa.admin-reset.execute";

    private final JdbcTemplate jdbcTemplate;

    public JdbcMfaResetApprovalStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public MfaResetApproval request(
            AuthorizedTenantContext context,
            UUID approvalId,
            UUID targetUserId,
            String reason,
            Instant expiresAt) {
        AuthorizedTenantTransactionGuard.requireWritable(jdbcTemplate, context);
        if (context.actorId().equals(targetUserId)) {
            throw new MfaAdministrationException(
                    Reason.INVALID_REQUEST,
                    "An administrator cannot request this reset for their own account.");
        }
        var eligible = Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                """
                SELECT EXISTS (
                    SELECT 1
                    FROM organization_memberships membership
                    JOIN users ON users.id = membership.user_id
                    JOIN mfa_methods method
                      ON method.user_id = users.id AND method.status = 'enabled'
                    WHERE membership.organization_id = ?
                      AND membership.user_id = ?
                      AND membership.status = 'active'
                      AND membership.effective_from <= clock_timestamp()
                      AND (membership.effective_to IS NULL
                           OR membership.effective_to > clock_timestamp())
                      AND users.status = 'active'
                )
                """,
                Boolean.class,
                context.organizationId(),
                targetUserId));
        if (!eligible) {
            var targetExists = Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                    """
                    SELECT EXISTS (
                        SELECT 1
                        FROM organization_memberships membership
                        JOIN users ON users.id = membership.user_id
                        WHERE membership.organization_id = ?
                          AND membership.user_id = ?
                          AND membership.status = 'active'
                          AND membership.effective_from <= clock_timestamp()
                          AND (membership.effective_to IS NULL
                               OR membership.effective_to > clock_timestamp())
                          AND users.status = 'active'
                    )
                    """,
                    Boolean.class,
                    context.organizationId(),
                    targetUserId));
            throw new MfaAdministrationException(
                    targetExists ? Reason.TARGET_MFA_NOT_ENABLED : Reason.TARGET_UNAVAILABLE,
                    targetExists
                            ? "The target account does not have enabled MFA."
                            : "The target account is unavailable.");
        }
        jdbcTemplate.update(
                """
                UPDATE authorization_approval_requests
                SET status = 'expired', updated_at = clock_timestamp(),
                    lock_version = lock_version + 1
                WHERE organization_id = ?
                  AND operation_key = ?
                  AND subject_type = 'user'
                  AND subject_id = ?
                  AND status IN ('pending', 'approved')
                  AND expires_at <= clock_timestamp()
                """,
                context.organizationId(),
                TARGET_OPERATION,
                targetUserId);
        try {
            return jdbcTemplate.query(
                            """
                            INSERT INTO authorization_approval_requests
                                (id, organization_id, operation_key, subject_type, subject_id,
                                 requested_by_user_id, request_reason,
                                 request_correlation_id, expires_at)
                            VALUES (?, ?, ?, 'user', ?, ?, ?, ?, ?)
                            RETURNING id, organization_id, subject_id, requested_by_user_id,
                                      decided_by_user_id, status, request_reason, expires_at
                            """,
                            JdbcMfaResetApprovalStore::map,
                            approvalId,
                            context.organizationId(),
                            TARGET_OPERATION,
                            targetUserId,
                            context.actorId(),
                            reason,
                            context.correlationId(),
                            Timestamp.from(expiresAt))
                    .stream()
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("Approval insert returned no row"));
        } catch (DuplicateKeyException exception) {
            throw new MfaAdministrationException(
                    Reason.APPROVAL_ALREADY_OPEN,
                    "An unexpired MFA reset approval is already open for this account.");
        }
    }

    @Override
    public MfaResetApproval approve(
            AuthorizedTenantContext context,
            UUID approvalId,
            UUID targetUserId,
            String decisionReason,
            Instant decidedAt) {
        AuthorizedTenantTransactionGuard.requireWritable(jdbcTemplate, context);
        return jdbcTemplate.query(
                        """
                        UPDATE authorization_approval_requests
                        SET status = 'approved', decided_by_user_id = ?,
                            decision_reason = ?, decision_correlation_id = ?,
                            decided_at = ?, updated_at = ?, lock_version = lock_version + 1
                        WHERE id = ?
                          AND organization_id = ?
                          AND operation_key = ?
                          AND subject_type = 'user'
                          AND subject_id = ?
                          AND requested_by_user_id <> ?
                          AND subject_id <> ?
                          AND status = 'pending'
                          AND expires_at > ?
                        RETURNING id, organization_id, subject_id, requested_by_user_id,
                                  decided_by_user_id, status, request_reason, expires_at
                        """,
                        JdbcMfaResetApprovalStore::map,
                        context.actorId(),
                        decisionReason,
                        context.correlationId(),
                        Timestamp.from(decidedAt),
                        Timestamp.from(decidedAt),
                        approvalId,
                        context.organizationId(),
                        TARGET_OPERATION,
                        targetUserId,
                        context.actorId(),
                        context.actorId(),
                        Timestamp.from(decidedAt))
                .stream()
                .findFirst()
                .orElseThrow(() -> new MfaAdministrationException(
                        Reason.APPROVAL_UNAVAILABLE,
                        "The pending MFA reset approval is unavailable."));
    }

    @Override
    public MfaResetApproval requireConsumed(
            AuthorizedTenantContext context,
            UUID approvalId,
            UUID targetUserId,
            String idempotencyKey) {
        AuthorizedTenantTransactionGuard.requireWritable(jdbcTemplate, context);
        return jdbcTemplate.query(
                        """
                        SELECT id, organization_id, subject_id, requested_by_user_id,
                               decided_by_user_id, status, request_reason, expires_at
                        FROM authorization_approval_requests
                        WHERE id = ?
                          AND organization_id = ?
                          AND operation_key = ?
                          AND subject_type = 'user'
                          AND subject_id = ?
                          AND status = 'consumed'
                          AND consumed_by_user_id = ?
                          AND consumed_idempotency_key = ?
                        """,
                        JdbcMfaResetApprovalStore::map,
                        approvalId,
                        context.organizationId(),
                        TARGET_OPERATION,
                        targetUserId,
                        context.actorId(),
                        idempotencyKey)
                .stream()
                .findFirst()
                .orElseThrow(() -> new MfaAdministrationException(
                        Reason.APPROVAL_UNAVAILABLE,
                        "The approved MFA reset is unavailable."));
    }

    private static MfaResetApproval map(ResultSet resultSet, int rowNumber) throws SQLException {
        return new MfaResetApproval(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("organization_id", UUID.class),
                resultSet.getObject("subject_id", UUID.class),
                resultSet.getObject("requested_by_user_id", UUID.class),
                resultSet.getObject("decided_by_user_id", UUID.class),
                resultSet.getString("status"),
                resultSet.getString("request_reason"),
                resultSet.getTimestamp("expires_at").toInstant());
    }
}
