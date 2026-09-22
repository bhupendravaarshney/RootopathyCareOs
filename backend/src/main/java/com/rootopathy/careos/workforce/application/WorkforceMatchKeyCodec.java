package com.rootopathy.careos.workforce.application;

import java.util.UUID;

/**
 * Produces tenant-bound, deterministic match keys without exposing normalized identity values.
 */
public interface WorkforceMatchKeyCodec {
    String keyVersion();

    String digest(UUID organizationId, String keyType, String normalizedValue);
}
