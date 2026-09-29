package com.rootopathy.careos.ai.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rootopathy.careos.ai.application.AiProcessingPort;
import com.rootopathy.careos.ai.application.AiService;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(classes = {com.rootopathy.careos.CareOsApplication.class, AiLifecycleIntegrationTest.TestProvider.class})
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AiLifecycleIntegrationTest {
    private static final String MIGRATOR_USER = "careos_migrator";
    private static final String MIGRATOR_PASSWORD = "careos-migrator-test-only";
    private static final String APP_USER = "careos_app";
    private static final String APP_PASSWORD = "careos-app-test-only";
    private static final UUID ORGANIZATION_ID =
            UUID.fromString("01900000-0000-7000-8000-000000000001");
    private static final UUID FACILITY_ID =
            UUID.fromString("01900000-0000-7000-8000-000000000101");
    private static final UUID ACTOR_ID =
            UUID.fromString("019f0000-0000-7000-8000-000000000101");
    private static final UUID CHECKER_ID =
            UUID.fromString("019f0000-0000-7000-8000-000000000102");
    private static final UUID MEMBERSHIP_ID =
            UUID.fromString("019f0000-0000-7000-8000-000000000103");
    private static final UUID SERVICE_ID =
            UUID.fromString("019f0000-0000-7000-8000-000000000201");
    private static final UUID LOCATION_ID =
            UUID.fromString("019f0000-0000-7000-8000-000000000202");
    private static final UUID WORKFORCE_MEMBER_ID =
            UUID.fromString("019f0000-0000-7000-8000-000000000301");
    private static final UUID PRACTITIONER_ID =
            UUID.fromString("019f0000-0000-7000-8000-000000000302");
    private static final UUID PATIENT_ID =
            UUID.fromString("019f0000-0000-7000-8000-000000000401");
    private static final UUID EPISODE_ID =
            UUID.fromString("019f0000-0000-7000-8000-000000000501");
    private static final UUID ENCOUNTER_ID =
            UUID.fromString("019f0000-0000-7000-8000-000000000502");
    private static final String DIGEST = "e".repeat(64);

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
    private AiService ai;

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
                    VALUES ('%s','m8.practitioner@example.test','M8 Practitioner','active'),
                           ('%s','m8.checker@example.test','M8 Safety Checker','active')
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
                    VALUES ('%s','%s','M8_TEST','M8 governed consultation','active','%s','%s')
                    """.formatted(SERVICE_ID, ORGANIZATION_ID, ACTOR_ID, ACTOR_ID),
                    """
                    INSERT INTO service_locations
                        (id,organization_id,facility_id,location_code,location_type,name,
                         virtual_service_type,effective_from,status,created_by,updated_by)
                    VALUES ('%s','%s','%s','M8_ROOM','virtual','M8 test room','video',
                            clock_timestamp()-interval '30 days','active','%s','%s')
                    """.formatted(LOCATION_ID, ORGANIZATION_ID, FACILITY_ID, ACTOR_ID, ACTOR_ID),
                    """
                    INSERT INTO person_profiles
                        (id,legal_given_name,legal_family_name,display_name,created_by,updated_by)
                    VALUES ('019f0000-0000-7000-8000-000000000303','M8','Practitioner',
                            'M8 Practitioner','%s','%s')
                    """.formatted(ACTOR_ID, ACTOR_ID),
                    """
                    INSERT INTO organization_person_links
                        (id,organization_id,person_id,display_label,relationship_status,
                         effective_from,status,created_by,updated_by)
                    VALUES ('019f0000-0000-7000-8000-000000000304','%s',
                            '019f0000-0000-7000-8000-000000000303','M8 Practitioner','active',
                            clock_timestamp()-interval '30 days','active','%s','%s')
                    """.formatted(ORGANIZATION_ID, ACTOR_ID, ACTOR_ID),
                    """
                    INSERT INTO workforce_members
                        (id,organization_id,organization_person_link_id,pathway,member_number,
                         lifecycle_state,activated_at,status,created_by,updated_by)
                    VALUES ('%s','%s','019f0000-0000-7000-8000-000000000304','clinical',
                            'M8CLINICIAN001','active',clock_timestamp()-interval '30 days',
                            'active','%s','%s')
                    """.formatted(WORKFORCE_MEMBER_ID, ORGANIZATION_ID, ACTOR_ID, ACTOR_ID),
                    """
                    INSERT INTO workforce_registry_definitions
                        (id,organization_id,registry_key,category,display_name,value_schema,
                         review_cadence_days,lifecycle_state,status,created_by,updated_by)
                    VALUES ('019f0000-0000-7000-8000-000000000305','%s','m8_test_profession',
                            'profession','M8 professions','{}',365,'active','active','%s','%s')
                    """.formatted(ORGANIZATION_ID, ACTOR_ID, ACTOR_ID),
                    """
                    INSERT INTO workforce_registry_entries
                        (id,organization_id,registry_definition_id,entry_key,code,display_label,
                         lifecycle_state,status,created_by,updated_by)
                    VALUES ('019f0000-0000-7000-8000-000000000306','%s',
                            '019f0000-0000-7000-8000-000000000305','test_practitioner',
                            'TEST_PRACTITIONER','Test practitioner','active','active','%s','%s')
                    """.formatted(ORGANIZATION_ID, ACTOR_ID, ACTOR_ID),
                    """
                    INSERT INTO workforce_registry_versions
                        (id,organization_id,registry_entry_id,version_number,version_fields,
                         version_digest,effective_from,maker_id,checker_id,decision_code,
                         activated_at,lifecycle_state,status,created_by,updated_by)
                    VALUES ('019f0000-0000-7000-8000-000000000307','%s',
                            '019f0000-0000-7000-8000-000000000306',1,'{}','%s',
                            clock_timestamp()-interval '30 days','%s','%s','approved_for_test',
                            clock_timestamp()-interval '30 days','active','active','%s','%s')
                    """.formatted(ORGANIZATION_ID, DIGEST, ACTOR_ID, CHECKER_ID, ACTOR_ID, ACTOR_ID),
                    """
                    INSERT INTO practitioner_profiles
                        (id,organization_id,workforce_member_id,profession_entry_id,
                         profession_version_id,regulated,clinical_title,effective_from,
                         lifecycle_state,status,created_by,updated_by)
                    VALUES ('%s','%s','%s','019f0000-0000-7000-8000-000000000306',
                            '019f0000-0000-7000-8000-000000000307',true,'Test clinician',
                            clock_timestamp()-interval '30 days','active','active','%s','%s')
                    """.formatted(PRACTITIONER_ID, ORGANIZATION_ID, WORKFORCE_MEMBER_ID, ACTOR_ID, ACTOR_ID),
                    """
                    INSERT INTO access_assignment_scopes
                        (id,organization_id,access_assignment_id,workforce_member_id,
                         facility_id,location_id,grant_request_id,effective_from,status,
                         created_by,updated_by)
                    VALUES ('019f0000-0000-7000-8000-000000000308','%s','%s','%s','%s','%s',
                            '019f0000-0000-7000-8000-000000000309',
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
                    VALUES ('%s','%s','M8PATIENT001','active','Governed','AI Patient','provided',
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
                    INSERT INTO ai_model_releases
                        (id,organization_id,provider_key,model_key,model_version,region_key,
                         training_use_prohibited,retention_days,status,effective_from,created_by,updated_by)
                    VALUES ('019f0000-0000-7000-8000-000000000601','%s','test_provider',
                            'care_summary','1.0.0','in_central',true,30,'active',
                            clock_timestamp()-interval '1 day','%s','%s')
                    """.formatted(ORGANIZATION_ID, ACTOR_ID, ACTOR_ID),
                    """
                    INSERT INTO ai_prompt_releases
                        (id,organization_id,prompt_key,prompt_version,purpose_key,template_digest,
                         output_schema_key,output_schema_version,status,effective_from,created_by,updated_by)
                    VALUES ('019f0000-0000-7000-8000-000000000602','%s','clinical_summary',
                            '1.0.0','clinical_documentation','%s','care_summary',1,'active',
                            clock_timestamp()-interval '1 day','%s','%s')
                    """.formatted(ORGANIZATION_ID, DIGEST, ACTOR_ID, ACTOR_ID),
                    """
                    INSERT INTO ai_evaluation_signoffs
                        (id,organization_id,model_release_id,prompt_release_id,dataset_key,
                         dataset_version,dataset_digest,evaluation_digest,outcome,safety_signoff_by,
                         safety_signoff_at,expires_at,reason,created_by,updated_by)
                    VALUES ('019f0000-0000-7000-8000-000000000603','%s',
                            '019f0000-0000-7000-8000-000000000601',
                            '019f0000-0000-7000-8000-000000000602','m8_test_set','1.0.0','%s','%s',
                            'approved','%s',clock_timestamp()-interval '1 day',
                            clock_timestamp()+interval '30 days','Approved synthetic safety evaluation.',
                            '%s','%s')
                    """.formatted(ORGANIZATION_ID, DIGEST, DIGEST, CHECKER_ID, ACTOR_ID, ACTOR_ID));
        }
    }

    @Test
    void completesConsentProvenanceSafetyAndClinicianDecisionWorkflow() throws Exception {
        var assurance = clock.instant();
        act(
                "P8-01",
                "launch-session",
                null,
                Map.of(
                        "patientId", PATIENT_ID.toString(),
                        "encounterId", ENCOUNTER_ID.toString(),
                        "sessionType", "summary"),
                null,
                "Launch a governed synthetic AI session.",
                "m8:launch:integration-0001",
                assurance);
        var sessionId = uuidValue("SELECT id FROM ai_sessions");
        assertThat(stringValue("SELECT status FROM ai_sessions WHERE id='%s'".formatted(sessionId)))
                .isEqualTo("draft");

        act(
                "P8-02",
                "record-purpose-consent",
                sessionId,
                Map.of(
                        "purposeKey", "clinical_documentation",
                        "legalBasisKey", "direct_care",
                        "consentStatus", "granted",
                        "consentReference", "CONSENT-M8-001",
                        "minimumNecessaryConfirmed", "true"),
                etag("P8-02", sessionId, 0),
                "Record exact purpose and consent evidence.",
                "m8:consent:integration-0001",
                assurance);
        act(
                "P8-03",
                "select-input",
                sessionId,
                Map.of(
                        "sourceType", "encounter",
                        "sourceId", ENCOUNTER_ID.toString(),
                        "sourceRevision", "0",
                        "sourceDigest", DIGEST,
                        "dataCategories", "encounter_notes,observations",
                        "selectionReason", "Use the exact active encounter as the minimum necessary source."),
                etag("P8-03", sessionId, 1),
                "Approve the exact minimum necessary source manifest.",
                "m8:input:integration-0001",
                assurance);
        act(
                "P8-04",
                "request-processing",
                sessionId,
                Map.of("parameters", "concise clinical summary"),
                etag("P8-04", sessionId, 2),
                "Request processing under the approved release contract.",
                "m8:process:integration-0001",
                assurance);

        assertThat(stringValue("SELECT status FROM ai_sessions WHERE id='%s'".formatted(sessionId)))
                .isEqualTo("draft_ready");
        assertThat(longValue("SELECT count(*) FROM ai_output_citations")).isEqualTo(1);
        assertThat(longValue("SELECT count(*) FROM ai_safety_escalations")).isEqualTo(1);
        var flagId = uuidValue("SELECT id FROM ai_safety_flags");

        assertThatThrownBy(() -> act(
                        "P8-09",
                        "decide-output",
                        sessionId,
                        Map.of(
                                "decision", "accepted",
                                "reviewerPractitionerId", PRACTITIONER_ID.toString()),
                        etag("P8-09", sessionId, 4),
                        "Attempt acceptance before resolving the safety flag.",
                        "m8:unsafe-decision:integration-0001",
                        assurance))
                .isInstanceOf(DataIntegrityViolationException.class);

        act(
                "P8-07",
                "acknowledge-safety-flag",
                sessionId,
                Map.of("safetyFlagId", flagId.toString()),
                etag("P8-07", sessionId, 4),
                "Acknowledge the critical provider safety flag.",
                "m8:flag-ack:integration-0001",
                assurance);
        act(
                "P8-07",
                "resolve-safety-flag",
                sessionId,
                Map.of("safetyFlagId", flagId.toString()),
                etag("P8-07", sessionId, 4),
                "Resolve the critical flag after accountable review.",
                "m8:flag-resolve:integration-0001",
                assurance);
        act(
                "P8-05",
                "edit-output",
                sessionId,
                Map.of(
                        "content", "Clinician-edited synthetic summary with the unsupported wording removed.",
                        "editSummary", "Removed unsupported provider wording after source review."),
                etag("P8-05", sessionId, 4),
                "Append the clinician-reviewed draft correction.",
                "m8:edit:integration-0001",
                assurance);
        act(
                "P8-09",
                "decide-output",
                sessionId,
                Map.of(
                        "decision", "accepted",
                        "reviewerPractitionerId", PRACTITIONER_ID.toString()),
                etag("P8-09", sessionId, 5),
                "Accept the exact latest clinician-edited draft after safety resolution.",
                "m8:decision:integration-0001",
                assurance);

        assertThat(stringValue("SELECT status FROM ai_sessions WHERE id='%s'".formatted(sessionId)))
                .isEqualTo("accepted");
        assertThat(stringValue("SELECT decision FROM ai_reviews WHERE ai_session_id='%s'".formatted(sessionId)))
                .isEqualTo("accepted");
        assertThat(longValue("SELECT count(*) FROM ai_output_versions")).isEqualTo(2);
        assertThat(longValue("SELECT count(*) FROM audit_events WHERE event_name LIKE 'ai.%%'"))
                .isGreaterThanOrEqualTo(8);
        assertThat(longValue("SELECT count(*) FROM outbox_events WHERE event_name LIKE 'm8.%%'"))
                .isGreaterThanOrEqualTo(5);
        assertThat(stringValue(
                        "SELECT payload::text FROM outbox_events WHERE event_name='m8.ai-output-decided.v1'"))
                .doesNotContain("Clinician-edited", "provider-request", "CONSENT-M8-001");

        var history = ai.screen(new AiService.ReadCommand(
                ORGANIZATION_ID,
                ACTOR_ID,
                "m8-history-correlation",
                "P8-10",
                PATIENT_ID,
                ENCOUNTER_ID,
                sessionId,
                null,
                null,
                25,
                null,
                assurance,
                assurance));
        assertThat(history.rows()).singleElement().satisfies(row -> {
            assertThat(row.status()).isEqualTo("accepted");
            assertThat(row.values().get("draftLabel"))
                    .isEqualTo("AI-generated draft — clinician review required");
            assertThat(row.values().get("model")).isEqualTo("care_summary @ 1.0.0");
            assertThat(row.values().get("citations")).isEqualTo("1");
        });

        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), APP_USER, APP_PASSWORD)) {
            assertThatThrownBy(() -> connection.createStatement().executeUpdate(
                            "UPDATE ai_output_versions SET content='tampered'"))
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
            Instant assurance) {
        ai.act(new AiService.ActionCommand(
                ORGANIZATION_ID,
                ACTOR_ID,
                "m8-" + action + "-correlation",
                screen,
                action,
                target,
                reason,
                fields,
                etag,
                idempotency,
                assurance,
                assurance));
    }

    private static String etag(String screen, UUID sessionId, long revision) {
        return "\"m8:" + screen + ":" + sessionId + ":" + revision + "\"";
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

    @TestConfiguration(proxyBeanMethods = false)
    static class TestProvider {
        @Bean
        @Primary
        AiProcessingPort syntheticAiProvider(Clock clock) {
            return request -> new AiProcessingPort.Result(
                    "provider-request-m8-001",
                    "Synthetic AI-generated draft requiring clinician review.",
                    "moderate",
                    List.of(new AiProcessingPort.Citation(
                            request.inputs().getFirst().manifestItemId(),
                            "encounter:summary",
                            "Synthetic claim linked to the exact approved encounter input.")),
                    List.of(new AiProcessingPort.SafetyFlag(
                            "critical_review_required",
                            "critical",
                            "Synthetic critical flag requires accountable clinician review.",
                            PRACTITIONER_ID,
                            clock.instant().plus(1, ChronoUnit.HOURS))),
                    80,
                    24,
                    7,
                    "INR",
                    25,
                    clock.instant().plus(1, ChronoUnit.DAYS));
        }
    }
}
