package com.rootopathy.careos.platform.domain;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/** Provider-neutral confirmation of COMPLIANCE retention on one exact storage version. */
public record DocumentRetentionReceipt(
        DocumentObjectReference document,
        Instant retainUntil,
        boolean legalHold,
        String storageVersionSha256) {
    public DocumentRetentionReceipt {
        Objects.requireNonNull(document, "document");
        retainUntil = Objects.requireNonNull(retainUntil, "retainUntil")
                .truncatedTo(ChronoUnit.SECONDS);
        storageVersionSha256 = PlatformValues.sha256(storageVersionSha256);
    }
}
