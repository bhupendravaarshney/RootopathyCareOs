package com.rootopathy.careos.platform.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Append-only proof that a bounded signed-read grant was issued for promoted clean content. */
public record DocumentAccessGrantEvidence(
        UUID accessGrantId,
        DocumentPromotionEvidence promotionEvidence,
        String policyKey,
        Set<String> acceptedPurposes,
        Duration requestedTtl,
        Duration maximumTtl,
        Duration maximumAuthorizationAge,
        Duration maximumFutureSkew,
        UUID actorId,
        String purpose,
        String correlationId,
        Instant authorizedAt,
        Instant grantedAt,
        Instant expiresAt) {
    public DocumentAccessGrantEvidence {
        var authorization = new DocumentAccessAuthorization(
                accessGrantId,
                promotionEvidence,
                policyKey,
                acceptedPurposes,
                requestedTtl,
                maximumTtl,
                maximumAuthorizationAge,
                maximumFutureSkew,
                purpose,
                authorizedAt);
        accessGrantId = authorization.accessGrantId();
        promotionEvidence = authorization.promotionEvidence();
        policyKey = authorization.policyKey();
        acceptedPurposes = authorization.acceptedPurposes();
        requestedTtl = authorization.requestedTtl();
        maximumTtl = authorization.maximumTtl();
        maximumAuthorizationAge = authorization.maximumAuthorizationAge();
        maximumFutureSkew = authorization.maximumFutureSkew();
        purpose = authorization.purpose();
        authorizedAt = authorization.authorizedAt();
        Objects.requireNonNull(actorId, "actorId");
        correlationId = PlatformValues.token(correlationId, "correlationId", 128);
        Objects.requireNonNull(grantedAt, "grantedAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (!expiresAt.equals(authorization.expiresAt()) || !expiresAt.isAfter(grantedAt)) {
            throw new IllegalArgumentException("access evidence expiry does not match its authorization");
        }
    }

    public DocumentObjectReference document() {
        return promotionEvidence.document();
    }
}
