package com.rootopathy.careos.careplan.application;

import com.rootopathy.careos.careplan.domain.CarePlanScreen;
import com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public interface CarePlanStore {
    Projection projection(AuthorizedTenantContext context, ScreenQuery query);

    Set<String> permissions(AuthorizedTenantContext context);

    MutationResult mutate(AuthorizedTenantContext context, MutationCommand command);

    record ScreenQuery(
            String screenId,
            UUID patientId,
            UUID encounterId,
            UUID carePlanId,
            String search,
            String status,
            int limit) {}

    record Projection(
            List<CarePlanScreen.Metric> metrics,
            List<CarePlanScreen.Column> columns,
            List<CarePlanScreen.Row> rows,
            List<CarePlanScreen.Notice> notices,
            Instant generatedAt) {}

    record MutationCommand(
            String screenId,
            String actionKey,
            UUID targetId,
            Long expectedRevision,
            String reason,
            Map<String, String> fields,
            Instant recentAuthenticationAt,
            Instant mfaAuthenticatedAt,
            Instant now,
            String correlationId) {
        public MutationCommand {
            fields = Map.copyOf(fields == null ? Map.of() : fields);
        }
    }

    record MutationResult(
            UUID subjectId,
            UUID carePlanId,
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
