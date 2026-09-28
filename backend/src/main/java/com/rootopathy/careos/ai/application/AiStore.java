package com.rootopathy.careos.ai.application;

import com.rootopathy.careos.ai.domain.AiScreen;
import com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public interface AiStore {
    Projection projection(AuthorizedTenantContext context, ScreenQuery query);

    Set<String> permissions(AuthorizedTenantContext context);

    MutationResult mutate(AuthorizedTenantContext context, MutationCommand command);

    ProcessingContext prepareProcessing(
            AuthorizedTenantContext context, MutationCommand command, Map<String, String> parameters);

    MutationResult completeProcessing(
            AuthorizedTenantContext context,
            ProcessingContext processing,
            AiProcessingPort.Result result,
            Instant completedAt);

    MutationResult failProcessing(
            AuthorizedTenantContext context,
            ProcessingContext processing,
            String failureCode,
            Instant completedAt);

    record ScreenQuery(
            String screenId,
            UUID patientId,
            UUID encounterId,
            UUID aiSessionId,
            String search,
            String status,
            int limit) {}

    record Projection(
            List<AiScreen.Metric> metrics,
            List<AiScreen.Column> columns,
            List<AiScreen.Row> rows,
            List<AiScreen.Notice> notices,
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

    record ProcessingContext(
            UUID aiSessionId,
            UUID patientId,
            UUID encounterId,
            long sessionRevision,
            UUID jobContractId,
            int attemptNumber,
            String purposeKey,
            String sessionType,
            String providerKey,
            String modelKey,
            String modelVersion,
            String promptKey,
            String promptVersion,
            String outputSchemaKey,
            int outputSchemaVersion,
            int retentionDays,
            Instant startedAt,
            Map<String, String> parameters,
            List<AiProcessingPort.InputReference> inputs) {
        public ProcessingContext {
            parameters = Map.copyOf(parameters);
            inputs = List.copyOf(inputs);
        }
    }

    record MutationResult(
            UUID subjectId,
            UUID aiSessionId,
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
