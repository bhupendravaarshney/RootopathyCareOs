package com.rootopathy.careos.platform.domain;

import java.time.Duration;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Short-lived authority to extend retention or enable a hold on one promoted object version. */
public record DocumentRetentionAuthorization(
        UUID retentionDirectiveId,
        DocumentPromotionEvidence promotionEvidence,
        UUID previousRetentionDirectiveId,
        String policyKey,
        Set<String> acceptedPurposes,
        Duration minimumRetention,
        Duration maximumRetention,
        Duration maximumAuthorizationAge,
        Duration maximumFutureSkew,
        Instant retainUntil,
        boolean legalHold,
        String purpose,
        Instant authorizedAt) {
    public DocumentRetentionAuthorization {
        Objects.requireNonNull(retentionDirectiveId, "retentionDirectiveId");
        Objects.requireNonNull(promotionEvidence, "promotionEvidence");
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
            throw new IllegalArgumentException("retention authorization requires an accepted purpose");
        }
        retainUntil = Objects.requireNonNull(retainUntil, "retainUntil")
                .truncatedTo(ChronoUnit.SECONDS);
        authorizedAt = Objects.requireNonNull(authorizedAt, "authorizedAt")
                .truncatedTo(ChronoUnit.MICROS);
        try {
            if (retainUntil.isBefore(authorizedAt.plus(minimumRetention))
                    || retainUntil.isAfter(authorizedAt.plus(maximumRetention))) {
                throw new IllegalArgumentException(
                        "retainUntil is outside the configured retention window");
            }
        } catch (DateTimeException | ArithmeticException exception) {
            throw new IllegalArgumentException(
                    "retention authorization window is outside the supported range", exception);
        }
    }

    public DocumentObjectReference document() {
        return promotionEvidence.document();
    }
}
