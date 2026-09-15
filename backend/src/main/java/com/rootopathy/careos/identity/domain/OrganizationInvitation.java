package com.rootopathy.careos.identity.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record OrganizationInvitation(
        UUID id,
        UUID organizationId,
        String email,
        String displayName,
        String roleKey,
        String status,
        Instant expiresAt) {
    public OrganizationInvitation {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(organizationId, "organizationId");
        email = requireText(email, "email");
        displayName = requireText(displayName, "displayName");
        roleKey = requireText(roleKey, "roleKey");
        status = requireText(status, "status");
        Objects.requireNonNull(expiresAt, "expiresAt");
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }
}
