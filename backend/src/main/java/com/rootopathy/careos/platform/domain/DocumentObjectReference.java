package com.rootopathy.careos.platform.domain;

import java.util.Objects;
import java.util.UUID;

/** Opaque application reference. Storage bucket names and object keys must not escape adapters. */
public record DocumentObjectReference(UUID organizationId, UUID documentId, UUID objectVersionId) {
    public DocumentObjectReference {
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(documentId, "documentId");
        Objects.requireNonNull(objectVersionId, "objectVersionId");
    }
}
