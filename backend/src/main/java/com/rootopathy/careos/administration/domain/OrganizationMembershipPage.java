package com.rootopathy.careos.administration.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record OrganizationMembershipPage(
        UUID organizationId,
        Instant asOf,
        List<OrganizationMembership> items,
        CursorPage page,
        List<String> availableActions) {
    public OrganizationMembershipPage {
        items = List.copyOf(items);
        availableActions = List.copyOf(availableActions);
    }

    public record CursorPage(int limit, boolean hasMore, String nextCursor) {}
}
