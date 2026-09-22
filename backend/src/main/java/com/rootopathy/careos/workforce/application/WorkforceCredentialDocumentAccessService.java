package com.rootopathy.careos.workforce.application;

import com.rootopathy.careos.governance.application.GovernedMutationExecutor;
import com.rootopathy.careos.governance.domain.AuditRecord;
import com.rootopathy.careos.governance.domain.GovernanceEvidence;
import com.rootopathy.careos.governance.domain.GovernedMutation;
import com.rootopathy.careos.governance.domain.IdempotencyCommand;
import com.rootopathy.careos.governance.domain.IdempotencyOutcome;
import com.rootopathy.careos.governance.domain.IdempotentResponse;
import com.rootopathy.careos.platform.application.DocumentAccessException;
import com.rootopathy.careos.platform.application.DocumentAccessOperations;
import com.rootopathy.careos.platform.application.DocumentStorageException;
import com.rootopathy.careos.platform.application.PlatformCapabilityUnavailableException;
import com.rootopathy.careos.tenancy.application.TenantAuthorizationOperations;
import com.rootopathy.careos.tenancy.domain.AuthenticatedActorContext;
import com.rootopathy.careos.tenancy.domain.OperationKey;
import com.rootopathy.careos.tenancy.domain.TenantAuthorizationRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
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
    private static final Pattern IDEMPOTENCY_KEY =
            Pattern.compile("[A-Za-z0-9._:-]{16,128}");

    private final GovernedMutationExecutor mutations;
    private final TenantAuthorizationOperations authorization;
    private final WorkforceStore store;
    private final ObjectProvider<DocumentAccessOperations> documentAccess;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public WorkforceCredentialDocumentAccessService(
            GovernedMutationExecutor mutations,
            TenantAuthorizationOperations authorization,
            WorkforceStore store,
            ObjectProvider<DocumentAccessOperations> documentAccess,
            ObjectMapper objectMapper,
            Clock clock) {
        this.mutations = mutations;
        this.authorization = authorization;
        this.store = store;
        this.documentAccess = documentAccess;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public IdempotencyOutcome access(Command command) {
        Objects.requireNonNull(command, "command");
        var purposeCode = token(command.purposeCode(), "purposeCode", 2, 80);
        if (!PURPOSES.contains(purposeCode)) {
            throw invalid("The credential-document access purpose is not supported.");
        }
        var reason = reason(command.reason());
        var idempotencyKey = command.idempotencyKey();
        if (idempotencyKey == null || !IDEMPOTENCY_KEY.matcher(idempotencyKey).matches()) {
            throw invalid("Idempotency-Key must contain 16 to 128 safe characters.");
        }
        var request = authorizationRequest(
                command.organizationId(),
                command.actorId(),
                command.correlationId(),
                purposeCode,
                reason,
                command.recentAuthenticationAt(),
                command.mfaAuthenticatedAt());
        var now = clock.instant();
        return mutations.execute(
                request,
                new IdempotencyCommand(
                        "credential.document.read",
                        idempotencyKey,
                        hash(
                                command.credentialId(),
                                command.documentId(),
                                purposeCode,
                                reason),
                        now.plus(ACCESS_TTL)),
                context -> {
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
                        auditPayload.put(
                                "evidenceDigest", credentialDocument.evidenceDigest());
                        var response = new Response(
                                credentialDocument.credentialId(),
                                credentialDocument.documentId(),
                                signed.accessGrantId(),
                                accessUrl(
                                        command.organizationId(),
                                        credentialDocument.credentialId(),
                                        credentialDocument.documentId(),
                                        signed.accessGrantId(),
                                        purposeCode),
                                signed.expiresAt(),
                                credentialDocument.mediaType(),
                                credentialDocument.byteCount(),
                                credentialDocument.evidenceDigest(),
                                purposeCode);
                        return new GovernedMutation(
                                new IdempotentResponse(200, "application/json", json(response)),
                                GovernanceEvidence.auditOnly(new AuditRecord(
                                        "credential.document.accessed",
                                        1,
                                        "credential_document",
                                        credentialDocument.documentId(),
                                        reason,
                                        json(auditPayload))));
                    } catch (DocumentAccessException
                            | DocumentStorageException
                            | PlatformCapabilityUnavailableException exception) {
                        throw unavailable();
                    }
                });
    }

    public Redirect open(OpenCommand command) {
        Objects.requireNonNull(command, "command");
        var purposeCode = token(command.purposeCode(), "purposeCode", 2, 80);
        if (!PURPOSES.contains(purposeCode)) {
            throw invalid("The credential-document access purpose is not supported.");
        }
        var request = authorizationRequest(
                command.organizationId(),
                command.actorId(),
                command.correlationId(),
                purposeCode,
                null,
                command.recentAuthenticationAt(),
                command.mfaAuthenticatedAt());
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
                var signed = accessOperations.reopenReadAccess(
                        context,
                        credentialDocument.object(),
                        Objects.requireNonNull(command.accessIntentId(), "accessIntentId"));
                return new Redirect(signed.readUrl().toASCIIString(), signed.expiresAt());
            } catch (DocumentAccessException exception) {
                throw new WorkforceException(
                        WorkforceException.Reason.NOT_FOUND,
                        "The credential-document access grant is unavailable or expired.");
            } catch (DocumentStorageException
                    | PlatformCapabilityUnavailableException exception) {
                throw unavailable();
            }
        });
    }

    private static TenantAuthorizationRequest authorizationRequest(
            UUID organizationId,
            UUID actorId,
            String correlationId,
            String purposeCode,
            String reason,
            Instant recentAuthenticationAt,
            Instant mfaAuthenticatedAt) {
        return new TenantAuthorizationRequest(
                Objects.requireNonNull(organizationId, "organizationId"),
                new AuthenticatedActorContext(
                        Objects.requireNonNull(actorId, "actorId"), purposeCode, correlationId),
                new OperationKey("credential.document.read"),
                reason,
                recentAuthenticationAt,
                mfaAuthenticatedAt,
                null);
    }

    private static String accessUrl(
            UUID organizationId,
            UUID credentialId,
            UUID documentId,
            UUID accessIntentId,
            String purposeCode) {
        return "/api/v1/organizations/" + organizationId
                + "/workforce/credentials/" + credentialId
                + "/documents/" + documentId
                + "/accesses/" + accessIntentId
                + "?purposeCode=" + purposeCode;
    }

    private static String hash(Object... values) {
        try {
            var canonical = new StringBuilder("m2-credential-document-access-v1|");
            for (var value : values) {
                var text = Objects.toString(value, "<null>");
                canonical.append(text.length()).append(':').append(text).append(';');
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to hash credential-document access.", exception);
        }
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
            String idempotencyKey,
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

    public record OpenCommand(
            UUID organizationId,
            UUID actorId,
            String correlationId,
            UUID credentialId,
            UUID documentId,
            UUID accessIntentId,
            String purposeCode,
            Instant recentAuthenticationAt,
            Instant mfaAuthenticatedAt) {}

    public record Redirect(String readUrl, Instant expiresAt) {}
}
