package com.rootopathy.careos.document.api;

import com.rootopathy.careos.document.application.DocumentException;
import com.rootopathy.careos.document.application.DocumentService;
import com.rootopathy.careos.document.domain.DocumentScreen;
import com.rootopathy.careos.governance.domain.IdempotencyOutcome;
import com.rootopathy.careos.identity.api.AuthenticationSessionState;
import com.rootopathy.careos.shared.api.ApiProblemException;
import com.rootopathy.careos.shared.api.CorrelationIdFilter;
import com.rootopathy.careos.shared.domain.AuthenticatedActor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.net.URI;
import java.time.Instant;
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
public class DocumentController {
    private static final String SCREEN_PATTERN = "P7-(0[1-9]|1[01])";
    private static final String ACTION_PATTERN = "[a-z][a-z0-9]*(?:-[a-z0-9]+)*";
    private static final String IDEMPOTENCY_PATTERN = "[A-Za-z0-9._:-]{16,128}";

    private final DocumentService documents;

    public DocumentController(DocumentService documents) {
        this.documents = documents;
    }

    @GetMapping("/api/v1/organizations/{organizationId}/documents/screens/{screenId}")
    ResponseEntity<DocumentScreen> screen(
            @PathVariable UUID organizationId,
            @PathVariable @Pattern(regexp = SCREEN_PATTERN) String screenId,
            @RequestParam(required = false) UUID patientId,
            @RequestParam(required = false) UUID documentId,
            @RequestParam(required = false) UUID diagnosticReportId,
            @RequestParam(required = false) @Size(max = 100) String q,
            @RequestParam(required = false) @Size(max = 120) String status,
            @RequestParam(required = false) @Min(1) @Max(100) Integer limit,
            @RequestParam(required = false) @Size(max = 2048) String cursor,
            Authentication authentication,
            HttpServletRequest request) {
        var actor = actor(authentication);
        var session = request.getSession(false);
        var response = documents.screen(new DocumentService.ReadCommand(
                organizationId,
                actor.id(),
                CorrelationIdFilter.from(request),
                screenId,
                patientId,
                documentId,
                diagnosticReportId,
                q,
                status,
                limit,
                cursor,
                assurance(session, AuthenticationSessionState.RECENT_AUTHENTICATION_AT),
                assurance(session, AuthenticationSessionState.MFA_AUTHENTICATED_AT)));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(response);
    }

