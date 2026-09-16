package com.rootopathy.careos.identity.api;

import com.rootopathy.careos.identity.application.MfaAdministrationService;
import com.rootopathy.careos.identity.application.MfaAdministrationService.ApproveCommand;
import com.rootopathy.careos.identity.application.MfaAdministrationService.ExecuteCommand;
import com.rootopathy.careos.identity.application.MfaAdministrationService.RequestCommand;
import com.rootopathy.careos.identity.infrastructure.security.CareOsPrincipal;
import com.rootopathy.careos.shared.api.ApiProblemException;
import com.rootopathy.careos.shared.api.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
public class MfaAdministrationController {
    private final MfaAdministrationService administration;

    public MfaAdministrationController(MfaAdministrationService administration) {
        this.administration = administration;
    }

    @PostMapping("/api/v1/organizations/{organizationId}/users/{targetUserId}/mfa-reset-requests")
    ResponseEntity<String> requestReset(
            @PathVariable UUID organizationId,
            @PathVariable UUID targetUserId,
            @RequestHeader("Idempotency-Key")
                    @Pattern(regexp = "[A-Za-z0-9._:-]{16,128}")
                    String idempotencyKey,
            @Valid @RequestBody ReasonRequest body,
            Authentication authentication,
            HttpServletRequest request) {
        var principal = requirePrincipal(authentication);
        var outcome = administration.requestReset(new RequestCommand(
                organizationId,
                principal.id(),
                targetUserId,
                body.reason(),
                recentAuthenticationAt(request.getSession(false)),
                mfaAuthenticatedAt(request.getSession(false)),
                CorrelationIdFilter.from(request),
                idempotencyKey));
        return response(outcome.response());
    }

    @PostMapping(
            "/api/v1/organizations/{organizationId}/users/{targetUserId}/mfa-reset-requests/{approvalId}/approvals")
    ResponseEntity<String> approveReset(
            @PathVariable UUID organizationId,
            @PathVariable UUID targetUserId,
            @PathVariable UUID approvalId,
            @RequestHeader("Idempotency-Key")
                    @Pattern(regexp = "[A-Za-z0-9._:-]{16,128}")
                    String idempotencyKey,
            @Valid @RequestBody ReasonRequest body,
            Authentication authentication,
            HttpServletRequest request) {
        var principal = requirePrincipal(authentication);
        var outcome = administration.approveReset(new ApproveCommand(
                organizationId,
                principal.id(),
                targetUserId,
                approvalId,
                body.reason(),
                recentAuthenticationAt(request.getSession(false)),
                mfaAuthenticatedAt(request.getSession(false)),
                CorrelationIdFilter.from(request),
                idempotencyKey));
        return response(outcome.response());
    }

    @PostMapping(
            "/api/v1/organizations/{organizationId}/users/{targetUserId}/mfa-reset-requests/{approvalId}/executions")
    ResponseEntity<String> executeReset(
            @PathVariable UUID organizationId,
            @PathVariable UUID targetUserId,
            @PathVariable UUID approvalId,
            @RequestHeader("Idempotency-Key")
                    @Pattern(regexp = "[A-Za-z0-9._:-]{16,128}")
                    String idempotencyKey,
            @Valid @RequestBody ReasonRequest body,
            Authentication authentication,
            HttpServletRequest request) {
        var principal = requirePrincipal(authentication);
        var outcome = administration.executeReset(new ExecuteCommand(
                organizationId,
                principal.id(),
                targetUserId,
                approvalId,
                body.reason(),
                recentAuthenticationAt(request.getSession(false)),
                mfaAuthenticatedAt(request.getSession(false)),
                CorrelationIdFilter.from(request),
                idempotencyKey,
                request.getRemoteAddr()));
        return response(outcome.response());
    }

    private static ResponseEntity<String> response(
            com.rootopathy.careos.governance.domain.IdempotentResponse response) {
        return ResponseEntity.status(response.statusCode())
                .contentType(MediaType.parseMediaType(response.mediaType()))
                .body(response.bodyJson());
    }

    private static CareOsPrincipal requirePrincipal(Authentication authentication) {
        if (authentication == null
                || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof CareOsPrincipal principal)) {
            throw new ApiProblemException(
                    HttpStatus.UNAUTHORIZED,
                    "authentication-required",
                    "Authentication required",
                    "A valid authenticated session is required.");
        }
        return principal;
    }

    private static Instant recentAuthenticationAt(HttpSession session) {
        if (session == null) {
            return null;
        }
        var value = session.getAttribute(AuthenticationSessionState.RECENT_AUTHENTICATION_AT);
        return value instanceof Long epochMillis ? Instant.ofEpochMilli(epochMillis) : null;
    }

    private static Instant mfaAuthenticatedAt(HttpSession session) {
        if (session == null) {
            return null;
        }
        var value = session.getAttribute(AuthenticationSessionState.MFA_AUTHENTICATED_AT);
        return value instanceof Long epochMillis ? Instant.ofEpochMilli(epochMillis) : null;
    }

    public record ReasonRequest(@NotBlank @Size(max = 2000) String reason) {}
}
