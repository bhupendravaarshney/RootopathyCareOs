package com.rootopathy.careos.administration.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record InternationalSettingsVersion(
        UUID settingsId,
        String source,
        String countryCode,
        String timezone,
        String locale,
        String language,
        String currencyCode,
        String weekStart,
        Instant effectiveFrom,
        Instant effectiveTo,
        String lifecycle,
        UUID supersedesId,
        long lockVersion,
        Instant updatedAt,
        InternationalSettingsFormatPreview formatPreview) {
    private static final Set<String> SOURCES =
            Set.of("organization_default", "configured");
    private static final Set<String> LIFECYCLES =
            Set.of("default", "active", "scheduled", "superseded");

    public InternationalSettingsVersion {
        source = Objects.requireNonNull(source, "source");
        countryCode = Objects.requireNonNull(countryCode, "countryCode");
        timezone = Objects.requireNonNull(timezone, "timezone");
        locale = Objects.requireNonNull(locale, "locale");
        language = Objects.requireNonNull(language, "language");
        currencyCode = Objects.requireNonNull(currencyCode, "currencyCode");
        weekStart = Objects.requireNonNull(weekStart, "weekStart");
        Objects.requireNonNull(effectiveFrom, "effectiveFrom");
        lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        Objects.requireNonNull(updatedAt, "updatedAt");
        Objects.requireNonNull(formatPreview, "formatPreview");
        if (!SOURCES.contains(source)) {
            throw new IllegalArgumentException("settings source is invalid");
        }
        if (!LIFECYCLES.contains(lifecycle)) {
            throw new IllegalArgumentException("settings lifecycle is invalid");
        }
        if ("configured".equals(source) && settingsId == null) {
            throw new IllegalArgumentException("configured settings require an ID");
        }
        if ("default".equals(lifecycle) && settingsId != null) {
            throw new IllegalArgumentException("a virtual default must not have an ID");
        }
        if (effectiveTo != null && !effectiveTo.isAfter(effectiveFrom)) {
            throw new IllegalArgumentException("settings effective range is invalid");
        }
        if (lockVersion < 0) {
            throw new IllegalArgumentException("settings lockVersion must not be negative");
        }
    }
}
