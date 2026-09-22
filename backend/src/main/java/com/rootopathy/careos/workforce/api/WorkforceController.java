package com.rootopathy.careos.workforce.api;

import com.rootopathy.careos.governance.domain.IdempotencyOutcome;
import com.rootopathy.careos.identity.api.AuthenticationSessionState;
import com.rootopathy.careos.shared.api.ApiProblemException;
import com.rootopathy.careos.shared.api.CorrelationIdFilter;
import com.rootopathy.careos.shared.domain.AuthenticatedActor;
import com.rootopathy.careos.workforce.application.WorkforceException;
import com.rootopathy.careos.workforce.application.WorkforceCredentialDocumentAccessService;
import com.rootopathy.careos.workforce.application.WorkforceService;
import com.rootopathy.careos.workforce.domain.WorkforceScreen;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@Validated
@RestController
public final class WorkforceController {
    private static final String SCREEN_PATTERN = "M2-(0[1-9]|1[0-9]|2[0-9])";
    private static final String ACTION_PATTERN = "[a-z][a-z0-9]*(?:-[a-z0-9]+)*";
    private static final String IDEMPOTENCY_PATTERN = "[A-Za-z0-9._:-]{16,128}";
    private final WorkforceService workforce;
    private final WorkforceCredentialDocumentAccessService credentialDocumentAccess;

    public WorkforceController(
            WorkforceService workforce,
            WorkforceCredentialDocumentAccessService credentialDocumentAccess) {
        this.workforce = workforce;
        this.credentialDocumentAccess = credentialDocumentAccess;
    }

    @GetMapping("/api/v1/organizations/{organizationId}/workforce/screens/{screenId}")
    ResponseEntity<WorkforceScreen> screen(
            @PathVariable UUID organizationId,
            @PathVariable @Pattern(regexp = SCREEN_PATTERN) String screenId,
            @RequestParam(required = false) UUID memberId,
            @RequestParam(required = false) @Size(max = 100) String q,
            @RequestParam(required = false) @Size(max = 120) String status,
            @RequestParam(required = false) @Min(1) @Max(100) Integer limit,
            @RequestParam(required = false) @Size(max = 2048) String cursor,
            Authentication authentication,
            HttpServletRequest request) {
        var actor = actor(authentication);
        var session = request.getSession(false);
        var response = workforce.screen(new WorkforceService.ReadCommand(
                organizationId,
                actor.id(),
                CorrelationIdFilter.from(request),
                screenId,
                memberId,
                q,
                status,
                limit,
                cursor,
                assurance(session, AuthenticationSessionState.RECENT_AUTHENTICATION_AT),
                assurance(session, AuthenticationSessionState.MFA_AUTHENTICATED_AT)));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(response);
    }

