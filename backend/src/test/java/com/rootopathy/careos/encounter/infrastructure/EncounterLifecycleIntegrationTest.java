package com.rootopathy.careos.encounter.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rootopathy.careos.encounter.application.EncounterService;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
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
class EncounterLifecycleIntegrationTest {
    private static final String MIGRATOR_USER = "careos_migrator";
    private static final String MIGRATOR_PASSWORD = "careos-migrator-test-only";
    private static final String APP_USER = "careos_app";
    private static final String APP_PASSWORD = "careos-app-test-only";
    private static final UUID ORGANIZATION_ID =
            UUID.fromString("01900000-0000-7000-8000-000000000001");
    private static final UUID FACILITY_ID =
            UUID.fromString("01900000-0000-7000-8000-000000000101");
    private static final UUID ACTOR_ID =
            UUID.fromString("019c0000-0000-7000-8000-000000000101");
    private static final UUID CHECKER_ID =
            UUID.fromString("019c0000-0000-7000-8000-000000000102");
    private static final UUID MEMBERSHIP_ID =
            UUID.fromString("019c0000-0000-7000-8000-000000000103");
    private static final UUID SERVICE_ID =
            UUID.fromString("019c0000-0000-7000-8000-000000000201");
    private static final UUID LOCATION_ID =
            UUID.fromString("019c0000-0000-7000-8000-000000000202");
    private static final UUID PRACTITIONER_ID =
            UUID.fromString("019c0000-0000-7000-8000-000000000301");
    private static final UUID WORKFORCE_MEMBER_ID =
            UUID.fromString("019c0000-0000-7000-8000-000000000304");
    private static final UUID PATIENT_ID =
            UUID.fromString("019c0000-0000-7000-8000-000000000401");
    private static final String DIGEST = "b".repeat(64);

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
    private EncounterService encounters;

    @Autowired
    private Clock clock;

