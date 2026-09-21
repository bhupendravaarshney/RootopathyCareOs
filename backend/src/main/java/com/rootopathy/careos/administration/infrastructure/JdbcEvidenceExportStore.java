package com.rootopathy.careos.administration.infrastructure;

import com.rootopathy.careos.administration.application.EvidenceExportStore;
import com.rootopathy.careos.administration.domain.EvidenceExportDirectory;
import com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Repository
public class JdbcEvidenceExportStore implements EvidenceExportStore {
  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;

  public JdbcEvidenceExportStore(JdbcTemplate jdbc, ObjectMapper mapper) {
    this.jdbc = jdbc;
    this.mapper = mapper;
  }

  @Override
  public EvidenceExportDirectory directory(AuthorizedTenantContext context, boolean audit) {
    var permissions = Set.copyOf(jdbc.queryForList(
        "SELECT DISTINCT rp.permission_key FROM organization_memberships m JOIN authorization_role_permissions rp ON rp.role_key=m.role_key WHERE m.organization_id=? AND m.user_id=? AND m.status='active' AND m.effective_from<=clock_timestamp() AND (m.effective_to IS NULL OR m.effective_to>clock_timestamp())",
        String.class,
        context.organizationId(),
        context.actorId()));
    var canApprove = permissions.contains("evidence.export.approve");
    var canAccess = permissions.contains("evidence.export.access");
    var evaluatedAt = now();
    var jobs = jdbc.query(
        "SELECT id,requester_id,projection,format,purpose_code,status,approval_id,approver_id,row_count,byte_count,artifact_digest,ready_at,expires_at,failure_code,lock_version,created_at,updated_at FROM evidence_export_jobs WHERE organization_id=? AND projection LIKE ? AND (requester_id=? OR ?) ORDER BY created_at DESC,id DESC LIMIT 100",
        (row, number) -> {
          var requesterId = row.getObject(2, UUID.class);
          var status = row.getString(6);
          var expiresAt = instant(row.getTimestamp(13));
          return new EvidenceExportDirectory.Job(
            row.getObject(1, UUID.class),
            requesterId,
            row.getString(3),
            row.getString(4),
            row.getString(5),
            status,
            canApprove && "requested".equals(status) && !context.actorId().equals(requesterId),
            canAccess && "ready".equals(status) && context.actorId().equals(requesterId)
                && expiresAt != null && expiresAt.isAfter(evaluatedAt),
            row.getObject(7, UUID.class),
            row.getObject(8, UUID.class),
            (Integer) row.getObject(9),
            (Long) row.getObject(10),
            row.getString(11),
            instant(row.getTimestamp(12)),
            expiresAt,
            row.getString(14),
            row.getLong(15),
            row.getTimestamp(16).toInstant(),
            row.getTimestamp(17).toInstant());
        },
        context.organizationId(),
        audit ? "audit-%" : "history-%",
        context.actorId(),
        canApprove);
    return new EvidenceExportDirectory(
        context.organizationId(),
        permissions.contains("evidence.export.request"),
        canApprove,
        canAccess,
        jobs,
        evaluatedAt);
  }

