package com.rootopathy.careos.encounter.infrastructure;

import com.rootopathy.careos.encounter.application.EncounterException;
import com.rootopathy.careos.encounter.application.EncounterStore;
import com.rootopathy.careos.encounter.domain.EncounterScreen;
import com.rootopathy.careos.shared.domain.UuidV7Generator;
import com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** PostgreSQL-backed encounter projections and governed clinical mutations for Module 5. */
@Repository
public class JdbcEncounterStore implements EncounterStore {
    private static final String POLICY_VERSION = "m5-standing-direction-v1";

    private final JdbcTemplate jdbc;

    public JdbcEncounterStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Projection projection(AuthorizedTenantContext context, ScreenQuery query) {
        requireOperationScope(context);
        var rows = switch (query.screenId()) {
            case "P5-01", "P5-02", "P5-03" -> encounterRows(context, query);
            case "P5-04" -> combine(encounterRows(context, query), participantRows(context, query));
            case "P5-05" -> combine(encounterRows(context, query), concernRows(context, query));
            case "P5-06" -> timelineRows(context, query);
            case "P5-07" -> combine(encounterRows(context, query), problemRows(context, query));
            case "P5-08" -> combine(encounterRows(context, query), workRows(context, query));
            case "P5-09" -> combine(encounterRows(context, query), noteRows(context, query));
            case "P5-10", "P5-11" -> noteRows(context, query);
            case "P5-12" -> historyRows(context, query);
            default -> throw notFound("The requested encounter projection does not exist.");
        };
        rows = rows.stream().map(row -> withAllowedActions(query.screenId(), row)).toList();
        var generatedAt = databaseNow();
        return new Projection(
                metrics(context), columns(query.screenId()), rows, notices(query.screenId()), generatedAt);
    }

    @Override
    public Set<String> permissions(AuthorizedTenantContext context) {
        requireOperationScope(context);
        return Set.copyOf(jdbc.queryForList(
                """
                SELECT DISTINCT grants.permission_key
                FROM organization_memberships memberships
                JOIN authorization_roles roles ON roles.role_key=memberships.role_key
                JOIN authorization_role_permissions grants ON grants.role_key=roles.role_key
                JOIN authorization_permissions permissions
                  ON permissions.permission_key=grants.permission_key
                WHERE memberships.organization_id=? AND memberships.user_id=?
                  AND memberships.status='active'
                  AND memberships.effective_from<=clock_timestamp()
                  AND (memberships.effective_to IS NULL OR memberships.effective_to>clock_timestamp())
                  AND roles.status='active' AND roles.interactive
                  AND permissions.status='active'
                  AND EXISTS(SELECT 1 FROM authorization_registry_releases release
                      WHERE release.registry_version=roles.registry_version AND release.status='active')
                  AND EXISTS(SELECT 1 FROM authorization_registry_releases release
                      WHERE release.registry_version=permissions.registry_version AND release.status='active')
                """,
                String.class,
                context.organizationId(),
                context.actorId()));
    }

    @Override
    public MutationResult mutate(AuthorizedTenantContext context, MutationCommand command) {
        requireOperationScope(context);
        return switch (command.actionKey()) {
            case "open-encounter" -> openEncounter(context, command);
            case "mark-arrived", "start-encounter", "place-on-hold", "resume-encounter",
                    "complete-encounter", "cancel-encounter", "enter-encounter-in-error" ->
                transitionEncounter(context, command);
            case "add-practitioner-participant" -> addParticipant(context, command);
            case "remove-participant" -> removeParticipant(context, command);
            case "record-presenting-concern" -> recordConcern(context, command);
            case "record-clinical-problem" -> recordProblem(context, command);
            case "record-diagnosis" -> recordDiagnosis(context, command);
            case "create-order" -> createOrder(context, command);
            case "create-clinical-task" -> createTask(context, command);
            case "progress-clinical-task" -> progressTask(context, command);
            case "acknowledge-red-flag", "resolve-red-flag" ->
                progressRedFlag(context, command);
            case "save-note-version" -> saveNoteVersion(context, command);
            case "sign-note" -> signNote(context, command);
            case "amend-signed-note" -> amendNote(context, command);
            default -> throw notFound("The requested encounter action does not exist.");
        };
    }

    private List<EncounterScreen.Row> encounterRows(
            AuthorizedTenantContext context, ScreenQuery query) {
        var search = query.search();
        if (search != null) requireLiteralSearch(search, 2, "Encounter search");
        return jdbc.query(
                """
                SELECT encounter.id,encounter.episode_of_care_id,encounter.source_appointment_id,
                       encounter.patient_id,encounter.status,encounter.encounter_type_key,
                       encounter.planned_start_at,encounter.lock_version,
                       patient.patient_number,
                       coalesce(patient.name_to_use,
                           nullif(concat_ws(' ',patient.official_given_name,patient.official_family_name),''),
                           'Patient '||left(patient.id::text,8)) patient_label,
                       service.display_name service_name,facility.name facility_name,
                       location.name location_name,practitioner.clinical_title
                FROM encounters encounter
                JOIN patient_profiles patient
                  ON patient.organization_id=encounter.organization_id AND patient.id=encounter.patient_id
                JOIN service_definitions service
                  ON service.organization_id=encounter.organization_id AND service.id=encounter.service_id
                JOIN facilities facility
                  ON facility.organization_id=encounter.organization_id AND facility.id=encounter.facility_id
                JOIN service_locations location
                  ON location.organization_id=encounter.organization_id AND location.id=encounter.location_id
                JOIN practitioner_profiles practitioner
                  ON practitioner.organization_id=encounter.organization_id
                 AND practitioner.id=encounter.responsible_practitioner_id
                WHERE encounter.organization_id=?
                  AND (CAST(? AS uuid) IS NULL OR encounter.patient_id=CAST(? AS uuid))
                  AND (CAST(? AS uuid) IS NULL OR encounter.episode_of_care_id=CAST(? AS uuid))
                  AND (CAST(? AS uuid) IS NULL OR encounter.id=CAST(? AS uuid))
                  AND (CAST(? AS uuid) IS NULL OR encounter.source_appointment_id=CAST(? AS uuid))
                  AND (CAST(? AS text) IS NULL OR encounter.status=CAST(? AS text))
                  AND (CAST(? AS text) IS NULL OR
                       position(lower(CAST(? AS text)) in lower(
                         patient.patient_number||' '||coalesce(patient.name_to_use,'')||' '||
                         coalesce(patient.official_given_name,'')||' '||coalesce(patient.official_family_name,'')||' '||
                         encounter.encounter_type_key))>0)
                ORDER BY encounter.planned_start_at DESC,encounter.id
                LIMIT ?
                """,
                (resultSet, rowNumber) -> row(
                        query.screenId(),
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("patient_id", UUID.class),
                        resultSet.getObject("episode_of_care_id", UUID.class),
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("source_appointment_id", UUID.class),
                        resultSet.getString("status"),
                        resultSet.getLong("lock_version"),
                        values(
                                "$kind", "encounter",
                                "primary", "Encounter " + shortId(resultSet.getObject("id", UUID.class)),
                                "secondary", resultSet.getString("patient_label") + " · "
                                        + maskPatientNumber(resultSet.getString("patient_number")),
                                "context", resultSet.getString("service_name") + " · "
                                        + resultSet.getString("facility_name") + " · "
                                        + resultSet.getString("location_name"),
                                "clinician", resultSet.getString("clinical_title"),
                                "encounterType", resultSet.getString("encounter_type_key"),
                                "plannedStart", instant(resultSet.getTimestamp("planned_start_at")))),
                context.organizationId(),
                query.patientId(),
                query.patientId(),
                query.episodeId(),
                query.episodeId(),
                query.encounterId(),
                query.encounterId(),
                query.appointmentId(),
                query.appointmentId(),
                query.status(),
                query.status(),
                search,
                search,
                query.limit());
    }

    private List<EncounterScreen.Row> participantRows(
            AuthorizedTenantContext context, ScreenQuery query) {
        return jdbc.query(
                """
                SELECT participant.id,participant.encounter_id,encounter.patient_id,
                       encounter.episode_of_care_id,encounter.source_appointment_id,
                       participant.participant_type,participant.role_key,
                       participant.display_name_snapshot,participant.role_snapshot,
                       participant.status,participant.added_at,participant.removed_at,
                       participant.practitioner_profile_id,participant.lock_version
                FROM encounter_participants participant
                JOIN encounters encounter ON encounter.organization_id=participant.organization_id
                 AND encounter.id=participant.encounter_id
                WHERE participant.organization_id=?
                  AND (CAST(? AS uuid) IS NULL OR participant.encounter_id=CAST(? AS uuid))
                  AND (CAST(? AS uuid) IS NULL OR encounter.patient_id=CAST(? AS uuid))
                ORDER BY participant.added_at,participant.id
                LIMIT ?
                """,
                (resultSet, rowNumber) -> row(
                        query.screenId(),
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("patient_id", UUID.class),
                        resultSet.getObject("episode_of_care_id", UUID.class),
                        resultSet.getObject("encounter_id", UUID.class),
                        resultSet.getObject("source_appointment_id", UUID.class),
                        resultSet.getString("status"),
                        resultSet.getLong("lock_version"),
                        values(
                                "$kind", "participant",
                                "primary", resultSet.getString("display_name_snapshot"),
                                "secondary", resultSet.getString("role_snapshot"),
                                "context", resultSet.getString("participant_type"),
                                "role", resultSet.getString("role_key"),
                                "addedAt", instant(resultSet.getTimestamp("added_at")))),
                context.organizationId(),
                query.encounterId(),
                query.encounterId(),
                query.patientId(),
                query.patientId(),
                query.limit());
    }

    private List<EncounterScreen.Row> concernRows(
            AuthorizedTenantContext context, ScreenQuery query) {
        return jdbc.query(
                """
                SELECT concern.id,concern.encounter_id,encounter.patient_id,
                       encounter.episode_of_care_id,encounter.source_appointment_id,
                       concern.concern_kind,concern.description_text,concern.onset_text,
                       concern.severity_key,concern.red_flag,concern.recorded_at,
                       concern.status,concern.lock_version
                FROM presenting_concerns concern
                JOIN encounters encounter ON encounter.organization_id=concern.organization_id
                 AND encounter.id=concern.encounter_id
                WHERE concern.organization_id=?
                  AND (CAST(? AS uuid) IS NULL OR concern.encounter_id=CAST(? AS uuid))
                  AND (CAST(? AS uuid) IS NULL OR encounter.patient_id=CAST(? AS uuid))
                ORDER BY concern.recorded_at DESC,concern.id
                LIMIT ?
                """,
                (resultSet, rowNumber) -> row(
                        query.screenId(),
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("patient_id", UUID.class),
                        resultSet.getObject("episode_of_care_id", UUID.class),
                        resultSet.getObject("encounter_id", UUID.class),
                        resultSet.getObject("source_appointment_id", UUID.class),
                        resultSet.getString("status"),
                        resultSet.getLong("lock_version"),
                        values(
                                "$kind", "concern",
                                "primary", resultSet.getString("description_text"),
                                "secondary", resultSet.getString("concern_kind"),
                                "context", resultSet.getBoolean("red_flag")
                                        ? "RED FLAG · " + safe(resultSet.getString("severity_key"), "critical")
                                        : safe(resultSet.getString("severity_key"), "Not graded"),
                                "onset", safe(resultSet.getString("onset_text"), "Not recorded"),
                                "recordedAt", instant(resultSet.getTimestamp("recorded_at")))),
                context.organizationId(),
                query.encounterId(),
                query.encounterId(),
                query.patientId(),
                query.patientId(),
                query.limit());
    }

