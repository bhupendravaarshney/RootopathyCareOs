package com.rootopathy.careos.governance.domain;

import java.time.Duration;
import java.util.Objects;

public record OutboxPublicationPolicy(
        int batchSize,
        int maxAttempts,
        Duration claimLease,
        Duration initialRetryDelay,
        Duration maximumRetryDelay) {
    public OutboxPublicationPolicy {
        if (batchSize < 1 || batchSize > 100) {
            throw new IllegalArgumentException("batchSize must be between 1 and 100");
        }
        if (maxAttempts < 1 || maxAttempts > 100) {
            throw new IllegalArgumentException("maxAttempts must be between 1 and 100");
        }
        Objects.requireNonNull(claimLease, "claimLease");
        Objects.requireNonNull(initialRetryDelay, "initialRetryDelay");
        Objects.requireNonNull(maximumRetryDelay, "maximumRetryDelay");
        if (claimLease.compareTo(Duration.ofSeconds(1)) < 0
                || claimLease.compareTo(Duration.ofMinutes(15)) > 0) {
            throw new IllegalArgumentException("claimLease must be between 1 second and 15 minutes");
        }
        if (initialRetryDelay.isNegative()
                || maximumRetryDelay.isNegative()
                || initialRetryDelay.compareTo(maximumRetryDelay) > 0
                || maximumRetryDelay.compareTo(Duration.ofHours(24)) > 0) {
            throw new IllegalArgumentException("retry delay bounds are invalid");
        }
    }

    public Duration retryDelayForAttempt(int attemptCount) {
        if (attemptCount < 1) {
            throw new IllegalArgumentException("attemptCount must be positive");
        }
        var delay = initialRetryDelay;
        for (var attempt = 1; attempt < attemptCount && delay.compareTo(maximumRetryDelay) < 0; attempt++) {
            if (delay.compareTo(maximumRetryDelay.dividedBy(2)) > 0) {
                return maximumRetryDelay;
            }
            delay = delay.multipliedBy(2);
        }
        return delay.compareTo(maximumRetryDelay) > 0 ? maximumRetryDelay : delay;
    }
}
