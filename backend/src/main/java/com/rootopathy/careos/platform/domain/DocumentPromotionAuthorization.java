package com.rootopathy.careos.platform.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/** In-memory authorization to copy one clean object; it is not durable promotion evidence. */
public record DocumentPromotionAuthorization(
        DocumentScanAttestation scanAttestation,
        String policyKey,
        Set<String> acceptedScannerKeys,
        Duration maximumScanAge,
        Duration maximumFutureSkew,
        Instant authorizedAt) {
    public DocumentPromotionAuthorization {
        Objects.requireNonNull(scanAttestation, "scanAttestation");
        var policy = new DocumentPromotionPolicy(
                policyKey, acceptedScannerKeys, maximumScanAge, maximumFutureSkew);
        policyKey = policy.policyKey();
        acceptedScannerKeys = policy.acceptedScannerKeys();
        maximumScanAge = policy.maximumScanAge();
        maximumFutureSkew = policy.maximumFutureSkew();
        Objects.requireNonNull(authorizedAt, "authorizedAt");
        var result = scanAttestation.result();
        if (result.verdict() != MalwareScanVerdict.CLEAN) {
            throw new IllegalArgumentException("promotion authorization requires a clean scan");
        }
        if (!acceptedScannerKeys.contains(result.scannerKey())) {
            throw new IllegalArgumentException("promotion authorization requires an accepted scanner");
        }
        if (result.scannedAt().isBefore(authorizedAt.minus(maximumScanAge))
                || result.scannedAt().isAfter(authorizedAt.plus(maximumFutureSkew))) {
            throw new IllegalArgumentException("promotion authorization scan is outside its policy window");
        }
    }

    public DocumentObjectReference document() {
        return scanAttestation.result().document();
    }
}
