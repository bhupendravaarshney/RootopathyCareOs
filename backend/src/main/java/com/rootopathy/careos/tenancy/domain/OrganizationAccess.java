package com.rootopathy.careos.tenancy.domain;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** A selectable organization backed by at least one currently active membership. */
public record OrganizationAccess(
        UUID organizationId, String displayName, String status, List<String> roleKeys) {
    public OrganizationAccess {
        Objects.requireNonNull(organizationId, "organizationId");
        displayName = requireText(displayName, "displayName");
        status = requireText(status, "status");
        roleKeys = List.copyOf(roleKeys);
        if (roleKeys.isEmpty() || roleKeys.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("At least one role key is required");
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