    private List<EncounterScreen.Row> problemRows(
            AuthorizedTenantContext context, ScreenQuery query) {
        return jdbc.query(
                """
                SELECT item.* FROM (
                    SELECT problem.id,problem.encounter_id,problem.patient_id,
                           encounter.episode_of_care_id,encounter.source_appointment_id,
                           'problem' item_kind,problem.display_text primary_text,
                           problem.clinical_status secondary_text,
                           problem.verification_status context_text,
                           problem.recorded_at effective_at,problem.status,problem.lock_version
                    FROM clinical_problems problem
                    JOIN encounters encounter ON encounter.organization_id=problem.organization_id
                     AND encounter.id=problem.encounter_id
                    WHERE problem.organization_id=?
                    UNION ALL
                    SELECT diagnosis.id,diagnosis.encounter_id,diagnosis.patient_id,
                           encounter.episode_of_care_id,encounter.source_appointment_id,
                           'diagnosis',diagnosis.display_text,diagnosis.diagnosis_type,
                           diagnosis.certainty_key,diagnosis.recorded_at,diagnosis.status,
                           diagnosis.lock_version
                    FROM diagnoses diagnosis
                    JOIN encounters encounter ON encounter.organization_id=diagnosis.organization_id
                     AND encounter.id=diagnosis.encounter_id
                    WHERE diagnosis.organization_id=?
                ) item
                WHERE (CAST(? AS uuid) IS NULL OR item.encounter_id=CAST(? AS uuid))
                  AND (CAST(? AS uuid) IS NULL OR item.patient_id=CAST(? AS uuid))
                ORDER BY item.effective_at DESC,item.id
                LIMIT ?
                """,
                (resultSet, rowNumber) -> row(
                        query.screenId(),
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("patient_id", UUID.class),
                        resultSet.getObject("episode_of_care_id", UUID.class),
                        resultSet.getObject("encounter_id", UUID.class),
                        resultSet.getObject("source_appointment_id", UUID.class),
                        resultSet.getString("status"),
                        resultSet.getLong("lock_version"),
                        values(
                                "$kind", resultSet.getString("item_kind"),
                                "primary", resultSet.getString("primary_text"),
                                "secondary", resultSet.getString("secondary_text"),
                                "context", resultSet.getString("context_text"),
                                "recordedAt", instant(resultSet.getTimestamp("effective_at")))),
                context.organizationId(),
                context.organizationId(),
                query.encounterId(),
                query.encounterId(),
                query.patientId(),
                query.patientId(),
                query.limit());
    }

    private List<EncounterScreen.Row> workRows(
            AuthorizedTenantContext context, ScreenQuery query) {
        return jdbc.query(
                """
                SELECT item.* FROM (
                    SELECT clinical_order.id,clinical_order.encounter_id,clinical_order.patient_id,
                           encounter.episode_of_care_id,encounter.source_appointment_id,
                           'order' item_kind,clinical_order.display_text primary_text,
                           clinical_order.order_type_key secondary_text,
                           clinical_order.priority_key context_text,
                           clinical_order.requested_at effective_at,clinical_order.status,
                           clinical_order.lock_version
                    FROM orders clinical_order
                    JOIN encounters encounter ON encounter.organization_id=clinical_order.organization_id
                     AND encounter.id=clinical_order.encounter_id
                    WHERE clinical_order.organization_id=?
                    UNION ALL
                    SELECT task.id,task.encounter_id,task.patient_id,
                           encounter.episode_of_care_id,encounter.source_appointment_id,
                           'task',task.description_text,task.task_type_key,
                           task.priority_key,task.created_at,task.status,task.lock_version
                    FROM clinical_tasks task
                    JOIN encounters encounter ON encounter.organization_id=task.organization_id
                     AND encounter.id=task.encounter_id
                    WHERE task.organization_id=?
                    UNION ALL
                    SELECT escalation.id,escalation.encounter_id,escalation.patient_id,
                           encounter.episode_of_care_id,encounter.source_appointment_id,
                           'escalation','Red-flag escalation',escalation.severity_key,
                           escalation.policy_version,escalation.raised_at,escalation.status,
                           escalation.lock_version
                    FROM red_flag_escalations escalation
                    JOIN encounters encounter ON encounter.organization_id=escalation.organization_id
                     AND encounter.id=escalation.encounter_id
                    WHERE escalation.organization_id=?
                ) item
                WHERE (CAST(? AS uuid) IS NULL OR item.encounter_id=CAST(? AS uuid))
                  AND (CAST(? AS uuid) IS NULL OR item.patient_id=CAST(? AS uuid))
                  AND (CAST(? AS text) IS NULL OR item.status=CAST(? AS text))
                ORDER BY item.effective_at DESC,item.id
                LIMIT ?
                """,
                (resultSet, rowNumber) -> row(
                        query.screenId(),
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("patient_id", UUID.class),
                        resultSet.getObject("episode_of_care_id", UUID.class),
                        resultSet.getObject("encounter_id", UUID.class),
                        resultSet.getObject("source_appointment_id", UUID.class),
                        resultSet.getString("status"),
                        resultSet.getLong("lock_version"),
                        values(
                                "$kind", resultSet.getString("item_kind"),
                                "primary", resultSet.getString("primary_text"),
                                "secondary", resultSet.getString("secondary_text"),
                                "context", resultSet.getString("context_text"),
                                "recordedAt", instant(resultSet.getTimestamp("effective_at")))),
                context.organizationId(),
                context.organizationId(),
                context.organizationId(),
                query.encounterId(),
                query.encounterId(),
                query.patientId(),
                query.patientId(),
                query.status(),
                query.status(),
                query.limit());
    }

    private List<EncounterScreen.Row> noteRows(
            AuthorizedTenantContext context, ScreenQuery query) {
        return jdbc.query(
                """
                SELECT note.id,note.encounter_id,encounter.patient_id,encounter.episode_of_care_id,
                       encounter.source_appointment_id,note.note_type_key,note.regulated_content,
                       note.current_version_number,note.status,note.lock_version,
                       version.id version_id,version.content_text,version.content_digest,
                       version.late_entry,version.recorded_at,
                       EXISTS(SELECT 1 FROM encounter_signatures signature
                         WHERE signature.organization_id=note.organization_id
                           AND signature.note_version_id=version.id) signed,
                       (SELECT count(*) FROM amendments amendment
                         WHERE amendment.organization_id=note.organization_id
                           AND amendment.encounter_note_id=note.id) amendment_count
                FROM encounter_notes note
                JOIN encounters encounter ON encounter.organization_id=note.organization_id
                 AND encounter.id=note.encounter_id
                LEFT JOIN note_versions version ON version.organization_id=note.organization_id
                 AND version.id=note.current_version_id
                WHERE note.organization_id=?
                  AND (CAST(? AS uuid) IS NULL OR note.encounter_id=CAST(? AS uuid))
                  AND (CAST(? AS uuid) IS NULL OR encounter.patient_id=CAST(? AS uuid))
                  AND (CAST(? AS text) IS NULL OR note.status=CAST(? AS text))
                ORDER BY coalesce(version.recorded_at,note.created_at) DESC,note.id
                LIMIT ?
                """,
                (resultSet, rowNumber) -> row(
                        query.screenId(),
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("patient_id", UUID.class),
                        resultSet.getObject("episode_of_care_id", UUID.class),
                        resultSet.getObject("encounter_id", UUID.class),
                        resultSet.getObject("source_appointment_id", UUID.class),
                        resultSet.getString("status"),
                        resultSet.getLong("lock_version"),
                        values(
                                "$kind", "note",
                                "primary", resultSet.getString("note_type_key") + " · version "
                                        + resultSet.getInt("current_version_number"),
                                "secondary", safe(resultSet.getString("content_text"), "No version recorded"),
                                "context", resultSet.getBoolean("signed") ? "Signed" : "Draft",
                                "digest", safe(resultSet.getString("content_digest"), "Not available"),
                                "lateEntry", Boolean.toString(resultSet.getBoolean("late_entry")),
                                "amendments", Long.toString(resultSet.getLong("amendment_count")),
                                "recordedAt", instant(resultSet.getTimestamp("recorded_at")))),
                context.organizationId(),
                query.encounterId(),
                query.encounterId(),
                query.patientId(),
                query.patientId(),
                query.status(),
                query.status(),
                query.limit());
    }

    private List<EncounterScreen.Row> timelineRows(
            AuthorizedTenantContext context, ScreenQuery query) {
        return evidenceRows(context, query, false);
    }

    private List<EncounterScreen.Row> historyRows(
            AuthorizedTenantContext context, ScreenQuery query) {
        return evidenceRows(context, query, true);
    }

    private List<EncounterScreen.Row> evidenceRows(
            AuthorizedTenantContext context, ScreenQuery query, boolean governanceOnly) {
        return jdbc.query(
                """
                SELECT evidence.* FROM (
                    SELECT history.id,history.encounter_id,encounter.patient_id,
                           encounter.episode_of_care_id,encounter.source_appointment_id,
                           'status' evidence_kind,
                           'Encounter status' primary_text,
                           coalesce(history.from_status,'opened')||' → '||history.to_status secondary_text,
                           history.reason_code context_text,history.effective_at,
                           history.to_status status,history.lock_version
                    FROM encounter_status_history history
                    JOIN encounters encounter ON encounter.organization_id=history.organization_id
                     AND encounter.id=history.encounter_id
                    WHERE history.organization_id=?
                    UNION ALL
                    SELECT signature.id,signature.encounter_id,encounter.patient_id,
                           encounter.episode_of_care_id,encounter.source_appointment_id,
                           'signature','Clinical note signed',signature.signature_meaning,
                           left(signature.signed_content_digest,16),signature.signed_at,
                           signature.status,signature.lock_version
                    FROM encounter_signatures signature
                    JOIN encounters encounter ON encounter.organization_id=signature.organization_id
                     AND encounter.id=signature.encounter_id
                    WHERE signature.organization_id=?
                    UNION ALL
                    SELECT amendment.id,amendment.encounter_id,encounter.patient_id,
                           encounter.episode_of_care_id,encounter.source_appointment_id,
                           'amendment','Signed amendment',left(amendment.amendment_digest,16),
                           amendment.reason_text,amendment.amended_at,amendment.status,
                           amendment.lock_version
                    FROM amendments amendment
                    JOIN encounters encounter ON encounter.organization_id=amendment.organization_id
                     AND encounter.id=amendment.encounter_id
                    WHERE amendment.organization_id=?
                    UNION ALL
                    SELECT escalation.id,escalation.encounter_id,encounter.patient_id,
                           encounter.episode_of_care_id,encounter.source_appointment_id,
                           'red_flag','Red-flag escalation',escalation.status,
                           escalation.severity_key,escalation.raised_at,escalation.status,
                           escalation.lock_version
                    FROM red_flag_escalations escalation
                    JOIN encounters encounter ON encounter.organization_id=escalation.organization_id
                     AND encounter.id=escalation.encounter_id
                    WHERE escalation.organization_id=?
                ) evidence
                WHERE (CAST(? AS uuid) IS NULL OR evidence.encounter_id=CAST(? AS uuid))
                  AND (CAST(? AS uuid) IS NULL OR evidence.patient_id=CAST(? AS uuid))
                ORDER BY evidence.effective_at DESC,evidence.id
                LIMIT ?
                """,
                (resultSet, rowNumber) -> row(
                        query.screenId(),
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("patient_id", UUID.class),
                        resultSet.getObject("episode_of_care_id", UUID.class),
                        resultSet.getObject("encounter_id", UUID.class),
                        resultSet.getObject("source_appointment_id", UUID.class),
                        resultSet.getString("status"),
                        resultSet.getLong("lock_version"),
                        values(
                                "$kind", "evidence",
                                "primary", resultSet.getString("primary_text"),
                                "secondary", resultSet.getString("secondary_text"),
                                "context", resultSet.getString("context_text"),
                                "effectiveAt", instant(resultSet.getTimestamp("effective_at")))),
                context.organizationId(),
                context.organizationId(),
                context.organizationId(),
                context.organizationId(),
                query.encounterId(),
                query.encounterId(),
                query.patientId(),
                query.patientId(),
                query.limit());
    }

