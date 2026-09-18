package com.rootopathy.careos.administration.application;

import com.rootopathy.careos.administration.application.OrganizationMembershipCursorCodec.CursorPosition;
import com.rootopathy.careos.administration.domain.AdministrationReadiness;
import com.rootopathy.careos.administration.domain.OrganizationMembership;
import com.rootopathy.careos.administration.domain.OrganizationProfile;
import com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext;
import java.time.Instant;
import java.util.List;

public interface OrganizationAdministrationStore {
    OrganizationProfile profile(AuthorizedTenantContext context);

    AdministrationReadiness readiness(AuthorizedTenantContext context);

    MembershipPageSlice memberships(
            AuthorizedTenantContext context, MembershipQuery query);

    ProfileUpdateResult updateProfile(AuthorizedTenantContext context, ProfileUpdate update);

    record MembershipQuery(
            Instant asOf,
            String search,
            String accessState,
            String roleKey,
            int limit,
            CursorPosition after) {}

    record MembershipPageSlice(
            List<OrganizationMembership> items,
            boolean hasMore,
            List<String> availableActions) {
        public MembershipPageSlice {
            items = List.copyOf(items);
            availableActions = List.copyOf(availableActions);
        }
    }

    record ProfileUpdate(
            String legalName,
            String displayName,
            String tradingName,
            String organizationType,
            String countryCode,
            String timezone,
            String locale,
            long expectedLockVersion) {}

    record ProfileUpdateResult(OrganizationProfile profile, List<String> changedFields) {
        public ProfileUpdateResult {
            changedFields = List.copyOf(changedFields);
        }
    }
}
