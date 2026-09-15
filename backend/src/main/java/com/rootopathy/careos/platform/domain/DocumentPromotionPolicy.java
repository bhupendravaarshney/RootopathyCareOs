package com.rootopathy.careos.platform.domain;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Explicit deployment policy used to decide whether durable scan evidence may be promoted. */
public record DocumentPromotionPolicy(
        String policyKey,
        Set<String> acceptedScannerKeys,
        Duration maximumScanAge,
        Duration maximumFutureSkew) {
    private static final Duration MAXIMUM_SCAN_AGE = Duration.ofDays(30);
    private static final Duration MAXIMUM_FUTURE_SKEW = Duration.ofMinutes(5);

    public DocumentPromotionPolicy {
        policyKey = PlatformValues.key(policyKey, "policyKey", 120);
        Objects.requireNonNull(acceptedScannerKeys, "acceptedScannerKeys");
        if (acceptedScannerKeys.isEmpty() || acceptedScannerKeys.size() > 16) {
            throw new IllegalArgumentException("acceptedScannerKeys must contain between 1 and 16 entries");
        }
        var normalizedScannerKeys = new LinkedHashSet<String>();
        for (var scannerKey : acceptedScannerKeys) {
            normalizedScannerKeys.add(PlatformValues.key(scannerKey, "scannerKey", 120));
        }
        acceptedScannerKeys = Set.copyOf(normalizedScannerKeys);
        requireWholeSecondDuration(
                maximumScanAge, Duration.ofSeconds(1), MAXIMUM_SCAN_AGE, "maximumScanAge");
        requireWholeSecondDuration(
                maximumFutureSkew, Duration.ZERO, MAXIMUM_FUTURE_SKEW, "maximumFutureSkew");
    }

    public boolean acceptsScanner(String scannerKey) {
        return acceptedScannerKeys.contains(scannerKey);
    }

    public String canonicalAcceptedScannerKeys() {
        return acceptedScannerKeys.stream().sorted().collect(Collectors.joining(","));
    }

    static void requireWholeSecondDuration(
            Duration value, Duration minimum, Duration maximum, String name) {
        Objects.requireNonNull(value, name);
        if (value.isNegative()
                || value.compareTo(minimum) < 0
                || value.compareTo(maximum) > 0
                || value.getNano() != 0) {
            throw new IllegalArgumentException(name + " is outside the supported whole-second range");
        }
    }
}
