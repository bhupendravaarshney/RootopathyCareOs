package com.rootopathy.careos.administration.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record OrganizationMembership(
        UUID membershipId,
        UUID userId,
        String displayName,
        String email,
        String accountStatus,
        String roleKey,
        String roleDisplayName,
        String roleStatus,
        boolean finalOwner,
        String accessState,
        Instant effectiveFrom,
        Instant effectiveTo,
        long lockVersion,
        boolean mfaEnabled,
        List<String> availableActions) {
    public OrganizationMembership {
        if (lockVersion < 0) {
            throw new IllegalArgumentException("lockVersion must not be negative");
        }
        availableActions = List.copyOf(availableActions);
    }
}
