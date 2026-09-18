package com.rootopathy.careos.administration.application;

import com.rootopathy.careos.administration.domain.OrganizationGovernanceDirectory;
import com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext;
import java.time.Instant;
import java.util.UUID;

public interface OrganizationGovernanceStore {
    OrganizationGovernanceDirectory directory(AuthorizedTenantContext context);
    MutationResult create(AuthorizedTenantContext context, Draft draft);
    MutationResult supersede(AuthorizedTenantContext context, UUID predecessorId, long expectedRevision, Draft draft);
    MutationResult end(AuthorizedTenantContext context, UUID responsibilityId, long expectedRevision, Instant effectiveTo);

    record Draft(String responsibilityType, UUID membershipId, UUID externalContactId,
                 String escalationEmail, String escalationPhone, Instant effectiveFrom) {}
    record MutationResult(OrganizationGovernanceDirectory directory, UUID responsibilityId,
                          String responsibilityType, String changeType, Instant effectiveFrom, long lockVersion) {}
}
