package com.rootopathy.careos.identity.domain;

import java.util.UUID;

public record CredentialAccount(
        UUID id,
        String email,
        String displayName,
        String status,
        String passwordHash,
        long securityVersion,
        boolean mfaEnabled,
        boolean mfaRequired) {
    public boolean isActive() {
        return "active".equals(status);
    }
}
