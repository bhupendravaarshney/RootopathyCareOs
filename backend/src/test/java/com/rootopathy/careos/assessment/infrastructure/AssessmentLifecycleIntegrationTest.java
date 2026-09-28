package com.rootopathy.careos.assessment.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rootopathy.careos.assessment.application.AssessmentService;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
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
class AssessmentLifecycleIntegrationTest {
    private static final String MIGRATOR_USER = "careos_migrator";
    private static final String MIGRATOR_PASSWORD = "careos-migrator-test-only";
    private static final String APP_USER = "careos_app";
    private static final String APP_PASSWORD = "careos-app-test-only";
    private static final UUID ORGANIZATION_ID =
            UUID.fromString("01900000-0000-7000-8000-000000000001");
    private static final UUID FACILITY_ID =
            UUID.fromString("01900000-0000-7000-8000-000000000101");
    private static final UUID ACTOR_ID =
            UUID.fromString("019d0000-0000-7000-8000-000000000101");
    private static final UUID CHECKER_ID =
            UUID.fromString("019d0000-0000-7000-8000-000000000102");
    private static final UUID MEMBERSHIP_ID =
            UUID.fromString("019d0000-0000-7000-8000-000000000103");
    private static final UUID SERVICE_ID =
            UUID.fromString("019d0000-0000-7000-8000-000000000201");
    private static final UUID LOCATION_ID =
            UUID.fromString("019d0000-0000-7000-8000-000000000202");
    private static final UUID PRACTITIONER_ID =
            UUID.fromString("019d0000-0000-7000-8000-000000000301");
    private static final UUID WORKFORCE_MEMBER_ID =
            UUID.fromString("019d0000-0000-7000-8000-000000000304");
    private static final UUID PATIENT_ID =
            UUID.fromString("019d0000-0000-7000-8000-000000000401");
    private static final UUID EPISODE_ID =
            UUID.fromString("019d0000-0000-7000-8000-000000000501");
    private static final UUID ENCOUNTER_ID =
            UUID.fromString("019d0000-0000-7000-8000-000000000502");
    private static final String DIGEST = "c".repeat(64);

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
    private AssessmentService assessments;

    @Autowired
    private Clock clock;

