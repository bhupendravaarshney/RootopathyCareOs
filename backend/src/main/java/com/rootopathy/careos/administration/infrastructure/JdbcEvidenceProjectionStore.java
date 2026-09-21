package com.rootopathy.careos.administration.infrastructure;

import com.rootopathy.careos.administration.application.*;
import com.rootopathy.careos.administration.domain.*;
import com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext;
import java.sql.Timestamp;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

@Repository
public class JdbcEvidenceProjectionStore implements EvidenceProjectionStore {
  private final JdbcTemplate jdbc; private final ObjectMapper mapper;
  public JdbcEvidenceProjectionStore(JdbcTemplate jdbc,ObjectMapper mapper){this.jdbc=jdbc;this.mapper=mapper;}

  public List<ConfigurationHistoryPage.Item> history(AuthorizedTenantContext c,HistoryQuery q){
    var sql=new StringBuilder("SELECT v.id,v.display_number,v.parent_version_id,v.status,v.change_summary,v.reason,v.maker_id,a.checker_id,run.activator_id,v.requested_effective_at,v.activated_at,v.superseded_at,r.result_digest,r.gate_catalogue_version,a.policy_version,v.correlation_id,v.lock_version,coalesce(v.activated_at,v.updated_at) occurred,(SELECT coalesce(jsonb_agg(jsonb_build_object('subjectType',i.subject_type,'subjectId',i.subject_id,'baselineRevision',i.baseline_revision,'newRevision',i.new_revision,'changeType',i.change_type) ORDER BY i.subject_type,i.subject_id),'[]')::text FROM configuration_change_items i WHERE i.organization_id=v.organization_id AND i.configuration_id=v.id) changes FROM configuration_versions v LEFT JOIN LATERAL(SELECT checker_id,policy_version FROM configuration_approvals x WHERE x.organization_id=v.organization_id AND x.configuration_id=v.id ORDER BY decided_at DESC LIMIT 1)a ON true LEFT JOIN LATERAL(SELECT result_digest,gate_catalogue_version FROM configuration_validation_results x WHERE x.organization_id=v.organization_id AND x.configuration_id=v.id ORDER BY evaluated_at DESC LIMIT 1)r ON true LEFT JOIN LATERAL(SELECT activator_id FROM configuration_activation_runs x WHERE x.organization_id=v.organization_id AND x.configuration_id=v.id AND x.status='activated' ORDER BY completed_at DESC LIMIT 1)run ON true WHERE v.organization_id=? AND coalesce(v.activated_at,v.updated_at)<=?");
    var p=new ArrayList<Object>();p.add(c.organizationId());p.add(Timestamp.from(q.asOf()));
    if(q.from()!=null){sql.append(" AND coalesce(v.activated_at,v.updated_at)>=?");p.add(Timestamp.from(q.from()));}
    if(q.to()!=null){sql.append(" AND coalesce(v.activated_at,v.updated_at)<?");p.add(Timestamp.from(q.to()));}
    if(q.status()!=null){sql.append(" AND v.status=?");p.add(q.status());}
    if(q.actorId()!=null){sql.append(" AND (v.maker_id=? OR a.checker_id=? OR run.activator_id=?)");p.add(q.actorId());p.add(q.actorId());p.add(q.actorId());}
    if(q.correlationId()!=null){sql.append(" AND v.correlation_id=?");p.add(q.correlationId());}
    if(q.subjectType()!=null||q.subjectId()!=null||q.changeType()!=null){sql.append(" AND EXISTS(SELECT 1 FROM configuration_change_items i WHERE i.organization_id=v.organization_id AND i.configuration_id=v.id");if(q.subjectType()!=null){sql.append(" AND i.subject_type=?");p.add(q.subjectType());}if(q.subjectId()!=null){sql.append(" AND i.subject_id=?");p.add(q.subjectId());}if(q.changeType()!=null){sql.append(" AND i.change_type=?");p.add(q.changeType());}sql.append(")");}
    if(q.after()!=null){sql.append(" AND (coalesce(v.activated_at,v.updated_at),v.id)<(?,?)");p.add(Timestamp.from(q.after().occurredAt()));p.add(q.after().id());}
    sql.append(" ORDER BY occurred DESC,v.id DESC LIMIT ?");p.add(q.limit()+1);
    return jdbc.query(sql.toString(),(r,n)->new ConfigurationHistoryPage.Item(r.getObject(1,UUID.class),r.getString(2),r.getObject(3,UUID.class),r.getString(4),r.getString(5),r.getString(6),r.getObject(7,UUID.class),r.getObject(8,UUID.class),r.getObject(9,UUID.class),r.getTimestamp(10).toInstant(),instant(r.getTimestamp(11)),instant(r.getTimestamp(12)),r.getString(13),r.getString(14),r.getString(15),r.getString(16),r.getLong(17),r.getTimestamp(18).toInstant(),changes(r.getString(19))),p.toArray());
  }

