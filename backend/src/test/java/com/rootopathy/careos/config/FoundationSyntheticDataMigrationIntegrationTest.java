package com.rootopathy.careos.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class FoundationSyntheticDataMigrationIntegrationTest {
    private static final String MIGRATOR_USER = "careos_migrator";
    private static final String MIGRATOR_PASSWORD = "careos-migrator-test-only";
    private static final String APP_USER = "careos_app";
    private static final Set<String> MODULE_2_TABLES = Set.of(
            "person_profiles",
            "person_profile_aliases",
            "person_contacts",
            "person_addresses",
            "organization_person_links",
            "person_match_keys",
            "person_merge_requests",
            "workforce_members",
            "workforce_identifiers",
            "employment_engagements",
            "practitioner_profiles",
            "professional_registrations",
            "qualifications",
            "practitioner_specialties",
            "practitioner_credentials",
            "credential_documents",
            "credential_scan_attempts",
            "credential_verifications",
            "scope_definitions",
            "scope_requirements",
            "scopes_of_practice",
            "scope_activities",
            "scope_restrictions",
            "workforce_assignments",
            "practitioner_service_assignments",
            "availability_profiles",
            "availability_periods",
            "availability_exceptions",
            "access_assignment_scopes",
            "workforce_readiness_runs",
            "workforce_readiness_results",
            "workforce_activation_requests",
            "workforce_offboarding_requests",
            "workforce_lifecycle_transitions",
            "workforce_configuration_snapshots",
            "workforce_configuration_change_requests",
            "workforce_configuration_change_items",
            "workforce_export_jobs",
            "credential_legal_holds",
            "practitioner_eligibility_evidence",
            "workforce_notification_deliveries",
            "workforce_registry_definitions",
            "workforce_registry_entries",
            "workforce_registry_versions");
    private static final Set<String> MODULE_3_TABLES = Set.of(
            "patient_profiles",
            "patient_identity_revisions",
            "patient_identifiers",
            "patient_contacts",
            "patient_addresses",
            "communication_preferences",
            "caregiver_relationships",
            "patient_authority_grants",
            "patient_portal_links",
            "patient_consents",
            "privacy_restrictions",
            "patient_safety_flags",
            "patient_match_keys",
            "patient_duplicate_candidates",
            "patient_merge_requests",
            "patient_merge_decisions",
            "patient_registration_runs");
    private static final Set<String> MODULE_4_TABLES = Set.of(
            "appointment_schedules",
            "appointment_slots",
            "appointment_requests",
            "appointments",
            "appointment_participants",
            "appointment_status_history",
            "appointment_assignments",
            "waitlist_entries",
            "appointment_reminders",
            "appointment_cancellations",
            "no_show_decisions",
            "appointment_payment_requirements",
            "external_calendar_links");
    private static final Set<String> MODULE_5_TABLES = Set.of(
            "episodes_of_care",
            "encounters",
            "encounter_participants",
            "encounter_status_history",
            "presenting_concerns",
            "clinical_problems",
            "diagnoses",
            "encounter_notes",
            "note_versions",
            "orders",
            "clinical_tasks",
            "encounter_signatures",
            "amendments",
            "red_flag_escalations");

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse(
                            "postgres:18-alpine@sha256:d3e1620b530c944afa6e887d22eb899824da68e19c52024bf98f5220c88a65b2")
                    .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("careos_test")
            .withUsername(MIGRATOR_USER)
            .withPassword(MIGRATOR_PASSWORD)
            .withInitScript("db/test-init.sql");

    @Test
    void removesOnlyAnUntouchedSyntheticTenantWhenTheFixtureIsDisabled() throws SQLException {
        flyway(MigrationVersion.fromVersion("18")).migrate();

        assertThat(count("organizations")).isOne();
        assertThat(count("facilities")).isOne();

        execute("UPDATE facilities SET name = 'Changed facility' WHERE code = 'GNO-01'");

        assertThatThrownBy(() -> flyway(null).migrate())
                .hasMessageContaining("changed or additional facilities");
        assertThat(appliedMigrationCount("19")).isZero();
        assertThat(count("organizations")).isOne();
        assertThat(count("facilities")).isOne();

        execute("UPDATE facilities SET name = 'ROOTOPATHY Greater Noida' WHERE code = 'GNO-01'");

        execute("""
                INSERT INTO users (id, email, display_name, status)
                VALUES ('01900000-0000-7000-8000-000000000901',
                        'seed-scope@rootopathy.test', 'Seed Scope Test', 'active')
                """);
        execute("""
                INSERT INTO organization_memberships
                    (organization_id, user_id, role_key, status)
                VALUES ('01900000-0000-7000-8000-000000000001',
                        '01900000-0000-7000-8000-000000000901',
                        'organization_member', 'active')
                """);

        assertThatThrownBy(() -> flyway(null).migrate())
                .hasMessageContaining("contains tenant data and cannot be removed automatically");
        assertThat(appliedMigrationCount("19")).isZero();
        assertThat(count("organizations")).isOne();
        assertThat(count("facilities")).isOne();

        execute("""
                DELETE FROM organization_memberships
                WHERE user_id = '01900000-0000-7000-8000-000000000901'
                """);
        execute("""
                DELETE FROM users
                WHERE id = '01900000-0000-7000-8000-000000000901'
                """);
        flyway(null).migrate();

        assertThat(appliedMigrationCount("19")).isOne();
        assertThat(appliedMigrationCount("89")).isOne();
        assertThat(count("organizations")).isZero();
        assertThat(count("facilities")).isZero();
        assertThat(MODULE_2_TABLES).hasSize(44);
        assertThat(publicTables()).containsAll(MODULE_2_TABLES);
        assertThat(MODULE_3_TABLES).hasSize(17);
        assertThat(publicTables()).containsAll(MODULE_3_TABLES);
        assertThat(MODULE_4_TABLES).hasSize(13);
        assertThat(publicTables()).containsAll(MODULE_4_TABLES);
        assertThat(MODULE_5_TABLES).hasSize(14);
        assertThat(publicTables()).containsAll(MODULE_5_TABLES);
        assertThat(tenantSecurityViolations()).isEmpty();
    }

    private static Flyway flyway(MigrationVersion target) {
        FluentConfiguration configuration = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), MIGRATOR_USER, MIGRATOR_PASSWORD)
                .locations("classpath:db/migration")
                .placeholders(Map.of(
                        "applicationRole", APP_USER,
                        "foundationSyntheticDataEnabled", "false"));
        if (target != null) {
            configuration.target(target);
        }
        return configuration.load();
    }

    private static long count(String table) throws SQLException {
        try (Connection connection = connection();
                var statement = connection.createStatement();
                var result = statement.executeQuery("SELECT count(*) FROM " + table)) {
            result.next();
            return result.getLong(1);
        }
    }

    private static long appliedMigrationCount(String version) throws SQLException {
        try (Connection connection = connection();
                var statement = connection.prepareStatement(
                        "SELECT count(*) FROM flyway_schema_history WHERE version = ? AND success")) {
            statement.setString(1, version);
            try (var result = statement.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        }
    }

    private static Set<String> publicTables() throws SQLException {
        try (Connection connection = connection();
                var statement = connection.createStatement();
                var result = statement.executeQuery("""
                        SELECT table_name
                        FROM information_schema.tables
                        WHERE table_schema = 'public'
                          AND table_type = 'BASE TABLE'
                        ORDER BY table_name
                        """)) {
            var tables = new TreeSet<String>();
            while (result.next()) {
                tables.add(result.getString(1));
            }
            return tables;
        }
    }

    private static Set<String> tenantSecurityViolations() throws SQLException {
        try (Connection connection = connection();
                var statement = connection.createStatement();
                var result = statement.executeQuery("""
                        SELECT relations.relname
                        FROM pg_class relations
                        JOIN pg_namespace schemas ON schemas.oid = relations.relnamespace
                        WHERE schemas.nspname = 'public'
                          AND relations.relkind IN ('r', 'p')
                          AND EXISTS (
                              SELECT 1
                              FROM pg_attribute columns
                              WHERE columns.attrelid = relations.oid
                                AND columns.attname = 'organization_id'
                                AND NOT columns.attisdropped)
                          AND (
                              NOT relations.relrowsecurity
                              OR NOT relations.relforcerowsecurity
                              OR NOT EXISTS (
                                  SELECT 1
                                  FROM pg_policy policies
                                  WHERE policies.polrelid = relations.oid))
                        ORDER BY relations.relname
                        """)) {
            var violations = new TreeSet<String>();
            while (result.next()) {
                violations.add(result.getString(1));
            }
            return violations;
        }
    }

    private static void execute(String sql) throws SQLException {
        try (Connection connection = connection(); var statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private static Connection connection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), MIGRATOR_USER, MIGRATOR_PASSWORD);
    }
}
