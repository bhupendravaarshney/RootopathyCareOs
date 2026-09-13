package com.rootopathy.careos.tenancy.application;

import com.rootopathy.careos.tenancy.domain.OrganizationAccess;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface OrganizationMembershipStore {
    List<OrganizationAccess> findSelectableOrganizations(UUID actorId, Instant at);
}
