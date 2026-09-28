package com.rootopathy.careos.document.application;

import com.rootopathy.careos.document.domain.DocumentScreen;
import com.rootopathy.careos.platform.domain.DocumentObjectReference;
import com.rootopathy.careos.platform.domain.DocumentPromotionEvidence;
import com.rootopathy.careos.platform.domain.DocumentQuarantineEvidence;
import com.rootopathy.careos.platform.domain.DocumentScanAttestation;
import com.rootopathy.careos.platform.domain.SignedDocumentAccess;
import com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public interface DocumentStore {
    Projection projection(AuthorizedTenantContext context, ScreenQuery query);

    Set<String> permissions(AuthorizedTenantContext context);

    MutationResult mutate(AuthorizedTenantContext context, MutationCommand command);

    MutationResult bindUpload(
            AuthorizedTenantContext context, UploadCommand command, DocumentQuarantineEvidence evidence);

    VersionReference version(
            AuthorizedTenantContext context, UUID documentId, UUID documentVersionId);

    MutationResult bindScan(
            AuthorizedTenantContext context,
            VersionReference version,
            DocumentScanAttestation scan,
            DocumentPromotionEvidence promotion);

    MutationResult bindAccess(
            AuthorizedTenantContext context,
            VersionReference version,
            String purposeKey,
            String reason,
            SignedDocumentAccess access);

    AccessReference access(
            AuthorizedTenantContext context,
            UUID documentId,
            UUID documentVersionId,
            UUID accessIntentId,
            String purposeKey);

    record ScreenQuery(
            String screenId,
            UUID patientId,
            UUID documentId,
            UUID diagnosticReportId,
            String search,
            String status,
            int limit) {}

    record Projection(
            List<DocumentScreen.Metric> metrics,
            List<DocumentScreen.Column> columns,
            List<DocumentScreen.Row> rows,
            List<DocumentScreen.Notice> notices,
            Instant generatedAt) {}

    record MutationCommand(
            String screenId,
            String actionKey,
            UUID targetId,
            UUID patientId,
            UUID documentId,
            UUID documentVersionId,
            UUID diagnosticReportId,
            Long expectedRevision,
            String reason,
            Map<String, String> fields,
            Instant now,
            String correlationId) {
        public MutationCommand {
            fields = Map.copyOf(fields == null ? Map.of() : fields);
        }
    }

    record UploadCommand(
            UUID documentId,
            UUID documentVersionId,
            UUID patientId,
            UUID encounterId,
            UUID assessmentSessionId,
            Long expectedRevision,
            String title,
            String documentTypeKey,
            String sourceKey,
            String originalFileName,
            String mediaType,
            long declaredBytes,
            String sha256,
            String replacementReason,
            Instant now) {}

    record VersionReference(
            UUID documentId,
            UUID documentVersionId,
            UUID patientId,
            String mediaType,
            long byteCount,
            String sha256,
            String status,
            long revision,
            DocumentObjectReference object) {}

    record AccessReference(
            UUID accessIntentId,
            UUID documentId,
            UUID documentVersionId,
            UUID platformAccessGrantId,
            String purposeKey,
            DocumentObjectReference object,
            Instant expiresAt) {}

    record MutationResult(
            UUID subjectId,
            UUID patientId,
            UUID documentId,
            UUID documentVersionId,
            UUID diagnosticReportId,
            UUID resultFlagId,
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
