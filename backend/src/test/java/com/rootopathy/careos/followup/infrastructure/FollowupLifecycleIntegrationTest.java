package com.rootopathy.careos.followup.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rootopathy.careos.followup.application.FollowupService;
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
import org.springframework.test.annotation.DirtiesContext;
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
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class FollowupLifecycleIntegrationTest {
    private static final String MIGRATOR_USER = "careos_migrator";
    private static final String MIGRATOR_PASSWORD = "careos-migrator-test-only";
    private static final String APP_USER = "careos_app";
    private static final String APP_PASSWORD = "careos-app-test-only";
    private static final UUID ORGANIZATION_ID =
            UUID.fromString("01900000-0000-7000-8000-000000000001");
    private static final UUID FACILITY_ID =
            UUID.fromString("01900000-0000-7000-8000-000000000101");
    private static final UUID ACTOR_ID =
            UUID.fromString("019f2000-0000-7000-8000-000000000101");
    private static final UUID CHECKER_ID =
            UUID.fromString("019f2000-0000-7000-8000-000000000102");
    private static final UUID MEMBERSHIP_ID =
            UUID.fromString("019f2000-0000-7000-8000-000000000103");
    private static final UUID SERVICE_ID =
            UUID.fromString("019f2000-0000-7000-8000-000000000201");
    private static final UUID LOCATION_ID =
            UUID.fromString("019f2000-0000-7000-8000-000000000202");
    private static final UUID WORKFORCE_MEMBER_ID =
            UUID.fromString("019f2000-0000-7000-8000-000000000301");
    private static final UUID PRACTITIONER_ID =
            UUID.fromString("019f2000-0000-7000-8000-000000000302");
    private static final UUID PATIENT_ID =
            UUID.fromString("019f2000-0000-7000-8000-000000000401");
    private static final UUID EPISODE_ID =
            UUID.fromString("019f2000-0000-7000-8000-000000000501");
    private static final UUID ENCOUNTER_ID =
            UUID.fromString("019f2000-0000-7000-8000-000000000502");
    private static final UUID CARE_PLAN_ID =
            UUID.fromString("019f2000-0000-7000-8000-000000000601");
    private static final UUID CARE_PLAN_VERSION_ID =
            UUID.fromString("019f2000-0000-7000-8000-000000000602");
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
    private FollowupService followups;

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
                    VALUES ('%s','m10.practitioner@example.test','M10 Practitioner','active'),
                           ('%s','m10.checker@example.test','M10 Checker','active')
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
                    VALUES ('%s','%s','M10_TEST','M10 outcome monitoring','active','%s','%s')
                    """.formatted(SERVICE_ID, ORGANIZATION_ID, ACTOR_ID, ACTOR_ID),
                    """
                    INSERT INTO service_locations
                        (id,organization_id,facility_id,location_code,location_type,name,
                         virtual_service_type,effective_from,status,created_by,updated_by)
                    VALUES ('%s','%s','%s','M10_ROOM','virtual','M10 test room','video',
                            clock_timestamp()-interval '30 days','active','%s','%s')
                    """.formatted(LOCATION_ID, ORGANIZATION_ID, FACILITY_ID, ACTOR_ID, ACTOR_ID),
                    """
                    INSERT INTO person_profiles
                        (id,legal_given_name,legal_family_name,display_name,created_by,updated_by)
                    VALUES ('019f2000-0000-7000-8000-000000000303','M10','Practitioner',
                            'M10 Practitioner','%s','%s')
                    """.formatted(ACTOR_ID, ACTOR_ID),
                    """
                    INSERT INTO organization_person_links
                        (id,organization_id,person_id,display_label,relationship_status,
                         effective_from,status,created_by,updated_by)
                    VALUES ('019f2000-0000-7000-8000-000000000304','%s',
                            '019f2000-0000-7000-8000-000000000303','M10 Practitioner','active',
                            clock_timestamp()-interval '30 days','active','%s','%s')
                    """.formatted(ORGANIZATION_ID, ACTOR_ID, ACTOR_ID),
                    """
                    INSERT INTO workforce_members
                        (id,organization_id,organization_person_link_id,pathway,member_number,
                         lifecycle_state,activated_at,status,created_by,updated_by)
                    VALUES ('%s','%s','019f2000-0000-7000-8000-000000000304','clinical',
                            'M10CLINICIAN001','active',clock_timestamp()-interval '30 days',
                            'active','%s','%s')
                    """.formatted(WORKFORCE_MEMBER_ID, ORGANIZATION_ID, ACTOR_ID, ACTOR_ID),
                    """
                    INSERT INTO workforce_registry_definitions
                        (id,organization_id,registry_key,category,display_name,value_schema,
                         review_cadence_days,lifecycle_state,status,created_by,updated_by)
                    VALUES ('019f2000-0000-7000-8000-000000000305','%s','m10_test_profession',
                            'profession','M10 professions','{}',365,'active','active','%s','%s')
                    """.formatted(ORGANIZATION_ID, ACTOR_ID, ACTOR_ID),
                    """
                    INSERT INTO workforce_registry_entries
                        (id,organization_id,registry_definition_id,entry_key,code,display_label,
                         lifecycle_state,status,created_by,updated_by)
                    VALUES ('019f2000-0000-7000-8000-000000000306','%s',
                            '019f2000-0000-7000-8000-000000000305','test_practitioner',
                            'TEST_PRACTITIONER','Test practitioner','active','active','%s','%s')
                    """.formatted(ORGANIZATION_ID, ACTOR_ID, ACTOR_ID),
                    """
                    INSERT INTO workforce_registry_versions
                        (id,organization_id,registry_entry_id,version_number,version_fields,
                         version_digest,effective_from,maker_id,checker_id,decision_code,
                         activated_at,lifecycle_state,status,created_by,updated_by)
                    VALUES ('019f2000-0000-7000-8000-000000000307','%s',
                            '019f2000-0000-7000-8000-000000000306',1,'{}','%s',
                            clock_timestamp()-interval '30 days','%s','%s','approved_for_test',
                            clock_timestamp()-interval '30 days','active','active','%s','%s')
                    """.formatted(ORGANIZATION_ID, DIGEST, ACTOR_ID, CHECKER_ID, ACTOR_ID, ACTOR_ID),
                    """
                    INSERT INTO practitioner_profiles
                        (id,organization_id,workforce_member_id,profession_entry_id,
                         profession_version_id,regulated,clinical_title,effective_from,
                         lifecycle_state,status,created_by,updated_by)
                    VALUES ('%s','%s','%s','019f2000-0000-7000-8000-000000000306',
                            '019f2000-0000-7000-8000-000000000307',true,'Test clinician',
                            clock_timestamp()-interval '30 days','active','active','%s','%s')
                    """.formatted(PRACTITIONER_ID, ORGANIZATION_ID, WORKFORCE_MEMBER_ID, ACTOR_ID, ACTOR_ID),
                    """
                    INSERT INTO access_assignment_scopes
                        (id,organization_id,access_assignment_id,workforce_member_id,
                         facility_id,location_id,grant_request_id,effective_from,status,
                         created_by,updated_by)
                    VALUES ('019f2000-0000-7000-8000-000000000308','%s','%s','%s','%s','%s',
                            '019f2000-0000-7000-8000-000000000309',
                            clock_timestamp()-interval '30 days','active','%s','%s')
                    """.formatted(
                            ORGANIZATION_ID, MEMBERSHIP_ID, WORKFORCE_MEMBER_ID,
                            FACILITY_ID, LOCATION_ID, ACTOR_ID, ACTOR_ID),
                    """
                    INSERT INTO patient_profiles
                        (id,organization_id,patient_number,lifecycle_state,official_given_name,
                         official_family_name,name_state,provenance_source,verification_state,
                         created_by,updated_by)
                    VALUES ('%s','%s','M10PATIENT001','active','Outcome','Monitoring Patient',
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
                            FACILITY_ID, LOCATION_ID, PRACTITIONER_ID, ACTOR_ID, ACTOR_ID),
                    """
                    INSERT INTO care_plans(
                        id,organization_id,patient_id,encounter_id,responsible_practitioner_id,
                        plan_title,status,created_by,updated_by)
                    VALUES ('%s','%s','%s','%s','%s','Active coordinated care plan','active','%s','%s')
                    """.formatted(
                            CARE_PLAN_ID, ORGANIZATION_ID, PATIENT_ID, ENCOUNTER_ID,
                            PRACTITIONER_ID, ACTOR_ID, ACTOR_ID),
                    """
                    INSERT INTO care_plan_versions(
                        id,organization_id,care_plan_id,version_number,clinical_summary,
                        patient_summary,content_digest,status,frozen_at,created_by,updated_by)
                    VALUES ('%s','%s','%s',1,'Approved clinical monitoring plan.',
                            'Your agreed plan includes measured follow-up.','%s','active',
                            clock_timestamp()-interval '1 hour','%s','%s');
                    UPDATE care_plans SET current_version_id='%s' WHERE id='%s'
                    """.formatted(
                            CARE_PLAN_VERSION_ID, ORGANIZATION_ID, CARE_PLAN_ID, DIGEST,
                            ACTOR_ID, ACTOR_ID, CARE_PLAN_VERSION_ID, CARE_PLAN_ID));
        }
    }

    @Test
    void completesFrozenMeasuredEscalatedInterpretedAndClosedWorkflow() throws Exception {
        var assurance = clock.instant();
        act("P10-01", "create-followup-plan", null,
                Map.of(
                        "carePlanId", CARE_PLAN_ID.toString(),
                        "carePlanVersionId", CARE_PLAN_VERSION_ID.toString(),
                        "patientId", PATIENT_ID.toString(),
                        "encounterId", ENCOUNTER_ID.toString(),
                        "responsiblePractitionerId", PRACTITIONER_ID.toString(),
                        "planTitle", "Functional recovery monitoring",
                        "monitoringPurpose", "Measure functional progress and create owned work for threshold breaches.",
                        "timezone", "Asia/Kolkata",
                        "startsOn", assurance.toString().substring(0, 10)),
                null, "Create monitoring for the approved care plan.",
                "m10:create:integration-0001", assurance);
        var planId = uuidValue("SELECT id FROM followup_plans");

        act("P10-03", "add-domain", planId,
                Map.of(
                        "domainKey", "function",
                        "domainDisplay", "Functional recovery",
                        "measureKey", "walking_minutes",
                        "measureDisplay", "Comfortable walking duration",
                        "unitCode", "min",
                        "direction", "increase",
                        "targetLower", "30",
                        "baselineRequired", "true"),
                etag("P10-03", planId, 0), "Define the agreed functional outcome measure.",
                "m10:domain:integration-0001", assurance);
        var definitionId = uuidValue("SELECT id FROM outcome_definitions");

        act("P10-02", "add-rule", planId,
                Map.of(
                        "outcomeDefinitionId", definitionId.toString(),
                        "ruleName", "Unexpected high symptom-limited duration",
                        "operator", "gt",
                        "thresholdLower", "10",
                        "severity", "critical",
                        "ownerPractitionerId", PRACTITIONER_ID.toString(),
                        "taskPriority", "critical",
                        "acknowledgeWithinMinutes", "60",
                        "instruction", "Review the value, patient status and monitoring context immediately."),
                etag("P10-02", planId, 1), "Define an owned critical escalation threshold.",
                "m10:rule:integration-0001", assurance);

        act("P10-06", "schedule-followup", planId,
                Map.of(
                        "eventType", "baseline",
                        "scheduledFor", assurance.minus(1, ChronoUnit.MINUTES).toString(),
                        "dueAt", assurance.plus(1, ChronoUnit.HOURS).toString(),
                        "ownerPractitionerId", PRACTITIONER_ID.toString()),
                etag("P10-06", planId, 2), "Schedule attributable baseline measurement.",
                "m10:baseline-event:integration-0001", assurance);
        var baselineEventId = uuidValue(
                "SELECT id FROM followup_events WHERE event_type='baseline'");
        act("P10-04", "record-measurement", baselineEventId,
                Map.of(
                        "outcomeDefinitionId", definitionId.toString(),
                        "numericValue", "5",
                        "unitCode", "min",
                        "observedAt", assurance.minus(30, ChronoUnit.SECONDS).toString(),
                        "sourceKey", "clinician_observed",
                        "methodKey", "timed_walk",
                        "recordedByPractitionerId", PRACTITIONER_ID.toString(),
                        "notes", "Baseline completed without a threshold breach."),
                etag("P10-04", baselineEventId, 0), "Record the required baseline measurement.",
                "m10:baseline-measure:integration-0001", assurance);

        act("P10-06", "schedule-followup", planId,
                Map.of(
                        "eventType", "scheduled",
                        "scheduledFor", assurance.plus(1, ChronoUnit.DAYS).toString(),
                        "dueAt", assurance.plus(25, ChronoUnit.HOURS).toString(),
                        "ownerPractitionerId", PRACTITIONER_ID.toString()),
                etag("P10-06", planId, 3), "Schedule the first future outcome review.",
                "m10:future-event:integration-0001", assurance);
        var futureEventId = uuidValue(
                "SELECT id FROM followup_events WHERE event_type='scheduled'");

        act("P10-08", "submit-followup-plan", planId, Map.of(),
                etag("P10-08", planId, 4), "Submit the complete exact monitoring plan for confirmation.",
                "m10:submit:integration-0001", assurance);
        act("P10-08", "confirm-followup-plan", planId, Map.of(),
                etag("P10-08", planId, 5), "Confirm the exact monitoring plan with recent MFA.",
                "m10:confirm:integration-0001", assurance);

        act("P10-04", "record-measurement", futureEventId,
                Map.of(
                        "outcomeDefinitionId", definitionId.toString(),
                        "numericValue", "15",
                        "unitCode", "min",
                        "observedAt", assurance.toString(),
                        "sourceKey", "clinician_observed",
                        "methodKey", "timed_walk",
                        "recordedByPractitionerId", PRACTITIONER_ID.toString(),
                        "notes", "Value requires review under the configured critical rule."),
                etag("P10-04", futureEventId, 0), "Record and evaluate the follow-up measurement.",
                "m10:breach-measure:integration-0001", assurance);
        var measurementId = uuidValue(
                "SELECT id FROM outcome_measurements WHERE followup_event_id='%s'".formatted(futureEventId));
        var escalationId = uuidValue("SELECT id FROM escalation_events");

        assertThat(longValue("SELECT count(*) FROM clinical_tasks WHERE followup_plan_id='%s'".formatted(planId)))
                .isEqualTo(1);
        assertThat(stringValue("SELECT status FROM escalation_events WHERE id='%s'".formatted(escalationId)))
                .isEqualTo("open");

        act("P10-05", "acknowledge-escalation", escalationId,
                Map.of("practitionerId", PRACTITIONER_ID.toString()),
                etag("P10-05", escalationId, 0), "Acknowledge ownership of the threshold review.",
                "m10:ack:integration-0001", assurance);
        act("P10-05", "resolve-escalation", escalationId,
                Map.of("practitionerId", PRACTITIONER_ID.toString()),
                etag("P10-05", escalationId, 1), "Resolve after reviewing the patient and measurement context.",
                "m10:resolve:integration-0001", assurance);

        act("P10-07", "record-interpretation", measurementId,
                Map.of(
                        "trend", "improving",
                        "interpretation", "Walking duration improved relative to the recorded baseline.",
                        "recommendation", "Continue the agreed plan with the next scheduled clinical review.",
                        "interpretedByPractitionerId", PRACTITIONER_ID.toString()),
                etag("P10-07", measurementId, 0), "Record clinical meaning after resolving the alert.",
                "m10:interpret:integration-0001", assurance);

        act("P10-09", "close-followup-plan", planId,
                Map.of("outcome", "completed"), etag("P10-09", planId, 6),
                "Complete monitoring after all escalations are resolved.",
                "m10:close:integration-0001", assurance);

        assertThat(stringValue("SELECT status FROM followup_plans WHERE id='%s'".formatted(planId)))
                .isEqualTo("completed");
        assertThat(stringValue("SELECT status FROM escalation_events WHERE id='%s'".formatted(escalationId)))
                .isEqualTo("resolved");
        assertThat(stringValue("SELECT status FROM clinical_tasks WHERE followup_plan_id='%s'".formatted(planId)))
                .isEqualTo("completed");
        assertThat(longValue("SELECT count(*) FROM interpretations WHERE followup_plan_id='%s'".formatted(planId)))
                .isEqualTo(1);
        assertThat(longValue("SELECT count(*) FROM audit_events WHERE event_name LIKE 'followup.%%' OR event_name='followup_plan.created'"))
                .isGreaterThanOrEqualTo(13);
        assertThat(stringValue(
                        "SELECT payload::text FROM outbox_events WHERE event_name='m10.outcome-threshold-breached.v1'"))
                .doesNotContain("Outcome", "Walking", "patient");

        var timeline = followups.screen(new FollowupService.ReadCommand(
                ORGANIZATION_ID, ACTOR_ID, "m10-timeline-correlation", "P10-09",
                PATIENT_ID, ENCOUNTER_ID, planId, null, null, 25, null,
                assurance, assurance));
        assertThat(timeline.rows()).singleElement().satisfies(row -> {
            assertThat(row.status()).isEqualTo("completed");
            assertThat(row.values().get("measurements")).isEqualTo("2");
            assertThat(row.values().get("openEscalations")).isEqualTo("0");
        });

        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), APP_USER, APP_PASSWORD)) {
            assertThatThrownBy(() -> connection.createStatement().executeUpdate(
                            "UPDATE outcome_measurements SET numeric_value=99"))
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
        followups.act(new FollowupService.ActionCommand(
                ORGANIZATION_ID, ACTOR_ID, "m10-" + action + "-correlation", screen, action,
                target, reason, fields, etag, idempotency, assurance, assurance));
    }

    private static String etag(String screen, UUID targetId, long revision) {
        return "\"m10:" + screen + ":" + targetId + ":" + revision + "\"";
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
