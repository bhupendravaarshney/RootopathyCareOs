package com.rootopathy.careos.platform.domain;

import java.time.Duration;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Append-only proof that immutable COMPLIANCE retention was applied to promoted content. */
public record DocumentRetentionEvidence(
        UUID retentionDirectiveId,
        DocumentObjectReference document,
        UUID previousRetentionDirectiveId,
        String policyKey,
        Set<String> acceptedPurposes,
        Duration minimumRetention,
        Duration maximumRetention,
        Duration maximumAuthorizationAge,
        Duration maximumFutureSkew,
        Instant retainUntil,
        boolean legalHold,
        String storageVersionSha256,
        UUID actorId,
        String purpose,
        String correlationId,
        Instant authorizedAt,
        Instant appliedAt) {
    public DocumentRetentionEvidence {
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(retentionDirectiveId, "retentionDirectiveId");
        if (retentionDirectiveId.equals(previousRetentionDirectiveId)) {
            throw new IllegalArgumentException("retention directive cannot reference itself");
        }
        var policy = new DocumentRetentionPolicy(
                policyKey,
                acceptedPurposes,
                minimumRetention,
                maximumRetention,
                maximumAuthorizationAge,
                maximumFutureSkew);
        policyKey = policy.policyKey();
        acceptedPurposes = policy.acceptedPurposes();
        minimumRetention = policy.minimumRetention();
        maximumRetention = policy.maximumRetention();
        maximumAuthorizationAge = policy.maximumAuthorizationAge();
        maximumFutureSkew = policy.maximumFutureSkew();
        purpose = PlatformValues.purpose(purpose);
        if (!policy.acceptsPurpose(purpose)) {
            throw new IllegalArgumentException("retention evidence requires an accepted purpose");
        }
        retainUntil = Objects.requireNonNull(retainUntil, "retainUntil")
                .truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        storageVersionSha256 = PlatformValues.sha256(storageVersionSha256);
        Objects.requireNonNull(actorId, "actorId");
        correlationId = PlatformValues.token(correlationId, "correlationId", 128);
        authorizedAt = Objects.requireNonNull(authorizedAt, "authorizedAt")
                .truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        appliedAt = Objects.requireNonNull(appliedAt, "appliedAt")
                .truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        try {
            if (retainUntil.isBefore(authorizedAt.plus(minimumRetention))
                    || retainUntil.isAfter(authorizedAt.plus(maximumRetention))
                    || !retainUntil.isAfter(appliedAt)) {
                throw new IllegalArgumentException("retention evidence is outside its policy window");
            }
        } catch (DateTimeException | ArithmeticException exception) {
            throw new IllegalArgumentException(
                    "retention evidence window is outside the supported range", exception);
        }
    }
}
