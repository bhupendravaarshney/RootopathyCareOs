package com.rootopathy.careos.workforce.application;

import com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface WorkforceNotificationStore {
    List<Plan> lockPlanned(AuthorizedTenantContext context, int maximumItems);

    Delivery queued(AuthorizedTenantContext context, UUID notificationId, long revision, Instant queuedAt);

    Delivery delivered(
            AuthorizedTenantContext context,
            UUID notificationId,
            long revision,
            int attempt,
            String providerOpaqueId,
            Instant deliveredAt);

    Delivery failed(
            AuthorizedTenantContext context,
            UUID notificationId,
            long revision,
            int attempt,
            String failureCode,
            Instant failedAt);

    record Plan(
            UUID notificationId,
            UUID recipientUserId,
            UUID memberId,
            String templateKey,
            int templateVersion,
            UUID templateVersionId,
            String milestone,
            LocalDate expiryDate,
            long revision) {}

    record Delivery(
            UUID notificationId,
            UUID templateVersionId,
            String channel,
            String milestone,
            int attempt,
            String state,
            String failureCode,
            long revision) {}
}
