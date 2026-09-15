package com.rootopathy.careos.platform.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Immutable persisted scanner observation; only a matching current CLEAN result may be promoted later. */
public record DocumentScanAttestation(
        UUID attestationId, MalwareScanResult result, Instant recordedAt) {
    public DocumentScanAttestation {
        Objects.requireNonNull(attestationId, "attestationId");
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(recordedAt, "recordedAt");
    }
}