  @Override
  public Result request(AuthorizedTenantContext context, Draft draft) {
    var filters = filters(draft.filtersJson());
    var maximumRows = draft.restricted() ? 25_000 : 100_000;
    var source = draft.projection().startsWith("history-")
        ? historySnapshotSource(draft.projection(), filters, maximumRows)
        : auditSnapshotSource(draft.projection(), filters, maximumRows);
    var sql = """
        WITH requested_export AS (
          INSERT INTO evidence_export_jobs(
            organization_id,requester_id,projection,format,filter_json,filter_digest,
            purpose_code,legal_basis_key,reason,correlation_id,status,snapshot_time)
          VALUES(?,?,?,?,?::jsonb,?,?,?,?,?,?,statement_timestamp())
          RETURNING id,organization_id
        ), projected_rows AS (
          SELECT requested_export.organization_id,
                 requested_export.id AS export_id,
                 row_number() OVER (
                   ORDER BY source._sort_time DESC,source._sort_id DESC)::integer AS ordinal,
                 to_jsonb(source)-'_sort_time'-'_sort_id' AS row_data
          FROM requested_export
          CROSS JOIN LATERAL (
        """ + source.sql() + """
          ) source
        ), inserted_rows AS (
          INSERT INTO evidence_export_snapshot_rows(organization_id,export_id,ordinal,row_data)
          SELECT organization_id,export_id,ordinal,row_data FROM projected_rows
          RETURNING export_id
        )
        SELECT requested_export.id
        FROM requested_export
        CROSS JOIN (SELECT count(*) FROM inserted_rows) materialized
        """;
    var parameters = new ArrayList<Object>();
    Collections.addAll(
        parameters,
        context.organizationId(),
        context.actorId(),
        draft.projection(),
        draft.format(),
        draft.filtersJson(),
        draft.filterDigest(),
        draft.purposeCode(),
        draft.legalBasisKey(),
        draft.reason(),
        context.correlationId(),
        draft.restricted() ? "requested" : "authorized");
    parameters.addAll(source.parameters());
    var id = Objects.requireNonNull(jdbc.queryForObject(sql, UUID.class, parameters.toArray()));
    return new Result(
        id,
        draft.projection(),
        draft.format(),
        draft.filterDigest(),
        draft.purposeCode(),
        null,
        draft.restricted() ? "requested" : "authorized",
        0,
        directory(context, draft.projection().startsWith("audit-")));
  }

  @Override
  public Result decide(
      AuthorizedTenantContext context,
      UUID id,
      long revision,
      boolean authorize,
      String reason) {
    var approval = Objects.requireNonNull(jdbc.queryForObject("SELECT uuidv7()", UUID.class));
    var status = authorize ? "authorized" : "denied";
    var result = jdbc.query(
        "UPDATE evidence_export_jobs SET status=?,approval_id=?,approver_id=?,decision_reason=?,lock_version=lock_version+1,updated_at=clock_timestamp() WHERE organization_id=? AND id=? AND status='requested' AND requester_id<>? AND lock_version=? RETURNING projection,format,filter_digest,purpose_code",
        row -> row.next()
            ? new String[] {row.getString(1), row.getString(2), row.getString(3), row.getString(4)}
            : null,
        status,
        approval,
        context.actorId(),
        reason,
        context.organizationId(),
        id,
        context.actorId(),
        revision);
    if (result == null) throw new IllegalArgumentException("stale or ineligible export request");
    if (!authorize) {
      jdbc.update(
          "DELETE FROM evidence_export_snapshot_rows WHERE organization_id=? AND export_id=?",
          context.organizationId(),
          id);
    }
    return new Result(
        id, result[0], result[1], result[2], result[3], approval, status, revision + 1,
        directory(context, result[0].startsWith("audit-")));
  }

  @Override
  public Work claim(AuthorizedTenantContext context, UUID id, String workerId) {
    jdbc.update(
        "UPDATE evidence_export_jobs SET status='authorized',failure_code='evidence.export.lease_expired',next_attempt_at=clock_timestamp(),worker_id=NULL,lease_expires_at=NULL,lock_version=lock_version+1,updated_at=clock_timestamp() WHERE organization_id=? AND id=? AND status='running' AND lease_expires_at<=clock_timestamp() AND attempt_count<5",
        context.organizationId(),
        id);
    var work = jdbc.query(
        "UPDATE evidence_export_jobs SET status='running',attempt_count=attempt_count+1,worker_id=?,lease_expires_at=clock_timestamp()+interval '5 minutes',next_attempt_at=NULL,failure_code=NULL,lock_version=lock_version+1,updated_at=clock_timestamp() WHERE organization_id=? AND id=? AND status='authorized' AND (next_attempt_at IS NULL OR next_attempt_at<=clock_timestamp()) AND attempt_count<5 RETURNING id,requester_id,projection,format,filter_json::text,filter_digest,purpose_code,status,snapshot_time,attempt_count,lock_version,artifact_reference,artifact_digest,artifact_content_type,artifact_filename,row_count,byte_count,expires_at,legal_hold",
        row -> row.next() ? work(row) : null,
        workerId,
        context.organizationId(),
        id);
    if (work == null) throw new IllegalArgumentException("export is not available for generation");
    return work;
  }

