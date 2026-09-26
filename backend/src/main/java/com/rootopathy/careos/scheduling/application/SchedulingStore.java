package com.rootopathy.careos.scheduling.application;

import com.rootopathy.careos.scheduling.domain.SchedulingScreen;
import com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public interface SchedulingStore {
    Projection projection(AuthorizedTenantContext context, ScreenQuery query);

    Set<String> permissions(AuthorizedTenantContext context);

    MutationResult mutate(AuthorizedTenantContext context, MutationCommand command);

    record ScreenQuery(
            String screenId,
            UUID patientId,
            UUID appointmentId,
            UUID requestId,
            String search,
            String status,
            int limit) {}

    record Projection(
            List<SchedulingScreen.Metric> metrics,
            List<SchedulingScreen.Column> columns,
            List<SchedulingScreen.Row> rows,
            List<SchedulingScreen.Notice> notices,
            Instant generatedAt) {}

    record MutationCommand(
            String screenId,
            String actionKey,
            UUID targetId,
            UUID patientId,
            UUID appointmentId,
            UUID requestId,
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
            UUID appointmentId,
            UUID requestId,
            String subjectType,
            String auditEvent,
            String outboxEvent,
            String aggregateType,
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
