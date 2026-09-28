package com.rootopathy.careos.reporting.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rootopathy.careos.reporting.application.ReportingException;
import com.rootopathy.careos.reporting.application.ReportingService;
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
class ReportingLifecycleIntegrationTest {
    private static final String MIGRATOR_USER = "careos_migrator";
    private static final String MIGRATOR_PASSWORD = "careos-migrator-test-only";
    private static final String APP_USER = "careos_app";
    private static final String APP_PASSWORD = "careos-app-test-only";
    private static final UUID ORGANIZATION_ID =
            UUID.fromString("01900000-0000-7000-8000-000000000001");
    private static final UUID ACTOR_ID =
            UUID.fromString("019f4000-0000-7000-8000-000000000101");
    private static final UUID MEMBERSHIP_ID =
            UUID.fromString("019f4000-0000-7000-8000-000000000102");

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
    private ReportingService reporting;

    @Autowired
    private Clock clock;

    @BeforeEach
    void seedActor() throws SQLException {
        try (var connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), MIGRATOR_USER, MIGRATOR_PASSWORD);
                var statement = connection.createStatement()) {
            execute(
                    statement,
                    """
                    INSERT INTO users (id,email,display_name,status)
                    VALUES ('%s','m12.reporting@example.test','M12 Reporting User','active')
                    ON CONFLICT (id) DO NOTHING
                    """.formatted(ACTOR_ID),
                    """
                    INSERT INTO organization_memberships
                        (id,organization_id,user_id,role_key,status,effective_from,updated_by)
                    VALUES ('%s','%s','%s','reporting_analyst','active',
                            clock_timestamp()-interval '30 days','%s')
                    ON CONFLICT (id) DO NOTHING
                    """.formatted(MEMBERSHIP_ID, ORGANIZATION_ID, ACTOR_ID, ACTOR_ID),
                    """
                    UPDATE organizations SET organization_type='care_network',locale='en-IN',status='active'
                    WHERE id='%s'
                    """.formatted(ORGANIZATION_ID));
        }
    }

    @Test
    void freezesAggregateRunAndGovernsScheduleAndExportRequest() throws Exception {
        var assurance = clock.instant();
        var start = assurance.minus(7, ChronoUnit.DAYS).toString().substring(0, 10);
        var end = assurance.toString().substring(0, 10);

        act("P12-02", "run-report", null,
                Map.of("periodStart", start, "periodEnd", end), null,
                "Create the bounded operational aggregate snapshot.",
                "m12:run-report:integration-0001", assurance);
        var runId = uuidValue("SELECT id FROM report_runs");
        assertThat(stringValue("SELECT status FROM report_runs WHERE id='%s'".formatted(runId)))
                .isEqualTo("completed");
        assertThat(longValue("SELECT count(*) FROM report_run_metrics WHERE report_run_id='%s'".formatted(runId)))
                .isEqualTo(4);
        assertThat(stringValue("SELECT snapshot_digest FROM report_runs WHERE id='%s'".formatted(runId)))
                .matches("[0-9a-f]{64}");

        act("P12-09", "create-report-schedule", null,
                Map.of(
                        "scheduleCode", "OPS_WEEKLY_01",
                        "reportKey", "operational",
                        "format", "csv",
                        "cadence", "weekly",
                        "lookbackDays", "30",
                        "timezone", "Asia/Kolkata",
                        "nextRunAt", assurance.plus(7, ChronoUnit.DAYS).toString()),
                null, "Create the governed weekly operational report schedule.",
                "m12:create-schedule:integration-0001", assurance);
        var scheduleId = uuidValue("SELECT id FROM report_schedules");
        act("P12-09", "pause-report-schedule", scheduleId, Map.of(),
                etag("P12-09", scheduleId, 0), "Pause the schedule during reporting review.",
                "m12:pause-schedule:integration-0001", assurance);
        act("P12-09", "resume-report-schedule", scheduleId, Map.of(),
                etag("P12-09", scheduleId, 1), "Resume the reviewed reporting schedule.",
                "m12:resume-schedule:integration-0001", assurance);
        act("P12-09", "cancel-report-schedule", scheduleId, Map.of(),
                etag("P12-09", scheduleId, 2), "Cancel the obsolete reporting schedule definition.",
                "m12:cancel-schedule:integration-0001", assurance);
        assertThat(stringValue("SELECT status FROM report_schedules WHERE id='%s'".formatted(scheduleId)))
                .isEqualTo("cancelled");

        act("P12-09", "request-report-export", runId, Map.of("format", "csv"),
                etag("P12-09", runId, 1), "Request the exact aggregate report for governance review.",
                "m12:request-export:integration-0001", assurance);
        assertThat(stringValue("SELECT status FROM report_exports")).isEqualTo("requested");
        assertThat(stringValue("SELECT artifact_reference FROM report_exports")).isNull();
        assertThat(longValue("SELECT extract(epoch FROM (expires_at-requested_at))::bigint FROM report_exports"))
                .isEqualTo(3600);
        assertThat(stringValue("SELECT careos_m12_csv_safe_cell('=2+3')")).isEqualTo("'=2+3");

        var projection = reporting.screen(new ReportingService.ReadCommand(
                ORGANIZATION_ID, ACTOR_ID, "m12-projection-correlation", "P12-10",
                null, null, null, 50, null, assurance, assurance));
        assertThat(projection.rows()).extracting(row -> row.values().get("artifact"))
                .contains("report_run", "report_schedule", "report_export");
        assertThat(projection.rows()).allSatisfy(row ->
                assertThat(row.values().keySet())
                        .doesNotContain("patientName", "clinicalNarrative", "paymentReference"));
    }

    @Test
    void rejectsUnboundedPeriodsStaleSchedulesAndMetricMutation() throws Exception {
        var assurance = clock.instant();
        assertThatThrownBy(() -> act("P12-02", "run-report", null,
                Map.of("periodStart", "2024-01-01", "periodEnd", "2026-01-01"), null,
                "Attempt an unbounded report period for negative verification.",
                "m12:unbounded-run:integration-0001", assurance))
                .isInstanceOf(ReportingException.class)
                .hasMessageContaining("1 to 366 days");

        act("P12-02", "run-report", null,
                Map.of(
                        "periodStart", assurance.minus(1, ChronoUnit.DAYS).toString().substring(0, 10),
                        "periodEnd", assurance.toString().substring(0, 10)),
                null, "Create immutable aggregate evidence for mutation testing.",
                "m12:immutable-run:integration-0001", assurance);
        var metricId = uuidValue("SELECT id FROM report_run_metrics LIMIT 1");
        try (var connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), MIGRATOR_USER, MIGRATOR_PASSWORD);
                var statement = connection.createStatement()) {
            assertThatThrownBy(() -> statement.executeUpdate(
                            "UPDATE report_run_metrics SET metric_value=999 WHERE id='%s'".formatted(metricId)))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("append-only");
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
        reporting.act(new ReportingService.ActionCommand(
                ORGANIZATION_ID, ACTOR_ID, "m12-" + action + "-correlation", screen, action,
                target, reason, fields, etag, idempotency, assurance, assurance));
    }

    private static String etag(String screen, UUID targetId, long revision) {
        return "\"m12:" + screen + ":" + targetId + ":" + revision + "\"";
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
