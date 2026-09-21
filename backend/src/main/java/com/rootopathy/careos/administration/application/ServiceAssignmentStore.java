package com.rootopathy.careos.administration.application;

import com.rootopathy.careos.administration.domain.ServiceAssignmentDirectory;
import com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ServiceAssignmentStore {
  ServiceAssignmentDirectory directory(AuthorizedTenantContext context);
  Result create(AuthorizedTenantContext context, Draft draft);
  Result update(AuthorizedTenantContext context, UUID id, long revision, Draft draft);
  Result transition(AuthorizedTenantContext context, UUID id, long revision, String from, String to);

  record Draft(UUID serviceId, UUID facilityId, UUID locationId, Integer capacity,
      String availabilityNotes, List<String> prerequisites, Instant effectiveFrom, Instant effectiveTo) {}
  record Result(UUID assignmentId, UUID serviceId, UUID targetId, Instant effectiveFrom,
      String fromState, String toState, long lockVersion, ServiceAssignmentDirectory directory) {}
}
