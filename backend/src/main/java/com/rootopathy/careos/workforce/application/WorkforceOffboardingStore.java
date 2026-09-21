package com.rootopathy.careos.workforce.application;

import com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Executes an already approved, due offboarding plan inside a service-authorized transaction. */
public interface WorkforceOffboardingStore {
    boolean isDue(AuthorizedTenantContext context, UUID requestId, Instant now);

    List<UUID> dueRequestIds(AuthorizedTenantContext context, Instant now, int maximumItems);

    WorkforceStore.MutationResult executeApproved(
            AuthorizedTenantContext context, UUID requestId, Instant now);

    WorkforceStore.MutationResult recordFailure(
            AuthorizedTenantContext context,
            UUID requestId,
            String failureCode,
            Instant failedAt);
}
