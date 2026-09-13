package com.rootopathy.careos.governance.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record OutboxEnvelope(
        UUID eventId,
        UUID organizationId,
        String eventName,
        int schemaVersion,
        String aggregateType,
        UUID aggregateId,
        String payloadJson,
        String correlationId,
        Instant occurredAt,
        int attemptCount,
        UUID claimToken,
        String claimedBy,
        Instant claimedUntil) {
    public OutboxEnvelope {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(organizationId, "organizationId");
        eventName = GovernanceValues.registryKey(eventName, "eventName", 180);
        if (schemaVersion < 1) {
            throw new IllegalArgumentException("schemaVersion must be positive");
        }
        aggregateType = GovernanceValues.typeKey(aggregateType, "aggregateType", 120);
        payloadJson = GovernanceValues.jsonObject(payloadJson, "payloadJson");
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        if (attemptCount < 1) {
            throw new IllegalArgumentException("attemptCount must be positive for a claimed event");
        }
        Objects.requireNonNull(claimToken, "claimToken");
        Objects.requireNonNull(claimedBy, "claimedBy");
        Objects.requireNonNull(claimedUntil, "claimedUntil");
    }
}
