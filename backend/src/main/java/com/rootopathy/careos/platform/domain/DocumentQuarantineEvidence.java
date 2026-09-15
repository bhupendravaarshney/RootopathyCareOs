package com.rootopathy.careos.platform.domain;

import java.time.Instant;
import java.util.Objects;

/** Authoritative metadata for one exact object version accepted into private quarantine. */
public record DocumentQuarantineEvidence(
        DocumentObjectReference document,
        long declaredBytes,
        String mediaType,
        String sha256,
        Instant quarantinedAt) {
    public DocumentQuarantineEvidence {
        Objects.requireNonNull(document, "document");
        var validated = new DocumentQuarantineRequest(
                document.documentId(), document.objectVersionId(), declaredBytes, mediaType, sha256);
        mediaType = validated.mediaType();
        sha256 = validated.sha256();
        Objects.requireNonNull(quarantinedAt, "quarantinedAt");
    }
}
