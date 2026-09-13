package com.rootopathy.careos.identity.domain;

import java.util.UUID;

public record TotpMethod(UUID id, UUID userId, String status, String encryptedSecret) {
    public boolean isPending() {
        return "pending".equals(status);
    }

    public boolean isEnabled() {
        return "enabled".equals(status);
    }
}