    @BeforeEach
    void seedAssessmentDependencies() throws SQLException {
        try (var connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), MIGRATOR_USER, MIGRATOR_PASSWORD);
                var statement = connection.createStatement()) {
            execute(
                    statement,
                    """
                    INSERT INTO users (id,email,display_name,status)
                    VALUES ('%s','m6.practitioner@example.test','M6 Practitioner','active'),
                           ('%s','m6.checker@example.test','M6 Checker','active')
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
                    VALUES ('%s','%s','M6_TEST','M6 test consultation','active','%s','%s')
                    """.formatted(SERVICE_ID, ORGANIZATION_ID, ACTOR_ID, ACTOR_ID),
                    """
                    INSERT INTO service_locations
                        (id,organization_id,facility_id,location_code,location_type,name,
                         virtual_service_type,effective_from,status,created_by,updated_by)
                    VALUES ('%s','%s','%s','M6_ROOM','virtual','M6 test room','video',
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
                    VALUES ('019d0000-0000-7000-8000-000000000312','%s','%s','%s','%s','%s',
                            '019d0000-0000-7000-8000-000000000313',
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
                         official_family_name,name_state,provenance_source,verification_state,
                         created_by,updated_by)
                    VALUES ('%s','%s','M6PATIENT001','active','Assessment','Patient','provided',
                            'test_fixture','evidence_checked','%s','%s')
                    """.formatted(PATIENT_ID, ORGANIZATION_ID, ACTOR_ID, ACTOR_ID),
                    """
                    INSERT INTO episodes_of_care
                      (id,organization_id,patient_id,service_id,facility_id,location_id,
                       managing_practitioner_id,status,started_at,created_by,updated_by)
                    VALUES ('%s','%s','%s','%s','%s','%s','%s','active',
                            clock_timestamp()-interval '1 hour','%s','%s')
                    """.formatted(
                            EPISODE_ID,
                            ORGANIZATION_ID,
                            PATIENT_ID,
                            SERVICE_ID,
                            FACILITY_ID,
                            LOCATION_ID,
                            PRACTITIONER_ID,
                            ACTOR_ID,
                            ACTOR_ID),
                    """
                    INSERT INTO encounters
                      (id,organization_id,episode_of_care_id,patient_id,service_id,facility_id,
                       location_id,responsible_practitioner_id,source_kind,encounter_type_key,
                       status,planned_start_at,in_progress_at,created_by,updated_by)
                    VALUES ('%s','%s','%s','%s','%s','%s','%s','%s','unscheduled','consultation',
                            'in_progress',clock_timestamp()-interval '30 minutes',
                            clock_timestamp()-interval '20 minutes','%s','%s')
                    """.formatted(
                            ENCOUNTER_ID,
                            ORGANIZATION_ID,
                            EPISODE_ID,
                            PATIENT_ID,
                            SERVICE_ID,
                            FACILITY_ID,
                            LOCATION_ID,
                            PRACTITIONER_ID,
                            ACTOR_ID,
                            ACTOR_ID),
                    """
                    INSERT INTO encounter_participants
                      (id,organization_id,encounter_id,participant_type,patient_id,role_key,
                       display_name_snapshot,role_snapshot,status,added_at,created_by,updated_by)
                    VALUES ('019d0000-0000-7000-8000-000000000503','%s','%s','patient','%s',
                            'subject_of_care','Assessment Patient','Patient','active',clock_timestamp(),'%s','%s')
                    """.formatted(ORGANIZATION_ID, ENCOUNTER_ID, PATIENT_ID, ACTOR_ID, ACTOR_ID),
                    """
                    INSERT INTO encounter_participants
                      (id,organization_id,encounter_id,participant_type,workforce_member_id,
                       practitioner_profile_id,role_key,display_name_snapshot,role_snapshot,
                       assignment_id,eligibility_evidence_id,eligibility_digest,registration_snapshot,
                       status,added_at,created_by,updated_by)
                    VALUES ('019d0000-0000-7000-8000-000000000504','%s','%s','practitioner','%s','%s',
                            'responsible_clinician','Test Practitioner','Responsible clinician',
                            '019d0000-0000-7000-8000-000000000311',
                            '019d0000-0000-7000-8000-000000000310','%s','{}','active',
                            clock_timestamp(),'%s','%s')
                    """.formatted(
                            ORGANIZATION_ID,
                            ENCOUNTER_ID,
                            WORKFORCE_MEMBER_ID,
                            PRACTITIONER_ID,
                            DIGEST,
                            ACTOR_ID,
                            ACTOR_ID));
        }
    }

    @Test
    void completesVersionedAssessmentWorkflowAndRejectsUnsafeEvidenceChanges() throws Exception {
        var assurance = clock.instant();
        assess(
                "COS-01",
                "start-assessment",
                ENCOUNTER_ID,
                Map.of("responsiblePractitionerId", PRACTITIONER_ID.toString()),
                "Start the governed clinical assessment",
                "m6-start-assessment-0001",
                assurance);
        var sessionId = uuidValue(
                "SELECT id FROM assessment_sessions WHERE encounter_id='%s'".formatted(ENCOUNTER_ID));
        assertThat(longValue(
                        "SELECT count(*) FROM assessment_sections WHERE assessment_session_id='%s'"
                                .formatted(sessionId)))
                .isEqualTo(27);

        var responseFields = Map.of(
                "authorPractitionerId", PRACTITIONER_ID.toString(),
                "responseKey", "patient_story",
                "content", "Patient describes symptoms beginning after exertion.",
                "sourceKey", "patient_report",
                "methodKey", "clinical_interview",
                "interpretationStatus", "provisional",
                "uncertainty", "Timing is approximate.");
        assess(
                "COS-02",
                "save-section-response",
                sessionId,
                responseFields,
                null,
                "m6-save-response-0001",
                assurance);
        var revisedResponseFields = new HashMap<>(responseFields);
        revisedResponseFields.put(
                "content", "Patient confirms symptoms began immediately after exertion.");
        revisedResponseFields.put("interpretationStatus", "reviewed");
        assess(
                "COS-02",
                "save-section-response",
                sessionId,
                revisedResponseFields,
                null,
                "m6-save-response-0002",
                assurance);
        assertThat(longValue("SELECT count(*) FROM response_versions")).isEqualTo(2);

        assess(
                "COS-08",
                "record-measurement",
                sessionId,
                Map.ofEntries(
                        Map.entry("recordedByPractitionerId", PRACTITIONER_ID.toString()),
                        Map.entry("ownerPractitionerId", PRACTITIONER_ID.toString()),
                        Map.entry("measurementKey", "heart_rate"),
                        Map.entry("purpose", "Monitor cardiovascular response"),
                        Map.entry("baseline", "true"),
                        Map.entry("value", "88"),
                        Map.entry("sourceKey", "direct_observation"),
                        Map.entry("methodKey", "validated_monitor"),
                        Map.entry("unitScale", "beats_per_minute"),
                        Map.entry("cadence", "At each clinical review"),
                        Map.entry("actionThreshold", "Escalate if persistently above local threshold"),
                        Map.entry("interpretationStatus", "uninterpreted")),
                null,
                "m6-record-measurement-0001",
                assurance);

        assess(
                "COS-10",
                "record-red-flag",
                sessionId,
                Map.of(
                        "raisedByPractitionerId", PRACTITIONER_ID.toString(),
                        "ownerPractitionerId", PRACTITIONER_ID.toString(),
                        "severityKey", "severe",
                        "summary", "Exertional symptom pattern requires immediate clinician review.",
                        "sourceKey", "patient_report",
                        "methodKey", "clinical_interview"),
                "Raise visible red flag for immediate review",
                "m6-record-red-flag-0001",
                assurance);
        var flagId = uuidValue("SELECT id FROM red_flags WHERE assessment_session_id='%s'"
                .formatted(sessionId));

        assertThatThrownBy(() -> assess(
                        "COS-25",
                        "submit-assessment-review",
                        sessionId,
                        reviewFields(),
                        "Review completeness source and uncertainty",
                        "m6-review-blocked-0001",
                        assurance))
                .isInstanceOf(RuntimeException.class);

        assess(
                "COS-10",
                "acknowledge-red-flag",
                sessionId,
                Map.of(
                        "redFlagId", flagId.toString(),
                        "practitionerId", PRACTITIONER_ID.toString()),
                "Acknowledge and assess the visible red flag",
                "m6-ack-red-flag-0001",
                assurance);
        assess(
                "COS-10",
                "resolve-red-flag",
                sessionId,
                Map.of(
                        "redFlagId", flagId.toString(),
                        "practitionerId", PRACTITIONER_ID.toString()),
                "Resolve after documented safety assessment",
                "m6-resolve-red-flag-0001",
                assurance);

        assess(
                "COS-25",
                "submit-assessment-review",
                sessionId,
                reviewFields(),
                "Review completeness source and uncertainty",
                "m6-submit-review-0001",
                assurance);
        assess(
                "COS-25",
                "sign-assessment",
                sessionId,
                Map.of("signerPractitionerId", PRACTITIONER_ID.toString()),
                "Sign the exact reviewed assessment revision",
                "m6-sign-assessment-0001",
                assurance);
        assess(
                "COS-25",
                "amend-assessment",
                sessionId,
                Map.of(
                        "authorPractitionerId", PRACTITIONER_ID.toString(),
                        "amendmentText", "Clarification: follow-up ownership remains with the responsible clinician.",
                        "sourceKey", "clinician_clarification",
                        "methodKey", "signed_amendment",
                        "uncertainty", "No change to the signed clinical observations."),
                "Append a traceable clarification to the signature",
                "m6-amend-assessment-0001",
                assurance);
        assess(
                "COS-27",
                "complete-assessment",
                sessionId,
                Map.of(),
                "Complete after signature and safety resolution",
                "m6-complete-assessment-0001",
                assurance);

        assertThat(stringValue(
                        "SELECT status FROM assessment_sessions WHERE id='%s'".formatted(sessionId)))
                .isEqualTo("completed");
        assertThat(longValue("SELECT lock_version FROM assessment_sessions WHERE id='%s'"
                        .formatted(sessionId)))
                .isEqualTo(10);
        assertThat(longValue("SELECT count(*) FROM assessment_reviews")).isOne();
        assertThat(longValue("SELECT count(*) FROM assessment_signatures")).isOne();
        assertThat(longValue("SELECT count(*) FROM assessment_amendments")).isOne();
        assertThat(longValue("SELECT count(*) FROM audit_events WHERE event_name LIKE 'assessment.%%'"))
                .isEqualTo(11);
        assertThat(longValue("SELECT count(*) FROM outbox_events WHERE event_name LIKE 'm6.%%'"))
                .isEqualTo(7);
        assertThat(longValue("""
                        SELECT count(*) FROM audit_events
                        WHERE event_name LIKE 'assessment.%%'
                          AND payload::text LIKE '%%Patient confirms symptoms%%'
                        """))
                .isZero();
        assertThat(assessments.screen(new AssessmentService.ReadCommand(
                                ORGANIZATION_ID,
                                ACTOR_ID,
                                "m6-completed-projection",
                                "COS-27",
                                PATIENT_ID,
                                ENCOUNTER_ID,
                                sessionId,
                                null,
                                null,
                                50,
                                null,
                                assurance,
                                assurance))
                        .rows())
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.status()).isEqualTo("completed");
                    assertThat(row.values()).containsEntry("sourcePackageStatus", "unavailable");
                });

        assertDirectVersionRewriteRejected();
    }

    private Map<String, String> reviewFields() {
        return Map.of(
                "reviewerPractitionerId", PRACTITIONER_ID.toString(),
                "completenessConfirmed", "true",
                "sourceReviewed", "true",
                "uncertaintyReviewed", "true",
                "reviewSummary", "Reviewed available sections, sources and recorded uncertainty.");
    }

    private void assess(
            String screenId,
            String actionKey,
            UUID targetId,
            Map<String, String> fields,
            String reason,
            String idempotencyKey,
            Instant assurance)
            throws SQLException {
        var start = actionKey.equals("start-assessment");
        var revision = start ? null : revision(targetId);
        var ifMatch = start ? null : "\"m6:" + screenId + ":" + targetId + ":" + revision + "\"";
        assessments.act(new AssessmentService.ActionCommand(
                ORGANIZATION_ID,
                ACTOR_ID,
                "m6-lifecycle-test",
                screenId,
                actionKey,
                targetId,
                PATIENT_ID,
                ENCOUNTER_ID,
                start ? null : targetId,
                reason,
                fields,
                ifMatch,
                idempotencyKey,
                assurance,
                assurance));
    }

    private long revision(UUID sessionId) throws SQLException {
        return longValue("SELECT lock_version FROM assessment_sessions WHERE id='%s'"
                .formatted(sessionId));
    }

    private void assertDirectVersionRewriteRejected() throws SQLException {
        var versionId = uuidValue("SELECT id FROM response_versions ORDER BY version_number LIMIT 1");
        try (var connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), APP_USER, APP_PASSWORD);
                var statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.execute("SELECT set_config('app.current_organization_id','%s',true)"
                    .formatted(ORGANIZATION_ID));
            statement.execute("SELECT set_config('app.current_actor_id','%s',true)"
                    .formatted(ACTOR_ID));
            statement.execute(
                    "SELECT set_config('app.current_operation_key','assessment.response.write',true)");
            assertThatThrownBy(() -> statement.executeUpdate("""
                            UPDATE response_versions
                               SET content_text='Rewritten content',lock_version=lock_version+1,
                                   updated_at=clock_timestamp(),updated_by='%s'
                             WHERE id='%s'
                            """.formatted(ACTOR_ID, versionId)))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("permission denied");
            connection.rollback();
        }
    }

    private static void seedPractitioner(java.sql.Statement statement) throws SQLException {
        var personId = "019d0000-0000-7000-8000-000000000302";
        var personLinkId = "019d0000-0000-7000-8000-000000000303";
        var registryDefinitionId = "019d0000-0000-7000-8000-000000000305";
        var professionEntryId = "019d0000-0000-7000-8000-000000000306";
        var professionVersionId = "019d0000-0000-7000-8000-000000000307";
        var scopeDefinitionId = "019d0000-0000-7000-8000-000000000308";
        var scopeId = "019d0000-0000-7000-8000-000000000309";
        var evidenceId = "019d0000-0000-7000-8000-000000000310";
        var assignmentId = "019d0000-0000-7000-8000-000000000311";
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
                VALUES ('%s','%s','%s','clinical','M6CLINICIAN001','active',
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
                VALUES ('%s','%s','m6_test_profession','profession','M6 professions','{}',365,
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
                VALUES ('%s','%s','M6_TEST_SCOPE','M6 test scope','%s','%s','%s','IN',
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
                        clock_timestamp()+interval '30 days','m6-test-evaluator','m6-test-catalogue',
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
        try (var connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), MIGRATOR_USER, MIGRATOR_PASSWORD);
                var statement = connection.createStatement();
                var resultSet = statement.executeQuery(sql)) {
            assertThat(resultSet.next()).isTrue();
            return resultSet.getObject(1, UUID.class);
        }
    }
}
