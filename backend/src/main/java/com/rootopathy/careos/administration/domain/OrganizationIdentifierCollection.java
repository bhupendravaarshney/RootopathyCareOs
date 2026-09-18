package com.rootopathy.careos.administration.domain;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record OrganizationIdentifierCollection(
        UUID organizationId,
        boolean canCreate,
        List<OrganizationIdentifierType> types,
        List<OrganizationIdentifier> items) {
    public OrganizationIdentifierCollection {
        Objects.requireNonNull(organizationId, "organizationId");
        types = List.copyOf(Objects.requireNonNull(types, "types"));
        items = List.copyOf(Objects.requireNonNull(items, "items"));
        var typeKeys = Set.copyOf(types.stream().map(OrganizationIdentifierType::key).toList());
        if (types.size() != typeKeys.size()) {
            throw new IllegalArgumentException("identifier type keys must be unique");
        }
        if (canCreate && types.isEmpty()) {
            throw new IllegalArgumentException("creation requires an applicable identifier type");
        }
        if (items.size()
                != Set.copyOf(items.stream().map(OrganizationIdentifier::identifierId).toList()).size()) {
            throw new IllegalArgumentException("identifier IDs must be unique");
        }
        if (items.stream().anyMatch(item -> !typeKeys.contains(item.identifierType()))) {
            throw new IllegalArgumentException("identifier items must use an applicable type");
        }
    }
}
