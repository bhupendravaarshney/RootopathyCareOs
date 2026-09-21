package com.rootopathy.careos.administration.api;

import com.rootopathy.careos.administration.application.ConfigurationActivationService;
import com.rootopathy.careos.administration.domain.ConfigurationActivationDirectory;
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
public class ConfigurationActivationController {
  private final ConfigurationActivationService service;
  public ConfigurationActivationController(ConfigurationActivationService service){this.service=service;}

  @GetMapping("/api/v1/organizations/{organizationId}/configuration-activations")
  ResponseEntity<ConfigurationActivationDirectory> directory(@PathVariable UUID organizationId,Authentication authentication,HttpServletRequest request){var actor=actor(authentication);return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.directory(new ConfigurationActivationService.Read(organizationId,actor.id(),CorrelationIdFilter.from(request))));}

  @PostMapping("/api/v1/organizations/{organizationId}/configuration-validations")
  ResponseEntity<String> validate(@PathVariable UUID organizationId,@RequestHeader(value="Idempotency-Key",required=false)String key,@RequestHeader(value=HttpHeaders.IF_MATCH,required=false)String etag,@RequestBody Validation body,Authentication authentication,HttpServletRequest request){var actor=actor(authentication);var items=body.changeItems()==null?java.util.List.<ConfigurationActivationService.ChangeItem>of():body.changeItems().stream().map(item->new ConfigurationActivationService.ChangeItem(item.subjectType(),item.subjectId(),item.expectedRevision(),item.changeType())).toList();return response(service.validate(new ConfigurationActivationService.Validate(organizationId,actor.id(),CorrelationIdFilter.from(request),key,etag,body.configurationId(),body.changeSummary(),body.reason(),body.requestedEffectiveAt(),items)));}

  @PostMapping("/api/v1/organizations/{organizationId}/configurations/{configurationId}/submissions")
  ResponseEntity<String> submit(@PathVariable UUID organizationId,@PathVariable UUID configurationId,@RequestHeader(value="Idempotency-Key",required=false)String key,@RequestHeader(value=HttpHeaders.IF_MATCH,required=false)String etag,@RequestBody ResultAction body,Authentication authentication,HttpServletRequest request){var actor=actor(authentication);return response(service.submit(new ConfigurationActivationService.Action(organizationId,actor.id(),CorrelationIdFilter.from(request),key,etag,configurationId,body.resultId(),body.resultDigest(),body.reason())));}

  @PostMapping("/api/v1/organizations/{organizationId}/configurations/{configurationId}/decisions")
  ResponseEntity<String> decide(@PathVariable UUID organizationId,@PathVariable UUID configurationId,@RequestHeader(value="Idempotency-Key",required=false)String key,@RequestHeader(value=HttpHeaders.IF_MATCH,required=false)String etag,@RequestBody Decision body,Authentication authentication,HttpServletRequest request){var actor=actor(authentication);var session=request.getSession(false);var command=new ConfigurationActivationService.Decision(organizationId,actor.id(),CorrelationIdFilter.from(request),key,etag,configurationId,body.resultId(),body.resultDigest(),body.decisionCode(),body.reason(),assurance(session,AuthenticationSessionState.RECENT_AUTHENTICATION_AT),assurance(session,AuthenticationSessionState.MFA_AUTHENTICATED_AT));return response(body.approve()?service.approve(command):service.reject(command));}

  @PostMapping("/api/v1/organizations/{organizationId}/configurations/{configurationId}/activations")
  ResponseEntity<String> activate(@PathVariable UUID organizationId,@PathVariable UUID configurationId,@RequestHeader(value="Idempotency-Key",required=false)String key,@RequestHeader(value=HttpHeaders.IF_MATCH,required=false)String etag,@RequestBody Activation body,Authentication authentication,HttpServletRequest request){var actor=actor(authentication);var session=request.getSession(false);return response(service.activate(new ConfigurationActivationService.Activate(organizationId,actor.id(),CorrelationIdFilter.from(request),key,etag,configurationId,body.approvalId(),body.resultDigest(),body.effectiveFrom(),body.reason(),assurance(session,AuthenticationSessionState.RECENT_AUTHENTICATION_AT),assurance(session,AuthenticationSessionState.MFA_AUTHENTICATED_AT))));}

  private static ResponseEntity<String> response(IdempotencyOutcome result){return ResponseEntity.status(result.response().statusCode()).cacheControl(CacheControl.noStore()).contentType(MediaType.APPLICATION_JSON).body(result.response().bodyJson());}
  private static AuthenticatedActor actor(Authentication authentication){if(authentication==null||!(authentication.getPrincipal() instanceof AuthenticatedActor actor))throw new ApiProblemException(HttpStatus.UNAUTHORIZED,"authentication-required","Authentication required","A valid authenticated session is required.");return actor;}
  private static Instant assurance(HttpSession session,String key){var value=session==null?null:session.getAttribute(key);return value instanceof Long millis?Instant.ofEpochMilli(millis):null;}

  public record Validation(UUID configurationId,String changeSummary,String reason,Instant requestedEffectiveAt,java.util.List<ChangeItem> changeItems){}
  public record ChangeItem(String subjectType,UUID subjectId,long expectedRevision,String changeType){}
  public record ResultAction(UUID resultId,String resultDigest,String reason){}
  public record Decision(UUID resultId,String resultDigest,boolean approve,String decisionCode,String reason){}
  public record Activation(UUID approvalId,String resultDigest,Instant effectiveFrom,String reason){}
}
