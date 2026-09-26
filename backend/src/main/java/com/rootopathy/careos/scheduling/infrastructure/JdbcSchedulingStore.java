package com.rootopathy.careos.scheduling.infrastructure;

import com.rootopathy.careos.scheduling.application.SchedulingException;
import com.rootopathy.careos.scheduling.application.SchedulingStore;
import com.rootopathy.careos.scheduling.domain.SchedulingScreen;
import com.rootopathy.careos.shared.domain.UuidV7Generator;
import com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
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

/** PostgreSQL-backed scheduling projection and atomic lifecycle mutations for Module 4. */
@Repository
public class JdbcSchedulingStore implements SchedulingStore {
    private static final String POLICY_VERSION = "m4-standing-direction-v1";
    private static final Duration HOLD_DURATION = Duration.ofMinutes(5);

    private final JdbcTemplate jdbc;

    public JdbcSchedulingStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Projection projection(AuthorizedTenantContext context, ScreenQuery query) {
        requireOperationScope(context);
        var rows = switch (query.screenId()) {
            case "P4-01", "P4-03", "P4-12", "P4-13" -> appointmentRows(context, query);
            case "P4-02" -> scheduleRows(context, query);
            case "P4-04", "P4-05", "P4-06", "P4-07", "P4-09", "P4-11" ->
                requestRows(context, query);
            case "P4-08" -> slotRows(context, query);
            case "P4-10" -> paymentRows(context, query);
            case "P4-14" -> waitlistRows(context, query);
            case "P4-15" -> timelineRows(context, query);
            default -> throw notFound("The requested scheduling projection does not exist.");
        };
        rows = rows.stream().map(row -> withAllowedActions(query.screenId(), row)).toList();
        var generatedAt = Objects.requireNonNull(
                        jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class))
                .toInstant();
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
                  AND (memberships.effective_to IS NULL
                       OR memberships.effective_to>clock_timestamp())
                  AND roles.status='active' AND roles.interactive
                  AND permissions.status='active'
                  AND EXISTS(SELECT 1 FROM authorization_registry_releases release
                      WHERE release.registry_version=roles.registry_version
                        AND release.status='active')
                  AND EXISTS(SELECT 1 FROM authorization_registry_releases release
                      WHERE release.registry_version=permissions.registry_version
                        AND release.status='active')
                """,
                String.class,
                context.organizationId(),
                context.actorId()));
    }

    @Override
    public MutationResult mutate(AuthorizedTenantContext context, MutationCommand command) {
        requireOperationScope(context);
        return switch (command.actionKey()) {
            case "create-schedule" -> createSchedule(context, command);
            case "activate-schedule" -> activateSchedule(context, command);
            case "create-slot" -> createSlot(context, command);
            case "start-appointment-request" -> startRequest(context, command);
            case "select-request-patient" -> updateRequestPatient(context, command);
            case "set-request-context" -> updateRequestContext(context, command);
            case "select-request-practitioner" -> updateRequestPractitioner(context, command);
            case "hold-slot" -> holdSlot(context, command);
            case "confirm-appointment" -> confirmAppointment(context, command);
            case "reschedule-appointment" -> rescheduleAppointment(context, command);
            case "cancel-appointment" -> cancelAppointment(context, command);
            case "record-no-show" -> recordNoShow(context, command);
            case "join-waitlist" -> joinWaitlist(context, command);
            case "withdraw-waitlist" -> withdrawWaitlist(context, command);
            default -> throw notFound("The requested scheduling action does not exist.");
        };
    }

    private List<SchedulingScreen.Row> scheduleRows(
            AuthorizedTenantContext context, ScreenQuery query) {
        return jdbc.query(
                """
                SELECT schedule.id,schedule.service_id,schedule.facility_id,schedule.location_id,
                       schedule.practitioner_profile_id,schedule.timezone,schedule.effective_from,
                       schedule.effective_to,schedule.slot_duration_minutes,schedule.lifecycle_state,
                       schedule.lock_version,service.display_name,facility.name facility_name,
                       location.name location_name,practitioner.clinical_title
                FROM appointment_schedules schedule
                JOIN service_definitions service
                  ON service.organization_id=schedule.organization_id AND service.id=schedule.service_id
                JOIN facilities facility
                  ON facility.organization_id=schedule.organization_id AND facility.id=schedule.facility_id
                JOIN service_locations location
                  ON location.organization_id=schedule.organization_id AND location.id=schedule.location_id
                JOIN practitioner_profiles practitioner
                  ON practitioner.organization_id=schedule.organization_id
                 AND practitioner.id=schedule.practitioner_profile_id
                WHERE schedule.organization_id=?
                  AND (CAST(? AS text) IS NULL OR schedule.lifecycle_state=CAST(? AS text))
                ORDER BY schedule.effective_from DESC,schedule.id
                LIMIT ?
                """,
                (resultSet, rowNumber) -> row(
                        query.screenId(),
                        resultSet.getObject("id", UUID.class),
                        null,
                        null,
                        resultSet.getString("lifecycle_state"),
                        resultSet.getLong("lock_version"),
                        values(
                                "$kind", "schedule",
                                "primary", resultSet.getString("display_name"),
                                "secondary", resultSet.getString("clinical_title"),
                                "context", resultSet.getString("facility_name") + " · "
                                        + resultSet.getString("location_name"),
                                "window", instant(resultSet.getTimestamp("effective_from")) + " — "
                                        + instant(resultSet.getTimestamp("effective_to")),
                                "timezone", resultSet.getString("timezone"),
                                "duration", resultSet.getInt("slot_duration_minutes") + " minutes",
                                "serviceId", resultSet.getObject("service_id", UUID.class).toString(),
                                "facilityId", resultSet.getObject("facility_id", UUID.class).toString(),
                                "locationId", resultSet.getObject("location_id", UUID.class).toString(),
                                "practitionerId",
                                        resultSet
                                                .getObject("practitioner_profile_id", UUID.class)
                                                .toString())),
                context.organizationId(),
                query.status(),
                query.status(),
                query.limit());
    }

    private List<SchedulingScreen.Row> slotRows(
            AuthorizedTenantContext context, ScreenQuery query) {
        return jdbc.query(
                """
                SELECT slot.id,slot.practitioner_profile_id,slot.starts_at,slot.ends_at,
                       slot.timezone_snapshot,
                       CASE WHEN slot.status='held' AND slot.hold_expires_at<=clock_timestamp()
                            THEN 'available' ELSE slot.status END projected_status,
                       slot.hold_expires_at,slot.lock_version,service.display_name,
                       facility.name facility_name,location.name location_name,
                       practitioner.clinical_title
                FROM appointment_slots slot
                JOIN service_definitions service
                  ON service.organization_id=slot.organization_id AND service.id=slot.service_id
                JOIN facilities facility
                  ON facility.organization_id=slot.organization_id AND facility.id=slot.facility_id
                JOIN service_locations location
                  ON location.organization_id=slot.organization_id AND location.id=slot.location_id
                JOIN practitioner_profiles practitioner
                  ON practitioner.organization_id=slot.organization_id
                 AND practitioner.id=slot.practitioner_profile_id
                WHERE slot.organization_id=?
                  AND (slot.starts_at>=clock_timestamp()-interval '1 day')
                  AND (CAST(? AS text) IS NULL OR
                       CASE WHEN slot.status='held' AND slot.hold_expires_at<=clock_timestamp()
                            THEN 'available' ELSE slot.status END=CAST(? AS text))
                ORDER BY slot.starts_at,slot.id
                LIMIT ?
                """,
                (resultSet, rowNumber) -> row(
                        query.screenId(),
                        resultSet.getObject("id", UUID.class),
                        null,
                        null,
                        resultSet.getString("projected_status"),
                        resultSet.getLong("lock_version"),
                        values(
                                "$kind", "slot",
                                "primary", resultSet.getString("display_name"),
                                "secondary", resultSet.getString("clinical_title"),
                                "context", resultSet.getString("facility_name") + " · "
                                        + resultSet.getString("location_name"),
                                "startsAt", instant(resultSet.getTimestamp("starts_at")),
                                "endsAt", instant(resultSet.getTimestamp("ends_at")),
                                "timezone", resultSet.getString("timezone_snapshot"),
                                "holdExpiresAt", instant(resultSet.getTimestamp("hold_expires_at")))),
                context.organizationId(),
                query.status(),
                query.status(),
                query.limit());
    }

    private List<SchedulingScreen.Row> requestRows(
            AuthorizedTenantContext context, ScreenQuery query) {
        return jdbc.query(
                """
                SELECT request.id,request.patient_id,request.service_id,request.facility_id,
                       request.location_id,request.practitioner_profile_id,request.selected_slot_id,
                       request.completed_appointment_id,request.request_source,request.purpose_key,
                       CASE WHEN request.status<>'confirmed' AND request.expires_at<=clock_timestamp()
                            THEN 'expired' ELSE request.status END projected_status,
                       request.hold_expires_at,request.expires_at,request.lock_version,
                       patient.patient_number,service.display_name,facility.name facility_name,
                       location.name location_name,practitioner.clinical_title,slot.starts_at
                FROM appointment_requests request
                JOIN patient_profiles patient
                  ON patient.organization_id=request.organization_id AND patient.id=request.patient_id
                LEFT JOIN service_definitions service
                  ON service.organization_id=request.organization_id AND service.id=request.service_id
                LEFT JOIN facilities facility
                  ON facility.organization_id=request.organization_id AND facility.id=request.facility_id
                LEFT JOIN service_locations location
                  ON location.organization_id=request.organization_id AND location.id=request.location_id
                LEFT JOIN practitioner_profiles practitioner
                  ON practitioner.organization_id=request.organization_id
                 AND practitioner.id=request.practitioner_profile_id
                LEFT JOIN appointment_slots slot
                  ON slot.organization_id=request.organization_id AND slot.id=request.selected_slot_id
                WHERE request.organization_id=?
                  AND (CAST(? AS uuid) IS NULL OR request.id=CAST(? AS uuid))
                  AND (CAST(? AS uuid) IS NULL OR request.patient_id=CAST(? AS uuid))
                  AND (CAST(? AS text) IS NULL OR request.status=CAST(? AS text))
                ORDER BY request.updated_at DESC,request.id
                LIMIT ?
                """,
                (resultSet, rowNumber) -> {
                    var id = resultSet.getObject("id", UUID.class);
                    var patientId = resultSet.getObject("patient_id", UUID.class);
                    return row(
                            query.screenId(),
                            id,
                            patientId,
                            resultSet.getObject("completed_appointment_id", UUID.class),
                            resultSet.getString("projected_status"),
                            resultSet.getLong("lock_version"),
                            values(
                                    "$kind", "request",
                                    "primary", "Request " + shortId(id),
                                    "secondary", maskPatientNumber(resultSet.getString("patient_number")),
                                    "context", safe(resultSet.getString("display_name"), "Care context not selected"),
                                    "facility", safe(resultSet.getString("facility_name"), "Not selected"),
                                    "location", safe(resultSet.getString("location_name"), "Not selected"),
                                    "practitioner", safe(resultSet.getString("clinical_title"), "Not selected"),
                                    "startsAt", instant(resultSet.getTimestamp("starts_at")),
                                    "holdExpiresAt", instant(resultSet.getTimestamp("hold_expires_at")),
                                    "expiresAt", instant(resultSet.getTimestamp("expires_at")),
                                    "source", resultSet.getString("request_source"),
                                    "purpose", resultSet.getString("purpose_key")));
                },
                context.organizationId(),
                query.requestId(),
                query.requestId(),
                query.patientId(),
                query.patientId(),
                query.status(),
                query.status(),
                query.limit());
    }

    private List<SchedulingScreen.Row> appointmentRows(
            AuthorizedTenantContext context, ScreenQuery query) {
        var search = query.search();
        if (search != null) requireLiteralSearch(search, 2, "Appointment search");
        return jdbc.query(
                """
                SELECT appointment.id,appointment.patient_id,appointment.source_request_id,
                       appointment.slot_id,appointment.starts_at,appointment.ends_at,
                       appointment.timezone_snapshot,appointment.status,
                       appointment.reschedule_count,appointment.lock_version,
                       patient.patient_number,service.display_name,facility.name facility_name,
                       location.name location_name,practitioner.clinical_title
                FROM appointments appointment
                JOIN patient_profiles patient
                  ON patient.organization_id=appointment.organization_id
                 AND patient.id=appointment.patient_id
                JOIN service_definitions service
                  ON service.organization_id=appointment.organization_id
                 AND service.id=appointment.service_id
                JOIN facilities facility
                  ON facility.organization_id=appointment.organization_id
                 AND facility.id=appointment.facility_id
                JOIN service_locations location
                  ON location.organization_id=appointment.organization_id
                 AND location.id=appointment.location_id
                LEFT JOIN appointment_assignments assignment
                  ON assignment.organization_id=appointment.organization_id
                 AND assignment.appointment_id=appointment.id AND assignment.status='active'
                LEFT JOIN practitioner_profiles practitioner
                  ON practitioner.organization_id=appointment.organization_id
                 AND practitioner.id=assignment.practitioner_profile_id
                WHERE appointment.organization_id=?
                  AND (CAST(? AS uuid) IS NULL OR appointment.id=CAST(? AS uuid))
                  AND (CAST(? AS uuid) IS NULL OR appointment.patient_id=CAST(? AS uuid))
                  AND (CAST(? AS text) IS NULL OR appointment.status=CAST(? AS text))
                  AND (CAST(? AS text) IS NULL
                       OR patient.patient_number ILIKE '%'||CAST(? AS text)||'%'
                       OR service.display_name ILIKE '%'||CAST(? AS text)||'%')
                ORDER BY appointment.starts_at DESC,appointment.id
                LIMIT ?
                """,
                (resultSet, rowNumber) -> {
                    var id = resultSet.getObject("id", UUID.class);
                    var patientId = resultSet.getObject("patient_id", UUID.class);
                    return row(
                            query.screenId(),
                            id,
                            patientId,
                            id,
                            resultSet.getString("status"),
                            resultSet.getLong("lock_version"),
                            values(
                                    "$kind", "appointment",
                                    "primary", resultSet.getString("display_name"),
                                    "secondary", maskPatientNumber(resultSet.getString("patient_number")),
                                    "context", resultSet.getString("facility_name") + " · "
                                            + resultSet.getString("location_name"),
                                    "practitioner", safe(resultSet.getString("clinical_title"), "Unavailable"),
                                    "startsAt", instant(resultSet.getTimestamp("starts_at")),
                                    "endsAt", instant(resultSet.getTimestamp("ends_at")),
                                    "timezone", resultSet.getString("timezone_snapshot"),
                                    "reschedules", Integer.toString(resultSet.getInt("reschedule_count")),
                                    "slotId", resultSet.getObject("slot_id", UUID.class).toString(),
                                    "requestId",
                                            resultSet
                                                    .getObject("source_request_id", UUID.class)
                                                    .toString()));
                },
                context.organizationId(),
                query.appointmentId(),
                query.appointmentId(),
                query.patientId(),
                query.patientId(),
                query.status(),
                query.status(),
                search,
                search,
                search,
                query.limit());
    }

    private List<SchedulingScreen.Row> paymentRows(
            AuthorizedTenantContext context, ScreenQuery query) {
        return jdbc.query(
                """
                SELECT requirement.id,requirement.appointment_id,requirement.requirement_state,
                       requirement.status,requirement.policy_version,requirement.lock_version,
                       appointment.patient_id,appointment.starts_at
                FROM appointment_payment_requirements requirement
                JOIN appointments appointment
                  ON appointment.organization_id=requirement.organization_id
                 AND appointment.id=requirement.appointment_id
                WHERE requirement.organization_id=?
                  AND (CAST(? AS uuid) IS NULL OR requirement.appointment_id=CAST(? AS uuid))
                  AND (CAST(? AS uuid) IS NULL OR appointment.patient_id=CAST(? AS uuid))
                ORDER BY appointment.starts_at DESC,requirement.id
                LIMIT ?
                """,
                (resultSet, rowNumber) -> row(
                        query.screenId(),
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("patient_id", UUID.class),
                        resultSet.getObject("appointment_id", UUID.class),
                        resultSet.getString("status"),
                        resultSet.getLong("lock_version"),
                        values(
                                "$kind", "payment-requirement",
                                "primary", "Deferred to billing",
                                "secondary", "No payment decision recorded in scheduling",
                                "context", instant(resultSet.getTimestamp("starts_at")),
                                "requirement", resultSet.getString("requirement_state"),
                                "policyVersion", resultSet.getString("policy_version"))),
                context.organizationId(),
                query.appointmentId(),
                query.appointmentId(),
                query.patientId(),
                query.patientId(),
                query.limit());
    }

    private List<SchedulingScreen.Row> waitlistRows(
            AuthorizedTenantContext context, ScreenQuery query) {
        return jdbc.query(
                """
                SELECT entry.id,entry.patient_id,entry.earliest_at,entry.latest_at,
                       entry.priority_key,entry.status,entry.lock_version,
                       patient.patient_number,service.display_name,facility.name facility_name,
                       location.name location_name
                FROM waitlist_entries entry
                JOIN patient_profiles patient
                  ON patient.organization_id=entry.organization_id AND patient.id=entry.patient_id
                JOIN service_definitions service
                  ON service.organization_id=entry.organization_id AND service.id=entry.service_id
                LEFT JOIN facilities facility
                  ON facility.organization_id=entry.organization_id AND facility.id=entry.facility_id
                LEFT JOIN service_locations location
                  ON location.organization_id=entry.organization_id AND location.id=entry.location_id
                WHERE entry.organization_id=?
                  AND (CAST(? AS uuid) IS NULL OR entry.patient_id=CAST(? AS uuid))
                  AND (CAST(? AS text) IS NULL OR entry.status=CAST(? AS text))
                ORDER BY entry.earliest_at,entry.id
                LIMIT ?
                """,
                (resultSet, rowNumber) -> {
                    var id = resultSet.getObject("id", UUID.class);
                    return row(
                            query.screenId(),
                            id,
                            resultSet.getObject("patient_id", UUID.class),
                            null,
                            resultSet.getString("status"),
                            resultSet.getLong("lock_version"),
                            values(
                                    "$kind", "waitlist",
                                    "primary", resultSet.getString("display_name"),
                                    "secondary", maskPatientNumber(resultSet.getString("patient_number")),
                                    "context", safe(resultSet.getString("facility_name"), "Any facility") + " · "
                                            + safe(resultSet.getString("location_name"), "Any location"),
                                    "window", instant(resultSet.getTimestamp("earliest_at")) + " — "
                                            + instant(resultSet.getTimestamp("latest_at")),
                                    "priority", resultSet.getString("priority_key")));
                },
                context.organizationId(),
                query.patientId(),
                query.patientId(),
                query.status(),
                query.status(),
                query.limit());
    }

    private List<SchedulingScreen.Row> timelineRows(
            AuthorizedTenantContext context, ScreenQuery query) {
        if (query.appointmentId() == null) return List.of();
        return jdbc.query(
                """
                SELECT history.id,history.appointment_id,history.from_status,history.to_status,
                       history.prior_slot_id,history.new_slot_id,history.reason_code,
                       history.policy_version,history.effective_at,history.lock_version,
                       appointment.patient_id
                FROM appointment_status_history history
                JOIN appointments appointment
                  ON appointment.organization_id=history.organization_id
                 AND appointment.id=history.appointment_id
                WHERE history.organization_id=? AND history.appointment_id=?
                ORDER BY history.effective_at DESC,history.id DESC
                LIMIT ?
                """,
                (resultSet, rowNumber) -> row(
                        query.screenId(),
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("patient_id", UUID.class),
                        resultSet.getObject("appointment_id", UUID.class),
                        resultSet.getString("to_status"),
                        resultSet.getLong("lock_version"),
                        values(
                                "$kind", "timeline",
                                "primary", timelineTitle(resultSet.getString("to_status")),
                                "secondary", "From "
                                        + safe(resultSet.getString("from_status"), "created"),
                                "context", resultSet.getString("reason_code"),
                                "effectiveAt", instant(resultSet.getTimestamp("effective_at")),
                                "priorSlotId", objectString(resultSet.getObject("prior_slot_id")),
                                "newSlotId", objectString(resultSet.getObject("new_slot_id")),
                                "policyVersion", resultSet.getString("policy_version"))),
                context.organizationId(),
                query.appointmentId(),
                query.limit());
    }

    private List<SchedulingScreen.Metric> metrics(AuthorizedTenantContext context) {
        return List.of(
                metric(
                        "upcoming",
                        "Upcoming",
                        count(
                                "appointments",
                                context.organizationId(),
                                "status='confirmed' AND starts_at>=clock_timestamp()"),
                        "info"),
                metric(
                        "activeHolds",
                        "Active holds",
                        count(
                                "appointment_slots",
                                context.organizationId(),
                                "status='held' AND hold_expires_at>clock_timestamp()"),
                        "warning"),
                metric(
                        "waitlist",
                        "Waiting",
                        count(
                                "waitlist_entries",
                                context.organizationId(),
                                "status='waiting'"),
                        "neutral"));
    }

    private long count(String table, UUID organizationId, String predicate) {
        if (!Set.of("appointments", "appointment_slots", "waitlist_entries").contains(table)) {
            throw new IllegalArgumentException("Unsupported scheduling metric table.");
        }
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE organization_id=? AND " + predicate,
                Long.class,
                organizationId));
    }

    private MutationResult createSchedule(
            AuthorizedTenantContext context, MutationCommand command) {
        var serviceId = fieldUuid(command, "serviceId");
        var facilityId = fieldUuid(command, "facilityId");
        var locationId = fieldUuid(command, "locationId");
        var practitionerId = fieldUuid(command, "practitionerId");
        var timezone = field(command, "timezone");
        try {
            ZoneId.of(timezone);
        } catch (Exception exception) {
            throw invalid("timezone must be a valid IANA timezone.");
        }
        var effectiveFrom = fieldInstant(command, "effectiveFrom");
        var effectiveTo = fieldInstant(command, "effectiveTo");
        var duration = fieldInt(command, "slotDurationMinutes", 5, 480);
        if (!effectiveTo.isAfter(effectiveFrom)) {
            throw invalid("effectiveTo must be after effectiveFrom.");
        }
        requireScheduleReferences(
                context,
                serviceId,
                facilityId,
                locationId,
                practitionerId,
                effectiveFrom,
                effectiveTo);
        var id = UuidV7Generator.randomUuid();
        jdbc.update(
                """
                INSERT INTO appointment_schedules
                    (id,organization_id,service_id,facility_id,location_id,
                     practitioner_profile_id,timezone,effective_from,effective_to,
                     slot_duration_minutes,lifecycle_state,policy_version,
                     created_by,updated_by)
                VALUES (?,?,?,?,?,?,?,?,?,?,'draft',?,?,?)
                """,
                id,
                context.organizationId(),
                serviceId,
                facilityId,
                locationId,
                practitionerId,
                timezone,
                Timestamp.from(effectiveFrom),
                Timestamp.from(effectiveTo),
                duration,
                POLICY_VERSION,
                context.actorId(),
                context.actorId());
        var payload = map(
                "scheduleId", id,
                "serviceId", serviceId,
                "facilityId", facilityId,
                "locationId", locationId,
                "practitionerId", practitionerId,
                "state", "draft",
                "revision", 0);
        return result(
                id,
                null,
                null,
                null,
                "appointment_schedule",
                "appointment.schedule.created",
                null,
                "appointment_schedule",
                payload,
                Map.of(),
                201,
                0);
    }

    private MutationResult activateSchedule(
            AuthorizedTenantContext context, MutationCommand command) {
        var schedule = lockSchedule(context, command.targetId());
        requireRevision(schedule.revision(), command.expectedRevision());
        if (!schedule.state().equals("draft")) {
            throw conflict("Only a draft schedule can be activated.");
        }
        requireScheduleReferences(
                context,
                schedule.serviceId(),
                schedule.facilityId(),
                schedule.locationId(),
                schedule.practitionerId(),
                schedule.effectiveFrom(),
                schedule.effectiveTo());
        var revision = schedule.revision() + 1;
        jdbc.update(
                """
                UPDATE appointment_schedules
                SET lifecycle_state='active',lock_version=?,updated_at=clock_timestamp(),updated_by=?
                WHERE organization_id=? AND id=?
                """,
                revision,
                context.actorId(),
                context.organizationId(),
                schedule.id());
        var payload = map(
                "scheduleId", schedule.id(),
                "state", "active",
                "revision", revision);
        return result(
                schedule.id(),
                null,
                null,
                null,
                "appointment_schedule",
                "appointment.schedule.activated",
                null,
                "appointment_schedule",
                payload,
                Map.of(),
                200,
                revision);
    }

    private MutationResult createSlot(
            AuthorizedTenantContext context, MutationCommand command) {
        var schedule = lockSchedule(context, command.targetId());
        requireRevision(schedule.revision(), command.expectedRevision());
        if (!schedule.state().equals("active")) {
            throw conflict("Slots can only be added to an active schedule.");
        }
        var startsAt = fieldInstant(command, "startsAt");
        var endsAt = fieldInstant(command, "endsAt");
        if (!startsAt.isBefore(endsAt)
                || !Duration.between(startsAt, endsAt)
                        .equals(Duration.ofMinutes(schedule.slotDurationMinutes()))) {
            throw invalid("The slot must use the schedule's exact duration.");
        }
        if (startsAt.isBefore(schedule.effectiveFrom()) || endsAt.isAfter(schedule.effectiveTo())) {
            throw invalid("The slot must be contained within the schedule window.");
        }
        var id = UuidV7Generator.randomUuid();
        jdbc.update(
                """
                INSERT INTO appointment_slots
                    (id,organization_id,schedule_id,service_id,facility_id,location_id,
                     practitioner_profile_id,starts_at,ends_at,timezone_snapshot,status,
                     created_by,updated_by)
                VALUES (?,?,?,?,?,?,?,?,?,?,'available',?,?)
                """,
                id,
                context.organizationId(),
                schedule.id(),
                schedule.serviceId(),
                schedule.facilityId(),
                schedule.locationId(),
                schedule.practitionerId(),
                Timestamp.from(startsAt),
                Timestamp.from(endsAt),
                schedule.timezone(),
                context.actorId(),
                context.actorId());
        var payload = map(
                "slotId", id,
                "scheduleId", schedule.id(),
                "startsAt", startsAt,
                "endsAt", endsAt,
                "revision", 0);
        return result(
                id,
                null,
                null,
                null,
                "appointment_slot",
                "appointment.slot.created",
                null,
                "appointment_slot",
                payload,
                Map.of(),
                201,
                0);
    }

    private MutationResult startRequest(
            AuthorizedTenantContext context, MutationCommand command) {
        var patientId = fieldUuid(command, "patientId");
        requireActivePatient(context, patientId);
        var source = field(command, "requestSource");
        if (!Set.of("staff", "referral").contains(source)) {
            throw invalid("Only staff and referral scheduling sources are currently available.");
        }
        var purpose = field(command, "purposeKey");
        requireBoundedCode(purpose, "purposeKey");
        var id = UuidV7Generator.randomUuid();
        jdbc.update(
                """
                INSERT INTO appointment_requests
                    (id,organization_id,patient_id,request_source,purpose_key,status,
                     expires_at,policy_version,created_by,updated_by)
                VALUES (?,?,?,?,?,'collecting',clock_timestamp()+interval '24 hours',?,?,?)
                """,
                id,
                context.organizationId(),
                patientId,
                source,
                purpose,
                POLICY_VERSION,
                context.actorId(),
                context.actorId());
        var payload = map(
                "requestId", id,
                "patientId", patientId,
                "source", source,
                "status", "collecting",
                "revision", 0);
        return result(
                id,
                patientId,
                null,
                id,
                "appointment_request",
                "appointment.request.started",
                null,
                "appointment_request",
                payload,
                Map.of(),
                201,
                0);
    }

    private MutationResult updateRequestPatient(
            AuthorizedTenantContext context, MutationCommand command) {
        var request = lockRequest(context, command.targetId());
        requireEditableRequest(request, command.expectedRevision());
        var patientId = fieldUuid(command, "patientId");
        requireActivePatient(context, patientId);
        var revision = request.revision() + 1;
        jdbc.update(
                """
                UPDATE appointment_requests
                SET patient_id=?,lock_version=?,updated_at=clock_timestamp(),updated_by=?
                WHERE organization_id=? AND id=?
                """,
                patientId,
                revision,
                context.actorId(),
                context.organizationId(),
                request.id());
        return contextChange(request.id(), patientId, "patient", revision);
    }

    private MutationResult updateRequestContext(
            AuthorizedTenantContext context, MutationCommand command) {
        var request = lockRequest(context, command.targetId());
        requireEditableRequest(request, command.expectedRevision());
        var serviceId = fieldUuid(command, "serviceId");
        var facilityId = fieldUuid(command, "facilityId");
        var locationId = fieldUuid(command, "locationId");
        requireCareContext(context, serviceId, facilityId, locationId);
        var revision = request.revision() + 1;
        jdbc.update(
                """
                UPDATE appointment_requests
                SET service_id=?,facility_id=?,location_id=?,practitioner_profile_id=NULL,
                    lock_version=?,updated_at=clock_timestamp(),updated_by=?
                WHERE organization_id=? AND id=?
                """,
                serviceId,
                facilityId,
                locationId,
                revision,
                context.actorId(),
                context.organizationId(),
                request.id());
        return contextChange(request.id(), request.patientId(), "care-context", revision);
    }

    private MutationResult updateRequestPractitioner(
            AuthorizedTenantContext context, MutationCommand command) {
        var request = lockRequest(context, command.targetId());
        requireEditableRequest(request, command.expectedRevision());
        if (request.serviceId() == null || request.facilityId() == null || request.locationId() == null) {
            throw conflict("Select the service, facility and location before the practitioner.");
        }
        var practitionerId = fieldUuid(command, "practitionerId");
        requireSelectablePractitioner(
                context,
                practitionerId,
                request.serviceId(),
                request.facilityId(),
                request.locationId());
        var revision = request.revision() + 1;
        jdbc.update(
                """
                UPDATE appointment_requests
                SET practitioner_profile_id=?,lock_version=?,updated_at=clock_timestamp(),updated_by=?
                WHERE organization_id=? AND id=?
                """,
                practitionerId,
                revision,
                context.actorId(),
                context.organizationId(),
                request.id());
        return contextChange(request.id(), request.patientId(), "practitioner", revision);
    }

    private MutationResult contextChange(
            UUID requestId, UUID patientId, String changeStep, long revision) {
        var payload = map(
                "requestId", requestId,
                "patientId", patientId,
                "changeStep", changeStep,
                "revision", revision);
        return result(
                requestId,
                patientId,
                null,
                requestId,
                "appointment_request",
                "appointment.request.context_changed",
                null,
                "appointment_request",
                payload,
                Map.of(),
                200,
                revision);
    }

    private MutationResult holdSlot(
            AuthorizedTenantContext context, MutationCommand command) {
        var slotId = fieldUuid(command, "slotId");
        var requestId = command.requestId();
        if (requestId == null && command.targetId() != null && !command.targetId().equals(slotId)) {
            requestId = command.targetId();
        }
        if (requestId == null) {
            throw invalid("requestId is required to acquire a slot hold.");
        }
        var request = lockRequest(context, requestId);
        var slot = lockSlot(context, slotId);
        if (command.targetId().equals(slotId)) {
            requireRevision(slot.revision(), command.expectedRevision());
        } else {
            requireRevision(request.revision(), command.expectedRevision());
        }
        requireEditableRequest(request, request.revision());
        if (request.serviceId() == null
                || request.facilityId() == null
                || request.locationId() == null
                || request.practitionerId() == null) {
            throw conflict("Complete the care context and practitioner selection before holding a slot.");
        }
        if (!request.serviceId().equals(slot.serviceId())
                || !request.facilityId().equals(slot.facilityId())
                || !request.locationId().equals(slot.locationId())
                || !request.practitionerId().equals(slot.practitionerId())) {
            throw conflict("The selected slot does not match the appointment request context.");
        }
        var databaseNow = databaseNow();
        if (!slot.startsAt().isAfter(databaseNow)) {
            throw conflict("Only a future slot can be held.");
        }
        if (!slot.state().equals("available")
                && !(slot.state().equals("held")
                        && slot.holdExpiresAt() != null
                        && !slot.holdExpiresAt().isAfter(databaseNow))) {
            throw conflict("The selected slot is no longer available.");
        }
        var expiresAt = databaseNow.plus(HOLD_DURATION);
        var tokenDigest = digest(UuidV7Generator.randomUuid().toString());
        var slotRevision = slot.revision() + 1;
        var requestRevision = request.revision() + 1;
        jdbc.update(
                """
                UPDATE appointment_slots
                SET status='held',held_by_request_id=?,hold_token_digest=?,hold_expires_at=?,
                    booked_appointment_id=NULL,lock_version=?,updated_at=clock_timestamp(),updated_by=?
                WHERE organization_id=? AND id=?
                """,
                request.id(),
                tokenDigest,
                Timestamp.from(expiresAt),
                slotRevision,
                context.actorId(),
                context.organizationId(),
                slot.id());
        jdbc.update(
                """
                UPDATE appointment_requests
                SET selected_slot_id=?,status='held',hold_token_digest=?,hold_expires_at=?,
                    lock_version=?,updated_at=clock_timestamp(),updated_by=?
                WHERE organization_id=? AND id=?
                """,
                slot.id(),
                tokenDigest,
                Timestamp.from(expiresAt),
                requestRevision,
                context.actorId(),
                context.organizationId(),
                request.id());
        var payload = map(
                "requestId", request.id(),
                "slotId", slot.id(),
                "holdExpiresAt", expiresAt,
                "revision", requestRevision);
        return result(
                request.id(),
                request.patientId(),
                null,
                request.id(),
                "appointment_request",
                "appointment.slot.held",
                null,
                "appointment_request",
                payload,
                Map.of(),
                200,
                requestRevision);
    }

    private MutationResult confirmAppointment(
            AuthorizedTenantContext context, MutationCommand command) {
        var request = lockRequest(context, command.targetId());
        requireRevision(request.revision(), command.expectedRevision());
        if (!request.state().equals("held")
                || request.slotId() == null
                || request.holdDigest() == null
                || request.holdExpiresAt() == null) {
            throw conflict("The appointment request does not have an active slot hold.");
        }
        var slot = lockSlot(context, request.slotId());
        var databaseNow = databaseNow();
        if (!request.holdExpiresAt().isAfter(databaseNow)
                || !slot.state().equals("held")
                || !request.id().equals(slot.heldByRequestId())
                || !Objects.equals(request.holdDigest(), slot.holdDigest())
                || slot.holdExpiresAt() == null
                || !slot.holdExpiresAt().isAfter(databaseNow)) {
            throw conflict("The slot hold expired or was replaced; select a slot again.");
        }
        requireRequestMatchesSlot(request, slot);
        requireActivePatient(context, request.patientId());
        var eligibility = requireEligibility(context, slot);
        var appointmentId = UuidV7Generator.randomUuid();
        jdbc.update(
                """
                INSERT INTO appointments
                    (id,organization_id,source_request_id,patient_id,service_id,facility_id,
                     location_id,schedule_id,slot_id,starts_at,ends_at,timezone_snapshot,
                     status,policy_version,confirmed_at,created_by,updated_by)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,'confirmed',?,?,?,?)
                """,
                appointmentId,
                context.organizationId(),
                request.id(),
                request.patientId(),
                slot.serviceId(),
                slot.facilityId(),
                slot.locationId(),
                slot.scheduleId(),
                slot.id(),
                Timestamp.from(slot.startsAt()),
                Timestamp.from(slot.endsAt()),
                slot.timezone(),
                POLICY_VERSION,
                Timestamp.from(databaseNow),
                context.actorId(),
                context.actorId());
        jdbc.update(
                """
                UPDATE appointment_slots
                SET status='booked',held_by_request_id=NULL,hold_token_digest=NULL,
                    hold_expires_at=NULL,booked_appointment_id=?,lock_version=?,
                    updated_at=clock_timestamp(),updated_by=?
                WHERE organization_id=? AND id=?
                """,
                appointmentId,
                slot.revision() + 1,
                context.actorId(),
                context.organizationId(),
                slot.id());
        jdbc.update(
                """
                UPDATE appointment_requests
                SET status='confirmed',completed_appointment_id=?,hold_token_digest=NULL,
                    hold_expires_at=NULL,lock_version=?,updated_at=clock_timestamp(),updated_by=?
                WHERE organization_id=? AND id=?
                """,
                appointmentId,
                request.revision() + 1,
                context.actorId(),
                context.organizationId(),
                request.id());
        insertParticipants(context, appointmentId, request.patientId(), slot.practitionerId());
        insertAssignment(context, appointmentId, slot, eligibility);
        insertHistory(
                context,
                appointmentId,
                null,
                "confirmed",
                null,
                slot.id(),
                "confirmed",
                databaseNow,
                command.correlationId());
        jdbc.update(
                """
                INSERT INTO appointment_payment_requirements
                    (id,organization_id,appointment_id,requirement_state,policy_version,status,
                     created_by,updated_by)
                VALUES (?,?,?,'deferred_to_billing',?,'deferred',?,?)
                """,
                UuidV7Generator.randomUuid(),
                context.organizationId(),
                appointmentId,
                POLICY_VERSION,
                context.actorId(),
                context.actorId());
        var audit = map(
                "appointmentId", appointmentId,
                "requestId", request.id(),
                "patientId", request.patientId(),
                "slotId", slot.id(),
                "startsAt", slot.startsAt(),
                "eligibilityEvidenceId", eligibility.evidenceId(),
                "revision", 0);
        var outbox = map(
                "appointmentId", appointmentId,
                "patientId", request.patientId(),
                "slotId", slot.id(),
                "startsAt", slot.startsAt());
        return result(
                appointmentId,
                request.patientId(),
                appointmentId,
                request.id(),
                "appointment",
                "appointment.confirmed",
                "m4.appointment.confirmed.v1",
                "appointment",
                audit,
                outbox,
                201,
                0);
    }

    private MutationResult rescheduleAppointment(
            AuthorizedTenantContext context, MutationCommand command) {
        var appointment = lockAppointment(context, command.targetId());
        requireRevision(appointment.revision(), command.expectedRevision());
        if (!appointment.state().equals("confirmed")) {
            throw conflict("Only a confirmed appointment can be rescheduled.");
        }
        var newSlotId = fieldUuid(command, "newSlotId");
        if (newSlotId.equals(appointment.slotId())) {
            throw invalid("The new slot must differ from the current slot.");
        }
        var oldSlot = lockSlot(context, appointment.slotId());
        var newSlot = lockSlot(context, newSlotId);
        var databaseNow = databaseNow();
        if (!newSlot.startsAt().isAfter(databaseNow)) {
            throw conflict("The replacement slot must be in the future.");
        }
        if (!newSlot.state().equals("available")
                && !(newSlot.state().equals("held")
                        && newSlot.holdExpiresAt() != null
                        && !newSlot.holdExpiresAt().isAfter(databaseNow))) {
            throw conflict("The replacement slot is no longer available.");
        }
        if (!appointment.serviceId().equals(newSlot.serviceId())) {
            throw conflict("Rescheduling cannot change the appointment service.");
        }
        requireActivePatient(context, appointment.patientId());
        var eligibility = requireEligibility(context, newSlot);
        var revision = appointment.revision() + 1;
        jdbc.update(
                """
                UPDATE appointments
                SET facility_id=?,location_id=?,schedule_id=?,slot_id=?,starts_at=?,ends_at=?,
                    timezone_snapshot=?,reschedule_count=reschedule_count+1,lock_version=?,
                    updated_at=clock_timestamp(),updated_by=?
                WHERE organization_id=? AND id=?
                """,
                newSlot.facilityId(),
                newSlot.locationId(),
                newSlot.scheduleId(),
                newSlot.id(),
                Timestamp.from(newSlot.startsAt()),
                Timestamp.from(newSlot.endsAt()),
                newSlot.timezone(),
                revision,
                context.actorId(),
                context.organizationId(),
                appointment.id());
        jdbc.update(
                """
                UPDATE appointment_slots
                SET status='available',held_by_request_id=NULL,hold_token_digest=NULL,
                    hold_expires_at=NULL,booked_appointment_id=NULL,lock_version=?,
                    updated_at=clock_timestamp(),updated_by=?
                WHERE organization_id=? AND id=?
                """,
                oldSlot.revision() + 1,
                context.actorId(),
                context.organizationId(),
                oldSlot.id());
        jdbc.update(
                """
                UPDATE appointment_slots
                SET status='booked',held_by_request_id=NULL,hold_token_digest=NULL,
                    hold_expires_at=NULL,booked_appointment_id=?,lock_version=?,
                    updated_at=clock_timestamp(),updated_by=?
                WHERE organization_id=? AND id=?
                """,
                appointment.id(),
                newSlot.revision() + 1,
                context.actorId(),
                context.organizationId(),
                newSlot.id());
        jdbc.update(
                """
                UPDATE appointment_assignments
                SET status='superseded',lock_version=lock_version+1,
                    updated_at=clock_timestamp(),updated_by=?
                WHERE organization_id=? AND appointment_id=? AND status='active'
                """,
                context.actorId(),
                context.organizationId(),
                appointment.id());
        insertAssignment(context, appointment.id(), newSlot, eligibility);
        insertHistory(
                context,
                appointment.id(),
                "confirmed",
                "rescheduled",
                oldSlot.id(),
                newSlot.id(),
                "rescheduled",
                databaseNow,
                command.correlationId());
        var audit = map(
                "appointmentId", appointment.id(),
                "priorSlotId", oldSlot.id(),
                "newSlotId", newSlot.id(),
                "startsAt", newSlot.startsAt(),
                "eligibilityEvidenceId", eligibility.evidenceId(),
                "revision", revision);
        var outbox = map(
                "appointmentId", appointment.id(),
                "priorSlotId", oldSlot.id(),
                "newSlotId", newSlot.id(),
                "startsAt", newSlot.startsAt());
        return result(
                appointment.id(),
                appointment.patientId(),
                appointment.id(),
                appointment.requestId(),
                "appointment",
                "appointment.rescheduled",
                "m4.appointment.rescheduled.v1",
                "appointment",
                audit,
                outbox,
                200,
                revision);
    }

    private MutationResult cancelAppointment(
            AuthorizedTenantContext context, MutationCommand command) {
        var appointment = lockAppointment(context, command.targetId());
        requireRevision(appointment.revision(), command.expectedRevision());
        if (!appointment.state().equals("confirmed")) {
            throw conflict("Only a confirmed appointment can be cancelled.");
        }
        var reasonCode = field(command, "reasonCode");
        requireBoundedCode(reasonCode, "reasonCode");
        var slot = lockSlot(context, appointment.slotId());
        if (!slot.state().equals("booked")
                || !appointment.id().equals(slot.bookedAppointmentId())) {
            throw conflict("The appointment slot booking evidence is inconsistent.");
        }
        var databaseNow = databaseNow();
        var revision = appointment.revision() + 1;
        jdbc.update(
                """
                UPDATE appointments
                SET status='cancelled',cancelled_at=?,lock_version=?,
                    updated_at=clock_timestamp(),updated_by=?
                WHERE organization_id=? AND id=?
                """,
                Timestamp.from(databaseNow),
                revision,
                context.actorId(),
                context.organizationId(),
                appointment.id());
        jdbc.update(
                """
                UPDATE appointment_slots
                SET status='available',booked_appointment_id=NULL,held_by_request_id=NULL,
                    hold_token_digest=NULL,hold_expires_at=NULL,lock_version=?,
                    updated_at=clock_timestamp(),updated_by=?
                WHERE organization_id=? AND id=?
                """,
                slot.revision() + 1,
                context.actorId(),
                context.organizationId(),
                slot.id());
        jdbc.update(
                """
                INSERT INTO appointment_cancellations
                    (id,organization_id,appointment_id,reason_code,policy_version,
                     financial_outcome,cancelled_at,cancelled_by,created_by,updated_by)
                VALUES (?,?,?,?,?,'pending_policy',?,?,?,?)
                """,
                UuidV7Generator.randomUuid(),
                context.organizationId(),
                appointment.id(),
                reasonCode,
                POLICY_VERSION,
                Timestamp.from(databaseNow),
                context.actorId(),
                context.actorId(),
                context.actorId());
        insertHistory(
                context,
                appointment.id(),
                "confirmed",
                "cancelled",
                slot.id(),
                null,
                reasonCode,
                databaseNow,
                command.correlationId());
        var audit = map(
                "appointmentId", appointment.id(),
                "reasonCode", reasonCode,
                "policyVersion", POLICY_VERSION,
                "revision", revision);
        var outbox = map(
                "appointmentId", appointment.id(),
                "reasonCode", reasonCode,
                "policyVersion", POLICY_VERSION);
        return result(
                appointment.id(),
                appointment.patientId(),
                appointment.id(),
                appointment.requestId(),
                "appointment",
                "appointment.cancelled",
                "m4.appointment.cancelled.v1",
                "appointment",
                audit,
                outbox,
                200,
                revision);
    }

    private MutationResult recordNoShow(
            AuthorizedTenantContext context, MutationCommand command) {
        var appointment = lockAppointment(context, command.targetId());
        requireRevision(appointment.revision(), command.expectedRevision());
        if (!appointment.state().equals("confirmed")) {
            throw conflict("Only a confirmed appointment can be marked as no-show.");
        }
        var reasonCode = field(command, "reasonCode");
        var evidenceCode = field(command, "evidenceCode");
        requireBoundedCode(reasonCode, "reasonCode");
        requireBoundedCode(evidenceCode, "evidenceCode");
        var databaseNow = databaseNow();
        if (appointment.startsAt().isAfter(databaseNow)) {
            throw conflict("A no-show cannot be recorded before the appointment start time.");
        }
        var revision = appointment.revision() + 1;
        jdbc.update(
                """
                UPDATE appointments
                SET status='no_show',no_show_at=?,lock_version=?,
                    updated_at=clock_timestamp(),updated_by=?
                WHERE organization_id=? AND id=?
                """,
                Timestamp.from(databaseNow),
                revision,
                context.actorId(),
                context.organizationId(),
                appointment.id());
        jdbc.update(
                """
                INSERT INTO no_show_decisions
                    (id,organization_id,appointment_id,reason_code,evidence_code,
                     policy_version,decided_at,decided_by,created_by,updated_by)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                """,
                UuidV7Generator.randomUuid(),
                context.organizationId(),
                appointment.id(),
                reasonCode,
                evidenceCode,
                POLICY_VERSION,
                Timestamp.from(databaseNow),
                context.actorId(),
                context.actorId(),
                context.actorId());
        insertHistory(
                context,
                appointment.id(),
                "confirmed",
                "no_show",
                appointment.slotId(),
                appointment.slotId(),
                reasonCode,
                databaseNow,
                command.correlationId());
        var audit = map(
                "appointmentId", appointment.id(),
                "reasonCode", reasonCode,
                "evidenceCode", evidenceCode,
                "policyVersion", POLICY_VERSION,
                "revision", revision);
        var outbox = map(
                "appointmentId", appointment.id(),
                "reasonCode", reasonCode,
                "policyVersion", POLICY_VERSION);
        return result(
                appointment.id(),
                appointment.patientId(),
                appointment.id(),
                appointment.requestId(),
                "appointment",
                "appointment.no_show_recorded",
                "m4.appointment.no-show.v1",
                "appointment",
                audit,
                outbox,
                200,
                revision);
    }

    private MutationResult joinWaitlist(
            AuthorizedTenantContext context, MutationCommand command) {
        var patientId = fieldUuid(command, "patientId");
        var serviceId = fieldUuid(command, "serviceId");
        var facilityId = optionalFieldUuid(command, "facilityId");
        var locationId = optionalFieldUuid(command, "locationId");
        var earliestAt = fieldInstant(command, "earliestAt");
        var latestAt = fieldInstant(command, "latestAt");
        var priority = field(command, "priorityKey");
        if (!Set.of("standard", "urgent_review").contains(priority)) {
            throw invalid("priorityKey must be standard or urgent_review.");
        }
        if (!latestAt.isAfter(earliestAt)) {
            throw invalid("latestAt must be after earliestAt.");
        }
        if (!latestAt.isAfter(databaseNow())) {
            throw invalid("The waitlist window must include a future time.");
        }
        requireActivePatient(context, patientId);
        requireWaitlistContext(context, serviceId, facilityId, locationId);
        var id = UuidV7Generator.randomUuid();
        jdbc.update(
                """
                INSERT INTO waitlist_entries
                    (id,organization_id,patient_id,service_id,facility_id,location_id,
                     earliest_at,latest_at,priority_key,status,policy_version,
                     created_by,updated_by)
                VALUES (?,?,?,?,?,?,?,?,?,'waiting',?,?,?)
                """,
                id,
                context.organizationId(),
                patientId,
                serviceId,
                facilityId,
                locationId,
                Timestamp.from(earliestAt),
                Timestamp.from(latestAt),
                priority,
                POLICY_VERSION,
                context.actorId(),
                context.actorId());
        var audit = new LinkedHashMap<String, Object>();
        audit.put("waitlistEntryId", id);
        audit.put("patientId", patientId);
        audit.put("serviceId", serviceId);
        if (facilityId != null) audit.put("facilityId", facilityId);
        if (locationId != null) audit.put("locationId", locationId);
        audit.put("status", "waiting");
        audit.put("revision", 0);
        var outbox = map(
                "waitlistEntryId", id,
                "patientId", patientId,
                "serviceId", serviceId,
                "status", "waiting");
        return result(
                id,
                patientId,
                null,
                null,
                "waitlist_entry",
                "appointment.waitlist.joined",
                "m4.waitlist.changed.v1",
                "waitlist_entry",
                audit,
                outbox,
                201,
                0);
    }

    private MutationResult withdrawWaitlist(
            AuthorizedTenantContext context, MutationCommand command) {
        var entry = lockWaitlist(context, command.targetId());
        requireRevision(entry.revision(), command.expectedRevision());
        if (!entry.state().equals("waiting")) {
            throw conflict("Only a waiting entry can be withdrawn.");
        }
        var revision = entry.revision() + 1;
        jdbc.update(
                """
                UPDATE waitlist_entries
                SET status='withdrawn',lock_version=?,updated_at=clock_timestamp(),updated_by=?
                WHERE organization_id=? AND id=?
                """,
                revision,
                context.actorId(),
                context.organizationId(),
                entry.id());
        var audit = map(
                "waitlistEntryId", entry.id(),
                "patientId", entry.patientId(),
                "status", "withdrawn",
                "revision", revision);
        var outbox = map(
                "waitlistEntryId", entry.id(),
                "patientId", entry.patientId(),
                "serviceId", entry.serviceId(),
                "status", "withdrawn");
        return result(
                entry.id(),
                entry.patientId(),
                null,
                null,
                "waitlist_entry",
                "appointment.waitlist.withdrawn",
                "m4.waitlist.changed.v1",
                "waitlist_entry",
                audit,
                outbox,
                200,
                revision);
    }

    private void insertParticipants(
            AuthorizedTenantContext context,
            UUID appointmentId,
            UUID patientId,
            UUID practitionerId) {
        jdbc.update(
                """
                INSERT INTO appointment_participants
                    (id,organization_id,appointment_id,participant_type,patient_id,
                     role_key,status,created_by,updated_by)
                VALUES (?,?,?,'patient',?,'patient','active',?,?)
                """,
                UuidV7Generator.randomUuid(),
                context.organizationId(),
                appointmentId,
                patientId,
                context.actorId(),
                context.actorId());
        jdbc.update(
                """
                INSERT INTO appointment_participants
                    (id,organization_id,appointment_id,participant_type,
                     practitioner_profile_id,role_key,status,created_by,updated_by)
                VALUES (?,?,?,'practitioner',?,'primary-practitioner','active',?,?)
                """,
                UuidV7Generator.randomUuid(),
                context.organizationId(),
                appointmentId,
                practitionerId,
                context.actorId(),
                context.actorId());
    }

    private void insertAssignment(
            AuthorizedTenantContext context,
            UUID appointmentId,
            Slot slot,
            Eligibility eligibility) {
        jdbc.update(
                """
                INSERT INTO appointment_assignments
                    (id,organization_id,appointment_id,practitioner_profile_id,
                     practitioner_service_assignment_id,eligibility_evidence_id,
                     eligibility_digest,evaluated_for,status,created_by,updated_by)
                VALUES (?,?,?,?,?,?,?,?,'active',?,?)
                """,
                UuidV7Generator.randomUuid(),
                context.organizationId(),
                appointmentId,
                slot.practitionerId(),
                eligibility.assignmentId(),
                eligibility.evidenceId(),
                eligibility.digest(),
                Timestamp.from(slot.startsAt()),
                context.actorId(),
                context.actorId());
    }

    private void insertHistory(
            AuthorizedTenantContext context,
            UUID appointmentId,
            String fromStatus,
            String toStatus,
            UUID priorSlotId,
            UUID newSlotId,
            String reasonCode,
            Instant effectiveAt,
            String correlationId) {
        jdbc.update(
                """
                INSERT INTO appointment_status_history
                    (id,organization_id,appointment_id,from_status,to_status,prior_slot_id,
                     new_slot_id,reason_code,policy_version,effective_at,actor_id,
                     correlation_reference,created_by,updated_by)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                UuidV7Generator.randomUuid(),
                context.organizationId(),
                appointmentId,
                fromStatus,
                toStatus,
                priorSlotId,
                newSlotId,
                reasonCode,
                POLICY_VERSION,
                Timestamp.from(effectiveAt),
                context.actorId(),
                correlationReference(correlationId),
                context.actorId(),
                context.actorId());
    }

    private void requireScheduleReferences(
            AuthorizedTenantContext context,
            UUID serviceId,
            UUID facilityId,
            UUID locationId,
            UUID practitionerId,
            Instant effectiveFrom,
            Instant effectiveTo) {
        var valid = Boolean.TRUE.equals(jdbc.queryForObject(
                """
                SELECT EXISTS(SELECT 1 FROM service_definitions service
                       WHERE service.organization_id=? AND service.id=? AND service.status='active')
                   AND EXISTS(SELECT 1 FROM facilities facility
                       WHERE facility.organization_id=? AND facility.id=? AND facility.status='active')
                   AND EXISTS(SELECT 1 FROM service_locations location
                       WHERE location.organization_id=? AND location.id=? AND location.facility_id=?
                         AND location.status='active' AND location.effective_from<=?
                         AND (location.effective_to IS NULL OR location.effective_to>=?))
                   AND EXISTS(SELECT 1 FROM practitioner_profiles practitioner
                       WHERE practitioner.organization_id=? AND practitioner.id=?
                         AND practitioner.lifecycle_state='active'
                         AND practitioner.effective_from<=?
                         AND (practitioner.effective_to IS NULL OR practitioner.effective_to>=?))
                """,
                Boolean.class,
                context.organizationId(),
                serviceId,
                context.organizationId(),
                facilityId,
                context.organizationId(),
                locationId,
                facilityId,
                Timestamp.from(effectiveFrom),
                Timestamp.from(effectiveTo),
                context.organizationId(),
                practitionerId,
                Timestamp.from(effectiveFrom),
                Timestamp.from(effectiveTo)));
        if (!valid) {
            throw conflict(
                    "The schedule requires active service, facility, location and practitioner references for its effective window.");
        }
    }

    private void requireCareContext(
            AuthorizedTenantContext context, UUID serviceId, UUID facilityId, UUID locationId) {
        var valid = Boolean.TRUE.equals(jdbc.queryForObject(
                """
                SELECT EXISTS(SELECT 1 FROM service_definitions service
                       WHERE service.organization_id=? AND service.id=? AND service.status='active')
                   AND EXISTS(SELECT 1 FROM facilities facility
                       WHERE facility.organization_id=? AND facility.id=? AND facility.status='active')
                   AND EXISTS(SELECT 1 FROM service_locations location
                       WHERE location.organization_id=? AND location.id=? AND location.facility_id=?
                         AND location.status='active' AND location.effective_from<=clock_timestamp()
                         AND (location.effective_to IS NULL OR location.effective_to>clock_timestamp()))
                """,
                Boolean.class,
                context.organizationId(),
                serviceId,
                context.organizationId(),
                facilityId,
                context.organizationId(),
                locationId,
                facilityId));
        if (!valid) {
            throw conflict("The selected service, facility and location are not an active care context.");
        }
    }

    private void requireWaitlistContext(
            AuthorizedTenantContext context, UUID serviceId, UUID facilityId, UUID locationId) {
        if (locationId != null && facilityId == null) {
            throw invalid("facilityId is required when locationId is supplied.");
        }
        var serviceValid = Boolean.TRUE.equals(jdbc.queryForObject(
                """
                SELECT EXISTS(SELECT 1 FROM service_definitions
                    WHERE organization_id=? AND id=? AND status='active')
                """,
                Boolean.class,
                context.organizationId(),
                serviceId));
        if (!serviceValid) throw conflict("The selected service is not active.");
        if (facilityId != null) {
            var facilityValid = Boolean.TRUE.equals(jdbc.queryForObject(
                    """
                    SELECT EXISTS(SELECT 1 FROM facilities
                        WHERE organization_id=? AND id=? AND status='active')
                    """,
                    Boolean.class,
                    context.organizationId(),
                    facilityId));
            if (!facilityValid) throw conflict("The selected facility is not active.");
        }
        if (locationId != null) {
            var locationValid = Boolean.TRUE.equals(jdbc.queryForObject(
                    """
                    SELECT EXISTS(SELECT 1 FROM service_locations
                        WHERE organization_id=? AND id=? AND facility_id=? AND status='active'
                          AND effective_from<=clock_timestamp()
                          AND (effective_to IS NULL OR effective_to>clock_timestamp()))
                    """,
                    Boolean.class,
                    context.organizationId(),
                    locationId,
                    facilityId));
            if (!locationValid) throw conflict("The selected location is not active.");
        }
    }

    private void requireSelectablePractitioner(
            AuthorizedTenantContext context,
            UUID practitionerId,
            UUID serviceId,
            UUID facilityId,
            UUID locationId) {
        var valid = Boolean.TRUE.equals(jdbc.queryForObject(
                """
                SELECT EXISTS(
                    SELECT 1 FROM practitioner_profiles practitioner
                    JOIN practitioner_service_assignments assignment
                      ON assignment.organization_id=practitioner.organization_id
                     AND assignment.practitioner_profile_id=practitioner.id
                    WHERE practitioner.organization_id=? AND practitioner.id=?
                      AND practitioner.lifecycle_state='active'
                      AND assignment.service_id=? AND assignment.facility_id=?
                      AND (assignment.location_id IS NULL OR assignment.location_id=?)
                      AND assignment.lifecycle_state='active'
                      AND assignment.effective_from<=clock_timestamp()
                      AND (assignment.effective_to IS NULL
                           OR assignment.effective_to>clock_timestamp()))
                """,
                Boolean.class,
                context.organizationId(),
                practitionerId,
                serviceId,
                facilityId,
                locationId));
        if (!valid) {
            throw conflict("The practitioner has no active assignment for the selected care context.");
        }
    }

    private Eligibility requireEligibility(AuthorizedTenantContext context, Slot slot) {
        var matches = jdbc.query(
                """
                SELECT assignment.id assignment_id,evidence.id evidence_id,evidence.result_digest
                FROM practitioner_service_assignments assignment
                JOIN practitioner_eligibility_evidence evidence
                  ON evidence.organization_id=assignment.organization_id
                 AND evidence.id=assignment.eligibility_evidence_id
                 AND evidence.result_digest=assignment.eligibility_digest
                JOIN practitioner_profiles practitioner
                  ON practitioner.organization_id=assignment.organization_id
                 AND practitioner.id=assignment.practitioner_profile_id
                WHERE assignment.organization_id=?
                  AND assignment.practitioner_profile_id=? AND assignment.service_id=?
                  AND assignment.facility_id=?
                  AND (assignment.location_id IS NULL OR assignment.location_id=?)
                  AND assignment.lifecycle_state='active'
                  AND assignment.effective_from<=?
                  AND (assignment.effective_to IS NULL OR assignment.effective_to>?)
                  AND practitioner.lifecycle_state='active'
                  AND practitioner.effective_from<=?
                  AND (practitioner.effective_to IS NULL OR practitioner.effective_to>?)
                  AND evidence.practitioner_profile_id=assignment.practitioner_profile_id
                  AND evidence.service_id=assignment.service_id
                  AND evidence.facility_id=assignment.facility_id
                  AND (evidence.location_id IS NULL OR evidence.location_id=?)
                  AND evidence.outcome='eligible' AND evidence.status='eligible'
                  AND evidence.evaluated_from<=?
                  AND (evidence.evaluated_to IS NULL OR evidence.evaluated_to>?)
                  AND evidence.expires_at>clock_timestamp()
                ORDER BY (assignment.location_id IS NOT NULL) DESC,assignment.effective_from DESC
                LIMIT 1
                """,
                (resultSet, rowNumber) -> new Eligibility(
                        resultSet.getObject("assignment_id", UUID.class),
                        resultSet.getObject("evidence_id", UUID.class),
                        resultSet.getString("result_digest")),
                context.organizationId(),
                slot.practitionerId(),
                slot.serviceId(),
                slot.facilityId(),
                slot.locationId(),
                Timestamp.from(slot.startsAt()),
                Timestamp.from(slot.startsAt()),
                Timestamp.from(slot.startsAt()),
                Timestamp.from(slot.startsAt()),
                slot.locationId(),
                Timestamp.from(slot.startsAt()),
                Timestamp.from(slot.startsAt()));
        if (matches.isEmpty()) {
            throw new SchedulingException(
                    SchedulingException.Reason.POLICY_UNAVAILABLE,
                    "No current eligible practitioner evidence covers the selected appointment instant.");
        }
        return matches.getFirst();
    }

    private void requireActivePatient(AuthorizedTenantContext context, UUID patientId) {
        var active = Boolean.TRUE.equals(jdbc.queryForObject(
                """
                SELECT EXISTS(SELECT 1 FROM patient_profiles
                    WHERE organization_id=? AND id=? AND lifecycle_state='active'
                      AND deceased_state<>'deceased' AND merged_into_patient_id IS NULL)
                """,
                Boolean.class,
                context.organizationId(),
                patientId));
        if (!active) {
            throw conflict("The selected patient is not active and schedulable.");
        }
    }

    private Schedule lockSchedule(AuthorizedTenantContext context, UUID id) {
        var rows = jdbc.query(
                """
                SELECT id,service_id,facility_id,location_id,practitioner_profile_id,
                       timezone,effective_from,effective_to,slot_duration_minutes,
                       lifecycle_state,lock_version
                FROM appointment_schedules
                WHERE organization_id=? AND id=?
                FOR UPDATE
                """,
                (resultSet, rowNumber) -> new Schedule(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("service_id", UUID.class),
                        resultSet.getObject("facility_id", UUID.class),
                        resultSet.getObject("location_id", UUID.class),
                        resultSet.getObject("practitioner_profile_id", UUID.class),
                        resultSet.getString("timezone"),
                        resultSet.getTimestamp("effective_from").toInstant(),
                        resultSet.getTimestamp("effective_to").toInstant(),
                        resultSet.getInt("slot_duration_minutes"),
                        resultSet.getString("lifecycle_state"),
                        resultSet.getLong("lock_version")),
                context.organizationId(),
                id);
        if (rows.isEmpty()) throw notFound("The scheduling resource is unavailable.");
        return rows.getFirst();
    }

    private Request lockRequest(AuthorizedTenantContext context, UUID id) {
        var rows = jdbc.query(
                """
                SELECT id,patient_id,service_id,facility_id,location_id,
                       practitioner_profile_id,selected_slot_id,completed_appointment_id,
                       status,hold_token_digest,hold_expires_at,expires_at,lock_version
                FROM appointment_requests
                WHERE organization_id=? AND id=?
                FOR UPDATE
                """,
                (resultSet, rowNumber) -> new Request(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("patient_id", UUID.class),
                        resultSet.getObject("service_id", UUID.class),
                        resultSet.getObject("facility_id", UUID.class),
                        resultSet.getObject("location_id", UUID.class),
                        resultSet.getObject("practitioner_profile_id", UUID.class),
                        resultSet.getObject("selected_slot_id", UUID.class),
                        resultSet.getObject("completed_appointment_id", UUID.class),
                        resultSet.getString("status"),
                        resultSet.getString("hold_token_digest"),
                        timestamp(resultSet.getTimestamp("hold_expires_at")),
                        resultSet.getTimestamp("expires_at").toInstant(),
                        resultSet.getLong("lock_version")),
                context.organizationId(),
                id);
        if (rows.isEmpty()) throw notFound("The appointment request is unavailable.");
        return rows.getFirst();
    }

    private Slot lockSlot(AuthorizedTenantContext context, UUID id) {
        var rows = jdbc.query(
                """
                SELECT id,schedule_id,service_id,facility_id,location_id,
                       practitioner_profile_id,starts_at,ends_at,timezone_snapshot,status,
                       held_by_request_id,hold_token_digest,hold_expires_at,
                       booked_appointment_id,lock_version
                FROM appointment_slots
                WHERE organization_id=? AND id=?
                FOR UPDATE
                """,
                (resultSet, rowNumber) -> new Slot(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("schedule_id", UUID.class),
                        resultSet.getObject("service_id", UUID.class),
                        resultSet.getObject("facility_id", UUID.class),
                        resultSet.getObject("location_id", UUID.class),
                        resultSet.getObject("practitioner_profile_id", UUID.class),
                        resultSet.getTimestamp("starts_at").toInstant(),
                        resultSet.getTimestamp("ends_at").toInstant(),
                        resultSet.getString("timezone_snapshot"),
                        resultSet.getString("status"),
                        resultSet.getObject("held_by_request_id", UUID.class),
                        resultSet.getString("hold_token_digest"),
                        timestamp(resultSet.getTimestamp("hold_expires_at")),
                        resultSet.getObject("booked_appointment_id", UUID.class),
                        resultSet.getLong("lock_version")),
                context.organizationId(),
                id);
        if (rows.isEmpty()) throw notFound("The appointment slot is unavailable.");
        return rows.getFirst();
    }

    private Appointment lockAppointment(AuthorizedTenantContext context, UUID id) {
        var rows = jdbc.query(
                """
                SELECT id,source_request_id,patient_id,service_id,facility_id,location_id,
                       schedule_id,slot_id,starts_at,ends_at,timezone_snapshot,status,
                       reschedule_count,lock_version
                FROM appointments
                WHERE organization_id=? AND id=?
                FOR UPDATE
                """,
                (resultSet, rowNumber) -> new Appointment(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("source_request_id", UUID.class),
                        resultSet.getObject("patient_id", UUID.class),
                        resultSet.getObject("service_id", UUID.class),
                        resultSet.getObject("facility_id", UUID.class),
                        resultSet.getObject("location_id", UUID.class),
                        resultSet.getObject("schedule_id", UUID.class),
                        resultSet.getObject("slot_id", UUID.class),
                        resultSet.getTimestamp("starts_at").toInstant(),
                        resultSet.getTimestamp("ends_at").toInstant(),
                        resultSet.getString("timezone_snapshot"),
                        resultSet.getString("status"),
                        resultSet.getInt("reschedule_count"),
                        resultSet.getLong("lock_version")),
                context.organizationId(),
                id);
        if (rows.isEmpty()) throw notFound("The appointment is unavailable.");
        return rows.getFirst();
    }

    private Waitlist lockWaitlist(AuthorizedTenantContext context, UUID id) {
        var rows = jdbc.query(
                """
                SELECT id,patient_id,service_id,status,lock_version
                FROM waitlist_entries
                WHERE organization_id=? AND id=?
                FOR UPDATE
                """,
                (resultSet, rowNumber) -> new Waitlist(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("patient_id", UUID.class),
                        resultSet.getObject("service_id", UUID.class),
                        resultSet.getString("status"),
                        resultSet.getLong("lock_version")),
                context.organizationId(),
                id);
        if (rows.isEmpty()) throw notFound("The waitlist entry is unavailable.");
        return rows.getFirst();
    }

    private void requireEditableRequest(Request request, Long expectedRevision) {
        requireRevision(request.revision(), expectedRevision);
        if (!request.state().equals("collecting")) {
            throw conflict("Only a collecting appointment request can be edited.");
        }
        if (!request.expiresAt().isAfter(databaseNow())) {
            throw conflict("The appointment request expired; start a new request.");
        }
    }

    private static void requireRequestMatchesSlot(Request request, Slot slot) {
        if (!Objects.equals(request.serviceId(), slot.serviceId())
                || !Objects.equals(request.facilityId(), slot.facilityId())
                || !Objects.equals(request.locationId(), slot.locationId())
                || !Objects.equals(request.practitionerId(), slot.practitionerId())) {
            throw conflict("The held slot no longer matches the appointment request context.");
        }
    }

    private static void requireRevision(long actual, Long expected) {
        if (expected == null) {
            throw new SchedulingException(
                    SchedulingException.Reason.PRECONDITION_REQUIRED,
                    "A strong If-Match revision is required.");
        }
        if (actual != expected) {
            throw stale("The scheduling resource changed; refresh and review it before retrying.");
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
            throw notFound("The scheduling resource is unavailable or is not assigned to this account.");
        }
    }

    private static List<SchedulingScreen.Column> columns(String screenId) {
        return switch (screenId) {
            case "P4-02" -> List.of(
                    column("primary", "Service"),
                    column("secondary", "Practitioner"),
                    column("context", "Location"),
                    column("window", "Effective window"));
            case "P4-08" -> List.of(
                    column("primary", "Service"),
                    column("secondary", "Practitioner"),
                    column("startsAt", "Starts"),
                    column("context", "Location"));
            case "P4-14" -> List.of(
                    column("primary", "Service"),
                    column("secondary", "Patient"),
                    column("window", "Requested window"),
                    column("priority", "Priority"));
            case "P4-15" -> List.of(
                    column("primary", "Event"),
                    column("secondary", "Transition"),
                    column("effectiveAt", "Effective at"),
                    column("context", "Reason"));
            default -> List.of(
                    column("primary", "Item"),
                    column("secondary", "Patient / clinician"),
                    column("context", "Context"),
                    column("startsAt", "Starts"));
        };
    }

    private static List<SchedulingScreen.Notice> notices(String screenId) {
        var notices = new ArrayList<SchedulingScreen.Notice>();
        if (screenId.equals("P4-01")) {
            notices.add(notice(
                    "warning",
                    "External delivery disabled",
                    "Reminder and calendar-provider workers remain unavailable until approved adapters are configured."));
        }
        if (screenId.equals("P4-04")) {
            notices.add(notice(
                    "info",
                    "Staff workflow only",
                    "Portal and proxy booking remain unavailable pending approved authority and consent policy."));
        }
        if (screenId.equals("P4-10")) {
            notices.add(notice(
                    "info",
                    "Payment deferred",
                    "Scheduling records no amount, charge, refund or financial decision; Module 11 owns billing."));
        }
        if (screenId.equals("P4-14")) {
            notices.add(notice(
                    "warning",
                    "Manual waitlist only",
                    "Automated offers and outbound notifications are disabled until their policy and adapters are approved."));
        }
        return List.copyOf(notices);
    }

    private static SchedulingScreen.Row withAllowedActions(
            String screenId, SchedulingScreen.Row row) {
        var actions = new ArrayList<String>();
        var kind = row.values().getOrDefault("$kind", "");
        switch (screenId) {
            case "P4-02" -> {
                if (kind.equals("schedule") && row.status().equals("draft")) {
                    actions.add("activate-schedule");
                }
                if (kind.equals("schedule") && row.status().equals("active")) {
                    actions.add("create-slot");
                }
            }
            case "P4-05" -> {
                if (kind.equals("request") && row.status().equals("collecting")) {
                    actions.add("select-request-patient");
                }
            }
            case "P4-06" -> {
                if (kind.equals("request") && row.status().equals("collecting")) {
                    actions.add("set-request-context");
                }
            }
            case "P4-07" -> {
                if (kind.equals("request") && row.status().equals("collecting")) {
                    actions.add("select-request-practitioner");
                }
            }
            case "P4-08" -> {
                if (kind.equals("slot") && row.status().equals("available")) {
                    actions.add("hold-slot");
                }
            }
            case "P4-11" -> {
                if (kind.equals("request") && row.status().equals("held")) {
                    actions.add("confirm-appointment");
                }
            }
            case "P4-12" -> {
                if (kind.equals("appointment") && row.status().equals("confirmed")) {
                    actions.add("reschedule-appointment");
                }
            }
            case "P4-13" -> {
                if (kind.equals("appointment") && row.status().equals("confirmed")) {
                    actions.add("cancel-appointment");
                    actions.add("record-no-show");
                }
            }
            case "P4-14" -> {
                if (kind.equals("waitlist") && row.status().equals("waiting")) {
                    actions.add("withdraw-waitlist");
                }
            }
            default -> {
                // Link-only and read-only projections do not expose row mutations.
            }
        }
        var publicValues = new LinkedHashMap<>(row.values());
        publicValues.remove("$kind");
        return new SchedulingScreen.Row(
                row.id(),
                row.patientId(),
                row.appointmentId(),
                row.status(),
                row.revision(),
                row.etag(),
                publicValues,
                actions);
    }

    private static SchedulingScreen.Row row(
            String screenId,
            UUID id,
            UUID patientId,
            UUID appointmentId,
            String status,
            long revision,
            Map<String, String> values) {
        return new SchedulingScreen.Row(
                id,
                patientId,
                appointmentId,
                status,
                revision,
                "\"m4:" + screenId + ":" + id + ":" + revision + "\"",
                values,
                List.of());
    }

    private static MutationResult result(
            UUID subjectId,
            UUID patientId,
            UUID appointmentId,
            UUID requestId,
            String subjectType,
            String auditEvent,
            String outboxEvent,
            String aggregateType,
            Map<String, Object> audit,
            Map<String, Object> outbox,
            int statusCode,
            long revision) {
        return new MutationResult(
                subjectId,
                patientId,
                appointmentId,
                requestId,
                subjectType,
                auditEvent,
                outboxEvent,
                aggregateType,
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
            return UUID.fromString(value);
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

    private static int fieldInt(MutationCommand command, String key, int minimum, int maximum) {
        try {
            var value = Integer.parseInt(field(command, key));
            if (value < minimum || value > maximum) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException exception) {
            throw invalid(key + " must be between " + minimum + " and " + maximum + ".");
        }
    }

    private static void requireBoundedCode(String value, String key) {
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{1,79}")) {
            throw invalid(key + " must contain 2-80 safe code characters.");
        }
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

    private static UUID correlationReference(String value) {
        try {
            return UUID.fromString(value);
        } catch (Exception exception) {
            return UUID.nameUUIDFromBytes(("m4:" + value).getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to calculate scheduling evidence digest.", exception);
        }
    }

    private static Map<String, Object> map(Object... entries) {
        var values = new LinkedHashMap<String, Object>();
        for (var index = 0; index < entries.length; index += 2) {
            values.put((String) entries[index], entries[index + 1]);
        }
        return Map.copyOf(values);
    }

    private static Map<String, String> values(String... entries) {
        var values = new LinkedHashMap<String, String>();
        for (var index = 0; index < entries.length; index += 2) {
            values.put(entries[index], entries[index + 1]);
        }
        return values;
    }

    private static SchedulingScreen.Column column(String key, String label) {
        return new SchedulingScreen.Column(key, label);
    }

    private static SchedulingScreen.Metric metric(
            String key, String label, long value, String tone) {
        return new SchedulingScreen.Metric(key, label, value, tone);
    }

    private static SchedulingScreen.Notice notice(String tone, String title, String detail) {
        return new SchedulingScreen.Notice(tone, title, detail);
    }

    private static String instant(Timestamp value) {
        return value == null ? "Not selected" : value.toInstant().toString();
    }

    private static Instant timestamp(Timestamp value) {
        return value == null ? null : value.toInstant();
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

    private static String timelineTitle(String status) {
        return switch (status) {
            case "no_show" -> "No-show recorded";
            case "rescheduled" -> "Appointment rescheduled";
            case "cancelled" -> "Appointment cancelled";
            default -> "Appointment confirmed";
        };
    }

    private static String objectString(Object value) {
        return value == null ? "None" : value.toString();
    }

    private static SchedulingException invalid(String message) {
        return new SchedulingException(SchedulingException.Reason.INVALID, message);
    }

    private static SchedulingException notFound(String message) {
        return new SchedulingException(SchedulingException.Reason.NOT_FOUND, message);
    }

    private static SchedulingException conflict(String message) {
        return new SchedulingException(SchedulingException.Reason.CONFLICT, message);
    }

    private static SchedulingException stale(String message) {
        return new SchedulingException(SchedulingException.Reason.STALE, message);
    }

    private record Schedule(
            UUID id,
            UUID serviceId,
            UUID facilityId,
            UUID locationId,
            UUID practitionerId,
            String timezone,
            Instant effectiveFrom,
            Instant effectiveTo,
            int slotDurationMinutes,
            String state,
            long revision) {}

    private record Request(
            UUID id,
            UUID patientId,
            UUID serviceId,
            UUID facilityId,
            UUID locationId,
            UUID practitionerId,
            UUID slotId,
            UUID appointmentId,
            String state,
            String holdDigest,
            Instant holdExpiresAt,
            Instant expiresAt,
            long revision) {}

    private record Slot(
            UUID id,
            UUID scheduleId,
            UUID serviceId,
            UUID facilityId,
            UUID locationId,
            UUID practitionerId,
            Instant startsAt,
            Instant endsAt,
            String timezone,
            String state,
            UUID heldByRequestId,
            String holdDigest,
            Instant holdExpiresAt,
            UUID bookedAppointmentId,
            long revision) {}

    private record Appointment(
            UUID id,
            UUID requestId,
            UUID patientId,
            UUID serviceId,
            UUID facilityId,
            UUID locationId,
            UUID scheduleId,
            UUID slotId,
            Instant startsAt,
            Instant endsAt,
            String timezone,
            String state,
            int rescheduleCount,
            long revision) {}

    private record Eligibility(UUID assignmentId, UUID evidenceId, String digest) {}

    private record Waitlist(
            UUID id, UUID patientId, UUID serviceId, String state, long revision) {}
}
