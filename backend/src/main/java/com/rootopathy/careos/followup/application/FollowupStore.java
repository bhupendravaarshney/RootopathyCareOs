package com.rootopathy.careos.followup.application;

import com.rootopathy.careos.followup.domain.FollowupScreen;
import com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public interface FollowupStore {
    Projection projection(AuthorizedTenantContext context, ScreenQuery query);

    Set<String> permissions(AuthorizedTenantContext context);

    MutationResult mutate(AuthorizedTenantContext context, MutationCommand command);

    record ScreenQuery(
            String screenId,
            UUID patientId,
            UUID encounterId,
            UUID followupPlanId,
            String search,
            String status,
            int limit) {}

    record Projection(
            List<FollowupScreen.Metric> metrics,
            List<FollowupScreen.Column> columns,
            List<FollowupScreen.Row> rows,
            List<FollowupScreen.Notice> notices,
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
            UUID followupPlanId,
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
