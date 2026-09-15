package com.rootopathy.careos.platform.domain;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** In-memory authority to sign one promoted object; it is not durable access evidence. */
public record DocumentAccessAuthorization(
        UUID accessGrantId,
        DocumentPromotionEvidence promotionEvidence,
        String policyKey,
        Set<String> acceptedPurposes,
        Duration requestedTtl,
        Duration maximumTtl,
        Duration maximumAuthorizationAge,
        Duration maximumFutureSkew,
        String purpose,
        Instant authorizedAt) {
    public DocumentAccessAuthorization {
        Objects.requireNonNull(accessGrantId, "accessGrantId");
        Objects.requireNonNull(promotionEvidence, "promotionEvidence");
        var policy = new DocumentAccessPolicy(
                policyKey,
                acceptedPurposes,
                maximumTtl,
                maximumAuthorizationAge,
                maximumFutureSkew);
        policyKey = policy.policyKey();
        acceptedPurposes = policy.acceptedPurposes();
        maximumTtl = policy.maximumTtl();
        maximumAuthorizationAge = policy.maximumAuthorizationAge();
        maximumFutureSkew = policy.maximumFutureSkew();
        DocumentPromotionPolicy.requireWholeSecondDuration(
                requestedTtl, Duration.ofSeconds(1), maximumTtl, "requestedTtl");
        purpose = PlatformValues.purpose(purpose);
        if (!policy.acceptsPurpose(purpose)) {
            throw new IllegalArgumentException("access authorization requires an accepted purpose");
        }
        authorizedAt = Objects.requireNonNull(authorizedAt, "authorizedAt")
                .truncatedTo(ChronoUnit.MICROS);
        try {
            authorizedAt.plus(requestedTtl);
        } catch (DateTimeException | ArithmeticException exception) {
            throw new IllegalArgumentException("access authorization expiry is outside the supported range", exception);
        }
    }

    public DocumentObjectReference document() {
        return promotionEvidence.document();
    }

    public Instant expiresAt() {
        return authorizedAt.plus(requestedTtl);
    }
}
