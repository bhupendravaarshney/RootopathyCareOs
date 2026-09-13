package com.rootopathy.careos.platform.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** A time-bounded ownership claim. The lease token is opaque and must never be logged. */
public record DurableJobClaim(
        UUID organizationId,
        DurableJob job,
        int attempt,
        String leaseToken,
        Instant leaseExpiresAt) {
    public DurableJobClaim {
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(job, "job");
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt must be positive");
        }
        leaseToken = PlatformValues.token(leaseToken, "leaseToken", 160);
        Objects.requireNonNull(leaseExpiresAt, "leaseExpiresAt");
    }
}
