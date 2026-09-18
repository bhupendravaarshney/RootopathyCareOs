package com.rootopathy.careos.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;
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
        assertThat(count("organizations")).isZero();
        assertThat(count("facilities")).isZero();
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

    private static void execute(String sql) throws SQLException {
        try (Connection connection = connection(); var statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private static Connection connection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), MIGRATOR_USER, MIGRATOR_PASSWORD);
    }
}
