package com.rootopathy.careos.careplan.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rootopathy.careos.careplan.application.CarePlanService;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(classes = com.rootopathy.careos.CareOsApplication.class)
@ActiveProfiles("test")
@Testcontainers
class CarePlanLifecycleIntegrationTest {
    private static final String MIGRATOR_USER = "careos_migrator";
    private static final String MIGRATOR_PASSWORD = "careos-migrator-test-only";
    private static final String APP_USER = "careos_app";
    private static final String APP_PASSWORD = "careos-app-test-only";
    private static final UUID ORGANIZATION_ID =
            UUID.fromString("01900000-0000-7000-8000-000000000001");
    private static final UUID FACILITY_ID =
            UUID.fromString("01900000-0000-7000-8000-000000000101");
    private static final UUID ACTOR_ID =
            UUID.fromString("019f1000-0000-7000-8000-000000000101");
    private static final UUID CHECKER_ID =
            UUID.fromString("019f1000-0000-7000-8000-000000000102");
    private static final UUID MEMBERSHIP_ID =
            UUID.fromString("019f1000-0000-7000-8000-000000000103");
    private static final UUID SERVICE_ID =
            UUID.fromString("019f1000-0000-7000-8000-000000000201");
    private static final UUID LOCATION_ID =
            UUID.fromString("019f1000-0000-7000-8000-000000000202");
    private static final UUID WORKFORCE_MEMBER_ID =
            UUID.fromString("019f1000-0000-7000-8000-000000000301");
    private static final UUID PRACTITIONER_ID =
            UUID.fromString("019f1000-0000-7000-8000-000000000302");
    private static final UUID PATIENT_ID =
            UUID.fromString("019f1000-0000-7000-8000-000000000401");
    private static final UUID EPISODE_ID =
            UUID.fromString("019f1000-0000-7000-8000-000000000501");
    private static final UUID ENCOUNTER_ID =
            UUID.fromString("019f1000-0000-7000-8000-000000000502");
    private static final String DIGEST = "9".repeat(64);

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
    private CarePlanService carePlans;

    @Autowired
    private Clock clock;

