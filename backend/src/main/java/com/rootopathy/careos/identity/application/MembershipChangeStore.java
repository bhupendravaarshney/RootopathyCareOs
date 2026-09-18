package com.rootopathy.careos.identity.application;

import com.rootopathy.careos.identity.domain.MembershipChange;
import com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext;
import java.time.Instant;
import java.util.UUID;

public interface MembershipChangeStore {
    MembershipChange request(
            AuthorizedTenantContext context,
            UUID approvalId,
            UUID membershipId,
            String changeType,
            String toRoleKey,
            long expectedLockVersion,
            String reason,
            Instant expiresAt);

    MembershipChange approve(
            AuthorizedTenantContext context,
            UUID approvalId,
            UUID membershipId,
            String decisionReason,
            Instant decidedAt);

    MembershipChange execute(
            AuthorizedTenantContext context,
            UUID approvalId,
            UUID membershipId,
            String idempotencyKey,
            Instant changedAt);

    MembershipChange requestOwnerTransfer(
            AuthorizedTenantContext context,
            UUID approvalId,
            UUID membershipId,
            String toRoleKey,
            long expectedLockVersion,
            String reason,
            Instant expiresAt);

    MembershipChange approveOwnerTransfer(
            AuthorizedTenantContext context,
            UUID approvalId,
            UUID membershipId,
            String decisionReason,
            Instant decidedAt);

    MembershipChange executeOwnerTransfer(
            AuthorizedTenantContext context,
            UUID approvalId,
            UUID membershipId,
            String idempotencyKey,
            Instant changedAt);
}
