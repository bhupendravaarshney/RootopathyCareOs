package com.rootopathy.careos.administration.api;

import com.rootopathy.careos.administration.application.EvidenceExportService;
import com.rootopathy.careos.administration.domain.EvidenceExportDirectory;
import com.rootopathy.careos.governance.domain.IdempotencyOutcome;
import com.rootopathy.careos.identity.api.AuthenticationSessionState;
import com.rootopathy.careos.shared.api.*;
import com.rootopathy.careos.shared.domain.AuthenticatedActor;
import jakarta.servlet.http.*;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
public class EvidenceExportController {
  private final EvidenceExportService service;

  public EvidenceExportController(EvidenceExportService service) {
    this.service = service;
  }

  @GetMapping("/api/v1/organizations/{organizationId}/evidence-exports")
  ResponseEntity<EvidenceExportDirectory> directory(
      @PathVariable UUID organizationId,
      @RequestParam(defaultValue = "history") String source,
      Authentication authentication,
      HttpServletRequest request) {
    var actor = actor(authentication);
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.directory(
        new EvidenceExportService.Read(
            organizationId, actor.id(), CorrelationIdFilter.from(request), "audit".equals(source))));
  }

  @PostMapping("/api/v1/organizations/{organizationId}/evidence-exports")
  ResponseEntity<String> request(
      @PathVariable UUID organizationId,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @RequestBody Request body,
      Authentication authentication,
      HttpServletRequest request) {
    var actor = actor(authentication);
    var session = request.getSession(false);
    return response(service.request(new EvidenceExportService.Request(
        organizationId, actor.id(), CorrelationIdFilter.from(request), key,
        body.projection(), body.format(), body.filters(), body.purposeCode(), body.legalBasisKey(),
        body.reason(), assurance(session, AuthenticationSessionState.RECENT_AUTHENTICATION_AT),
        assurance(session, AuthenticationSessionState.MFA_AUTHENTICATED_AT))));
  }

  @PostMapping("/api/v1/organizations/{organizationId}/evidence-exports/{exportId}/decisions")
  ResponseEntity<String> decide(
      @PathVariable UUID organizationId,
      @PathVariable UUID exportId,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String etag,
      @RequestBody Decision body,
      Authentication authentication,
      HttpServletRequest request) {
    var actor = actor(authentication);
    var session = request.getSession(false);
    return response(service.decide(new EvidenceExportService.Decision(
        organizationId, actor.id(), CorrelationIdFilter.from(request), key, etag, exportId,
        body.authorize(), body.reason(),
        assurance(session, AuthenticationSessionState.RECENT_AUTHENTICATION_AT),
        assurance(session, AuthenticationSessionState.MFA_AUTHENTICATED_AT))));
  }

  @PostMapping("/api/v1/organizations/{organizationId}/evidence-exports/{exportId}/accesses")
  ResponseEntity<String> access(
      @PathVariable UUID organizationId,
      @PathVariable UUID exportId,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String etag,
      @RequestBody Access body,
      Authentication authentication,
      HttpServletRequest request) {
    var actor = actor(authentication);
    var session = request.getSession(false);
    return response(service.access(new EvidenceExportService.Access(
        organizationId, actor.id(), CorrelationIdFilter.from(request), key, etag, exportId,
        body.purposeCode(), body.reason(),
        assurance(session, AuthenticationSessionState.RECENT_AUTHENTICATION_AT),
        assurance(session, AuthenticationSessionState.MFA_AUTHENTICATED_AT))));
  }

  private static ResponseEntity<String> response(IdempotencyOutcome result) {
    return ResponseEntity.status(result.response().statusCode())
        .cacheControl(CacheControl.noStore())
        .contentType(MediaType.APPLICATION_JSON)
        .body(result.response().bodyJson());
  }

  private static AuthenticatedActor actor(Authentication authentication) {
    if (authentication == null
        || !(authentication.getPrincipal() instanceof AuthenticatedActor actor)) {
      throw new ApiProblemException(
          HttpStatus.UNAUTHORIZED, "authentication-required", "Authentication required",
          "A valid authenticated session is required.");
    }
    return actor;
  }

  private static Instant assurance(HttpSession session, String key) {
    var value = session == null ? null : session.getAttribute(key);
    return value instanceof Long milliseconds ? Instant.ofEpochMilli(milliseconds) : null;
  }

  public record Request(
      String projection, String format, Map<String, Object> filters, String purposeCode,
      String legalBasisKey, String reason) {}
  public record Decision(boolean authorize, String reason) {}
  public record Access(String purposeCode, String reason) {}
}
