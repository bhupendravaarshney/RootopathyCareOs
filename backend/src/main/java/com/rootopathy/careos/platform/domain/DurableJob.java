package com.rootopathy.careos.platform.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Job payloads must contain opaque references rather than clinical text or credentials. */
public record DurableJob(
        UUID jobId,
        String jobType,
        int schemaVersion,
        String referenceJson,
        String deduplicationKey,
        Instant notBefore) {
    public DurableJob {
        Objects.requireNonNull(jobId, "jobId");
        jobType = PlatformValues.key(jobType, "jobType", 160);
        if (schemaVersion < 1) {
            throw new IllegalArgumentException("schemaVersion must be positive");
        }
        referenceJson = PlatformValues.jsonObject(referenceJson, "referenceJson");
        deduplicationKey = PlatformValues.token(deduplicationKey, "deduplicationKey", 160);
        Objects.requireNonNull(notBefore, "notBefore");
        try {
            notBefore.toEpochMilli();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("notBefore is outside the supported range", exception);
        }
    }
}
