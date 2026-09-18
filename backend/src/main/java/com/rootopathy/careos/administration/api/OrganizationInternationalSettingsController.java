package com.rootopathy.careos.administration.api;

import com.rootopathy.careos.administration.application.OrganizationInternationalSettingsService;
import com.rootopathy.careos.administration.application.OrganizationInternationalSettingsService.ReadCommand;
import com.rootopathy.careos.administration.application.OrganizationInternationalSettingsService.ScheduleCommand;
import com.rootopathy.careos.administration.application.OrganizationInternationalSettingsService.SettingsDraftCommand;
import com.rootopathy.careos.administration.domain.OrganizationInternationalSettings;
import com.rootopathy.careos.shared.api.ApiProblemException;
import com.rootopathy.careos.shared.api.CorrelationIdFilter;
import com.rootopathy.careos.shared.domain.AuthenticatedActor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
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
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
public class OrganizationInternationalSettingsController {
    private final OrganizationInternationalSettingsService settings;

    public OrganizationInternationalSettingsController(
            OrganizationInternationalSettingsService settings) {
        this.settings = settings;
    }

    @GetMapping("/api/v1/organizations/{organizationId}/international-settings")
    ResponseEntity<OrganizationInternationalSettings> settings(
            @PathVariable UUID organizationId,
            Authentication authentication,
            HttpServletRequest request) {
        var actor = requireActor(authentication);
        var result = settings.settings(new ReadCommand(
                organizationId, actor.id(), CorrelationIdFilter.from(request)));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .eTag(OrganizationInternationalSettingsService.entityTag(result))
                .body(result);
    }

    @PutMapping("/api/v1/organizations/{organizationId}/international-settings")
    ResponseEntity<String> schedule(
            @PathVariable UUID organizationId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody InternationalSettingsScheduleRequest body,
            Authentication authentication,
            HttpServletRequest request) {
        var actor = requireActor(authentication);
        var result = settings.schedule(new ScheduleCommand(
                organizationId,
                actor.id(),
                CorrelationIdFilter.from(request),
                ifMatch,
                idempotencyKey,
                new SettingsDraftCommand(
                        body.countryCode(),
                        body.timezone(),
                        body.locale(),
                        body.language(),
                        body.currencyCode(),
                        body.weekStart(),
                        body.effectiveFrom()),
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

    public record InternationalSettingsScheduleRequest(
            String countryCode,
            String timezone,
            String locale,
            String language,
            String currencyCode,
            String weekStart,
            String effectiveFrom,
            String reason) {}
}
