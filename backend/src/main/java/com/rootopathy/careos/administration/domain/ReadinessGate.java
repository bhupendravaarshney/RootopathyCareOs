package com.rootopathy.careos.administration.domain;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

public record ReadinessGate(
        String key,
        String version,
        String label,
        String outcome,
        String reasonCode,
        String remediationCode,
        String detail,
        List<String> evidenceReferences,
        String href) {
    public static final String CATALOGUE_VERSION = "m1-readiness-v1";
    private static final Set<String> OUTCOMES =
            Set.of("complete", "warning", "blocked", "not_applicable");
    private static final Pattern CODE = Pattern.compile("[a-z][a-z0-9]*(\\.[a-z0-9_]+)+");
    private static final Pattern HREF = Pattern.compile("#/M1-[0-9]{2}");

    public ReadinessGate {
        key = required(key, "key", 100);
        version = required(version, "version", 80);
        label = required(label, "label", 160);
        outcome = required(outcome, "outcome", 32);
        reasonCode = required(reasonCode, "reasonCode", 160);
        remediationCode = required(remediationCode, "remediationCode", 160);
        detail = required(detail, "detail", 500);
        evidenceReferences = List.copyOf(
                Objects.requireNonNull(evidenceReferences, "evidenceReferences"));
        href = required(href, "href", 16);

        if (!CATALOGUE_VERSION.equals(version)) {
            throw new IllegalArgumentException("readiness gate version is not approved");
        }
        if (!OUTCOMES.contains(outcome)) {
            throw new IllegalArgumentException("readiness gate outcome is invalid");
        }
        if (!CODE.matcher(reasonCode).matches() || !CODE.matcher(remediationCode).matches()) {
            throw new IllegalArgumentException("readiness gate codes are invalid");
        }
        if (evidenceReferences.size() > 4
                || evidenceReferences.stream().anyMatch(reference ->
                        reference == null || reference.isBlank() || reference.length() > 160)
                || evidenceReferences.stream().distinct().count() != evidenceReferences.size()) {
            throw new IllegalArgumentException("readiness evidence references are invalid");
        }
        if (!HREF.matcher(href).matches()) {
            throw new IllegalArgumentException("readiness gate deep link is invalid");
        }
    }

    private static String required(String value, String field, int maximumLength) {
        var required = Objects.requireNonNull(value, field);
        if (required.isBlank() || required.length() > maximumLength) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return required;
    }
}
