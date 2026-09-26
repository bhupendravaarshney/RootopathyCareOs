package com.rootopathy.careos.patientregistry.api;

import com.rootopathy.careos.governance.domain.IdempotencyOutcome;
import com.rootopathy.careos.identity.api.AuthenticationSessionState;
import com.rootopathy.careos.patientregistry.application.PatientRegistryService;
import com.rootopathy.careos.patientregistry.domain.PatientRegistryScreen;
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
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
public class PatientRegistryController {
    private static final String SCREEN_PATTERN = "P3-(0[1-9]|1[0-6])";
    private static final String ACTION_PATTERN = "[a-z][a-z0-9]*(?:-[a-z0-9]+)*";
    private static final String IDEMPOTENCY_PATTERN = "[A-Za-z0-9._:-]{16,128}";
    private final PatientRegistryService patients;

    public PatientRegistryController(PatientRegistryService patients) {
        this.patients = patients;
    }

    @GetMapping("/api/v1/organizations/{organizationId}/patients/screens/{screenId}")
    ResponseEntity<PatientRegistryScreen> screen(
            @PathVariable UUID organizationId,
            @PathVariable @Pattern(regexp = SCREEN_PATTERN) String screenId,
            @RequestParam(required = false) UUID patientId,
            @RequestParam(required = false) UUID registrationId,
            @RequestParam(required = false) @Size(max = 100) String q,
            @RequestParam(required = false) @Size(max = 120) String status,
            @RequestParam(required = false) @Min(1) @Max(100) Integer limit,
            @RequestParam(required = false) @Size(max = 2048) String cursor,
            Authentication authentication,
            HttpServletRequest request) {
        var actor = actor(authentication);
        var session = request.getSession(false);
        var response = patients.screen(new PatientRegistryService.ReadCommand(
                organizationId,
                actor.id(),
                CorrelationIdFilter.from(request),
                screenId,
                patientId,
                registrationId,
                q,
                status,
                limit,
                cursor,
                assurance(session, AuthenticationSessionState.RECENT_AUTHENTICATION_AT),
                assurance(session, AuthenticationSessionState.MFA_AUTHENTICATED_AT)));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(response);
    }

    @PostMapping(
            path = "/api/v1/organizations/{organizationId}/patients/screens/{screenId}/actions/{actionKey}",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> action(
            @PathVariable UUID organizationId,
            @PathVariable @Pattern(regexp = SCREEN_PATTERN) String screenId,
            @PathVariable @Pattern(regexp = ACTION_PATTERN) String actionKey,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestHeader("Idempotency-Key")
                    @Pattern(regexp = IDEMPOTENCY_PATTERN)
                    String idempotencyKey,
            @Valid @RequestBody PatientActionRequest body,
            Authentication authentication,
            HttpServletRequest request) {
        var actor = actor(authentication);
        var session = request.getSession(false);
        var outcome = patients.act(new PatientRegistryService.ActionCommand(
                organizationId,
                actor.id(),
                CorrelationIdFilter.from(request),
                screenId,
                actionKey,
                body.targetId(),
                body.patientId(),
                body.registrationId(),
                body.decision(),
                body.reason(),
                body.fields(),
                body.impactToken(),
                ifMatch,
                idempotencyKey,
                assurance(session, AuthenticationSessionState.RECENT_AUTHENTICATION_AT),
                assurance(session, AuthenticationSessionState.MFA_AUTHENTICATED_AT)));
        return response(outcome);
    }

    @PostMapping(
            path = "/api/v1/organizations/{organizationId}/patients/screens/{screenId}/actions/{actionKey}/impact-preview",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<PatientRegistryService.ImpactPreviewResponse> impactPreview(
            @PathVariable UUID organizationId,
            @PathVariable @Pattern(regexp = SCREEN_PATTERN) String screenId,
            @PathVariable @Pattern(regexp = ACTION_PATTERN) String actionKey,
            @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
            @Valid @RequestBody PatientActionRequest body,
            Authentication authentication,
            HttpServletRequest request) {
        var actor = actor(authentication);
        var session = request.getSession(false);
        var response = patients.previewImpact(new PatientRegistryService.ImpactPreviewCommand(
                organizationId,
                actor.id(),
                CorrelationIdFilter.from(request),
                screenId,
                actionKey,
                body.targetId(),
                body.patientId(),
                body.registrationId(),
                body.decision(),
                body.reason(),
                body.fields(),
                ifMatch,
                assurance(session, AuthenticationSessionState.RECENT_AUTHENTICATION_AT),
                assurance(session, AuthenticationSessionState.MFA_AUTHENTICATED_AT)));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(response);
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

    public record PatientActionRequest(
            UUID targetId,
            UUID patientId,
            UUID registrationId,
            @Size(max = 80) String decision,
            @Size(max = 500) String reason,
            @NotNull @Size(max = 64) Map<@Size(max = 80) String, @Size(max = 2000) String> fields,
            @Size(max = 4096) String impactToken) {
        public PatientActionRequest {
            if (fields == null) {
                fields = Map.of();
            } else if (fields.entrySet().stream()
                    .anyMatch(entry -> entry.getKey() == null || entry.getValue() == null)) {
                throw new IllegalArgumentException(
                        "Patient action fields must not contain null keys or values.");
            } else {
                fields = Map.copyOf(fields);
            }
        }
    }
}
