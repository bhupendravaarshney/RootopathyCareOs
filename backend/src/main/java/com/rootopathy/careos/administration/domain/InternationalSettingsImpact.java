package com.rootopathy.careos.administration.domain;

import java.util.Objects;
import java.util.Set;

public record InternationalSettingsImpact(
        String field, String code, String severity, String description) {
    private static final Set<String> FIELDS = Set.of(
            "countryCode", "timezone", "locale", "language", "currencyCode", "weekStart");
    private static final Set<String> SEVERITIES = Set.of("information", "warning");

    public InternationalSettingsImpact {
        field = Objects.requireNonNull(field, "field");
        code = Objects.requireNonNull(code, "code");
        severity = Objects.requireNonNull(severity, "severity");
        description = Objects.requireNonNull(description, "description");
        if (!FIELDS.contains(field)) {
            throw new IllegalArgumentException("impact field is not approved");
        }
        if (!code.matches("m1[.]settings[.][a-z_]+")) {
            throw new IllegalArgumentException("impact code is invalid");
        }
        if (!SEVERITIES.contains(severity)) {
            throw new IllegalArgumentException("impact severity is invalid");
        }
        if (description.isBlank() || !description.equals(description.strip())) {
            throw new IllegalArgumentException("impact description is invalid");
        }
    }
}