  @Override
  public List<Map<String, Object>> rows(
      AuthorizedTenantContext context, Work work, int maximumRows) {
    return jdbc.query(
        "SELECT row_data::text FROM evidence_export_snapshot_rows WHERE organization_id=? AND export_id=? ORDER BY ordinal LIMIT ?",
        (row, number) -> snapshotRow(row.getString(1)),
        context.organizationId(),
        work.exportId(),
        maximumRows + 1);
  }

  private SnapshotSource historySnapshotSource(
      String projection, Map<String, Object> filters, int maximumRows) {
    var detail = projection.equals("history-detail-v1");
    var sql = new StringBuilder("SELECT v.id AS configuration_id,v.display_number,v.parent_version_id,v.status,v.change_summary,v.maker_id,a.checker_id,run.activator_id,v.requested_effective_at,v.activated_at,v.superseded_at,r.result_digest,v.correlation_id,coalesce(v.activated_at,v.updated_at) AS _sort_time,v.id AS _sort_id");
    if (detail) sql.append(",v.baseline_digest,v.parent_digest,v.reason,(SELECT coalesce(jsonb_agg(jsonb_build_object('subjectType',i.subject_type,'subjectId',i.subject_id,'baselineRevision',i.baseline_revision,'newRevision',i.new_revision,'changeType',i.change_type) ORDER BY i.subject_type,i.subject_id),'[]') FROM configuration_change_items i WHERE i.organization_id=v.organization_id AND i.configuration_id=v.id) AS change_items");
    sql.append(" FROM configuration_versions v LEFT JOIN LATERAL(SELECT checker_id FROM configuration_approvals x WHERE x.organization_id=v.organization_id AND x.configuration_id=v.id ORDER BY decided_at DESC LIMIT 1)a ON true LEFT JOIN LATERAL(SELECT result_digest FROM configuration_validation_results x WHERE x.organization_id=v.organization_id AND x.configuration_id=v.id ORDER BY evaluated_at DESC LIMIT 1)r ON true LEFT JOIN LATERAL(SELECT activator_id FROM configuration_activation_runs x WHERE x.organization_id=v.organization_id AND x.configuration_id=v.id AND x.status='activated' ORDER BY completed_at DESC LIMIT 1)run ON true WHERE v.organization_id=requested_export.organization_id");
    var params = new ArrayList<Object>();
    appendCommonHistoryFilters(sql, params, filters);
    sql.append(" ORDER BY coalesce(v.activated_at,v.updated_at) DESC,v.id DESC LIMIT ?");
    params.add(maximumRows + 1);
    return new SnapshotSource(sql.toString(), params);
  }

