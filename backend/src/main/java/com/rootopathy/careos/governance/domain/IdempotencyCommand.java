package com.rootopathy.careos.governance.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

public record IdempotencyCommand(
        String operationKey, String idempotencyKey, String requestHash, Instant expiresAt) {
    private static final Pattern IDEMPOTENCY_KEY =
            Pattern.compile("[A-Za-z0-9._:-]{16,180}");
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    public IdempotencyCommand {
        operationKey = GovernanceValues.registryKey(operationKey, "operationKey", 160);
        if (idempotencyKey == null || !IDEMPOTENCY_KEY.matcher(idempotencyKey).matches()) {
            throw new IllegalArgumentException("idempotencyKey has an invalid format");
        }
        if (requestHash == null || !SHA_256.matcher(requestHash).matches()) {
            throw new IllegalArgumentException("requestHash must be a lowercase SHA-256 digest");
        }
        Objects.requireNonNull(expiresAt, "expiresAt");
    }
}
