package com.rootopathy.careos.identity.infrastructure.config;

import com.rootopathy.careos.identity.application.InvitationPolicy;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("careos.invitations")
public record InvitationPolicyProperties(
        boolean enabled,
        Duration tokenTtl,
        Duration idempotencyTtl,
        int acceptanceAttemptLimit,
        Duration acceptanceAttemptWindow) implements InvitationPolicy {
    private static final Duration MAXIMUM_TOKEN_TTL = Duration.ofDays(7);
    private static final Duration MAXIMUM_IDEMPOTENCY_TTL = Duration.ofDays(7);

    public InvitationPolicyProperties {
        requireBounded(tokenTtl, MAXIMUM_TOKEN_TTL, "tokenTtl");
        requireBounded(idempotencyTtl, MAXIMUM_IDEMPOTENCY_TTL, "idempotencyTtl");
        requireBounded(acceptanceAttemptWindow, Duration.ofDays(1), "acceptanceAttemptWindow");
        if (acceptanceAttemptLimit < 1 || acceptanceAttemptLimit > 100) {
            throw new IllegalArgumentException("acceptanceAttemptLimit must be between 1 and 100");
        }
    }

    private static void requireBounded(Duration value, Duration maximum, String name) {
        if (value == null || value.isZero() || value.isNegative() || value.compareTo(maximum) > 0) {
            throw new IllegalArgumentException(name + " must be positive and no greater than " + maximum);
        }
    }
}
