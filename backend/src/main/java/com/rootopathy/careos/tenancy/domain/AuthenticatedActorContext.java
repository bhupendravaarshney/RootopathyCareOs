package com.rootopathy.careos.tenancy.domain;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * An authenticated human actor bound to a controlled purpose and request correlation identifier.
 * Delivery code must derive the actor identifier from the authenticated principal, never request data.
 */
public record AuthenticatedActorContext(UUID actorId, String purpose, String correlationId) {
    private static final Pattern SAFE_PURPOSE = Pattern.compile("[a-z0-9][a-z0-9._:-]{0,127}");
    private static final Pattern SAFE_CORRELATION_ID = Pattern.compile("[A-Za-z0-9._:-]{1,128}");

    public AuthenticatedActorContext {
        Objects.requireNonNull(actorId, "actorId");
        purpose = requireMatch(purpose, SAFE_PURPOSE, "purpose");
        correlationId = requireMatch(correlationId, SAFE_CORRELATION_ID, "correlationId");
    }

    private static String requireMatch(String value, Pattern pattern, String name) {
        if (value == null || !pattern.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " has an invalid format");
        }
        return value;
    }
}
