package com.rootopathy.careos.administration.domain;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record OrganizationInternationalSettings(
        UUID organizationId,
        boolean editable,
        boolean canSchedule,
        long lockVersion,
        Instant evaluatedAt,
        List<String> weekStarts,
        List<InternationalSettingsImpact> impactRules,
        List<InternationalSettingsVersion> versions) {
    public static final List<String> APPROVED_WEEK_STARTS = List.of(
            "MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY");
    public static final List<InternationalSettingsImpact> APPROVED_IMPACT_RULES = List.of(
            new InternationalSettingsImpact(
                    "countryCode",
                    "m1.settings.jurisdiction_impact",
                    "warning",
                    "Jurisdiction-dependent registration and governance rules must be revalidated."),
            new InternationalSettingsImpact(
                    "timezone",
                    "m1.settings.timezone_impact",
                    "warning",
                    "Operating hours and future schedules use the new timezone after the effective time."),
            new InternationalSettingsImpact(
                    "locale",
                    "m1.settings.locale_format_impact",
                    "information",
                    "Locale-library date, time, number, and currency presentation changes."),
            new InternationalSettingsImpact(
                    "language",
                    "m1.settings.language_impact",
                    "information",
                    "The organization language default changes without translating stored content."),
            new InternationalSettingsImpact(
                    "currencyCode",
                    "m1.settings.currency_impact",
                    "warning",
                    "The monetary display default changes; stored monetary amounts are not converted."),
            new InternationalSettingsImpact(
                    "weekStart",
                    "m1.settings.week_start_impact",
                    "information",
                    "Calendar week presentation changes without changing stored dates."));

    public OrganizationInternationalSettings {
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(evaluatedAt, "evaluatedAt");
        weekStarts = List.copyOf(Objects.requireNonNull(weekStarts, "weekStarts"));
        impactRules = List.copyOf(Objects.requireNonNull(impactRules, "impactRules"));
        versions = List.copyOf(Objects.requireNonNull(versions, "versions"));
        if (lockVersion < 0) {
            throw new IllegalArgumentException("lockVersion must not be negative");
        }
        if (!weekStarts.equals(APPROVED_WEEK_STARTS)) {
            throw new IllegalArgumentException("weekStarts must use the approved ordering");
        }
        if (versions.isEmpty()) {
            throw new IllegalArgumentException("settings require a current/default version");
        }
        if (canSchedule && !editable) {
            throw new IllegalArgumentException("scheduling requires edit permission");
        }
        var ids = new HashSet<UUID>();
        for (var version : versions) {
            if (version.settingsId() != null && !ids.add(version.settingsId())) {
                throw new IllegalArgumentException("settings version IDs must be unique");
            }
        }
        var currentCount = versions.stream()
                .filter(version -> Set.of("default", "active").contains(version.lifecycle()))
                .count();
        var scheduledCount = versions.stream()
                .filter(version -> "scheduled".equals(version.lifecycle()))
                .count();
        if (currentCount != 1 || scheduledCount > 1) {
            throw new IllegalArgumentException("settings require one current and at most one scheduled version");
        }
        if (canSchedule && scheduledCount != 0) {
            throw new IllegalArgumentException("an existing scheduled version blocks another schedule");
        }
        var maximumRevision = versions.stream()
                .mapToLong(InternationalSettingsVersion::lockVersion)
                .max()
                .orElseThrow();
        if (maximumRevision != lockVersion) {
            throw new IllegalArgumentException("aggregate lockVersion must match its latest version");
        }
        if (impactRules.size()
                != Set.copyOf(impactRules.stream().map(InternationalSettingsImpact::field).toList())
                        .size()) {
            throw new IllegalArgumentException("impact rule fields must be unique");
        }
    }
}
