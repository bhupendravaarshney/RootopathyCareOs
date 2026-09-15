package com.rootopathy.careos.platform.domain;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Explicit deployment policy for short-lived access to a verified private clean object. */
public record DocumentAccessPolicy(
        String policyKey,
        Set<String> acceptedPurposes,
        Duration maximumTtl,
        Duration maximumAuthorizationAge,
        Duration maximumFutureSkew) {
    private static final Duration HARD_MAXIMUM_TTL = Duration.ofHours(1);
    private static final Duration HARD_MAXIMUM_AUTHORIZATION_AGE = Duration.ofMinutes(5);
    private static final Duration HARD_MAXIMUM_FUTURE_SKEW = Duration.ofMinutes(1);

    public DocumentAccessPolicy {
        policyKey = PlatformValues.key(policyKey, "policyKey", 120);
        Objects.requireNonNull(acceptedPurposes, "acceptedPurposes");
        if (acceptedPurposes.isEmpty() || acceptedPurposes.size() > 16) {
            throw new IllegalArgumentException("acceptedPurposes must contain between 1 and 16 entries");
        }
        var normalizedPurposes = new LinkedHashSet<String>();
        for (var purpose : acceptedPurposes) {
            normalizedPurposes.add(PlatformValues.purpose(purpose));
        }
        acceptedPurposes = Set.copyOf(normalizedPurposes);
        DocumentPromotionPolicy.requireWholeSecondDuration(
                maximumTtl, Duration.ofSeconds(1), HARD_MAXIMUM_TTL, "maximumTtl");
        DocumentPromotionPolicy.requireWholeSecondDuration(
                maximumAuthorizationAge,
                Duration.ofSeconds(1),
                HARD_MAXIMUM_AUTHORIZATION_AGE,
                "maximumAuthorizationAge");
        DocumentPromotionPolicy.requireWholeSecondDuration(
                maximumFutureSkew,
                Duration.ZERO,
                HARD_MAXIMUM_FUTURE_SKEW,
                "maximumFutureSkew");
    }

    public boolean acceptsPurpose(String purpose) {
        return acceptedPurposes.contains(purpose);
    }

    public String canonicalAcceptedPurposes() {
        return acceptedPurposes.stream().sorted().collect(Collectors.joining(","));
    }
}
