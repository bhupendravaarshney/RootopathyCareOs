package com.rootopathy.careos.identity.api;

import com.rootopathy.careos.governance.domain.IdempotentResponse;
import com.rootopathy.careos.identity.application.MembershipAdministrationService;
import com.rootopathy.careos.identity.application.MembershipAdministrationService.ApproveCommand;
import com.rootopathy.careos.identity.application.MembershipAdministrationService.ExecuteCommand;
import com.rootopathy.careos.identity.application.MembershipAdministrationService.OwnerTransferRequestCommand;
import com.rootopathy.careos.identity.application.MembershipAdministrationService.RequestCommand;
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
import org.springframework.http.CacheControl;
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
public class MembershipAdministrationController {
    private final MembershipAdministrationService administration;

    public MembershipAdministrationController(MembershipAdministrationService administration) {
        this.administration = administration;
    }

    @PostMapping(
            "/api/v1/organizations/{organizationId}/memberships/{membershipId}/change-requests")
    ResponseEntity<String> requestChange(
            @PathVariable UUID organizationId,
            @PathVariable UUID membershipId,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestHeader("Idempotency-Key")
                    @Pattern(regexp = "[A-Za-z0-9._:-]{16,128}")
                    String idempotencyKey,
            @Valid @RequestBody MembershipChangeRequest body,
            Authentication authentication,
            HttpServletRequest request) {
        var principal = requirePrincipal(authentication);
        var session = request.getSession(false);
        var outcome = administration.requestChange(new RequestCommand(
                organizationId,
                principal.id(),
                membershipId,
                body.changeType(),
                body.toRoleKey(),
                body.reason(),
                ifMatch,
                recentAuthenticationAt(session),
                mfaAuthenticatedAt(session),
                CorrelationIdFilter.from(request),
                idempotencyKey));
        return response(outcome.response());
    }

    @PostMapping(
            "/api/v1/organizations/{organizationId}/memberships/{membershipId}/change-requests/{approvalId}/approvals")
    ResponseEntity<String> approveChange(
            @PathVariable UUID organizationId,
            @PathVariable UUID membershipId,
            @PathVariable UUID approvalId,
            @RequestHeader("Idempotency-Key")
                    @Pattern(regexp = "[A-Za-z0-9._:-]{16,128}")
                    String idempotencyKey,
            @Valid @RequestBody ReasonRequest body,
            Authentication authentication,
            HttpServletRequest request) {
        var principal = requirePrincipal(authentication);
        var session = request.getSession(false);
        var outcome = administration.approveChange(new ApproveCommand(
                organizationId,
                principal.id(),
                membershipId,
                approvalId,
                body.reason(),
                recentAuthenticationAt(session),
                mfaAuthenticatedAt(session),
                CorrelationIdFilter.from(request),
                idempotencyKey));
        return response(outcome.response());
    }

    @PostMapping(
            "/api/v1/organizations/{organizationId}/memberships/{membershipId}/change-requests/{approvalId}/executions")
    ResponseEntity<String> executeChange(
            @PathVariable UUID organizationId,
            @PathVariable UUID membershipId,
            @PathVariable UUID approvalId,
            @RequestHeader("Idempotency-Key")
                    @Pattern(regexp = "[A-Za-z0-9._:-]{16,128}")
                    String idempotencyKey,
            @Valid @RequestBody ReasonRequest body,
            Authentication authentication,
            HttpServletRequest request) {
        var principal = requirePrincipal(authentication);
        var session = request.getSession(false);
        var outcome = administration.executeChange(new ExecuteCommand(
                organizationId,
                principal.id(),
                membershipId,
                approvalId,
                body.reason(),
                recentAuthenticationAt(session),
                mfaAuthenticatedAt(session),
                CorrelationIdFilter.from(request),
                idempotencyKey));
        return response(outcome.response());
    }

    @PostMapping(
            "/api/v1/organizations/{organizationId}/memberships/{membershipId}/owner-transfer-requests")
    ResponseEntity<String> requestOwnerTransfer(
            @PathVariable UUID organizationId,
            @PathVariable UUID membershipId,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestHeader("Idempotency-Key")
                    @Pattern(regexp = "[A-Za-z0-9._:-]{16,128}")
                    String idempotencyKey,
            @Valid @RequestBody OwnerTransferRequest body,
            Authentication authentication,
            HttpServletRequest request) {
        var principal = requirePrincipal(authentication);
        var session = request.getSession(false);
        var outcome = administration.requestOwnerTransfer(new OwnerTransferRequestCommand(
                organizationId,
                principal.id(),
                membershipId,
                body.toRoleKey(),
                body.reason(),
                ifMatch,
                recentAuthenticationAt(session),
                mfaAuthenticatedAt(session),
                CorrelationIdFilter.from(request),
                idempotencyKey));
        return response(outcome.response());
    }

    @PostMapping(
            "/api/v1/organizations/{organizationId}/memberships/{membershipId}/owner-transfer-requests/{approvalId}/approvals")
    ResponseEntity<String> approveOwnerTransfer(
            @PathVariable UUID organizationId,
            @PathVariable UUID membershipId,
            @PathVariable UUID approvalId,
            @RequestHeader("Idempotency-Key")
                    @Pattern(regexp = "[A-Za-z0-9._:-]{16,128}")
                    String idempotencyKey,
            @Valid @RequestBody ReasonRequest body,
            Authentication authentication,
            HttpServletRequest request) {
        var principal = requirePrincipal(authentication);
        var session = request.getSession(false);
        var outcome = administration.approveOwnerTransfer(new ApproveCommand(
                organizationId,
                principal.id(),
                membershipId,
                approvalId,
                body.reason(),
                recentAuthenticationAt(session),
                mfaAuthenticatedAt(session),
                CorrelationIdFilter.from(request),
                idempotencyKey));
        return response(outcome.response());
    }

    @PostMapping(
            "/api/v1/organizations/{organizationId}/memberships/{membershipId}/owner-transfer-requests/{approvalId}/executions")
    ResponseEntity<String> executeOwnerTransfer(
            @PathVariable UUID organizationId,
            @PathVariable UUID membershipId,
            @PathVariable UUID approvalId,
            @RequestHeader("Idempotency-Key")
                    @Pattern(regexp = "[A-Za-z0-9._:-]{16,128}")
                    String idempotencyKey,
            @Valid @RequestBody ReasonRequest body,
            Authentication authentication,
            HttpServletRequest request) {
        var principal = requirePrincipal(authentication);
        var session = request.getSession(false);
        var outcome = administration.executeOwnerTransfer(new ExecuteCommand(
                organizationId,
                principal.id(),
                membershipId,
                approvalId,
                body.reason(),
                recentAuthenticationAt(session),
                mfaAuthenticatedAt(session),
                CorrelationIdFilter.from(request),
                idempotencyKey));
        return response(outcome.response());
    }

    private static ResponseEntity<String> response(IdempotentResponse response) {
        return ResponseEntity.status(response.statusCode())
                .cacheControl(CacheControl.noStore())
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

    public record MembershipChangeRequest(
            @NotBlank @Pattern(regexp = "role_change|revoke") String changeType,
            @Size(max = 100) String toRoleKey,
            @NotBlank @Size(min = 10, max = 500) String reason) {}

    public record OwnerTransferRequest(
            @NotBlank @Size(max = 100) String toRoleKey,
            @NotBlank @Size(min = 10, max = 500) String reason) {}

    public record ReasonRequest(@NotBlank @Size(min = 10, max = 500) String reason) {}
}
