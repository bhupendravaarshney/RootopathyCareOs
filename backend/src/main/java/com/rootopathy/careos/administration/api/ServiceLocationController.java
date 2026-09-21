package com.rootopathy.careos.administration.api;

import com.rootopathy.careos.administration.application.ServiceLocationService;
import com.rootopathy.careos.administration.domain.ServiceLocationDirectory;
import com.rootopathy.careos.identity.api.AuthenticationSessionState;
import com.rootopathy.careos.shared.api.ApiProblemException;
import com.rootopathy.careos.shared.api.CorrelationIdFilter;
import com.rootopathy.careos.shared.domain.AuthenticatedActor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
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
public class ServiceLocationController {
    private final ServiceLocationService service;

    public ServiceLocationController(ServiceLocationService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/organizations/{organizationId}/facilities/{facilityId}/locations")
    ResponseEntity<ServiceLocationDirectory> directory(
            @PathVariable UUID organizationId,
            @PathVariable UUID facilityId,
            Authentication authentication,
            HttpServletRequest request) {
        var actor = actor(authentication);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(service.directory(new ServiceLocationService.Read(
                        organizationId,
                        actor.id(),
                        CorrelationIdFilter.from(request),
                        facilityId)));
    }

    @PostMapping("/api/v1/organizations/{organizationId}/facilities/{facilityId}/locations")
    ResponseEntity<String> create(
            @PathVariable UUID organizationId,
            @PathVariable UUID facilityId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody LocationCreate body,
            Authentication authentication,
            HttpServletRequest request) {
        var actor = actor(authentication);
        var result = service.create(new ServiceLocationService.Create(
                organizationId,
                actor.id(),
                CorrelationIdFilter.from(request),
                idempotencyKey,
                facilityId,
                body.unitId(),
                body.parentId(),
                body.addressId(),
                body.locationCode(),
                body.locationType(),
                body.name(),
                body.virtualServiceType(),
                body.capacity(),
                body.accessibilityNotes(),
                body.effectiveFrom(),
                body.effectiveTo(),
                body.reason()));
        return ResponseEntity.status(result.response().statusCode())
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.APPLICATION_JSON)
                .body(result.response().bodyJson());
    }

    @PutMapping("/api/v1/organizations/{organizationId}/facilities/{facilityId}/locations/{locationId}")
    ResponseEntity<String> update(
            @PathVariable UUID organizationId,
            @PathVariable UUID facilityId,
            @PathVariable UUID locationId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody LocationUpdate body,
            Authentication authentication,
            HttpServletRequest request) {
        var actor = actor(authentication);
        var result = service.update(new ServiceLocationService.Update(
                organizationId, actor.id(), CorrelationIdFilter.from(request), idempotencyKey, ifMatch,
                facilityId, locationId, body.unitId(), body.addressId(), body.locationCode(),
                body.locationType(), body.name(), body.virtualServiceType(), body.capacity(),
                body.accessibilityNotes(), body.effectiveFrom(), body.effectiveTo(), body.reason()));
        return response(result);
    }

    @PostMapping("/api/v1/organizations/{organizationId}/facilities/{facilityId}/locations/{locationId}/reparentings")
    ResponseEntity<String> reparent(
            @PathVariable UUID organizationId,
            @PathVariable UUID facilityId,
            @PathVariable UUID locationId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody LocationReparent body,
            Authentication authentication,
            HttpServletRequest request) {
        var actor = actor(authentication);
        var result = service.reparent(new ServiceLocationService.Reparent(
                organizationId, actor.id(), CorrelationIdFilter.from(request), idempotencyKey, ifMatch,
                facilityId, locationId, body.parentId(), body.effectiveFrom(), body.reason()));
        return response(result);
    }

