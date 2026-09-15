package com.rootopathy.careos.platform.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Caller-supplied, retry-stable request to extend retention or enable a legal hold. */
public record DocumentRetentionDirective(
        UUID retentionDirectiveId, String policyKey, Instant retainUntil, boolean legalHold) {
    public DocumentRetentionDirective {
        Objects.requireNonNull(retentionDirectiveId, "retentionDirectiveId");
        policyKey = PlatformValues.key(policyKey, "policyKey", 160);
        retainUntil = Objects.requireNonNull(retainUntil, "retainUntil")
                .truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
    }
}
