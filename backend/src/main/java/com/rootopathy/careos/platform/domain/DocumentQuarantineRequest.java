package com.rootopathy.careos.platform.domain;

import java.util.Objects;
import java.util.UUID;

public record DocumentQuarantineRequest(
        UUID documentId,
        UUID objectVersionId,
        long declaredBytes,
        String mediaType,
        String sha256) {
    public DocumentQuarantineRequest {
        Objects.requireNonNull(documentId, "documentId");
        Objects.requireNonNull(objectVersionId, "objectVersionId");
        if (declaredBytes < 1) {
            throw new IllegalArgumentException("declaredBytes must be positive");
        }
        mediaType = PlatformValues.mediaType(mediaType);
        sha256 = PlatformValues.sha256(sha256);
    }
}
