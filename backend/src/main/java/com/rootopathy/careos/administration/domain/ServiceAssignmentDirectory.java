package com.rootopathy.careos.administration.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ServiceAssignmentDirectory(
    UUID organizationId,
    boolean canManage,
    boolean canManageLifecycle,
    List<Assignment> assignments,
    Instant evaluatedAt) {
  public record Assignment(
      UUID assignmentId,
      UUID serviceId,
      UUID facilityId,
      UUID locationId,
      Integer capacity,
      String availabilityNotes,
      List<String> prerequisites,
      Instant effectiveFrom,
      Instant effectiveTo,
      String status,
      long lockVersion,
      Instant createdAt,
      Instant updatedAt) {}
}