  private SnapshotSource auditSnapshotSource(
      String projection, Map<String, Object> filters, int maximumRows) {
    var detail = projection.equals("audit-detail-v1");
    var sql = new StringBuilder("""
      SELECT e.id,e.occurred_at,e.actor_user_id,e.operation_key,e.event_name,e.schema_version,
             e.subject_type,e.subject_id,e.correlation_id,
             CASE WHEN e.event_name~'(failed|denied|rejected)$' THEN 'failure' ELSE 'success' END AS outcome,
             CASE p.risk_class WHEN 'critical' THEN 'restricted' WHEN 'high' THEN 'high' ELSE 'standard' END AS risk,
             (p.risk_class='critical') AS redacted,
             e.occurred_at AS _sort_time,e.id AS _sort_id
      """);
    if (detail) sql.append(",e.reason,e.payload");
    sql.append("""
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
       WHERE e.organization_id=requested_export.organization_id
      """);
    var params = new ArrayList<Object>();
    instantFilter(sql, params, filters, "from", "e.occurred_at>=?");
    instantFilter(sql, params, filters, "to", "e.occurred_at<?");
    uuidFilter(sql, params, filters, "actorId", "e.actor_user_id=?", false);
    stringFilter(sql, params, filters, "operation", "e.operation_key=?");
    stringFilter(sql, params, filters, "eventName", "e.event_name=?");
    if (filters.containsKey("schemaVersion")) {
      sql.append(" AND e.schema_version=?");
      params.add(((Number) filters.get("schemaVersion")).intValue());
    }
    stringFilter(sql, params, filters, "subjectType", "e.subject_type=?");
    uuidFilter(sql, params, filters, "subjectId", "e.subject_id=?", false);
    stringFilter(sql, params, filters, "correlationId", "e.correlation_id=?");
    if (filters.containsKey("outcome")) {
      sql.append(" AND CASE WHEN e.event_name~'(failed|denied|rejected)$' THEN 'failure' ELSE 'success' END=?");
      params.add(filters.get("outcome"));
    }
    if (filters.containsKey("risk")) {
      sql.append(" AND CASE p.risk_class WHEN 'critical' THEN 'restricted' WHEN 'high' THEN 'high' ELSE 'standard' END=?");
      params.add(filters.get("risk"));
    }
    sql.append(" ORDER BY e.occurred_at DESC,e.id DESC LIMIT ?");
    params.add(maximumRows + 1);
    return new SnapshotSource(sql.toString(), params);
  }

  private Map<String, Object> snapshotRow(String json) {
    try {
      return mapper.readValue(json, new TypeReference<>() {});
    } catch (Exception exception) {
      throw new IllegalStateException("stored export snapshot row is invalid", exception);
    }
  }

  private void appendCommonHistoryFilters(
      StringBuilder sql, List<Object> params, Map<String, Object> filters) {
    instantFilter(sql, params, filters, "from", "coalesce(v.activated_at,v.updated_at)>=?");
    instantFilter(sql, params, filters, "to", "coalesce(v.activated_at,v.updated_at)<?");
    stringFilter(sql, params, filters, "status", "v.status=?");
    if (filters.containsKey("actorId")) {
      sql.append(" AND (v.maker_id=? OR a.checker_id=? OR run.activator_id=?)");
      var actor = UUID.fromString(filters.get("actorId").toString());
      params.add(actor); params.add(actor); params.add(actor);
    }
    stringFilter(sql, params, filters, "correlationId", "v.correlation_id=?");
    if (filters.containsKey("subjectType") || filters.containsKey("subjectId") || filters.containsKey("changeType")) {
      sql.append(" AND EXISTS(SELECT 1 FROM configuration_change_items i WHERE i.organization_id=v.organization_id AND i.configuration_id=v.id");
      stringFilter(sql, params, filters, "subjectType", "i.subject_type=?");
      uuidFilter(sql, params, filters, "subjectId", "i.subject_id=?", false);
      stringFilter(sql, params, filters, "changeType", "i.change_type=?");
      sql.append(')');
    }
  }