    private List<EncounterScreen.Metric> metrics(AuthorizedTenantContext context) {
        var values = jdbc.queryForMap(
                """
                SELECT
                  (SELECT count(*) FROM encounters WHERE organization_id=?
                     AND status IN ('planned','arrived','in_progress','on_hold')) active_encounters,
                  (SELECT count(*) FROM red_flag_escalations WHERE organization_id=?
                     AND status<>'resolved') unresolved_red_flags,
                  (SELECT count(*) FROM encounter_notes WHERE organization_id=?
                     AND status='draft' AND current_version_id IS NOT NULL) unsigned_notes,
                  (SELECT count(*) FROM clinical_tasks WHERE organization_id=?
                     AND status IN ('open','in_progress','acknowledged')) open_tasks
                """,
                context.organizationId(),
                context.organizationId(),
                context.organizationId(),
                context.organizationId());
        return List.of(
                metric("active", "Active encounters", number(values.get("active_encounters")), "neutral"),
                metric("redFlags", "Unresolved red flags", number(values.get("unresolved_red_flags")), "danger"),
                metric("unsigned", "Unsigned notes", number(values.get("unsigned_notes")), "warning"),
                metric("tasks", "Open tasks", number(values.get("open_tasks")), "info"));
    }

    private MutationResult openEncounter(
            AuthorizedTenantContext context, MutationCommand command) {
        var patientId = fieldUuid(command, "patientId");
        var appointmentId = firstNonNull(
                optionalFieldUuid(command, "appointmentId"), command.appointmentId());
        var plannedStartAt = fieldInstant(command, "plannedStartAt");
        var encounterType = field(command, "encounterType");
        requireOneOf(encounterType, "encounterType", "consultation", "follow_up", "procedure", "remote");

        UUID serviceId;
        UUID facilityId;
        UUID locationId;
        UUID practitionerId;
        if (appointmentId != null) {
            var appointment = appointmentContext(context, appointmentId);
            if (!appointment.patientId().equals(patientId)) {
                throw conflict("The selected appointment belongs to a different patient.");
            }
            if (!appointment.startsAt().equals(plannedStartAt)) {
                throw conflict("The planned encounter start must match the confirmed appointment instant.");
            }
            serviceId = appointment.serviceId();
            facilityId = appointment.facilityId();
            locationId = appointment.locationId();
            practitionerId = appointment.practitionerId();
        } else {
            serviceId = fieldUuid(command, "serviceId");
            facilityId = fieldUuid(command, "facilityId");
            locationId = fieldUuid(command, "locationId");
            practitionerId = fieldUuid(command, "responsiblePractitionerId");
        }
        requireActivePatient(context, patientId);
        var eligibility = eligibility(
                context, practitionerId, serviceId, facilityId, locationId, plannedStartAt);
        var episodeId = optionalFieldUuid(command, "episodeId");
        if (episodeId == null) {
            episodeId = UuidV7Generator.randomUuid();
            jdbc.update(
                    """
                    INSERT INTO episodes_of_care
                        (id,organization_id,patient_id,service_id,facility_id,location_id,
                         managing_practitioner_id,status,started_at,policy_version,
                         created_by,updated_by)
                    VALUES (?,?,?,?,?,?,?,'active',?,?,?,?)
                    """,
                    episodeId,
                    context.organizationId(),
                    patientId,
                    serviceId,
                    facilityId,
                    locationId,
                    practitionerId,
                    Timestamp.from(plannedStartAt),
                    POLICY_VERSION,
                    context.actorId(),
                    context.actorId());
        } else {
            var matches = jdbc.query(
                    """
                    SELECT id FROM episodes_of_care
                    WHERE organization_id=? AND id=? AND patient_id=? AND service_id=?
                      AND facility_id=? AND location_id=? AND status='active'
                    FOR SHARE
                    """,
                    (resultSet, rowNumber) -> resultSet.getObject("id", UUID.class),
                    context.organizationId(),
                    episodeId,
                    patientId,
                    serviceId,
                    facilityId,
                    locationId);
            if (matches.size() != 1) {
                throw conflict("The selected episode is unavailable or does not match the encounter context.");
            }
        }

        var encounterId = UuidV7Generator.randomUuid();
        jdbc.update(
                """
                INSERT INTO encounters
                    (id,organization_id,episode_of_care_id,source_appointment_id,patient_id,
                     service_id,facility_id,location_id,responsible_practitioner_id,
                     source_kind,encounter_type_key,status,planned_start_at,policy_version,
                     created_by,updated_by)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,'planned',?,?,?,?)
                """,
                encounterId,
                context.organizationId(),
                episodeId,
                appointmentId,
                patientId,
                serviceId,
                facilityId,
                locationId,
                practitionerId,
                appointmentId == null ? "unscheduled" : "appointment",
                encounterType,
                Timestamp.from(plannedStartAt),
                POLICY_VERSION,
                context.actorId(),
                context.actorId());

        insertPatientParticipant(context, encounterId, patientId);
        insertPractitionerParticipant(
                context,
                encounterId,
                practitionerId,
                "responsible_clinician",
                "Responsible clinician",
                eligibility,
                plannedStartAt);
        insertStatusHistory(context, encounterId, null, "planned", "encounter_opened", command);
        var audit = map(
                "encounterId", encounterId,
                "episodeId", episodeId,
                "patientId", patientId,
                "appointmentId", appointmentId,
                "sourceKind", appointmentId == null ? "unscheduled" : "appointment",
                "status", "planned",
                "revision", 0L);
        var outbox = mapWithoutNulls(
                "encounterId", encounterId,
                "episodeId", episodeId,
                "patientId", patientId,
                "appointmentId", appointmentId,
                "sourceKind", appointmentId == null ? "unscheduled" : "appointment");
        return result(
                encounterId,
                patientId,
                episodeId,
                encounterId,
                appointmentId,
                "encounter",
                "encounter.opened",
                "m5.encounter.opened.v1",
                "encounter",
                audit,
                outbox,
                201,
                0);
    }

    private MutationResult transitionEncounter(
            AuthorizedTenantContext context, MutationCommand command) {
        var encounter = encounter(context, command.targetId(), true);
        requireRevision(encounter.revision(), command.expectedRevision());
        var next = switch (command.actionKey()) {
            case "mark-arrived" -> "arrived";
            case "start-encounter", "resume-encounter" -> "in_progress";
            case "place-on-hold" -> "on_hold";
            case "complete-encounter" -> "completed";
            case "cancel-encounter" -> "cancelled";
            case "enter-encounter-in-error" -> "entered_in_error";
            default -> throw invalid("Unsupported encounter lifecycle action.");
        };
        requireLifecycleTransition(encounter.status(), next);
        var changed = jdbc.update(
                """
                UPDATE encounters
                SET status=?,
                    arrived_at=CASE WHEN ?='arrived' THEN clock_timestamp() ELSE arrived_at END,
                    in_progress_at=CASE WHEN ?='in_progress' AND in_progress_at IS NULL
                                        THEN clock_timestamp() ELSE in_progress_at END,
                    on_hold_at=CASE WHEN ?='on_hold' THEN clock_timestamp() ELSE on_hold_at END,
                    completed_at=CASE WHEN ?='completed' THEN clock_timestamp() ELSE completed_at END,
                    cancelled_at=CASE WHEN ?='cancelled' THEN clock_timestamp() ELSE cancelled_at END,
                    entered_in_error_at=CASE WHEN ?='entered_in_error'
                                             THEN clock_timestamp() ELSE entered_in_error_at END,
                    lock_version=lock_version+1,updated_at=clock_timestamp(),updated_by=?
                WHERE organization_id=? AND id=? AND lock_version=?
                """,
                next,
                next,
                next,
                next,
                next,
                next,
                next,
                context.actorId(),
                context.organizationId(),
                encounter.id(),
                encounter.revision());
        if (changed != 1) throw stale("The encounter changed before the lifecycle transition completed.");
        insertStatusHistory(
                context,
                encounter.id(),
                encounter.status(),
                next,
                codeReason(command.reason()),
                command);
        var revision = encounter.revision() + 1;
        var audit = map(
                "encounterId", encounter.id(),
                "patientId", encounter.patientId(),
                "fromStatus", encounter.status(),
                "toStatus", next,
                "revision", revision);
        var outbox = map(
                "encounterId", encounter.id(),
                "patientId", encounter.patientId(),
                "fromStatus", encounter.status(),
                "toStatus", next);
        return result(
                encounter.id(),
                encounter.patientId(),
                encounter.episodeId(),
                encounter.id(),
                encounter.appointmentId(),
                "encounter",
                "encounter.status.changed",
                "m5.encounter.status-changed.v1",
                "encounter",
                audit,
                outbox,
                200,
                revision);
    }

    private MutationResult addParticipant(
            AuthorizedTenantContext context, MutationCommand command) {
        var encounter = encounter(context, command.targetId(), true);
        requireRevision(encounter.revision(), command.expectedRevision());
        requireOpenClinicalState(encounter);
        var practitionerId = fieldUuid(command, "practitionerId");
        var roleKey = field(command, "roleKey");
        requireOneOf(roleKey, "roleKey", "attending", "consulting", "observer");
        var eligibility = eligibility(
                context,
                practitionerId,
                encounter.serviceId(),
                encounter.facilityId(),
                encounter.locationId(),
                databaseNow());
        var participantId = insertPractitionerParticipant(
                context,
                encounter.id(),
                practitionerId,
                roleKey,
                roleLabel(roleKey),
                eligibility,
                databaseNow());
        var audit = map(
                "participantId", participantId,
                "encounterId", encounter.id(),
                "participantType", "practitioner",
                "roleKey", roleKey,
                "practitionerId", practitionerId,
                "revision", 0L);
        return result(
                participantId,
                encounter.patientId(),
                encounter.episodeId(),
                encounter.id(),
                encounter.appointmentId(),
                "encounter_participant",
                "encounter.participant.added",
                null,
                "encounter_participant",
                audit,
                Map.of(),
                201,
                0);
    }

    private MutationResult removeParticipant(
            AuthorizedTenantContext context, MutationCommand command) {
        var participant = participant(context, command.targetId(), true);
        requireRevision(participant.revision(), command.expectedRevision());
        if (!participant.status().equals("active")) {
            throw conflict("Only an active encounter participant can be removed.");
        }
        if (participant.roleKey().equals("subject_of_care")
                || participant.roleKey().equals("responsible_clinician")) {
            throw conflict("The patient and responsible clinician are required encounter participants.");
        }
        requireOpenClinicalState(encounter(context, participant.encounterId(), false));
        var changed = jdbc.update(
                """
                UPDATE encounter_participants
                SET status='removed',removed_at=clock_timestamp(),lock_version=lock_version+1,
                    updated_at=clock_timestamp(),updated_by=?
                WHERE organization_id=? AND id=? AND lock_version=?
                """,
                context.actorId(),
                context.organizationId(),
                participant.id(),
                participant.revision());
        if (changed != 1) throw stale("The participant changed before removal completed.");
        var encounter = encounter(context, participant.encounterId(), false);
        var revision = participant.revision() + 1;
        var audit = map(
                "participantId", participant.id(),
                "encounterId", participant.encounterId(),
                "roleKey", participant.roleKey(),
                "revision", revision);
        return result(
                participant.id(),
                encounter.patientId(),
                encounter.episodeId(),
                encounter.id(),
                encounter.appointmentId(),
                "encounter_participant",
                "encounter.participant.removed",
                null,
                "encounter_participant",
                audit,
                Map.of(),
                200,
                revision);
    }

