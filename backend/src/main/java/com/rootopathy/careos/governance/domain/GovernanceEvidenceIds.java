package com.rootopathy.careos.governance.domain;

import java.util.Objects;
import java.util.UUID;

public record GovernanceEvidenceIds(UUID auditEventId, UUID outboxEventId) {
    public GovernanceEvidenceIds {
        Objects.requireNonNull(auditEventId, "auditEventId");
        Objects.requireNonNull(outboxEventId, "outboxEventId");
    }
}
