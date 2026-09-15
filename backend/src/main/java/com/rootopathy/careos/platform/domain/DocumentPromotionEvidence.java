package com.rootopathy.careos.platform.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

/** Immutable proof that a specific clean scan satisfied the snapshotted promotion policy. */
public record DocumentPromotionEvidence(
        DocumentScanAttestation scanAttestation,
        String policyKey,
        Set<String> acceptedScannerKeys,
        Duration maximumScanAge,
        Duration maximumFutureSkew,
        Instant promotedAt) {
    public DocumentPromotionEvidence {
        var authorization = new DocumentPromotionAuthorization(
                scanAttestation,
                policyKey,
                acceptedScannerKeys,
                maximumScanAge,
                maximumFutureSkew,
                promotedAt);
        scanAttestation = authorization.scanAttestation();
        policyKey = authorization.policyKey();
        acceptedScannerKeys = authorization.acceptedScannerKeys();
        maximumScanAge = authorization.maximumScanAge();
        maximumFutureSkew = authorization.maximumFutureSkew();
        promotedAt = authorization.authorizedAt();
    }

    public DocumentObjectReference document() {
        return scanAttestation.result().document();
    }
}