    private MutationResult recordConcern(
            AuthorizedTenantContext context, MutationCommand command) {
        var encounter = encounter(context, command.targetId(), true);
        requireRevision(encounter.revision(), command.expectedRevision());
        requireDocumentableState(encounter);
        var authorId = fieldUuid(command, "authorPractitionerId");
        var concernKind = field(command, "concernKind");
        requireOneOf(
                concernKind,
                "concernKind",
                "presenting",
                "symptom",
                "referral_reason",
                "red_flag");
        var description = bounded(field(command, "description"), 2, 4000, "description");
        var onset = optionalBounded(command.fields().get("onset"), 240, "onset");
        var severity = optionalBounded(command.fields().get("severityKey"), 40, "severityKey");
        if (severity != null) {
            requireOneOf(severity, "severityKey", "mild", "moderate", "severe", "critical");
        }
        var redFlag = concernKind.equals("red_flag");
        if (redFlag && !("severe".equals(severity) || "critical".equals(severity))) {
            throw invalid("A red flag requires severe or critical severity.");
        }
        var concernId = UuidV7Generator.randomUuid();
        jdbc.update(
                """
                INSERT INTO presenting_concerns
                    (id,organization_id,encounter_id,concern_kind,description_text,onset_text,
                     severity_key,red_flag,author_practitioner_id,recorded_at,status,
                     created_by,updated_by)
                VALUES (?,?,?,?,?,?,?,?,?,clock_timestamp(),'active',?,?)
                """,
                concernId,
                context.organizationId(),
                encounter.id(),
                concernKind,
                description,
                onset,
                severity,
                redFlag,
                authorId,
                context.actorId(),
                context.actorId());
        UUID escalationId = null;
        UUID taskId = null;
        if (redFlag) {
            taskId = UuidV7Generator.randomUuid();
            jdbc.update(
                    """
                    INSERT INTO clinical_tasks
                        (id,organization_id,encounter_id,patient_id,source_concern_id,
                         task_type_key,description_text,priority_key,owner_practitioner_id,
                         requires_acknowledgement,status,created_by,updated_by)
                    VALUES (?,?,?,?,?,'red_flag_escalation',?,'critical',?,true,'open',?,?)
                    """,
                    taskId,
                    context.organizationId(),
                    encounter.id(),
                    encounter.patientId(),
                    concernId,
                    "Acknowledge and resolve the recorded red flag.",
                    encounter.practitionerId(),
                    context.actorId(),
                    context.actorId());
            escalationId = UuidV7Generator.randomUuid();
            jdbc.update(
                    """
                    INSERT INTO red_flag_escalations
                        (id,organization_id,encounter_id,patient_id,presenting_concern_id,
                         clinical_task_id,severity_key,policy_version,raised_at,
                         raised_by_practitioner_id,status,created_by,updated_by)
                    VALUES (?,?,?,?,?,?,?,?,clock_timestamp(),?,'raised',?,?)
                    """,
                    escalationId,
                    context.organizationId(),
                    encounter.id(),
                    encounter.patientId(),
                    concernId,
                    taskId,
                    severity,
                    POLICY_VERSION,
                    authorId,
                    context.actorId(),
                    context.actorId());
        }
        var contentDigest = digest(concernKind + "\n" + description + "\n" + safe(onset, "") + "\n"
                + safe(severity, ""));
        var audit = mapWithoutNulls(
                "concernId", concernId,
                "encounterId", encounter.id(),
                "redFlag", redFlag,
                "severityKey", severity,
                "contentDigest", contentDigest);
        var outbox = redFlag
                ? map(
                        "escalationId", escalationId,
                        "encounterId", encounter.id(),
                        "patientId", encounter.patientId(),
                        "severityKey", severity,
                        "taskId", taskId)
                : Map.<String, Object>of();
        return result(
                concernId,
                encounter.patientId(),
                encounter.episodeId(),
                encounter.id(),
                encounter.appointmentId(),
                "presenting_concern",
                "encounter.concern.recorded",
                redFlag ? "m5.red-flag.raised.v1" : null,
                redFlag ? "red_flag_escalation" : "presenting_concern",
                escalationId,
                audit,
                outbox,
                201,
                0);
    }

    private MutationResult recordProblem(
            AuthorizedTenantContext context, MutationCommand command) {
        var encounter = encounter(context, command.targetId(), true);
        requireRevision(encounter.revision(), command.expectedRevision());
        requireDocumentableState(encounter);
        var authorId = fieldUuid(command, "authorPractitionerId");
        var codeSystem = optionalBounded(command.fields().get("codeSystem"), 240, "codeSystem");
        var codeValue = optionalBounded(command.fields().get("codeValue"), 120, "codeValue");
        requireCodePair(codeSystem, codeValue);
        var display = bounded(field(command, "displayText"), 2, 300, "displayText");
        var clinicalStatus = field(command, "clinicalStatus");
        requireOneOf(clinicalStatus, "clinicalStatus", "active", "inactive", "resolved");
        var verification = field(command, "verificationStatus");
        requireOneOf(
                verification, "verificationStatus", "provisional", "confirmed", "refuted");
        var id = UuidV7Generator.randomUuid();
        jdbc.update(
                """
                INSERT INTO clinical_problems
                    (id,organization_id,encounter_id,patient_id,code_system,code_value,
                     display_text,clinical_status,verification_status,author_practitioner_id,
                     recorded_at,status,created_by,updated_by)
                VALUES (?,?,?,?,?,?,?,?,?,?,clock_timestamp(),?,?,?)
                """,
                id,
                context.organizationId(),
                encounter.id(),
                encounter.patientId(),
                codeSystem,
                codeValue,
                display,
                clinicalStatus,
                verification,
                authorId,
                clinicalStatus.equals("resolved") ? "resolved" : "active",
                context.actorId(),
                context.actorId());
        var contentDigest = digest(safe(codeSystem, "") + "\n" + safe(codeValue, "") + "\n" + display);
        var audit = map(
                "problemId", id,
                "encounterId", encounter.id(),
                "clinicalStatus", clinicalStatus,
                "verificationStatus", verification,
                "contentDigest", contentDigest);
        return result(
                id,
                encounter.patientId(),
                encounter.episodeId(),
                encounter.id(),
                encounter.appointmentId(),
                "clinical_problem",
                "encounter.problem.recorded",
                null,
                "clinical_problem",
                audit,
                Map.of(),
                201,
                0);
    }

    private MutationResult recordDiagnosis(
            AuthorizedTenantContext context, MutationCommand command) {
        var encounter = encounter(context, command.targetId(), true);
        requireRevision(encounter.revision(), command.expectedRevision());
        requireDocumentableState(encounter);
        var authorId = fieldUuid(command, "authorPractitionerId");
        var problemId = optionalFieldUuid(command, "clinicalProblemId");
        if (problemId != null
                && !belongsToEncounter(context, "clinical_problems", problemId, encounter.id())) {
            throw conflict("The related problem is unavailable in this encounter.");
        }
        var codeSystem = optionalBounded(command.fields().get("codeSystem"), 240, "codeSystem");
        var codeValue = optionalBounded(command.fields().get("codeValue"), 120, "codeValue");
        requireCodePair(codeSystem, codeValue);
        var display = bounded(field(command, "displayText"), 2, 300, "displayText");
        var certainty = field(command, "certaintyKey");
        requireOneOf(certainty, "certaintyKey", "suspected", "provisional", "confirmed", "refuted");
        var diagnosisType = field(command, "diagnosisType");
        requireOneOf(diagnosisType, "diagnosisType", "working", "differential", "final");
        var id = UuidV7Generator.randomUuid();
        jdbc.update(
                """
                INSERT INTO diagnoses
                    (id,organization_id,encounter_id,patient_id,clinical_problem_id,
                     code_system,code_value,display_text,certainty_key,diagnosis_type,
                     author_practitioner_id,recorded_at,status,created_by,updated_by)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,clock_timestamp(),'active',?,?)
                """,
                id,
                context.organizationId(),
                encounter.id(),
                encounter.patientId(),
                problemId,
                codeSystem,
                codeValue,
                display,
                certainty,
                diagnosisType,
                authorId,
                context.actorId(),
                context.actorId());
        var contentDigest = digest(safe(codeSystem, "") + "\n" + safe(codeValue, "") + "\n" + display);
        var audit = map(
                "diagnosisId", id,
                "encounterId", encounter.id(),
                "certaintyKey", certainty,
                "diagnosisType", diagnosisType,
                "contentDigest", contentDigest);
        return result(
                id,
                encounter.patientId(),
                encounter.episodeId(),
                encounter.id(),
                encounter.appointmentId(),
                "diagnosis",
                "encounter.diagnosis.recorded",
                null,
                "diagnosis",
                audit,
                Map.of(),
                201,
                0);
    }

    private MutationResult createOrder(
            AuthorizedTenantContext context, MutationCommand command) {
        var encounter = encounter(context, command.targetId(), true);
        requireRevision(encounter.revision(), command.expectedRevision());
        requireDocumentableState(encounter);
        var practitionerId = fieldUuid(command, "requesterPractitionerId");
        var orderType = boundedCode(field(command, "orderTypeKey"), "orderTypeKey");
        var codeSystem = optionalBounded(command.fields().get("codeSystem"), 240, "codeSystem");
        var codeValue = optionalBounded(command.fields().get("codeValue"), 120, "codeValue");
        requireCodePair(codeSystem, codeValue);
        var display = bounded(field(command, "displayText"), 2, 300, "displayText");
        var instructions = optionalBounded(command.fields().get("instructionText"), 4000, "instructionText");
        var priority = field(command, "priorityKey");
        requireOneOf(priority, "priorityKey", "routine", "urgent", "stat");
        var id = UuidV7Generator.randomUuid();
        jdbc.update(
                """
                INSERT INTO orders
                    (id,organization_id,encounter_id,patient_id,order_type_key,code_system,
                     code_value,display_text,instruction_text,priority_key,
                     requester_practitioner_id,requested_at,status,created_by,updated_by)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,clock_timestamp(),'active',?,?)
                """,
                id,
                context.organizationId(),
                encounter.id(),
                encounter.patientId(),
                orderType,
                codeSystem,
                codeValue,
                display,
                instructions,
                priority,
                practitionerId,
                context.actorId(),
                context.actorId());
        var contentDigest = digest(orderType + "\n" + display + "\n" + safe(instructions, ""));
        var audit = map(
                "orderId", id,
                "encounterId", encounter.id(),
                "priorityKey", priority,
                "status", "active",
                "contentDigest", contentDigest);
        var outbox = map(
                "orderId", id,
                "encounterId", encounter.id(),
                "patientId", encounter.patientId(),
                "priorityKey", priority);
        return result(
                id,
                encounter.patientId(),
                encounter.episodeId(),
                encounter.id(),
                encounter.appointmentId(),
                "clinical_order",
                "encounter.order.created",
                "m5.order.created.v1",
                "clinical_order",
                audit,
                outbox,
                201,
                0);
    }

    private MutationResult createTask(
            AuthorizedTenantContext context, MutationCommand command) {
        var encounter = encounter(context, command.targetId(), true);
        requireRevision(encounter.revision(), command.expectedRevision());
        requireDocumentableState(encounter);
        var ownerId = optionalFieldUuid(command, "ownerPractitionerId");
        var taskType = boundedCode(field(command, "taskTypeKey"), "taskTypeKey");
        var description = bounded(field(command, "description"), 2, 2000, "description");
        var priority = field(command, "priorityKey");
        requireOneOf(priority, "priorityKey", "routine", "urgent");
        var id = UuidV7Generator.randomUuid();
        jdbc.update(
                """
                INSERT INTO clinical_tasks
                    (id,organization_id,encounter_id,patient_id,task_type_key,
                     description_text,priority_key,owner_practitioner_id,
                     requires_acknowledgement,status,created_by,updated_by)
                VALUES (?,?,?,?,?,?,?,?,false,'open',?,?)
                """,
                id,
                context.organizationId(),
                encounter.id(),
                encounter.patientId(),
                taskType,
                description,
                priority,
                ownerId,
                context.actorId(),
                context.actorId());
        var contentDigest = digest(taskType + "\n" + description);
        var audit = map(
                "taskId", id,
                "encounterId", encounter.id(),
                "priorityKey", priority,
                "status", "open",
                "contentDigest", contentDigest);
        return result(
                id,
                encounter.patientId(),
                encounter.episodeId(),
                encounter.id(),
                encounter.appointmentId(),
                "clinical_task",
                "encounter.task.created",
                null,
                "clinical_task",
                audit,
                Map.of(),
                201,
                0);
    }

