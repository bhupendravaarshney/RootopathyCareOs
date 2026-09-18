package com.rootopathy.careos.administration.api;

import com.rootopathy.careos.administration.application.OrganizationAdministrationException;
import com.rootopathy.careos.administration.application.OrganizationAdministrationService;
import com.rootopathy.careos.administration.application.OrganizationAdministrationService.MembershipListCommand;
import com.rootopathy.careos.administration.application.OrganizationAdministrationService.ReadCommand;
import com.rootopathy.careos.administration.application.OrganizationAdministrationService.UpdateCommand;
import com.rootopathy.careos.administration.domain.AdministrationReadiness;
import com.rootopathy.careos.administration.domain.OrganizationMembershipPage;
import com.rootopathy.careos.administration.domain.OrganizationProfile;
import com.rootopathy.careos.shared.api.ApiProblemException;
import com.rootopathy.careos.shared.api.CorrelationIdFilter;
import com.rootopathy.careos.shared.domain.AuthenticatedActor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.Set;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
public class OrganizationAdministrationController {
    private static final Set<String> MEMBERSHIP_QUERY_PARAMETERS =
            Set.of("search", "state", "roleKey", "cursor", "limit");

    private final OrganizationAdministrationService administration;

    public OrganizationAdministrationController(OrganizationAdministrationService administration) {
        this.administration = administration;
    }

    @GetMapping("/api/v1/organizations/{organizationId}/memberships")
    ResponseEntity<OrganizationMembershipPage> memberships(
            @PathVariable UUID organizationId,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String roleKey,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "25") @Min(1) @Max(100) int limit,
            Authentication authentication,
            HttpServletRequest request) {
        requireMembershipQueryParameters(request);
        var actor = requireActor(authentication);
        var memberships = administration.memberships(new MembershipListCommand(
                organizationId,
                actor.id(),
                CorrelationIdFilter.from(request),
                search,
                state,
                roleKey,
                cursor,
                limit));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(memberships);
    }

    @GetMapping("/api/v1/organizations/{organizationId}/setup-readiness")
    ResponseEntity<AdministrationReadiness> readiness(
            @PathVariable UUID organizationId,
            Authentication authentication,
            HttpServletRequest request) {
        var actor = requireActor(authentication);
        var readiness = administration.readiness(new ReadCommand(
                organizationId, actor.id(), CorrelationIdFilter.from(request)));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(readiness);
    }

    @GetMapping("/api/v1/organizations/{organizationId}/profile")
    ResponseEntity<OrganizationProfile> profile(
            @PathVariable UUID organizationId,
            Authentication authentication,
            HttpServletRequest request) {
        var actor = requireActor(authentication);
        var profile = administration.profile(new ReadCommand(
                organizationId, actor.id(), CorrelationIdFilter.from(request)));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .eTag(OrganizationAdministrationService.entityTag(profile))
                .body(profile);
    }

    @PutMapping("/api/v1/organizations/{organizationId}/profile")
    ResponseEntity<String> updateProfile(
            @PathVariable UUID organizationId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody OrganizationProfileUpdateRequest body,
            Authentication authentication,
            HttpServletRequest request) {
        var actor = requireActor(authentication);
        var result = administration.updateProfile(new UpdateCommand(
                organizationId,
                actor.id(),
                CorrelationIdFilter.from(request),
                ifMatch,
                idempotencyKey,
                body.legalName(),
                body.displayName(),
                body.tradingName(),
                body.organizationType(),
                body.countryCode(),
                body.timezone(),
                body.locale(),
                body.reason()));
        return ResponseEntity.status(result.outcome().response().statusCode())
                .cacheControl(CacheControl.noStore())
                .eTag(result.entityTag())
                .contentType(MediaType.parseMediaType(result.outcome().response().mediaType()))
                .body(result.outcome().response().bodyJson());
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

    private static void requireMembershipQueryParameters(HttpServletRequest request) {
        for (var parameter : request.getParameterMap().entrySet()) {
            if (!MEMBERSHIP_QUERY_PARAMETERS.contains(parameter.getKey())
                    || parameter.getValue().length != 1) {
                throw new OrganizationAdministrationException(
                        OrganizationAdministrationException.Reason.MEMBERSHIP_LIST_INVALID,
                        "Only one value for each documented membership filter is allowed.");
            }
        }
    }

    public record OrganizationProfileUpdateRequest(
            String legalName,
            String displayName,
            String tradingName,
            String organizationType,
            String countryCode,
            String timezone,
            String locale,
            String reason) {}
}
