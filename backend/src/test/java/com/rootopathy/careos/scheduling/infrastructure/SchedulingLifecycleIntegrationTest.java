package com.rootopathy.careos.scheduling.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rootopathy.careos.scheduling.application.SchedulingException;
import com.rootopathy.careos.scheduling.application.SchedulingService;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class SchedulingLifecycleIntegrationTest {
    private static final String MIGRATOR_USER = "careos_migrator";
    private static final String MIGRATOR_PASSWORD = "careos-migrator-test-only";
    private static final String APP_USER = "careos_app";
    private static final String APP_PASSWORD = "careos-app-test-only";
    private static final UUID ORGANIZATION_ID =
            UUID.fromString("01900000-0000-7000-8000-000000000001");
    private static final UUID FACILITY_ID =
            UUID.fromString("01900000-0000-7000-8000-000000000101");
    private static final UUID ACTOR_ID =
            UUID.fromString("019b0000-0000-7000-8000-000000000101");
    private static final UUID CHECKER_ID =
            UUID.fromString("019b0000-0000-7000-8000-000000000102");
    private static final UUID MEMBERSHIP_ID =
            UUID.fromString("019b0000-0000-7000-8000-000000000103");
    private static final UUID SERVICE_ID =
            UUID.fromString("019b0000-0000-7000-8000-000000000201");
    private static final UUID LOCATION_ID =
            UUID.fromString("019b0000-0000-7000-8000-000000000202");
    private static final UUID PRACTITIONER_ID =
            UUID.fromString("019b0000-0000-7000-8000-000000000301");
    private static final UUID PATIENT_ID =
            UUID.fromString("019b0000-0000-7000-8000-000000000401");
    private static final String DIGEST = "a".repeat(64);

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse(
                            "postgres:18-alpine@sha256:d3e1620b530c944afa6e887d22eb899824da68e19c52024bf98f5220c88a65b2")
                    .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("careos_test")
            .withUsername(MIGRATOR_USER)
            .withPassword(MIGRATOR_PASSWORD)
            .withInitScript("db/test-init.sql");

    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse(
                    "redis:8-alpine@sha256:becdda6c7f4b3fb42e42fd7f120bbf5c54c4caaaf16f26da24e4563d2c1f0576"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void applicationProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> APP_USER);
        registry.add("spring.datasource.password", () -> APP_PASSWORD);
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", () -> MIGRATOR_USER);
        registry.add("spring.flyway.password", () -> MIGRATOR_PASSWORD);
        registry.add("spring.flyway.placeholders.applicationRole", () -> APP_USER);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired
    private SchedulingService scheduling;

    @Autowired
    private Clock clock;

    @BeforeEach
    void seedSchedulingDependencies() throws SQLException {
        try (var connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), MIGRATOR_USER, MIGRATOR_PASSWORD);
                var statement = connection.createStatement()) {
            execute(
                    statement,
                    """
                    INSERT INTO users (id,email,display_name,status)
                    VALUES ('%s','m4.owner@example.test','M4 Owner','active'),
                           ('%s','m4.checker@example.test','M4 Checker','active')
                    """.formatted(ACTOR_ID, CHECKER_ID),
                    """
                    INSERT INTO organization_memberships
                        (id,organization_id,user_id,role_key,status,effective_from,updated_by)
                    VALUES ('%s','%s','%s','organization_owner','active',
                            clock_timestamp()-interval '1 day','%s')
                    """.formatted(MEMBERSHIP_ID, ORGANIZATION_ID, ACTOR_ID, ACTOR_ID),
                    """
                    UPDATE organizations
                    SET organization_type='care_network',locale='en-IN',status='active'
                    WHERE id='%s';
                    UPDATE facilities SET status='active' WHERE id='%s'
                    """.formatted(ORGANIZATION_ID, FACILITY_ID),
                    """
                    INSERT INTO service_definitions
                        (id,organization_id,service_code,display_name,status,created_by,updated_by)
                    VALUES ('%s','%s','M4_TEST','M4 test consultation','active','%s','%s')
                    """.formatted(SERVICE_ID, ORGANIZATION_ID, ACTOR_ID, ACTOR_ID),
                    """
                    INSERT INTO service_locations
                        (id,organization_id,facility_id,location_code,location_type,name,virtual_service_type,
                         effective_from,status,created_by,updated_by)
                    VALUES ('%s','%s','%s','M4_ROOM','virtual','M4 test room','video',
                            clock_timestamp()-interval '30 days','active','%s','%s')
                    """.formatted(
                            LOCATION_ID, ORGANIZATION_ID, FACILITY_ID, ACTOR_ID, ACTOR_ID));
            seedPractitioner(statement);
            statement.executeUpdate("""
                    INSERT INTO patient_profiles
                        (id,organization_id,patient_number,lifecycle_state,official_given_name,
                         official_family_name,name_state,provenance_source,created_by,updated_by)
                    VALUES ('%s','%s','M4PATIENT001','active','Schedule','Patient','provided',
                            'test_fixture','%s','%s')
                    """.formatted(PATIENT_ID, ORGANIZATION_ID, ACTOR_ID, ACTOR_ID));
        }
    }

    @Test
    void completesLifecycleAndRejectsConcurrentOrDirectStateAttacks() throws Exception {
        var assurance = clock.instant();
        var start = assurance.plus(2, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MINUTES);
        var scheduleFrom = assurance.plus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MINUTES);
        var scheduleTo = assurance.plus(8, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MINUTES);

        act(
                "P4-02",
                "create-schedule",
                null,
                null,
                Map.of(
                        "serviceId", SERVICE_ID.toString(),
                        "facilityId", FACILITY_ID.toString(),
                        "locationId", LOCATION_ID.toString(),
                        "practitionerId", PRACTITIONER_ID.toString(),
                        "timezone", "Asia/Kolkata",
                        "effectiveFrom", scheduleFrom.toString(),
                        "effectiveTo", scheduleTo.toString(),
                        "slotDurationMinutes", "30"),
                "Create the governed test schedule",
                "m4-create-schedule-0001",
                assurance);
        var scheduleId = uuidValue(
                "SELECT id FROM appointment_schedules WHERE organization_id='%s'"
                        .formatted(ORGANIZATION_ID));
        act(
                "P4-02",
                "activate-schedule",
                scheduleId,
                null,
                Map.of(),
                "Activate the governed test schedule",
                "m4-activate-schedule-0001",
                assurance);
        createSlot(scheduleId, start, "m4-create-slot-first-0001", assurance);
        createSlot(
                scheduleId,
                start.plus(1, ChronoUnit.HOURS),
                "m4-create-slot-second-0001",
                assurance);
        createSlot(
                scheduleId,
                start.plus(2, ChronoUnit.HOURS),
                "m4-create-slot-race-0001",
                assurance);
        createSlot(
                scheduleId,
                start.plus(3, ChronoUnit.HOURS),
                "m4-create-slot-no-show-0001",
                assurance);
        var slots = uuidValues("""
                SELECT id FROM appointment_slots WHERE schedule_id='%s' ORDER BY starts_at
                """.formatted(scheduleId));

        act(
                "P4-04",
                "start-appointment-request",
                null,
                null,
                Map.of(
                        "patientId", PATIENT_ID.toString(),
                        "requestSource", "staff",
                        "purposeKey", "direct_care"),
                null,
                "m4-start-request-0001",
                assurance);
        var requestId = uuidValue(
                "SELECT id FROM appointment_requests WHERE organization_id='%s'"
                        .formatted(ORGANIZATION_ID));
        act(
                "P4-06",
                "set-request-context",
                requestId,
                null,
                Map.of(
                        "serviceId", SERVICE_ID.toString(),
                        "facilityId", FACILITY_ID.toString(),
                        "locationId", LOCATION_ID.toString()),
                null,
                "m4-set-context-0001",
                assurance);
        act(
                "P4-07",
                "select-request-practitioner",
                requestId,
                null,
                Map.of("practitionerId", PRACTITIONER_ID.toString()),
                null,
                "m4-select-practitioner-0001",
                assurance);
        act(
                "P4-08",
                "hold-slot",
                slots.getFirst(),
                requestId,
                Map.of("slotId", slots.getFirst().toString()),
                null,
                "m4-hold-slot-0001",
                assurance);
        act(
                "P4-11",
                "confirm-appointment",
                requestId,
                requestId,
                Map.of(),
                "Confirm the governed test appointment",
                "m4-confirm-appointment-0001",
                assurance);
        var appointmentId = uuidValue(
                "SELECT id FROM appointments WHERE source_request_id='%s'".formatted(requestId));

        act(
                "P4-12",
                "reschedule-appointment",
                appointmentId,
                requestId,
                Map.of("newSlotId", slots.get(1).toString()),
                "Move the governed test appointment",
                "m4-reschedule-appointment-0001",
                assurance);
        act(
                "P4-13",
                "cancel-appointment",
                appointmentId,
                requestId,
                Map.of("reasonCode", "patient_requested"),
                "Cancel the governed test appointment",
                "m4-cancel-appointment-0001",
                assurance);

        act(
                "P4-14",
                "join-waitlist",
                null,
                null,
                Map.of(
                        "patientId", PATIENT_ID.toString(),
                        "serviceId", SERVICE_ID.toString(),
                        "facilityId", FACILITY_ID.toString(),
                        "locationId", LOCATION_ID.toString(),
                        "earliestAt", start.toString(),
                        "latestAt", start.plus(1, ChronoUnit.DAYS).toString(),
                        "priorityKey", "standard"),
                "Join the governed scheduling waitlist",
                "m4-join-waitlist-0001",
                assurance);
        var waitlistId = uuidValue(
                "SELECT id FROM waitlist_entries WHERE organization_id='%s'"
                        .formatted(ORGANIZATION_ID));
        act(
                "P4-14",
                "withdraw-waitlist",
                waitlistId,
                null,
                Map.of(),
                "Withdraw the governed waitlist request",
                "m4-withdraw-waitlist-0001",
                assurance);

        var raceRequestOne = createConfiguredRequest("race-one", assurance);
        var raceRequestTwo = createConfiguredRequest("race-two", assurance);
        assertExactlyOneConcurrentHold(slots.get(2), raceRequestOne, raceRequestTwo, assurance);

        var noShowRequest = createConfiguredRequest("no-show", assurance);
        act(
                "P4-08",
                "hold-slot",
                slots.get(3),
                noShowRequest,
                Map.of("slotId", slots.get(3).toString()),
                null,
                "m4-hold-slot-no-show-0001",
                assurance);
        act(
                "P4-11",
                "confirm-appointment",
                noShowRequest,
                noShowRequest,
                Map.of(),
                "Confirm the governed no-show test appointment",
                "m4-confirm-no-show-0001",
                assurance);
        var noShowAppointmentId = uuidValue(
                "SELECT id FROM appointments WHERE source_request_id='%s'"
                        .formatted(noShowRequest));
        executeAsMigrator(
                "UPDATE appointments SET starts_at=clock_timestamp()-interval '1 minute' WHERE id='%s'"
                        .formatted(noShowAppointmentId));
        act(
                "P4-13",
                "record-no-show",
                noShowAppointmentId,
                noShowRequest,
                Map.of("reasonCode", "patient_absent", "evidenceCode", "staff_verified"),
                "Record the verified governed no-show outcome",
                "m4-record-no-show-0001",
                assurance);

        assertDirectAppointmentRewriteRejected(appointmentId);

        assertThat(stringValue(
                        "SELECT status FROM appointments WHERE id='%s'".formatted(appointmentId)))
                .isEqualTo("cancelled");
        assertThat(longValue(
                        "SELECT reschedule_count FROM appointments WHERE id='%s'"
                                .formatted(appointmentId)))
                .isOne();
        assertThat(longValue(
                        "SELECT count(*) FROM appointment_status_history WHERE appointment_id='%s'"
                                .formatted(appointmentId)))
                .isEqualTo(3);
        assertThat(longValue("""
                        SELECT count(*) FROM audit_events
                        WHERE organization_id='%s' AND event_name LIKE 'appointment.%%'
                        """.formatted(ORGANIZATION_ID)))
                .isEqualTo(31);
        assertThat(longValue("""
                        SELECT count(*) FROM outbox_events
                        WHERE organization_id='%s' AND event_name LIKE 'm4.%%'
                        """.formatted(ORGANIZATION_ID)))
                .isEqualTo(7);
        assertThat(stringValue(
                        "SELECT status FROM appointments WHERE id='%s'"
                                .formatted(noShowAppointmentId)))
                .isEqualTo("no_show");
        assertThat(scheduling.screen(new SchedulingService.ReadCommand(
                                ORGANIZATION_ID,
                                ACTOR_ID,
                                "m4-timeline-test",
                                "P4-15",
                                PATIENT_ID,
                                appointmentId,
                                requestId,
                                null,
                                null,
                                50,
                                null,
                                assurance,
                                assurance))
                        .rows())
                .hasSize(3);
    }

    private UUID createConfiguredRequest(String suffix, Instant assurance) throws SQLException {
        act(
                "P4-04",
                "start-appointment-request",
                null,
                null,
                Map.of(
                        "patientId", PATIENT_ID.toString(),
                        "requestSource", "staff",
                        "purposeKey", "direct_care"),
                null,
                "m4-start-" + suffix + "-0001",
                assurance);
        var requestId = uuidValue("""
                SELECT id FROM appointment_requests
                WHERE organization_id='%s'
                ORDER BY id DESC LIMIT 1
                """.formatted(ORGANIZATION_ID));
        act(
                "P4-05",
                "select-request-patient",
                requestId,
                requestId,
                Map.of("patientId", PATIENT_ID.toString()),
                null,
                "m4-patient-" + suffix + "-0001",
                assurance);
        act(
                "P4-06",
                "set-request-context",
                requestId,
                requestId,
                Map.of(
                        "serviceId", SERVICE_ID.toString(),
                        "facilityId", FACILITY_ID.toString(),
                        "locationId", LOCATION_ID.toString()),
                null,
                "m4-context-" + suffix + "-0001",
                assurance);
        act(
                "P4-07",
                "select-request-practitioner",
                requestId,
                requestId,
                Map.of("practitionerId", PRACTITIONER_ID.toString()),
                null,
                "m4-practitioner-" + suffix + "-0001",
                assurance);
        return requestId;
    }

    private void assertExactlyOneConcurrentHold(
            UUID slotId, UUID firstRequestId, UUID secondRequestId, Instant assurance)
            throws Exception {
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(
                    () -> concurrentHold(slotId, firstRequestId, "m4-race-hold-first-0001", assurance, ready, start));
            var second = executor.submit(
                    () -> concurrentHold(slotId, secondRequestId, "m4-race-hold-second-0001", assurance, ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            var outcomes = java.util.Arrays.asList(
                    first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
            assertThat(outcomes).filteredOn(java.util.Objects::isNull).hasSize(1);
            assertThat(outcomes)
                    .filteredOn(java.util.Objects::nonNull)
                    .singleElement()
                    .satisfies(error -> {
                        assertThat(error).isInstanceOf(SchedulingException.class);
                        assertThat(((SchedulingException) error).reason())
                                .isIn(
                                        SchedulingException.Reason.CONFLICT,
                                        SchedulingException.Reason.STALE);
                    });
        }
        assertThat(stringValue("SELECT status FROM appointment_slots WHERE id='%s'"
                        .formatted(slotId)))
                .isEqualTo("held");
        assertThat(uuidValue("SELECT held_by_request_id FROM appointment_slots WHERE id='%s'"
                        .formatted(slotId)))
                .isIn(firstRequestId, secondRequestId);
    }

    private Throwable concurrentHold(
            UUID slotId,
            UUID requestId,
            String idempotencyKey,
            Instant assurance,
            CountDownLatch ready,
            CountDownLatch start) {
        try {
            ready.countDown();
            if (!start.await(10, TimeUnit.SECONDS)) {
                return new AssertionError("The concurrent hold start gate timed out.");
            }
            scheduling.act(new SchedulingService.ActionCommand(
                    ORGANIZATION_ID,
                    ACTOR_ID,
                    "m4-concurrent-hold-test",
                    "P4-08",
                    "hold-slot",
                    slotId,
                    PATIENT_ID,
                    null,
                    requestId,
                    null,
                    Map.of("slotId", slotId.toString()),
                    "\"m4:P4-08:" + slotId + ":0\"",
                    idempotencyKey,
                    assurance,
                    assurance));
            return null;
        } catch (Throwable error) {
            return error;
        }
    }

    private void assertDirectAppointmentRewriteRejected(UUID appointmentId) throws SQLException {
        try (var connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), APP_USER, APP_PASSWORD);
                var statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.execute("SELECT set_config('app.current_organization_id','%s',true)"
                    .formatted(ORGANIZATION_ID));
            statement.execute("SELECT set_config('app.current_actor_id','%s',true)"
                    .formatted(ACTOR_ID));
            statement.execute(
                    "SELECT set_config('app.current_operation_key','appointment.cancel',true)");
            assertThatThrownBy(() -> statement.executeUpdate("""
                            UPDATE appointments
                            SET status='confirmed',cancelled_at=NULL,lock_version=lock_version+1,
                                updated_at=clock_timestamp(),updated_by='%s'
                            WHERE id='%s'
                            """.formatted(ACTOR_ID, appointmentId)))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("invalid Module 4 cancellation transition");
            connection.rollback();
        }
    }

    private static void executeAsMigrator(String sql) throws SQLException {
        try (var connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), MIGRATOR_USER, MIGRATOR_PASSWORD);
                var statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private void createSlot(
            UUID scheduleId, Instant startsAt, String idempotencyKey, Instant assurance)
            throws SQLException {
        act(
                "P4-02",
                "create-slot",
                scheduleId,
                null,
                Map.of(
                        "startsAt", startsAt.toString(),
                        "endsAt", startsAt.plus(30, ChronoUnit.MINUTES).toString()),
                "Create a governed appointment slot",
                idempotencyKey,
                assurance);
    }

    private void act(
            String screenId,
            String actionKey,
            UUID targetId,
            UUID requestId,
            Map<String, String> fields,
            String reason,
            String idempotencyKey,
            Instant assurance)
            throws SQLException {
        var revision = targetId == null ? null : revision(targetId, actionKey);
        var ifMatch = targetId == null
                ? null
                : "\"m4:" + screenId + ":" + targetId + ":" + revision + "\"";
        scheduling.act(new SchedulingService.ActionCommand(
                ORGANIZATION_ID,
                ACTOR_ID,
                "m4-lifecycle-test",
                screenId,
                actionKey,
                targetId,
                PATIENT_ID,
                null,
                requestId,
                reason,
                fields,
                ifMatch,
                idempotencyKey,
                assurance,
                assurance));
    }

    private long revision(UUID id, String actionKey) throws SQLException {
        var table = switch (actionKey) {
            case "activate-schedule", "create-slot" -> "appointment_schedules";
            case "select-request-patient", "set-request-context", "select-request-practitioner", "confirm-appointment" ->
                "appointment_requests";
            case "hold-slot" -> "appointment_slots";
            case "reschedule-appointment", "cancel-appointment", "record-no-show" ->
                "appointments";
            case "withdraw-waitlist" -> "waitlist_entries";
            default -> throw new IllegalArgumentException("No revision table for " + actionKey);
        };
        return longValue("SELECT lock_version FROM %s WHERE id='%s'".formatted(table, id));
    }

    private static void seedPractitioner(java.sql.Statement statement) throws SQLException {
        var personId = "019b0000-0000-7000-8000-000000000302";
        var personLinkId = "019b0000-0000-7000-8000-000000000303";
        var memberId = "019b0000-0000-7000-8000-000000000304";
        var registryDefinitionId = "019b0000-0000-7000-8000-000000000305";
        var professionEntryId = "019b0000-0000-7000-8000-000000000306";
        var professionVersionId = "019b0000-0000-7000-8000-000000000307";
        var scopeDefinitionId = "019b0000-0000-7000-8000-000000000308";
        var scopeId = "019b0000-0000-7000-8000-000000000309";
        var evidenceId = "019b0000-0000-7000-8000-000000000310";
        var assignmentId = "019b0000-0000-7000-8000-000000000311";
        execute(
                statement,
                """
                INSERT INTO person_profiles
                    (id,legal_given_name,legal_family_name,display_name,created_by,updated_by)
                VALUES ('%s','Test','Practitioner','Test Practitioner','%s','%s')
                """.formatted(personId, ACTOR_ID, ACTOR_ID),
                """
                INSERT INTO organization_person_links
                    (id,organization_id,person_id,display_label,relationship_status,
                     effective_from,status,created_by,updated_by)
                VALUES ('%s','%s','%s','Test Practitioner','active',
                        clock_timestamp()-interval '30 days','active','%s','%s')
                """.formatted(personLinkId, ORGANIZATION_ID, personId, ACTOR_ID, ACTOR_ID),
                """
                INSERT INTO workforce_members
                    (id,organization_id,organization_person_link_id,pathway,member_number,
                     lifecycle_state,activated_at,status,created_by,updated_by)
                VALUES ('%s','%s','%s','clinical','M4CLINICIAN001','active',
                        clock_timestamp()-interval '30 days','active','%s','%s')
                """.formatted(memberId, ORGANIZATION_ID, personLinkId, ACTOR_ID, ACTOR_ID),
                """
                INSERT INTO workforce_registry_definitions
                    (id,organization_id,registry_key,category,display_name,value_schema,
                     review_cadence_days,lifecycle_state,status,created_by,updated_by)
                VALUES ('%s','%s','m4_test_profession','profession','M4 professions','{}',365,
                        'active','active','%s','%s')
                """.formatted(registryDefinitionId, ORGANIZATION_ID, ACTOR_ID, ACTOR_ID),
                """
                INSERT INTO workforce_registry_entries
                    (id,organization_id,registry_definition_id,entry_key,code,display_label,
                     lifecycle_state,status,created_by,updated_by)
                VALUES ('%s','%s','%s','test_practitioner','TEST_PRACTITIONER',
                        'Test practitioner','active','active','%s','%s')
                """.formatted(
                        professionEntryId,
                        ORGANIZATION_ID,
                        registryDefinitionId,
                        ACTOR_ID,
                        ACTOR_ID),
                """
                INSERT INTO workforce_registry_versions
                    (id,organization_id,registry_entry_id,version_number,version_fields,
                     version_digest,effective_from,maker_id,checker_id,decision_code,
                     activated_at,lifecycle_state,status,created_by,updated_by)
                VALUES ('%s','%s','%s',1,'{}','%s',clock_timestamp()-interval '30 days',
                        '%s','%s','approved_for_test',clock_timestamp()-interval '30 days',
                        'active','active','%s','%s')
                """.formatted(
                        professionVersionId,
                        ORGANIZATION_ID,
                        professionEntryId,
                        DIGEST,
                        ACTOR_ID,
                        CHECKER_ID,
                        ACTOR_ID,
                        ACTOR_ID),
                """
                INSERT INTO practitioner_profiles
                    (id,organization_id,workforce_member_id,profession_entry_id,
                     profession_version_id,regulated,clinical_title,effective_from,
                     lifecycle_state,status,created_by,updated_by)
                VALUES ('%s','%s','%s','%s','%s',false,'Test clinician',
                        clock_timestamp()-interval '30 days','active','active','%s','%s')
                """.formatted(
                        PRACTITIONER_ID,
                        ORGANIZATION_ID,
                        memberId,
                        professionEntryId,
                        professionVersionId,
                        ACTOR_ID,
                        ACTOR_ID),
                """
                INSERT INTO scope_definitions
                    (id,organization_id,definition_code,name,profession_entry_id,
                     profession_version_id,service_id,jurisdiction_country,effective_from,
                     lifecycle_state,status,created_by,updated_by)
                VALUES ('%s','%s','M4_TEST_SCOPE','M4 test scope','%s','%s','%s','IN',
                        clock_timestamp()-interval '30 days','active','active','%s','%s')
                """.formatted(
                        scopeDefinitionId,
                        ORGANIZATION_ID,
                        professionEntryId,
                        professionVersionId,
                        SERVICE_ID,
                        ACTOR_ID,
                        ACTOR_ID),
                """
                INSERT INTO scopes_of_practice
                    (id,organization_id,practitioner_profile_id,scope_definition_id,
                     definition_version_digest,effective_from,submitted_revision,result_digest,
                     submitted_by,submitted_at,decided_by,decision_code,decided_at,
                     lifecycle_state,status,created_by,updated_by)
                VALUES ('%s','%s','%s','%s','%s',clock_timestamp()-interval '30 days',0,'%s',
                        '%s',clock_timestamp()-interval '30 days','%s','approved_for_test',
                        clock_timestamp()-interval '30 days','approved','approved','%s','%s')
                """.formatted(
                        scopeId,
                        ORGANIZATION_ID,
                        PRACTITIONER_ID,
                        scopeDefinitionId,
                        DIGEST,
                        DIGEST,
                        ACTOR_ID,
                        CHECKER_ID,
                        ACTOR_ID,
                        ACTOR_ID),
                """
                INSERT INTO practitioner_eligibility_evidence
                    (id,organization_id,practitioner_profile_id,service_id,facility_id,
                     location_id,evaluated_from,evaluated_to,evaluator_version,catalogue_version,
                     registration_evidence,credential_evidence,scope_evidence,assignment_evidence,
                     supervision_evidence,outcome,result_digest,evaluated_at,expires_at,status,
                     created_by,updated_by)
                VALUES ('%s','%s','%s','%s','%s','%s',clock_timestamp()-interval '1 day',
                        clock_timestamp()+interval '30 days','m4-test-evaluator','m4-test-catalogue',
                        '{}','{}','{}','{}','{}','eligible','%s',clock_timestamp(),
                        clock_timestamp()+interval '30 days','eligible','%s','%s')
                """.formatted(
                        evidenceId,
                        ORGANIZATION_ID,
                        PRACTITIONER_ID,
                        SERVICE_ID,
                        FACILITY_ID,
                        LOCATION_ID,
                        DIGEST,
                        ACTOR_ID,
                        ACTOR_ID),
                """
                INSERT INTO practitioner_service_assignments
                    (id,organization_id,practitioner_profile_id,service_id,facility_id,
                     location_id,scope_of_practice_id,effective_from,eligibility_evidence_id,
                     eligibility_digest,lifecycle_state,status,created_by,updated_by)
                VALUES ('%s','%s','%s','%s','%s','%s','%s',clock_timestamp()-interval '30 days',
                        '%s','%s','active','active','%s','%s')
                """.formatted(
                        assignmentId,
                        ORGANIZATION_ID,
                        PRACTITIONER_ID,
                        SERVICE_ID,
                        FACILITY_ID,
                        LOCATION_ID,
                        scopeId,
                        evidenceId,
                        DIGEST,
                        ACTOR_ID,
                        ACTOR_ID));
    }

    private static void execute(java.sql.Statement statement, String... statements)
            throws SQLException {
        for (var sql : statements) statement.executeUpdate(sql);
    }

    private long longValue(String sql) throws SQLException {
        try (var connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), MIGRATOR_USER, MIGRATOR_PASSWORD);
                var statement = connection.createStatement();
                var resultSet = statement.executeQuery(sql)) {
            assertThat(resultSet.next()).isTrue();
            return resultSet.getLong(1);
        }
    }

    private String stringValue(String sql) throws SQLException {
        try (var connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), MIGRATOR_USER, MIGRATOR_PASSWORD);
                var statement = connection.createStatement();
                var resultSet = statement.executeQuery(sql)) {
            assertThat(resultSet.next()).isTrue();
            return resultSet.getString(1);
        }
    }

    private UUID uuidValue(String sql) throws SQLException {
        return uuidValues(sql).getFirst();
    }

    private java.util.List<UUID> uuidValues(String sql) throws SQLException {
        try (var connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), MIGRATOR_USER, MIGRATOR_PASSWORD);
                var statement = connection.createStatement();
                var resultSet = statement.executeQuery(sql)) {
            var values = new java.util.ArrayList<UUID>();
            while (resultSet.next()) values.add(resultSet.getObject(1, UUID.class));
            assertThat(values).isNotEmpty();
            return values;
        }
    }
}
