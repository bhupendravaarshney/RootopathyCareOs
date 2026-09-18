package com.rootopathy.careos.administration.application;

import com.rootopathy.careos.administration.domain.OrganizationInternationalSettings;
import com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext;
import java.time.Instant;
import java.util.List;

public interface OrganizationInternationalSettingsStore {
    OrganizationInternationalSettings settings(AuthorizedTenantContext context);

    ScheduleResult schedule(
            AuthorizedTenantContext context, SettingsDraft draft, long expectedLockVersion);

    record SettingsDraft(
            String countryCode,
            String timezone,
            String locale,
            String language,
            String currencyCode,
            String weekStart,
            Instant effectiveFrom) {}

    record ScheduleResult(
            OrganizationInternationalSettings settings,
            java.util.UUID settingsId,
            List<String> changedFields) {
        public ScheduleResult {
            changedFields = List.copyOf(changedFields);
        }
    }
}
