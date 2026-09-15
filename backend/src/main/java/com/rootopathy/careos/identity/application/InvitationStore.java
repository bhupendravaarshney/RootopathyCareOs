package com.rootopathy.careos.identity.application;

import com.rootopathy.careos.identity.domain.OrganizationInvitation;
import com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext;
import java.time.Instant;
import java.util.UUID;

public interface InvitationStore {
    OrganizationInvitation issue(
            AuthorizedTenantContext context,
            UUID invitationId,
            String normalizedEmail,
            String displayName,
            String roleKey,
            String tokenHash,
            Instant expiresAt,
            String reason);

    OrganizationInvitation revoke(
            AuthorizedTenantContext context, UUID invitationId, String reason);
}
