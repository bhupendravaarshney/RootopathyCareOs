package com.rootopathy.careos.administration.application;

import com.rootopathy.careos.administration.application.OrganizationGovernanceStore.Draft;
import com.rootopathy.careos.administration.domain.OrganizationGovernanceDirectory;
import com.rootopathy.careos.governance.application.GovernedMutationExecutor;
import com.rootopathy.careos.governance.domain.*;
import com.rootopathy.careos.tenancy.application.TenantAuthorizationOperations;
import com.rootopathy.careos.tenancy.domain.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public final class OrganizationGovernanceService {
    public static final String READ = "organization.governance.read";
    public static final String MANAGE = "organization.governance.manage";
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9._:-]{16,128}");
    private static final Pattern ETAG = Pattern.compile("\"organization-governance:[0-9a-fA-F-]{36}:([0-9]{1,19})\"");
    private final TenantAuthorizationOperations authorization;
    private final GovernedMutationExecutor mutations;
    private final OrganizationGovernanceStore store;
    private final ObjectMapper mapper;
    private final Clock clock;

    public OrganizationGovernanceService(TenantAuthorizationOperations authorization, GovernedMutationExecutor mutations,
            OrganizationGovernanceStore store, ObjectMapper mapper, Clock clock) {
        this.authorization=authorization; this.mutations=mutations; this.store=store; this.mapper=mapper; this.clock=clock;
    }

    public OrganizationGovernanceDirectory directory(ReadCommand command) {
        return authorization.execute(request(command.organizationId(), command.actorId(), command.correlationId(), READ, null, null, null), store::directory);
    }

    public MutationOutcome create(MutationCommand command) {
        return mutate(command, context -> store.create(context, draft(command)));
    }

    public MutationOutcome supersede(MutationCommand command) {
        if (command.responsibilityId()==null) throw invalid("responsibilityId is required", "responsibilityId");
        return mutate(command, context -> store.supersede(context, command.responsibilityId(), revision(command.ifMatch()), draft(command)));
    }

    public MutationOutcome end(EndCommand command) {
        var reason=text(command.reason(), "reason", 10, 500);
        var effectiveTo=instant(command.effectiveTo(), "effectiveTo");
        var revision=revision(command.ifMatch());
        return governed(command.organizationId(), command.actorId(), command.correlationId(), command.recentAuthenticationAt(), command.mfaAuthenticatedAt(),
                reason, command.idempotencyKey(), hash(command.organizationId(), command.responsibilityId(), revision, effectiveTo, reason),
                context -> store.end(context, command.responsibilityId(), revision, effectiveTo));
    }

    private MutationOutcome mutate(MutationCommand command, Work work) {
        var reason=text(command.reason(), "reason", 10, 500);
        var draft=draft(command);
        return governed(command.organizationId(), command.actorId(), command.correlationId(), command.recentAuthenticationAt(), command.mfaAuthenticatedAt(),
                reason, command.idempotencyKey(), hash(command.organizationId(), command.responsibilityId(), command.ifMatch(), draft, reason), work);
    }

    private MutationOutcome governed(UUID organizationId, UUID actorId, String correlationId, Instant recent, Instant mfa,
            String reason, String key, String hash, Work work) {
        if (key==null || !KEY.matcher(key).matches()) throw invalid("Idempotency-Key has an invalid format", null);
        var outcome=mutations.execute(request(organizationId, actorId, correlationId, MANAGE, reason, recent, mfa),
                new IdempotencyCommand(MANAGE,key,hash,clock.instant().plusSeconds(86400)), context -> {
                    var result=work.apply(context);
                    var payload=new LinkedHashMap<String,Object>();
                    payload.put("responsibilityId",result.responsibilityId());
                    payload.put("responsibilityType",result.responsibilityType());
                    payload.put("changeType",result.changeType());
                    payload.put("effectiveFrom",result.effectiveFrom());
                    payload.put("lockVersion",result.lockVersion());
                    var json=json(payload);
                    return new GovernedMutation(new IdempotentResponse(result.changeType().equals("created")?201:200,"application/json",json(result.directory())),
                            new GovernanceEvidence(new AuditRecord("organization.governance.changed",1,"organization_governance_responsibility",result.responsibilityId(),reason,json),
                                    new OutboxRecord("organization.governance.changed",1,"organization_governance_responsibility",result.responsibilityId(),json)));
                });
        return new MutationOutcome(outcome);
    }

    private Draft draft(MutationCommand c) {
        var type=text(c.responsibilityType(),"responsibilityType",3,24).toLowerCase(Locale.ROOT);
        if (!OrganizationGovernanceDirectory.TYPES.contains(type)) throw invalid("responsibilityType is not approved","responsibilityType");
        if ((c.membershipId()==null)==(c.externalContactId()==null)) throw invalid("exactly one assignee reference is required","membershipId");
        var email=optional(c.escalationEmail()); var phone=optional(c.escalationPhone());
        if (email==null && phone==null) throw invalid("an escalation channel is required","escalationEmail");
        if (email!=null && !email.matches("^[^\\s@]+@[^\\s@]+[.][^\\s@]+$") ) throw invalid("escalationEmail is invalid","escalationEmail");
        if (phone!=null && !phone.matches("^[+][1-9][0-9]{1,14}$")) throw invalid("escalationPhone is invalid","escalationPhone");
        return new Draft(type,c.membershipId(),c.externalContactId(),email==null?null:email.toLowerCase(Locale.ROOT),phone,instant(c.effectiveFrom(),"effectiveFrom"));
    }

    private TenantAuthorizationRequest request(UUID org, UUID actor, String correlation, String operation, String reason, Instant recent, Instant mfa) {
        return new TenantAuthorizationRequest(org,new AuthenticatedActorContext(actor,"organization-administration",correlation),new OperationKey(operation),reason,recent,mfa,null);
    }
    public static String entityTag(UUID id,long version){return "\"organization-governance:"+id+":"+version+"\"";}
    private static long revision(String value){if(value==null)throw new OrganizationGovernanceException(OrganizationGovernanceException.Reason.PRECONDITION_REQUIRED,"A strong If-Match value is required");var m=ETAG.matcher(value);if(!m.matches())throw invalid("If-Match is invalid",null);return Long.parseLong(m.group(1));}
    private static String text(String v,String field,int min,int max){var n=v==null?"":v.strip();if(n.length()<min||n.length()>max||n.codePoints().anyMatch(Character::isISOControl))throw invalid(field+" is invalid",field);return n;}
    private static String optional(String v){return v==null||v.isBlank()?null:v.strip();}
    private static Instant instant(String v,String field){try{return Instant.parse(v);}catch(Exception e){throw invalid(field+" must be an ISO instant",field);}}
    private String json(Object v){try{return mapper.writeValueAsString(v);}catch(Exception e){throw new IllegalStateException(e);}}
    private static String hash(Object... values){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Arrays.toString(values).getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    private static OrganizationGovernanceException invalid(String message,String field){return new OrganizationGovernanceException(OrganizationGovernanceException.Reason.INVALID_REQUEST,message,field);}
    @FunctionalInterface private interface Work { OrganizationGovernanceStore.MutationResult apply(com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext context); }
    public record ReadCommand(UUID organizationId,UUID actorId,String correlationId){}
    public record MutationCommand(UUID organizationId,UUID actorId,String correlationId,UUID responsibilityId,String ifMatch,String idempotencyKey,String responsibilityType,UUID membershipId,UUID externalContactId,String escalationEmail,String escalationPhone,String effectiveFrom,String reason,Instant recentAuthenticationAt,Instant mfaAuthenticatedAt){}
    public record EndCommand(UUID organizationId,UUID actorId,String correlationId,UUID responsibilityId,String ifMatch,String idempotencyKey,String effectiveTo,String reason,Instant recentAuthenticationAt,Instant mfaAuthenticatedAt){}
    public record MutationOutcome(IdempotencyOutcome outcome){}
}
