package com.rootopathy.careos.identity.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record MembershipChange(
        UUID approvalId,
        UUID organizationId,
        UUID membershipId,
        UUID targetUserId,
        UUID requestedByUserId,
        UUID approvedByUserId,
        String changeType,
        String fromRoleKey,
        String toRoleKey,
        long lockVersion,
        String status,
        Instant expiresAt) {
    public MembershipChange {
        Objects.requireNonNull(approvalId, "approvalId");
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(membershipId, "membershipId");
        Objects.requireNonNull(targetUserId, "targetUserId");
        Objects.requireNonNull(requestedByUserId, "requestedByUserId");
        Objects.requireNonNull(changeType, "changeType");
        Objects.requireNonNull(fromRoleKey, "fromRoleKey");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (lockVersion < 0) {
            throw new IllegalArgumentException("lockVersion must not be negative");
        }
    }
}