    private MutationResult progressTask(
            AuthorizedTenantContext context, MutationCommand command) {
        var task = task(context, command.targetId(), true);
        requireRevision(task.revision(), command.expectedRevision());
        if (task.sourceConcernId() != null) {
            throw conflict("Red-flag tasks require the explicit acknowledgement workflow.");
        }
        var next = field(command, "nextStatus");
        requireOneOf(next, "nextStatus", "in_progress", "completed", "cancelled", "entered_in_error");
        var changed = jdbc.update(
                """
                UPDATE clinical_tasks
                SET status=?,
                    completed_at=CASE WHEN ?='completed' THEN clock_timestamp() ELSE NULL END,
                    completion_reason=CASE WHEN ? IN ('completed','cancelled','entered_in_error')
                                           THEN ? ELSE NULL END,
                    lock_version=lock_version+1,updated_at=clock_timestamp(),updated_by=?
                WHERE organization_id=? AND id=? AND lock_version=?
                """,
                next,
                next,
                next,
                command.reason(),
                context.actorId(),
                context.organizationId(),
                task.id(),
                task.revision());
        if (changed != 1) throw stale("The clinical task changed before the transition completed.");
        var encounter = encounter(context, task.encounterId(), false);
        var revision = task.revision() + 1;
        var audit = map(
                "taskId", task.id(),
                "encounterId", task.encounterId(),
                "fromStatus", task.status(),
                "toStatus", next,
                "revision", revision);
        return result(
                task.id(),
                encounter.patientId(),
                encounter.episodeId(),
                encounter.id(),
                encounter.appointmentId(),
                "clinical_task",
                "encounter.task.progressed",
                null,
                "clinical_task",
                audit,
                Map.of(),
                200,
                revision);
    }

    private MutationResult progressRedFlag(
            AuthorizedTenantContext context, MutationCommand command) {
        var escalation = escalation(context, command.targetId(), true);
        requireRevision(escalation.revision(), command.expectedRevision());
        var practitionerId = fieldUuid(command, "practitionerId");
        requireActorPractitioner(context, practitionerId);
        var resolving = command.actionKey().equals("resolve-red-flag");
        var next = resolving ? "resolved" : "acknowledged";
        var changed = resolving
                ? jdbc.update(
                        """
                        UPDATE red_flag_escalations
                        SET status='resolved',resolved_at=clock_timestamp(),
                            resolved_by_practitioner_id=?,resolution_reason=?,
                            lock_version=lock_version+1,updated_at=clock_timestamp(),updated_by=?
                        WHERE organization_id=? AND id=? AND lock_version=?
                        """,
                        practitionerId,
                        command.reason(),
                        context.actorId(),
                        context.organizationId(),
                        escalation.id(),
                        escalation.revision())
                : jdbc.update(
                        """
                        UPDATE red_flag_escalations
                        SET status='acknowledged',acknowledged_at=clock_timestamp(),
                            acknowledged_by_practitioner_id=?,acknowledgement_reason=?,
                            lock_version=lock_version+1,updated_at=clock_timestamp(),updated_by=?
                        WHERE organization_id=? AND id=? AND lock_version=?
                        """,
                        practitionerId,
                        command.reason(),
                        context.actorId(),
                        context.organizationId(),
                        escalation.id(),
                        escalation.revision());
        if (changed != 1) throw stale("The red-flag escalation changed before the transition completed.");
        int taskChanged;
        if (resolving) {
            taskChanged = jdbc.update(
                    """
                    UPDATE clinical_tasks
                    SET status='completed',completed_at=clock_timestamp(),completion_reason=?,
                        lock_version=lock_version+1,updated_at=clock_timestamp(),updated_by=?
                    WHERE organization_id=? AND id=? AND status='acknowledged'
                    """,
                    command.reason(),
                    context.actorId(),
                    context.organizationId(),
                    escalation.taskId());
        } else {
            taskChanged = jdbc.update(
                    """
                    UPDATE clinical_tasks
                    SET status='acknowledged',acknowledged_at=clock_timestamp(),
                        acknowledged_by_practitioner_id=?,acknowledgement_reason=?,
                        lock_version=lock_version+1,updated_at=clock_timestamp(),updated_by=?
                    WHERE organization_id=? AND id=? AND status='open'
                    """,
                    practitionerId,
                    command.reason(),
                    context.actorId(),
                    context.organizationId(),
                    escalation.taskId());
        }
        if (taskChanged != 1) {
            throw stale("The linked red-flag task changed before the transition completed.");
        }
        var encounter = encounter(context, escalation.encounterId(), false);
        var revision = escalation.revision() + 1;
        var audit = map(
                "escalationId", escalation.id(),
                "encounterId", escalation.encounterId(),
                "fromStatus", escalation.status(),
                "toStatus", next,
                "revision", revision);
        var outbox = map(
                "escalationId", escalation.id(),
                "encounterId", escalation.encounterId(),
                "patientId", encounter.patientId(),
                "status", next);
        return result(
                escalation.id(),
                encounter.patientId(),
                encounter.episodeId(),
                encounter.id(),
                encounter.appointmentId(),
                "red_flag_escalation",
                resolving ? "encounter.red_flag.resolved" : "encounter.red_flag.acknowledged",
                "m5.red-flag.changed.v1",
                "red_flag_escalation",
                audit,
                outbox,
                200,
                revision);
    }

    private MutationResult saveNoteVersion(
            AuthorizedTenantContext context, MutationCommand command) {
        var suppliedNoteId = optionalFieldUuid(command, "noteId");
        if (suppliedNoteId == null
                && command.targetId() != null
                && noteExists(context, command.targetId())) {
            suppliedNoteId = command.targetId();
        }
        Encounter encounter;
        Note note;
        if (suppliedNoteId == null) {
            encounter = encounter(context, command.targetId(), true);
            requireRevision(encounter.revision(), command.expectedRevision());
            requireDocumentableState(encounter);
            var noteId = UuidV7Generator.randomUuid();
            var noteType = boundedCode(field(command, "noteTypeKey"), "noteTypeKey");
            jdbc.update(
                    """
                    INSERT INTO encounter_notes
                        (id,organization_id,encounter_id,note_type_key,regulated_content,
                         status,created_by,updated_by)
                    VALUES (?,?,?,?,true,'draft',?,?)
                    """,
                    noteId,
                    context.organizationId(),
                    encounter.id(),
                    noteType,
                    context.actorId(),
                    context.actorId());
            note = new Note(noteId, encounter.id(), noteType, null, 0, "draft", 0);
        } else {
            if (!suppliedNoteId.equals(command.targetId())) {
                throw invalid("targetId must identify the note being versioned.");
            }
            note = note(context, suppliedNoteId, true);
            requireRevision(note.revision(), command.expectedRevision());
            encounter = encounter(context, note.encounterId(), false);
            requireDocumentableState(encounter);
            if (!note.status().equals("draft")) {
                throw conflict("Signed notes cannot receive another draft version; create an amendment.");
            }
            var requestedType = boundedCode(field(command, "noteTypeKey"), "noteTypeKey");
            if (!requestedType.equals(note.noteType())) {
                throw conflict("The note type cannot change between append-only versions.");
            }
        }
        var authorId = fieldUuid(command, "authorPractitionerId");
        var content = bounded(field(command, "content"), 2, 20000, "content");
        var lateEntry = Boolean.parseBoolean(command.fields().getOrDefault("lateEntry", "false"));
        var digest = digest(content);
        var versionId = UuidV7Generator.randomUuid();
        var versionNumber = note.versionNumber() + 1;
        jdbc.update(
                """
                INSERT INTO note_versions
                    (id,organization_id,encounter_note_id,version_number,prior_version_id,
                     content_text,content_digest,author_practitioner_id,late_entry,
                     recorded_at,status,created_by,updated_by)
                VALUES (?,?,?,?,?,?,?,?,?,clock_timestamp(),'recorded',?,?)
                """,
                versionId,
                context.organizationId(),
                note.id(),
                versionNumber,
                note.versionId(),
                content,
                digest,
                authorId,
                lateEntry,
                context.actorId(),
                context.actorId());
        var changed = jdbc.update(
                """
                UPDATE encounter_notes
                SET current_version_id=?,current_version_number=?,
                    lock_version=lock_version+1,updated_at=clock_timestamp(),updated_by=?
                WHERE organization_id=? AND id=? AND lock_version=? AND status='draft'
                """,
                versionId,
                versionNumber,
                context.actorId(),
                context.organizationId(),
                note.id(),
                note.revision());
        if (changed != 1) throw stale("The note changed before the new version completed.");
        var revision = note.revision() + 1;
        var audit = map(
                "noteId", note.id(),
                "encounterId", encounter.id(),
                "versionId", versionId,
                "versionNumber", versionNumber,
                "contentDigest", digest,
                "lateEntry", lateEntry,
                "revision", revision);
        return result(
                note.id(),
                encounter.patientId(),
                encounter.episodeId(),
                encounter.id(),
                encounter.appointmentId(),
                "encounter_note",
                "encounter.note.versioned",
                null,
                "encounter_note",
                audit,
                Map.of(),
                suppliedNoteId == null ? 201 : 200,
                revision);
    }

    private MutationResult signNote(
            AuthorizedTenantContext context, MutationCommand command) {
        var note = note(context, command.targetId(), true);
        requireRevision(note.revision(), command.expectedRevision());
        if (!note.status().equals("draft") || note.versionId() == null) {
            throw conflict("Only an exact current draft note version can be signed.");
        }
        var encounter = encounter(context, note.encounterId(), false);
        requireDocumentableState(encounter);
        var practitionerId = fieldUuid(command, "signerPractitionerId");
        var meaning = field(command, "signatureMeaning");
        requireOneOf(meaning, "signatureMeaning", "author", "reviewer", "cosigner");
        var eligibility = eligibility(
                context,
                practitionerId,
                encounter.serviceId(),
                encounter.facilityId(),
                encounter.locationId(),
                databaseNow());
        var participantId = activePractitionerParticipant(context, encounter.id(), practitionerId);
        var version = noteVersion(context, note.versionId());
        var signatureId = UuidV7Generator.randomUuid();
        jdbc.update(
                """
                INSERT INTO encounter_signatures
                    (id,organization_id,encounter_id,encounter_note_id,note_version_id,
                     signer_participant_id,signer_practitioner_id,eligibility_evidence_id,
                     eligibility_digest,signature_meaning,signed_content_digest,signed_at,
                     status,created_by,updated_by)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,clock_timestamp(),'signed',?,?)
                """,
                signatureId,
                context.organizationId(),
                encounter.id(),
                note.id(),
                version.id(),
                participantId,
                practitionerId,
                eligibility.evidenceId(),
                eligibility.digest(),
                meaning,
                version.digest(),
                context.actorId(),
                context.actorId());
        var changed = jdbc.update(
                """
                UPDATE encounter_notes
                SET status='signed',lock_version=lock_version+1,
                    updated_at=clock_timestamp(),updated_by=?
                WHERE organization_id=? AND id=? AND lock_version=? AND status='draft'
                """,
                context.actorId(),
                context.organizationId(),
                note.id(),
                note.revision());
        if (changed != 1) throw stale("The note changed before signing completed.");
        var revision = note.revision() + 1;
        var audit = map(
                "noteId", note.id(),
                "encounterId", encounter.id(),
                "versionId", version.id(),
                "signatureId", signatureId,
                "signerPractitionerId", practitionerId,
                "contentDigest", version.digest(),
                "revision", revision);
        var outbox = map(
                "noteId", note.id(),
                "encounterId", encounter.id(),
                "versionId", version.id(),
                "signatureId", signatureId,
                "contentDigest", version.digest());
        return result(
                note.id(),
                encounter.patientId(),
                encounter.episodeId(),
                encounter.id(),
                encounter.appointmentId(),
                "encounter_note",
                "encounter.note.signed",
                "m5.note.signed.v1",
                "encounter_note",
                audit,
                outbox,
                200,
                revision);
    }

