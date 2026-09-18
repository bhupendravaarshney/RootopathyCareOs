package com.rootopathy.careos.administration.domain;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.IllformedLocaleException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record OrganizationProfile(
        UUID organizationId,
        String legalName,
        String displayName,
        String tradingName,
        String organizationType,
        String countryCode,
        String timezone,
        String locale,
        String lifecycleStatus,
        boolean editable,
        long lockVersion,
        Instant updatedAt) {
    private static final Set<String> ORGANIZATION_TYPES =
            Set.of("care_provider", "care_network", "administrative");
    private static final Set<String> ELIGIBLE_LIFECYCLES =
            Set.of("draft", "under_review", "active", "suspended");
    private static final Set<String> ISO_COUNTRIES = Set.of(Locale.getISOCountries());

    public OrganizationProfile {
        Objects.requireNonNull(organizationId, "organizationId");
        legalName = Objects.requireNonNull(legalName, "legalName");
        displayName = Objects.requireNonNull(displayName, "displayName");
        countryCode = Objects.requireNonNull(countryCode, "countryCode");
        timezone = Objects.requireNonNull(timezone, "timezone");
        lifecycleStatus = Objects.requireNonNull(lifecycleStatus, "lifecycleStatus");
        if (lockVersion < 0) {
            throw new IllegalArgumentException("lockVersion must not be negative");
        }
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    public List<String> readinessGaps() {
        var gaps = new ArrayList<String>();
        if (!validName(legalName, 2, 200)) {
            gaps.add("legalName");
        }
        if (!validName(displayName, 2, 120)) {
            gaps.add("displayName");
        }
        if (tradingName != null && !validName(tradingName, 2, 160)) {
            gaps.add("tradingName");
        }
        if (organizationType == null || !ORGANIZATION_TYPES.contains(organizationType)) {
            gaps.add("organizationType");
        }
        if (!ISO_COUNTRIES.contains(countryCode)) {
            gaps.add("countryCode");
        }
        if (!ZoneId.getAvailableZoneIds().contains(timezone)) {
            gaps.add("timezone");
        }
        if (!validLocale(locale)) {
            gaps.add("locale");
        }
        if (!ELIGIBLE_LIFECYCLES.contains(lifecycleStatus)) {
            gaps.add("lifecycleStatus");
        }
        return List.copyOf(gaps);
    }

    public boolean readinessComplete() {
        return readinessGaps().isEmpty();
    }

    private static boolean validName(String value, int minimum, int maximum) {
        if (value == null
                || !value.equals(value.strip())
                || value.indexOf('<') >= 0
                || value.indexOf('>') >= 0
                || value.codePoints().anyMatch(Character::isISOControl)) {
            return false;
        }
        var length = value.codePointCount(0, value.length());
        return length >= minimum && length <= maximum;
    }

    private static boolean validLocale(String value) {
        if (value == null || value.isBlank() || !value.equals(value.strip())) {
            return false;
        }
        try {
            var parsed = new Locale.Builder().setLanguageTag(value).build();
            return !parsed.getLanguage().isBlank() && !"und".equals(parsed.toLanguageTag());
        } catch (IllformedLocaleException exception) {
            return false;
        }
    }
}
