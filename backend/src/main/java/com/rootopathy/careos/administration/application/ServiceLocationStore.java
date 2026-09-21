package com.rootopathy.careos.administration.application;

import com.rootopathy.careos.administration.domain.ServiceLocationDirectory;
import com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext;
import java.time.Instant;
import java.util.UUID;

public interface ServiceLocationStore {
    ServiceLocationDirectory directory(AuthorizedTenantContext context, UUID facilityId);

    Result create(AuthorizedTenantContext context, Draft draft);

    Result update(AuthorizedTenantContext context, UUID locationId, long revision, Draft draft);

    ReparentResult reparent(
            AuthorizedTenantContext context,
            UUID facilityId,
            UUID locationId,
            long revision,
            UUID parentId,
            Instant effectiveFrom);

    LifecycleResult transition(AuthorizedTenantContext context, UUID facilityId, UUID locationId,
            long revision, String fromState, String toState);

    LifecycleResult close(AuthorizedTenantContext context, UUID facilityId, UUID locationId,
            long revision, Instant effectiveTo);

    record Draft(
            UUID facilityId,
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
            Instant effectiveTo) {}

    record Result(ServiceLocationDirectory directory, UUID locationId, long lockVersion, UUID parentId) {}

    record ReparentResult(
            ServiceLocationDirectory directory,
            UUID locationId,
            UUID previousParentId,
            UUID parentId,
            long lockVersion,
            Instant effectiveFrom) {}

    record LifecycleResult(ServiceLocationDirectory directory, UUID locationId, UUID parentId,
            String fromState, long lockVersion) {}
}
