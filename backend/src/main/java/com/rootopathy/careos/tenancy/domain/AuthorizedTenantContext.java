package com.rootopathy.careos.tenancy.domain;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Tenant identity that has already passed authentication, active-membership, permission, and purpose checks.
 * Raw request headers must never be converted directly into this context.
 */
public record AuthorizedTenantContext(
        UUID organizationId, UUID actorId, String purpose, String correlationId) {
    private static final Pattern SAFE_PURPOSE = Pattern.compile("[a-z0-9][a-z0-9._:-]{0,127}");
    private static final Pattern SAFE_CORRELATION_ID = Pattern.compile("[A-Za-z0-9._:-]{1,128}");

    public AuthorizedTenantContext {
        Objects.requireNonNull(organizationId, "organizationId");
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
