package com.rootopathy.careos.identity.infrastructure.config;

import com.rootopathy.careos.identity.application.MfaAdministrationPolicy;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("careos.mfa-administration")
public record MfaAdministrationPolicyProperties(
        boolean enabled, Duration approvalTtl, Duration idempotencyTtl)
        implements MfaAdministrationPolicy {
    public MfaAdministrationPolicyProperties {
        requireBounded(approvalTtl, Duration.ofMinutes(30), "approvalTtl");
        requireBounded(idempotencyTtl, Duration.ofDays(7), "idempotencyTtl");
    }

    private static void requireBounded(Duration value, Duration maximum, String name) {
        if (value == null || value.isZero() || value.isNegative() || value.compareTo(maximum) > 0) {
            throw new IllegalArgumentException(
                    name + " must be positive and no greater than " + maximum);
        }
    }
}
