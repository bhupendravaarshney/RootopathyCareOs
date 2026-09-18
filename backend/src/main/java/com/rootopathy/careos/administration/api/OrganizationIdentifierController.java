package com.rootopathy.careos.administration.api;

import com.rootopathy.careos.administration.application.OrganizationIdentifierService;
import com.rootopathy.careos.administration.application.OrganizationIdentifierService.CreateCommand;
import com.rootopathy.careos.administration.application.OrganizationIdentifierService.DraftCommand;
import com.rootopathy.careos.administration.application.OrganizationIdentifierService.ReadCommand;
import com.rootopathy.careos.administration.application.OrganizationIdentifierService.RevokeCommand;
import com.rootopathy.careos.administration.application.OrganizationIdentifierService.SupersedeCommand;
import com.rootopathy.careos.administration.application.OrganizationIdentifierService.UpdateCommand;
import com.rootopathy.careos.administration.application.OrganizationIdentifierService.VerifyCommand;
import com.rootopathy.careos.administration.domain.OrganizationIdentifierCollection;
import com.rootopathy.careos.identity.api.AuthenticationSessionState;
import com.rootopathy.careos.shared.api.ApiProblemException;
import com.rootopathy.careos.shared.api.CorrelationIdFilter;
import com.rootopathy.careos.shared.domain.AuthenticatedActor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class OrganizationIdentifierController {
    private final OrganizationIdentifierService identifiers;

    public OrganizationIdentifierController(OrganizationIdentifierService identifiers) {
        this.identifiers = identifiers;
    }

    @GetMapping("/api/v1/organizations/{organizationId}/identifiers")
    ResponseEntity<OrganizationIdentifierCollection> identifiers(
            @PathVariable UUID organizationId,
            Authentication authentication,
            HttpServletRequest request) {
        var actor = requireActor(authentication);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(identifiers.identifiers(new ReadCommand(
                        organizationId, actor.id(), CorrelationIdFilter.from(request))));
    }

    @PostMapping("/api/v1/organizations/{organizationId}/identifiers")
    ResponseEntity<String> create(
            @PathVariable UUID organizationId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody IdentifierWriteRequest body,
            Authentication authentication,
            HttpServletRequest request) {
        var actor = requireActor(authentication);
        var result = identifiers.create(new CreateCommand(
                organizationId,
                actor.id(),
                CorrelationIdFilter.from(request),
                idempotencyKey,
                body.draft(),
                body.reason()));
        return mutationResponse(result)
                .location(URI.create("/api/v1/organizations/" + organizationId
                        + "/identifiers/" + result.identifierId()))
                .body(result.outcome().response().bodyJson());
    }

    @PutMapping("/api/v1/organizations/{organizationId}/identifiers/{identifierId}")
    ResponseEntity<String> update(
            @PathVariable UUID organizationId,
            @PathVariable UUID identifierId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody IdentifierWriteRequest body,
            Authentication authentication,
            HttpServletRequest request) {
        var actor = requireActor(authentication);
        var result = identifiers.update(new UpdateCommand(
                organizationId,
                actor.id(),
                CorrelationIdFilter.from(request),
                identifierId,
                ifMatch,
                idempotencyKey,
                body.draft(),
                body.reason()));
        return mutationResponse(result).body(result.outcome().response().bodyJson());
    }

    @PostMapping(
            "/api/v1/organizations/{organizationId}/identifiers/{identifierId}/verifications")
    ResponseEntity<String> verify(
            @PathVariable UUID organizationId,
            @PathVariable UUID identifierId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody IdentifierVerificationRequest body,
            Authentication authentication,
            HttpSession session,
            HttpServletRequest request) {
        var actor = requireActor(authentication);
        var result = identifiers.verify(new VerifyCommand(
                organizationId,
                actor.id(),
                CorrelationIdFilter.from(request),
                identifierId,
                ifMatch,
                idempotencyKey,
                body.evidenceReference(),
                body.reason(),
                recentAuthenticationAt(session),
                mfaAuthenticatedAt(session)));
        return mutationResponse(result).body(result.outcome().response().bodyJson());
    }

    @PostMapping(
            "/api/v1/organizations/{organizationId}/identifiers/{identifierId}/revocations")
    ResponseEntity<String> revoke(
            @PathVariable UUID organizationId,
            @PathVariable UUID identifierId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody IdentifierRevocationRequest body,
            Authentication authentication,
            HttpServletRequest request) {
        var actor = requireActor(authentication);
        var result = identifiers.revoke(new RevokeCommand(
                organizationId,
                actor.id(),
                CorrelationIdFilter.from(request),
                identifierId,
                ifMatch,
                idempotencyKey,
                body.reason()));
        return mutationResponse(result).body(result.outcome().response().bodyJson());
    }

    @PostMapping(
            "/api/v1/organizations/{organizationId}/identifiers/{identifierId}/supersessions")
    ResponseEntity<String> supersede(
            @PathVariable UUID organizationId,
            @PathVariable UUID identifierId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody IdentifierSupersessionRequest body,
            Authentication authentication,
            HttpServletRequest request) {
        var actor = requireActor(authentication);
        var result = identifiers.supersede(new SupersedeCommand(
                organizationId,
                actor.id(),
                CorrelationIdFilter.from(request),
                identifierId,
                ifMatch,
                idempotencyKey,
                body.replacementId(),
                body.replacementEtag(),
                body.reason()));
        return mutationResponse(result).body(result.outcome().response().bodyJson());
    }

    private static ResponseEntity.BodyBuilder mutationResponse(
            OrganizationIdentifierService.MutationOutcome result) {
        return ResponseEntity.status(result.outcome().response().statusCode())
                .cacheControl(CacheControl.noStore())
                .eTag(result.entityTag())
                .contentType(MediaType.parseMediaType(result.outcome().response().mediaType()));
    }

    private static AuthenticatedActor requireActor(Authentication authentication) {
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

    private static Instant recentAuthenticationAt(HttpSession session) {
        var value = session.getAttribute(AuthenticationSessionState.RECENT_AUTHENTICATION_AT);
        return value instanceof Long epochMillis ? Instant.ofEpochMilli(epochMillis) : null;
    }

    private static Instant mfaAuthenticatedAt(HttpSession session) {
        var value = session.getAttribute(AuthenticationSessionState.MFA_AUTHENTICATED_AT);
        return value instanceof Long epochMillis ? Instant.ofEpochMilli(epochMillis) : null;
    }

    public record IdentifierWriteRequest(
            String identifierType,
            String assigningAuthority,
            String value,
            String jurisdictionCountryCode,
            Boolean isPrimary,
            String issueDate,
            String expiryDate,
            String effectiveFrom,
            String effectiveTo,
            String reason) {
        DraftCommand draft() {
            return new DraftCommand(
                    identifierType,
                    assigningAuthority,
                    value,
                    jurisdictionCountryCode,
                    isPrimary,
                    issueDate,
                    expiryDate,
                    effectiveFrom,
                    effectiveTo);
        }
    }

    public record IdentifierVerificationRequest(String evidenceReference, String reason) {}

    public record IdentifierRevocationRequest(String reason) {}

    public record IdentifierSupersessionRequest(
            UUID replacementId, String replacementEtag, String reason) {}
}
