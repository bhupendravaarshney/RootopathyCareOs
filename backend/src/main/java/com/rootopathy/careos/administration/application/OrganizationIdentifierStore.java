package com.rootopathy.careos.administration.application;

import com.rootopathy.careos.administration.domain.OrganizationIdentifier;
import com.rootopathy.careos.administration.domain.OrganizationIdentifierCollection;
import com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public interface OrganizationIdentifierStore {
    OrganizationIdentifierCollection identifiers(AuthorizedTenantContext context);

    OrganizationIdentifier create(AuthorizedTenantContext context, IdentifierDraft draft);

    OrganizationIdentifier update(
            AuthorizedTenantContext context,
            UUID identifierId,
            IdentifierDraft draft,
            long expectedLockVersion);

    OrganizationIdentifier verify(
            AuthorizedTenantContext context,
            UUID identifierId,
            String evidenceReference,
            long expectedLockVersion);

    RevokeResult revoke(
            AuthorizedTenantContext context,
            UUID identifierId,
            long expectedLockVersion);

    SupersedeResult supersede(
            AuthorizedTenantContext context,
            UUID identifierId,
            long expectedLockVersion,
            UUID replacementId,
            long expectedReplacementLockVersion);

    record IdentifierDraft(
            String identifierType,
            String assigningAuthority,
            String value,
            String jurisdictionCountryCode,
            boolean isPrimary,
            LocalDate issueDate,
            LocalDate expiryDate,
            Instant effectiveFrom,
            Instant effectiveTo) {}

    record RevokeResult(OrganizationIdentifier identifier, String fromState) {}

    record SupersedeResult(
            OrganizationIdentifier identifier, String fromState, UUID replacementId) {}
}
