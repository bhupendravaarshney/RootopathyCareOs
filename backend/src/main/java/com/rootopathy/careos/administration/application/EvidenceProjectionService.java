package com.rootopathy.careos.administration.application;

import com.rootopathy.careos.administration.domain.*;
import com.rootopathy.careos.governance.application.GovernedMutationExecutor;
import com.rootopathy.careos.governance.domain.*;
import com.rootopathy.careos.tenancy.application.TenantAuthorizationOperations;
import com.rootopathy.careos.tenancy.domain.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class EvidenceProjectionService {
  private static final Pattern REGISTRY_KEY=Pattern.compile("[a-z][a-z0-9_]*([.:-][a-z0-9_]+)*");
  private static final Pattern SUBJECT_TYPE=Pattern.compile("[a-z][a-z0-9_]{1,79}");
  private static final Pattern CORRELATION=Pattern.compile("[A-Za-z0-9._:-]{1,128}");
  private final TenantAuthorizationOperations auth; private final GovernedMutationExecutor mutations;
  private final EvidenceProjectionStore store; private final EvidenceCursorCodec cursors;
  private final ObjectMapper mapper; private final Clock clock;
  public EvidenceProjectionService(TenantAuthorizationOperations auth,GovernedMutationExecutor mutations,EvidenceProjectionStore store,EvidenceCursorCodec cursors,ObjectMapper mapper,Clock clock){this.auth=auth;this.mutations=mutations;this.store=store;this.cursors=cursors;this.mapper=mapper;this.clock=clock;}

  public ConfigurationHistoryPage history(History c){validateRange(c.from,c.to,366);token(c.status,"status",Set.of("draft","validated","submitted","approved","rejected","active","superseded"));token(c.changeType,"changeType",Set.of("activated","closed","retired","ended"));pattern(c.subjectType,"subjectType",SUBJECT_TYPE);pattern(c.correlationId,"correlationId",CORRELATION);var limit=limit(c.limit);var digest=hash(c.from,c.to,c.status,c.changeType,c.actorId,c.subjectType,c.subjectId,c.correlationId);var binding=new EvidenceCursorCodec.Binding(c.organizationId,"history",digest,limit);return auth.execute(req(c.organizationId,c.actorIdRequest,c.correlationRequest,"evidence.history.read",null,null),x->{var after=c.cursor==null?null:cursors.decode(c.cursor,binding);var asOf=after==null?clock.instant():after.asOf();var rows=store.history(x,new EvidenceProjectionStore.HistoryQuery(asOf,c.from,c.to,c.status,c.changeType,c.actorId,c.subjectType,c.subjectId,c.correlationId,limit,after));var more=rows.size()>limit;var items=more?List.copyOf(rows.subList(0,limit)):List.copyOf(rows);return new ConfigurationHistoryPage(c.organizationId,asOf,items,limit,more,more?cursors.encode(binding,new EvidenceCursorCodec.Position(asOf,items.getLast().recordedAt(),items.getLast().configurationId())):null);});}
  public AuditEvidencePage audit(Audit c){validateRange(c.from,c.to,90);pattern(c.operation,"operation",REGISTRY_KEY);pattern(c.eventName,"eventName",REGISTRY_KEY);pattern(c.subjectType,"subjectType",SUBJECT_TYPE);pattern(c.correlationId,"correlationId",CORRELATION);token(c.outcome,"outcome",Set.of("success","failure"));token(c.risk,"risk",Set.of("standard","high","restricted"));if(c.schemaVersion!=null&&(c.schemaVersion<1||c.schemaVersion>1000))throw new IllegalArgumentException("invalid schemaVersion");var limit=limit(c.limit);var digest=hash(c.from,c.to,c.actorId,c.operation,c.eventName,c.schemaVersion,c.subjectType,c.subjectId,c.outcome,c.risk,c.correlationId);var binding=new EvidenceCursorCodec.Binding(c.organizationId,"audit",digest,limit);return auth.execute(req(c.organizationId,c.actorIdRequest,c.correlationRequest,"evidence.audit.read",c.recentAuthenticationAt,c.mfaAuthenticatedAt),x->{var after=c.cursor==null?null:cursors.decode(c.cursor,binding);var asOf=after==null?clock.instant():after.asOf();var rows=store.audit(x,new EvidenceProjectionStore.AuditQuery(asOf,c.from,c.to,c.actorId,c.operation,c.eventName,c.schemaVersion,c.subjectType,c.subjectId,c.outcome,c.risk,c.correlationId,limit,after));var more=rows.size()>limit;var items=more?List.copyOf(rows.subList(0,limit)):List.copyOf(rows);return new AuditEvidencePage(c.organizationId,asOf,items,limit,more,more?cursors.encode(binding,new EvidenceCursorCodec.Position(asOf,items.getLast().occurredAt(),items.getLast().eventId())):null);});}
  public IdempotencyOutcome detail(Detail c){if(c.eventId==null||c.purposeCode==null||!Set.of("configuration_review","regulatory_evidence","security_investigation","data_correction").contains(c.purposeCode)||c.idempotencyKey==null||!c.idempotencyKey.matches("[A-Za-z0-9._:-]{16,128}"))throw new IllegalArgumentException("invalid audit-detail access request");var reason="Audit detail accessed for "+c.purposeCode;return mutations.execute(req(c.organizationId,c.actorId,c.correlationId,"evidence.audit.read",c.recentAuthenticationAt,c.mfaAuthenticatedAt),new IdempotencyCommand("evidence.audit.read",c.idempotencyKey,hash(c.eventId,c.purposeCode),clock.instant().plusSeconds(600)),x->{var detail=store.detail(x,c.eventId,c.purposeCode);var payload=json(Map.of("eventId",c.eventId,"purposeCode",c.purposeCode,"risk",detail.risk()));return new GovernedMutation(new IdempotentResponse(200,"application/json",json(detail)),GovernanceEvidence.auditOnly(new AuditRecord("evidence.audit.accessed",1,"audit_event",c.eventId,reason,payload)));});}
  private TenantAuthorizationRequest req(UUID organization,UUID actor,String correlation,String operation,Instant recent,Instant mfa){return new TenantAuthorizationRequest(organization,new AuthenticatedActorContext(actor,"evidence-projection",correlation),new OperationKey(operation),null,recent,mfa,null);}
  private static int limit(Integer value){if(value==null)return 25;if(value<1||value>100)throw new IllegalArgumentException("page size must be 1 to 100");return value;}
  private static void validateRange(Instant from,Instant to,int days){if(from==null||to==null||!to.isAfter(from)||Duration.between(from,to).compareTo(Duration.ofDays(days))>0)throw new IllegalArgumentException("invalid evidence time range");}
  private static void token(String value,String field,Set<String> allowed){if(value!=null&&!allowed.contains(value))throw new IllegalArgumentException("invalid "+field);}
  private static void pattern(String value,String field,Pattern allowed){if(value!=null&&!allowed.matcher(value).matches())throw new IllegalArgumentException("invalid "+field);}
  private String json(Object value){try{return mapper.writeValueAsString(value);}catch(Exception exception){throw new IllegalStateException(exception);}}
  private static String hash(Object...values){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Arrays.toString(values).getBytes(StandardCharsets.UTF_8)));}catch(Exception exception){throw new IllegalStateException(exception);}}
  public record History(UUID organizationId,UUID actorIdRequest,String correlationRequest,Instant from,Instant to,String status,String changeType,UUID actorId,String subjectType,UUID subjectId,String correlationId,Integer limit,String cursor){}
  public record Audit(UUID organizationId,UUID actorIdRequest,String correlationRequest,Instant from,Instant to,UUID actorId,String operation,String eventName,Integer schemaVersion,String subjectType,UUID subjectId,String outcome,String risk,String correlationId,Integer limit,String cursor,Instant recentAuthenticationAt,Instant mfaAuthenticatedAt){}
  public record Detail(UUID organizationId,UUID actorId,String correlationId,String idempotencyKey,UUID eventId,String purposeCode,Instant recentAuthenticationAt,Instant mfaAuthenticatedAt){}
}
