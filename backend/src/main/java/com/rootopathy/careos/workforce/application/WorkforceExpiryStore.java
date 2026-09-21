package com.rootopathy.careos.workforce.application;

import com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface WorkforceExpiryStore {
    List<Milestone> projectDue(
            AuthorizedTenantContext context,
            LocalDate evaluationDate,
            Instant evaluatedAt,
            int maximumItems);

    record Milestone(
            UUID notificationId,
            UUID sourceId,
            String sourceType,
            UUID memberId,
            LocalDate expiryDate,
            String milestone,
            String state,
            String outcomeCode) {}
}