  @Override
  public Work ready(
      AuthorizedTenantContext context,
      Work work,
      String reference,
      String digest,
      String contentType,
      String filename,
      int rowCount,
      long byteCount) {
    var result = jdbc.query(
        "UPDATE evidence_export_jobs SET status='ready',artifact_reference=?,artifact_digest=?,artifact_content_type=?,artifact_filename=?,row_count=?,byte_count=?,ready_at=clock_timestamp(),expires_at=clock_timestamp()+interval '24 hours',worker_id=NULL,lease_expires_at=NULL,lock_version=lock_version+1,updated_at=clock_timestamp() WHERE organization_id=? AND id=? AND status='running' AND lock_version=? RETURNING id,requester_id,projection,format,filter_json::text,filter_digest,purpose_code,status,snapshot_time,attempt_count,lock_version,artifact_reference,artifact_digest,artifact_content_type,artifact_filename,row_count,byte_count,expires_at,legal_hold",
        row -> row.next() ? work(row) : null,
        reference, digest, contentType, filename, rowCount, byteCount,
        context.organizationId(), work.exportId(), work.lockVersion());
    if (result == null) throw new IllegalArgumentException("export generation lease was lost");
    jdbc.update(
        "DELETE FROM evidence_export_snapshot_rows WHERE organization_id=? AND export_id=?",
        context.organizationId(),
        work.exportId());
    return result;
  }

  @Override
  public Work generationFailed(
      AuthorizedTenantContext context, Work work, String code, boolean retryable) {
    var retry = retryable && work.attemptCount() < 5;
    var result = jdbc.query(
        "UPDATE evidence_export_jobs SET status=?,failure_code=?,next_attempt_at=CASE WHEN ? THEN clock_timestamp()+(power(2,attempt_count)::text||' minutes')::interval ELSE NULL END,dead_lettered_at=CASE WHEN ? THEN NULL ELSE clock_timestamp() END,worker_id=NULL,lease_expires_at=NULL,lock_version=lock_version+1,updated_at=clock_timestamp() WHERE organization_id=? AND id=? AND status='running' AND lock_version=? RETURNING id,requester_id,projection,format,filter_json::text,filter_digest,purpose_code,status,snapshot_time,attempt_count,lock_version,artifact_reference,artifact_digest,artifact_content_type,artifact_filename,row_count,byte_count,expires_at,legal_hold",
        row -> row.next() ? work(row) : null,
        retry ? "authorized" : "failed", code, retry, retry,
        context.organizationId(), work.exportId(), work.lockVersion());
    if (result == null) throw new IllegalArgumentException("export generation lease was lost");
    if (!retry) {
      jdbc.update(
          "DELETE FROM evidence_export_snapshot_rows WHERE organization_id=? AND export_id=?",
          context.organizationId(),
          work.exportId());
    }
    return result;
  }

  @Override
  public Access access(
      AuthorizedTenantContext context, UUID id, long revision, String purposeCode) {
    var access = jdbc.query(
        "SELECT id,artifact_reference,artifact_digest,artifact_content_type,artifact_filename,expires_at,lock_version FROM evidence_export_jobs WHERE organization_id=? AND id=? AND requester_id=? AND status='ready' AND expires_at>clock_timestamp() AND lock_version=? FOR UPDATE",
        row -> row.next()
            ? new Access(row.getObject(1,UUID.class),row.getString(2),row.getString(3),row.getString(4),row.getString(5),row.getTimestamp(6).toInstant(),row.getLong(7))
            : null,
        context.organizationId(), id, context.actorId(), revision);
    if (access == null) throw new IllegalArgumentException("ready export is unavailable");
    jdbc.update(
        "INSERT INTO evidence_export_accesses(organization_id,export_id,actor_id,purpose_code,artifact_digest,correlation_id) VALUES(?,?,?,?,?,?)",
        context.organizationId(), id, context.actorId(), purposeCode, access.artifactDigest(), context.correlationId());
    return access;
  }

