package com.rootopathy.careos.administration.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record FacilityDirectory(UUID organizationId, boolean canCreate, List<FacilityType> facilityTypes,
                                List<FacilitySummary> facilities, Instant evaluatedAt) {
    public record FacilityType(String key,String displayName) {}
    public record FacilitySummary(UUID facilityId,String facilityCode,String legalName,String displayName,
                                  String facilityType,String timezone,String status,long lockVersion,
                                  Instant createdAt,Instant updatedAt) {}
}
