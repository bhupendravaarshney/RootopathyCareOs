package com.rootopathy.careos.administration.application;

import com.rootopathy.careos.administration.domain.IdentifierSchemeDirectory;
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
public class IdentifierSchemeService {
  private static final Pattern KEY=Pattern.compile("[A-Za-z0-9._:-]{16,128}");
  private static final Pattern CODE=Pattern.compile("[A-Z][A-Z0-9_.-]{1,39}");
  private static final Pattern SAFE=Pattern.compile("[A-Za-z0-9\\[\\]{}()^$.*+?_|\\-]{1,200}");
  private static final Pattern ETAG=Pattern.compile("\"identifier-scheme:([0-9a-fA-F-]{36}):([0-9]+):([0-9a-fA-F-]{36}):([0-9]+)\"");
  private final TenantAuthorizationOperations auth; private final GovernedMutationExecutor mutations;
  private final IdentifierSchemeStore store; private final ObjectMapper mapper; private final Clock clock;
  public IdentifierSchemeService(TenantAuthorizationOperations auth,GovernedMutationExecutor mutations,IdentifierSchemeStore store,ObjectMapper mapper,Clock clock){this.auth=auth;this.mutations=mutations;this.store=store;this.mapper=mapper;this.clock=clock;}

  public IdentifierSchemeDirectory directory(Read c){return auth.execute(req(c.organizationId,c.actorId,c.correlationId,"identifier.scheme.read",null,null,null),store::directory);}
  public IdempotencyOutcome create(Write c){var d=draft(c);return execute(c,"identifier.scheme.manage",null,x->store.create(x,new IdentifierSchemeStore.Draft(code(c.schemeKey),scope(c.scopeType,c.scopeId),c.scopeId,optional(c.description,500),d)),"created",201);}
  public IdempotencyOutcome createVersion(Write c){var revisions=revisions(c.ifMatch,c.schemeId,c.versionId);var d=draft(c);return execute(c,"identifier.scheme.version.create",revisions,x->store.createVersion(x,c.schemeId,revisions[0],d),"created",201);}
  private IdempotencyOutcome execute(Write c,String operation,long[] revisions,java.util.function.Function<AuthorizedTenantContext,IdentifierSchemeStore.Result> action,String change,int status){var reason=text(c.reason,"reason",10,500);key(c.idempotencyKey);var command=new IdempotencyCommand(operation,c.idempotencyKey,hash(c.schemeId,c.versionId,revisions,change,reason),clock.instant().plusSeconds(86400));return mutations.execute(req(c.organizationId,c.actorId,c.correlationId,operation,reason,c.recentAuthenticationAt,c.mfaAuthenticatedAt),command,x->{var result=action.apply(x);var payload=json(Map.of("schemeId",result.schemeId(),"versionId",result.versionId(),"fromState",result.fromState(),"toState",result.toState(),"effectiveFrom",result.effectiveFrom()));var event="identifier.scheme."+change;return new GovernedMutation(new IdempotentResponse(status,"application/json",json(result.directory())),new GovernanceEvidence(new AuditRecord(event,1,"identifier_scheme",result.schemeId(),reason,payload),new OutboxRecord(event,1,"identifier_scheme",result.schemeId(),payload)));});}