    private MutationResult amendNote(
            AuthorizedTenantContext context, MutationCommand command) {
        var note = note(context, command.targetId(), true);
        requireRevision(note.revision(), command.expectedRevision());
        if (!(note.status().equals("signed") || note.status().equals("amended"))
                || note.versionId() == null) {
            throw conflict("Only a signed note can receive an amendment.");
        }
        var encounter = encounter(context, note.encounterId(), false);
        var practitionerId = fieldUuid(command, "authorPractitionerId");
        var amendmentText = bounded(field(command, "amendmentText"), 2, 20000, "amendmentText");
        var eligibility = eligibility(
                context,
                practitionerId,
                encounter.serviceId(),
                encounter.facilityId(),
                encounter.locationId(),
                databaseNow());
        requireActorPractitioner(context, practitionerId);
        var priorSignatureId = jdbc.query(
                        """
                        SELECT id FROM encounter_signatures
                        WHERE organization_id=? AND encounter_note_id=? AND note_version_id=?
                          AND status='signed'
                        ORDER BY signed_at DESC,id DESC LIMIT 1
                        """,
                        (resultSet, rowNumber) -> resultSet.getObject("id", UUID.class),
                        context.organizationId(),
                        note.id(),
                        note.versionId())
                .stream()
                .findFirst()
                .orElseThrow(() -> conflict("The signed note evidence is unavailable."));
        var amendmentId = UuidV7Generator.randomUuid();
        var amendmentDigest = digest(amendmentText);
        jdbc.update(
                """
                INSERT INTO amendments
                    (id,organization_id,encounter_id,encounter_note_id,amended_note_version_id,
                     prior_signature_id,author_practitioner_id,eligibility_evidence_id,
                     eligibility_digest,reason_text,amendment_text,amendment_digest,
                     amended_at,status,created_by,updated_by)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,clock_timestamp(),'signed',?,?)
                """,
                amendmentId,
                context.organizationId(),
                encounter.id(),
                note.id(),
                note.versionId(),
                priorSignatureId,
                practitionerId,
                eligibility.evidenceId(),
                eligibility.digest(),
                command.reason(),
                amendmentText,
                amendmentDigest,
                context.actorId(),
                context.actorId());
        var changed = jdbc.update(
                """
                UPDATE encounter_notes
                SET status='amended',lock_version=lock_version+1,
                    updated_at=clock_timestamp(),updated_by=?
                WHERE organization_id=? AND id=? AND lock_version=?
                  AND status IN ('signed','amended')
                """,
                context.actorId(),
                context.organizationId(),
                note.id(),
                note.revision());
        if (changed != 1) throw stale("The signed note changed before amendment completed.");
        var revision = note.revision() + 1;
        var audit = map(
                "noteId", note.id(),
                "encounterId", encounter.id(),
                "versionId", note.versionId(),
                "amendmentId", amendmentId,
                "authorPractitionerId", practitionerId,
                "amendmentDigest", amendmentDigest,
                "revision", revision);
        var outbox = map(
                "noteId", note.id(),
                "encounterId", encounter.id(),
                "versionId", note.versionId(),
                "amendmentId", amendmentId,
                "amendmentDigest", amendmentDigest);
        return result(
                note.id(),
                encounter.patientId(),
                encounter.episodeId(),
                encounter.id(),
                encounter.appointmentId(),
                "encounter_note",
                "encounter.note.amended",
                "m5.note.amended.v1",
                "encounter_note",
                audit,
                outbox,
                201,
                revision);
    }

    private AppointmentContext appointmentContext(
            AuthorizedTenantContext context, UUID appointmentId) {
        return jdbc.query(
                        """
                        SELECT appointment.id,appointment.patient_id,appointment.service_id,
                               appointment.facility_id,appointment.location_id,appointment.starts_at,
                               assignment.practitioner_profile_id
                        FROM appointments appointment
                        JOIN appointment_assignments assignment
                          ON assignment.organization_id=appointment.organization_id
                         AND assignment.appointment_id=appointment.id AND assignment.status='active'
                        WHERE appointment.organization_id=? AND appointment.id=?
                          AND appointment.status='confirmed'
                        FOR SHARE OF appointment,assignment
                        """,
                        (resultSet, rowNumber) -> new AppointmentContext(
                                resultSet.getObject("id", UUID.class),
                                resultSet.getObject("patient_id", UUID.class),
                                resultSet.getObject("service_id", UUID.class),
                                resultSet.getObject("facility_id", UUID.class),
                                resultSet.getObject("location_id", UUID.class),
                                resultSet.getObject("practitioner_profile_id", UUID.class),
                                resultSet.getTimestamp("starts_at").toInstant()),
                        context.organizationId(),
                        appointmentId)
                .stream()
                .findFirst()
                .orElseThrow(() -> conflict("The confirmed appointment is unavailable."));
    }

    private void requireActivePatient(AuthorizedTenantContext context, UUID patientId) {
        var found = Boolean.TRUE.equals(jdbc.queryForObject(
                """
                SELECT EXISTS(SELECT 1 FROM patient_profiles
                  WHERE organization_id=? AND id=? AND lifecycle_state='active'
                    AND merged_into_patient_id IS NULL)
                """,
                Boolean.class,
                context.organizationId(),
                patientId));
        if (!found) throw conflict("The patient must be active and must not be merged.");
    }

    private Eligibility eligibility(
            AuthorizedTenantContext context,
            UUID practitionerId,
            UUID serviceId,
            UUID facilityId,
            UUID locationId,
            Instant evaluatedFor) {
        return jdbc.query(
                        """
                        SELECT assignment.id assignment_id,evidence.id evidence_id,
                               evidence.result_digest,practitioner.workforce_member_id,
                               practitioner.clinical_title,
                               jsonb_build_object(
                                 'clinicalTitle',practitioner.clinical_title,
                                 'regulated',practitioner.regulated,
                                 'registration',coalesce(registration.masked_display,'unavailable'),
                                 'evaluatorVersion',evidence.evaluator_version,
                                 'catalogueVersion',evidence.catalogue_version)::text snapshot
                        FROM practitioner_profiles practitioner
                        JOIN workforce_members member
                          ON member.organization_id=practitioner.organization_id
                         AND member.id=practitioner.workforce_member_id
                         AND member.lifecycle_state='active'
                        JOIN practitioner_service_assignments assignment
                          ON assignment.organization_id=practitioner.organization_id
                         AND assignment.practitioner_profile_id=practitioner.id
                         AND assignment.service_id=? AND assignment.facility_id=?
                         AND (assignment.location_id IS NULL OR assignment.location_id=?)
                         AND assignment.lifecycle_state='active'
                         AND assignment.effective_from<=?
                         AND (assignment.effective_to IS NULL OR assignment.effective_to>?)
                        JOIN practitioner_eligibility_evidence evidence
                          ON evidence.organization_id=assignment.organization_id
                         AND evidence.id=assignment.eligibility_evidence_id
                         AND evidence.result_digest=assignment.eligibility_digest
                         AND evidence.outcome='eligible' AND evidence.status='eligible'
                         AND evidence.evaluated_from<=?
                         AND (evidence.evaluated_to IS NULL OR evidence.evaluated_to>?)
                         AND evidence.expires_at>?
                        LEFT JOIN professional_registrations registration
                          ON registration.organization_id=practitioner.organization_id
                         AND registration.id=practitioner.primary_registration_id
                        WHERE practitioner.organization_id=? AND practitioner.id=?
                          AND practitioner.lifecycle_state='active'
                          AND practitioner.effective_from<=?
                          AND (practitioner.effective_to IS NULL OR practitioner.effective_to>?)
                        ORDER BY evidence.evaluated_at DESC,evidence.id DESC
                        LIMIT 1
                        """,
                        (resultSet, rowNumber) -> new Eligibility(
                                resultSet.getObject("assignment_id", UUID.class),
                                resultSet.getObject("evidence_id", UUID.class),
                                resultSet.getString("result_digest"),
                                resultSet.getObject("workforce_member_id", UUID.class),
                                resultSet.getString("clinical_title"),
                                resultSet.getString("snapshot")),
                        serviceId,
                        facilityId,
                        locationId,
                        Timestamp.from(evaluatedFor),
                        Timestamp.from(evaluatedFor),
                        Timestamp.from(evaluatedFor),
                        Timestamp.from(evaluatedFor),
                        Timestamp.from(evaluatedFor),
                        context.organizationId(),
                        practitionerId,
                        Timestamp.from(evaluatedFor),
                        Timestamp.from(evaluatedFor))
                .stream()
                .findFirst()
                .orElseThrow(() -> new EncounterException(
                        EncounterException.Reason.DEPENDENCY_UNAVAILABLE,
                        "Current practitioner eligibility is unavailable for this encounter context."));
    }

    private void insertPatientParticipant(
            AuthorizedTenantContext context, UUID encounterId, UUID patientId) {
        var label = jdbc.queryForObject(
                """
                SELECT coalesce(name_to_use,
                    nullif(concat_ws(' ',official_given_name,official_family_name),''),
                    'Patient '||left(id::text,8))
                FROM patient_profiles WHERE organization_id=? AND id=?
                """,
                String.class,
                context.organizationId(),
                patientId);
        jdbc.update(
                """
                INSERT INTO encounter_participants
                    (id,organization_id,encounter_id,participant_type,patient_id,role_key,
                     display_name_snapshot,role_snapshot,registration_snapshot,status,
                     added_at,created_by,updated_by)
                VALUES (?, ?,?,'patient',?,'subject_of_care',?,'Patient','{}'::jsonb,
                        'active',clock_timestamp(),?,?)
                """,
                UuidV7Generator.randomUuid(),
                context.organizationId(),
                encounterId,
                patientId,
                label,
                context.actorId(),
                context.actorId());
    }

    private UUID insertPractitionerParticipant(
            AuthorizedTenantContext context,
            UUID encounterId,
            UUID practitionerId,
            String roleKey,
            String roleLabel,
            Eligibility eligibility,
            Instant addedAt) {
        var participantId = UuidV7Generator.randomUuid();
        jdbc.update(
                """
                INSERT INTO encounter_participants
                    (id,organization_id,encounter_id,participant_type,workforce_member_id,
                     practitioner_profile_id,role_key,display_name_snapshot,role_snapshot,
                     assignment_id,eligibility_evidence_id,eligibility_digest,
                     registration_snapshot,status,added_at,created_by,updated_by)
                VALUES (?,?,?,'practitioner',?,?,?,?,?,?,?,?,?::jsonb,'active',?,?,?)
                """,
                participantId,
                context.organizationId(),
                encounterId,
                eligibility.workforceMemberId(),
                practitionerId,
                roleKey,
                eligibility.clinicalTitle(),
                roleLabel,
                eligibility.assignmentId(),
                eligibility.evidenceId(),
                eligibility.digest(),
                eligibility.registrationSnapshot(),
                Timestamp.from(addedAt),
                context.actorId(),
                context.actorId());
        return participantId;
    }

    private void insertStatusHistory(
            AuthorizedTenantContext context,
            UUID encounterId,
            String fromStatus,
            String toStatus,
            String reasonCode,
            MutationCommand command) {
        jdbc.update(
                """
                INSERT INTO encounter_status_history
                    (id,organization_id,encounter_id,from_status,to_status,reason_code,
                     policy_version,effective_at,actor_id,correlation_reference,
                     created_by,updated_by)
                VALUES (?,?,?,?,?,?,?,clock_timestamp(),?,?,?,?)
                """,
                UuidV7Generator.randomUuid(),
                context.organizationId(),
                encounterId,
                fromStatus,
                toStatus,
                reasonCode,
                POLICY_VERSION,
                context.actorId(),
                correlationReference(command.correlationId()),
                context.actorId(),
                context.actorId());
    }

    private Encounter encounter(
            AuthorizedTenantContext context, UUID encounterId, boolean lock) {
        if (encounterId == null) throw invalid("An encounter target is required.");
        return jdbc.query(
                        """
                        SELECT id,episode_of_care_id,source_appointment_id,patient_id,service_id,
                               facility_id,location_id,responsible_practitioner_id,status,
                               planned_start_at,lock_version
                        FROM encounters WHERE organization_id=? AND id=?
                        """ + (lock ? " FOR UPDATE" : ""),
                        (resultSet, rowNumber) -> new Encounter(
                                resultSet.getObject("id", UUID.class),
                                resultSet.getObject("episode_of_care_id", UUID.class),
                                resultSet.getObject("source_appointment_id", UUID.class),
                                resultSet.getObject("patient_id", UUID.class),
                                resultSet.getObject("service_id", UUID.class),
                                resultSet.getObject("facility_id", UUID.class),
                                resultSet.getObject("location_id", UUID.class),
                                resultSet.getObject("responsible_practitioner_id", UUID.class),
                                resultSet.getString("status"),
                                resultSet.getTimestamp("planned_start_at").toInstant(),
                                resultSet.getLong("lock_version")),
                        context.organizationId(),
                        encounterId)
                .stream()
                .findFirst()
                .orElseThrow(() -> notFound("The encounter is unavailable."));
    }