    private static ResponseEntity<String> response(com.rootopathy.careos.governance.domain.IdempotencyOutcome result) {
        return ResponseEntity.status(result.response().statusCode())
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.APPLICATION_JSON)
                .body(result.response().bodyJson());
    }

    @PostMapping("/api/v1/organizations/{organizationId}/facilities/{facilityId}/locations/{locationId}/activations")
    ResponseEntity<String> activate(@PathVariable UUID organizationId, @PathVariable UUID facilityId,
            @PathVariable UUID locationId, @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestHeader(value = "Idempotency-Key", required = false) String key, @RequestBody LocationLifecycle body,
            Authentication authentication, HttpServletRequest request) {
        return lifecycle(organizationId, facilityId, locationId, ifMatch, key, body, authentication, request, "activate");
    }

    @PostMapping("/api/v1/organizations/{organizationId}/facilities/{facilityId}/locations/{locationId}/suspensions")
    ResponseEntity<String> suspend(@PathVariable UUID organizationId, @PathVariable UUID facilityId,
            @PathVariable UUID locationId, @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestHeader(value = "Idempotency-Key", required = false) String key, @RequestBody LocationLifecycle body,
            Authentication authentication, HttpServletRequest request) {
        return lifecycle(organizationId, facilityId, locationId, ifMatch, key, body, authentication, request, "suspend");
    }

    @PostMapping("/api/v1/organizations/{organizationId}/facilities/{facilityId}/locations/{locationId}/reactivations")
    ResponseEntity<String> reactivate(@PathVariable UUID organizationId, @PathVariable UUID facilityId,
            @PathVariable UUID locationId, @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestHeader(value = "Idempotency-Key", required = false) String key, @RequestBody LocationLifecycle body,
            Authentication authentication, HttpServletRequest request) {
        return lifecycle(organizationId, facilityId, locationId, ifMatch, key, body, authentication, request, "reactivate");
    }

    @PostMapping("/api/v1/organizations/{organizationId}/facilities/{facilityId}/locations/{locationId}/closures")
    ResponseEntity<String> close(@PathVariable UUID organizationId, @PathVariable UUID facilityId,
            @PathVariable UUID locationId, @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestHeader(value = "Idempotency-Key", required = false) String key, @RequestBody LocationClosure body,
            Authentication authentication, HttpServletRequest request) {
        var actor = actor(authentication); var session = request.getSession(false);
        return response(service.close(new ServiceLocationService.Close(organizationId, actor.id(), CorrelationIdFilter.from(request),
                key, ifMatch, facilityId, locationId, body.effectiveTo(), body.reason(),
                assurance(session, AuthenticationSessionState.RECENT_AUTHENTICATION_AT), assurance(session, AuthenticationSessionState.MFA_AUTHENTICATED_AT))));
    }

    private ResponseEntity<String> lifecycle(UUID organizationId, UUID facilityId, UUID locationId, String ifMatch,
            String key, LocationLifecycle body, Authentication authentication, HttpServletRequest request, String action) {
        var actor = actor(authentication); var session = request.getSession(false);
        var command = new ServiceLocationService.Lifecycle(organizationId, actor.id(), CorrelationIdFilter.from(request), key,
                ifMatch, facilityId, locationId, body.reason(), assurance(session, AuthenticationSessionState.RECENT_AUTHENTICATION_AT),
                assurance(session, AuthenticationSessionState.MFA_AUTHENTICATED_AT));
        return response(switch (action) { case "activate" -> service.activate(command); case "suspend" -> service.suspend(command); case "reactivate" -> service.reactivate(command); default -> throw new IllegalArgumentException("unsupported lifecycle action"); });
    }

    private static Instant assurance(HttpSession session, String key) {
        var value = session == null ? null : session.getAttribute(key);
        return value instanceof Long epochMillis ? Instant.ofEpochMilli(epochMillis) : null;
    }

    private static AuthenticatedActor actor(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedActor actor)) {
            throw new ApiProblemException(
                    HttpStatus.UNAUTHORIZED,
                    "authentication-required",
                    "Authentication required",
                    "A valid authenticated session is required.");
        }
        return actor;
    }

    public record LocationCreate(
            UUID unitId,
            UUID parentId,
            UUID addressId,
            String locationCode,
            String locationType,
            String name,
            String virtualServiceType,
            Integer capacity,
            String accessibilityNotes,
            Instant effectiveFrom,
            Instant effectiveTo,
            String reason) {}

    public record LocationUpdate(
            UUID unitId,
            UUID addressId,
            String locationCode,
            String locationType,
            String name,
            String virtualServiceType,
            Integer capacity,
            String accessibilityNotes,
            Instant effectiveFrom,
            Instant effectiveTo,
            String reason) {}

    public record LocationReparent(UUID parentId, Instant effectiveFrom, String reason) {}
    public record LocationLifecycle(String reason) {}
    public record LocationClosure(Instant effectiveTo, String reason) {}
}
