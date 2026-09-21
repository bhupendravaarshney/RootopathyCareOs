package com.rootopathy.careos.administration.infrastructure;

import com.rootopathy.careos.administration.application.FacilityStore;
import com.rootopathy.careos.administration.application.FacilityService;
import com.rootopathy.careos.administration.application.FacilityException;
import com.rootopathy.careos.administration.domain.FacilityDirectory;
import com.rootopathy.careos.administration.domain.FacilityDirectory.*;
import com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext;
import com.rootopathy.careos.tenancy.infrastructure.AuthorizedTenantTransactionGuard;
import java.sql.Timestamp;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcFacilityStore implements FacilityStore {
 private final JdbcTemplate jdbc; public JdbcFacilityStore(JdbcTemplate j){jdbc=j;}
 public FacilityDirectory directory(AuthorizedTenantContext c,String query,String status){AuthorizedTenantTransactionGuard.requireBound(jdbc,c);var q=query==null?"":query.toLowerCase(Locale.ROOT);var s=status==null?"":status;var types=jdbc.query("SELECT type_key,display_name FROM facility_types WHERE status='active' ORDER BY display_name",(r,n)->new FacilityType(r.getString(1),r.getString(2)));var rows=jdbc.query("""
 SELECT id,code,legal_name,name,facility_type,timezone,status,lock_version,created_at,updated_at FROM facilities
 WHERE organization_id=? AND (?='' OR lower(name) LIKE '%'||?||'%' OR lower(code) LIKE '%'||?||'%') AND (?='' OR status=?) ORDER BY lower(name),id
 """,(r,n)->new FacilitySummary(r.getObject(1,UUID.class),r.getString(2),r.getString(3),r.getString(4),r.getString(5),r.getString(6),r.getString(7),r.getLong(8),r.getTimestamp(9).toInstant(),r.getTimestamp(10).toInstant()),c.organizationId(),q,q,q,s,s);var permissions=permissions(c);return new FacilityDirectory(c.organizationId(),permissions.contains(FacilityService.MANAGE),permissions.contains(FacilityService.LIFECYCLE),types,rows,Objects.requireNonNull(jdbc.queryForObject("SELECT clock_timestamp()",Timestamp.class)).toInstant());}
 public Result create(AuthorizedTenantContext c,Draft d){AuthorizedTenantTransactionGuard.requireBound(jdbc,c);var id=jdbc.queryForObject("""
 INSERT INTO facilities(organization_id,name,code,legal_name,facility_type,address_id,contact_id,timezone,status,created_by,updated_by)
 VALUES(?,?,?,?,?,?,?,?, 'draft',?,?) RETURNING id
 """,UUID.class,c.organizationId(),d.displayName(),d.facilityCode(),d.legalName(),d.facilityType(),d.addressId(),d.contactId(),d.timezone(),c.actorId(),c.actorId());return new Result(directory(c,null,null),id,0);}
 public Result update(AuthorizedTenantContext c,UUID id,long revision,Draft d){AuthorizedTenantTransactionGuard.requireBound(jdbc,c);var changed=jdbc.update("""
 UPDATE facilities SET name=?,code=?,legal_name=?,facility_type=?,address_id=?,contact_id=?,timezone=?,lock_version=lock_version+1,updated_at=clock_timestamp(),updated_by=?
 WHERE organization_id=? AND id=? AND status='draft' AND lock_version=?
 """,d.displayName(),d.facilityCode(),d.legalName(),d.facilityType(),d.addressId(),d.contactId(),d.timezone(),c.actorId(),c.organizationId(),id,revision);if(changed!=1)throw new FacilityException(FacilityException.Reason.STALE,"Facility changed or is no longer an editable draft.");return new Result(directory(c,null,null),id,revision+1);}
 public Result submit(AuthorizedTenantContext c,UUID id,long revision){AuthorizedTenantTransactionGuard.requireBound(jdbc,c);var eligible=Boolean.TRUE.equals(jdbc.queryForObject("""
 SELECT EXISTS(SELECT 1 FROM facilities f JOIN organizations o ON o.id=f.organization_id
   JOIN organization_addresses a ON a.organization_id=f.organization_id AND a.id=f.address_id
  WHERE f.organization_id=? AND f.id=? AND f.status='draft' AND f.lock_version=?
    AND a.validation_status='validated' AND a.status='active'
    AND a.effective_from<=clock_timestamp() AND (a.effective_to IS NULL OR a.effective_to>clock_timestamp())
    AND COALESCE(f.timezone,o.timezone) IS NOT NULL)
 """,Boolean.class,c.organizationId(),id,revision));if(!eligible)throw new FacilityException(FacilityException.Reason.CONFLICT,"Facility submission requires a validated active address and an effective timezone.");var changed=jdbc.update("""
 UPDATE facilities SET status='under_review',lock_version=lock_version+1,updated_at=clock_timestamp(),updated_by=?
 WHERE organization_id=? AND id=? AND status='draft' AND lock_version=?
 """,c.actorId(),c.organizationId(),id,revision);if(changed!=1)throw new FacilityException(FacilityException.Reason.STALE,"Facility changed or is no longer a draft.");return new Result(directory(c,null,null),id,revision+1);}
 public Result transition(AuthorizedTenantContext c,UUID id,long revision,String from,String to,String closureReason){AuthorizedTenantTransactionGuard.requireBound(jdbc,c);var changed=jdbc.update("UPDATE facilities SET status=?,closure_reason=?,closed_at=CASE WHEN ?='closed' THEN clock_timestamp() ELSE NULL END,lock_version=lock_version+1,updated_at=clock_timestamp(),updated_by=? WHERE organization_id=? AND id=? AND status=? AND lock_version=?",to,closureReason,to,c.actorId(),c.organizationId(),id,from,revision);if(changed!=1)throw new FacilityException(FacilityException.Reason.STALE,"Facility changed or the lifecycle transition is no longer available.");return new Result(directory(c,null,null),id,revision+1);}
 private Set<String> permissions(AuthorizedTenantContext c){return Set.copyOf(jdbc.queryForList("""
 SELECT DISTINCT p.permission_key FROM organization_memberships m JOIN authorization_roles r ON r.role_key=m.role_key JOIN authorization_role_permissions rp ON rp.role_key=r.role_key JOIN authorization_permissions p ON p.permission_key=rp.permission_key AND p.registry_version=r.registry_version WHERE m.organization_id=? AND m.user_id=? AND m.status='active' AND r.registry_version='m1-candidate-1' AND r.status='active' AND p.status='active'
 """,String.class,c.organizationId(),c.actorId()));}
}
