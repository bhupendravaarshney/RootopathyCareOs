package com.rootopathy.careos.administration.infrastructure;

import com.rootopathy.careos.administration.application.ServiceAssignmentStore;
import com.rootopathy.careos.administration.domain.ServiceAssignmentDirectory;
import com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext;
import java.sql.Array;
import java.sql.Timestamp;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcServiceAssignmentStore implements ServiceAssignmentStore {
  private final JdbcTemplate jdbc;
  public JdbcServiceAssignmentStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

  @Override public ServiceAssignmentDirectory directory(AuthorizedTenantContext c) {
    var permissions = Set.copyOf(jdbc.queryForList("SELECT DISTINCT rp.permission_key FROM organization_memberships m JOIN authorization_role_permissions rp ON rp.role_key=m.role_key WHERE m.organization_id=? AND m.user_id=? AND m.status='active' AND m.effective_from<=clock_timestamp() AND (m.effective_to IS NULL OR m.effective_to>clock_timestamp())", String.class, c.organizationId(), c.actorId()));
    var rows = jdbc.query("SELECT id,service_id,facility_id,location_id,capacity,availability_notes,prerequisites,effective_from,effective_to,status,lock_version,created_at,updated_at FROM service_assignments WHERE organization_id=? ORDER BY effective_from,id", (r,n) -> {
      Array value = r.getArray(7);
      String[] prerequisites = value == null ? new String[0] : (String[]) value.getArray();
      var end = r.getTimestamp(9);
      return new ServiceAssignmentDirectory.Assignment(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getObject(3,UUID.class),r.getObject(4,UUID.class),(Integer)r.getObject(5),r.getString(6),List.of(prerequisites),r.getTimestamp(8).toInstant(),end==null?null:end.toInstant(),r.getString(10),r.getLong(11),r.getTimestamp(12).toInstant(),r.getTimestamp(13).toInstant());
    }, c.organizationId());
    return new ServiceAssignmentDirectory(c.organizationId(),permissions.contains("service.assignment.manage"),permissions.contains("service.assignment.lifecycle"),rows,Objects.requireNonNull(jdbc.queryForObject("SELECT clock_timestamp()",Timestamp.class)).toInstant());
  }

  @Override public Result create(AuthorizedTenantContext c, Draft d) {
    var id=jdbc.queryForObject("INSERT INTO service_assignments(organization_id,service_id,facility_id,location_id,capacity,availability_notes,prerequisites,effective_from,effective_to,created_by,updated_by) VALUES(?,?,?,?,?,?,?::varchar[],?,?,?,?) RETURNING id",UUID.class,c.organizationId(),d.serviceId(),d.facilityId(),d.locationId(),d.capacity(),d.availabilityNotes(),d.prerequisites().toArray(String[]::new),Timestamp.from(d.effectiveFrom()),d.effectiveTo()==null?null:Timestamp.from(d.effectiveTo()),c.actorId(),c.actorId());
    return new Result(id,d.serviceId(),d.locationId()==null?d.facilityId():d.locationId(),d.effectiveFrom(),"none","scheduled",0,directory(c));
  }
  @Override public Result update(AuthorizedTenantContext c, UUID id, long rev, Draft d) {
    var n=jdbc.update("UPDATE service_assignments SET capacity=?,availability_notes=?,prerequisites=?::varchar[],effective_from=?,effective_to=?,lock_version=lock_version+1,updated_at=clock_timestamp(),updated_by=? WHERE organization_id=? AND id=? AND service_id=? AND facility_id=? AND location_id IS NOT DISTINCT FROM ? AND status='scheduled' AND lock_version=?",d.capacity(),d.availabilityNotes(),d.prerequisites().toArray(String[]::new),Timestamp.from(d.effectiveFrom()),d.effectiveTo()==null?null:Timestamp.from(d.effectiveTo()),c.actorId(),c.organizationId(),id,d.serviceId(),d.facilityId(),d.locationId(),rev);
    if(n!=1) throw new IllegalArgumentException("stale or unavailable service assignment");
    return new Result(id,d.serviceId(),d.locationId()==null?d.facilityId():d.locationId(),d.effectiveFrom(),"scheduled","scheduled",rev+1,directory(c));
  }
  @Override public Result transition(AuthorizedTenantContext c, UUID id, long rev, String from, String to) {
    var row=jdbc.query("UPDATE service_assignments SET status=?,lock_version=lock_version+1,updated_at=clock_timestamp(),updated_by=? WHERE organization_id=? AND id=? AND status=? AND lock_version=? RETURNING service_id,COALESCE(location_id,facility_id),effective_from",r->r.next()?new Object[]{r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getTimestamp(3).toInstant()}:null,to,c.actorId(),c.organizationId(),id,from,rev);
    if(row==null) throw new IllegalArgumentException("stale or invalid assignment transition");
    return new Result(id,(UUID)row[0],(UUID)row[1],(java.time.Instant)row[2],from,to,rev+1,directory(c));
  }
}
