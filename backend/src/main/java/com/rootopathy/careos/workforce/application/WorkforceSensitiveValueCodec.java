package com.rootopathy.careos.workforce.application;

import java.util.UUID;

public interface WorkforceSensitiveValueCodec {
    byte[] encrypt(UUID organizationId, String field, String normalizedValue);

    String digest(UUID organizationId, String field, String normalizedValue);
}
