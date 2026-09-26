package com.rootopathy.careos.patientregistry.application;

import com.rootopathy.careos.patientregistry.domain.PatientRegistryScreen;
import com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public interface PatientRegistryStore {
    Projection projection(AuthorizedTenantContext context, ScreenQuery query);

    Set<String> permissions(AuthorizedTenantContext context);

    ImpactAnalysis previewImpact(AuthorizedTenantContext context, MutationCommand command);

    MutationResult mutate(AuthorizedTenantContext context, MutationCommand command);

    record ScreenQuery(
            String screenId,
            UUID patientId,
            UUID registrationId,
            String search,
            String status,
            int limit) {}

    record Projection(
            List<PatientRegistryScreen.Metric> metrics,
            List<PatientRegistryScreen.Column> columns,
            List<PatientRegistryScreen.Row> rows,
            List<PatientRegistryScreen.Notice> notices,
            Instant generatedAt) {}

    record MutationCommand(
            String screenId,
            String actionKey,
            UUID targetId,
            UUID patientId,
            UUID registrationId,
            Long expectedRevision,
            String decision,
            String reason,
            Map<String, String> fields,
            Instant now) {
        public MutationCommand {
            fields = Map.copyOf(fields == null ? Map.of() : fields);
        }
    }

    record ImpactAnalysis(String digest, List<ImpactItem> items) {
        public ImpactAnalysis {
            items = List.copyOf(items == null ? List.of() : items);
        }

        public boolean blocked() {
            return items.stream().anyMatch(item -> item.tone().equals("blocker"));
        }
    }

    record ImpactItem(String code, String tone, String detail, int affectedCount) {}

    record AdditionalAudit(
            String eventName,
            String subjectType,
            UUID subjectId,
            String reason,
            Map<String, Object> payload) {
        public AdditionalAudit {
            payload = java.util.Collections.unmodifiableMap(
                    new java.util.LinkedHashMap<>(payload == null ? Map.of() : payload));
        }
    }

    record MutationResult(
            UUID subjectId,
            UUID patientId,
            String subjectType,
            String auditEvent,
            String outboxEvent,
            String aggregateType,
            Map<String, Object> auditPayload,
            Map<String, Object> outboxPayload,
            int statusCode,
            long revision,
            List<AdditionalAudit> additionalAudits) {
        public MutationResult(
                UUID subjectId,
                UUID patientId,
                String subjectType,
                String auditEvent,
                String outboxEvent,
                String aggregateType,
                Map<String, Object> auditPayload,
                Map<String, Object> outboxPayload,
                int statusCode,
                long revision) {
            this(
                    subjectId,
                    patientId,
                    subjectType,
                    auditEvent,
                    outboxEvent,
                    aggregateType,
                    auditPayload,
                    outboxPayload,
                    statusCode,
                    revision,
                    List.of());
        }

        public MutationResult {
            auditPayload = java.util.Collections.unmodifiableMap(
                    new java.util.LinkedHashMap<>(auditPayload == null ? Map.of() : auditPayload));
            outboxPayload = java.util.Collections.unmodifiableMap(
                    new java.util.LinkedHashMap<>(outboxPayload == null ? Map.of() : outboxPayload));
            additionalAudits = List.copyOf(
                    additionalAudits == null ? List.of() : additionalAudits);
        }
    }
}
