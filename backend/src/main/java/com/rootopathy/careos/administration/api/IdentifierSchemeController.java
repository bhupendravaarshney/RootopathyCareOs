package com.rootopathy.careos.administration.api;

import com.rootopathy.careos.administration.application.IdentifierSchemeService;
import com.rootopathy.careos.administration.domain.IdentifierSchemeDirectory;
import com.rootopathy.careos.governance.domain.IdempotencyOutcome;
import com.rootopathy.careos.shared.api.ApiProblemException;
import com.rootopathy.careos.shared.api.CorrelationIdFilter;
import com.rootopathy.careos.shared.domain.AuthenticatedActor;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
public class IdentifierSchemeController {
  private final IdentifierSchemeService service;

  public IdentifierSchemeController(IdentifierSchemeService service) {
    this.service = service;
  }

  @GetMapping("/api/v1/organizations/{organizationId}/identifier-schemes")
  ResponseEntity<IdentifierSchemeDirectory> directory(
      @PathVariable UUID organizationId, Authentication authentication, HttpServletRequest request) {
    var actor = actor(authentication);
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(service.directory(new IdentifierSchemeService.Read(
            organizationId, actor.id(), CorrelationIdFilter.from(request))));
  }

  @PostMapping("/api/v1/organizations/{organizationId}/identifier-schemes")
  ResponseEntity<String> create(
      @PathVariable UUID organizationId,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @RequestBody Write body,
      Authentication authentication,
      HttpServletRequest request) {
    var actor = actor(authentication);
    return response(service.create(command(
        organizationId, actor.id(), key, null, null, null, body, request)));
  }

  @PostMapping("/api/v1/organizations/{organizationId}/identifier-schemes/{schemeId}/versions")
  ResponseEntity<String> version(
      @PathVariable UUID organizationId,
      @PathVariable UUID schemeId,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String etag,
      @RequestBody Write body,
      Authentication authentication,
      HttpServletRequest request) {
    var actor = actor(authentication);
    return response(service.createVersion(command(
        organizationId, actor.id(), key, etag, schemeId, body.versionId(), body, request)));
  }

  private static IdentifierSchemeService.Write command(
      UUID organizationId,
      UUID actorId,
      String key,
      String etag,
      UUID schemeId,
      UUID versionId,
      Write body,
      HttpServletRequest request) {
    return new IdentifierSchemeService.Write(
        organizationId,
        actorId,
        CorrelationIdFilter.from(request),
        key,
        etag,
        schemeId,
        versionId,
        body.schemeKey(),
        body.scopeType(),
        body.scopeId(),
        body.description(),
        body.prefix(),
        body.pattern(),
        body.alphabet(),
        body.sequenceStart(),
        body.sequenceIncrement(),
        body.padding(),
        body.checkDigitAlgorithm(),
        body.effectiveFrom(),
        body.reason(),
        null,
        null);
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
          HttpStatus.UNAUTHORIZED,
          "authentication-required",
          "Authentication required",
          "A valid authenticated session is required.");
    }
    return actor;
  }

  public record Write(
      UUID versionId,
      String schemeKey,
      String scopeType,
      UUID scopeId,
      String description,
      String prefix,
      String pattern,
      String alphabet,
      long sequenceStart,
      int sequenceIncrement,
      int padding,
      String checkDigitAlgorithm,
      Instant effectiveFrom,
      String reason) {}
}
