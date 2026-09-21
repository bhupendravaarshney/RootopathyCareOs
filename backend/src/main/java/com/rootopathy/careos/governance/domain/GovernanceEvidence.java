package com.rootopathy.careos.governance.domain;

public record GovernanceEvidence(AuditRecord audit, OutboxRecord outbox) {
    public GovernanceEvidence {
        if (audit == null && outbox == null) {
            throw new IllegalArgumentException("at least one governance evidence record is required");
        }
    }

    public static GovernanceEvidence auditOnly(AuditRecord audit) {
        return new GovernanceEvidence(java.util.Objects.requireNonNull(audit, "audit"), null);
    }

    public static GovernanceEvidence outboxOnly(OutboxRecord outbox) {
        return new GovernanceEvidence(null, java.util.Objects.requireNonNull(outbox, "outbox"));
    }
}
