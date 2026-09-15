package com.rootopathy.careos.identity.domain;

import java.util.Objects;
import java.util.UUID;

public record InvitationAcceptance(
        UUID invitationId,
        UUID organizationId,
        UUID userId,
        String roleKey,
        String emailHash,
        boolean existingAccount) {
    public InvitationAcceptance {
        Objects.requireNonNull(invitationId, "invitationId");
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(userId, "userId");
        if (roleKey == null || roleKey.isBlank()) {
            throw new IllegalArgumentException("roleKey is required");
        }
        if (emailHash == null || !emailHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("emailHash must be a lowercase SHA-256 HMAC");
        }
    }

    public String accountLink() {
        return existingAccount ? "existing" : "created";
    }
}
