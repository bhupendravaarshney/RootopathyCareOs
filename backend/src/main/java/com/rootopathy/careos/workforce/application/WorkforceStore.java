package com.rootopathy.careos.workforce.application;

import com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext;
import com.rootopathy.careos.workforce.domain.WorkforceScreen;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public interface WorkforceStore {
    Projection projection(AuthorizedTenantContext context, ScreenQuery query);

    Set<String> permissions(AuthorizedTenantContext context);

    MutationResult mutate(AuthorizedTenantContext context, MutationCommand command);

    MutationResult bindCredentialDocument(
            AuthorizedTenantContext context,
            DocumentCommand command,
            UUID platformDocumentId,
            UUID platformObjectVersionId,
            Instant quarantinedAt);

    record ScreenQuery(
            String screenId,
            UUID memberId,
            String search,
            String status,
            int limit) {}

    record Projection(
            List<WorkforceScreen.Metric> metrics,
            List<WorkforceScreen.Column> columns,
            List<WorkforceScreen.Row> rows,
            List<WorkforceScreen.Notice> notices,
            Instant generatedAt) {}

    record MutationCommand(
            String screenId,
            String actionKey,
            UUID targetId,
            UUID memberId,
            Long expectedRevision,
            String decision,
            String reason,
            Map<String, String> fields,
            List<UUID> evidenceIds,
            Instant now) {
        public MutationCommand {
            fields = Map.copyOf(fields == null ? Map.of() : fields);
            evidenceIds = List.copyOf(evidenceIds == null ? List.of() : evidenceIds);
        }
    }

    record DocumentCommand(
            UUID credentialId,
            UUID memberId,
            String fileName,
            String mediaType,
            long declaredSize,
            String sha256,
            String retentionClass,
            String reason,
            InputStream content,
            Instant now) {}

    record MutationResult(
            UUID subjectId,
            String subjectType,
            String auditEvent,
            String outboxEvent,
            String aggregateType,
            Map<String, Object> auditPayload,
            Map<String, Object> outboxPayload,
            int statusCode,
            long revision) {
        public MutationResult {
            auditPayload = java.util.Collections.unmodifiableMap(
                    new java.util.LinkedHashMap<>(auditPayload == null ? Map.of() : auditPayload));
            outboxPayload = java.util.Collections.unmodifiableMap(
                    new java.util.LinkedHashMap<>(outboxPayload == null ? Map.of() : outboxPayload));
        }
    }
}
