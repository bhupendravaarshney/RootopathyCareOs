package com.rootopathy.careos.governance.domain;

import java.util.UUID;

public record GovernanceEvidenceIds(UUID auditEventId, UUID outboxEventId) {
    public GovernanceEvidenceIds {
        if (auditEventId == null && outboxEventId == null) {
            throw new IllegalArgumentException("at least one evidence identifier is required");
        }
    }
}