    @PostMapping(
            path = "/api/v1/organizations/{organizationId}/workforce/screens/{screenId}/actions/{actionKey}",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> action(
            @PathVariable UUID organizationId,
            @PathVariable @Pattern(regexp = SCREEN_PATTERN) String screenId,
            @PathVariable @Pattern(regexp = ACTION_PATTERN) String actionKey,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestHeader("Idempotency-Key")
                    @Pattern(regexp = IDEMPOTENCY_PATTERN)
                    String idempotencyKey,
            @Valid @RequestBody WorkforceActionRequest body,
            Authentication authentication,
            HttpServletRequest request) {
        var actor = actor(authentication);
        var session = request.getSession(false);
        var outcome = workforce.act(new WorkforceService.ActionCommand(
                organizationId,
                actor.id(),
                CorrelationIdFilter.from(request),
                screenId,
                actionKey,
                body.targetId(),
                body.memberId(),
                body.decision(),
                body.reason(),
                body.fields(),
                body.evidenceIds(),
                body.impactToken(),
                ifMatch,
                idempotencyKey,
                assurance(session, AuthenticationSessionState.RECENT_AUTHENTICATION_AT),
                assurance(session, AuthenticationSessionState.MFA_AUTHENTICATED_AT)));
        return response(outcome);
    }

    @PostMapping(
            path = "/api/v1/organizations/{organizationId}/workforce/screens/{screenId}/actions/{actionKey}/impact-preview",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<WorkforceService.ImpactPreviewResponse> impactPreview(
            @PathVariable UUID organizationId,
            @PathVariable @Pattern(regexp = SCREEN_PATTERN) String screenId,
            @PathVariable @Pattern(regexp = ACTION_PATTERN) String actionKey,
            @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
            @Valid @RequestBody WorkforceActionRequest body,
            Authentication authentication,
            HttpServletRequest request) {
        var actor=actor(authentication);
        var session=request.getSession(false);
        var preview=workforce.previewImpact(new WorkforceService.ImpactPreviewCommand(
                organizationId,actor.id(),CorrelationIdFilter.from(request),screenId,actionKey,
                body.targetId(),body.memberId(),body.decision(),body.reason(),body.fields(),
                body.evidenceIds(),ifMatch,
                assurance(session,AuthenticationSessionState.RECENT_AUTHENTICATION_AT),
                assurance(session,AuthenticationSessionState.MFA_AUTHENTICATED_AT)));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(preview);
    }

    @PostMapping(
            path = "/api/v1/organizations/{organizationId}/workforce/credentials/{credentialId}/documents",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<String> uploadCredentialDocument(
            @PathVariable UUID organizationId,
            @PathVariable UUID credentialId,
            @RequestHeader("Idempotency-Key")
                    @Pattern(regexp = IDEMPOTENCY_PATTERN)
                    String idempotencyKey,
            @Valid @RequestPart("metadata") CredentialDocumentMetadata metadata,
            @RequestPart("file") MultipartFile file,
            Authentication authentication,
            HttpServletRequest request) {
        if (file.isEmpty()) {
            throw new WorkforceException(
                    WorkforceException.Reason.INVALID, "The credential document must not be empty.");
        }
        var actor = actor(authentication);
        var session = request.getSession(false);
        try {
            var outcome = workforce.uploadCredentialDocument(
                    new WorkforceService.DocumentUploadCommand(
                            organizationId,
                            actor.id(),
                            CorrelationIdFilter.from(request),
                            credentialId,
                            metadata.memberId(),
                            file.getOriginalFilename(),
                            file.getContentType(),
                            file.getSize(),
                            metadata.sha256(),
                            metadata.retentionClass(),
                            metadata.reason(),
                            file.getInputStream(),
                            idempotencyKey,
                            assurance(session, AuthenticationSessionState.RECENT_AUTHENTICATION_AT),
                            assurance(session, AuthenticationSessionState.MFA_AUTHENTICATED_AT)));
            return response(outcome);
        } catch (IOException exception) {
            throw new WorkforceException(
                    WorkforceException.Reason.INVALID, "The credential document could not be read.");
        }
    }

    @PostMapping(
            path = "/api/v1/organizations/{organizationId}/workforce/evidence/{evidenceId}/accesses",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> accessEvidence(
            @PathVariable UUID organizationId,
            @PathVariable UUID evidenceId,
            @RequestHeader("Idempotency-Key")
                    @Pattern(regexp = IDEMPOTENCY_PATTERN)
                    String idempotencyKey,
            @Valid @RequestBody WorkforceEvidenceAccessRequest body,
            Authentication authentication,
            HttpServletRequest request) {
        var actor = actor(authentication);
        var session = request.getSession(false);
        var outcome = workforce.accessEvidence(new WorkforceService.EvidenceAccessCommand(
                organizationId,
                actor.id(),
                CorrelationIdFilter.from(request),
                evidenceId,
                body.memberId(),
                body.projection(),
                body.purposeCode(),
                body.reason(),
                idempotencyKey,
                assurance(session, AuthenticationSessionState.RECENT_AUTHENTICATION_AT),
                assurance(session, AuthenticationSessionState.MFA_AUTHENTICATED_AT)));
        return response(outcome);
    }

    @PostMapping(
            path = "/api/v1/organizations/{organizationId}/workforce/credentials/{credentialId}/documents/{documentId}/accesses",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> accessCredentialDocument(
            @PathVariable UUID organizationId,
            @PathVariable UUID credentialId,
            @PathVariable UUID documentId,
            @RequestHeader("Idempotency-Key")
                    @Pattern(regexp = IDEMPOTENCY_PATTERN)
                    String idempotencyKey,
            @Valid @RequestBody CredentialDocumentAccessRequest body,
            Authentication authentication,
            HttpServletRequest request) {
        var actor = actor(authentication);
        var session = request.getSession(false);
        var outcome = credentialDocumentAccess.access(
                new WorkforceCredentialDocumentAccessService.Command(
                        organizationId,
                        actor.id(),
                        CorrelationIdFilter.from(request),
                        credentialId,
                        documentId,
                        body.purposeCode(),
                        body.reason(),
                        idempotencyKey,
                        assurance(session, AuthenticationSessionState.RECENT_AUTHENTICATION_AT),
                        assurance(session, AuthenticationSessionState.MFA_AUTHENTICATED_AT)));
        return response(outcome);
    }

    @GetMapping(
            path = "/api/v1/organizations/{organizationId}/workforce/credentials/{credentialId}/documents/{documentId}/accesses/{accessIntentId}")
    ResponseEntity<Void> openCredentialDocument(
            @PathVariable UUID organizationId,
            @PathVariable UUID credentialId,
            @PathVariable UUID documentId,
            @PathVariable UUID accessIntentId,
            @RequestParam
                    @Pattern(regexp = "credentialing_review|regulatory_evidence|security_investigation|employment_record_request|data_correction")
                    String purposeCode,
            Authentication authentication,
            HttpServletRequest request) {
        var actor = actor(authentication);
        var session = request.getSession(false);
        var redirect = credentialDocumentAccess.open(
                new WorkforceCredentialDocumentAccessService.OpenCommand(
                        organizationId,
                        actor.id(),
                        CorrelationIdFilter.from(request),
                        credentialId,
                        documentId,
                        accessIntentId,
                        purposeCode,
                        assurance(session, AuthenticationSessionState.RECENT_AUTHENTICATION_AT),
                        assurance(session, AuthenticationSessionState.MFA_AUTHENTICATED_AT)));
        return ResponseEntity.status(HttpStatus.TEMPORARY_REDIRECT)
                .location(URI.create(redirect.readUrl()))
                .cacheControl(CacheControl.noStore())
                .header("Referrer-Policy", "no-referrer")
                .build();
    }

    private static ResponseEntity<String> response(IdempotencyOutcome outcome) {
        return ResponseEntity.status(outcome.response().statusCode())
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.parseMediaType(outcome.response().mediaType()))
                .body(outcome.response().bodyJson());
    }

    private static AuthenticatedActor actor(Authentication authentication) {
        if (authentication == null
                || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof AuthenticatedActor actor)) {
            throw new ApiProblemException(
                    HttpStatus.UNAUTHORIZED,
                    "authentication-required",
                    "Authentication required",
                    "A valid authenticated session is required.");
        }
        return actor;
    }

    private static Instant assurance(HttpSession session, String key) {
        var value = session == null ? null : session.getAttribute(key);
        return value instanceof Long millis ? Instant.ofEpochMilli(millis) : null;
    }

    public record WorkforceActionRequest(
            UUID targetId,
            UUID memberId,
            @Size(max = 80) String decision,
            @Size(max = 500) String reason,
            @NotNull @Size(max = 64) Map<@Size(max = 80) String, @Size(max = 2000) String> fields,
            @Size(max = 32) List<UUID> evidenceIds,
            @Size(max = 4096) String impactToken) {
        public WorkforceActionRequest {
            if (fields == null) {
                fields = Map.of();
            } else if (fields.entrySet().stream()
                    .anyMatch(entry -> entry.getKey() == null || entry.getValue() == null)) {
                throw new IllegalArgumentException("Workforce action fields must not contain null keys or values.");
            } else {
                fields = Map.copyOf(fields);
            }
            evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
        }
    }

    public record CredentialDocumentMetadata(
            UUID memberId,
            @NotBlank @Pattern(regexp = "[0-9a-f]{64}") String sha256,
            @NotBlank @Size(max = 80) String retentionClass,
            @NotBlank @Size(min = 10, max = 500) String reason) {}

    public record WorkforceEvidenceAccessRequest(
            UUID memberId,
            @NotBlank
                    @Pattern(regexp = "workforce-audit-detail-v1|member-evidence-detail-v1")
                    String projection,
            @NotBlank @Pattern(regexp = "[a-z][a-z0-9_]{1,79}") String purposeCode,
            @NotBlank @Size(min = 10, max = 500) String reason) {}

    public record CredentialDocumentAccessRequest(
            @NotBlank
                    @Pattern(regexp = "credentialing_review|regulatory_evidence|security_investigation|employment_record_request|data_correction")
                    String purposeCode,
            @NotBlank @Size(min = 10, max = 500) String reason) {}
}
