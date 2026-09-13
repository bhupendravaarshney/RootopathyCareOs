package com.rootopathy.careos.governance.application;

import com.rootopathy.careos.governance.domain.OutboxEnvelope;
import com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/** Tenant-scoped leased delivery state transitions; every method requires an authorized transaction. */
public interface OutboxDeliveryOperations {
    List<OutboxEnvelope> claimDue(
            AuthorizedTenantContext context, String workerId, int batchSize, Duration lease);

    boolean markPublished(
            AuthorizedTenantContext context, UUID eventId, UUID claimToken, String workerId);

    boolean reschedule(
            AuthorizedTenantContext context,
            UUID eventId,
            UUID claimToken,
            String workerId,
            String errorCode,
            Duration retryDelay);

    boolean deadLetter(
            AuthorizedTenantContext context,
            UUID eventId,
            UUID claimToken,
            String workerId,
            String errorCode);
}
