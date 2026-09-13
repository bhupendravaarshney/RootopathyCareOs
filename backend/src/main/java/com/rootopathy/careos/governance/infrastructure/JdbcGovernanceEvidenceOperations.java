package com.rootopathy.careos.governance.infrastructure;

import com.rootopathy.careos.governance.application.GovernanceEvidenceOperations;
import com.rootopathy.careos.governance.domain.GovernanceEvidence;
import com.rootopathy.careos.governance.domain.GovernanceEvidenceIds;
import com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcGovernanceEvidenceOperations implements GovernanceEvidenceOperations {
    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    public JdbcGovernanceEvidenceOperations(JdbcTemplate jdbcTemplate, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    @Override
    public GovernanceEvidenceIds record(
            AuthorizedTenantContext context, GovernanceEvidence evidence) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(evidence, "evidence");
        TenantTransactionContextVerifier.requireAuthorizedWriteTransaction(jdbcTemplate, context);

        var auditId = UUID.randomUUID();
        var outboxId = UUID.randomUUID();
        var occurredAt = Timestamp.from(clock.instant());
        var audit = evidence.audit();
        jdbcTemplate.update(
                """
                INSERT INTO audit_events
                    (id, organization_id, actor_user_id, event_name, schema_version,
                     subject_type, subject_id, reason, payload, purpose, correlation_id, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?)
                """,
                auditId,
                context.organizationId(),
                context.actorId(),
                audit.eventName(),
                audit.schemaVersion(),
                audit.subjectType(),
                audit.subjectId(),
                audit.reason(),
                audit.payloadJson(),
                context.purpose(),
                context.correlationId(),
                occurredAt);

        var outbox = evidence.outbox();
        jdbcTemplate.update(
                """
                INSERT INTO outbox_events
                    (id, organization_id, actor_user_id, event_name, schema_version,
                     aggregate_type, aggregate_id, payload, purpose, correlation_id,
                     occurred_at, available_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?, ?)
                """,
                outboxId,
                context.organizationId(),
                context.actorId(),
                outbox.eventName(),
                outbox.schemaVersion(),
                outbox.aggregateType(),
                outbox.aggregateId(),
                outbox.payloadJson(),
                context.purpose(),
                context.correlationId(),
                occurredAt,
                occurredAt);
        return new GovernanceEvidenceIds(auditId, outboxId);
    }
}
