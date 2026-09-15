package com.rootopathy.careos.identity.api;

import com.rootopathy.careos.identity.application.InvitationService;
import com.rootopathy.careos.identity.application.InvitationService.AcceptCommand;
import com.rootopathy.careos.identity.application.InvitationService.IssueCommand;
import com.rootopathy.careos.identity.application.InvitationService.RevokeCommand;
import com.rootopathy.careos.identity.domain.InvitationAcceptance;
import com.rootopathy.careos.identity.infrastructure.security.CareOsPrincipal;
import com.rootopathy.careos.shared.api.ApiProblemException;
import com.rootopathy.careos.shared.api.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
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
public class InvitationController {
    private final InvitationService invitations;

    public InvitationController(InvitationService invitations) {
        this.invitations = invitations;
    }

    @PostMapping("/api/v1/organizations/{organizationId}/invitations")
    ResponseEntity<String> issue(
            @PathVariable UUID organizationId,
            @RequestHeader("Idempotency-Key")
                    @Pattern(regexp = "[A-Za-z0-9._:-]{16,128}")
                    String idempotencyKey,
            @Valid @RequestBody InvitationIssueRequest body,
            Authentication authentication,
            HttpServletRequest request) {
        var principal = requirePrincipal(authentication);
        var outcome = invitations.issue(new IssueCommand(
                organizationId,
                principal.id(),
                body.email(),
                body.displayName(),
                body.roleKey(),
                body.reason(),
                recentAuthenticationAt(request.getSession(false)),
                CorrelationIdFilter.from(request),
                idempotencyKey));
        return ResponseEntity.status(outcome.response().statusCode())
                .contentType(MediaType.parseMediaType(outcome.response().mediaType()))
                .body(outcome.response().bodyJson());
    }

    @PostMapping(
            "/api/v1/organizations/{organizationId}/invitations/{invitationId}/revocations")
    ResponseEntity<String> revoke(
            @PathVariable UUID organizationId,
            @PathVariable UUID invitationId,
            @RequestHeader("Idempotency-Key")
                    @Pattern(regexp = "[A-Za-z0-9._:-]{16,128}")
                    String idempotencyKey,
            @Valid @RequestBody InvitationRevocationRequest body,
            Authentication authentication,
            HttpServletRequest request) {
        var principal = requirePrincipal(authentication);
        var outcome = invitations.revoke(new RevokeCommand(
                organizationId,
                principal.id(),
                invitationId,
                body.reason(),
                recentAuthenticationAt(request.getSession(false)),
                CorrelationIdFilter.from(request),
                idempotencyKey));
        return ResponseEntity.status(outcome.response().statusCode())
                .contentType(MediaType.parseMediaType(outcome.response().mediaType()))
                .body(outcome.response().bodyJson());
    }

    @PostMapping("/api/v1/auth/invitation-acceptances")
    ResponseEntity<InvitationAcceptanceResponse> accept(
            @Valid @RequestBody InvitationAcceptanceRequest body,
            Authentication authentication,
            HttpServletRequest request) {
        var principal = optionalPrincipal(authentication);
        var result = invitations.accept(new AcceptCommand(
                body.token(),
                body.newPassword(),
                principal == null ? null : principal.id(),
                principal == null ? null : principal.email(),
                CorrelationIdFilter.from(request),
                request.getRemoteAddr()));
        var response = new InvitationAcceptanceResponse(
                result.invitationId(),
                result.organizationId(),
                result.userId(),
                result.roleKey(),
                result.accountLink());
        return ResponseEntity.status(result.existingAccount() ? HttpStatus.OK : HttpStatus.CREATED)
                .cacheControl(org.springframework.http.CacheControl.noStore())
                .body(response);
    }

    private static CareOsPrincipal requirePrincipal(Authentication authentication) {
        var principal = optionalPrincipal(authentication);
        if (principal == null) {
            throw new ApiProblemException(
                    HttpStatus.UNAUTHORIZED,
                    "authentication-required",
                    "Authentication required",
                    "A valid authenticated session is required.");
        }
        return principal;
    }

    private static CareOsPrincipal optionalPrincipal(Authentication authentication) {
        if (authentication == null
                || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof CareOsPrincipal principal)) {
            return null;
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

    public record InvitationIssueRequest(
            @NotBlank @Email @Size(max = 320) String email,
            @NotBlank @Size(max = 160) String displayName,
            @NotBlank
                    @Size(max = 100)
                    @Pattern(regexp = "[a-z][a-z0-9]*([._:-][a-z0-9]+)*")
                    String roleKey,
            @NotBlank @Size(max = 2000) String reason) {}

    public record InvitationRevocationRequest(
            @NotBlank @Size(max = 2000) String reason) {}

    public record InvitationAcceptanceRequest(
            @NotBlank @Size(min = 32, max = 512) String token,
            @Size(max = 128) String newPassword) {}

    public record InvitationAcceptanceResponse(
            UUID invitationId,
            UUID organizationId,
            UUID userId,
            String roleKey,
            String accountLink) {
        static InvitationAcceptanceResponse from(InvitationAcceptance acceptance) {
            return new InvitationAcceptanceResponse(
                    acceptance.invitationId(),
                    acceptance.organizationId(),
                    acceptance.userId(),
                    acceptance.roleKey(),
                    acceptance.accountLink());
        }
    }
}
