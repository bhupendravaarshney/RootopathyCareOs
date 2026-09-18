package com.rootopathy.careos.administration.application;

import com.rootopathy.careos.administration.domain.FacilityDirectory;
import com.rootopathy.careos.governance.application.GovernedMutationExecutor;
import com.rootopathy.careos.governance.domain.*;
import com.rootopathy.careos.tenancy.application.TenantAuthorizationOperations;
import com.rootopathy.careos.tenancy.domain.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.ZoneId;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public final class FacilityService {
 public static final String READ="network.facility.read",MANAGE="network.facility.manage";
 private static final Pattern CODE=Pattern.compile("[A-Z0-9][A-Z0-9_-]{1,31}"),KEY=Pattern.compile("[A-Za-z0-9._:-]{16,128}");
 private static final Pattern ETAG=Pattern.compile("\"facility:([0-9a-fA-F-]{36}):([0-9]{1,19})\"");
 private final TenantAuthorizationOperations auth; private final GovernedMutationExecutor mutations; private final FacilityStore store; private final ObjectMapper mapper; private final Clock clock;
 public FacilityService(TenantAuthorizationOperations a,GovernedMutationExecutor m,FacilityStore s,ObjectMapper o,Clock c){auth=a;mutations=m;store=s;mapper=o;clock=c;}
 public FacilityDirectory directory(Read c){var status=optional(c.status);if(status!=null&&!Set.of("draft","under_review","active","suspended","closed").contains(status))throw new IllegalArgumentException("invalid status");return auth.execute(request(c.organizationId,c.actorId,c.correlationId,READ,null),x->store.directory(x,optional(c.query),status));}
 public IdempotencyOutcome create(Create c){
  var reason=text(c.reason,"reason",10,500);if(c.idempotencyKey==null||!KEY.matcher(c.idempotencyKey).matches())throw new IllegalArgumentException("invalid Idempotency-Key");
  var code=text(c.facilityCode,"facilityCode",2,32).toUpperCase(Locale.ROOT);if(!CODE.matcher(code).matches())throw new IllegalArgumentException("invalid facilityCode");
  var timezone=optional(c.timezone);if(timezone!=null)try{ZoneId.of(timezone);}catch(Exception e){throw new IllegalArgumentException("invalid timezone");}
  var draft=new FacilityStore.Draft(code,text(c.legalName,"legalName",2,200),text(c.displayName,"displayName",2,120),text(c.facilityType,"facilityType",2,48),c.addressId,c.contactId,timezone);
  var command=new IdempotencyCommand(MANAGE,c.idempotencyKey,hash(draft,reason),clock.instant().plusSeconds(86400));
  return mutations.execute(request(c.organizationId,c.actorId,c.correlationId,MANAGE,reason),command,ctx->{var r=store.create(ctx,draft);var payload=json(Map.of("facilityId",r.facilityId(),"fromState","none","toState","draft","lockVersion",r.lockVersion()));return new GovernedMutation(new IdempotentResponse(201,"application/json",json(r.directory())),new GovernanceEvidence(new AuditRecord("facility.created",1,"facility",r.facilityId(),reason,payload),new OutboxRecord("facility.created",1,"facility",r.facilityId(),payload)));});
 }
 public IdempotencyOutcome update(Update c){var reason=text(c.reason,"reason",10,500);validKey(c.idempotencyKey);var revision=revision(c.ifMatch,c.facilityId);var code=text(c.facilityCode,"facilityCode",2,32).toUpperCase(Locale.ROOT);if(!CODE.matcher(code).matches())throw new IllegalArgumentException("invalid facilityCode");var timezone=optional(c.timezone);if(timezone!=null)try{ZoneId.of(timezone);}catch(Exception e){throw new IllegalArgumentException("invalid timezone");}var draft=new FacilityStore.Draft(code,text(c.legalName,"legalName",2,200),text(c.displayName,"displayName",2,120),text(c.facilityType,"facilityType",2,48),c.addressId,c.contactId,timezone);var command=new IdempotencyCommand(MANAGE,c.idempotencyKey,hash(c.facilityId,revision,draft,reason),clock.instant().plusSeconds(86400));return mutations.execute(request(c.organizationId,c.actorId,c.correlationId,MANAGE,reason),command,ctx->{var r=store.update(ctx,c.facilityId,revision,draft);var payload=json(Map.of("facilityId",r.facilityId(),"fromState","draft","toState","draft","lockVersion",r.lockVersion()));return new GovernedMutation(new IdempotentResponse(200,"application/json",json(r.directory())),new GovernanceEvidence(new AuditRecord("facility.updated",1,"facility",r.facilityId(),reason,payload),new OutboxRecord("facility.updated",1,"facility",r.facilityId(),payload)));});}
 public IdempotencyOutcome submit(Submit c){var reason=text(c.reason,"reason",10,500);validKey(c.idempotencyKey);var revision=revision(c.ifMatch,c.facilityId);var command=new IdempotencyCommand(MANAGE,c.idempotencyKey,hash(c.facilityId,revision,reason),clock.instant().plusSeconds(86400));return mutations.execute(request(c.organizationId,c.actorId,c.correlationId,MANAGE,reason),command,ctx->{var r=store.submit(ctx,c.facilityId,revision);var payload=json(Map.of("facilityId",r.facilityId(),"fromState","draft","toState","under_review","lockVersion",r.lockVersion()));return new GovernedMutation(new IdempotentResponse(200,"application/json",json(r.directory())),new GovernanceEvidence(new AuditRecord("facility.submitted",1,"facility",r.facilityId(),reason,payload),new OutboxRecord("facility.submitted",1,"facility",r.facilityId(),payload)));});}
 private static void validKey(String key){if(key==null||!KEY.matcher(key).matches())throw new IllegalArgumentException("invalid Idempotency-Key");}
 private static long revision(String etag,UUID id){var match=etag==null?null:ETAG.matcher(etag);if(match==null||!match.matches())throw new FacilityException(FacilityException.Reason.PRECONDITION_REQUIRED,"A strong facility If-Match value is required.");if(!UUID.fromString(match.group(1)).equals(id))throw new FacilityException(FacilityException.Reason.STALE,"The facility entity tag does not match this resource.");return Long.parseLong(match.group(2));}
 private TenantAuthorizationRequest request(UUID o,UUID a,String c,String op,String reason){return new TenantAuthorizationRequest(o,new AuthenticatedActorContext(a,"facility-administration",c),new OperationKey(op),reason,null,null,null);}
 private static String text(String v,String f,int min,int max){var n=v==null?"":v.strip();if(n.length()<min||n.length()>max||n.codePoints().anyMatch(Character::isISOControl))throw new IllegalArgumentException("invalid "+f);return n;}
 private static String optional(String v){return v==null||v.isBlank()?null:v.strip();}
 private String json(Object v){try{return mapper.writeValueAsString(v);}catch(Exception e){throw new IllegalStateException(e);}}
 private static String hash(Object...v){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Arrays.toString(v).getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
 public record Read(UUID organizationId,UUID actorId,String correlationId,String query,String status){}
 public record Create(UUID organizationId,UUID actorId,String correlationId,String idempotencyKey,String facilityCode,String legalName,String displayName,String facilityType,UUID addressId,UUID contactId,String timezone,String reason){}
 public record Update(UUID organizationId,UUID actorId,String correlationId,UUID facilityId,String ifMatch,String idempotencyKey,String facilityCode,String legalName,String displayName,String facilityType,UUID addressId,UUID contactId,String timezone,String reason){}
 public record Submit(UUID organizationId,UUID actorId,String correlationId,UUID facilityId,String ifMatch,String idempotencyKey,String reason){}
}
