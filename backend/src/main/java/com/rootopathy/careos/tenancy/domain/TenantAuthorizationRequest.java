package com.rootopathy.careos.tenancy.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * An untrusted tenant candidate paired with an authenticated actor and required operation.
 * It becomes an {@link AuthorizedTenantContext} only inside the authorization transaction.
 */
public record TenantAuthorizationRequest(
        UUID organizationId,
        AuthenticatedActorContext actor,
        OperationKey requiredOperation,
        String reason,
        Instant recentAuthenticationAt,
        Instant mfaAuthenticatedAt,
        IndependentApproval independentApproval) {
    public TenantAuthorizationRequest(
            UUID organizationId,
            AuthenticatedActorContext actor,
            OperationKey requiredOperation) {
        this(organizationId, actor, requiredOperation, null, null, null, null);
    }

    public TenantAuthorizationRequest(
            UUID organizationId,
            AuthenticatedActorContext actor,
            OperationKey requiredOperation,
            String reason,
            Instant recentAuthenticationAt) {
        this(
                organizationId,
                actor,
                requiredOperation,
                reason,
                recentAuthenticationAt,
                null,
                null);
    }

    public TenantAuthorizationRequest(
            UUID organizationId,
            AuthenticatedActorContext actor,
            OperationKey requiredOperation,
            String reason,
            Instant recentAuthenticationAt,
            IndependentApproval independentApproval) {
        this(
                organizationId,
                actor,
                requiredOperation,
                reason,
                recentAuthenticationAt,
                null,
                independentApproval);
    }

    public TenantAuthorizationRequest {
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(requiredOperation, "requiredOperation");
        if (reason != null) {
            reason = reason.strip();
            if (reason.isEmpty() || reason.length() > 2000) {
                throw new IllegalArgumentException("reason must contain between 1 and 2000 characters");
            }
        }
    }
}
