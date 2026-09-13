package com.rootopathy.careos.tenancy.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * An untrusted tenant candidate paired with an authenticated actor and required permission.
 * It becomes an {@link AuthorizedTenantContext} only inside the authorization transaction.
 */
public record TenantAuthorizationRequest(
        UUID organizationId, AuthenticatedActorContext actor, PermissionKey requiredPermission) {
    public TenantAuthorizationRequest {
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(requiredPermission, "requiredPermission");
    }
}