    @BeforeEach
    void seedDependencies() throws SQLException {
        try (var connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), MIGRATOR_USER, MIGRATOR_PASSWORD);
                var statement = connection.createStatement()) {
            execute(
                    statement,
                    """
                    INSERT INTO users (id,email,display_name,status)
                    VALUES ('%s','m9.practitioner@example.test','M9 Practitioner','active'),
                           ('%s','m9.checker@example.test','M9 Checker','active')
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
                    VALUES ('%s','%s','M9_TEST','M9 coordinated planning','active','%s','%s')
                    """.formatted(SERVICE_ID, ORGANIZATION_ID, ACTOR_ID, ACTOR_ID),
                    """
                    INSERT INTO service_locations
                        (id,organization_id,facility_id,location_code,location_type,name,
                         virtual_service_type,effective_from,status,created_by,updated_by)
                    VALUES ('%s','%s','%s','M9_ROOM','virtual','M9 test room','video',
                            clock_timestamp()-interval '30 days','active','%s','%s')
                    """.formatted(LOCATION_ID, ORGANIZATION_ID, FACILITY_ID, ACTOR_ID, ACTOR_ID),
                    """
                    INSERT INTO person_profiles
                        (id,legal_given_name,legal_family_name,display_name,created_by,updated_by)
                    VALUES ('019f1000-0000-7000-8000-000000000303','M9','Practitioner',
                            'M9 Practitioner','%s','%s')
                    """.formatted(ACTOR_ID, ACTOR_ID),
                    """
                    INSERT INTO organization_person_links
                        (id,organization_id,person_id,display_label,relationship_status,
                         effective_from,status,created_by,updated_by)
                    VALUES ('019f1000-0000-7000-8000-000000000304','%s',
                            '019f1000-0000-7000-8000-000000000303','M9 Practitioner','active',
                            clock_timestamp()-interval '30 days','active','%s','%s')
                    """.formatted(ORGANIZATION_ID, ACTOR_ID, ACTOR_ID),
                    """
                    INSERT INTO workforce_members
                        (id,organization_id,organization_person_link_id,pathway,member_number,
                         lifecycle_state,activated_at,status,created_by,updated_by)
                    VALUES ('%s','%s','019f1000-0000-7000-8000-000000000304','clinical',
                            'M9CLINICIAN001','active',clock_timestamp()-interval '30 days',
                            'active','%s','%s')
                    """.formatted(WORKFORCE_MEMBER_ID, ORGANIZATION_ID, ACTOR_ID, ACTOR_ID),
                    """
                    INSERT INTO workforce_registry_definitions
                        (id,organization_id,registry_key,category,display_name,value_schema,
                         review_cadence_days,lifecycle_state,status,created_by,updated_by)
                    VALUES ('019f1000-0000-7000-8000-000000000305','%s','m9_test_profession',
                            'profession','M9 professions','{}',365,'active','active','%s','%s')
                    """.formatted(ORGANIZATION_ID, ACTOR_ID, ACTOR_ID),
                    """
                    INSERT INTO workforce_registry_entries
                        (id,organization_id,registry_definition_id,entry_key,code,display_label,
                         lifecycle_state,status,created_by,updated_by)
                    VALUES ('019f1000-0000-7000-8000-000000000306','%s',
                            '019f1000-0000-7000-8000-000000000305','test_practitioner',
                            'TEST_PRACTITIONER','Test practitioner','active','active','%s','%s')
                    """.formatted(ORGANIZATION_ID, ACTOR_ID, ACTOR_ID),
                    """
                    INSERT INTO workforce_registry_versions
                        (id,organization_id,registry_entry_id,version_number,version_fields,
                         version_digest,effective_from,maker_id,checker_id,decision_code,
                         activated_at,lifecycle_state,status,created_by,updated_by)
                    VALUES ('019f1000-0000-7000-8000-000000000307','%s',
                            '019f1000-0000-7000-8000-000000000306',1,'{}','%s',
                            clock_timestamp()-interval '30 days','%s','%s','approved_for_test',
                            clock_timestamp()-interval '30 days','active','active','%s','%s')
                    """.formatted(ORGANIZATION_ID, DIGEST, ACTOR_ID, CHECKER_ID, ACTOR_ID, ACTOR_ID),
                    """
                    INSERT INTO practitioner_profiles
                        (id,organization_id,workforce_member_id,profession_entry_id,
                         profession_version_id,regulated,clinical_title,effective_from,
                         lifecycle_state,status,created_by,updated_by)
                    VALUES ('%s','%s','%s','019f1000-0000-7000-8000-000000000306',
                            '019f1000-0000-7000-8000-000000000307',true,'Test clinician',
                            clock_timestamp()-interval '30 days','active','active','%s','%s')
                    """.formatted(PRACTITIONER_ID, ORGANIZATION_ID, WORKFORCE_MEMBER_ID, ACTOR_ID, ACTOR_ID),
                    """
                    INSERT INTO access_assignment_scopes
                        (id,organization_id,access_assignment_id,workforce_member_id,
                         facility_id,location_id,grant_request_id,effective_from,status,
                         created_by,updated_by)
                    VALUES ('019f1000-0000-7000-8000-000000000308','%s','%s','%s','%s','%s',
                            '019f1000-0000-7000-8000-000000000309',
                            clock_timestamp()-interval '30 days','active','%s','%s')
                    """.formatted(
                            ORGANIZATION_ID, MEMBERSHIP_ID, WORKFORCE_MEMBER_ID,
                            FACILITY_ID, LOCATION_ID, ACTOR_ID, ACTOR_ID),
                    """
                    INSERT INTO patient_profiles
                        (id,organization_id,patient_number,lifecycle_state,official_given_name,
                         official_family_name,name_state,provenance_source,verification_state,
                         created_by,updated_by)
                    VALUES ('%s','%s','M9PATIENT001','active','Coordinated','Plan Patient',
                            'provided','test_fixture','evidence_checked','%s','%s')
                    """.formatted(PATIENT_ID, ORGANIZATION_ID, ACTOR_ID, ACTOR_ID),
                    """
                    INSERT INTO episodes_of_care
                        (id,organization_id,patient_id,service_id,facility_id,location_id,
                         managing_practitioner_id,status,started_at,created_by,updated_by)
                    VALUES ('%s','%s','%s','%s','%s','%s','%s','active',
                            clock_timestamp()-interval '1 hour','%s','%s')
                    """.formatted(
                            EPISODE_ID, ORGANIZATION_ID, PATIENT_ID, SERVICE_ID, FACILITY_ID,
                            LOCATION_ID, PRACTITIONER_ID, ACTOR_ID, ACTOR_ID),
                    """
                    INSERT INTO encounters
                        (id,organization_id,episode_of_care_id,patient_id,service_id,facility_id,
                         location_id,responsible_practitioner_id,source_kind,encounter_type_key,
                         status,planned_start_at,in_progress_at,created_by,updated_by)
                    VALUES ('%s','%s','%s','%s','%s','%s','%s','%s','unscheduled','consultation',
                            'in_progress',clock_timestamp()-interval '30 minutes',
                            clock_timestamp()-interval '20 minutes','%s','%s')
                    """.formatted(
                            ENCOUNTER_ID, ORGANIZATION_ID, EPISODE_ID, PATIENT_ID, SERVICE_ID,
                            FACILITY_ID, LOCATION_ID, PRACTITIONER_ID, ACTOR_ID, ACTOR_ID));
        }
    }

    @Test
    void completesVersionedOwnedSafeApprovedAndAmendedPlanWorkflow() throws Exception {
        var assurance = clock.instant();
        act("P9-02", "create-plan", null,
                Map.of(
                        "patientId", PATIENT_ID.toString(),
                        "encounterId", ENCOUNTER_ID.toString(),
                        "responsiblePractitionerId", PRACTITIONER_ID.toString(),
                        "planTitle", "Coordinated recovery plan",
                        "clinicalSummary", "Coordinate monitored recovery across the approved care team.",
                        "patientSummary", "Your care team will coordinate the agreed plan and review progress."),
                null, "Create a patient-centred coordinated plan.", "m9:create:integration-0001", assurance);
        var planId = uuidValue("SELECT id FROM care_plans");

        act("P9-03", "add-priority", planId,
                Map.of(
                        "sourceType", "clinician",
                        "displayText", "Restore safe daily activity",
                        "rationale", "This priority reflects the current assessment and patient concern.",
                        "priority", "important"),
                etag("P9-03", planId, 1), "Record the agreed first clinical priority.",
                "m9:priority:integration-0001", assurance);
        act("P9-04", "add-goal", planId,
                Map.of(
                        "goalType", "patient_stated",
                        "description", "Return to a comfortable daily walking routine.",
                        "measure", "Comfortable walking duration",
                        "target", "Thirty minutes without red-flag symptoms",
                        "targetDate", assurance.plus(30, ChronoUnit.DAYS).toString().substring(0, 10),
                        "priority", "important"),
                etag("P9-04", planId, 2), "Record the patient's measurable goal.",
                "m9:goal:integration-0001", assurance);
        act("P9-05", "add-intervention", planId,
                Map.of(
                        "modalityKey", "supervised_movement",
                        "interventionName", "Supervised graded movement",
                        "rationale", "A monitored progression supports the agreed functional goal.",
                        "priority", "important",
                        "startDate", assurance.plus(1, ChronoUnit.DAYS).toString().substring(0, 10),
                        "reviewDate", assurance.plus(14, ChronoUnit.DAYS).toString().substring(0, 10),
                        "stopCriteria", "Stop and obtain clinical review if red-flag symptoms emerge.",
                        "monitoring", "Record tolerance, duration, symptoms and recovery at each review.",
                        "evidenceStatus", "limited"),
                etag("P9-05", planId, 3), "Add a bounded monitored intervention.",
                "m9:intervention:integration-0001", assurance);
        var interventionId = uuidValue("SELECT id FROM care_plan_interventions");
        act("P9-07", "assign-owner-task", planId,
                Map.of(
                        "interventionId", interventionId.toString(),
                        "ownerPractitionerId", PRACTITIONER_ID.toString(),
                        "responsibility", "Own delivery, monitoring, review and escalation for this intervention.",
                        "startDate", assurance.plus(1, ChronoUnit.DAYS).toString().substring(0, 10),
                        "reviewDate", assurance.plus(14, ChronoUnit.DAYS).toString().substring(0, 10),
                        "taskDescription", "Review tolerance and documented monitoring before progression.",
                        "taskPriority", "urgent",
                        "dueAt", assurance.plus(14, ChronoUnit.DAYS).toString()),
                etag("P9-07", planId, 4), "Assign accountable ownership and a review task.",
                "m9:assignment:integration-0001", assurance);
        act("P9-08", "record-consent", planId,
                Map.of(
                        "consentStatus", "granted",
                        "consentReference", "M9-CONSENT-001",
                        "preferences", "The patient prefers gradual progression and shared review decisions.",
                        "communicationNeeds", "Use plain language and provide the review date in writing.",
                        "recordedByPractitionerId", PRACTITIONER_ID.toString()),
                etag("P9-08", planId, 5), "Record consent and visible patient preferences.",
                "m9:consent:integration-0001", assurance);
        act("P9-09", "record-interaction-review", planId,
                Map.of(
                        "modalities", "supervised_movement,clinical_monitoring",
                        "interactionFindings", "The initial combination requires sequencing changes before approval.",
                        "safetyOutcome", "needs_changes",
                        "reviewedByPractitionerId", PRACTITIONER_ID.toString()),
                etag("P9-09", planId, 6), "Record the initial cross-modality safety finding.",
                "m9:safety-unsafe:integration-0001", assurance);

        assertThatThrownBy(() -> act("P9-10", "submit-plan", planId, Map.of(),
                        etag("P9-10", planId, 7),
                        "Attempt review submission before a clear safety outcome.",
                        "m9:unsafe-submit:integration-0001", assurance))
                .isInstanceOf(DataIntegrityViolationException.class);

        act("P9-09", "record-interaction-review", planId,
                Map.of(
                        "modalities", "supervised_movement,clinical_monitoring",
                        "interactionFindings", "Sequencing, monitoring and stop criteria are coordinated with no unresolved interaction.",
                        "safetyOutcome", "clear",
                        "reviewedByPractitionerId", PRACTITIONER_ID.toString()),
                etag("P9-09", planId, 7), "Record the resolved cross-modality safety review.",
                "m9:safety-clear:integration-0001", assurance);
        act("P9-10", "submit-plan", planId, Map.of(), etag("P9-10", planId, 8),
                "Submit the complete exact plan version for approval.",
                "m9:submit:integration-0001", assurance);
        act("P9-10", "approve-plan", planId,
                Map.of("approverPractitionerId", PRACTITIONER_ID.toString()),
                etag("P9-10", planId, 9), "Approve the exact complete and safety-reviewed version.",
                "m9:approve:integration-0001", assurance);
        act("P9-10", "activate-plan", planId, Map.of(), etag("P9-10", planId, 10),
                "Activate the independently approved coordinated plan.",
                "m9:activate:integration-0001", assurance);

        assertThat(stringValue("SELECT status FROM care_plans WHERE id='%s'".formatted(planId)))
                .isEqualTo("active");
        assertThat(longValue("SELECT count(*) FROM clinical_tasks WHERE care_plan_id='%s'".formatted(planId)))
                .isEqualTo(1);
        assertThat(longValue("SELECT count(*) FROM care_plan_approvals WHERE care_plan_id='%s'".formatted(planId)))
                .isEqualTo(1);

        act("P9-12", "amend-plan", planId,
                Map.of(
                        "amendedByPractitionerId", PRACTITIONER_ID.toString(),
                        "amendmentSummary", "Create a successor version for the next monitored phase.",
                        "clinicalSummary", "Coordinate the next monitored phase after the initial review.",
                        "patientSummary", "Your next plan phase is a draft until your clinician reviews and approves it."),
                etag("P9-12", planId, 11), "Amend through a new draft successor without overwriting evidence.",
                "m9:amend:integration-0001", assurance);

        assertThat(stringValue("SELECT status FROM care_plans WHERE id='%s'".formatted(planId)))
                .isEqualTo("revised");
        assertThat(longValue("SELECT count(*) FROM care_plans")).isEqualTo(2);
        assertThat(longValue("SELECT count(*) FROM care_plan_amendments")).isEqualTo(1);
        assertThat(longValue("SELECT count(*) FROM audit_events WHERE event_name LIKE 'care_plan.%%'"))
                .isGreaterThanOrEqualTo(11);
        assertThat(stringValue(
                        "SELECT payload::text FROM outbox_events WHERE event_name='m9.care-plan-approved.v1'"))
                .doesNotContain("walking", "movement", "M9-CONSENT-001");

        var successorId = uuidValue("SELECT id FROM care_plans WHERE supersedes_care_plan_id IS NOT NULL");
        var history = carePlans.screen(new CarePlanService.ReadCommand(
                ORGANIZATION_ID, ACTOR_ID, "m9-history-correlation", "P9-12",
                PATIENT_ID, ENCOUNTER_ID, successorId, null, null, 25, null,
                assurance, assurance));
        assertThat(history.rows()).singleElement().satisfies(row -> {
            assertThat(row.status()).isEqualTo("draft");
            assertThat(row.values().get("version")).isEqualTo("2");
            assertThat(row.values().get("supersedes")).isEqualTo(planId.toString());
        });

        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), APP_USER, APP_PASSWORD)) {
            assertThatThrownBy(() -> connection.createStatement().executeUpdate(
                            "UPDATE care_plan_goals SET description_text='tampered'"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("permission denied");
        }
    }

    private void act(
            String screen,
            String action,
            UUID target,
            Map<String, String> fields,
            String etag,
            String reason,
            String idempotency,
            java.time.Instant assurance) {
        carePlans.act(new CarePlanService.ActionCommand(
                ORGANIZATION_ID, ACTOR_ID, "m9-" + action + "-correlation", screen, action,
                target, reason, fields, etag, idempotency, assurance, assurance));
    }

    private static String etag(String screen, UUID planId, long revision) {
        return "\"m9:" + screen + ":" + planId + ":" + revision + "\"";
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
        try (var connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), MIGRATOR_USER, MIGRATOR_PASSWORD);
                var statement = connection.createStatement();
                var resultSet = statement.executeQuery(sql)) {
            assertThat(resultSet.next()).isTrue();
            return resultSet.getObject(1, UUID.class);
        }
    }
}
