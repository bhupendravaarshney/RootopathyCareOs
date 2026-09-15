package com.rootopathy.careos.tenancy.domain;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * A tenant candidate and an opaque credential presented by a non-interactive workload.
 * The credential must never be logged, persisted raw, or accepted from a browser session.
 */
public record ServiceIdentityAuthorizationRequest(
        UUID organizationId,
        String presentedCredential,
        String purpose,
        String correlationId,
        OperationKey requiredOperation) {
    private static final Pattern SAFE_CREDENTIAL =
            Pattern.compile("[A-Za-z0-9._~-]{32,512}");
    private static final Pattern SAFE_PURPOSE = Pattern.compile("[a-z0-9][a-z0-9._:-]{0,127}");
    private static final Pattern SAFE_CORRELATION_ID =
            Pattern.compile("[A-Za-z0-9._:-]{1,128}");

    public ServiceIdentityAuthorizationRequest {
        Objects.requireNonNull(organizationId, "organizationId");
        if (presentedCredential == null
                || !SAFE_CREDENTIAL.matcher(presentedCredential).matches()) {
            throw new IllegalArgumentException("presentedCredential has an invalid format");
        }
        purpose = requireMatch(purpose, SAFE_PURPOSE, "purpose");
        correlationId = requireMatch(correlationId, SAFE_CORRELATION_ID, "correlationId");
        Objects.requireNonNull(requiredOperation, "requiredOperation");
    }

    private static String requireMatch(String value, Pattern pattern, String name) {
        if (value == null || !pattern.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " has an invalid format");
        }
        return value;
    }

    @Override
    public String toString() {
        return "ServiceIdentityAuthorizationRequest[organizationId="
                + organizationId
                + ", presentedCredential=<redacted>, purpose="
                + purpose
                + ", correlationId="
                + correlationId
                + ", requiredOperation="
                + requiredOperation
                + "]";
    }
}
