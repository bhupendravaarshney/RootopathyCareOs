package com.rootopathy.careos.billing.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rootopathy.careos.billing.application.BillingException;
import com.rootopathy.careos.billing.application.BillingService;
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
class BillingLifecycleIntegrationTest {
    private static final String MIGRATOR_USER = "careos_migrator";
    private static final String MIGRATOR_PASSWORD = "careos-migrator-test-only";
    private static final String APP_USER = "careos_app";
    private static final String APP_PASSWORD = "careos-app-test-only";
    private static final UUID ORGANIZATION_ID =
            UUID.fromString("01900000-0000-7000-8000-000000000001");
    private static final UUID ACTOR_ID =
            UUID.fromString("019f3000-0000-7000-8000-000000000101");
    private static final UUID MEMBERSHIP_ID =
            UUID.fromString("019f3000-0000-7000-8000-000000000102");
    private static final UUID SERVICE_ID =
            UUID.fromString("019f3000-0000-7000-8000-000000000201");
    private static final UUID PATIENT_ID =
            UUID.fromString("019f3000-0000-7000-8000-000000000301");
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
    private BillingService billing;

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
                    VALUES ('%s','m11.billing@example.test','M11 Billing User','active')
                    """.formatted(ACTOR_ID),
                    """
                    INSERT INTO organization_memberships
                        (id,organization_id,user_id,role_key,status,effective_from,updated_by)
                    VALUES ('%s','%s','%s','billing_administrator','active',
                            clock_timestamp()-interval '30 days','%s')
                    """.formatted(MEMBERSHIP_ID, ORGANIZATION_ID, ACTOR_ID, ACTOR_ID),
                    """
                    UPDATE organizations SET organization_type='care_network',locale='en-IN',status='active'
                    WHERE id='%s'
                    """.formatted(ORGANIZATION_ID),
                    """
                    INSERT INTO service_definitions
                        (id,organization_id,service_code,display_name,status,created_by,updated_by)
                    VALUES ('%s','%s','M11_TEST','M11 consultation','active','%s','%s')
                    """.formatted(SERVICE_ID, ORGANIZATION_ID, ACTOR_ID, ACTOR_ID),
                    """
                    INSERT INTO patient_profiles
                        (id,organization_id,patient_number,lifecycle_state,official_given_name,
                         official_family_name,name_state,provenance_source,verification_state,
                         created_by,updated_by)
                    VALUES ('%s','%s','M11PATIENT001','active','Finance','Evidence Patient',
                            'provided','test_fixture','evidence_checked','%s','%s')
                    """.formatted(PATIENT_ID, ORGANIZATION_ID, ACTOR_ID, ACTOR_ID));
        }
    }

    @Test
    void completesPricedEstimatedInvoicedSettledClaimedAndReconciledWorkflow() throws Exception {
        var assurance = clock.instant();
        var today = assurance.toString().substring(0, 10);
        var future = assurance.plus(30, ChronoUnit.DAYS).toString().substring(0, 10);

        act("P11-02", "create-price-book", null,
                Map.of(
                        "bookCode", "STANDARD_2026",
                        "displayName", "Standard 2026 pricing",
                        "currency", "INR",
                        "versionNumber", "1",
                        "effectiveFrom", today),
                null, "Create the governed standard price book.",
                "m11:create-book:integration-0001", assurance);
        var bookId = uuidValue("SELECT id FROM price_books");

        act("P11-02", "add-price-item", bookId,
                Map.of(
                        "serviceId", SERVICE_ID.toString(),
                        "itemCode", "CONSULTATION",
                        "displayName", "Clinical consultation",
                        "unitAmountMinor", "10000",
                        "taxBasisPoints", "1800",
                        "effectiveFrom", today),
                etag("P11-02", bookId, 0), "Add the approved consultation price.",
                "m11:add-price:integration-0001", assurance);
        var priceItemId = uuidValue("SELECT id FROM price_items");
        act("P11-02", "activate-price-book", bookId, Map.of(),
                etag("P11-02", bookId, 1), "Activate the exact complete price book.",
                "m11:activate-book:integration-0001", assurance);

        act("P11-03", "create-package", null,
                Map.of(
                        "priceBookId", bookId.toString(),
                        "packageCode", "CARE_START",
                        "displayName", "Care start package",
                        "packageAmountMinor", "18000",
                        "versionNumber", "1",
                        "effectiveFrom", today),
                null, "Create a package against active pricing.",
                "m11:create-package:integration-0001", assurance);
        var packageId = uuidValue("SELECT id FROM packages");
        act("P11-03", "add-package-entitlement", packageId,
                Map.of("serviceId", SERVICE_ID.toString(), "quantity", "2", "expiresAfterDays", "90"),
                etag("P11-03", packageId, 0), "Add the exact package service entitlement.",
                "m11:add-entitlement:integration-0001", assurance);
        act("P11-03", "activate-package", packageId, Map.of(),
                etag("P11-03", packageId, 1), "Activate the exact complete package.",
                "m11:activate-package:integration-0001", assurance);

        act("P11-04", "create-estimate", null,
                Map.of(
                        "patientId", PATIENT_ID.toString(),
                        "priceBookId", bookId.toString(),
                        "priceItemId", priceItemId.toString(),
                        "estimateNumber", "EST_2026_0001",
                        "quantity", "2",
                        "validUntil", future),
                null, "Create an exact patient estimate from active pricing.",
                "m11:create-estimate:integration-0001", assurance);
        var estimateId = uuidValue("SELECT id FROM estimates");
        assertThat(longValue("SELECT total_minor FROM estimates WHERE id='%s'".formatted(estimateId)))
                .isEqualTo(23600);
        act("P11-04", "finalize-estimate", estimateId, Map.of(),
                etag("P11-04", estimateId, 0), "Finalize the exact estimate snapshot.",
                "m11:finalize-estimate:integration-0001", assurance);

        act("P11-05", "issue-invoice", estimateId,
                Map.of("invoiceNumber", "INV_2026_0001", "dueOn", future),
                etag("P11-05", estimateId, 1), "Issue the invoice from the finalized estimate.",
                "m11:issue-invoice:integration-0001", assurance);
        var invoiceId = uuidValue("SELECT id FROM invoices");
        assertThat(longValue("SELECT count(*) FROM invoice_items WHERE invoice_id='%s'".formatted(invoiceId)))
                .isEqualTo(1);

        act("P11-07", "create-payment-intent", invoiceId,
                Map.of(
                        "providerKey", "test_provider",
                        "amountMinor", "23600",
                        "expiresAt", assurance.plus(1, ChronoUnit.HOURS).toString()),
                etag("P11-07", invoiceId, 0), "Create a card-data-free provider intent.",
                "m11:create-intent:integration-0001", assurance);
        assertThat(stringValue("SELECT provider_reference_digest FROM payment_intents"))
                .isNull();

        act("P11-06", "record-payment", invoiceId,
                Map.of(
                        "source", "manual_bank",
                        "paymentReference", "BANK-SETTLEMENT-0001",
                        "amountMinor", "5000",
                        "occurredAt", assurance.toString()),
                etag("P11-06", invoiceId, 0), "Record verified bank settlement evidence.",
                "m11:record-payment:integration-0001", assurance);
        var paymentId = uuidValue("SELECT id FROM payments");

        act("P11-08", "record-refund", paymentId,
                Map.of("refundReference", "REFUND-0001", "amountMinor", "1000"),
                null, "Authorize the documented partial refund.",
                "m11:record-refund:integration-0001", assurance);
        act("P11-08", "record-adjustment", invoiceId,
                Map.of(
                        "adjustmentReference", "ADJUSTMENT-0001",
                        "direction", "credit",
                        "amountMinor", "600"),
                etag("P11-08", invoiceId, 2), "Authorize the documented account credit.",
                "m11:record-adjustment:integration-0001", assurance);

        act("P11-09", "create-claim", invoiceId,
                Map.of("claimNumber", "CLAIM-0001", "payerKey", "test_payer", "amountMinor", "3000"),
                etag("P11-09", invoiceId, 3), "Create the invoice-bound payer claim.",
                "m11:create-claim:integration-0001", assurance);
        var claimId = uuidValue("SELECT id FROM claims");
        act("P11-09", "submit-claim", claimId, Map.of(),
                etag("P11-09", claimId, 0), "Submit the exact claim amount and currency.",
                "m11:submit-claim:integration-0001", assurance);
        act("P11-09", "record-remittance", claimId,
                Map.of(
                        "remittanceReference", "REMITTANCE-0001",
                        "amountMinor", "3000",
                        "receivedAt", assurance.toString(),
                        "evidenceDigest", DIGEST),
                etag("P11-09", claimId, 1), "Record exact payer remittance evidence.",
                "m11:record-remittance:integration-0001", assurance);

        act("P11-10", "create-reconciliation", null,
                Map.of(
                        "type", "claim",
                        "reconciliationReference", "RECONCILIATION-0001",
                        "periodStart", today,
                        "periodEnd", future,
                        "currency", "INR",
                        "expectedMinor", "3000",
                        "observedMinor", "2900",
                        "evidenceDigest", DIGEST),
                null, "Create exact claim reconciliation evidence.",
                "m11:create-reconciliation:integration-0001", assurance);
        var reconciliationId = uuidValue("SELECT id FROM reconciliations");
        act("P11-10", "complete-reconciliation", reconciliationId, Map.of(),
                etag("P11-10", reconciliationId, 0), "Resolve the documented reconciliation variance.",
                "m11:complete-reconciliation:integration-0001", assurance);

        act("P11-11", "request-financial-export", null,
                Map.of(
                        "exportType", "invoice_register",
                        "format", "csv",
                        "periodStart", today,
                        "periodEnd", future),
                null, "Request a bounded finance operations export.",
                "m11:request-export:integration-0001", assurance);

        assertThat(longValue("SELECT balance_minor FROM invoices WHERE id='%s'".formatted(invoiceId)))
                .isEqualTo(16000);
        assertThat(stringValue("SELECT status FROM claims WHERE id='%s'".formatted(claimId)))
                .isEqualTo("paid");
        assertThat(stringValue("SELECT status FROM reconciliations WHERE id='%s'".formatted(reconciliationId)))
                .isEqualTo("resolved");
        assertThat(stringValue("SELECT status FROM financial_exports"))
                .isEqualTo("requested");
        assertThat(longValue("SELECT count(*) FROM audit_events WHERE event_name LIKE 'billing.%%'"))
                .isGreaterThanOrEqualTo(17);
        assertThat(stringValue("SELECT payload::text FROM outbox_events ORDER BY occurred_at DESC LIMIT 1"))
                .doesNotContain("Finance", "Patient", "BANK", "card");

        var dashboard = billing.screen(new BillingService.ReadCommand(
                ORGANIZATION_ID, ACTOR_ID, "m11-dashboard-correlation", "P11-01",
                PATIENT_ID, invoiceId, null, null, 25, null, assurance, assurance));
        assertThat(dashboard.rows()).singleElement().satisfies(row -> {
            assertThat(row.status()).isEqualTo("partially_paid");
            assertThat(row.values().get("balanceMinor")).isEqualTo("16000");
        });

        assertThatThrownBy(() -> act("P11-06", "record-payment", invoiceId,
                        Map.of("paymentReference", "4111 1111 1111 1111"),
                        etag("P11-06", invoiceId, 4), "Never accept raw card data in CareOS.",
                        "m11:raw-card:integration-0001", assurance))
                .isInstanceOf(BillingException.class)
                .hasMessageContaining("prohibited");

        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), APP_USER, APP_PASSWORD)) {
            assertThatThrownBy(() -> connection.createStatement().executeUpdate(
                            "UPDATE payments SET amount_minor=999999"))
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
        billing.act(new BillingService.ActionCommand(
                ORGANIZATION_ID, ACTOR_ID, "m11-" + action + "-correlation", screen, action,
                target, reason, fields, etag, idempotency, assurance, assurance));
    }

    private static String etag(String screen, UUID targetId, long revision) {
        return "\"m11:" + screen + ":" + targetId + ":" + revision + "\"";
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
