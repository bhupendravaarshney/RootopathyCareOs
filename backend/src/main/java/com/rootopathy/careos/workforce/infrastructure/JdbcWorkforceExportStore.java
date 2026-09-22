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
    public List<UUID> dueExportIds(
            AuthorizedTenantContext context, Instant now, int maximumItems) {
        if (maximumItems < 1 || maximumItems > 100) {
            throw new IllegalArgumentException("maximumItems must be between 1 and 100");
        }
        return jdbc.queryForList(
                """
                SELECT id FROM workforce_export_jobs
                WHERE organization_id=?
                  AND ((status='authorized' AND attempt_count=0
                        AND (next_attempt_at IS NULL OR next_attempt_at<=?))
                    OR (status='running' AND lease_expires_at<=?))
                ORDER BY COALESCE(next_attempt_at,lease_expires_at,created_at),id
                LIMIT ?
                """,
                UUID.class,
                context.organizationId(),
                Timestamp.from(now),
                Timestamp.from(now),
                maximumItems);
    }

    @Override
    public Work claim(AuthorizedTenantContext context, UUID exportId, String workerId) {
        var result = jdbc.query(
                """
                UPDATE workforce_export_jobs
                   SET status=CASE
                           WHEN status='running' AND lease_expires_at<=clock_timestamp() THEN 'failed'
                           ELSE 'running' END,
                       attempt_count=CASE
                           WHEN status='running' AND lease_expires_at<=clock_timestamp() THEN attempt_count
                           ELSE attempt_count+1 END,
                       worker_id=CASE
                           WHEN status='running' AND lease_expires_at<=clock_timestamp() THEN NULL
                           ELSE ? END,
                       snapshot_at=CASE
                           WHEN status='authorized' THEN transaction_timestamp()
                           ELSE snapshot_at END,
                       lease_expires_at=CASE
                           WHEN status='running' AND lease_expires_at<=clock_timestamp() THEN NULL
                           ELSE clock_timestamp()+interval '15 minutes' END,
                       next_attempt_at=NULL,lock_version=lock_version+1,
                       dead_lettered_at=CASE
                           WHEN status='running' AND lease_expires_at<=clock_timestamp()
                                THEN clock_timestamp()
                           ELSE NULL END,
                       dead_letter_owner=CASE
                           WHEN status='running' AND lease_expires_at<=clock_timestamp()
                                THEN 'workforce_operations'
                           ELSE NULL END,
                       failure_code=CASE
                           WHEN status='running' AND lease_expires_at<=clock_timestamp()
                                THEN 'workforce.export.lease_expired'
                           ELSE NULL END,
                       updated_at=clock_timestamp(),updated_by=?
                 WHERE organization_id=? AND id=?
                   AND ((status='authorized' AND attempt_count=0
                         AND (next_attempt_at IS NULL
                              OR next_attempt_at<=clock_timestamp()))
                     OR (status='running' AND lease_expires_at<=clock_timestamp()))
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
        var search = optionalText(filters.get("search"));
        var status = optionalText(filters.get("status"));
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
                      AND (?::uuid IS NULL OR member.id=?::uuid)
                      AND (?::text IS NULL OR lower(link.display_label||' '||member.member_number)
                          LIKE '%'||lower(?::text)||'%')
                      AND (?::text IS NULL OR member.lifecycle_state=?::text)
                      AND careos_m2_user_can_access_member(
                          ?,?,member.id,'workforce.directory.read')
                    ORDER BY member.member_number,member.id LIMIT ?
                    """,
                    (row, number) -> orderedRow(row,
                            "memberId","memberNumber","displayName","pathway","status",
                            "primaryFacilityId","activatedAt"),
                    Timestamp.from(work.snapshotAt()),
                    Timestamp.from(work.snapshotAt()),
                    context.organizationId(),
                    Timestamp.from(work.snapshotAt()),
                    memberId,
                    memberId,
                    search,
                    search,
                    status,
                    status,
                    context.organizationId(),
                    work.requesterId(),
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
                      AND (?::uuid IS NULL OR credential.workforce_member_id=?::uuid)
                      AND (?::text IS NULL OR lower(entry.display_label||' '||member.member_number)
                          LIKE '%'||lower(?::text)||'%')
                      AND (?::text IS NULL OR credential.status=?::text)
                      AND careos_m2_user_can_access_member(
                          ?,?,credential.workforce_member_id,'workforce.expiry.read')
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
                    memberId,
                    memberId,
                    search,
                    search,
                    status,
                    status,
                    context.organizationId(),
                    work.requesterId(),
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
                      AND (?::text IS NULL OR lower(snapshot.display_number)
                          LIKE '%'||lower(?::text)||'%')
                      AND (?::text IS NULL OR snapshot.status=?::text)
                    ORDER BY snapshot.effective_at DESC,snapshot.id DESC LIMIT ?
                    """,
                    (row, number) -> orderedRow(row,
                            "snapshotId","displayNumber","parentSnapshotId","snapshotDigest","status",
                            "effectiveAt","supersededAt","makerId","checkerId","activatorId"),
                    context.organizationId(),Timestamp.from(work.snapshotAt()),
                    search,search,status,status,limit);
            case "workforce-audit-summary-v1" ->
                    auditRows(context, work, false, memberId, search, status, limit);
            case "workforce-audit-detail-v1" ->
                    auditRows(context, work, true, memberId, search, status, limit);
            case "member-timeline-summary-v1" ->
                    memberTimelineRows(
                            context, work, false, requiredMember(memberId), search, status, limit);
            case "member-evidence-detail-v1" ->
                    memberTimelineRows(
                            context, work, true, requiredMember(memberId), search, status, limit);
            case "credential-decision-detail-v1" -> credentialDecisionRows(
                    context, work, requiredMember(memberId), search, status, limit);
            case "scope-decision-detail-v1" -> scopeDecisionRows(
                    context, work, requiredMember(memberId), search, status, limit);
            default -> throw new IllegalArgumentException("unsupported workforce export projection");
        };
    }

    private List<Map<String, Object>> auditRows(
            AuthorizedTenantContext context,
            Work work,
            boolean detail,
            UUID memberId,
            String search,
            String status,
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
        sql.append("""
                 AND (?::text IS NULL OR lower(event.event_name) LIKE '%'||lower(?::text)||'%')
                 AND (?::text IS NULL OR event.event_name=?::text)
                """);
        parameters.add(search);
        parameters.add(search);
        parameters.add(status);
        parameters.add(status);
        if (memberId != null) {
            sql.append("""
                     AND careos_m2_audit_event_member_id(
                         event.organization_id,event.subject_type,event.subject_id,event.payload)=?
                     AND careos_m2_user_can_access_member(?,?,?::uuid,?)
                    """);
            parameters.add(memberId);
            parameters.add(context.organizationId());
            parameters.add(work.requesterId());
            parameters.add(memberId);
            parameters.add(scopePermission(work.projection()));
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
            String search,
            String status,
            int limit) {
        return auditRows(context, work, detail, memberId, search, status, limit);
    }

    private List<Map<String, Object>> credentialDecisionRows(
            AuthorizedTenantContext context,
            Work work,
            UUID memberId,
            String search,
            String status,
            int limit) {
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
                  AND (?::text IS NULL OR lower(credential.issuer) LIKE '%'||lower(?::text)||'%')
                  AND (?::text IS NULL OR credential.status=?::text)
                  AND careos_m2_user_can_access_member(
                      ?,?,credential.workforce_member_id,'credential.record.read')
                ORDER BY verification.decided_at DESC,verification.id DESC LIMIT ?
                """,
                (row, number) -> orderedRow(row,
                        "verificationId","credentialId","memberId","decision","decisionReasonCode",
                        "credentialDigest","evidenceDigest","policyVersion","registryVersion",
                        "decidedAt","reviewerId"),
                context.organizationId(),memberId,Timestamp.from(work.snapshotAt()),
                search,search,status,status,
                context.organizationId(),work.requesterId(),limit);
    }

    private List<Map<String, Object>> scopeDecisionRows(
            AuthorizedTenantContext context,
            Work work,
            UUID memberId,
            String search,
            String status,
            int limit) {
        return jdbc.query(
                """
                SELECT scope.id AS "scopeId",practitioner.workforce_member_id AS "memberId",
                       scope.scope_definition_id AS "definitionId",scope.status,
                       scope.result_digest AS "resultDigest",scope.submitted_by AS "submittedBy",
                       scope.submitted_at AS "submittedAt",scope.decided_by AS "decidedBy",
                       scope.decision_code AS "decisionCode",scope.decided_at AS "decidedAt",
                       scope.effective_from AS "effectiveFrom",scope.effective_to AS "effectiveTo"
                FROM scopes_of_practice scope
                JOIN practitioner_profiles practitioner
                  ON practitioner.organization_id=scope.organization_id
                 AND practitioner.id=scope.practitioner_profile_id
                JOIN scope_definitions definition
                  ON definition.organization_id=scope.organization_id
                 AND definition.id=scope.scope_definition_id
                WHERE scope.organization_id=? AND practitioner.workforce_member_id=?
                  AND scope.created_at<=?
                  AND (?::text IS NULL OR lower(definition.name) LIKE '%'||lower(?::text)||'%')
                  AND (?::text IS NULL OR scope.status=?::text)
                  AND careos_m2_user_can_access_member(
                      ?,?,practitioner.workforce_member_id,'practitioner.scope.read')
                ORDER BY coalesce(scope.decided_at,scope.submitted_at,scope.created_at) DESC,scope.id DESC
                LIMIT ?
                """,
                (row, number) -> orderedRow(row,
                        "scopeId","memberId","definitionId","status","resultDigest","submittedBy",
                        "submittedAt","decidedBy","decisionCode","decidedAt","effectiveFrom","effectiveTo"),
                context.organizationId(),memberId,Timestamp.from(work.snapshotAt()),
                search,search,status,status,
                context.organizationId(),work.requesterId(),limit);
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
            AuthorizedTenantContext context, Work work, String failureCode) {
        var result = jdbc.query(
                """
                UPDATE workforce_export_jobs
                   SET status='failed',failure_code=?,next_attempt_at=NULL,
                       dead_lettered_at=clock_timestamp(),
                       dead_letter_owner='workforce_operations',
                       worker_id=NULL,lease_expires_at=NULL,lock_version=lock_version+1,
                       updated_at=clock_timestamp(),updated_by=?
                 WHERE organization_id=? AND id=? AND status='running' AND lock_version=?
                """ + RETURNING,
                row -> row.next() ? work(row) : null,
                failureCode,
                context.actorId(),context.organizationId(),work.exportId(),work.revision());
        if (result == null) throw new IllegalArgumentException("workforce export generation lease was lost");
        return result;
    }

    @Override
    public Access access(
            AuthorizedTenantContext context,
            UUID exportId,
            long revision,
            String purposeKey,
            Instant maximumGrantExpiry) {
        requireSourceAccess(context,exportId);
        var result = jdbc.query(
                """
                UPDATE workforce_export_jobs
                   SET access_granted_to=requester_id,
                       access_grant_digest=artifact_digest,
                       access_granted_at=clock_timestamp(),
                       access_grant_expires_at=least(
                           expires_at,clock_timestamp()+interval '10 minutes',?),
                       lock_version=lock_version+1,
                       updated_at=clock_timestamp(),updated_by=?
                 WHERE organization_id=? AND id=? AND requester_id=? AND purpose_key=?
                   AND status='ready' AND expires_at>clock_timestamp()
                   AND artifact_opaque_id IS NOT NULL AND artifact_digest IS NOT NULL
                   AND artifact_content_type IS NOT NULL AND artifact_filename IS NOT NULL
                   AND ?>clock_timestamp() AND lock_version=?
                RETURNING id,artifact_digest,artifact_content_type,artifact_filename,
                          row_count,access_grant_expires_at,lock_version
                """,
                row -> row.next()
                        ? new Access(
                                row.getObject(1,UUID.class),
                                row.getString(2),row.getString(3),row.getString(4),
                                (Integer) row.getObject(5),row.getTimestamp(6).toInstant(),row.getLong(7))
                        : null,
                Timestamp.from(maximumGrantExpiry),context.actorId(),context.organizationId(),
                exportId,context.actorId(),purposeKey,Timestamp.from(maximumGrantExpiry),revision);
        if (result == null) throw new IllegalArgumentException("ready workforce export is unavailable");
        return result;
    }

    @Override
    public Download download(AuthorizedTenantContext context, UUID exportId) {
        requireSourceAccess(context,exportId);
        var result=jdbc.query(
                """
                SELECT id,artifact_opaque_id,artifact_digest,artifact_content_type,
                       artifact_filename,artifact_byte_count,access_grant_expires_at
                FROM workforce_export_jobs
                WHERE organization_id=? AND id=? AND requester_id=?
                  AND access_granted_to=? AND status='ready'
                  AND expires_at>clock_timestamp()
                  AND access_grant_expires_at>clock_timestamp()
                  AND access_grant_digest=artifact_digest
                  AND artifact_opaque_id IS NOT NULL AND artifact_digest IS NOT NULL
                  AND artifact_content_type IS NOT NULL AND artifact_filename IS NOT NULL
                  AND artifact_byte_count IS NOT NULL
                """,
                row -> row.next()
                        ? new Download(
                                row.getObject(1,UUID.class),row.getObject(2,UUID.class).toString(),
                                row.getString(3),row.getString(4),row.getString(5),
                                row.getLong(6),row.getTimestamp(7).toInstant())
                        : null,
                context.organizationId(),exportId,context.actorId(),context.actorId());
        if (result==null) throw new SecurityException("workforce export download grant is unavailable");
        return result;
    }

    @Override
    public void requireSourceAccess(AuthorizedTenantContext context, UUID exportId) {
        var source=jdbc.query(
                """
                SELECT requester_id,projection,filters_json::text FROM workforce_export_jobs
                WHERE organization_id=? AND id=? AND requester_id=?
                  AND status='ready' AND expires_at>clock_timestamp()
                """,
                row -> row.next()
                        ? new SourceAuthorization(
                                row.getObject("requester_id",UUID.class),
                                row.getString("projection"),
                                row.getString("filters_json"))
                        : null,
                context.organizationId(),exportId,context.actorId());
        if (source==null) throw new SecurityException("ready workforce export is unavailable");
        var memberId=optionalUuid(filters(source.filtersJson()).get("memberId"));
        requireRequesterSourcePermissions(
                context,source.requesterId(),source.projection(),memberId);
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
    public Work forDisposal(AuthorizedTenantContext context, UUID exportId) {
        return forDisposal(context, exportId, null);
    }

    @Override
    public Work forDisposal(AuthorizedTenantContext context, UUID exportId, long revision) {
        return forDisposal(context, exportId, Long.valueOf(revision));
    }

    private Work forDisposal(
            AuthorizedTenantContext context, UUID exportId, Long expectedRevision) {
        var result = jdbc.query(
                """
                SELECT id,requester_id,projection,format,filters_json::text,filters_digest,
                       purpose_key,status,snapshot_at,row_limit,size_limit_bytes,attempt_count,
                       lock_version,artifact_opaque_id,artifact_digest,artifact_content_type,
                       artifact_filename,row_count,artifact_byte_count,expires_at,legal_hold
                FROM workforce_export_jobs
                WHERE organization_id=? AND id=? AND status='expired'
                  AND (?::bigint IS NULL OR lock_version=?::bigint)
                FOR UPDATE
                """,
                row -> row.next() ? work(row) : null,
                context.organizationId(),exportId,expectedRevision,expectedRevision);
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
        var memberId=optionalUuid(filters(work.filtersJson()).get("memberId"));
        requireRequesterSourcePermissions(
                context,work.requesterId(),work.projection(),memberId);
    }

    private void requireRequesterSourcePermissions(
            AuthorizedTenantContext context,
            UUID requesterId,
            String projection,
            UUID memberId) {
        var required=switch (projection) {
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
                JOIN authorization_roles role ON role.role_key=membership.role_key
                JOIN authorization_role_permissions grant_record
                  ON grant_record.role_key=membership.role_key
                JOIN authorization_permissions permission
                  ON permission.permission_key=grant_record.permission_key
                WHERE membership.organization_id=? AND membership.user_id=?
                  AND membership.status='active' AND membership.effective_from<=clock_timestamp()
                  AND (membership.effective_to IS NULL OR membership.effective_to>clock_timestamp())
                  AND role.status='active' AND role.interactive
                  AND permission.status='active'
                  AND EXISTS(SELECT 1 FROM authorization_registry_releases release
                      WHERE release.registry_version=role.registry_version
                        AND release.status='active')
                  AND EXISTS(SELECT 1 FROM authorization_registry_releases release
                      WHERE release.registry_version=permission.registry_version
                        AND release.status='active')
                  AND grant_record.permission_key=ANY(?::varchar[])
                """,
                Integer.class,context.organizationId(),requesterId,
                required.toArray(String[]::new));
        if (count==null || count!=required.size()) {
            throw new SecurityException("export requester source authorization changed");
        }
        if (memberId!=null && !Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT careos_m2_user_can_access_member(?,?,?,?)",
                Boolean.class,
                context.organizationId(),requesterId,memberId,scopePermission(projection)))) {
            throw new SecurityException("export requester resource scope changed");
        }
    }

    private static String scopePermission(String projection) {
        return switch (projection) {
            case "workforce-directory-summary-v1" -> "workforce.directory.read";
            case "credential-expiry-summary-v1" -> "workforce.expiry.read";
            case "workforce-configuration-summary-v1" -> "workforce.history.read";
            case "workforce-audit-summary-v1","workforce-audit-detail-v1" ->
                    "workforce.audit.read";
            case "member-timeline-summary-v1","member-evidence-detail-v1" ->
                    "workforce.timeline.read";
            case "credential-decision-detail-v1" -> "credential.record.read";
            case "scope-decision-detail-v1" -> "practitioner.scope.read";
            default -> throw new SecurityException("unsupported export projection");
        };
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

    private static String optionalText(Object value) {
        if (value == null) return null;
        var text = value.toString().strip();
        return text.isEmpty() ? null : text;
    }

    private static UUID requiredMember(UUID value) {
        if (value == null) throw new IllegalArgumentException("member-bound export requires memberId");
        return value;
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private record SourceAuthorization(
            UUID requesterId, String projection, String filtersJson) {}
}