    @BeforeEach
    void seedEncounterDependencies() throws SQLException {
        try (var connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), MIGRATOR_USER, MIGRATOR_PASSWORD);
                var statement = connection.createStatement()) {
            execute(
                    statement,
                    """
                    INSERT INTO users (id,email,display_name,status)
                    VALUES ('%s','m5.practitioner@example.test','M5 Practitioner','active'),
                           ('%s','m5.checker@example.test','M5 Checker','active')
                    """.formatted(ACTOR_ID, CHECKER_ID),
                    """
                    INSERT INTO organization_memberships
                        (id,organization_id,user_id,role_key,status,effective_from,updated_by)
                    VALUES ('%s','%s','%s','practitioner','active',
                            clock_timestamp()-interval '30 days','%s')
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
                    VALUES ('%s','%s','M5_TEST','M5 test consultation','active','%s','%s')
                    """.formatted(SERVICE_ID, ORGANIZATION_ID, ACTOR_ID, ACTOR_ID),
                    """
                    INSERT INTO service_locations
                        (id,organization_id,facility_id,location_code,location_type,name,
                         virtual_service_type,effective_from,status,created_by,updated_by)
                    VALUES ('%s','%s','%s','M5_ROOM','virtual','M5 test room','video',
                            clock_timestamp()-interval '30 days','active','%s','%s')
                    """.formatted(
                            LOCATION_ID, ORGANIZATION_ID, FACILITY_ID, ACTOR_ID, ACTOR_ID));
            seedPractitioner(statement);
            execute(
                    statement,
                    """
                    INSERT INTO access_assignment_scopes
                        (id,organization_id,access_assignment_id,workforce_member_id,
                         facility_id,location_id,grant_request_id,effective_from,status,
                         created_by,updated_by)
                    VALUES ('019c0000-0000-7000-8000-000000000312','%s','%s','%s','%s','%s',
                            '019c0000-0000-7000-8000-000000000313',
                            clock_timestamp()-interval '30 days','active','%s','%s')
                    """.formatted(
                            ORGANIZATION_ID,
                            MEMBERSHIP_ID,
                            WORKFORCE_MEMBER_ID,
                            FACILITY_ID,
                            LOCATION_ID,
                            ACTOR_ID,
                            ACTOR_ID),
                    """
                    INSERT INTO patient_profiles
                        (id,organization_id,patient_number,lifecycle_state,official_given_name,
                         official_family_name,name_state,provenance_source,created_by,updated_by)
                    VALUES ('%s','%s','M5PATIENT001','active','Encounter','Patient','provided',
                            'test_fixture','%s','%s')
                    """.formatted(PATIENT_ID, ORGANIZATION_ID, ACTOR_ID, ACTOR_ID));
        }
    }

    @Test
    void completesGovernedClinicalWorkflowAndRejectsEvidenceRewrite() throws Exception {
        var assurance = clock.instant();
        var plannedStart = assurance.plus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);

        act(
                "P5-02",
                "open-encounter",
                null,
                Map.of(
                        "patientId", PATIENT_ID.toString(),
                        "serviceId", SERVICE_ID.toString(),
                        "facilityId", FACILITY_ID.toString(),
                        "locationId", LOCATION_ID.toString(),
                        "responsiblePractitionerId", PRACTITIONER_ID.toString(),
                        "encounterType", "consultation",
                        "plannedStartAt", plannedStart.toString()),
                "Open the governed test encounter",
                "m5-open-encounter-0001",
                assurance);
        var encounterId = uuidValue(
                "SELECT id FROM encounters WHERE organization_id='%s'".formatted(ORGANIZATION_ID));
        var episodeId = uuidValue(
                "SELECT episode_of_care_id FROM encounters WHERE id='%s'".formatted(encounterId));

        act(
                "P5-03",
                "mark-arrived",
                encounterId,
                Map.of(),
                "Record the patient arrival",
                "m5-mark-arrived-0001",
                assurance);
        act(
                "P5-03",
                "start-encounter",
                encounterId,
                Map.of(),
                "Start the clinical encounter",
                "m5-start-encounter-0001",
                assurance);

        act(
                "P5-04",
                "add-practitioner-participant",
                encounterId,
                Map.of(
                        "practitionerId", PRACTITIONER_ID.toString(),
                        "roleKey", "attending"),
                "Add an attending participant",
                "m5-add-participant-0001",
                assurance);
        var attendingId = uuidValue("""
                SELECT id FROM encounter_participants
                WHERE encounter_id='%s' AND role_key='attending'
                """.formatted(encounterId));
        act(
                "P5-04",
                "remove-participant",
                attendingId,
                Map.of(),
                "Remove the attending participant",
                "m5-remove-participant-0001",
                assurance);

        act(
                "P5-05",
                "record-presenting-concern",
                encounterId,
                Map.of(
                        "authorPractitionerId", PRACTITIONER_ID.toString(),
                        "concernKind", "red_flag",
                        "description", "Acute chest discomfort requiring immediate review",
                        "onset", "Ten minutes before arrival",
                        "severityKey", "severe"),
                null,
                "m5-record-red-flag-0001",
                assurance);
        var escalationId = uuidValue(
                "SELECT id FROM red_flag_escalations WHERE encounter_id='%s'".formatted(encounterId));
        assertThat(longValue("""
                        SELECT count(*) FROM clinical_tasks
                        WHERE encounter_id='%s' AND priority_key='critical'
                          AND requires_acknowledgement
                        """.formatted(encounterId)))
                .isOne();
        act(
                "P5-08",
                "acknowledge-red-flag",
                escalationId,
                Map.of("practitionerId", PRACTITIONER_ID.toString()),
                "Acknowledge and assess the red flag",
                "m5-ack-red-flag-0001",
                assurance);
        act(
                "P5-08",
                "resolve-red-flag",
                escalationId,
                Map.of("practitionerId", PRACTITIONER_ID.toString()),
                "Resolve after documented clinical assessment",
                "m5-resolve-red-flag-0001",
                assurance);

        act(
                "P5-07",
                "record-clinical-problem",
                encounterId,
                Map.of(
                        "authorPractitionerId", PRACTITIONER_ID.toString(),
                        "displayText", "Acute chest discomfort",
                        "clinicalStatus", "active",
                        "verificationStatus", "confirmed"),
                "Record the confirmed clinical problem",
                "m5-record-problem-0001",
                assurance);
        var problemId = uuidValue(
                "SELECT id FROM clinical_problems WHERE encounter_id='%s'".formatted(encounterId));
        act(
                "P5-07",
                "record-diagnosis",
                encounterId,
                Map.of(
                        "authorPractitionerId", PRACTITIONER_ID.toString(),
                        "clinicalProblemId", problemId.toString(),
                        "displayText", "Chest pain under evaluation",
                        "certaintyKey", "provisional",
                        "diagnosisType", "working"),
                "Record the working diagnosis",
                "m5-record-diagnosis-0001",
                assurance);
        act(
                "P5-08",
                "create-order",
                encounterId,
                Map.of(
                        "requesterPractitionerId", PRACTITIONER_ID.toString(),
                        "orderTypeKey", "diagnostic_test",
                        "displayText", "Twelve lead electrocardiogram",
                        "instructionText", "Perform within the encounter",
                        "priorityKey", "urgent"),
                "Create the internal diagnostic order",
                "m5-create-order-0001",
                assurance);
        act(
                "P5-08",
                "create-clinical-task",
                encounterId,
                Map.of(
                        "ownerPractitionerId", PRACTITIONER_ID.toString(),
                        "taskTypeKey", "clinical_review",
                        "description", "Review diagnostic findings before completion",
                        "priorityKey", "urgent"),
                "Create the governed review task",
                "m5-create-task-0001",
                assurance);
        var taskId = uuidValue("""
                SELECT id FROM clinical_tasks
                WHERE encounter_id='%s' AND source_concern_id IS NULL
                """.formatted(encounterId));
        act(
                "P5-08",
                "progress-clinical-task",
                taskId,
                Map.of("nextStatus", "in_progress"),
                "Begin the clinical review task",
                "m5-progress-task-0001",
                assurance);
        act(
                "P5-08",
                "progress-clinical-task",
                taskId,
                Map.of("nextStatus", "completed"),
                "Complete the clinical review task",
                "m5-complete-task-0001",
                assurance);

        act(
                "P5-09",
                "save-note-version",
                encounterId,
                Map.of(
                        "authorPractitionerId", PRACTITIONER_ID.toString(),
                        "noteTypeKey", "encounter_note",
                        "content", "Initial assessment and immediate safety actions recorded.",
                        "lateEntry", "false"),
                null,
                "m5-save-note-v1-0001",
                assurance);
        var noteId = uuidValue(
                "SELECT id FROM encounter_notes WHERE encounter_id='%s'".formatted(encounterId));
        act(
                "P5-09",
                "save-note-version",
                noteId,
                Map.of(
                        "authorPractitionerId", PRACTITIONER_ID.toString(),
                        "noteTypeKey", "encounter_note",
                        "content", "Assessment reviewed; red flag resolved and follow-up documented.",
                        "lateEntry", "false"),
                null,
                "m5-save-note-v2-0001",
                assurance);
        assertThat(longValue(
                        "SELECT count(*) FROM note_versions WHERE encounter_note_id='%s'".formatted(noteId)))
                .isEqualTo(2);
        act(
                "P5-10",
                "sign-note",
                noteId,
                Map.of(
                        "signerPractitionerId", PRACTITIONER_ID.toString(),
                        "signatureMeaning", "author"),
                "Sign the reviewed encounter note",
                "m5-sign-note-0001",
                assurance);
        act(
                "P5-03",
                "complete-encounter",
                encounterId,
                Map.of(),
                "Complete after safety and documentation review",
                "m5-complete-encounter-0001",
                assurance);
        act(
                "P5-11",
                "amend-signed-note",
                noteId,
                Map.of(
                        "authorPractitionerId", PRACTITIONER_ID.toString(),
                        "amendmentText", "Late clarification: the patient received written return precautions."),
                "Clarify the signed discharge instruction record",
                "m5-amend-note-0001",
                assurance);

        assertThat(stringValue("SELECT status FROM encounters WHERE id='%s'".formatted(encounterId)))
                .isEqualTo("completed");
        assertThat(stringValue(
                        "SELECT status FROM encounter_notes WHERE id='%s'".formatted(noteId)))
                .isEqualTo("amended");
        assertThat(longValue(
                        "SELECT count(*) FROM encounter_status_history WHERE encounter_id='%s'"
                                .formatted(encounterId)))
                .isEqualTo(4);
        assertThat(longValue("""
                        SELECT count(*) FROM audit_events
                        WHERE organization_id='%s' AND event_name LIKE 'encounter.%%'
                        """.formatted(ORGANIZATION_ID)))
                .isEqualTo(19);
        assertThat(longValue("""
                        SELECT count(*) FROM outbox_events
                        WHERE organization_id='%s' AND event_name LIKE 'm5.%%'
                        """.formatted(ORGANIZATION_ID)))
                .isEqualTo(10);
        assertThat(encounters.screen(new EncounterService.ReadCommand(
                                ORGANIZATION_ID,
                                ACTOR_ID,
                                "m5-history-test",
                                "P5-12",
                                PATIENT_ID,
                                episodeId,
                                encounterId,
                                null,
                                null,
                                null,
                                50,
                                null,
                                assurance,
                                assurance))
                        .rows())
                .hasSizeGreaterThanOrEqualTo(7)
                .allSatisfy(row -> assertThat(row.values().values())
                        .noneMatch(value -> value.contains("written return precautions")));

        assertDirectClinicalRewriteRejected(problemId);
    }

    private void act(
            String screenId,
            String actionKey,
            UUID targetId,
            Map<String, String> fields,
            String reason,
            String idempotencyKey,
            Instant assurance)
            throws SQLException {
        var revision = targetId == null ? null : revision(targetId, actionKey);
        var ifMatch = targetId == null
                ? null
                : "\"m5:" + screenId + ":" + targetId + ":" + revision + "\"";
        encounters.act(new EncounterService.ActionCommand(
                ORGANIZATION_ID,
                ACTOR_ID,
                "m5-lifecycle-test",
                screenId,
                actionKey,
                targetId,
                PATIENT_ID,
                null,
                null,
                null,
                reason,
                fields,
                ifMatch,
                idempotencyKey,
                assurance,
                assurance));
    }

    private long revision(UUID id, String actionKey) throws SQLException {
        var table = switch (actionKey) {
            case "remove-participant" -> "encounter_participants";
            case "progress-clinical-task" -> "clinical_tasks";
            case "acknowledge-red-flag", "resolve-red-flag" -> "red_flag_escalations";
            case "sign-note", "amend-signed-note" -> "encounter_notes";
            case "save-note-version" -> nullableStringValue(
                            "SELECT 'encounter_notes' FROM encounter_notes WHERE id='%s'".formatted(id))
                    == null
                    ? "encounters"
                    : "encounter_notes";
            default -> "encounters";
        };
        return longValue("SELECT lock_version FROM %s WHERE id='%s'".formatted(table, id));
    }

    private void assertDirectClinicalRewriteRejected(UUID problemId) throws SQLException {
        try (var connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), APP_USER, APP_PASSWORD);
                var statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.execute("SELECT set_config('app.current_organization_id','%s',true)"
                    .formatted(ORGANIZATION_ID));
            statement.execute("SELECT set_config('app.current_actor_id','%s',true)"
                    .formatted(ACTOR_ID));
            statement.execute(
                    "SELECT set_config('app.current_operation_key','encounter.problem.write',true)");
            assertThatThrownBy(() -> statement.executeUpdate("""
                            UPDATE clinical_problems
                            SET display_text='Rewritten content',lock_version=lock_version+1,
                                updated_at=clock_timestamp(),updated_by='%s'
                            WHERE id='%s'
                            """.formatted(ACTOR_ID, problemId)))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("append-only");
            connection.rollback();
        }
    }

    private static void seedPractitioner(java.sql.Statement statement) throws SQLException {
        var personId = "019c0000-0000-7000-8000-000000000302";
        var personLinkId = "019c0000-0000-7000-8000-000000000303";
        var registryDefinitionId = "019c0000-0000-7000-8000-000000000305";
        var professionEntryId = "019c0000-0000-7000-8000-000000000306";
        var professionVersionId = "019c0000-0000-7000-8000-000000000307";
        var scopeDefinitionId = "019c0000-0000-7000-8000-000000000308";
        var scopeId = "019c0000-0000-7000-8000-000000000309";
        var evidenceId = "019c0000-0000-7000-8000-000000000310";
        var assignmentId = "019c0000-0000-7000-8000-000000000311";
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
                VALUES ('%s','%s','%s','clinical','M5CLINICIAN001','active',
                        clock_timestamp()-interval '30 days','active','%s','%s')
                """.formatted(
                        WORKFORCE_MEMBER_ID,
                        ORGANIZATION_ID,
                        personLinkId,
                        ACTOR_ID,
                        ACTOR_ID),
                """
                INSERT INTO workforce_registry_definitions
                    (id,organization_id,registry_key,category,display_name,value_schema,
                     review_cadence_days,lifecycle_state,status,created_by,updated_by)
                VALUES ('%s','%s','m5_test_profession','profession','M5 professions','{}',365,
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
                VALUES ('%s','%s','%s','%s','%s',true,'Test clinician',
                        clock_timestamp()-interval '30 days','active','active','%s','%s')
                """.formatted(
                        PRACTITIONER_ID,
                        ORGANIZATION_ID,
                        WORKFORCE_MEMBER_ID,
                        professionEntryId,
                        professionVersionId,
                        ACTOR_ID,
                        ACTOR_ID),
                """
                INSERT INTO scope_definitions
                    (id,organization_id,definition_code,name,profession_entry_id,
                     profession_version_id,service_id,jurisdiction_country,effective_from,
                     lifecycle_state,status,created_by,updated_by)
                VALUES ('%s','%s','M5_TEST_SCOPE','M5 test scope','%s','%s','%s','IN',
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
                        clock_timestamp()+interval '30 days','m5-test-evaluator','m5-test-catalogue',
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
        var value = nullableStringValue(sql);
        assertThat(value).isNotNull();
        return value;
    }

    private String nullableStringValue(String sql) throws SQLException {
        try (var connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), MIGRATOR_USER, MIGRATOR_PASSWORD);
                var statement = connection.createStatement();
                var resultSet = statement.executeQuery(sql)) {
            return resultSet.next() ? resultSet.getString(1) : null;
        }
    }

    private UUID uuidValue(String sql) throws SQLException {
        try (var connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), MIGRATOR_USER, MIGRATOR_PASSWORD);
                var statement = connection.createStatement();
                var resultSet = statement.executeQuery(sql)) {
            assertThat(resultSet.next()).isTrue();
            return resultSet.getObject(1, UUID.class);
        }
    }
}
