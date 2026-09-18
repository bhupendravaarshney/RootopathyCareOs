package com.rootopathy.careos.administration.infrastructure;

import com.rootopathy.careos.administration.application.*;
import com.rootopathy.careos.administration.domain.*;
import com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext;
import com.rootopathy.careos.tenancy.infrastructure.AuthorizedTenantTransactionGuard;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcOrganizationGovernanceStore implements OrganizationGovernanceStore {
    private final JdbcTemplate jdbc;
    public JdbcOrganizationGovernanceStore(JdbcTemplate jdbc){this.jdbc=jdbc;}

    @Override public OrganizationGovernanceDirectory directory(AuthorizedTenantContext c){
        AuthorizedTenantTransactionGuard.requireBound(jdbc,c);
        var now=Objects.requireNonNull(jdbc.queryForObject("SELECT clock_timestamp()",Timestamp.class)).toInstant();
        var manage=permissions(c).contains("organization.governance.manage");
        var assignees=new ArrayList<GovernanceAssignee>();
        assignees.addAll(jdbc.query("""
            SELECT m.id,u.display_name FROM organization_memberships m JOIN users u ON u.id=m.user_id
             WHERE m.organization_id=? AND m.status='active' AND m.effective_from<=clock_timestamp()
               AND (m.effective_to IS NULL OR m.effective_to>clock_timestamp()) ORDER BY lower(u.display_name),m.id
            """,(rs,n)->new GovernanceAssignee(rs.getObject("id",UUID.class),"membership",rs.getString("display_name")),c.organizationId()));
        assignees.addAll(jdbc.query("""
            SELECT id,channel,value_normalized FROM organization_contacts
             WHERE organization_id=? AND status='active' AND verification_status='verified' AND effective_from<=clock_timestamp()
               AND (effective_to IS NULL OR effective_to>clock_timestamp()) ORDER BY channel,id
            """,(rs,n)->new GovernanceAssignee(rs.getObject("id",UUID.class),"external_contact",mask(rs.getString("channel"),rs.getString("value_normalized"))),c.organizationId()));
        var rows=jdbc.query("""
            SELECT r.*,u.display_name,c.channel,c.value_normalized FROM organization_governance_responsibilities r
            LEFT JOIN organization_memberships m ON m.organization_id=r.organization_id AND m.id=r.membership_id
            LEFT JOIN users u ON u.id=m.user_id
            LEFT JOIN organization_contacts c ON c.organization_id=r.organization_id AND c.id=r.external_contact_id
            WHERE r.organization_id=? ORDER BY r.effective_from DESC,r.id DESC
            """,(rs,n)->map(rs,now,manage),c.organizationId());
        return new OrganizationGovernanceDirectory(c.organizationId(),manage,now,OrganizationGovernanceDirectory.TYPES,assignees,rows);
    }

    @Override public MutationResult create(AuthorizedTenantContext c,Draft d){
        AuthorizedTenantTransactionGuard.requireBound(jdbc,c);
        var id=insert(c,d,null,0);
        return result(c,id,d.responsibilityType(),"created",d.effectiveFrom(),0);
    }
    @Override public MutationResult supersede(AuthorizedTenantContext c,UUID id,long revision,Draft d){
        AuthorizedTenantTransactionGuard.requireBound(jdbc,c);
        try{
            var type=jdbc.query("SELECT responsibility_type FROM organization_governance_responsibilities WHERE organization_id=? AND id=?",
                    (rs,n)->rs.getString(1),c.organizationId(),id).stream().findFirst().orElseThrow(()->notFound());
            if(!type.equals(d.responsibilityType()))throw conflict("A replacement must retain its responsibility type.");
            var next=Math.addExact(revision,1);
            var changed=jdbc.update("""
                UPDATE organization_governance_responsibilities SET effective_to=?,status='superseded',lock_version=?,updated_at=clock_timestamp(),updated_by=?
                 WHERE organization_id=? AND id=? AND lock_version=? AND status='scheduled'
                """,Timestamp.from(d.effectiveFrom()),next,c.actorId(),c.organizationId(),id,revision);
            if(changed!=1)throw stale();
            var replacement=insert(c,d,id,next);
            return result(c,replacement,type,"superseded",d.effectiveFrom(),next);
        }catch(DataAccessException e){throw translate(e);}
    }
    @Override public MutationResult end(AuthorizedTenantContext c,UUID id,long revision,Instant effectiveTo){
        AuthorizedTenantTransactionGuard.requireBound(jdbc,c);
        try{
            var row=jdbc.query("SELECT responsibility_type,effective_from FROM organization_governance_responsibilities WHERE organization_id=? AND id=?",
                    (rs,n)->new Object[]{rs.getString(1),rs.getTimestamp(2).toInstant()},c.organizationId(),id).stream().findFirst().orElseThrow(()->notFound());
            var next=Math.addExact(revision,1);
            var changed=jdbc.update("""
                UPDATE organization_governance_responsibilities SET effective_to=?,status='ended',lock_version=?,updated_at=clock_timestamp(),updated_by=?
                 WHERE organization_id=? AND id=? AND lock_version=? AND status IN ('active','scheduled')
                """,Timestamp.from(effectiveTo),next,c.actorId(),c.organizationId(),id,revision);
            if(changed!=1)throw stale();
            return result(c,id,(String)row[0],"ended",(Instant)row[1],next);
        }catch(DataAccessException e){throw translate(e);}
    }

    private UUID insert(AuthorizedTenantContext c,Draft d,UUID supersedes,long version){
        try{return jdbc.queryForObject("""
            INSERT INTO organization_governance_responsibilities
             (organization_id,responsibility_type,membership_id,external_contact_id,escalation_email,escalation_phone,is_primary,effective_from,supersedes_id,status,lock_version,created_by,updated_by)
            VALUES (?,?,?,?,?,?,true,?,?,CASE WHEN ?>clock_timestamp() THEN 'scheduled' ELSE 'active' END,?,?,?) RETURNING id
            """,UUID.class,c.organizationId(),d.responsibilityType(),d.membershipId(),d.externalContactId(),d.escalationEmail(),d.escalationPhone(),Timestamp.from(d.effectiveFrom()),supersedes,Timestamp.from(d.effectiveFrom()),version,c.actorId(),c.actorId());}
        catch(DataAccessException e){throw translate(e);}
    }
    private MutationResult result(AuthorizedTenantContext c,UUID id,String type,String change,Instant from,long version){return new MutationResult(directory(c),id,type,change,from,version);}
    private static GovernanceResponsibility map(ResultSet r,Instant now,boolean manage)throws SQLException{
        var membership=r.getObject("membership_id",UUID.class);var channel=r.getString("channel");var raw=r.getString("value_normalized");
        var from=r.getTimestamp("effective_from").toInstant();var to=nullable(r,"effective_to");
        var lifecycle=from.isAfter(now)?"scheduled":to==null||to.isAfter(now)?"active":r.getString("status");
        List<String> actions=!manage?List.of():"active".equals(lifecycle)?List.of("supersede"):"scheduled".equals(lifecycle)?List.of("supersede","end"):List.of();
        return new GovernanceResponsibility(r.getObject("id",UUID.class),r.getString("responsibility_type"),membership!=null?"membership":"external_contact",membership!=null?membership:r.getObject("external_contact_id",UUID.class),membership!=null?r.getString("display_name"):mask(channel,raw),maskEmail(r.getString("escalation_email")),maskPhone(r.getString("escalation_phone")),r.getBoolean("is_primary"),from,to,r.getObject("supersedes_id",UUID.class),lifecycle,actions,r.getLong("lock_version"),r.getTimestamp("created_at").toInstant(),r.getTimestamp("updated_at").toInstant());
    }
    private Set<String> permissions(AuthorizedTenantContext c){return Set.copyOf(jdbc.queryForList("""
        SELECT DISTINCT p.permission_key FROM organization_memberships m JOIN authorization_roles r ON r.role_key=m.role_key
        JOIN authorization_role_permissions rp ON rp.role_key=r.role_key JOIN authorization_permissions p ON p.permission_key=rp.permission_key AND p.registry_version=r.registry_version
        WHERE m.organization_id=? AND m.user_id=? AND m.status='active' AND r.registry_version='m1-candidate-1' AND r.interactive AND r.status='active' AND p.status='active'
        """,String.class,c.organizationId(),c.actorId()));}
    private static Instant nullable(ResultSet r,String n)throws SQLException{var t=r.getTimestamp(n);return t==null?null:t.toInstant();}
    private static String mask(String channel,String v){if(v==null)return "Unavailable";return switch(channel){case "email"->maskEmail(v);case "phone"->maskPhone(v);default->"https://***";};}
    private static String maskEmail(String v){if(v==null)return null;var at=v.indexOf('@');return at>0?v.substring(0,1)+"***@***"+(v.lastIndexOf('.')>at?v.substring(v.lastIndexOf('.')):""):"***";}
    private static String maskPhone(String v){if(v==null)return null;return v.length()>4?"***"+v.substring(v.length()-4):"***";}
    private static OrganizationGovernanceException translate(DataAccessException e){var m=e.getMostSpecificCause().getMessage();if(m!=null&&(m.contains("overlap")||m.contains("eligible")||m.contains("replacement")||m.contains("handoff")))return conflict(m);throw e;}
    private static OrganizationGovernanceException stale(){return new OrganizationGovernanceException(OrganizationGovernanceException.Reason.STALE_REVISION,"Responsibility changed. Reload before continuing.");}
    private static OrganizationGovernanceException notFound(){return new OrganizationGovernanceException(OrganizationGovernanceException.Reason.NOT_FOUND,"Responsibility was not found.");}
    private static OrganizationGovernanceException conflict(String m){return new OrganizationGovernanceException(OrganizationGovernanceException.Reason.CONFLICT,m);}
}
