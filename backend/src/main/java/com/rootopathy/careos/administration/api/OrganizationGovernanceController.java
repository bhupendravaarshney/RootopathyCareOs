package com.rootopathy.careos.administration.api;

import com.rootopathy.careos.administration.application.OrganizationGovernanceService;
import com.rootopathy.careos.administration.application.OrganizationGovernanceService.*;
import com.rootopathy.careos.administration.domain.OrganizationGovernanceDirectory;
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
public class OrganizationGovernanceController {
    private final OrganizationGovernanceService service;
    public OrganizationGovernanceController(OrganizationGovernanceService service){this.service=service;}

    @GetMapping("/api/v1/organizations/{organizationId}/governance-responsibilities")
    ResponseEntity<OrganizationGovernanceDirectory> directory(@PathVariable UUID organizationId,Authentication auth,HttpServletRequest request){
        var result=service.directory(new ReadCommand(organizationId,actor(auth).id(),CorrelationIdFilter.from(request)));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
    }
    @PostMapping("/api/v1/organizations/{organizationId}/governance-responsibilities")
    ResponseEntity<String> create(@PathVariable UUID organizationId,@RequestHeader(value="Idempotency-Key",required=false)String key,@RequestBody GovernanceWriteRequest body,Authentication auth,HttpSession session,HttpServletRequest request){
        return response(service.create(command(organizationId,null,null,key,body,auth,session,request)));
    }
    @PostMapping("/api/v1/organizations/{organizationId}/governance-responsibilities/{responsibilityId}/supersessions")
    ResponseEntity<String> supersede(@PathVariable UUID organizationId,@PathVariable UUID responsibilityId,@RequestHeader(value=HttpHeaders.IF_MATCH,required=false)String etag,@RequestHeader(value="Idempotency-Key",required=false)String key,@RequestBody GovernanceWriteRequest body,Authentication auth,HttpSession session,HttpServletRequest request){
        return response(service.supersede(command(organizationId,responsibilityId,etag,key,body,auth,session,request)));
    }
    @PostMapping("/api/v1/organizations/{organizationId}/governance-responsibilities/{responsibilityId}/endings")
    ResponseEntity<String> end(@PathVariable UUID organizationId,@PathVariable UUID responsibilityId,@RequestHeader(value=HttpHeaders.IF_MATCH,required=false)String etag,@RequestHeader(value="Idempotency-Key",required=false)String key,@RequestBody GovernanceEndRequest body,Authentication auth,HttpSession session,HttpServletRequest request){
        var a=actor(auth);return response(service.end(new EndCommand(organizationId,a.id(),CorrelationIdFilter.from(request),responsibilityId,etag,key,body.effectiveTo(),body.reason(),recent(session),mfa(session))));
    }
    private MutationCommand command(UUID org,UUID id,String etag,String key,GovernanceWriteRequest b,Authentication auth,HttpSession s,HttpServletRequest r){var a=actor(auth);return new MutationCommand(org,a.id(),CorrelationIdFilter.from(r),id,etag,key,b.responsibilityType(),b.membershipId(),b.externalContactId(),b.escalationEmail(),b.escalationPhone(),b.effectiveFrom(),b.reason(),recent(s),mfa(s));}
    private static ResponseEntity<String> response(MutationOutcome result){return ResponseEntity.status(result.outcome().response().statusCode()).cacheControl(CacheControl.noStore()).contentType(MediaType.APPLICATION_JSON).body(result.outcome().response().bodyJson());}
    private static AuthenticatedActor actor(Authentication a){if(a==null||!(a.getPrincipal() instanceof AuthenticatedActor actor))throw new ApiProblemException(HttpStatus.UNAUTHORIZED,"authentication-required","Authentication required","A valid authenticated session is required.");return actor;}
    private static Instant recent(HttpSession s){var v=s.getAttribute(AuthenticationSessionState.RECENT_AUTHENTICATION_AT);return v instanceof Long n?Instant.ofEpochMilli(n):null;}
    private static Instant mfa(HttpSession s){var v=s.getAttribute(AuthenticationSessionState.MFA_AUTHENTICATED_AT);return v instanceof Long n?Instant.ofEpochMilli(n):null;}
    public record GovernanceWriteRequest(String responsibilityType,UUID membershipId,UUID externalContactId,String escalationEmail,String escalationPhone,String effectiveFrom,String reason){}
    public record GovernanceEndRequest(String effectiveTo,String reason){}
}
