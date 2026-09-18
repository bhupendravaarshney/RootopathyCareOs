package com.rootopathy.careos.administration.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record GovernanceResponsibility(
        UUID responsibilityId,
        String responsibilityType,
        String assigneeType,
        UUID assigneeId,
        String assigneeDisplay,
        String escalationEmailMasked,
        String escalationPhoneMasked,
        boolean primary,
        Instant effectiveFrom,
        Instant effectiveTo,
        UUID supersedesId,
        String status,
        List<String> availableActions,
        long lockVersion,
        Instant createdAt,
        Instant updatedAt) {
    public GovernanceResponsibility {
        Objects.requireNonNull(responsibilityId);
        Objects.requireNonNull(responsibilityType);
        Objects.requireNonNull(assigneeType);
        Objects.requireNonNull(assigneeId);
        Objects.requireNonNull(assigneeDisplay);
        Objects.requireNonNull(effectiveFrom);
        Objects.requireNonNull(status);
        Objects.requireNonNull(createdAt);
        Objects.requireNonNull(updatedAt);
        availableActions = List.copyOf(availableActions);
        if (!Set.of("clinical", "privacy", "security", "billing").contains(responsibilityType)
                || !Set.of("membership", "external_contact").contains(assigneeType)
                || !Set.of("scheduled", "active", "ended", "superseded").contains(status)
                || (escalationEmailMasked == null && escalationPhoneMasked == null)
                || lockVersion < 0) {
            throw new IllegalArgumentException("invalid governance responsibility");
        }
    }
}
