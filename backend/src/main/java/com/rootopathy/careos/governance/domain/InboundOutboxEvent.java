package com.rootopathy.careos.governance.domain;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

/** The immutable source envelope used to deduplicate one consumer's processing attempt. */
public record InboundOutboxEvent(
        UUID organizationId,
        String consumerKey,
        UUID sourceEventId,
        String eventName,
        int schemaVersion,
        String aggregateType,
        UUID aggregateId,
        String payloadJson,
        String sourceCorrelationId,
        Instant occurredAt) {
    public InboundOutboxEvent {
        Objects.requireNonNull(organizationId, "organizationId");
        consumerKey = GovernanceValues.registryKey(consumerKey, "consumerKey", 160);
        Objects.requireNonNull(sourceEventId, "sourceEventId");
        eventName = GovernanceValues.registryKey(eventName, "eventName", 180);
        if (schemaVersion < 1) {
            throw new IllegalArgumentException("schemaVersion must be positive");
        }
        aggregateType = GovernanceValues.typeKey(aggregateType, "aggregateType", 120);
        Objects.requireNonNull(aggregateId, "aggregateId");
        payloadJson = GovernanceValues.jsonObject(payloadJson, "payloadJson");
        sourceCorrelationId = GovernanceValues.correlationId(
                sourceCorrelationId, "sourceCorrelationId");
        occurredAt = Objects.requireNonNull(occurredAt, "occurredAt")
                .truncatedTo(ChronoUnit.MICROS);
    }

    public static InboundOutboxEvent from(String consumerKey, OutboxEnvelope envelope) {
        Objects.requireNonNull(envelope, "envelope");
        return new InboundOutboxEvent(
                envelope.organizationId(),
                consumerKey,
                envelope.eventId(),
                envelope.eventName(),
                envelope.schemaVersion(),
                envelope.aggregateType(),
                envelope.aggregateId(),
                envelope.payloadJson(),
                envelope.correlationId(),
                envelope.occurredAt());
    }
}
