package com.rootopathy.careos.governance.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ConsumerInboxReceipt(
        UUID organizationId,
        String consumerKey,
        UUID sourceEventId,
        Instant receivedAt,
        boolean replayed) {
    public ConsumerInboxReceipt {
        Objects.requireNonNull(organizationId, "organizationId");
        consumerKey = GovernanceValues.registryKey(consumerKey, "consumerKey", 160);
        Objects.requireNonNull(sourceEventId, "sourceEventId");
        Objects.requireNonNull(receivedAt, "receivedAt");
    }
}
