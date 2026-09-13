package com.rootopathy.careos.governance.domain;

import java.util.UUID;

public record AuditRecord(
        String eventName,
        int schemaVersion,
        String subjectType,
        UUID subjectId,
        String reason,
        String payloadJson) {
    public AuditRecord {
        eventName = GovernanceValues.registryKey(eventName, "eventName", 180);
        if (schemaVersion < 1) {
            throw new IllegalArgumentException("schemaVersion must be positive");
        }
        subjectType = GovernanceValues.typeKey(subjectType, "subjectType", 120);
        reason = GovernanceValues.optionalReason(reason);
        payloadJson = GovernanceValues.jsonObject(payloadJson, "payloadJson");
    }
}
