package com.rootopathy.careos.governance.domain;

import java.util.UUID;

public record OutboxRecord(
        String eventName,
        int schemaVersion,
        String aggregateType,
        UUID aggregateId,
        String payloadJson) {
    public OutboxRecord {
        eventName = GovernanceValues.registryKey(eventName, "eventName", 180);
        if (schemaVersion < 1) {
            throw new IllegalArgumentException("schemaVersion must be positive");
        }
        aggregateType = GovernanceValues.typeKey(aggregateType, "aggregateType", 120);
        payloadJson = GovernanceValues.jsonObject(payloadJson, "payloadJson");
    }
}
