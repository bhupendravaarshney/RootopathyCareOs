package com.rootopathy.careos.governance.domain;

import java.util.Objects;

public record GovernanceEvidence(AuditRecord audit, OutboxRecord outbox) {
    public GovernanceEvidence {
        Objects.requireNonNull(audit, "audit");
        Objects.requireNonNull(outbox, "outbox");
    }
}
