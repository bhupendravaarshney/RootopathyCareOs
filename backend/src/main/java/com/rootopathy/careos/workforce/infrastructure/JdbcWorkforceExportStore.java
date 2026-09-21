package com.rootopathy.careos.workforce.infrastructure;

import com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext;
import com.rootopathy.careos.workforce.application.WorkforceExportStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Repository
public final class JdbcWorkforceExportStore implements WorkforceExportStore {
    private static final String RETURNING = """
            RETURNING id,requester_id,projection,format,filters_json::text,filters_digest,
                      purpose_key,status,snapshot_at,row_limit,size_limit_bytes,attempt_count,
                      lock_version,artifact_opaque_id,artifact_digest,artifact_content_type,
                      artifact_filename,row_count,artifact_byte_count,expires_at,legal_hold
            """;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public JdbcWorkforceExportStore(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public Work claim(AuthorizedTenantContext context, UUID exportId, String workerId) {
        var result = jdbc.query(
                """
                UPDATE workforce_export_jobs
                   SET status='running',attempt_count=attempt_count+1,worker_id=?,
                       lease_expires_at=clock_timestamp()+interval '15 minutes',
                       snapshot_at=transaction_timestamp(),
                       next_attempt_at=NULL,failure_code=NULL,lock_version=lock_version+1,
                       updated_at=clock_timestamp(),updated_by=?
                 WHERE organization_id=? AND id=?
                   AND (status='authorized'
                     OR (status='running' AND lease_expires_at<=clock_timestamp()))
                   AND attempt_count<5
                   AND (next_attempt_at IS NULL OR next_attempt_at<=clock_timestamp())
                """ + RETURNING,
                row -> row.next() ? work(row) : null,
                workerId,
                context.actorId(),
                context.organizationId(),
                exportId);
        if (result == null) {
            throw new IllegalArgumentException("authorized workforce export is unavailable");
        }
        return result;
    }

    @Override
    public List<Map<String, Object>> rows(
            AuthorizedTenantContext context, Work work, int maximumRows) {
        requireRequesterSourcePermissions(context,work);
        var filters = filters(work.filtersJson());
        var memberId = optionalUuid(filters.get("memberId"));
        var limit = Math.min(work.rowLimit(), maximumRows) + 1;
        return switch (work.projection()) {
            case "workforce-directory-summary-v1" -> jdbc.query(
                    """
                    SELECT member.id AS "memberId",member.member_number AS "memberNumber",
                           link.display_label AS "displayName",member.pathway,
                           member.lifecycle_state AS status,
                           assignment.facility_id AS "primaryFacilityId",
                           member.activated_at AS "activatedAt"
                    FROM workforce_members member
                    JOIN organization_person_links link
                      ON link.organization_id=member.organization_id
                     AND link.id=member.organization_person_link_id
                    LEFT JOIN LATERAL(
                        SELECT facility_id FROM workforce_assignments candidate
                        WHERE candidate.organization_id=member.organization_id
                          AND candidate.workforce_member_id=member.id
                          AND candidate.primary_assignment
                          AND candidate.lifecycle_state IN ('scheduled','active')
                          AND candidate.effective_from<=?
                          AND (candidate.effective_to IS NULL OR candidate.effective_to>?)
                        ORDER BY candidate.effective_from DESC,candidate.id DESC LIMIT 1
                    ) assignment ON true
                    WHERE member.organization_id=? AND member.updated_at<=?
                    ORDER BY member.member_number,member.id LIMIT ?
                    """,
                    (row, number) -> orderedRow(row,
                            "memberId","memberNumber","displayName","pathway","status",
                            "primaryFacilityId","activatedAt"),
                    Timestamp.from(work.snapshotAt()),
                    Timestamp.from(work.snapshotAt()),
                    context.organizationId(),
                    Timestamp.from(work.snapshotAt()),
                    limit);
            case "credential-expiry-summary-v1" -> jdbc.query(
                    """
                    SELECT credential.id AS "credentialId",credential.workforce_member_id AS "memberId",
                           member.member_number AS "memberNumber",entry.code AS "credentialTypeCode",
                           credential.status,credential.expires_on AS "expiryDate",
                           CASE WHEN credential.expires_on<?::date THEN 'expired'
                                WHEN credential.expires_on<=?::date+7 THEN '0-7'
                                WHEN credential.expires_on<=?::date+30 THEN '8-30'
                                WHEN credential.expires_on<=?::date+60 THEN '31-60'
                                ELSE '61-90' END AS bucket
                    FROM practitioner_credentials credential
                    JOIN workforce_members member ON member.organization_id=credential.organization_id
                      AND member.id=credential.workforce_member_id
                    JOIN workforce_registry_entries entry ON entry.organization_id=credential.organization_id
                      AND entry.id=credential.credential_type_entry_id
                    WHERE credential.organization_id=? AND credential.expires_on IS NOT NULL
                      AND credential.expires_on<=?::date+90 AND credential.updated_at<=?
                    ORDER BY credential.expires_on,credential.id LIMIT ?
                    """,
                    (row, number) -> orderedRow(row,
                            "credentialId","memberId","memberNumber","credentialTypeCode",
                            "status","expiryDate","bucket"),
                    Timestamp.from(work.snapshotAt()),
                    Timestamp.from(work.snapshotAt()),
                    Timestamp.from(work.snapshotAt()),
                    Timestamp.from(work.snapshotAt()),
                    context.organizationId(),
                    Timestamp.from(work.snapshotAt()),
                    Timestamp.from(work.snapshotAt()),
                    limit);
            case "workforce-configuration-summary-v1" -> jdbc.query(
                    """
                    SELECT snapshot.id AS "snapshotId",snapshot.display_number AS "displayNumber",
                           snapshot.parent_snapshot_id AS "parentSnapshotId",
                           snapshot.snapshot_digest AS "snapshotDigest",snapshot.status,
                           snapshot.effective_at AS "effectiveAt",snapshot.superseded_at AS "supersededAt",
                           snapshot.maker_id AS "makerId",snapshot.checker_id AS "checkerId",
                           snapshot.activator_id AS "activatorId"
                    FROM workforce_configuration_snapshots snapshot
                    WHERE snapshot.organization_id=? AND snapshot.effective_at<=?
                    ORDER BY snapshot.effective_at DESC,snapshot.id DESC LIMIT ?
                    """,
                    (row, number) -> orderedRow(row,
                            "snapshotId","displayNumber","parentSnapshotId","snapshotDigest","status",
                            "effectiveAt","supersededAt","makerId","checkerId","activatorId"),
                    context.organizationId(),Timestamp.from(work.snapshotAt()),limit);
            case "workforce-audit-summary-v1" -> auditRows(context, work, false, memberId, limit);
            case "workforce-audit-detail-v1" -> auditRows(context, work, true, memberId, limit);
            case "member-timeline-summary-v1" -> memberTimelineRows(context, work, false, requiredMember(memberId), limit);
            case "member-evidence-detail-v1" -> memberTimelineRows(context, work, true, requiredMember(memberId), limit);
            case "credential-decision-detail-v1" -> credentialDecisionRows(context, work, requiredMember(memberId), limit);
            case "scope-decision-detail-v1" -> scopeDecisionRows(context, work, requiredMember(memberId), limit);
            default -> throw new IllegalArgumentException("unsupported workforce export projection");
        };
    }

    private List<Map<String, Object>> auditRows(
            AuthorizedTenantContext context,
            Work work,
            boolean detail,
            UUID memberId,
            int limit) {
        var sql = new StringBuilder("""
                SELECT event.id AS "eventId",event.occurred_at AS "occurredAt",
                       event.actor_user_id AS "actorId",event.actor_kind AS "actorKind",
                       event.operation_key AS operation,event.event_name AS "eventName",
                       event.schema_version AS "schemaVersion",event.subject_type AS "subjectType",
                       event.subject_id AS "subjectId",event.correlation_id AS "correlationId",
                       coalesce(event.payload->>'outcome',event.payload->>'decisionCode',
                                event.payload->>'toState',event.payload->>'state','recorded') AS outcome,
                       coalesce(permission.risk_class,'moderate') AS risk,
                       'minimum_necessary_v1'::text AS "redactionMarker"
                """);
        if (detail) sql.append("""
                ,(event.reason IS NOT NULL) AS "protectedReasonPresent",
                 coalesce(event.payload->>'verificationId',event.payload->>'decisionId',
                          event.payload->>'approvalId',event.payload->>'transitionId')
                     AS "decisionReferenceId",
                 coalesce(event.payload->>'evidenceId',event.payload->>'notificationId',
                          event.payload->>'resultId',event.payload->>'readinessRunId')
                     AS "evidenceReferenceId",
                 coalesce(event.payload->>'policyVersion',definition.registry_version)
                     AS "policyVersion",
                 coalesce(event.payload->>'resultDigest',event.payload->>'evidenceDigest',
                          event.payload->>'impactDigest',event.payload->>'filterDigest',
                          event.payload->>'artifactDigest') AS "evidenceDigest"
                """);
        sql.append("""
                FROM audit_events event
                JOIN audit_event_definitions definition
                  ON definition.event_name=event.event_name
                 AND definition.schema_version=event.schema_version
                 AND definition.status='active'
                LEFT JOIN authorization_operations operation
                  ON operation.operation_key=event.operation_key AND operation.status='active'
                LEFT JOIN authorization_permissions permission
                  ON permission.permission_key=operation.permission_key
                 AND permission.status='active'
                WHERE event.organization_id=? AND event.occurred_at<=?
                """);
        var parameters = new ArrayList<Object>();
        parameters.add(context.organizationId());
        parameters.add(Timestamp.from(work.snapshotAt()));
        if (memberId != null) {
            sql.append(" AND event.payload->>'memberId'=?");
            parameters.add(memberId.toString());
        }
        sql.append(" ORDER BY event.occurred_at DESC,event.id DESC LIMIT ?");
        parameters.add(limit);
        var keys = new ArrayList<>(List.of(
                "eventId","occurredAt","actorId","actorKind","operation","eventName",
                "schemaVersion","subjectType","subjectId","correlationId","outcome",
                "risk","redactionMarker"));
        if (detail) {
            keys.add("protectedReasonPresent");
            keys.add("decisionReferenceId");
            keys.add("evidenceReferenceId");
            keys.add("policyVersion");
            keys.add("evidenceDigest");
        }
        return jdbc.query(sql.toString(), (row, number) -> orderedRow(row, keys.toArray(String[]::new)), parameters.toArray());
    }

    private List<Map<String, Object>> memberTimelineRows(
            AuthorizedTenantContext context,
            Work work,
            boolean detail,
            UUID memberId,
            int limit) {
        return auditRows(context, work, detail, memberId, limit);
    }

    private List<Map<String, Object>> credentialDecisionRows(
            AuthorizedTenantContext context, Work work, UUID memberId, int limit) {
        return jdbc.query(
                """
                SELECT verification.id AS "verificationId",credential.id AS "credentialId",
                       credential.workforce_member_id AS "memberId",verification.decision,
                       verification.decision_reason_code AS "decisionReasonCode",
                       verification.credential_digest AS "credentialDigest",
                       verification.evidence_digest AS "evidenceDigest",
                       verification.policy_version AS "policyVersion",
                       verification.registry_version AS "registryVersion",
                       verification.decided_at AS "decidedAt",verification.reviewer_id AS "reviewerId"
                FROM credential_verifications verification
                JOIN practitioner_credentials credential
                  ON credential.organization_id=verification.organization_id
                 AND credential.id=verification.practitioner_credential_id
                WHERE verification.organization_id=? AND credential.workforce_member_id=?
                  AND verification.decided_at<=?
                ORDER BY verification.decided_at DESC,verification.id DESC LIMIT ?
                """,
                (row, number) -> orderedRow(row,
                        "verificationId","credentialId","memberId","decision","decisionReasonCode",
                        "credentialDigest","evidenceDigest","policyVersion","registryVersion",
                        "decidedAt","reviewerId"),
                context.organizationId(),memberId,Timestamp.from(work.snapshotAt()),limit);
    }

    private List<Map<String, Object>> scopeDecisionRows(
            AuthorizedTenantContext context, Work work, UUID memberId, int limit) {
        return jdbc.query(
                """
                SELECT scope.id AS "scopeId",practitioner.workforce_member_id AS "memberId",
                       scope.scope_definition_id AS "definitionId",scope.lifecycle_state AS status,
                       scope.result_digest AS "resultDigest",scope.submitted_by AS "submittedBy",
                       scope.submitted_at AS "submittedAt",scope.decided_by AS "decidedBy",
                       scope.decision_code AS "decisionCode",scope.decided_at AS "decidedAt",
                       scope.effective_from AS "effectiveFrom",scope.effective_to AS "effectiveTo"
                FROM scopes_of_practice scope
                JOIN practitioner_profiles practitioner
                  ON practitioner.organization_id=scope.organization_id
                 AND practitioner.id=scope.practitioner_profile_id
                WHERE scope.organization_id=? AND practitioner.workforce_member_id=?
                  AND scope.created_at<=?
                ORDER BY coalesce(scope.decided_at,scope.submitted_at,scope.created_at) DESC,scope.id DESC
                LIMIT ?
                """,
                (row, number) -> orderedRow(row,
                        "scopeId","memberId","definitionId","status","resultDigest","submittedBy",
                        "submittedAt","decidedBy","decisionCode","decidedAt","effectiveFrom","effectiveTo"),
                context.organizationId(),memberId,Timestamp.from(work.snapshotAt()),limit);
    }

    @Override
    public Work ready(
            AuthorizedTenantContext context,
            Work work,
            String artifactReference,
            String artifactDigest,
            String contentType,
            String filename,
            int rowCount,
            long byteCount) {
        var result = jdbc.query(
                """
                UPDATE workforce_export_jobs
                   SET status='ready',artifact_opaque_id=?,artifact_digest=?,
                       artifact_content_type=?,artifact_filename=?,artifact_byte_count=?,
                       row_count=?,ready_at=clock_timestamp(),expires_at=clock_timestamp()+interval '24 hours',
                       worker_id=NULL,lease_expires_at=NULL,lock_version=lock_version+1,
                       updated_at=clock_timestamp(),updated_by=?
                 WHERE organization_id=? AND id=? AND status='running' AND lock_version=?
                """ + RETURNING,
                row -> row.next() ? work(row) : null,
                UUID.fromString(artifactReference),artifactDigest,contentType,filename,byteCount,rowCount,
                context.actorId(),context.organizationId(),work.exportId(),work.revision());
        if (result == null) throw new IllegalArgumentException("workforce export generation lease was lost");
        return result;
    }

    @Override
    public Work failed(
            AuthorizedTenantContext context, Work work, String failureCode, boolean retryable) {
        var retry = retryable && work.attemptCount() < 5;
        var result = jdbc.query(
                """
                UPDATE workforce_export_jobs
                   SET status=?,failure_code=?,
                       next_attempt_at=CASE WHEN ? THEN clock_timestamp()
                           +(power(2,attempt_count)::text||' minutes')::interval ELSE NULL END,
                       dead_lettered_at=CASE WHEN ? THEN NULL ELSE clock_timestamp() END,
                       worker_id=NULL,lease_expires_at=NULL,lock_version=lock_version+1,
                       updated_at=clock_timestamp(),updated_by=?
                 WHERE organization_id=? AND id=? AND status='running' AND lock_version=?
                """ + RETURNING,
                row -> row.next() ? work(row) : null,
                retry ? "authorized" : "failed",failureCode,retry,retry,
                context.actorId(),context.organizationId(),work.exportId(),work.revision());
        if (result == null) throw new IllegalArgumentException("workforce export generation lease was lost");
        return result;
    }

    @Override
    public Access access(
            AuthorizedTenantContext context, UUID exportId, long revision, String purposeKey) {
        var result = jdbc.query(
                """
                SELECT id,artifact_opaque_id,artifact_digest,artifact_content_type,
                       artifact_filename,row_count,expires_at,lock_version
                FROM workforce_export_jobs
                WHERE organization_id=? AND id=? AND requester_id=? AND purpose_key=?
                  AND status='ready' AND expires_at>clock_timestamp() AND lock_version=?
                FOR UPDATE
                """,
                row -> row.next()
                        ? new Access(
                                row.getObject(1,UUID.class),
                                row.getObject(2,UUID.class).toString(),
                                row.getString(3),row.getString(4),row.getString(5),
                                (Integer) row.getObject(6),row.getTimestamp(7).toInstant(),row.getLong(8))
                        : null,
                context.organizationId(),exportId,context.actorId(),purposeKey,revision);
        if (result == null) throw new IllegalArgumentException("ready workforce export is unavailable");
        return result;
    }

    @Override
    public Work expire(AuthorizedTenantContext context, UUID exportId, long revision) {
        return transition(
                context,exportId,revision,
                """
                UPDATE workforce_export_jobs
                   SET status='expired',lock_version=lock_version+1,
                       updated_at=clock_timestamp(),updated_by=?
                 WHERE organization_id=? AND id=? AND status='ready'
                   AND expires_at<=clock_timestamp() AND lock_version=?
                """);
    }

    @Override
    public Work forDisposal(AuthorizedTenantContext context, UUID exportId, long revision) {
        var result = jdbc.query(
                """
                SELECT id,requester_id,projection,format,filters_json::text,filters_digest,
                       purpose_key,status,snapshot_at,row_limit,size_limit_bytes,attempt_count,
                       lock_version,artifact_opaque_id,artifact_digest,artifact_content_type,
                       artifact_filename,row_count,artifact_byte_count,expires_at,legal_hold
                FROM workforce_export_jobs
                WHERE organization_id=? AND id=? AND status='expired' AND lock_version=?
                FOR UPDATE
                """,
                row -> row.next() ? work(row) : null,
                context.organizationId(),exportId,revision);
        if (result == null) throw new IllegalArgumentException("expired workforce export is unavailable");
        return result;
    }

    @Override
    public Work disposed(AuthorizedTenantContext context, UUID exportId, long revision) {
        return transition(
                context,exportId,revision,
                """
                UPDATE workforce_export_jobs
                   SET status='disposed',disposed_at=clock_timestamp(),lock_version=lock_version+1,
                       updated_at=clock_timestamp(),updated_by=?
                 WHERE organization_id=? AND id=? AND status='expired'
                   AND NOT legal_hold AND lock_version=?
                """);
    }

    private Work transition(
            AuthorizedTenantContext context, UUID exportId, long revision, String sql) {
        var result = jdbc.query(
                sql + RETURNING,
                row -> row.next() ? work(row) : null,
                context.actorId(),context.organizationId(),exportId,revision);
        if (result == null) throw new IllegalArgumentException("workforce export transition is unavailable");
        return result;
    }

    private Work work(ResultSet row) throws SQLException {
        var reference = row.getObject(14,UUID.class);
        return new Work(
                row.getObject(1,UUID.class),row.getObject(2,UUID.class),row.getString(3),
                row.getString(4),row.getString(5),row.getString(6),row.getString(7),
                row.getString(8),row.getTimestamp(9).toInstant(),row.getInt(10),row.getLong(11),
                row.getInt(12),row.getLong(13),reference == null ? null : reference.toString(),
                row.getString(15),row.getString(16),row.getString(17),
                (Integer) row.getObject(18),(Long) row.getObject(19),instant(row.getTimestamp(20)),
                row.getBoolean(21));
    }

    private Map<String, Object> filters(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception exception) {
            throw new IllegalStateException("Stored workforce export filters are invalid.", exception);
        }
    }

