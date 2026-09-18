package com.rootopathy.careos.administration.api;

import com.rootopathy.careos.administration.application.FacilityService;
import com.rootopathy.careos.administration.domain.FacilityDirectory;
import com.rootopathy.careos.shared.api.*;
import com.rootopathy.careos.shared.domain.AuthenticatedActor;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
public class FacilityController {
 private final FacilityService service; public FacilityController(FacilityService s){service=s;}
 @GetMapping("/api/v1/organizations/{organizationId}/facilities")
 ResponseEntity<FacilityDirectory> directory(@PathVariable UUID organizationId,@RequestParam(required=false)String query,@RequestParam(required=false)String status,Authentication authentication,HttpServletRequest request){var a=actor(authentication);return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.directory(new FacilityService.Read(organizationId,a.id(),CorrelationIdFilter.from(request),query,status)));}
 @PostMapping("/api/v1/organizations/{organizationId}/facilities")
 ResponseEntity<String> create(@PathVariable UUID organizationId,@RequestHeader(value="Idempotency-Key",required=false)String key,@RequestBody FacilityCreate body,Authentication authentication,HttpServletRequest request){var a=actor(authentication);var result=service.create(new FacilityService.Create(organizationId,a.id(),CorrelationIdFilter.from(request),key,body.facilityCode(),body.legalName(),body.displayName(),body.facilityType(),body.addressId(),body.contactId(),body.timezone(),body.reason()));return ResponseEntity.status(result.response().statusCode()).cacheControl(CacheControl.noStore()).contentType(MediaType.APPLICATION_JSON).body(result.response().bodyJson());}
 @PutMapping("/api/v1/organizations/{organizationId}/facilities/{facilityId}")
 ResponseEntity<String> update(@PathVariable UUID organizationId,@PathVariable UUID facilityId,@RequestHeader(value=HttpHeaders.IF_MATCH,required=false)String etag,@RequestHeader(value="Idempotency-Key",required=false)String key,@RequestBody FacilityCreate body,Authentication authentication,HttpServletRequest request){var a=actor(authentication);var result=service.update(new FacilityService.Update(organizationId,a.id(),CorrelationIdFilter.from(request),facilityId,etag,key,body.facilityCode(),body.legalName(),body.displayName(),body.facilityType(),body.addressId(),body.contactId(),body.timezone(),body.reason()));return ResponseEntity.status(result.response().statusCode()).cacheControl(CacheControl.noStore()).contentType(MediaType.APPLICATION_JSON).body(result.response().bodyJson());}
 @PostMapping("/api/v1/organizations/{organizationId}/facilities/{facilityId}/submissions")
 ResponseEntity<String> submit(@PathVariable UUID organizationId,@PathVariable UUID facilityId,@RequestHeader(value=HttpHeaders.IF_MATCH,required=false)String etag,@RequestHeader(value="Idempotency-Key",required=false)String key,@RequestBody FacilitySubmit body,Authentication authentication,HttpServletRequest request){var a=actor(authentication);var result=service.submit(new FacilityService.Submit(organizationId,a.id(),CorrelationIdFilter.from(request),facilityId,etag,key,body.reason()));return ResponseEntity.status(result.response().statusCode()).cacheControl(CacheControl.noStore()).contentType(MediaType.APPLICATION_JSON).body(result.response().bodyJson());}
 private static AuthenticatedActor actor(Authentication a){if(a==null||!(a.getPrincipal() instanceof AuthenticatedActor actor))throw new ApiProblemException(HttpStatus.UNAUTHORIZED,"authentication-required","Authentication required","A valid authenticated session is required.");return actor;}
 public record FacilityCreate(String facilityCode,String legalName,String displayName,String facilityType,UUID addressId,UUID contactId,String timezone,String reason){}
 public record FacilitySubmit(String reason){}
}
