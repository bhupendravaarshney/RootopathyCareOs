package com.rootopathy.careos.workforce.infrastructure;

import com.rootopathy.careos.shared.domain.UuidV7Generator;
import com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext;
import com.rootopathy.careos.workforce.application.WorkforceExpiryStore;
import com.rootopathy.careos.workforce.application.WorkforceNotificationStore;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public final class JdbcWorkforceExpiryStore
        implements WorkforceExpiryStore, WorkforceNotificationStore {
    private final JdbcTemplate jdbc;

    public JdbcWorkforceExpiryStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Milestone> projectDue(
            AuthorizedTenantContext context,
            LocalDate evaluationDate,
            Instant evaluatedAt,
            int maximumItems) {
        if (maximumItems < 1 || maximumItems > 500) {
            throw new IllegalArgumentException("maximumItems must be between 1 and 500");
        }
        var candidates = jdbc.query(
                """
                WITH parameters AS (
                    SELECT ?::date AS evaluation_date, ?::timestamptz AS evaluated_at
                ), active_template AS (
                    SELECT entry.id AS template_entry_id,version.id AS template_version_id
                    FROM workforce_registry_definitions definition
                    JOIN workforce_registry_entries entry
                      ON entry.organization_id=definition.organization_id
                     AND entry.registry_definition_id=definition.id
                    JOIN workforce_registry_versions version
                      ON version.organization_id=entry.organization_id
                     AND version.registry_entry_id=entry.id
                    CROSS JOIN parameters parameter
                    WHERE definition.organization_id=?
                      AND definition.category='notification_template_metadata'
                      AND definition.status='active' AND entry.status='active'
                      AND version.status='active'
                      AND version.effective_from<=parameter.evaluated_at
                      AND (version.effective_to IS NULL OR version.effective_to>parameter.evaluated_at)
                      AND coalesce((version.version_fields->>'enabled')::boolean,false)
                    ORDER BY version.effective_from DESC,version.version_number DESC,version.id DESC
                    LIMIT 1
                ), sources AS (
                    SELECT 'credential'::text AS source_type,credential.id AS source_id,
                           credential.workforce_member_id AS member_id,credential.expires_on AS expiry_date
                    FROM practitioner_credentials credential
                    WHERE credential.organization_id=? AND credential.expires_on IS NOT NULL
                      AND credential.status IN ('verified','suspended','expired')
                    UNION ALL
                    SELECT 'registration'::text,registration.id,practitioner.workforce_member_id,
                           registration.expires_on
                    FROM professional_registrations registration
                    JOIN practitioner_profiles practitioner
                      ON practitioner.organization_id=registration.organization_id
                     AND practitioner.id=registration.practitioner_profile_id
                    WHERE registration.organization_id=? AND registration.expires_on IS NOT NULL
                      AND registration.status IN ('verified','suspended','expired')
                ), due AS (
                    SELECT source.*,template.template_entry_id,template.template_version_id,
                           CASE source.expiry_date-parameter.evaluation_date
                             WHEN 90 THEN '90' WHEN 60 THEN '60' WHEN 30 THEN '30'
                             WHEN 7 THEN '7' WHEN 0 THEN '0' ELSE 'expired' END AS milestone,
                           parameter.evaluated_at
                    FROM sources source
                    CROSS JOIN active_template template
                    CROSS JOIN parameters parameter
                    WHERE source.expiry_date-parameter.evaluation_date IN (90,60,30,7,0)
                       OR source.expiry_date<parameter.evaluation_date
                )
                SELECT due.source_type,due.source_id,due.member_id,due.expiry_date,due.milestone,
                       due.template_entry_id,due.template_version_id,recipient.user_id
                FROM due
                LEFT JOIN LATERAL (
                    SELECT membership.user_id
                    FROM access_assignment_scopes scope
                    JOIN organization_memberships membership
                      ON membership.organization_id=scope.organization_id
                     AND membership.id=scope.access_assignment_id
                    WHERE scope.organization_id=? AND scope.workforce_member_id=due.member_id
                      AND scope.status='active' AND membership.status='active'
                      AND scope.effective_from<=due.evaluated_at
                      AND (scope.effective_to IS NULL OR scope.effective_to>due.evaluated_at)
                      AND membership.effective_from<=due.evaluated_at
                      AND (membership.effective_to IS NULL OR membership.effective_to>due.evaluated_at)
                    ORDER BY scope.effective_from DESC,scope.id DESC
                    LIMIT 1
                ) recipient ON true
                WHERE NOT EXISTS (
                    SELECT 1 FROM workforce_notification_deliveries delivery
                    WHERE delivery.organization_id=?
                      AND delivery.template_version_id=due.template_version_id
                      AND delivery.milestone=due.milestone AND delivery.attempt_number=1
                      AND ((due.source_type='credential'
                            AND delivery.practitioner_credential_id=due.source_id)
                        OR (due.source_type='registration'
                            AND delivery.professional_registration_id=due.source_id))
                )
                ORDER BY due.expiry_date,due.source_type,due.source_id
                LIMIT ?
                """,
                (row, number) -> new Candidate(
                        row.getString("source_type"),
                        row.getObject("source_id", UUID.class),
                        row.getObject("member_id", UUID.class),
                        row.getObject("expiry_date", LocalDate.class),
                        row.getString("milestone"),
                        row.getObject("template_entry_id", UUID.class),
                        row.getObject("template_version_id", UUID.class),
                        row.getObject("user_id", UUID.class)),
                evaluationDate,
                Timestamp.from(evaluatedAt),
                context.organizationId(),
                context.organizationId(),
                context.organizationId(),
                context.organizationId(),
                context.organizationId(),
                maximumItems);

        var projected = new ArrayList<Milestone>(candidates.size());
        for (var candidate : candidates) {
            var notificationId = UuidV7Generator.randomUuid();
            var planned = candidate.recipientUserId() != null;
            var inserted = jdbc.query(
                    """
                    INSERT INTO workforce_notification_deliveries(
                        id,organization_id,workforce_member_id,practitioner_credential_id,
                        professional_registration_id,template_entry_id,template_version_id,
                        milestone,channel,recipient_opaque_reference,purpose_key,attempt_number,
                        failure_code,status,created_by,updated_by)
                    VALUES (?,?,?,?,?,?,?,?,? ,?,?,1,?,?,?,?)
                    ON CONFLICT DO NOTHING
                    RETURNING id
                    """,
                    resultSet -> resultSet.next()
                            ? resultSet.getObject(1, UUID.class)
                            : null,
                    notificationId,
                    context.organizationId(),
                    candidate.memberId(),
                    candidate.sourceType().equals("credential") ? candidate.sourceId() : null,
                    candidate.sourceType().equals("registration") ? candidate.sourceId() : null,
                    candidate.templateEntryId(),
                    candidate.templateVersionId(),
                    candidate.milestone(),
                    "email",
                    planned ? candidate.recipientUserId() : candidate.memberId(),
                    "credential_expiry_notification",
                    planned ? null : "recipient_unavailable",
                    planned ? "planned" : "suppressed",
                    context.actorId(),
                    context.actorId());
            if (inserted == null) continue;
            if (candidate.milestone().equals("expired")) {
                expireSource(context, candidate);
            }
            projected.add(new Milestone(
                    notificationId,
                    candidate.sourceId(),
                    candidate.sourceType(),
                    candidate.memberId(),
                    candidate.expiryDate(),
                    candidate.milestone(),
                    planned ? "planned" : "suppressed",
                    planned ? "notification_planned" : "recipient_unavailable"));
        }
        return List.copyOf(projected);
    }

    @Override
    public List<Plan> lockPlanned(AuthorizedTenantContext context, int maximumItems) {
        if (maximumItems < 1 || maximumItems > 100) {
            throw new IllegalArgumentException("maximumItems must be between 1 and 100");
        }
        return jdbc.query(
                """
                SELECT delivery.id,delivery.recipient_opaque_reference,delivery.workforce_member_id,
                       entry.entry_key,version.version_number,delivery.template_version_id,
                       delivery.milestone,
                       coalesce(credential.expires_on,registration.expires_on) AS expiry_date,
                       delivery.lock_version
                FROM workforce_notification_deliveries delivery
                JOIN workforce_registry_versions version
                  ON version.organization_id=delivery.organization_id
                 AND version.id=delivery.template_version_id
                JOIN workforce_registry_entries entry
                  ON entry.organization_id=version.organization_id
                 AND entry.id=version.registry_entry_id
                LEFT JOIN practitioner_credentials credential
                  ON credential.organization_id=delivery.organization_id
                 AND credential.id=delivery.practitioner_credential_id
                LEFT JOIN professional_registrations registration
                  ON registration.organization_id=delivery.organization_id
                 AND registration.id=delivery.professional_registration_id
                WHERE delivery.organization_id=? AND delivery.status='planned'
                ORDER BY delivery.created_at,delivery.id
                LIMIT ? FOR UPDATE OF delivery SKIP LOCKED
                """,
                (row, number) -> new Plan(
                        row.getObject("id", UUID.class),
                        row.getObject("recipient_opaque_reference", UUID.class),
                        row.getObject("workforce_member_id", UUID.class),
                        "workforce.expiry." + row.getString("entry_key"),
                        row.getInt("version_number"),
                        row.getObject("template_version_id", UUID.class),
                        row.getString("milestone"),
                        row.getObject("expiry_date", LocalDate.class),
                        row.getLong("lock_version")),
                context.organizationId(),
                maximumItems);
    }

    @Override
    public Delivery queued(
            AuthorizedTenantContext context, UUID notificationId, long revision, Instant queuedAt) {
        return transition(
                context,
                notificationId,
                revision,
                """
                UPDATE workforce_notification_deliveries
                   SET status='queued',queued_at=?,lock_version=lock_version+1,
                       updated_at=clock_timestamp(),updated_by=?
                 WHERE organization_id=? AND id=? AND status='planned' AND lock_version=?
                """,
                Timestamp.from(queuedAt));
    }

    @Override
    public Delivery delivered(
            AuthorizedTenantContext context,
            UUID notificationId,
            long revision,
            int attempt,
            String providerOpaqueId,
            Instant deliveredAt) {
        return transition(
                context,
                notificationId,
                revision,
                """
                UPDATE workforce_notification_deliveries
                   SET status='delivered',attempt_number=?,provider_opaque_id=?,sent_at=?,delivered_at=?,
                       failure_code=NULL,lock_version=lock_version+1,
                       updated_at=clock_timestamp(),updated_by=?
                 WHERE organization_id=? AND id=? AND status IN ('queued','sending') AND lock_version=?
                """,
                attempt,
                providerOpaqueId,
                Timestamp.from(deliveredAt),
                Timestamp.from(deliveredAt));
    }

    @Override
    public Delivery failed(
            AuthorizedTenantContext context,
            UUID notificationId,
            long revision,
            int attempt,
            String failureCode,
            Instant failedAt) {
        return transition(
                context,
                notificationId,
                revision,
                """
                UPDATE workforce_notification_deliveries
                   SET status='failed',attempt_number=?,failure_code=?,sent_at=?,
                       lock_version=lock_version+1,updated_at=clock_timestamp(),updated_by=?
                 WHERE organization_id=? AND id=? AND status IN ('queued','sending') AND lock_version=?
                """,
                attempt,
                failureCode,
                Timestamp.from(failedAt));
    }

    private Delivery transition(
            AuthorizedTenantContext context,
            UUID notificationId,
            long revision,
            String sql,
            Object... values) {
        var parameters = new ArrayList<Object>();
        java.util.Collections.addAll(parameters,values);
        parameters.add(context.actorId());
        parameters.add(context.organizationId());
        parameters.add(notificationId);
        parameters.add(revision);
        var result = jdbc.query(
                sql + """
                 RETURNING id,template_version_id,channel,milestone,attempt_number,status,
                           failure_code,lock_version
                """,
                row -> row.next()
                        ? new Delivery(
                                row.getObject("id", UUID.class),
                                row.getObject("template_version_id", UUID.class),
                                row.getString("channel"),
                                row.getString("milestone"),
                                row.getInt("attempt_number"),
                                row.getString("status"),
                                row.getString("failure_code"),
                                row.getLong("lock_version"))
                        : null,
                parameters.toArray());
        if (result == null) {
            throw new IllegalArgumentException("workforce notification state is stale");
        }
        return result;
    }

    private void expireSource(AuthorizedTenantContext context, Candidate candidate) {
        if (candidate.sourceType().equals("credential")) {
            jdbc.update(
                    """
                    UPDATE practitioner_credentials
                       SET status='expired',lock_version=lock_version+1,
                           updated_at=clock_timestamp(),updated_by=?
                     WHERE organization_id=? AND id=? AND status IN ('verified','suspended')
                    """,
                    context.actorId(),context.organizationId(),candidate.sourceId());
        } else {
            jdbc.update(
                    """
                    UPDATE professional_registrations
                       SET status='expired',lock_version=lock_version+1,
                           updated_at=clock_timestamp(),updated_by=?
                     WHERE organization_id=? AND id=? AND status IN ('verified','suspended')
                    """,
                    context.actorId(),context.organizationId(),candidate.sourceId());
        }
    }

    private record Candidate(
            String sourceType,
            UUID sourceId,
            UUID memberId,
            LocalDate expiryDate,
            String milestone,
            UUID templateEntryId,
            UUID templateVersionId,
            UUID recipientUserId) {}
}
