package com.rootopathy.careos.administration.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ServiceLocationDirectory(
        UUID organizationId,
        UUID facilityId,
        boolean canManage,
        boolean canManageLifecycle,
        List<Location> locations,
        Instant evaluatedAt) {
    public record Location(
            UUID locationId,
            UUID unitId,
            UUID parentId,
            UUID addressId,
            String locationCode,
            String locationType,
            String name,
            String virtualServiceType,
            Integer capacity,
            String accessibilityNotes,
            Instant effectiveFrom,
            Instant effectiveTo,
            String status,
            long lockVersion,
            Instant createdAt,
            Instant updatedAt) {}
}
