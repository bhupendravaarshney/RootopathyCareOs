package com.rootopathy.careos.administration.application;

import com.rootopathy.careos.administration.domain.OrganizationAddress;
import com.rootopathy.careos.administration.domain.OrganizationContact;
import com.rootopathy.careos.administration.domain.OrganizationContactCollection;
import com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface OrganizationContactStore {
    OrganizationContactCollection directory(AuthorizedTenantContext context);

    OrganizationAddress createAddress(
            AuthorizedTenantContext context, AddressDraft draft);

    SupersededAddress supersedeAddress(
            AuthorizedTenantContext context,
            UUID addressId,
            long expectedLockVersion,
            AddressDraft replacement);

    OrganizationAddress endAddress(
            AuthorizedTenantContext context, UUID addressId, long expectedLockVersion);

    OrganizationContact createContact(
            AuthorizedTenantContext context, ContactDraft draft);

    OrganizationContact verifyContact(
            AuthorizedTenantContext context, UUID contactId, long expectedLockVersion);

    SupersededContact supersedeContact(
            AuthorizedTenantContext context,
            UUID contactId,
            long expectedLockVersion,
            ContactDraft replacement);

    OrganizationContact endContact(
            AuthorizedTenantContext context, UUID contactId, long expectedLockVersion);

    record AddressDraft(
            String addressType,
            List<String> addressLines,
            String locality,
            String region,
            String postcode,
            String countryCode,
            String validationStatus,
            String validationSource,
            boolean isPrimary,
            Instant effectiveFrom,
            Instant effectiveTo) {}

    record ContactDraft(
            String channel,
            String purpose,
            String value,
            boolean isPrimary,
            boolean isPreferred,
            Instant effectiveFrom,
            Instant effectiveTo) {}

    record SupersededAddress(OrganizationAddress replacement, UUID predecessorId) {}

    record SupersededContact(OrganizationContact replacement, UUID predecessorId) {}
}
