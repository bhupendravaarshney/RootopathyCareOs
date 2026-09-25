package com.rootopathy.careos.workforce.api;

import com.rootopathy.careos.governance.domain.IdempotencyOutcome;
import com.rootopathy.careos.identity.api.AuthenticationSessionState;
import com.rootopathy.careos.shared.api.ApiProblemException;
import com.rootopathy.careos.shared.api.CorrelationIdFilter;
import com.rootopathy.careos.shared.domain.AuthenticatedActor;
import com.rootopathy.careos.workforce.application.WorkforceExportAccessService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
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
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@Validated
@RestController
public class WorkforceExportController {
    private static final String IDEMPOTENCY_PATTERN="[A-Za-z0-9._:-]{16,128}";
    private final WorkforceExportAccessService service;

    public WorkforceExportController(WorkforceExportAccessService service) {
        this.service=service;
    }

    @PostMapping("/api/v1/organizations/{organizationId}/workforce/exports/{exportId}/accesses")
    ResponseEntity<String> access(
            @PathVariable UUID organizationId,
            @PathVariable UUID exportId,
            @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
            @RequestHeader("Idempotency-Key")
                    @Pattern(regexp=IDEMPOTENCY_PATTERN) String idempotencyKey,
            @Valid @RequestBody AccessRequest body,
            Authentication authentication,
            HttpServletRequest request) {
        var actor=actor(authentication);
        var session=request.getSession(false);
        return response(service.access(new WorkforceExportAccessService.Command(
                organizationId,actor.id(),CorrelationIdFilter.from(request),exportId,
                body.purposeKey(),body.reason(),ifMatch,idempotencyKey,
                assurance(session,AuthenticationSessionState.RECENT_AUTHENTICATION_AT),
                assurance(session,AuthenticationSessionState.MFA_AUTHENTICATED_AT))));
    }

    @GetMapping("/api/v1/organizations/{organizationId}/workforce/exports/{exportId}/download")
    ResponseEntity<StreamingResponseBody> download(
            @PathVariable UUID organizationId,
            @PathVariable UUID exportId,
            Authentication authentication,
            HttpServletRequest request) {
        var actor=actor(authentication);
        var session=request.getSession(false);
        var download=service.download(new WorkforceExportAccessService.DownloadCommand(
                organizationId,actor.id(),CorrelationIdFilter.from(request),exportId,
                assurance(session,AuthenticationSessionState.RECENT_AUTHENTICATION_AT),
                assurance(session,AuthenticationSessionState.MFA_AUTHENTICATED_AT)));
        StreamingResponseBody body=output -> {
            try (var content=download.content()) {
                var transferred=content.stream().transferTo(output);
                if (transferred!=download.byteCount()) {
                    throw new IOException("Workforce export artifact length changed during download.");
                }
            }
        };
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment()
                        .filename(download.filename(),StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options","nosniff")
                .header("Referrer-Policy","no-referrer")
                .header("Cross-Origin-Resource-Policy","same-origin")
                .contentType(MediaType.parseMediaType(download.contentType()))
                .contentLength(download.byteCount())
                .body(body);
    }

    private static ResponseEntity<String> response(IdempotencyOutcome outcome) {
        return ResponseEntity.status(outcome.response().statusCode())
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.APPLICATION_JSON)
                .body(outcome.response().bodyJson());
    }

    private static AuthenticatedActor actor(Authentication authentication) {
        if (authentication==null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof AuthenticatedActor actor)) {
            throw new ApiProblemException(
                    HttpStatus.UNAUTHORIZED,"authentication-required","Authentication required",
                    "A valid authenticated session is required.");
        }
        return actor;
    }

    private static Instant assurance(HttpSession session,String key) {
        var value=session==null?null:session.getAttribute(key);
        return value instanceof Long millis?Instant.ofEpochMilli(millis):null;
    }

    public record AccessRequest(
            @NotBlank @Size(max=80) String purposeKey,
            @NotBlank @Size(min=10,max=500) String reason) {}
}