    private void requireRequesterSourcePermissions(
            AuthorizedTenantContext context, Work work) {
        var required=switch (work.projection()) {
            case "workforce-directory-summary-v1" -> List.of("workforce.directory.read");
            case "credential-expiry-summary-v1" -> List.of("workforce.expiry.read");
            case "workforce-configuration-summary-v1" -> List.of("workforce.history.read");
            case "workforce-audit-summary-v1","workforce-audit-detail-v1" ->
                    List.of("workforce.audit.read");
            case "member-timeline-summary-v1" -> List.of("workforce.timeline.read");
            case "member-evidence-detail-v1" -> List.of(
                    "workforce.timeline.read","workforce.audit.read",
                    "credential.qualification.read","credential.registration.read",
                    "credential.record.read","practitioner.scope.read",
                    "workforce.assignment.read","practitioner.service_assignment.read");
            case "credential-decision-detail-v1" ->
                    List.of("credential.record.read","credential.document.read");
            case "scope-decision-detail-v1" -> List.of("practitioner.scope.read");
            default -> List.<String>of();
        };
        if (required.isEmpty()) throw new SecurityException("unsupported export projection");
        var count=jdbc.queryForObject(
                """
                SELECT count(DISTINCT grant_record.permission_key)
                FROM organization_memberships membership
                JOIN authorization_role_permissions grant_record
                  ON grant_record.role_key=membership.role_key AND grant_record.status='active'
                JOIN authorization_registry_releases release
                  ON release.registry_version=grant_record.registry_version AND release.status='active'
                WHERE membership.organization_id=? AND membership.user_id=?
                  AND membership.status='active' AND membership.effective_from<=clock_timestamp()
                  AND (membership.effective_to IS NULL OR membership.effective_to>clock_timestamp())
                  AND grant_record.permission_key=ANY(?::varchar[])
                """,
                Integer.class,context.organizationId(),work.requesterId(),
                required.toArray(String[]::new));
        if (count==null || count!=required.size()) {
            throw new SecurityException("export requester source authorization changed");
        }
    }

    private static Map<String, Object> orderedRow(ResultSet row, String... keys)
            throws SQLException {
        var result = new LinkedHashMap<String, Object>();
        for (var key : keys) result.put(key, row.getObject(key));
        return result;
    }

    private static UUID optionalUuid(Object value) {
        return value == null ? null : UUID.fromString(value.toString());
    }

    private static UUID requiredMember(UUID value) {
        if (value == null) throw new IllegalArgumentException("member-bound export requires memberId");
        return value;
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
