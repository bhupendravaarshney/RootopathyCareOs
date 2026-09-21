package com.rootopathy.careos.administration.api;

import com.rootopathy.careos.administration.application.EvidenceProjectionService;
import com.rootopathy.careos.administration.domain.*;
import com.rootopathy.careos.governance.domain.IdempotencyOutcome;
import com.rootopathy.careos.identity.api.AuthenticationSessionState;
import com.rootopathy.careos.shared.api.*;
import com.rootopathy.careos.shared.domain.AuthenticatedActor;
import jakarta.servlet.http.*;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
public class EvidenceProjectionController {
  private final EvidenceProjectionService service; public EvidenceProjectionController(EvidenceProjectionService service){this.service=service;}
  @GetMapping("/api/v1/organizations/{organizationId}/configuration-history") ResponseEntity<ConfigurationHistoryPage> history(@PathVariable UUID organizationId,@RequestParam Instant from,@RequestParam Instant to,@RequestParam(required=false)String status,@RequestParam(required=false)String changeType,@RequestParam(required=false)UUID actorId,@RequestParam(required=false)String subjectType,@RequestParam(required=false)UUID subjectId,@RequestParam(required=false)String correlationId,@RequestParam(required=false)Integer limit,@RequestParam(required=false)String cursor,Authentication authentication,HttpServletRequest request){var actor=actor(authentication);return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.history(new EvidenceProjectionService.History(organizationId,actor.id(),CorrelationIdFilter.from(request),from,to,status,changeType,actorId,subjectType,subjectId,correlationId,limit,cursor)));}
  @GetMapping("/api/v1/organizations/{organizationId}/audit-evidence") ResponseEntity<AuditEvidencePage> audit(@PathVariable UUID organizationId,@RequestParam Instant from,@RequestParam Instant to,@RequestParam(required=false)UUID actorId,@RequestParam(required=false)String operation,@RequestParam(required=false)String eventName,@RequestParam(required=false)Integer schemaVersion,@RequestParam(required=false)String subjectType,@RequestParam(required=false)UUID subjectId,@RequestParam(required=false)String outcome,@RequestParam(required=false)String risk,@RequestParam(required=false)String correlationId,@RequestParam(required=false)Integer limit,@RequestParam(required=false)String cursor,Authentication authentication,HttpServletRequest request){var actor=actor(authentication);var session=request.getSession(false);return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.audit(new EvidenceProjectionService.Audit(organizationId,actor.id(),CorrelationIdFilter.from(request),from,to,actorId,operation,eventName,schemaVersion,subjectType,subjectId,outcome,risk,correlationId,limit,cursor,assurance(session,AuthenticationSessionState.RECENT_AUTHENTICATION_AT),assurance(session,AuthenticationSessionState.MFA_AUTHENTICATED_AT))));}
  @PostMapping("/api/v1/organizations/{organizationId}/audit-evidence/{eventId}/accesses") ResponseEntity<String> detail(@PathVariable UUID organizationId,@PathVariable UUID eventId,@RequestHeader(value="Idempotency-Key",required=false)String key,@RequestBody Detail body,Authentication authentication,HttpServletRequest request){var actor=actor(authentication);var session=request.getSession(false);return response(service.detail(new EvidenceProjectionService.Detail(organizationId,actor.id(),CorrelationIdFilter.from(request),key,eventId,body.purposeCode(),assurance(session,AuthenticationSessionState.RECENT_AUTHENTICATION_AT),assurance(session,AuthenticationSessionState.MFA_AUTHENTICATED_AT))));}
  private static ResponseEntity<String> response(IdempotencyOutcome result){return ResponseEntity.status(result.response().statusCode()).cacheControl(CacheControl.noStore()).contentType(MediaType.APPLICATION_JSON).body(result.response().bodyJson());}
  private static AuthenticatedActor actor(Authentication authentication){if(authentication==null||!(authentication.getPrincipal() instanceof AuthenticatedActor actor))throw new ApiProblemException(HttpStatus.UNAUTHORIZED,"authentication-required","Authentication required","A valid authenticated session is required.");return actor;}
  private static Instant assurance(HttpSession session,String key){var value=session==null?null:session.getAttribute(key);return value instanceof Long millis?Instant.ofEpochMilli(millis):null;}
  public record Detail(String purposeCode){}
}