  @Override
  public Work accessForDisposal(AuthorizedTenantContext context, UUID id, long revision) {
    var result = jdbc.query(
        "SELECT id,requester_id,projection,format,filter_json::text,filter_digest,purpose_code,status,snapshot_time,attempt_count,lock_version,artifact_reference,artifact_digest,artifact_content_type,artifact_filename,row_count,byte_count,expires_at,legal_hold FROM evidence_export_jobs WHERE organization_id=? AND id=? AND status='expired' AND lock_version=? FOR UPDATE",
        row -> row.next() ? work(row) : null,
        context.organizationId(), id, revision);
    if (result == null) throw new IllegalArgumentException("expired export is unavailable");
    return result;
  }

  @Override
  public Work expire(AuthorizedTenantContext context, UUID id, long revision) {
    return transition(context, id, revision,
        "UPDATE evidence_export_jobs SET status='expired',lock_version=lock_version+1,updated_at=clock_timestamp() WHERE organization_id=? AND id=? AND status='ready' AND expires_at<=clock_timestamp() AND lock_version=? RETURNING id,requester_id,projection,format,filter_json::text,filter_digest,purpose_code,status,snapshot_time,attempt_count,lock_version,artifact_reference,artifact_digest,artifact_content_type,artifact_filename,row_count,byte_count,expires_at,legal_hold");
  }

  @Override
  public Work dispose(AuthorizedTenantContext context, UUID id, long revision) {
    return transition(context, id, revision,
        "UPDATE evidence_export_jobs SET status='disposed',disposed_at=clock_timestamp(),lock_version=lock_version+1,updated_at=clock_timestamp() WHERE organization_id=? AND id=? AND status='expired' AND NOT legal_hold AND lock_version=? RETURNING id,requester_id,projection,format,filter_json::text,filter_digest,purpose_code,status,snapshot_time,attempt_count,lock_version,artifact_reference,artifact_digest,artifact_content_type,artifact_filename,row_count,byte_count,expires_at,legal_hold");
  }

  private Work transition(AuthorizedTenantContext context, UUID id, long revision, String sql) {
    var result = jdbc.query(sql, row -> row.next() ? work(row) : null,
        context.organizationId(), id, revision);
    if (result == null) throw new IllegalArgumentException("export lifecycle transition is unavailable");
    return result;
  }

  private Work work(java.sql.ResultSet row) throws java.sql.SQLException {
    return new Work(
        row.getObject(1, UUID.class), row.getObject(2, UUID.class), row.getString(3),
        row.getString(4), row.getString(5), row.getString(6), row.getString(7), row.getString(8),
        row.getTimestamp(9).toInstant(), row.getInt(10), row.getLong(11), row.getString(12),
        row.getString(13), row.getString(14), row.getString(15), (Integer) row.getObject(16),
        (Long) row.getObject(17), instant(row.getTimestamp(18)), row.getBoolean(19));
  }

  private Map<String, Object> filters(String json) {
    try { return mapper.readValue(json, new TypeReference<>() {}); }
    catch (Exception exception) { throw new IllegalStateException("stored export filter is invalid", exception); }
  }

  private static void stringFilter(StringBuilder sql,List<Object> params,Map<String,Object> filters,String key,String expression){if(filters.containsKey(key)){sql.append(" AND ").append(expression);params.add(filters.get(key));}}
  private static void instantFilter(StringBuilder sql,List<Object> params,Map<String,Object> filters,String key,String expression){if(filters.containsKey(key)){sql.append(" AND ").append(expression);params.add(Timestamp.from(Instant.parse(filters.get(key).toString())));}}
  private static void uuidFilter(StringBuilder sql,List<Object> params,Map<String,Object> filters,String key,String expression,boolean twice){if(filters.containsKey(key)){sql.append(" AND ").append(expression);var id=UUID.fromString(filters.get(key).toString());params.add(id);if(twice)params.add(id);}}
  private Instant now(){return Objects.requireNonNull(jdbc.queryForObject("SELECT clock_timestamp()",Timestamp.class)).toInstant();}
  private static Instant instant(Timestamp value){return value==null?null:value.toInstant();}
  private record SnapshotSource(String sql, List<Object> parameters) {}
}
