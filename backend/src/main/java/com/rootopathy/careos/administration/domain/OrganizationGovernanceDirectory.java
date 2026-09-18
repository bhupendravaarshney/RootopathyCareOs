package com.rootopathy.careos.administration.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record OrganizationGovernanceDirectory(
        UUID organizationId,
        boolean canManage,
        Instant evaluatedAt,
        List<String> responsibilityTypes,
        List<GovernanceAssignee> eligibleAssignees,
        List<GovernanceResponsibility> responsibilities) {
    public static final List<String> TYPES = List.of("clinical", "privacy", "security", "billing");

    public OrganizationGovernanceDirectory {
        Objects.requireNonNull(organizationId);
        Objects.requireNonNull(evaluatedAt);
        responsibilityTypes = List.copyOf(responsibilityTypes);
        eligibleAssignees = List.copyOf(eligibleAssignees);
        responsibilities = List.copyOf(responsibilities);
        if (!responsibilityTypes.equals(TYPES)) {
            throw new IllegalArgumentException("invalid governance responsibility catalogue");
        }
    }
}