    @PostMapping(
            path = "/api/v1/organizations/{organizationId}/documents/screens/{screenId}/actions/{actionKey}",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> action(
            @PathVariable UUID organizationId,
            @PathVariable @Pattern(regexp = SCREEN_PATTERN) String screenId,
            @PathVariable @Pattern(regexp = ACTION_PATTERN) String actionKey,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestHeader("Idempotency-Key") @Pattern(regexp = IDEMPOTENCY_PATTERN) String idempotencyKey,
            @Valid @RequestBody DocumentActionRequest body,
            Authentication authentication,
            HttpServletRequest request) {
        var actor = actor(authentication);
        var session = request.getSession(false);
        var outcome = documents.act(new DocumentService.ActionCommand(
                organizationId,
                actor.id(),
                CorrelationIdFilter.from(request),
                screenId,
                actionKey,
                body.targetId(),
                body.patientId(),
                body.documentId(),
                body.documentVersionId(),
                body.diagnosticReportId(),
                body.reason(),
                body.fields(),
                ifMatch,
                idempotencyKey,
                assurance(session, AuthenticationSessionState.RECENT_AUTHENTICATION_AT),
                assurance(session, AuthenticationSessionState.MFA_AUTHENTICATED_AT)));
        return response(outcome);
    }

    @PostMapping(
            path = "/api/v1/organizations/{organizationId}/documents",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<String> upload(
            @PathVariable UUID organizationId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestHeader("Idempotency-Key") @Pattern(regexp = IDEMPOTENCY_PATTERN) String idempotencyKey,
            @Valid @RequestPart("metadata") DocumentUploadMetadata metadata,
            @RequestPart("file") MultipartFile file,
            Authentication authentication,
            HttpServletRequest request) {
        if (file.isEmpty()) {
            throw new DocumentException(
                    DocumentException.Reason.INVALID, "The document file must not be empty.");
        }
        var actor = actor(authentication);
        var session = request.getSession(false);
        try {
            var outcome = documents.upload(new DocumentService.UploadRequest(
                    organizationId,
                    actor.id(),
                    CorrelationIdFilter.from(request),
                    metadata.patientId(),
                    metadata.encounterId(),
                    metadata.assessmentSessionId(),
                    metadata.replacementDocumentId(),
                    metadata.title(),
                    metadata.documentTypeKey(),
                    metadata.sourceKey(),
                    metadata.reason(),
                    file.getOriginalFilename(),
                    file.getContentType(),
                    metadata.sha256(),
                    file.getBytes(),
                    ifMatch,
                    idempotencyKey,
                    assurance(session, AuthenticationSessionState.RECENT_AUTHENTICATION_AT),
                    assurance(session, AuthenticationSessionState.MFA_AUTHENTICATED_AT)));
            return response(outcome);
        } catch (IOException exception) {
            throw new DocumentException(
                    DocumentException.Reason.INVALID, "The document file could not be read.");
        }
    }

    @PostMapping(
            path = "/api/v1/organizations/{organizationId}/documents/{documentId}/accesses",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> createAccess(
            @PathVariable UUID organizationId,
            @PathVariable UUID documentId,
            @RequestHeader("Idempotency-Key") @Pattern(regexp = IDEMPOTENCY_PATTERN) String idempotencyKey,
            @Valid @RequestBody DocumentAccessRequest body,
            Authentication authentication,
            HttpServletRequest request) {
        var actor = actor(authentication);
        var session = request.getSession(false);
        var outcome = documents.createAccess(new DocumentService.AccessCommand(
                organizationId,
                actor.id(),
                CorrelationIdFilter.from(request),
                documentId,
                body.documentVersionId(),
                body.purposeKey(),
                body.reason(),
                idempotencyKey,
                assurance(session, AuthenticationSessionState.RECENT_AUTHENTICATION_AT),
                assurance(session, AuthenticationSessionState.MFA_AUTHENTICATED_AT)));
        return response(outcome);
    }

    @GetMapping(
            "/api/v1/organizations/{organizationId}/documents/{documentId}/versions/{documentVersionId}/accesses/{accessIntentId}")
    ResponseEntity<Void> openAccess(
            @PathVariable UUID organizationId,
            @PathVariable UUID documentId,
            @PathVariable UUID documentVersionId,
            @PathVariable UUID accessIntentId,
            @RequestParam @Pattern(regexp = "[a-z][a-z0-9_]{1,79}") String purposeKey,
            Authentication authentication,
            HttpServletRequest request) {
        var actor = actor(authentication);
        var session = request.getSession(false);
        var redirect = documents.openAccess(new DocumentService.OpenAccessCommand(
                organizationId,
                actor.id(),
                CorrelationIdFilter.from(request),
                documentId,
                documentVersionId,
                accessIntentId,
                purposeKey,
                assurance(session, AuthenticationSessionState.RECENT_AUTHENTICATION_AT),
                assurance(session, AuthenticationSessionState.MFA_AUTHENTICATED_AT)));
        return ResponseEntity.status(HttpStatus.SEE_OTHER)
                .location(URI.create(redirect.location()))
                .cacheControl(CacheControl.noStore())
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

    public record DocumentActionRequest(
            UUID targetId,
            UUID patientId,
            UUID documentId,
            UUID documentVersionId,
            UUID diagnosticReportId,
            @Size(max = 500) String reason,
            @NotNull @Size(max = 64)
                    Map<@Size(max = 80) String, @Size(max = 20000) String> fields) {
        public DocumentActionRequest {
            if (fields == null) {
                fields = Map.of();
            } else if (fields.entrySet().stream()
                    .anyMatch(entry -> entry.getKey() == null || entry.getValue() == null)) {
                throw new IllegalArgumentException(
                        "Document action fields must not contain null keys or values.");
            } else {
                fields = Map.copyOf(fields);
            }
        }
    }

    public record DocumentUploadMetadata(
            @NotNull UUID patientId,
            UUID encounterId,
            UUID assessmentSessionId,
            UUID replacementDocumentId,
            @NotNull @Size(min = 2, max = 240) String title,
            @NotNull @Pattern(regexp = "[a-z][a-z0-9_]{1,79}") String documentTypeKey,
            @NotNull @Pattern(regexp = "[a-z][a-z0-9_.:-]{1,119}") String sourceKey,
            @NotNull @Pattern(regexp = "[0-9a-f]{64}") String sha256,
            @NotNull @Size(min = 10, max = 500) String reason) {}

    public record DocumentAccessRequest(
            UUID documentVersionId,
            @NotNull @Pattern(regexp = "[a-z][a-z0-9_]{1,79}") String purposeKey,
            @NotNull @Size(min = 10, max = 500) String reason) {}
}