    private Participant participant(
            AuthorizedTenantContext context, UUID participantId, boolean lock) {
        if (participantId == null) throw invalid("A participant target is required.");
        return jdbc.query(
                        """
                        SELECT id,encounter_id,role_key,status,lock_version
                        FROM encounter_participants WHERE organization_id=? AND id=?
                        """ + (lock ? " FOR UPDATE" : ""),
                        (resultSet, rowNumber) -> new Participant(
                                resultSet.getObject("id", UUID.class),
                                resultSet.getObject("encounter_id", UUID.class),
                                resultSet.getString("role_key"),
                                resultSet.getString("status"),
                                resultSet.getLong("lock_version")),
                        context.organizationId(),
                        participantId)
                .stream()
                .findFirst()
                .orElseThrow(() -> notFound("The encounter participant is unavailable."));
    }

    private Task task(AuthorizedTenantContext context, UUID taskId, boolean lock) {
        if (taskId == null) throw invalid("A clinical task target is required.");
        return jdbc.query(
                        """
                        SELECT id,encounter_id,source_concern_id,status,lock_version
                        FROM clinical_tasks WHERE organization_id=? AND id=?
                        """ + (lock ? " FOR UPDATE" : ""),
                        (resultSet, rowNumber) -> new Task(
                                resultSet.getObject("id", UUID.class),
                                resultSet.getObject("encounter_id", UUID.class),
                                resultSet.getObject("source_concern_id", UUID.class),
                                resultSet.getString("status"),
                                resultSet.getLong("lock_version")),
                        context.organizationId(),
                        taskId)
                .stream()
                .findFirst()
                .orElseThrow(() -> notFound("The clinical task is unavailable."));
    }

    private Escalation escalation(
            AuthorizedTenantContext context, UUID escalationId, boolean lock) {
        if (escalationId == null) throw invalid("A red-flag escalation target is required.");
        return jdbc.query(
                        """
                        SELECT id,encounter_id,clinical_task_id,status,lock_version
                        FROM red_flag_escalations WHERE organization_id=? AND id=?
                        """ + (lock ? " FOR UPDATE" : ""),
                        (resultSet, rowNumber) -> new Escalation(
                                resultSet.getObject("id", UUID.class),
                                resultSet.getObject("encounter_id", UUID.class),
                                resultSet.getObject("clinical_task_id", UUID.class),
                                resultSet.getString("status"),
                                resultSet.getLong("lock_version")),
                        context.organizationId(),
                        escalationId)
                .stream()
                .findFirst()
                .orElseThrow(() -> notFound("The red-flag escalation is unavailable."));
    }

    private Note note(AuthorizedTenantContext context, UUID noteId, boolean lock) {
        if (noteId == null) throw invalid("An encounter note target is required.");
        return jdbc.query(
                        """
                        SELECT id,encounter_id,note_type_key,current_version_id,
                               current_version_number,status,lock_version
                        FROM encounter_notes WHERE organization_id=? AND id=?
                        """ + (lock ? " FOR UPDATE" : ""),
                        (resultSet, rowNumber) -> new Note(
                                resultSet.getObject("id", UUID.class),
                                resultSet.getObject("encounter_id", UUID.class),
                                resultSet.getString("note_type_key"),
                                resultSet.getObject("current_version_id", UUID.class),
                                resultSet.getInt("current_version_number"),
                                resultSet.getString("status"),
                                resultSet.getLong("lock_version")),
                        context.organizationId(),
                        noteId)
                .stream()
                .findFirst()
                .orElseThrow(() -> notFound("The encounter note is unavailable."));
    }

    private NoteVersion noteVersion(AuthorizedTenantContext context, UUID versionId) {
        return jdbc.query(
                        """
                        SELECT id,encounter_note_id,content_digest
                        FROM note_versions WHERE organization_id=? AND id=?
                        """,
                        (resultSet, rowNumber) -> new NoteVersion(
                                resultSet.getObject("id", UUID.class),
                                resultSet.getObject("encounter_note_id", UUID.class),
                                resultSet.getString("content_digest")),
                        context.organizationId(),
                        versionId)
                .stream()
                .findFirst()
                .orElseThrow(() -> notFound("The exact note version is unavailable."));
    }

    private UUID activePractitionerParticipant(
            AuthorizedTenantContext context, UUID encounterId, UUID practitionerId) {
        return jdbc.query(
                        """
                        SELECT id FROM encounter_participants
                        WHERE organization_id=? AND encounter_id=? AND practitioner_profile_id=?
                          AND participant_type='practitioner' AND status='active'
                        ORDER BY CASE role_key WHEN 'responsible_clinician' THEN 0 ELSE 1 END,id
                        LIMIT 1
                        """,
                        (resultSet, rowNumber) -> resultSet.getObject("id", UUID.class),
                        context.organizationId(),
                        encounterId,
                        practitionerId)
                .stream()
                .findFirst()
                .orElseThrow(() -> conflict("The signer must be an active practitioner participant."));
    }

