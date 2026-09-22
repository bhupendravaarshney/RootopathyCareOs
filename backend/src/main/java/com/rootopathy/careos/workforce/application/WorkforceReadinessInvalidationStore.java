package com.rootopathy.careos.workforce.application;

import com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext;
import java.util.UUID;

/** Resolves one approved material event to tenant-owned members and reconciles stale readiness. */
public interface WorkforceReadinessInvalidationStore {
    int reconcile(
            AuthorizedTenantContext context,
            String aggregateType,
            UUID aggregateId,
            String invalidationCode);
}
