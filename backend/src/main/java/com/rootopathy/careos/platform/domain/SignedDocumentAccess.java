package com.rootopathy.careos.platform.domain;

import java.net.URI;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** A bearer read URL that must remain memory-only and expire at the stated instant. */
public record SignedDocumentAccess(
        UUID accessGrantId,
        DocumentObjectReference document,
        URI readUrl,
        Instant expiresAt) {
    public SignedDocumentAccess {
        Objects.requireNonNull(accessGrantId, "accessGrantId");
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(readUrl, "readUrl");
        Objects.requireNonNull(expiresAt, "expiresAt");
        var scheme = readUrl.getScheme();
        if (!("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))
                || !readUrl.isAbsolute()
                || readUrl.getHost() == null
                || readUrl.getUserInfo() != null
                || readUrl.getFragment() != null
                || readUrl.getRawQuery() == null
                || readUrl.getRawQuery().isBlank()) {
            throw new IllegalArgumentException("readUrl must be an absolute signed HTTP URL");
        }
    }
}
