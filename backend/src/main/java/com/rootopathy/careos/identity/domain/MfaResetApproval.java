package com.rootopathy.careos.identity.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record MfaResetApproval(
        UUID id,
        UUID organizationId,
        UUID targetUserId,
        UUID requestedByUserId,
        UUID approvedByUserId,
        String status,
        String requestReason,
        Instant expiresAt) {
    public MfaResetApproval {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(targetUserId, "targetUserId");
        Objects.requireNonNull(requestedByUserId, "requestedByUserId");
        if (status == null || status.isBlank()) {
            throw new IllegalArgumentException("status is required");
        }
        if (requestReason == null || requestReason.isBlank()) {
            throw new IllegalArgumentException("requestReason is required");
        }
        Objects.requireNonNull(expiresAt, "expiresAt");
    }
}