  public List<AuditEvidencePage.Item> audit(AuthorizedTenantContext c,AuditQuery q){
    var sql=new StringBuilder("""
      SELECT e.id,e.occurred_at,e.actor_user_id,e.operation_key,e.event_name,e.schema_version,
             e.subject_type,e.subject_id,e.correlation_id,
             CASE WHEN e.event_name~'(failed|denied|rejected)$' THEN 'failure' ELSE 'success' END AS outcome,
             CASE p.risk_class WHEN 'critical' THEN 'restricted' WHEN 'high' THEN 'high' ELSE 'standard' END AS risk
      FROM audit_events e
      JOIN audit_event_definitions d ON d.event_name=e.event_name AND d.schema_version=e.schema_version
       AND d.status='active' AND d.registry_version='m1-candidate-1'
      JOIN authorization_operation_events oe ON oe.operation_key=e.operation_key AND oe.event_kind='audit'
       AND oe.event_name=e.event_name AND oe.schema_version=e.schema_version
       AND oe.status='active' AND oe.registry_version='m1-candidate-1'
      JOIN authorization_operations o ON o.operation_key=e.operation_key
       AND o.status='active' AND o.registry_version='m1-candidate-1'
      JOIN authorization_permissions p ON p.permission_key=o.permission_key
       AND p.status='active' AND p.registry_version='m1-candidate-1'
      WHERE e.organization_id=? AND e.occurred_at<=?
      """);
    var p=new ArrayList<Object>();p.add(c.organizationId());p.add(Timestamp.from(q.asOf()));
    if(q.from()!=null){sql.append(" AND e.occurred_at>=?");p.add(Timestamp.from(q.from()));}if(q.to()!=null){sql.append(" AND e.occurred_at<?");p.add(Timestamp.from(q.to()));}if(q.actorId()!=null){sql.append(" AND e.actor_user_id=?");p.add(q.actorId());}if(q.operation()!=null){sql.append(" AND e.operation_key=?");p.add(q.operation());}if(q.eventName()!=null){sql.append(" AND e.event_name=?");p.add(q.eventName());}if(q.schemaVersion()!=null){sql.append(" AND e.schema_version=?");p.add(q.schemaVersion());}if(q.subjectType()!=null){sql.append(" AND e.subject_type=?");p.add(q.subjectType());}if(q.subjectId()!=null){sql.append(" AND e.subject_id=?");p.add(q.subjectId());}if(q.correlationId()!=null){sql.append(" AND e.correlation_id=?");p.add(q.correlationId());}
    if(q.outcome()!=null){sql.append(" AND CASE WHEN e.event_name~'(failed|denied|rejected)$' THEN 'failure' ELSE 'success' END=?");p.add(q.outcome());}
    if(q.risk()!=null){sql.append(" AND CASE p.risk_class WHEN 'critical' THEN 'restricted' WHEN 'high' THEN 'high' ELSE 'standard' END=?");p.add(q.risk());}
    if(q.after()!=null){sql.append(" AND (e.occurred_at,e.id)<(?,?)");p.add(Timestamp.from(q.after().occurredAt()));p.add(q.after().id());}sql.append(" ORDER BY e.occurred_at DESC,e.id DESC LIMIT ?");p.add(q.limit()+1);
    return jdbc.query(sql.toString(),(r,n)->{var risk=r.getString(11);return new AuditEvidencePage.Item(r.getObject(1,UUID.class),r.getTimestamp(2).toInstant(),r.getObject(3,UUID.class),r.getString(4),r.getString(5),r.getInt(6),r.getString(7),r.getObject(8,UUID.class),r.getString(10),risk,r.getString(9),"restricted".equals(risk));},p.toArray());
  }

  public AuditEvidenceDetail detail(AuthorizedTenantContext c,UUID eventId,String purposeCode){
    return jdbc.query("""
      SELECT e.id,e.occurred_at,e.actor_user_id,e.operation_key,e.event_name,e.schema_version,
             e.subject_type,e.subject_id,e.correlation_id,e.reason,e.payload::text,d.registry_version,
             CASE WHEN e.event_name~'(failed|denied|rejected)$' THEN 'failure' ELSE 'success' END,
             CASE p.risk_class WHEN 'critical' THEN 'restricted' WHEN 'high' THEN 'high' ELSE 'standard' END
      FROM audit_events e
      JOIN audit_event_definitions d ON d.event_name=e.event_name AND d.schema_version=e.schema_version
       AND d.status='active' AND d.registry_version='m1-candidate-1'
      JOIN authorization_operation_events oe ON oe.operation_key=e.operation_key AND oe.event_kind='audit'
       AND oe.event_name=e.event_name AND oe.schema_version=e.schema_version
       AND oe.status='active' AND oe.registry_version='m1-candidate-1'
      JOIN authorization_operations o ON o.operation_key=e.operation_key
       AND o.status='active' AND o.registry_version='m1-candidate-1'
      JOIN authorization_permissions p ON p.permission_key=o.permission_key
       AND p.status='active' AND p.registry_version='m1-candidate-1'
      WHERE e.organization_id=? AND e.id=?
      """,rs->{if(!rs.next())throw new NoSuchElementException("audit event is unavailable");var risk=rs.getString(14);var restricted="restricted".equals(risk);Map<String,Object> payload;try{payload=mapper.readValue(rs.getString(11),Map.class);}catch(Exception exception){throw new IllegalStateException("audit payload could not be projected",exception);}var occurred=rs.getTimestamp(2).toInstant();return new AuditEvidenceDetail(c.organizationId(),rs.getObject(1,UUID.class),occurred,occurred,rs.getObject(3,UUID.class),rs.getString(4),rs.getString(5),rs.getInt(6),rs.getString(7),rs.getObject(8,UUID.class),rs.getString(13),risk,rs.getString(9),purposeCode,restricted?"Restricted reason withheld":rs.getString(10),payload,restricted,rs.getString(12));},c.organizationId(),eventId);
  }

  private List<ConfigurationHistoryPage.Change> changes(String value){try{return mapper.readValue(value,mapper.getTypeFactory().constructCollectionType(List.class,ConfigurationHistoryPage.Change.class));}catch(Exception exception){throw new IllegalStateException("configuration change history is invalid",exception);}}
  private static java.time.Instant instant(Timestamp value){return value==null?null:value.toInstant();}
}
