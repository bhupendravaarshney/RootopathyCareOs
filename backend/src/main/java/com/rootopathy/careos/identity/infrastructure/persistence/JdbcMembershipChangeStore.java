package com.rootopathy.careos.identity.infrastructure.persistence;

import com.rootopathy.careos.identity.application.MembershipAdministrationException;
import com.rootopathy.careos.identity.application.MembershipAdministrationException.Reason;
import com.rootopathy.careos.identity.application.MembershipChangeStore;
import com.rootopathy.careos.identity.domain.MembershipChange;
import com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext;
import com.rootopathy.careos.tenancy.infrastructure.AuthorizedTenantTransactionGuard;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcMembershipChangeStore implements MembershipChangeStore {
    private static final String TARGET_OPERATION = "access.membership.change";
    private static final String OWNER_TRANSFER_TARGET_OPERATION = "access.owner-transfer.execute";

    private final JdbcTemplate jdbcTemplate;

    public JdbcMembershipChangeStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public MembershipChange request(
            AuthorizedTenantContext context,
            UUID approvalId,
            UUID membershipId,
            String changeType,
            String toRoleKey,
            long expectedLockVersion,
            String reason,
            Instant expiresAt) {
        AuthorizedTenantTransactionGuard.requireWritable(jdbcTemplate, context);
        var target = jdbcTemplate.query(
                        """
                        SELECT membership.user_id, membership.role_key, membership.lock_version,
                               role.final_owner
                        FROM organization_memberships membership
                        JOIN users target_user ON target_user.id = membership.user_id
                        JOIN authorization_roles role ON role.role_key = membership.role_key
                        WHERE membership.organization_id = ?
                          AND membership.id = ?
                          AND membership.status = 'active'
                          AND membership.effective_from <= clock_timestamp()
                          AND (membership.effective_to IS NULL
                               OR membership.effective_to > clock_timestamp())
                          AND target_user.status = 'active'
                          AND role.registry_version = 'm1-candidate-1'
                          AND role.status = 'active'
                          AND role.interactive
                        FOR UPDATE OF membership
                        """,
                        (resultSet, rowNumber) -> new TargetMembership(
                                resultSet.getObject("user_id", UUID.class),
                                resultSet.getString("role_key"),
                                resultSet.getLong("lock_version"),
                                resultSet.getBoolean("final_owner")),
                        context.organizationId(),
                        membershipId)
                .stream()
                .findFirst()
                .orElseThrow(JdbcMembershipChangeStore::targetUnavailable);
        if (target.finalOwner() || context.actorId().equals(target.userId())) {
            throw targetUnavailable();
        }
        if (target.lockVersion() != expectedLockVersion) {
            throw new MembershipAdministrationException(
                    Reason.STALE_REVISION,
                    "The membership changed. Reload it before requesting a change.");
        }
        if ("role_change".equals(changeType)) {
            if (target.roleKey().equals(toRoleKey)) {
                throw new MembershipAdministrationException(
                        Reason.INVALID_REQUEST,
                        "The requested role must differ from the current role.");
            }
            var delegable = Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                    """
                    SELECT EXISTS (
                        SELECT 1
                        FROM authorization_roles target_role
                        WHERE target_role.role_key = ?
                          AND target_role.registry_version = 'm1-candidate-1'
                          AND target_role.status = 'active'
                          AND target_role.interactive
                          AND NOT target_role.final_owner
                          AND EXISTS (
                              SELECT 1
                              FROM organization_memberships actor_membership
                              JOIN authorization_roles actor_role
                                ON actor_role.role_key = actor_membership.role_key
                              JOIN authorization_role_delegations delegation
                                ON delegation.delegator_role_key = actor_role.role_key
                               AND delegation.target_role_key = target_role.role_key
                               AND delegation.registry_version = 'm1-candidate-1'
                              WHERE actor_membership.organization_id = ?
                                AND actor_membership.user_id = ?
                                AND actor_membership.status = 'active'
                                AND actor_membership.effective_from <= clock_timestamp()
                                AND (actor_membership.effective_to IS NULL
                                     OR actor_membership.effective_to > clock_timestamp())
                                AND actor_role.registry_version = 'm1-candidate-1'
                                AND actor_role.status = 'active'
                                AND actor_role.interactive
                          )
                    )
                    """,
                    Boolean.class,
                    toRoleKey,
                    context.organizationId(),
                    context.actorId()));
            if (!delegable) {
                throw new MembershipAdministrationException(
                        Reason.TARGET_UNAVAILABLE,
                        "The requested membership role is unavailable.");
            }
        }

        jdbcTemplate.update(
                """
                UPDATE authorization_approval_requests
                SET status = 'expired', updated_at = clock_timestamp(),
                    lock_version = lock_version + 1
                WHERE organization_id = ?
                  AND operation_key = ?
                  AND subject_type = 'membership'
                  AND subject_id = ?
                  AND status IN ('pending', 'approved')
                  AND expires_at <= clock_timestamp()
                """,
                context.organizationId(),
                TARGET_OPERATION,
                membershipId);
        try {
            jdbcTemplate.update(
                    """
                    INSERT INTO authorization_approval_requests
                        (id, organization_id, operation_key, subject_type, subject_id,
                         requested_by_user_id, request_reason,
                         request_correlation_id, expires_at)
                    VALUES (?, ?, ?, 'membership', ?, ?, ?, ?, ?)
                    """,
                    approvalId,
                    context.organizationId(),
                    TARGET_OPERATION,
                    membershipId,
                    context.actorId(),
                    reason,
                    context.correlationId(),
                    Timestamp.from(expiresAt));
            jdbcTemplate.update(
                    """
                    INSERT INTO membership_change_requests
                        (id, organization_id, membership_id, target_user_id,
                         change_type, from_role_key, to_role_key,
                         expected_lock_version, created_by, request_correlation_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    approvalId,
                    context.organizationId(),
                    membershipId,
                    target.userId(),
                    changeType,
                    target.roleKey(),
                    toRoleKey,
                    expectedLockVersion,
                    context.actorId(),
                    context.correlationId());
        } catch (DuplicateKeyException exception) {
            throw new MembershipAdministrationException(
                    Reason.APPROVAL_ALREADY_OPEN,
                    "An unexpired membership change approval is already open for this membership.");
        } catch (DataIntegrityViolationException exception) {
            throw targetUnavailable();
        }
        return new MembershipChange(
                approvalId,
                context.organizationId(),
                membershipId,
                target.userId(),
                context.actorId(),
                null,
                changeType,
                target.roleKey(),
                toRoleKey,
                expectedLockVersion,
                "pending",
                expiresAt);
    }

    @Override
    public MembershipChange approve(
            AuthorizedTenantContext context,
            UUID approvalId,
            UUID membershipId,
            String decisionReason,
            Instant decidedAt) {
        AuthorizedTenantTransactionGuard.requireWritable(jdbcTemplate, context);
        return jdbcTemplate.query(
                        """
                        WITH approved AS (
                            UPDATE authorization_approval_requests approval
                            SET status = 'approved', decided_by_user_id = ?,
                                decision_reason = ?, decision_correlation_id = ?,
                                decided_at = ?, updated_at = ?, lock_version = lock_version + 1
                            WHERE approval.id = ?
                              AND approval.organization_id = ?
                              AND approval.operation_key = ?
                              AND approval.subject_type = 'membership'
                              AND approval.subject_id = ?
                              AND approval.requested_by_user_id <> ?
                              AND approval.status = 'pending'
                              AND approval.expires_at > ?
                              AND EXISTS (
                                  SELECT 1
                                  FROM membership_change_requests request
                                  WHERE request.id = approval.id
                                    AND request.organization_id = approval.organization_id
                                    AND request.membership_id = approval.subject_id
                                    AND request.target_user_id <> ?
                              )
                            RETURNING approval.id, approval.organization_id,
                                      approval.requested_by_user_id,
                                      approval.decided_by_user_id, approval.status,
                                      approval.expires_at
                        )
                        SELECT approved.id, approved.organization_id,
                               request.membership_id, request.target_user_id,
                               approved.requested_by_user_id, approved.decided_by_user_id,
                               request.change_type, request.from_role_key, request.to_role_key,
                               request.expected_lock_version, approved.status, approved.expires_at
                        FROM approved
                        JOIN membership_change_requests request ON request.id = approved.id
                        """,
                        JdbcMembershipChangeStore::map,
                        context.actorId(),
                        decisionReason,
                        context.correlationId(),
                        Timestamp.from(decidedAt),
                        Timestamp.from(decidedAt),
                        approvalId,
                        context.organizationId(),
                        TARGET_OPERATION,
                        membershipId,
                        context.actorId(),
                        Timestamp.from(decidedAt),
                        context.actorId())
                .stream()
                .findFirst()
                .orElseThrow(() -> new MembershipAdministrationException(
                        Reason.APPROVAL_UNAVAILABLE,
                        "The pending membership change approval is unavailable."));
    }

    @Override
    public MembershipChange execute(
            AuthorizedTenantContext context,
            UUID approvalId,
            UUID membershipId,
            String idempotencyKey,
            Instant changedAt) {
        AuthorizedTenantTransactionGuard.requireWritable(jdbcTemplate, context);
        var change = jdbcTemplate.query(
                        """
                        SELECT approval.id, approval.organization_id,
                               request.membership_id, request.target_user_id,
                               approval.requested_by_user_id, approval.decided_by_user_id,
                               request.change_type, request.from_role_key, request.to_role_key,
                               request.expected_lock_version, approval.status, approval.expires_at
                        FROM authorization_approval_requests approval
                        JOIN membership_change_requests request ON request.id = approval.id
                        WHERE approval.id = ?
                          AND approval.organization_id = ?
                          AND approval.operation_key = ?
                          AND approval.subject_type = 'membership'
                          AND approval.subject_id = ?
                          AND approval.status = 'consumed'
                          AND approval.consumed_by_user_id = ?
                          AND approval.consumed_idempotency_key = ?
                        """,
                        JdbcMembershipChangeStore::map,
                        approvalId,
                        context.organizationId(),
                        TARGET_OPERATION,
                        membershipId,
                        context.actorId(),
                        idempotencyKey)
                .stream()
                .findFirst()
                .orElseThrow(() -> new MembershipAdministrationException(
                        Reason.APPROVAL_UNAVAILABLE,
                        "The approved membership change is unavailable."));

        var updatedLockVersion = jdbcTemplate.query(
                        "role_change".equals(change.changeType())
                                ? """
                                  UPDATE organization_memberships
                                  SET role_key = ?, updated_by = ?, lock_version = lock_version + 1
                                  WHERE organization_id = ? AND id = ? AND lock_version = ?
                                  RETURNING lock_version
                                  """
                                : """
                                  UPDATE organization_memberships
                                  SET status = 'revoked', effective_to = ?, updated_by = ?,
                                      lock_version = lock_version + 1
                                  WHERE organization_id = ? AND id = ? AND lock_version = ?
                                  RETURNING lock_version
                                  """,
                        (resultSet, rowNumber) -> resultSet.getLong("lock_version"),
                        "role_change".equals(change.changeType())
                                ? new Object[] {
                                    change.toRoleKey(),
                                    context.actorId(),
                                    context.organizationId(),
                                    membershipId,
                                    change.lockVersion()
                                }
                                : new Object[] {
                                    Timestamp.from(changedAt),
                                    context.actorId(),
                                    context.organizationId(),
                                    membershipId,
                                    change.lockVersion()
                                })
                .stream()
                .findFirst()
                .orElseThrow(() -> new MembershipAdministrationException(
                        Reason.CONFLICT,
                        "The approved membership change no longer matches the current membership."));

        return new MembershipChange(
                change.approvalId(),
                change.organizationId(),
                change.membershipId(),
                change.targetUserId(),
                change.requestedByUserId(),
                change.approvedByUserId(),
                change.changeType(),
                change.fromRoleKey(),
                change.toRoleKey(),
                updatedLockVersion,
                "revoke".equals(change.changeType()) ? "revoked" : "changed",
                change.expiresAt());
    }

    @Override
    public MembershipChange requestOwnerTransfer(
            AuthorizedTenantContext context,
            UUID approvalId,
            UUID membershipId,
            String toRoleKey,
            long expectedLockVersion,
            String reason,
            Instant expiresAt) {
        AuthorizedTenantTransactionGuard.requireWritable(jdbcTemplate, context);
        var target = jdbcTemplate.query(
                        """
                        SELECT membership.user_id, membership.role_key, membership.lock_version,
                               role.final_owner, membership.effective_to IS NULL AS indefinite
                        FROM organization_memberships membership
                        JOIN users target_user ON target_user.id = membership.user_id
                        JOIN authorization_roles role ON role.role_key = membership.role_key
                        WHERE membership.organization_id = ?
                          AND membership.id = ?
                          AND membership.status = 'active'
                          AND membership.effective_from <= clock_timestamp()
                          AND (membership.effective_to IS NULL
                               OR membership.effective_to > clock_timestamp())
                          AND target_user.status = 'active'
                          AND role.registry_version = 'm1-candidate-1'
                          AND role.status = 'active'
                          AND role.interactive
                        FOR UPDATE OF membership
                        """,
                        (resultSet, rowNumber) -> new OwnerTargetMembership(
                                resultSet.getObject("user_id", UUID.class),
                                resultSet.getString("role_key"),
                                resultSet.getLong("lock_version"),
                                resultSet.getBoolean("final_owner"),
                                resultSet.getBoolean("indefinite")),
                        context.organizationId(),
                        membershipId)
                .stream()
                .findFirst()
                .orElseThrow(JdbcMembershipChangeStore::targetUnavailable);
        if (context.actorId().equals(target.userId())) {
            throw targetUnavailable();
        }
        if (target.lockVersion() != expectedLockVersion) {
            throw new MembershipAdministrationException(
                    Reason.STALE_REVISION,
                    "The membership changed. Reload it before requesting an owner transfer.");
        }

        String changeType;
        if (target.finalOwner()) {
            if (!"organization_owner".equals(target.roleKey())
                    || "organization_owner".equals(toRoleKey)) {
                throw targetUnavailable();
            }
            changeType = "owner_demotion";
        } else {
            if (!target.indefinite() || !"organization_owner".equals(toRoleKey)) {
                throw targetUnavailable();
            }
            changeType = "owner_promotion";
        }

        var destinationAllowed = Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                """
                SELECT EXISTS (
                    SELECT 1
                    FROM authorization_roles destination_role
                    WHERE destination_role.role_key = ?
                      AND destination_role.registry_version = 'm1-candidate-1'
                      AND destination_role.status = 'active'
                      AND destination_role.interactive
                      AND (
                          (? = 'owner_promotion'
                              AND destination_role.role_key = 'organization_owner'
                              AND destination_role.final_owner)
                          OR
                          (? = 'owner_demotion'
                              AND NOT destination_role.final_owner
                              AND EXISTS (
                                  SELECT 1
                                  FROM authorization_role_delegations delegation
                                  WHERE delegation.delegator_role_key = 'organization_owner'
                                    AND delegation.target_role_key = destination_role.role_key
                                    AND delegation.registry_version = 'm1-candidate-1'
                              ))
                      )
                )
                """,
                Boolean.class,
                toRoleKey,
                changeType,
                changeType));
        if (!destinationAllowed) {
            throw targetUnavailable();
        }

        jdbcTemplate.update(
                """
                UPDATE authorization_approval_requests
                SET status = 'expired', updated_at = clock_timestamp(),
                    lock_version = lock_version + 1
                WHERE organization_id = ?
                  AND operation_key = ?
                  AND subject_type = 'membership'
                  AND subject_id = ?
                  AND status IN ('pending', 'approved')
                  AND expires_at <= clock_timestamp()
                """,
                context.organizationId(),
                OWNER_TRANSFER_TARGET_OPERATION,
                membershipId);
        try {
            jdbcTemplate.update(
                    """
                    INSERT INTO authorization_approval_requests
                        (id, organization_id, operation_key, subject_type, subject_id,
                         requested_by_user_id, request_reason,
                         request_correlation_id, expires_at)
                    VALUES (?, ?, ?, 'membership', ?, ?, ?, ?, ?)
                    """,
                    approvalId,
                    context.organizationId(),
                    OWNER_TRANSFER_TARGET_OPERATION,
                    membershipId,
                    context.actorId(),
                    reason,
                    context.correlationId(),
                    Timestamp.from(expiresAt));
            jdbcTemplate.update(
                    """
                    INSERT INTO owner_transfer_requests
                        (id, organization_id, membership_id, target_user_id,
                         change_type, from_role_key, to_role_key,
                         expected_lock_version, created_by, request_correlation_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    approvalId,
                    context.organizationId(),
                    membershipId,
                    target.userId(),
                    changeType,
                    target.roleKey(),
                    toRoleKey,
                    expectedLockVersion,
                    context.actorId(),
                    context.correlationId());
        } catch (DuplicateKeyException exception) {
            throw new MembershipAdministrationException(
                    Reason.APPROVAL_ALREADY_OPEN,
                    "An unexpired owner transfer approval is already open for this membership.");
        } catch (DataIntegrityViolationException exception) {
            throw targetUnavailable();
        }
        return new MembershipChange(
                approvalId,
                context.organizationId(),
                membershipId,
                target.userId(),
                context.actorId(),
                null,
                changeType,
                target.roleKey(),
                toRoleKey,
                expectedLockVersion,
                "pending",
                expiresAt);
    }

    @Override
    public MembershipChange approveOwnerTransfer(
            AuthorizedTenantContext context,
            UUID approvalId,
            UUID membershipId,
            String decisionReason,
            Instant decidedAt) {
        AuthorizedTenantTransactionGuard.requireWritable(jdbcTemplate, context);
        return jdbcTemplate.query(
                        """
                        WITH approved AS (
                            UPDATE authorization_approval_requests approval
                            SET status = 'approved', decided_by_user_id = ?,
                                decision_reason = ?, decision_correlation_id = ?,
                                decided_at = ?, updated_at = ?, lock_version = lock_version + 1
                            WHERE approval.id = ?
                              AND approval.organization_id = ?
                              AND approval.operation_key = ?
                              AND approval.subject_type = 'membership'
                              AND approval.subject_id = ?
                              AND approval.requested_by_user_id <> ?
                              AND approval.status = 'pending'
                              AND approval.expires_at > ?
                              AND EXISTS (
                                  SELECT 1
                                  FROM owner_transfer_requests request
                                  WHERE request.id = approval.id
                                    AND request.organization_id = approval.organization_id
                                    AND request.membership_id = approval.subject_id
                                    AND request.target_user_id <> ?
                              )
                            RETURNING approval.id, approval.organization_id,
                                      approval.requested_by_user_id,
                                      approval.decided_by_user_id, approval.status,
                                      approval.expires_at
                        )
                        SELECT approved.id, approved.organization_id,
                               request.membership_id, request.target_user_id,
                               approved.requested_by_user_id, approved.decided_by_user_id,
                               request.change_type, request.from_role_key, request.to_role_key,
                               request.expected_lock_version, approved.status, approved.expires_at
                        FROM approved
                        JOIN owner_transfer_requests request ON request.id = approved.id
                        """,
                        JdbcMembershipChangeStore::map,
                        context.actorId(),
                        decisionReason,
                        context.correlationId(),
                        Timestamp.from(decidedAt),
                        Timestamp.from(decidedAt),
                        approvalId,
                        context.organizationId(),
                        OWNER_TRANSFER_TARGET_OPERATION,
                        membershipId,
                        context.actorId(),
                        Timestamp.from(decidedAt),
                        context.actorId())
                .stream()
                .findFirst()
                .orElseThrow(() -> new MembershipAdministrationException(
                        Reason.APPROVAL_UNAVAILABLE,
                        "The pending owner transfer approval is unavailable."));
    }

    @Override
    public MembershipChange executeOwnerTransfer(
            AuthorizedTenantContext context,
            UUID approvalId,
            UUID membershipId,
            String idempotencyKey,
            Instant changedAt) {
        AuthorizedTenantTransactionGuard.requireWritable(jdbcTemplate, context);
        var change = jdbcTemplate.query(
                        """
                        SELECT approval.id, approval.organization_id,
                               request.membership_id, request.target_user_id,
                               approval.requested_by_user_id, approval.decided_by_user_id,
                               request.change_type, request.from_role_key, request.to_role_key,
                               request.expected_lock_version, approval.status, approval.expires_at
                        FROM authorization_approval_requests approval
                        JOIN owner_transfer_requests request ON request.id = approval.id
                        WHERE approval.id = ?
                          AND approval.organization_id = ?
                          AND approval.operation_key = ?
                          AND approval.subject_type = 'membership'
                          AND approval.subject_id = ?
                          AND approval.status = 'consumed'
                          AND approval.consumed_by_user_id = ?
                          AND approval.consumed_idempotency_key = ?
                        """,
                        JdbcMembershipChangeStore::map,
                        approvalId,
                        context.organizationId(),
                        OWNER_TRANSFER_TARGET_OPERATION,
                        membershipId,
                        context.actorId(),
                        idempotencyKey)
                .stream()
                .findFirst()
                .orElseThrow(() -> new MembershipAdministrationException(
                        Reason.APPROVAL_UNAVAILABLE,
                        "The approved owner transfer is unavailable."));

        final long updatedLockVersion;
        try {
            updatedLockVersion = jdbcTemplate.query(
                            """
                            UPDATE organization_memberships
                            SET role_key = ?, updated_by = ?, lock_version = lock_version + 1
                            WHERE organization_id = ? AND id = ? AND lock_version = ?
                            RETURNING lock_version
                            """,
                            (resultSet, rowNumber) -> resultSet.getLong("lock_version"),
                            change.toRoleKey(),
                            context.actorId(),
                            context.organizationId(),
                            membershipId,
                            change.lockVersion())
                    .stream()
                    .findFirst()
                    .orElseThrow(JdbcMembershipChangeStore::ownerTransferConflict);
        } catch (DataIntegrityViolationException exception) {
            throw ownerTransferConflict();
        }

        return new MembershipChange(
                change.approvalId(),
                change.organizationId(),
                change.membershipId(),
                change.targetUserId(),
                change.requestedByUserId(),
                change.approvedByUserId(),
                change.changeType(),
                change.fromRoleKey(),
                change.toRoleKey(),
                updatedLockVersion,
                "transferred",
                change.expiresAt());
    }

    private static MembershipChange map(ResultSet resultSet, int rowNumber) throws SQLException {
        return new MembershipChange(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("organization_id", UUID.class),
                resultSet.getObject("membership_id", UUID.class),
                resultSet.getObject("target_user_id", UUID.class),
                resultSet.getObject("requested_by_user_id", UUID.class),
                resultSet.getObject("decided_by_user_id", UUID.class),
                resultSet.getString("change_type"),
                resultSet.getString("from_role_key"),
                resultSet.getString("to_role_key"),
                resultSet.getLong("expected_lock_version"),
                resultSet.getString("status"),
                resultSet.getTimestamp("expires_at").toInstant());
    }

    private static MembershipAdministrationException targetUnavailable() {
        return new MembershipAdministrationException(
                Reason.TARGET_UNAVAILABLE, "The membership change target is unavailable.");
    }

    private static MembershipAdministrationException ownerTransferConflict() {
        return new MembershipAdministrationException(
                Reason.CONFLICT,
                "The approved owner transfer no longer matches a safe current membership state.");
    }

    private record TargetMembership(
            UUID userId, String roleKey, long lockVersion, boolean finalOwner) {}

    private record OwnerTargetMembership(
            UUID userId,
            String roleKey,
            long lockVersion,
            boolean finalOwner,
            boolean indefinite) {}
}
