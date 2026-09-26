package com.rootopathy.careos.patientregistry.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.rootopathy.careos.identity.api.AuthenticationSessionState;
import com.rootopathy.careos.patientregistry.application.PatientRegistryException;
import com.rootopathy.careos.patientregistry.application.PatientRegistryService;
import com.rootopathy.careos.shared.domain.AuthenticatedActor;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class PatientRegistrationIntegrationTest {
    private static final String MIGRATOR_USER = "careos_migrator";
    private static final String MIGRATOR_PASSWORD = "careos-migrator-test-only";
    private static final String APP_USER = "careos_app";
    private static final String APP_PASSWORD = "careos-app-test-only";
    private static final UUID ORGANIZATION_ID =
            UUID.fromString("01900000-0000-7000-8000-000000000001");
    private static final UUID ACTOR_ID =
            UUID.fromString("019a0000-0000-7000-8000-000000000101");
    private static final UUID MEMBERSHIP_ID =
            UUID.fromString("019a0000-0000-7000-8000-000000000102");
    private static final UUID CHECKER_ID =
            UUID.fromString("019a0000-0000-7000-8000-000000000201");
    private static final UUID CHECKER_MEMBERSHIP_ID =
            UUID.fromString("019a0000-0000-7000-8000-000000000202");
    private static final UUID EXECUTOR_ID =
            UUID.fromString("019a0000-0000-7000-8000-000000000301");
    private static final UUID EXECUTOR_MEMBERSHIP_ID =
            UUID.fromString("019a0000-0000-7000-8000-000000000302");
    private static final UUID SEARCHER_ID =
            UUID.fromString("019a0000-0000-7000-8000-000000000401");
    private static final UUID SEARCHER_MEMBERSHIP_ID =
            UUID.fromString("019a0000-0000-7000-8000-000000000402");

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
    private PatientRegistryService patients;

    @Autowired
    private Clock clock;

    @Autowired
    private MockMvc mockMvc;

    @BeforeEach
    void seedOwner() throws SQLException {
        try (var connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), MIGRATOR_USER, MIGRATOR_PASSWORD);
                var statement = connection.createStatement()) {
            seedOwner(statement, ACTOR_ID, MEMBERSHIP_ID, "registration");
            seedOwner(statement, CHECKER_ID, CHECKER_MEMBERSHIP_ID, "checker");
            seedOwner(statement, EXECUTOR_ID, EXECUTOR_MEMBERSHIP_ID, "executor");
            seedDuplicateSearcher(statement);
        }
    }

    private static void seedOwner(
            java.sql.Statement statement, UUID actorId, UUID membershipId, String label)
            throws SQLException {
        statement.executeUpdate("""
                INSERT INTO users (id,email,display_name,status)
                VALUES ('%s','m3.%s@example.test','M3 %s Owner','active')
                ON CONFLICT (id) DO NOTHING
                """.formatted(actorId, label, label));
        statement.executeUpdate("""
                INSERT INTO organization_memberships
                    (id,organization_id,user_id,role_key,status,effective_from,updated_by)
                VALUES ('%s','%s','%s','organization_owner','active',clock_timestamp(),'%s')
                ON CONFLICT (organization_id,user_id,role_key) DO NOTHING
                """.formatted(membershipId, ORGANIZATION_ID, actorId, actorId));
    }

    private static void seedDuplicateSearcher(java.sql.Statement statement)
            throws SQLException {
        statement.executeUpdate("""
                INSERT INTO users (id,email,display_name,status)
                VALUES ('%s','m3.searcher@example.test','M3 Searcher','active')
                ON CONFLICT (id) DO NOTHING
                """.formatted(SEARCHER_ID));
        statement.executeUpdate("""
                INSERT INTO authorization_roles
                    (role_key,display_name,description,status,registry_version,interactive)
                VALUES ('m3_duplicate_search_test','M3 duplicate search test',
                        'Test-only exact duplicate-search role.','active','m3-candidate-1',true)
                ON CONFLICT (role_key) DO NOTHING
                """);
        statement.executeUpdate("""
                INSERT INTO authorization_role_permissions (role_key,permission_key)
                VALUES ('m3_duplicate_search_test','patient.duplicate.search')
                ON CONFLICT DO NOTHING
                """);
        statement.executeUpdate("""
                INSERT INTO organization_memberships
                    (id,organization_id,user_id,role_key,status,effective_from,updated_by)
                VALUES ('%s','%s','%s','m3_duplicate_search_test','active',
                        clock_timestamp(),'%s')
                ON CONFLICT (organization_id,user_id,role_key) DO NOTHING
                """.formatted(
                SEARCHER_MEMBERSHIP_ID,
                ORGANIZATION_ID,
                SEARCHER_ID,
                SEARCHER_ID));
    }

    @Test
    void completesSearchFirstRegistrationWithGovernanceEvidence() throws SQLException {
        var assurance = clock.instant();
        var auditBaseline = longValue(
                "SELECT count(*) FROM audit_events WHERE organization_id='%s' AND event_name LIKE 'patient.%%'"
                        .formatted(ORGANIZATION_ID));
        var outboxBaseline = longValue(
                "SELECT count(*) FROM outbox_events WHERE organization_id='%s' AND event_name='m3.patient.registered.v1'"
                        .formatted(ORGANIZATION_ID));
        var started = patients.act(action(
                "P3-03",
                "start-registration",
                null,
                null,
                null,
                Map.of(
                        "registrationSource", "staff_entry",
                        "purposeKey", "patient_registration",
                        "urgent", "false"),
                "Start synthetic patient registration",
                "m3-registration-start-0001",
                assurance));
        assertThat(started.response().statusCode()).isEqualTo(201);

        var registrationId = uuidValue(
                "SELECT id FROM patient_registration_runs WHERE creator_id='%s'"
                        .formatted(ACTOR_ID));
        assertThat(registrationId).isNotNull();

        var searched = patients.act(action(
                "P3-04",
                "search-duplicates",
                registrationId,
                null,
                registrationId,
                Map.of("officialName", "Synthetic Patient Alpha"),
                null,
                "m3-registration-search-0001",
                assurance));
        assertThat(searched.response().statusCode()).isEqualTo(200);

        var dispositioned = patients.act(action(
                "P3-04",
                "record-registration-disposition",
                registrationId,
                null,
                registrationId,
                Map.of(
                        "dispositionCode", "create_new",
                        "officialGivenName", "Synthetic",
                        "officialFamilyName", "Patient Alpha",
                        "nameToUse", "Synthetic Patient Alpha",
                        "provenanceCode", "staff_attested"),
                "Create a new synthetic patient after duplicate search",
                "m3-registration-disposition-0001",
                assurance));
        assertThat(dispositioned.response().statusCode()).isEqualTo(201);

        var patientId = uuidValue(
                "SELECT selected_patient_id FROM patient_registration_runs WHERE id='%s'"
                        .formatted(registrationId));
        assertThat(patientId).isNotNull();

        var corrected = patients.act(action(
                "P3-05",
                "correct-identity",
                patientId,
                patientId,
                registrationId,
                Map.of(
                        "officialGivenName", "Synthetic",
                        "officialFamilyName", "Patient Alpha",
                        "nameToUse", "Synthetic Patient Alpha",
                        "nameState", "provided",
                        "birthDate", "2000-01-01",
                        "birthDatePrecision", "day",
                        "birthDateCertainty", "exact",
                        "provenanceCode", "staff_attested"),
                "Correct the synthetic patient identity",
                "m3-registration-identity-0001",
                assurance));
        assertThat(corrected.response().statusCode()).isEqualTo(200);

        var validated = patients.act(action(
                "P3-12",
                "validate-registration",
                registrationId,
                patientId,
                registrationId,
                Map.of(),
                "Validate the exact synthetic registration revision",
                "m3-registration-validate-0001",
                assurance));
        assertThat(validated.response().statusCode()).isEqualTo(200);

        var submitted = patients.act(action(
                "P3-12",
                "submit-registration",
                registrationId,
                patientId,
                registrationId,
                Map.of(),
                "Complete the validated synthetic registration",
                "m3-registration-submit-0001",
                assurance));
        assertThat(submitted.response().statusCode()).isEqualTo(200);

        assertThat(stringValue(
                        "SELECT lifecycle_state FROM patient_profiles WHERE id='%s'"
                                .formatted(patientId)))
                .isEqualTo("active");
        assertThat(stringValue(
                        "SELECT status FROM patient_registration_runs WHERE id='%s'"
                                .formatted(registrationId)))
                .isEqualTo("completed");
        assertThat(longValue(
                        "SELECT count(*) FROM audit_events WHERE organization_id='%s' AND event_name LIKE 'patient.%%'"
                                .formatted(ORGANIZATION_ID)))
                .isEqualTo(auditBaseline + 6);
        assertThat(longValue(
                        "SELECT count(*) FROM outbox_events WHERE organization_id='%s' AND event_name='m3.patient.registered.v1'"
                                .formatted(ORGANIZATION_ID)))
                .isEqualTo(outboxBaseline + 1);
        assertThat(stringValue(
                        "SELECT payload->>'patientId' FROM outbox_events WHERE event_name='m3.patient.registered.v1'"))
                .isEqualTo(patientId.toString());
    }

    @Test
    void enforcesSearchFirstAndCreatesSafeDuplicateEvidence() throws SQLException {
        var assurance = clock.instant();
        var marker = UUID.randomUUID().toString().substring(0, 8);
        var name = "Synthetic Duplicate " + marker;
        var existingPatientId = registerPatient(name, ACTOR_ID, assurance, "existing-" + marker);
        var source = "duplicate-" + marker;
        var registrationId = startRegistration(ACTOR_ID, source, assurance, "duplicate-start-" + marker);

        assertThatThrownBy(() -> patients.screen(new PatientRegistryService.ReadCommand(
                        ORGANIZATION_ID,
                        ACTOR_ID,
                        "m3-wildcard-directory-deny",
                        "P3-02",
                        null,
                        null,
                        "%%",
                        null,
                        50,
                        null,
                        assurance,
                        assurance)))
                .isInstanceOf(PatientRegistryException.class)
                .hasMessageContaining("without wildcards");

        assertThatThrownBy(() -> patients.act(action(
                        ACTOR_ID,
                        "P3-04",
                        "search-duplicates",
                        registrationId,
                        null,
                        registrationId,
                        Map.of("officialName", "%_%"),
                        null,
                        "wildcard-duplicate-deny-" + marker,
                        null,
                        assurance)))
                .isInstanceOf(PatientRegistryException.class)
                .hasMessageContaining("without wildcards");

        assertThatThrownBy(() -> patients.act(action(
                        ACTOR_ID,
                        "P3-04",
                        "record-registration-disposition",
                        registrationId,
                        null,
                        registrationId,
                        Map.of(
                                "dispositionCode", "create_new",
                                "nameToUse", name,
                                "provenanceCode", "staff_attested"),
                        "Attempt creation before duplicate search",
                        "search-first-deny-" + marker,
                        null,
                        assurance)))
                .isInstanceOf(PatientRegistryException.class)
                .matches(error -> ((PatientRegistryException) error).reason()
                        == PatientRegistryException.Reason.CONFLICT)
                .hasMessageContaining("current duplicate review");

        assertThatThrownBy(() -> patients.act(action(
                        ACTOR_ID,
                        "P3-04",
                        "search-duplicates",
                        registrationId,
                        null,
                        registrationId,
                        Map.of("identifier", "SYNTHETIC-IDENTIFIER"),
                        null,
                        "identifier-deny-" + marker,
                        null,
                        assurance)))
                .isInstanceOf(PatientRegistryException.class)
                .matches(error -> ((PatientRegistryException) error).reason()
                        == PatientRegistryException.Reason.INVALID)
                .hasMessageContaining("approved local scheme");

        patients.act(action(
                ACTOR_ID,
                "P3-04",
                "search-duplicates",
                registrationId,
                null,
                registrationId,
                Map.of("officialName", name),
                null,
                "duplicate-search-" + marker,
                null,
                assurance));
        patients.act(action(
                ACTOR_ID,
                "P3-04",
                "record-registration-disposition",
                registrationId,
                null,
                registrationId,
                Map.of(
                        "dispositionCode", "create_new",
                        "nameToUse", name,
                        "provenanceCode", "staff_attested"),
                "Continue new after reviewing the duplicate candidate",
                "duplicate-disposition-" + marker,
                null,
                assurance));

        var newPatientId = uuidValue("""
                SELECT selected_patient_id FROM patient_registration_runs
                WHERE id='%s'
                """.formatted(registrationId));
        var candidateId = uuidValue("""
                SELECT id FROM patient_duplicate_candidates
                WHERE organization_id='%s'
                  AND patient_a_id IN ('%s','%s')
                  AND patient_b_id IN ('%s','%s')
                ORDER BY created_at DESC LIMIT 1
                """.formatted(
                ORGANIZATION_ID,
                existingPatientId,
                newPatientId,
                existingPatientId,
                newPatientId));
        assertThat(candidateId).isNotNull();
        assertThat(stringValue("""
                SELECT match_band FROM patient_duplicate_candidates WHERE id='%s'
                """.formatted(candidateId))).isEqualTo("possible_review");
        var evidence = stringValue("""
                SELECT payload::text FROM audit_events
                WHERE event_name='patient.duplicate.detected' AND subject_id='%s'
                """.formatted(candidateId));
        assertThat(evidence)
                .contains("factorCategories", "resultDigest")
                .doesNotContain(name, "SYNTHETIC-IDENTIFIER");
    }

    @Test
    void hidesRegistrationStagingFromANonCreatorWithoutManagePermission()
            throws SQLException {
        var assurance = clock.instant();
        var marker = UUID.randomUUID().toString().substring(0, 8);
        var registrationId = startRegistration(
                ACTOR_ID,
                "ownership-" + marker,
                assurance,
                "ownership-start-" + marker);

        var screen = screenAs(
                SEARCHER_ID,
                "P3-04",
                null,
                registrationId,
                assurance);
        assertThat(screen.rows()).isEmpty();
        assertThatThrownBy(() -> patients.act(action(
                        SEARCHER_ID,
                        "P3-04",
                        "search-duplicates",
                        registrationId,
                        null,
                        registrationId,
                        Map.of("officialName", "Synthetic Ownership " + marker),
                        null,
                        "ownership-search-deny-" + marker,
                        null,
                        assurance)))
                .isInstanceOf(PatientRegistryException.class)
                .matches(error -> ((PatientRegistryException) error).reason()
                        == PatientRegistryException.Reason.NOT_FOUND);
    }

    @Test
    void keepsUrgentTemporaryRegistrationFailClosedWithoutReconciliationWorker()
            throws SQLException {
        var assurance = clock.instant();
        var marker = UUID.randomUUID().toString().substring(0, 8);
        var before = longValue("""
                SELECT count(*) FROM patient_registration_runs
                WHERE organization_id='%s'
                """.formatted(ORGANIZATION_ID));

        assertThatThrownBy(() -> patients.act(action(
                        "P3-03",
                        "start-registration",
                        null,
                        null,
                        null,
                        Map.of(
                                "registrationSource", "urgent_care",
                                "supplierRelationshipKey", "direct_care",
                                "purposeKey", "urgent_care",
                                "urgent", "true",
                                "urgentReasonCode", "identity_unavailable"),
                        "Start urgent synthetic registration",
                        "urgent-fail-closed-" + marker,
                        assurance)))
                .isInstanceOf(PatientRegistryException.class)
                .hasMessageContaining("reconciliation worker");
        assertThat(longValue("""
                SELECT count(*) FROM patient_registration_runs
                WHERE organization_id='%s'
                """.formatted(ORGANIZATION_ID))).isEqualTo(before);
        assertThat(screen("P3-03", null, assurance).notices())
                .extracting(notice -> notice.title())
                .contains("Urgent temporary pathway unavailable");
    }

    @Test
    void rejectsCrossTenantReadsAndWrongOperationDirectWrites() throws SQLException {
        var marker = UUID.randomUUID().toString().substring(0, 8);
        var patientId = registerPatient(
                "Synthetic RLS " + marker,
                ACTOR_ID,
                clock.instant(),
                "rls-" + marker);
        var registrationId = uuidValue("""
                SELECT id FROM patient_registration_runs
                WHERE organization_id='%s' AND completed_patient_id='%s'
                ORDER BY completed_at DESC LIMIT 1
                """.formatted(ORGANIZATION_ID, patientId));
        try (var connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), APP_USER, APP_PASSWORD);
                var statement = connection.createStatement()) {
            statement.execute("SELECT set_config('app.current_organization_id','%s',false)"
                    .formatted(ORGANIZATION_ID));
            statement.execute("SELECT set_config('app.current_actor_id','%s',false)"
                    .formatted(ACTOR_ID));
            statement.execute("SELECT set_config('app.current_operation_key','patient.registration.start',false)");

            assertThatThrownBy(() -> statement.executeUpdate("""
                            UPDATE patient_profiles
                            SET lifecycle_state='inactive',updated_at=clock_timestamp(),
                                updated_by='%s',lock_version=lock_version+1
                            WHERE id='%s'
                            """.formatted(ACTOR_ID, patientId)))
                    .isInstanceOf(SQLException.class)
                    .matches(error -> "42501".equals(((SQLException) error).getSQLState()));

            statement.execute("SELECT set_config('app.current_operation_key','patient.profile.manage',false)");
            assertThatThrownBy(() -> statement.executeUpdate("""
                            UPDATE patient_profiles
                            SET lifecycle_state='deceased',updated_at=clock_timestamp(),
                                updated_by='%s',lock_version=lock_version+1
                            WHERE id='%s'
                            """.formatted(ACTOR_ID, patientId)))
                    .isInstanceOf(SQLException.class)
                    .matches(error -> "23514".equals(((SQLException) error).getSQLState()));

            statement.execute("SELECT set_config('app.current_operation_key','patient.registration.manage',false)");
            assertThatThrownBy(() -> statement.executeUpdate("""
                            UPDATE patient_registration_runs
                            SET status='ready',current_step='review',
                                updated_at=clock_timestamp(),updated_by='%s',
                                lock_version=lock_version+1
                            WHERE id='%s'
                            """.formatted(ACTOR_ID, registrationId)))
                    .isInstanceOf(SQLException.class)
                    .matches(error -> "23514".equals(((SQLException) error).getSQLState()));

            statement.execute("SELECT set_config('app.current_organization_id','%s',false)"
                    .formatted(UUID.randomUUID()));
            try (var result = statement.executeQuery(
                    "SELECT count(*) FROM patient_profiles WHERE id='%s'".formatted(patientId))) {
                assertThat(result.next()).isTrue();
                assertThat(result.getLong(1)).isZero();
            }
        }
    }

    @Test
    void enforcesPatientHttpAuthenticationOriginAndRevisionContracts() throws Exception {
        var screenPath = "/api/v1/organizations/" + ORGANIZATION_ID
                + "/patients/screens/P3-01";
        mockMvc.perform(get(screenPath)).andExpect(status().isUnauthorized());

        var assurance = clock.instant();
        var session = authenticatedSession(assurance);
        var principal = ownerPrincipal();
        mockMvc.perform(get(screenPath).session(session).with(user(principal)))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().exists("X-Correlation-Id"));

        var marker = UUID.randomUUID().toString().substring(0, 8);
        var patientId = registerPatient(
                "Synthetic HTTP " + marker,
                ACTOR_ID,
                assurance,
                "http-" + marker);
        var actionPath = "/api/v1/organizations/" + ORGANIZATION_ID
                + "/patients/screens/P3-06/actions/add-contact";
        var body = """
                {
                  "targetId":"%s",
                  "patientId":"%s",
                  "reason":"Attempt a synthetic contact update without a revision",
                  "fields":{
                    "channel":"email",
                    "contactUse":"home",
                    "purposeKey":"care_coordination",
                    "value":"http.%s@example.test",
                    "primary":"true",
                    "preferred":"true",
                    "confidential":"true",
                    "provenanceCode":"patient_supplied"
                  }
                }
                """.formatted(patientId, patientId, marker);

        mockMvc.perform(post(actionPath)
                        .session(authenticatedSession(assurance))
                        .with(user(principal))
                        .with(csrf())
                        .header("Idempotency-Key", "m3-http-origin-deny-" + marker)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());

        mockMvc.perform(post(actionPath)
                        .session(authenticatedSession(assurance))
                        .with(user(principal))
                        .with(csrf())
                        .header("Origin", "http://localhost:4173")
                        .header("Idempotency-Key", "m3-http-precondition-" + marker)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isPreconditionRequired());
    }

    @Test
    void rejectsValidationReuseAfterAPatientChange() throws SQLException {
        var assurance = clock.instant();
        var marker = UUID.randomUUID().toString().substring(0, 8);
        var registrationId = startRegistration(
                ACTOR_ID,
                "validation-" + marker,
                assurance,
                "validation-start-" + marker);
        patients.act(action(
                ACTOR_ID,
                "P3-04",
                "search-duplicates",
                registrationId,
                null,
                registrationId,
                Map.of("officialName", "Synthetic Validation " + marker),
                null,
                "validation-search-" + marker,
                null,
                assurance));
        patients.act(action(
                ACTOR_ID,
                "P3-04",
                "record-registration-disposition",
                registrationId,
                null,
                registrationId,
                Map.of(
                        "dispositionCode", "create_new",
                        "nameToUse", "Synthetic Validation " + marker,
                        "provenanceCode", "staff_attested"),
                "Create a synthetic patient for validation binding",
                "validation-disposition-" + marker,
                null,
                assurance));
        var patientId = uuidValue("""
                SELECT selected_patient_id FROM patient_registration_runs WHERE id='%s'
                """.formatted(registrationId));
        patients.act(action(
                "P3-12",
                "validate-registration",
                registrationId,
                patientId,
                registrationId,
                Map.of(),
                "Validate the exact synthetic source revisions",
                "validation-first-" + marker,
                assurance));

        assertThat(stringValue("""
                SELECT validation_result->>'schemaVersion'
                FROM patient_registration_runs WHERE id='%s'
                """.formatted(registrationId))).isEqualTo("patient-registration-v1");
        assertThat(longValue("""
                SELECT jsonb_array_length(validation_result->'gates')
                FROM patient_registration_runs WHERE id='%s'
                """.formatted(registrationId))).isEqualTo(13);

        patients.act(action(
                "P3-05",
                "correct-identity",
                patientId,
                patientId,
                registrationId,
                Map.of(
                        "nameToUse", "Synthetic Validation Corrected " + marker,
                        "nameState", "provided",
                        "birthDateCertainty", "unknown",
                        "provenanceCode", "patient_supplied"),
                "Change the synthetic patient after validation",
                "validation-change-" + marker,
                assurance));

        assertThatThrownBy(() -> patients.act(action(
                        "P3-12",
                        "submit-registration",
                        registrationId,
                        patientId,
                        registrationId,
                        Map.of(),
                        "Attempt submission with stale patient validation",
                        "validation-stale-submit-" + marker,
                        assurance)))
                .isInstanceOf(PatientRegistryException.class)
                .matches(error -> ((PatientRegistryException) error).reason()
                        == PatientRegistryException.Reason.STALE)
                .hasMessageContaining("changed after validation");

        assertThat(registrationScreen(registrationId, assurance).rows().getFirst()
                        .allowedActionKeys())
                .contains("validate-registration", "submit-registration");
        patients.act(action(
                "P3-12",
                "validate-registration",
                registrationId,
                patientId,
                registrationId,
                Map.of(),
                "Revalidate after the synthetic patient change",
                "validation-refresh-" + marker,
                assurance));
        patients.act(action(
                "P3-12",
                "submit-registration",
                registrationId,
                patientId,
                registrationId,
                Map.of(),
                "Submit after refreshing exact validation evidence",
                "validation-submit-" + marker,
                assurance));
        assertThat(stringValue("""
                SELECT status FROM patient_registration_runs WHERE id='%s'
                """.formatted(registrationId))).isEqualTo("completed");
    }

    @Test
    void preservesPatientHistoryAndKeepsSensitiveValuesOutOfEvidence() throws SQLException {
        var assurance = clock.instant();
        var marker = UUID.randomUUID().toString().substring(0, 8);
        var patientId = registerPatient(
                "Synthetic History " + marker,
                ACTOR_ID,
                assurance,
                "history-" + marker);
        var correctedName = "Synthetic Corrected " + marker;
        var emailOne = "first." + marker + "@example.test";
        var emailTwo = "second." + marker + "@example.test";
        var addressOne = "101 Synthetic Avenue " + marker;
        var addressTwo = "202 Synthetic Avenue " + marker;

        patients.act(action(
                "P3-05",
                "correct-identity",
                patientId,
                patientId,
                null,
                Map.of(
                        "nameToUse", correctedName,
                        "nameState", "provided",
                        "birthDateCertainty", "unknown",
                        "provenanceCode", "patient_supplied"),
                "Correct identity from synthetic source evidence",
                "history-identity-" + marker,
                assurance));
        patients.act(action(
                "P3-06",
                "add-contact",
                patientId,
                patientId,
                null,
                Map.of(
                        "channel", "email",
                        "contactUse", "home",
                        "purposeKey", "care_coordination",
                        "value", emailOne,
                        "primary", "true",
                        "preferred", "true",
                        "confidential", "true",
                        "provenanceCode", "patient_supplied"),
                "Add the first synthetic contact record",
                "history-contact-one-" + marker,
                assurance));
        patients.act(action(
                "P3-06",
                "add-contact",
                patientId,
                patientId,
                null,
                Map.of(
                        "channel", "email",
                        "contactUse", "home",
                        "purposeKey", "care_coordination",
                        "value", emailTwo,
                        "primary", "true",
                        "preferred", "true",
                        "confidential", "true",
                        "provenanceCode", "patient_supplied"),
                "Add a replacement synthetic primary contact",
                "history-contact-two-" + marker,
                assurance));
        patients.act(action(
                "P3-06",
                "add-address",
                patientId,
                patientId,
                null,
                Map.ofEntries(
                        Map.entry("addressUse", "home"),
                        Map.entry("purposeKey", "care_coordination"),
                        Map.entry("line1", addressOne),
                        Map.entry("locality", "Synthetic City"),
                        Map.entry("countryCode", "IN"),
                        Map.entry("primary", "true"),
                        Map.entry("preferred", "true"),
                        Map.entry("confidential", "true"),
                        Map.entry("provenanceCode", "patient_supplied")),
                "Add the first synthetic address record",
                "history-address-one-" + marker,
                assurance));
        patients.act(action(
                "P3-06",
                "add-address",
                patientId,
                patientId,
                null,
                Map.ofEntries(
                        Map.entry("addressUse", "home"),
                        Map.entry("purposeKey", "care_coordination"),
                        Map.entry("line1", addressTwo),
                        Map.entry("locality", "Synthetic City"),
                        Map.entry("countryCode", "IN"),
                        Map.entry("primary", "true"),
                        Map.entry("preferred", "true"),
                        Map.entry("confidential", "true"),
                        Map.entry("provenanceCode", "patient_supplied")),
                "Add a replacement synthetic primary address",
                "history-address-two-" + marker,
                assurance));
        patients.act(action(
                "P3-07",
                "set-communication-preference",
                patientId,
                patientId,
                null,
                Map.of(
                        "purposeKey", "care_coordination",
                        "channel", "email",
                        "decision", "allow",
                        "languageTag", "en-IN",
                        "provenanceCode", "patient_supplied"),
                "Record the initial synthetic communication preference",
                "history-preference-one-" + marker,
                assurance));
        patients.act(action(
                "P3-07",
                "set-communication-preference",
                patientId,
                patientId,
                null,
                Map.of(
                        "purposeKey", "care_coordination",
                        "channel", "email",
                        "decision", "deny",
                        "languageTag", "en-IN",
                        "provenanceCode", "patient_supplied"),
                "Supersede the synthetic communication preference",
                "history-preference-two-" + marker,
                assurance));
        patients.act(action(
                "P3-09",
                "add-caregiver-relationship",
                patientId,
                patientId,
                null,
                Map.of(
                        "relatedPersonReference", UUID.randomUUID().toString(),
                        "relationshipTypeKey", "caregiver",
                        "displayLabel", "Synthetic caregiver " + marker,
                        "provenanceCode", "staff_attested"),
                "Record a relationship fact without granting authority",
                "history-relationship-" + marker,
                assurance));

        assertThat(longValue("""
                SELECT count(*) FROM patient_contacts
                WHERE patient_id='%s' AND status='active'
                """.formatted(patientId))).isEqualTo(2);
        assertThat(longValue("""
                SELECT count(*) FROM patient_identity_revisions
                WHERE patient_id='%s'
                """.formatted(patientId))).isEqualTo(2);
        assertThat(longValue("""
                SELECT count(*) FROM patient_identity_revisions successor
                JOIN patient_identity_revisions predecessor
                  ON predecessor.organization_id=successor.organization_id
                 AND predecessor.id=successor.predecessor_revision_id
                 AND predecessor.patient_id=successor.patient_id
                 AND predecessor.profile_revision<successor.profile_revision
                WHERE successor.patient_id='%s'
                """.formatted(patientId))).isOne();
        assertThat(stringValue("""
                SELECT payload::text FROM audit_events
                WHERE event_name='patient.identity.corrected'
                  AND subject_id='%s'
                ORDER BY occurred_at DESC LIMIT 1
                """.formatted(patientId)))
                .contains("predecessorReference", "successorReference")
                .doesNotContain(correctedName);
        assertThat(longValue("""
                SELECT count(*) FROM patient_contacts
                WHERE patient_id='%s' AND status='active' AND primary_contact
                """.formatted(patientId))).isOne();
        assertThat(stringValue("""
                SELECT payload::text FROM audit_events
                WHERE event_name='patient.contact.changed'
                  AND payload->>'patientId'='%s'
                ORDER BY occurred_at DESC,id DESC LIMIT 1
                """.formatted(patientId)))
                .contains("priorPrimaryReferences")
                .doesNotContain(emailOne, emailTwo);
        assertThat(longValue("""
                SELECT count(*) FROM patient_addresses
                WHERE patient_id='%s' AND status='active'
                """.formatted(patientId))).isEqualTo(2);
        assertThat(longValue("""
                SELECT count(*) FROM patient_addresses
                WHERE patient_id='%s' AND status='active' AND primary_address
                """.formatted(patientId))).isOne();
        assertThat(stringValue("""
                SELECT payload::text FROM audit_events
                WHERE event_name='patient.address.changed'
                  AND payload->>'patientId'='%s'
                ORDER BY occurred_at DESC,id DESC LIMIT 1
                """.formatted(patientId)))
                .contains("priorPrimaryReferences")
                .doesNotContain(addressOne, addressTwo);
        assertThat(longValue("""
                SELECT count(*) FROM communication_preferences
                WHERE patient_id='%s' AND status='superseded' AND effective_to IS NOT NULL
                """.formatted(patientId))).isOne();
        assertThat(longValue("""
                SELECT count(*) FROM communication_preferences
                WHERE patient_id='%s' AND status='active' AND decision='deny'
                """.formatted(patientId))).isOne();
        assertThat(stringValue("""
                SELECT payload::text FROM audit_events
                WHERE event_name='patient.preference.changed'
                  AND payload->>'patientId'='%s'
                ORDER BY occurred_at DESC,id DESC LIMIT 1
                """.formatted(patientId))).contains("predecessorReference");
        assertThat(stringValue("""
                SELECT supplier_relationship_key FROM patient_registration_runs
                WHERE selected_patient_id='%s'
                ORDER BY created_at DESC LIMIT 1
                """.formatted(patientId))).isEqualTo("direct_care");

        var contactScreen = screen("P3-06", patientId, assurance);
        assertThat(contactScreen.rows())
                .filteredOn(row -> !row.id().equals(patientId)
                        && row.values().get("secondary").startsWith("email"))
                .hasSize(2)
                .allSatisfy(row -> assertThat(row.values().get("primary"))
                        .doesNotContain(emailOne, emailTwo));
        assertThat(contactScreen.rows())
                .filteredOn(row -> !row.id().equals(patientId)
                        && row.values().get("secondary").startsWith("home"))
                .hasSize(2)
                .allSatisfy(row -> assertThat(row.values().get("primary"))
                        .doesNotContain(addressOne, addressTwo));

        assertThat(screen("P3-08", patientId, assurance).actions()).isEmpty();
        assertThat(screen("P3-10", patientId, assurance).actions()).isEmpty();
        assertThat(screen("P3-11", patientId, assurance).actions()).isEmpty();
        assertThat(screen("P3-09", patientId, assurance).notices())
                .extracting(notice -> notice.title())
                .contains("Authority policy unavailable");
        assertThat(screen("P3-13", patientId, assurance).notices())
                .extracting(notice -> notice.title())
                .contains("Patient export unavailable");
        var timeline = screen("P3-16", patientId, assurance);
        assertThat(timeline.notices())
                .extracting(notice -> notice.title())
                .contains("Minimum-necessary timeline");
        assertThat(timeline.rows())
                .extracting(row -> row.values().get("primary"))
                .contains(
                        "Patient Identity Corrected",
                        "Patient Contact Changed",
                        "Patient Address Changed",
                        "Patient Preference Changed");
        assertThat(timeline.rows())
                .allSatisfy(row -> assertThat(row.values())
                        .containsEntry("redaction", "Minimum-necessary summary")
                        .containsKeys("schema", "correlation")
                        .doesNotContainKey("payload"));

        for (var sensitive : List.of(
                correctedName, emailOne, emailTwo, addressOne, addressTwo)) {
            assertThat(longValue("""
                    SELECT count(*) FROM audit_events
                    WHERE payload::text LIKE '%%%s%%'
                    """.formatted(sensitive))).isZero();
            assertThat(longValue("""
                    SELECT count(*) FROM outbox_events
                    WHERE payload::text LIKE '%%%s%%'
                    """.formatted(sensitive))).isZero();
        }
    }

    @Test
    void projectsExpiredRegistrationsAsTerminalAndExcludesThemFromOpenWork()
            throws SQLException {
        var assurance = clock.instant();
        var marker = UUID.randomUUID().toString().substring(0, 8);
        var registrationId = startRegistration(
                ACTOR_ID,
                "expired-" + marker,
                assurance,
                "expired-registration-" + marker);

        executeUpdate("""
                UPDATE patient_registration_runs
                SET created_at=clock_timestamp()-interval '2 days',
                    expires_at=clock_timestamp()-interval '1 second'
                WHERE id='%s'
                """.formatted(registrationId));

        var registration = registrationScreen(registrationId, assurance).rows().stream()
                .filter(row -> row.id().equals(registrationId))
                .findFirst()
                .orElseThrow();
        assertThat(registration.status()).isEqualTo("expired");
        assertThat(registration.allowedActionKeys()).isEmpty();

        var dashboard = screen("P3-01", null, assurance);
        var openRegistrations = dashboard.metrics().stream()
                .filter(metric -> metric.key().equals("registrations"))
                .findFirst()
                .orElseThrow()
                .value();
        assertThat(openRegistrations).isEqualTo(longValue("""
                SELECT count(*) FROM patient_registration_runs
                WHERE organization_id='%s'
                  AND status IN ('collecting','duplicate_review','ready','submitted')
                  AND expires_at>clock_timestamp()
                """.formatted(ORGANIZATION_ID)));
    }

    @Test
    void recoversExpiredDuplicateLeaseAndRequiresTheCurrentReviewer()
            throws SQLException {
        var assurance = clock.instant();
        var marker = UUID.randomUUID().toString().substring(0, 8);
        var name = "Synthetic Lease " + marker;
        var firstPatientId = registerPatient(
                name, ACTOR_ID, assurance, "lease-a-" + marker);
        var secondPatientId = registerPatient(
                name, ACTOR_ID, assurance, "lease-b-" + marker);
        var candidateId = uuidValue("""
                SELECT id FROM patient_duplicate_candidates
                WHERE organization_id='%s'
                  AND patient_a_id IN ('%s','%s')
                  AND patient_b_id IN ('%s','%s')
                  AND status='open'
                ORDER BY created_at DESC LIMIT 1
                """.formatted(
                ORGANIZATION_ID,
                firstPatientId,
                secondPatientId,
                firstPatientId,
                secondPatientId));

        patients.act(action(
                ACTOR_ID,
                "P3-14",
                "claim-duplicate",
                candidateId,
                firstPatientId,
                null,
                Map.of(),
                "Claim the synthetic duplicate lease",
                "lease-first-claim-" + marker,
                null,
                assurance));

        assertThatThrownBy(() -> patients.act(action(
                        CHECKER_ID,
                        "P3-14",
                        "disposition-duplicate",
                        candidateId,
                        firstPatientId,
                        null,
                        Map.of(
                                "dispositionCode", "not_duplicate",
                                "reasonCode", "independent_evidence"),
                        "Attempt disposition without the active lease",
                        "lease-nonholder-deny-" + marker,
                        null,
                        assurance)))
                .isInstanceOf(PatientRegistryException.class)
                .hasMessageContaining("current review lease");

        executeUpdate("""
                UPDATE patient_duplicate_candidates
                SET lease_expires_at=clock_timestamp()-interval '1 second'
                WHERE id='%s'
                """.formatted(candidateId));

        var recovered = screen("P3-14", null, assurance).rows().stream()
                .filter(row -> row.id().equals(candidateId))
                .findFirst()
                .orElseThrow();
        assertThat(recovered.status()).isEqualTo("open");
        assertThat(recovered.values().get("assignment")).isEqualTo("Unassigned");
        assertThat(recovered.allowedActionKeys()).containsExactly("claim-duplicate");

        patients.act(action(
                CHECKER_ID,
                "P3-14",
                "claim-duplicate",
                candidateId,
                firstPatientId,
                null,
                Map.of(),
                "Recover the expired synthetic duplicate lease",
                "lease-recovery-claim-" + marker,
                null,
                assurance));

        assertThatThrownBy(() -> patients.act(action(
                        ACTOR_ID,
                        "P3-14",
                        "disposition-duplicate",
                        candidateId,
                        firstPatientId,
                        null,
                        Map.of(
                                "dispositionCode", "not_duplicate",
                                "reasonCode", "independent_evidence"),
                        "Attempt disposition after lease reassignment",
                        "lease-old-holder-deny-" + marker,
                        null,
                        assurance)))
                .isInstanceOf(PatientRegistryException.class)
                .hasMessageContaining("current review lease");

        patients.act(action(
                CHECKER_ID,
                "P3-14",
                "disposition-duplicate",
                candidateId,
                firstPatientId,
                null,
                Map.of(
                        "dispositionCode", "not_duplicate",
                        "reasonCode", "independent_evidence"),
                "Dismiss after current-reviewer evidence comparison",
                "lease-current-holder-disposition-" + marker,
                null,
                assurance));
        assertThat(stringValue("""
                SELECT status FROM patient_duplicate_candidates WHERE id='%s'
                """.formatted(candidateId))).isEqualTo("dismissed");
        assertThat(stringValue("""
                SELECT lease_reference::text FROM patient_duplicate_candidates WHERE id='%s'
                """.formatted(candidateId))).isNull();
    }

    @Test
    void executesThreePartyMergeAndConsumesApprovalOnce() throws SQLException {
        var assurance = clock.instant();
        var marker = UUID.randomUUID().toString().substring(0, 8);
        var name = "Synthetic Merge " + marker;
        var survivorId = registerPatient(name, ACTOR_ID, assurance, "merge-a-" + marker);
        var duplicateId = registerPatient(name, ACTOR_ID, assurance, "merge-b-" + marker);
        var candidateId = uuidValue("""
                SELECT id FROM patient_duplicate_candidates
                WHERE organization_id='%s'
                  AND patient_a_id IN ('%s','%s')
                  AND patient_b_id IN ('%s','%s')
                  AND status='open'
                ORDER BY created_at DESC LIMIT 1
                """.formatted(
                ORGANIZATION_ID,
                survivorId,
                duplicateId,
                survivorId,
                duplicateId));
        assertThat(candidateId).isNotNull();

        patients.act(action(
                ACTOR_ID,
                "P3-14",
                "claim-duplicate",
                candidateId,
                survivorId,
                null,
                Map.of(),
                "Claim the synthetic duplicate for review",
                "merge-claim-" + marker,
                null,
                assurance));
        var requestFields = Map.of(
                "survivorPatientId", survivorId.toString(),
                "duplicatePatientId", duplicateId.toString(),
                "reasonCode", "same_synthetic_person");
        var requestReason = "Request merge after reviewing synthetic identity evidence";
        var requestPreview = preview(
                ACTOR_ID,
                "request-patient-merge",
                candidateId,
                survivorId,
                requestFields,
                requestReason,
                assurance);
        patients.act(action(
                ACTOR_ID,
                "P3-15",
                "request-patient-merge",
                candidateId,
                survivorId,
                null,
                requestFields,
                requestReason,
                "merge-request-" + marker,
                requestPreview.token(),
                assurance));
        var requestId = uuidValue("""
                SELECT id FROM patient_merge_requests
                WHERE organization_id='%s' AND duplicate_candidate_id='%s'
                """.formatted(ORGANIZATION_ID, candidateId));

        try (var connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), APP_USER, APP_PASSWORD);
                var statement = connection.createStatement()) {
            statement.execute("SELECT set_config('app.current_organization_id','%s',false)"
                    .formatted(ORGANIZATION_ID));
            statement.execute("SELECT set_config('app.current_actor_id','%s',false)"
                    .formatted(ACTOR_ID));
            statement.execute("SELECT set_config('app.current_operation_key','patient.merge.decide',false)");
            var assuranceReference = UUID.randomUUID();
            assertThatThrownBy(() -> statement.executeUpdate("""
                            INSERT INTO patient_merge_decisions
                                (organization_id,merge_request_id,request_revision,
                                 impact_digest,decision,reason_code,checker_id,
                                 assurance_reference,decision_expires_at,status,
                                 provenance_source,created_by,updated_by)
                            SELECT organization_id,id,lock_version,impact_digest,
                                   'approve','direct_self_approval_attack',requested_by,
                                   '%s',clock_timestamp()+interval '10 minutes','approved',
                                   'synthetic_direct_attack',requested_by,requested_by
                            FROM patient_merge_requests WHERE id='%s'
                            """.formatted(assuranceReference, requestId)))
                    .isInstanceOf(SQLException.class)
                    .matches(error -> "42501".equals(((SQLException) error).getSQLState()));
        }

        var decisionFields = Map.of(
                "decisionCode", "approve",
                "reasonCode", "independent_evidence_confirmed");
        assertThatThrownBy(() -> patients.act(action(
                        ACTOR_ID,
                        "P3-15",
                        "decide-patient-merge",
                        requestId,
                        survivorId,
                        null,
                        decisionFields,
                        "Attempt self approval of the merge request",
                        "merge-self-deny-" + marker,
                        null,
                        assurance)))
                .isInstanceOf(PatientRegistryException.class)
                .hasMessageContaining("cannot decide their own request");

        patients.act(action(
                CHECKER_ID,
                "P3-15",
                "decide-patient-merge",
                requestId,
                survivorId,
                null,
                decisionFields,
                "Approve independently after comparing exact revisions",
                "merge-decision-" + marker,
                null,
                assurance));
        var decisionId = uuidValue("""
                SELECT id FROM patient_merge_decisions
                WHERE organization_id='%s' AND merge_request_id='%s'
                """.formatted(ORGANIZATION_ID, requestId));
        var executionFields = Map.of("decisionId", decisionId.toString());
        var executionReason = "Execute the independently approved synthetic merge";
        var checkerPreview = preview(
                CHECKER_ID,
                "execute-patient-merge",
                requestId,
                survivorId,
                executionFields,
                executionReason,
                assurance);
        assertThatThrownBy(() -> patients.act(action(
                        CHECKER_ID,
                        "P3-15",
                        "execute-patient-merge",
                        requestId,
                        survivorId,
                        null,
                        executionFields,
                        executionReason,
                        "merge-checker-deny-" + marker,
                        checkerPreview.token(),
                        assurance)))
                .isInstanceOf(PatientRegistryException.class)
                .hasMessageContaining("separate checker");

        var executionPreview = preview(
                EXECUTOR_ID,
                "execute-patient-merge",
                requestId,
                survivorId,
                executionFields,
                executionReason,
                assurance);
        patients.act(action(
                EXECUTOR_ID,
                "P3-15",
                "execute-patient-merge",
                requestId,
                survivorId,
                null,
                executionFields,
                executionReason,
                "merge-execute-" + marker,
                executionPreview.token(),
                assurance));

        assertThat(stringValue("SELECT lifecycle_state FROM patient_profiles WHERE id='%s'"
                        .formatted(duplicateId)))
                .isEqualTo("merged");
        assertThat(stringValue("SELECT merged_into_patient_id::text FROM patient_profiles WHERE id='%s'"
                        .formatted(duplicateId)))
                .isEqualTo(survivorId.toString());
        assertThat(stringValue("SELECT status FROM patient_merge_requests WHERE id='%s'"
                        .formatted(requestId)))
                .isEqualTo("executed");
        assertThat(stringValue("SELECT status FROM patient_merge_decisions WHERE id='%s'"
                        .formatted(decisionId)))
                .isEqualTo("consumed");
        assertThat(stringValue("SELECT consumed_by::text FROM patient_merge_decisions WHERE id='%s'"
                        .formatted(decisionId)))
                .isEqualTo(EXECUTOR_ID.toString());
        assertThat(longValue("""
                SELECT count(*) FROM outbox_events
                WHERE event_name='m3.patient.merged.v1'
                  AND payload->>'mergeRequestId'='%s'
                """.formatted(requestId))).isOne();

        var resolvedSummary = screen("P3-13", duplicateId, assurance);
        assertThat(resolvedSummary.rows()).hasSize(1);
        assertThat(resolvedSummary.rows().getFirst().id()).isEqualTo(survivorId);
        assertThat(resolvedSummary.rows().getFirst().patientId()).isEqualTo(survivorId);
        assertThat(resolvedSummary.rows().getFirst().values())
                .containsEntry("lineage", "Resolved from a merged patient record");

        assertThat(screen("P3-16", survivorId, assurance).rows())
                .extracting(row -> row.values().get("primary"))
                .contains("Patient Merge Requested", "Patient Merge Decided", "Patient Merge Executed");
    }

    private UUID registerPatient(
            String name, UUID actorId, Instant assurance, String keyPrefix) throws SQLException {
        var source = keyPrefix + "-source";
        var registrationId = startRegistration(
                actorId, source, assurance, keyPrefix + "-start-registration");
        patients.act(action(
                actorId,
                "P3-04",
                "search-duplicates",
                registrationId,
                null,
                registrationId,
                Map.of("officialName", name),
                null,
                keyPrefix + "-search-duplicates",
                null,
                assurance));
        patients.act(action(
                actorId,
                "P3-04",
                "record-registration-disposition",
                registrationId,
                null,
                registrationId,
                Map.of(
                        "dispositionCode", "create_new",
                        "nameToUse", name,
                        "provenanceCode", "staff_attested"),
                "Create synthetic patient after bounded search",
                keyPrefix + "-record-disposition",
                null,
                assurance));
        var patientId = uuidValue("""
                SELECT selected_patient_id FROM patient_registration_runs WHERE id='%s'
                """.formatted(registrationId));
        patients.act(action(
                actorId,
                "P3-12",
                "validate-registration",
                registrationId,
                patientId,
                registrationId,
                Map.of(),
                "Validate the exact synthetic patient registration",
                keyPrefix + "-validate-registration",
                null,
                assurance));
        patients.act(action(
                actorId,
                "P3-12",
                "submit-registration",
                registrationId,
                patientId,
                registrationId,
                Map.of(),
                "Submit the exact synthetic patient registration",
                keyPrefix + "-submit-registration",
                null,
                assurance));
        return patientId;
    }

    private UUID startRegistration(
            UUID actorId, String source, Instant assurance, String idempotencyKey)
            throws SQLException {
        patients.act(action(
                actorId,
                "P3-03",
                "start-registration",
                null,
                null,
                null,
                Map.of(
                        "registrationSource", source,
                        "supplierRelationshipKey", "direct_care",
                        "purposeKey", "patient_registration",
                        "urgent", "false"),
                "Start a governed synthetic registration",
                idempotencyKey,
                null,
                assurance));
        return uuidValue("""
                SELECT id FROM patient_registration_runs
                WHERE organization_id='%s' AND registration_source='%s'
                ORDER BY created_at DESC LIMIT 1
                """.formatted(ORGANIZATION_ID, source));
    }

    private com.rootopathy.careos.patientregistry.domain.PatientRegistryScreen screen(
            String screenId, UUID patientId, Instant assurance) {
        return screenAs(ACTOR_ID, screenId, patientId, null, assurance);
    }

    private com.rootopathy.careos.patientregistry.domain.PatientRegistryScreen screenAs(
            UUID actorId,
            String screenId,
            UUID patientId,
            UUID registrationId,
            Instant assurance) {
        return patients.screen(new PatientRegistryService.ReadCommand(
                ORGANIZATION_ID,
                actorId,
                "m3-patient-history-integration",
                screenId,
                patientId,
                registrationId,
                null,
                null,
                100,
                null,
                assurance,
                assurance));
    }

    private com.rootopathy.careos.patientregistry.domain.PatientRegistryScreen registrationScreen(
            UUID registrationId, Instant assurance) {
        return patients.screen(new PatientRegistryService.ReadCommand(
                ORGANIZATION_ID,
                ACTOR_ID,
                "m3-registration-validation-integration",
                "P3-12",
                null,
                registrationId,
                null,
                null,
                100,
                null,
                assurance,
                assurance));
    }

    private static HttpPrincipal ownerPrincipal() {
        return new HttpPrincipal(ACTOR_ID);
    }

    private static MockHttpSession authenticatedSession(Instant assurance) {
        var session = new MockHttpSession();
        session.setAttribute(
                AuthenticationSessionState.AUTHENTICATED_AT, assurance.toEpochMilli());
        session.setAttribute(
                AuthenticationSessionState.RECENT_AUTHENTICATION_AT, assurance.toEpochMilli());
        session.setAttribute(
                AuthenticationSessionState.MFA_AUTHENTICATED_AT, assurance.toEpochMilli());
        return session;
    }

    private record HttpPrincipal(UUID id) implements UserDetails, AuthenticatedActor {
        @Override
        public List<? extends GrantedAuthority> getAuthorities() {
            return List.of(new SimpleGrantedAuthority("CAREOS_AUTHENTICATED"));
        }

        @Override
        public String getPassword() {
            return "unused-test-credential";
        }

        @Override
        public String getUsername() {
            return "m3.registration@example.test";
        }
    }

    private PatientRegistryService.ImpactPreviewResponse preview(
            UUID actorId,
            String actionKey,
            UUID targetId,
            UUID patientId,
            Map<String, String> fields,
            String reason,
            Instant assurance) throws SQLException {
        var revision = revision(targetId, actionKey);
        return patients.previewImpact(new PatientRegistryService.ImpactPreviewCommand(
                ORGANIZATION_ID,
                actorId,
                "m3-merge-integration",
                "P3-15",
                actionKey,
                targetId,
                patientId,
                null,
                null,
                reason,
                fields,
                "\"m3:P3-15:" + targetId + ":" + revision + "\"",
                assurance,
                assurance));
    }

    private PatientRegistryService.ActionCommand action(
            String screenId,
            String actionKey,
            UUID targetId,
            UUID patientId,
            UUID registrationId,
            Map<String, String> fields,
            String reason,
            String idempotencyKey,
            Instant assurance) throws SQLException {
        return action(
                ACTOR_ID,
                screenId,
                actionKey,
                targetId,
                patientId,
                registrationId,
                fields,
                reason,
                idempotencyKey,
                null,
                assurance);
    }

    private PatientRegistryService.ActionCommand action(
            UUID actorId,
            String screenId,
            String actionKey,
            UUID targetId,
            UUID patientId,
            UUID registrationId,
            Map<String, String> fields,
            String reason,
            String idempotencyKey,
            String impactToken,
            Instant assurance) throws SQLException {
        var revision = targetId == null ? null : revision(targetId, actionKey);
        var ifMatch = targetId == null
                ? null
                : "\"m3:" + screenId + ":" + targetId + ":" + revision + "\"";
        return new PatientRegistryService.ActionCommand(
                ORGANIZATION_ID,
                actorId,
                "m3-registration-integration",
                screenId,
                actionKey,
                targetId,
                patientId,
                registrationId,
                null,
                reason,
                fields,
                impactToken,
                ifMatch,
                idempotencyKey,
                assurance,
                assurance);
    }

    private long revision(UUID targetId, String actionKey) throws SQLException {
        var table = switch (actionKey) {
            case "correct-identity", "add-contact", "add-address",
                    "set-communication-preference", "add-caregiver-relationship",
                    "change-patient-lifecycle" -> "patient_profiles";
            case "claim-duplicate", "disposition-duplicate", "request-patient-merge" ->
                "patient_duplicate_candidates";
            case "decide-patient-merge", "execute-patient-merge" -> "patient_merge_requests";
            default -> "patient_registration_runs";
        };
        return longValue("SELECT lock_version FROM " + table + " WHERE id='" + targetId + "'");
    }

    private static UUID uuidValue(String sql) throws SQLException {
        var value = stringValue(sql);
        return value == null ? null : UUID.fromString(value);
    }

    private static String stringValue(String sql) throws SQLException {
        try (var connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), MIGRATOR_USER, MIGRATOR_PASSWORD);
                var statement = connection.createStatement();
                var result = statement.executeQuery(sql)) {
            return result.next() ? result.getString(1) : null;
        }
    }

    private static long longValue(String sql) throws SQLException {
        try (var connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), MIGRATOR_USER, MIGRATOR_PASSWORD);
                var statement = connection.createStatement();
                var result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getLong(1);
        }
    }

    private static void executeUpdate(String sql) throws SQLException {
        try (var connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), MIGRATOR_USER, MIGRATOR_PASSWORD);
                var statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }
}
