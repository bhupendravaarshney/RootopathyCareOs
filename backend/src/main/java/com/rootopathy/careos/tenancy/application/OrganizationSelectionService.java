package com.rootopathy.careos.tenancy.application;

import com.rootopathy.careos.tenancy.domain.AuthenticatedActorContext;
import com.rootopathy.careos.tenancy.domain.OrganizationAccess;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public final class OrganizationSelectionService {
    public static final String SELECTION_PURPOSE = "organization-selection";

    private final ActorTransactionOperations actorTransactions;
    private final OrganizationMembershipStore memberships;
    private final Clock clock;

    public OrganizationSelectionService(
            ActorTransactionOperations actorTransactions,
            OrganizationMembershipStore memberships,
            Clock clock) {
        this.actorTransactions = actorTransactions;
        this.memberships = memberships;
        this.clock = clock;
    }

    public List<OrganizationAccess> listSelectableOrganizations(UUID actorId, String correlationId) {
        var context = new AuthenticatedActorContext(actorId, SELECTION_PURPOSE, correlationId);
        return actorTransactions.execute(
                context, () -> List.copyOf(memberships.findSelectableOrganizations(actorId, clock.instant())));
    }

    public Optional<OrganizationAccess> findSelectableOrganization(
            UUID actorId, UUID organizationId, String correlationId) {
        return listSelectableOrganizations(actorId, correlationId).stream()
                .filter(access -> access.organizationId().equals(organizationId))
                .findFirst();
    }
}
