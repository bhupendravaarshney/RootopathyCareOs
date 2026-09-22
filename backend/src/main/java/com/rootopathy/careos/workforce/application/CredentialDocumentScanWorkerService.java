package com.rootopathy.careos.workforce.application;

import com.rootopathy.careos.governance.application.GovernanceEvidenceOperations;
import com.rootopathy.careos.governance.domain.AuditRecord;
import com.rootopathy.careos.governance.domain.GovernanceEvidence;
import com.rootopathy.careos.governance.domain.OutboxRecord;
import com.rootopathy.careos.platform.application.DocumentPromotionOperations;
import com.rootopathy.careos.platform.application.DocumentSecurityOperations;
import com.rootopathy.careos.platform.domain.DocumentPromotionEvidence;
import com.rootopathy.careos.platform.domain.MalwareScanVerdict;
import com.rootopathy.careos.tenancy.application.ServiceIdentityAuthorizationOperations;
import com.rootopathy.careos.tenancy.domain.OperationKey;
import com.rootopathy.careos.tenancy.domain.ServiceIdentityAuthorizationRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public final class CredentialDocumentScanWorkerService {
    private static final String SERVICE_IDENTITY = "m2-credential-scan-coordinator-v1";
    private final ServiceIdentityAuthorizationOperations authorization;
    private final WorkforceWorkerStore store;
    private final DocumentSecurityOperations security;
    private final DocumentPromotionOperations promotion;
    private final GovernanceEvidenceOperations evidence;
    private final ObjectMapper objectMapper;

    public CredentialDocumentScanWorkerService(
            ServiceIdentityAuthorizationOperations authorization,
            WorkforceWorkerStore store,
            DocumentSecurityOperations security,
            DocumentPromotionOperations promotion,
            GovernanceEvidenceOperations evidence,
            ObjectMapper objectMapper) {
        this.authorization = authorization;
        this.store = store;
        this.security = security;
        this.promotion = promotion;
        this.evidence = evidence;
        this.objectMapper = objectMapper;
    }

    public Result scan(Command command) {
        return authorization.execute(
                new ServiceIdentityAuthorizationRequest(
                        command.organizationId(),
                        command.presentedCredential(),
                        SERVICE_IDENTITY,
                        command.correlationId(),
                        new OperationKey("m2.credential.scan.bind")),
                context -> {
                    var work = store.document(context, command.documentId());
                    var attestation = security.scan(context, work.object());
                    DocumentPromotionEvidence promoted = null;
                    if (attestation.result().verdict() == MalwareScanVerdict.CLEAN) {
                        promoted = promotion.promote(context, work.object());
                    }
                    var bound = store.bindScan(context, work, attestation, promoted);
                    record(context, bound, attestation.result().scannerKey());
                    return new Result(
                            bound.documentId(),
                            bound.credentialId(),
                            bound.outcome(),
                            bound.failureCode());
                });
    }

    private void record(
            com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext context,
            WorkforceWorkerStore.DocumentBinding binding,
            String scannerPolicyVersion) {
        evidence.record(
                context,
                GovernanceEvidence.auditOnly(new AuditRecord(
                        "credential.document.scan_started",
                        1,
                        "credential_document",
                        binding.documentId(),
                        null,
                        json(ordered(
                                "credentialId", binding.credentialId(),
                                "documentId", binding.documentId(),
                                "scanAttemptId", binding.scanAttemptId(),
                                "scannerPolicyVersion", scannerPolicyVersion,
                                "outcome", "started",
                                "failureCode", null)))));
        var event = switch (binding.outcome()) {
            case "clean" -> "credential.document.clean";
            case "infected" -> "credential.document.infected";
            default -> "credential.document.failed";
        };
        var audit = ordered(
                "credentialId", binding.credentialId(),
                "documentId", binding.documentId(),
                "scanAttemptId", binding.scanAttemptId(),
                "scannerPolicyVersion", scannerPolicyVersion,
                "outcome", binding.outcome(),
                "failureCode", binding.failureCode());
        if (Set.of("clean", "infected").contains(binding.outcome())) {
            var outbox = ordered(
                    "credentialId", binding.credentialId(),
                    "documentId", binding.documentId(),
                    "digest", binding.digest(),
                    "outcome", binding.outcome());
            evidence.record(
                    context,
                    new GovernanceEvidence(
                            new AuditRecord(event, 1, "credential_document", binding.documentId(), null, json(audit)),
                            new OutboxRecord(
                                    binding.outcome().equals("clean")
                                            ? "credential.document.clean"
                                            : "credential.document.rejected",
                                    1,
                                    "credential_document",
                                    binding.documentId(),
                                    json(outbox))));
        } else {
            evidence.record(
                    context,
                    GovernanceEvidence.auditOnly(new AuditRecord(
                            event,
                            1,
                            "credential_document",
                            binding.documentId(),
                            null,
                            json(audit))));
        }
    }

    private static LinkedHashMap<String, Object> ordered(Object... values) {
        var result = new LinkedHashMap<String, Object>();
        for (var index = 0; index < values.length; index += 2) {
            result.put((String) values[index], values[index + 1]);
        }
        return result;
    }

    private String json(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to serialize credential scan evidence.", exception);
        }
    }

    public record Command(
            UUID organizationId,
            UUID documentId,
            String presentedCredential,
            String correlationId) {}

    public record Result(
            UUID documentId, UUID credentialId, String outcome, String failureCode) {}
}
