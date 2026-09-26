package com.rootopathy.careos.assessment.application;

import com.rootopathy.careos.assessment.domain.AssessmentScreen;
import com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public interface AssessmentStore {
    Projection projection(AuthorizedTenantContext context, ScreenQuery query);

    Set<String> permissions(AuthorizedTenantContext context);

    MutationResult mutate(AuthorizedTenantContext context, MutationCommand command);

    record ScreenQuery(
            String screenId,
            UUID patientId,
            UUID encounterId,
            UUID assessmentSessionId,
            String search,
            String status,
            int limit) {}

    record Projection(
            List<AssessmentScreen.Metric> metrics,
            List<AssessmentScreen.Column> columns,
            List<AssessmentScreen.Row> rows,
            List<AssessmentScreen.Notice> notices,
            Instant generatedAt) {}

    record MutationCommand(
            String screenId,
            String actionKey,
            UUID targetId,
            UUID patientId,
            UUID encounterId,
            UUID assessmentSessionId,
            Long expectedRevision,
            String reason,
            Map<String, String> fields,
            Instant now,
            String correlationId) {
        public MutationCommand {
            fields = Map.copyOf(fields == null ? Map.of() : fields);
        }
    }

    record MutationResult(
            UUID subjectId,
            UUID patientId,
            UUID encounterId,
            UUID assessmentSessionId,
            String subjectType,
            String auditEvent,
            String outboxEvent,
            String aggregateType,
            UUID outboxAggregateId,
            Map<String, Object> auditPayload,
            Map<String, Object> outboxPayload,
            int statusCode,
            long revision) {
        public MutationResult {
            auditPayload = Map.copyOf(auditPayload == null ? Map.of() : auditPayload);
            outboxPayload = Map.copyOf(outboxPayload == null ? Map.of() : outboxPayload);
        }
    }
}