    private void requireActorPractitioner(
            AuthorizedTenantContext context, UUID practitionerId) {
        var matches = Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT careos_m5_actor_is_practitioner(?,?,?,clock_timestamp())",
                Boolean.class,
                context.organizationId(),
                context.actorId(),
                practitionerId));
        if (!matches) {
            throw new EncounterException(
                    EncounterException.Reason.POLICY_UNAVAILABLE,
                    "The authenticated account is not linked to the selected active practitioner.");
        }
    }

    private boolean noteExists(AuthorizedTenantContext context, UUID noteId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM encounter_notes WHERE organization_id=? AND id=?)",
                Boolean.class,
                context.organizationId(),
                noteId));
    }

    private boolean belongsToEncounter(
            AuthorizedTenantContext context, String table, UUID id, UUID encounterId) {
        var relation = switch (table) {
            case "clinical_problems" -> "clinical_problems";
            default -> throw new IllegalArgumentException("Unsupported encounter child relation.");
        };
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM " + relation
                        + " WHERE organization_id=? AND id=? AND encounter_id=?)",
                Boolean.class,
                context.organizationId(),
                id,
                encounterId));
    }

    private static void requireDocumentableState(Encounter encounter) {
        if (!(encounter.status().equals("in_progress") || encounter.status().equals("on_hold"))) {
            throw conflict("Clinical content can be recorded only while the encounter is in progress or on hold.");
        }
    }

    private static void requireOpenClinicalState(Encounter encounter) {
        if (!isOpenEncounterStatus(encounter.status())) {
            throw conflict("The encounter is closed and its participants can no longer be changed.");
        }
    }

    private static void requireLifecycleTransition(String current, String next) {
        var allowed = switch (current) {
            case "planned" -> Set.of("arrived", "cancelled", "entered_in_error");
            case "arrived" -> Set.of("in_progress", "cancelled", "entered_in_error");
            case "in_progress" -> Set.of("on_hold", "completed", "cancelled", "entered_in_error");
            case "on_hold" -> Set.of("in_progress", "cancelled", "entered_in_error");
            default -> Set.<String>of();
        };
        if (!allowed.contains(next)) {
            throw conflict("The requested encounter lifecycle transition is not allowed from " + current + ".");
        }
    }

    private static boolean isOpenEncounterStatus(String status) {
        return status.equals("planned")
                || status.equals("arrived")
                || status.equals("in_progress")
                || status.equals("on_hold");
    }

    private static void requireRevision(long actual, Long expected) {
        if (expected == null) {
            throw new EncounterException(
                    EncounterException.Reason.PRECONDITION_REQUIRED,
                    "A strong If-Match revision is required.");
        }
        if (actual != expected) {
            throw stale("The encounter resource changed; refresh and review it before retrying.");
        }
    }

    private Instant databaseNow() {
        return Objects.requireNonNull(
                        jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class))
                .toInstant();
    }

    private void requireOperationScope(AuthorizedTenantContext context) {
        var bound = Boolean.TRUE.equals(jdbc.queryForObject(
                """
                SELECT nullif(current_setting('app.current_organization_id',true),'')::uuid=?
                   AND nullif(current_setting('app.current_actor_id',true),'')::uuid=?
                   AND nullif(current_setting('app.current_operation_key',true),'') IS NOT NULL
                """,
                Boolean.class,
                context.organizationId(),
                context.actorId()));
        if (!bound) {
            throw notFound("The encounter resource is unavailable or is not assigned to this account.");
        }
    }

    private static List<EncounterScreen.Column> columns(String screenId) {
        return switch (screenId) {
            case "P5-04" -> List.of(
                    column("primary", "Participant"),
                    column("secondary", "Role"),
                    column("context", "Type"),
                    column("addedAt", "Added"));
            case "P5-05" -> List.of(
                    column("primary", "Presenting concern"),
                    column("secondary", "Kind"),
                    column("context", "Safety"),
                    column("recordedAt", "Recorded"));
            case "P5-06", "P5-12" -> List.of(
                    column("primary", "Event"),
                    column("secondary", "Detail"),
                    column("context", "Evidence"),
                    column("effectiveAt", "Effective at"));
            case "P5-07" -> List.of(
                    column("primary", "Problem or diagnosis"),
                    column("secondary", "Classification"),
                    column("context", "Clinical status"),
                    column("recordedAt", "Recorded"));
            case "P5-08" -> List.of(
                    column("primary", "Order, task or escalation"),
                    column("secondary", "Type"),
                    column("context", "Priority / policy"),
                    column("recordedAt", "Recorded"));
            case "P5-09", "P5-10", "P5-11" -> List.of(
                    column("primary", "Note"),
                    column("secondary", "Current content"),
                    column("context", "Evidence state"),
                    column("recordedAt", "Recorded"));
            default -> List.of(
                    column("primary", "Encounter"),
                    column("secondary", "Patient"),
                    column("context", "Care context"),
                    column("plannedStart", "Planned start"));
        };
    }

    private static List<EncounterScreen.Notice> notices(String screenId) {
        var notices = new ArrayList<EncounterScreen.Notice>();
        if (screenId.equals("P5-01")) {
            notices.add(notice(
                    "info",
                    "Clinical policy boundary",
                    "This module records governed encounter evidence; external fulfilment and downstream activation remain disabled."));
        }
        if (screenId.equals("P5-05")) {
            notices.add(notice(
                    "warning",
                    "Red flags remain visible",
                    "A red flag creates a critical task and escalation that must be explicitly acknowledged and resolved."));
        }
        if (screenId.equals("P5-07")) {
            notices.add(notice(
                    "info",
                    "Terminology is explicit",
                    "Codes are stored only as supplied; this foundation does not claim terminology validation authority."));
        }
        if (screenId.equals("P5-08")) {
            notices.add(notice(
                    "warning",
                    "Internal workflow only",
                    "Orders are recorded internally and are not transmitted to laboratories, pharmacies or external providers."));
        }
        if (screenId.equals("P5-10")) {
            notices.add(notice(
                    "warning",
                    "Current signer eligibility required",
                    "Regulated content requires an active practitioner participant, current eligibility, recent authentication and MFA."));
        }
        if (screenId.equals("P5-11")) {
            notices.add(notice(
                    "info",
                    "Append-only correction",
                    "An amendment preserves the prior signed version, author, time, reason and content digest."));
        }
        return List.copyOf(notices);
    }

    private static EncounterScreen.Row withAllowedActions(
            String screenId, EncounterScreen.Row row) {
        var actions = new ArrayList<String>();
        var kind = row.values().getOrDefault("$kind", "");
        switch (screenId) {
            case "P5-03" -> {
                if (kind.equals("encounter")) {
                    switch (row.status()) {
                        case "planned" -> {
                            actions.add("mark-arrived");
                            actions.add("cancel-encounter");
                            actions.add("enter-encounter-in-error");
                        }
                        case "arrived" -> {
                            actions.add("start-encounter");
                            actions.add("cancel-encounter");
                            actions.add("enter-encounter-in-error");
                        }
                        case "in_progress" -> {
                            actions.add("place-on-hold");
                            actions.add("complete-encounter");
                            actions.add("cancel-encounter");
                            actions.add("enter-encounter-in-error");
                        }
                        case "on_hold" -> {
                            actions.add("resume-encounter");
                            actions.add("cancel-encounter");
                            actions.add("enter-encounter-in-error");
                        }
                        default -> {
                            // Terminal encounters are evidence-only.
                        }
                    }
                }
            }
            case "P5-04" -> {
                if (kind.equals("encounter") && isOpenEncounterStatus(row.status())) {
                    actions.add("add-practitioner-participant");
                }
                var role = row.values().getOrDefault("role", "");
                if (kind.equals("participant")
                        && row.status().equals("active")
                        && !role.equals("subject_of_care")
                        && !role.equals("responsible_clinician")) {
                    actions.add("remove-participant");
                }
            }
            case "P5-05" -> {
                if (kind.equals("encounter") && isDocumentableStatus(row.status())) {
                    actions.add("record-presenting-concern");
                }
            }
            case "P5-07" -> {
                if (kind.equals("encounter") && isDocumentableStatus(row.status())) {
                    actions.add("record-clinical-problem");
                    actions.add("record-diagnosis");
                }
            }
            case "P5-08" -> {
                if (kind.equals("encounter") && isDocumentableStatus(row.status())) {
                    actions.add("create-order");
                    actions.add("create-clinical-task");
                }
                if (kind.equals("task")
                        && (row.status().equals("open") || row.status().equals("in_progress"))
                        && !row.values().getOrDefault("secondary", "").equals("red_flag_escalation")) {
                    actions.add("progress-clinical-task");
                }
                if (kind.equals("escalation") && row.status().equals("raised")) {
                    actions.add("acknowledge-red-flag");
                }
                if (kind.equals("escalation") && row.status().equals("acknowledged")) {
                    actions.add("resolve-red-flag");
                }
            }
            case "P5-09" -> {
                if (kind.equals("encounter") && isDocumentableStatus(row.status())) {
                    actions.add("save-note-version");
                }
                if (kind.equals("note") && row.status().equals("draft")) {
                    actions.add("save-note-version");
                }
            }
            case "P5-10" -> {
                if (kind.equals("note")
                        && row.status().equals("draft")
                        && !row.values().getOrDefault("primary", "").endsWith("version 0")) {
                    actions.add("sign-note");
                }
            }
            case "P5-11" -> {
                if (kind.equals("note")
                        && (row.status().equals("signed") || row.status().equals("amended"))) {
                    actions.add("amend-signed-note");
                }
            }
            default -> {
                // Dashboard, open and history views use global or link actions only.
            }
        }
        var publicValues = new LinkedHashMap<>(row.values());
        publicValues.remove("$kind");
        return new EncounterScreen.Row(
                row.id(),
                row.patientId(),
                row.episodeId(),
                row.encounterId(),
                row.appointmentId(),
                row.status(),
                row.revision(),
                row.etag(),
                publicValues,
                actions);
    }

    private static boolean isDocumentableStatus(String status) {
        return status.equals("in_progress") || status.equals("on_hold");
    }

    private static EncounterScreen.Row row(
            String screenId,
            UUID id,
            UUID patientId,
            UUID episodeId,
            UUID encounterId,
            UUID appointmentId,
            String status,
            long revision,
            Map<String, String> values) {
        return new EncounterScreen.Row(
                id,
                patientId,
                episodeId,
                encounterId,
                appointmentId,
                status,
                revision,
                "\"m5:" + screenId + ":" + id + ":" + revision + "\"",
                values,
                List.of());
    }

    private static List<EncounterScreen.Row> combine(
            List<EncounterScreen.Row> first, List<EncounterScreen.Row> second) {
        var rows = new ArrayList<EncounterScreen.Row>(first.size() + second.size());
        rows.addAll(first);
        rows.addAll(second);
        return List.copyOf(rows);
    }

    private static MutationResult result(
            UUID subjectId,
            UUID patientId,
            UUID episodeId,
            UUID encounterId,
            UUID appointmentId,
            String subjectType,
            String auditEvent,
            String outboxEvent,
            String aggregateType,
            Map<String, Object> audit,
            Map<String, Object> outbox,
            int statusCode,
            long revision) {
        return result(
                subjectId,
                patientId,
                episodeId,
                encounterId,
                appointmentId,
                subjectType,
                auditEvent,
                outboxEvent,
                aggregateType,
                null,
                audit,
                outbox,
                statusCode,
                revision);
    }

    private static MutationResult result(
            UUID subjectId,
            UUID patientId,
            UUID episodeId,
            UUID encounterId,
            UUID appointmentId,
            String subjectType,
            String auditEvent,
            String outboxEvent,
            String aggregateType,
            UUID outboxAggregateId,
            Map<String, Object> audit,
            Map<String, Object> outbox,
            int statusCode,
            long revision) {
        return new MutationResult(
                subjectId,
                patientId,
                episodeId,
                encounterId,
                appointmentId,
                subjectType,
                auditEvent,
                outboxEvent,
                aggregateType,
                outboxAggregateId,
                audit,
                outbox,
                statusCode,
                revision);
    }

    private static String field(MutationCommand command, String key) {
        var value = command.fields().get(key);
        if (value == null || value.isBlank()) throw invalid(key + " is required.");
        return value.strip();
    }

    private static UUID fieldUuid(MutationCommand command, String key) {
        try {
            return UUID.fromString(field(command, key));
        } catch (IllegalArgumentException exception) {
            throw invalid(key + " must be a UUID.");
        }
    }

    private static UUID optionalFieldUuid(MutationCommand command, String key) {
        var value = command.fields().get(key);
        if (value == null || value.isBlank()) return null;
        try {
            return UUID.fromString(value.strip());
        } catch (IllegalArgumentException exception) {
            throw invalid(key + " must be a UUID.");
        }
    }

    private static Instant fieldInstant(MutationCommand command, String key) {
        try {
            return Instant.parse(field(command, key));
        } catch (Exception exception) {
            throw invalid(key + " must be an ISO-8601 instant with an explicit offset.");
        }
    }

    private static String bounded(
            String value, int minimum, int maximum, String fieldName) {
        var normalized = value == null ? "" : value.strip();
        var length = normalized.codePointCount(0, normalized.length());
        if (length < minimum || length > maximum) {
            throw invalid(fieldName + " must contain " + minimum + " to " + maximum + " characters.");
        }
        return normalized;
    }

    private static String optionalBounded(String value, int maximum, String fieldName) {
        if (value == null || value.isBlank()) return null;
        var normalized = value.strip();
        if (normalized.codePointCount(0, normalized.length()) > maximum) {
            throw invalid(fieldName + " must contain no more than " + maximum + " characters.");
        }
        return normalized;
    }

    private static String boundedCode(String value, String fieldName) {
        var normalized = value == null ? "" : value.strip();
        if (!normalized.matches("[A-Za-z0-9][A-Za-z0-9._:-]{1,79}")) {
            throw invalid(fieldName + " must contain 2 to 80 safe code characters.");
        }
        return normalized;
    }

    private static void requireCodePair(String system, String value) {
        if ((system == null) != (value == null)) {
            throw invalid("codeSystem and codeValue must be supplied together.");
        }
    }

    private static void requireOneOf(
            String value, String fieldName, String... supportedValues) {
        for (var supported : supportedValues) {
            if (supported.equals(value)) return;
        }
        throw invalid(fieldName + " contains an unsupported value.");
    }

    private static void requireLiteralSearch(String value, int minimum, String label) {
        var meaningful = value.codePoints().filter(Character::isLetterOrDigit).count();
        if (value.codePointCount(0, value.length()) < minimum
                || meaningful < 2
                || value.indexOf('%') >= 0
                || value.indexOf('_') >= 0
                || value.indexOf('\\') >= 0) {
            throw invalid(label + " requires bounded literal characters without wildcards.");
        }
    }

    private static UUID firstNonNull(UUID first, UUID second) {
        return first == null ? second : first;
    }

    private static String codeReason(String reason) {
        return "manual_" + digest(safe(reason, "reason unavailable")).substring(0, 16);
    }

    private static UUID correlationReference(String value) {
        try {
            return UUID.fromString(value);
        } catch (Exception exception) {
            return UUID.nameUUIDFromBytes(
                    ("m5:" + safe(value, "missing")).getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to calculate encounter evidence digest.", exception);
        }
    }

    private static Map<String, Object> map(Object... entries) {
        return mapWithoutNulls(entries);
    }

    private static Map<String, Object> mapWithoutNulls(Object... entries) {
        if (entries.length % 2 != 0) throw new IllegalArgumentException("Map entries must be paired.");
        var values = new LinkedHashMap<String, Object>();
        for (var index = 0; index < entries.length; index += 2) {
            if (entries[index + 1] != null) {
                values.put((String) entries[index], entries[index + 1]);
            }
        }
        return Map.copyOf(values);
    }

    private static Map<String, String> values(String... entries) {
        if (entries.length % 2 != 0) throw new IllegalArgumentException("Value entries must be paired.");
        var values = new LinkedHashMap<String, String>();
        for (var index = 0; index < entries.length; index += 2) {
            values.put(entries[index], safe(entries[index + 1], "Not recorded"));
        }
        return values;
    }

    private static EncounterScreen.Column column(String key, String label) {
        return new EncounterScreen.Column(key, label);
    }

    private static EncounterScreen.Metric metric(
            String key, String label, long value, String tone) {
        return new EncounterScreen.Metric(key, label, value, tone);
    }

    private static EncounterScreen.Notice notice(String tone, String title, String detail) {
        return new EncounterScreen.Notice(tone, title, detail);
    }

    private static String instant(Timestamp value) {
        return value == null ? "Not recorded" : value.toInstant().toString();
    }

    private static String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String shortId(UUID id) {
        return id.toString().substring(0, 8).toUpperCase(Locale.ROOT);
    }

    private static String maskPatientNumber(String value) {
        if (value == null || value.length() <= 4) return "••••";
        return "••••" + value.substring(value.length() - 4);
    }

    private static long number(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private static String roleLabel(String roleKey) {
        return switch (roleKey) {
            case "attending" -> "Attending practitioner";
            case "consulting" -> "Consulting practitioner";
            case "observer" -> "Observing practitioner";
            default -> throw invalid("roleKey contains an unsupported value.");
        };
    }

    private static EncounterException invalid(String message) {
        return new EncounterException(EncounterException.Reason.INVALID, message);
    }

    private static EncounterException notFound(String message) {
        return new EncounterException(EncounterException.Reason.NOT_FOUND, message);
    }

    private static EncounterException conflict(String message) {
        return new EncounterException(EncounterException.Reason.CONFLICT, message);
    }

    private static EncounterException stale(String message) {
        return new EncounterException(EncounterException.Reason.STALE, message);
    }

    private record AppointmentContext(
            UUID id,
            UUID patientId,
            UUID serviceId,
            UUID facilityId,
            UUID locationId,
            UUID practitionerId,
            Instant startsAt) {}

    private record Eligibility(
            UUID assignmentId,
            UUID evidenceId,
            String digest,
            UUID workforceMemberId,
            String clinicalTitle,
            String registrationSnapshot) {}

    private record Encounter(
            UUID id,
            UUID episodeId,
            UUID appointmentId,
            UUID patientId,
            UUID serviceId,
            UUID facilityId,
            UUID locationId,
            UUID practitionerId,
            String status,
            Instant plannedStart,
            long revision) {}

    private record Participant(
            UUID id, UUID encounterId, String roleKey, String status, long revision) {}

    private record Task(
            UUID id, UUID encounterId, UUID sourceConcernId, String status, long revision) {}

    private record Escalation(
            UUID id, UUID encounterId, UUID taskId, String status, long revision) {}

    private record Note(
            UUID id,
            UUID encounterId,
            String noteType,
            UUID versionId,
            int versionNumber,
            String status,
            long revision) {}

    private record NoteVersion(UUID id, UUID noteId, String digest) {}
}