  private IdentifierSchemeStore.VersionDraft draft(Write c){
    var prefix=c.prefix==null?"":c.prefix.strip();
    if(prefix.length()>20||!SAFE.matcher(c.pattern==null?"":c.pattern).matches()||c.alphabet==null||!c.alphabet.matches("[A-Za-z0-9]{2,80}")||c.sequenceStart<0||c.sequenceIncrement<1||c.sequenceIncrement>1000000||c.padding<1||c.padding>20||c.effectiveFrom==null||c.checkDigitAlgorithm!=null&&!Set.of("luhn_mod_n","mod_11").contains(c.checkDigitAlgorithm))throw new IllegalArgumentException("invalid scheme version");
    java.util.regex.Pattern compiled;try{compiled=java.util.regex.Pattern.compile(c.pattern);}catch(java.util.regex.PatternSyntaxException exception){throw new IllegalArgumentException("invalid identifier pattern");}
    var previews=new ArrayList<String>();
    for(int index=0;index<3;index++){
      long sequence;try{sequence=Math.addExact(c.sequenceStart,Math.multiplyExact((long)index,c.sequenceIncrement));}catch(ArithmeticException exception){throw new IllegalArgumentException("identifier sequence overflows");}
      var value=prefix+String.format(Locale.ROOT,"%0"+c.padding+"d",sequence);
      if("mod_11".equals(c.checkDigitAlgorithm))value+=mod11(value);else if("luhn_mod_n".equals(c.checkDigitAlgorithm))value+=luhn(value,c.alphabet);
      if(!compiled.matcher(value).matches())throw new IllegalArgumentException("server preview does not match identifier pattern");
      previews.add(value);
    }
    return new IdentifierSchemeStore.VersionDraft(prefix,c.pattern,c.alphabet,c.checkDigitAlgorithm,c.sequenceStart,c.sequenceIncrement,c.padding,List.copyOf(previews),c.effectiveFrom);
  }
  private static char mod11(String value){int sum=0,weight=2;for(int index=value.length()-1;index>=0;index--){var ch=value.charAt(index);if(Character.isDigit(ch)){sum+=(ch-'0')*weight;weight=weight==7?2:weight+1;}}int check=11-sum%11;return check==10?'X':check==11?'0':Character.forDigit(check,10);}
  private static char luhn(String value,String alphabet){int factor=2,sum=0,cardinality=alphabet.length();for(int index=value.length()-1;index>=0;index--){int code=alphabet.indexOf(value.charAt(index));if(code<0)throw new IllegalArgumentException("identifier character is outside alphabet");int addend=factor*code;factor=factor==2?1:2;addend=addend/cardinality+addend%cardinality;sum+=addend;}return alphabet.charAt((cardinality-sum%cardinality)%cardinality);}
  private static String code(String value){var normalized=value==null?"":value.strip().toUpperCase(Locale.ROOT);if(!CODE.matcher(normalized).matches())throw new IllegalArgumentException("invalid scheme key");return normalized;}
  private static String scope(String value,UUID id){if(!Set.of("organization","facility","service").contains(value)||(value.equals("organization")&&id!=null)||(!value.equals("organization")&&id==null))throw new IllegalArgumentException("invalid scheme scope");return value;}
  private TenantAuthorizationRequest req(UUID organization,UUID actor,String correlation,String operation,String reason,Instant recent,Instant mfa){return new TenantAuthorizationRequest(organization,new AuthenticatedActorContext(actor,"identifier-scheme-administration",correlation),new OperationKey(operation),reason,recent,mfa,null);}
  private static long[] revisions(String etag,UUID scheme,UUID version){var matcher=etag==null?null:ETAG.matcher(etag);if(matcher==null||!matcher.matches()||!UUID.fromString(matcher.group(1)).equals(scheme)||!UUID.fromString(matcher.group(3)).equals(version))throw new IllegalArgumentException("strong scheme If-Match required");return new long[]{Long.parseLong(matcher.group(2)),Long.parseLong(matcher.group(4))};}
  private static String text(String value,String field,int min,int max){var normalized=value==null?"":value.strip();if(normalized.length()<min||normalized.length()>max||normalized.codePoints().anyMatch(Character::isISOControl))throw new IllegalArgumentException("invalid "+field);return normalized;}
  private static String optional(String value,int max){return value==null||value.isBlank()?null:text(value,"optional field",1,max);}
  private static void key(String value){if(value==null||!KEY.matcher(value).matches())throw new IllegalArgumentException("invalid Idempotency-Key");}
  private String json(Object value){try{return mapper.writeValueAsString(value);}catch(Exception exception){throw new IllegalStateException(exception);}}
  private static String hash(Object...values){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Arrays.deepToString(values).getBytes(StandardCharsets.UTF_8)));}catch(Exception exception){throw new IllegalStateException(exception);}}

  public record Read(UUID organizationId,UUID actorId,String correlationId){}
  public record Write(UUID organizationId,UUID actorId,String correlationId,String idempotencyKey,String ifMatch,UUID schemeId,UUID versionId,String schemeKey,String scopeType,UUID scopeId,String description,String prefix,String pattern,String alphabet,long sequenceStart,int sequenceIncrement,int padding,String checkDigitAlgorithm,Instant effectiveFrom,String reason,Instant recentAuthenticationAt,Instant mfaAuthenticatedAt){}
}
