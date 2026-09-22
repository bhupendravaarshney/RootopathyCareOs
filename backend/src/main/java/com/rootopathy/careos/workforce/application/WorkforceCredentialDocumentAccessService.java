package com.rootopathy.careos.workforce.application;

import com.rootopathy.careos.governance.application.GovernanceEvidenceOperations;
import com.rootopathy.careos.governance.domain.AuditRecord;
import com.rootopathy.careos.governance.domain.GovernanceEvidence;
import com.rootopathy.careos.platform.application.DocumentAccessException;
import com.rootopathy.careos.platform.application.DocumentAccessOperations;
import com.rootopathy.careos.platform.application.PlatformCapabilityUnavailableException;
import com.rootopathy.careos.tenancy.application.TenantAuthorizationOperations;
import com.rootopathy.careos.tenancy.domain.AuthenticatedActorContext;
import com.rootopathy.careos.tenancy.domain.OperationKey;
import com.rootopathy.careos.tenancy.domain.TenantAuthorizationRequest;
import java.time.Duration;
import java.time.Instant;
import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public final class WorkforceCredentialDocumentAccessService {
    private static final Set<String> PURPOSES = Set.of(
            "credentialing_review",
            "regulatory_evidence",
            "security_investigation",
            "employment_record_request",
            "data_correction");
    private static final Duration ACCESS_TTL = Duration.ofSeconds(60);

    private final TenantAuthorizationOperations authorization;
    private final GovernanceEvidenceOperations governanceEvidence;
    private final WorkforceStore store;
    private final ObjectProvider<DocumentAccessOperations> documentAccess;
    private final ObjectMapper objectMapper;

    public WorkforceCredentialDocumentAccessService(
            TenantAuthorizationOperations authorization,
            GovernanceEvidenceOperations governanceEvidence,
            WorkforceStore store,
            ObjectProvider<DocumentAccessOperations> documentAccess,
            ObjectMapper objectMapper) {
        this.authorization = authorization;
        this.governanceEvidence = governanceEvidence;
        this.store = store;
        this.documentAccess = documentAccess;
        this.objectMapper = objectMapper;
    }

    public Response access(Command command) {
        Objects.requireNonNull(command, "command");
        var purposeCode = token(command.purposeCode(), "purposeCode", 2, 80);
        if (!PURPOSES.contains(purposeCode)) {
            throw invalid("The credential-document access purpose is not supported.");
        }
        var reason = reason(command.reason());
        var request = new TenantAuthorizationRequest(
                Objects.requireNonNull(command.organizationId(), "organizationId"),
                new AuthenticatedActorContext(
                        Objects.requireNonNull(command.actorId(), "actorId"),
                        purposeCode,
                        command.correlationId()),
                new OperationKey("credential.document.read"),
                reason,
                command.recentAuthenticationAt(),
                command.mfaAuthenticatedAt(),
                null);
        return authorization.execute(request, context -> {
            var credentialDocument = store.credentialDocument(
                    context,
                    Objects.requireNonNull(command.credentialId(), "credentialId"),
                    Objects.requireNonNull(command.documentId(), "documentId"));
            var accessOperations = documentAccess.getIfAvailable();
            if (accessOperations == null) {
                throw unavailable();
            }
            try {
                var signed = accessOperations.createReadAccess(
                        context, credentialDocument.object(), ACCESS_TTL);
                var auditPayload = new LinkedHashMap<String, Object>();
                auditPayload.put("credentialId", credentialDocument.credentialId());
                auditPayload.put("documentId", credentialDocument.documentId());
                auditPayload.put("accessIntentId", signed.accessGrantId());
                auditPayload.put("purposeCode", purposeCode);
                auditPayload.put("evidenceDigest", credentialDocument.evidenceDigest());
                governanceEvidence.record(
                        context,
                        GovernanceEvidence.auditOnly(new AuditRecord(
                                "credential.document.accessed",
                                1,
                                "credential_document",
                                credentialDocument.documentId(),
                                reason,
                                json(auditPayload))));
                return new Response(
                        credentialDocument.credentialId(),
                        credentialDocument.documentId(),
                        signed.accessGrantId(),
                        signed.readUrl().toASCIIString(),
                        signed.expiresAt(),
                        credentialDocument.mediaType(),
                        credentialDocument.byteCount(),
                        credentialDocument.evidenceDigest(),
                        purposeCode);
            } catch (DocumentAccessException | PlatformCapabilityUnavailableException exception) {
                throw unavailable();
            }
        });
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Unable to serialize credential-document access evidence.", exception);
        }
    }

    private static String reason(String value) {
        var normalized = Normalizer.normalize(
                value == null ? "" : value.strip(), Normalizer.Form.NFC);
        var length = normalized.codePointCount(0, normalized.length());
        if (length < 10
                || length > 500
                || normalized.codePoints().anyMatch(Character::isISOControl)) {
            throw invalid("reason must contain between 10 and 500 safe characters.");
        }
        return normalized;
    }

    private static String token(String value, String name, int minimum, int maximum) {
        var normalized = Normalizer.normalize(
                value == null ? "" : value.strip(), Normalizer.Form.NFC);
        var length = normalized.codePointCount(0, normalized.length());
        if (length < minimum
                || length > maximum
                || !normalized.matches("[a-z][a-z0-9_]*")) {
            throw invalid(name + " has an invalid format.");
        }
        return normalized;
    }

    private static WorkforceException invalid(String message) {
        return new WorkforceException(WorkforceException.Reason.INVALID, message);
    }

    private static WorkforceException unavailable() {
        return new WorkforceException(
                WorkforceException.Reason.DEPENDENCY_UNAVAILABLE,
                "Clean credential evidence access is not currently available.");
    }

    public record Command(
            UUID organizationId,
            UUID actorId,
            String correlationId,
            UUID credentialId,
            UUID documentId,
            String purposeCode,
            String reason,
            Instant recentAuthenticationAt,
            Instant mfaAuthenticatedAt) {}

    public record Response(
            UUID credentialId,
            UUID documentId,
            UUID accessIntentId,
            String readUrl,
            Instant expiresAt,
            String mediaType,
            long byteCount,
            String evidenceDigest,
            String purposeCode) {}
}
