package com.rootopathy.careos.administration.api;

import com.rootopathy.careos.administration.application.ServiceAssignmentService;
import com.rootopathy.careos.administration.domain.ServiceAssignmentDirectory;
import com.rootopathy.careos.governance.domain.IdempotencyOutcome;
import com.rootopathy.careos.identity.api.AuthenticationSessionState;
import com.rootopathy.careos.shared.api.*;
import com.rootopathy.careos.shared.domain.AuthenticatedActor;
import jakarta.servlet.http.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
public class ServiceAssignmentController {
  private final ServiceAssignmentService service; public ServiceAssignmentController(ServiceAssignmentService service){this.service=service;}
  @GetMapping("/api/v1/organizations/{organizationId}/service-assignments") ResponseEntity<ServiceAssignmentDirectory> directory(@PathVariable UUID organizationId,Authentication authentication,HttpServletRequest request){var a=actor(authentication);return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.directory(new ServiceAssignmentService.Read(organizationId,a.id(),CorrelationIdFilter.from(request))));}
  @PostMapping("/api/v1/organizations/{organizationId}/service-assignments") ResponseEntity<String> create(@PathVariable UUID organizationId,@RequestHeader(value="Idempotency-Key",required=false)String key,@RequestBody Write body,Authentication authentication,HttpServletRequest request){var a=actor(authentication);return response(service.create(command(organizationId,a.id(),request,key,null,null,body)));}
  @PutMapping("/api/v1/organizations/{organizationId}/service-assignments/{assignmentId}") ResponseEntity<String> update(@PathVariable UUID organizationId,@PathVariable UUID assignmentId,@RequestHeader(value=HttpHeaders.IF_MATCH,required=false)String etag,@RequestHeader(value="Idempotency-Key",required=false)String key,@RequestBody Write body,Authentication authentication,HttpServletRequest request){var a=actor(authentication);return response(service.update(command(organizationId,a.id(),request,key,etag,assignmentId,body)));}
  @PostMapping("/api/v1/organizations/{organizationId}/service-assignments/{assignmentId}/{action}") ResponseEntity<String> lifecycle(@PathVariable UUID organizationId,@PathVariable UUID assignmentId,@PathVariable String action,@RequestHeader(value=HttpHeaders.IF_MATCH,required=false)String etag,@RequestHeader(value="Idempotency-Key",required=false)String key,@RequestBody Lifecycle body,Authentication authentication,HttpServletRequest request){var a=actor(authentication);var session=request.getSession(false);var c=new ServiceAssignmentService.Lifecycle(organizationId,a.id(),CorrelationIdFilter.from(request),key,etag,assignmentId,body.fromState(),body.reason(),assurance(session,AuthenticationSessionState.RECENT_AUTHENTICATION_AT),assurance(session,AuthenticationSessionState.MFA_AUTHENTICATED_AT));return response(switch(action){case "activations"->service.activate(c);case "suspensions"->service.suspend(c);case "endings"->service.end(c);case "cancellations"->service.cancel(c);default->throw new ApiProblemException(HttpStatus.NOT_FOUND,"assignment-action-not-found","Assignment action not found","The requested assignment lifecycle action does not exist.");});}
  private static ServiceAssignmentService.Write command(UUID o,UUID actor,HttpServletRequest request,String key,String etag,UUID id,Write b){return new ServiceAssignmentService.Write(o,actor,CorrelationIdFilter.from(request),key,etag,id,b.serviceId(),b.facilityId(),b.locationId(),b.capacity(),b.availabilityNotes(),b.prerequisites(),b.effectiveFrom(),b.effectiveTo(),b.reason());} private static ResponseEntity<String> response(IdempotencyOutcome r){return ResponseEntity.status(r.response().statusCode()).cacheControl(CacheControl.noStore()).contentType(MediaType.APPLICATION_JSON).body(r.response().bodyJson());} private static AuthenticatedActor actor(Authentication a){if(a==null||!(a.getPrincipal() instanceof AuthenticatedActor actor))throw new ApiProblemException(HttpStatus.UNAUTHORIZED,"authentication-required","Authentication required","A valid authenticated session is required.");return actor;} private static Instant assurance(HttpSession s,String key){var v=s==null?null:s.getAttribute(key);return v instanceof Long ms?Instant.ofEpochMilli(ms):null;}
  public record Write(UUID serviceId,UUID facilityId,UUID locationId,Integer capacity,String availabilityNotes,List<String> prerequisites,Instant effectiveFrom,Instant effectiveTo,String reason){} public record Lifecycle(String fromState,String reason){}
}
