package com.rootopathy.careos.integration.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rootopathy.careos.integration.application.IntegrationException;
import com.rootopathy.careos.integration.application.IntegrationService;
import com.rootopathy.careos.shared.domain.UuidV7Generator;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
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
class IntegrationLifecycleIntegrationTest {
    private static final String MIGRATOR_USER = "careos_migrator";
    private static final String MIGRATOR_PASSWORD = "careos-migrator-test-only";
    private static final String APP_USER = "careos_app";
    private static final String APP_PASSWORD = "careos-app-test-only";
    private static final UUID ORGANIZATION_ID =
            UUID.fromString("01900000-0000-7000-8000-000000000001");
    private static final UUID ACTOR_ID =
            UUID.fromString("019f5000-0000-7000-8000-000000000101");
    private static final UUID MEMBERSHIP_ID =
            UUID.fromString("019f5000-0000-7000-8000-000000000102");

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
    private IntegrationService integrations;

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
                    VALUES ('%s','m13.integration@example.test','M13 Integration User','active')
                    ON CONFLICT (id) DO NOTHING
                    """.formatted(ACTOR_ID),
                    """
                    INSERT INTO organization_memberships
                        (id,organization_id,user_id,role_key,status,effective_from,updated_by)
                    VALUES ('%s','%s','%s','integration_administrator','active',
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
    void governsExactMappingsConnectionsAndDisabledApiClients() throws Exception {
        var assurance = clock.instant();
        var mappingId = createAndActivateFhirMapping(
                "patient-r4-primary", "m13:create-map:lifecycle-0001",
                "m13:activate-map:lifecycle-0001", assurance);
        var connectionId = createAndValidateConnection(
                "P13-02", "fhir-primary", mappingId,
                "m13:create-fhir:lifecycle-0001", "m13:validate-fhir:lifecycle-0001", assurance);

        act("P13-02", "suspend-connection", connectionId, Map.of(),
                etag("P13-02", connectionId, 1),
                "Suspend the exact FHIR configuration during contract review.",
                "m13:suspend-fhir:lifecycle-0001", assurance);
        act("P13-02", "validate-connection", connectionId, Map.of(),
                etag("P13-02", connectionId, 2),
                "Revalidate the unchanged FHIR configuration after contract review.",
                "m13:revalidate-fhir:lifecycle-0001", assurance);
        act("P13-02", "retire-connection", connectionId, Map.of(),
                etag("P13-02", connectionId, 3),
                "Retire the exact FHIR connection without deleting exchange evidence.",
                "m13:retire-fhir:lifecycle-0001", assurance);
        act("P13-03", "retire-mapping", mappingId, Map.of(),
                etag("P13-03", mappingId, 1),
                "Retire the unreferenced active FHIR mapping version.",
                "m13:retire-map:lifecycle-0001", assurance);

        act("P13-09", "register-api-client", null,
                Map.of(
                        "clientCode", "mobile-review-client",
                        "displayName", "Mobile review client",
                        "publicIdentifierDigest", "1".repeat(64),
                        "credentialReferenceDigest", "2".repeat(64),
                        "scopesDigest", "3".repeat(64),
                        "purposeKey", "mobile_access",
                        "accessPolicyVersion", "policy-v1"),
                null, "Register disabled client metadata for security review.",
                "m13:register-client:lifecycle-0001", assurance);
        var clientId = uuidValue(
                "SELECT id FROM integration_api_clients WHERE client_code='mobile-review-client'");
        assertThat(booleanValue(
                        "SELECT access_enabled FROM integration_api_clients WHERE id='%s'".formatted(clientId)))
                .isFalse();
        act("P13-09", "revoke-api-client", clientId, Map.of(),
                etag("P13-09", clientId, 0),
                "Revoke the disabled client registration after security review.",
                "m13:revoke-client:lifecycle-0001", assurance);

        assertThat(stringValue(
                        "SELECT status FROM integration_connections WHERE id='%s'".formatted(connectionId)))
                .isEqualTo("retired");
        assertThat(stringValue(
                        "SELECT status FROM integration_mapping_versions WHERE id='%s'".formatted(mappingId)))
                .isEqualTo("retired");
        assertThat(stringValue(
                        "SELECT status FROM integration_api_clients WHERE id='%s'".formatted(clientId)))
                .isEqualTo("revoked");

        assertThatThrownBy(() -> act("P13-09", "register-api-client", null,
                Map.of("clientSecret", "must-never-be-transported"), null,
                "Reject raw client secret material before authorization or transport.",
                "m13:reject-secret:lifecycle-0001", assurance))
                .isInstanceOf(IntegrationException.class)
                .hasMessageContaining("prohibited");
    }

    @Test
    void enforcesWebhookReplayProfileProvenanceAndSuccessorOnlyDeliveryReplay() throws Exception {
        var assurance = clock.instant();
        var mappingId = createAndActivateFhirMapping(
                "patient-r4-exchange", "m13:create-map:evidence-0001",
                "m13:activate-map:evidence-0001", assurance);
        var fhirConnection = createAndValidateConnection(
                "P13-02", "fhir-exchange", mappingId,
                "m13:create-fhir:evidence-0001", "m13:validate-fhir:evidence-0001", assurance);
        var webhookConnection = createAndValidateConnection(
                "P13-08", "webhook-payment-events", null,
                "m13:create-hook:evidence-0001", "m13:validate-hook:evidence-0001", assurance);

        var receiptId = UuidV7Generator.randomUuid();
        executeAsApp(
                "integration.webhook.receive",
                "Record a signature-verified webhook receipt without its payload.",
                """
                INSERT INTO inbound_webhook_receipts(
                    id,organization_id,connection_id,signed_at,received_at,signature_scheme,
                    signature_key_version,signature_digest,nonce_digest,idempotency_digest,
                    payload_digest,content_type,payload_byte_count,signature_verified,status,created_by)
                VALUES ('%s','%s','%s',clock_timestamp(),clock_timestamp(),'hmac_sha256','key-v1',
                        '%s','%s','%s','%s','application/json',128,true,'accepted','%s')
                """.formatted(
                        receiptId, ORGANIZATION_ID, webhookConnection, "4".repeat(64),
                        "5".repeat(64), "6".repeat(64), "7".repeat(64), ACTOR_ID));
        assertThat(stringValue(
                        "SELECT status FROM inbound_webhook_receipts WHERE id='%s'".formatted(receiptId)))
                .isEqualTo("accepted");
        assertThatThrownBy(() -> executeAsApp(
                        "integration.webhook.receive",
                        "Attempt a duplicate webhook nonce for replay verification.",
                        """
                        INSERT INTO inbound_webhook_receipts(
                            id,organization_id,connection_id,signed_at,received_at,signature_scheme,
                            signature_key_version,signature_digest,nonce_digest,idempotency_digest,
                            payload_digest,content_type,payload_byte_count,signature_verified,status,created_by)
                        VALUES ('%s','%s','%s',clock_timestamp(),clock_timestamp(),'hmac_sha256','key-v1',
                                '%s','%s','%s','%s','application/json',128,true,'accepted','%s')
                        """.formatted(
                                UuidV7Generator.randomUuid(), ORGANIZATION_ID, webhookConnection,
                                "8".repeat(64), "5".repeat(64), "9".repeat(64),
                                "a".repeat(64), ACTOR_ID)))
                .isInstanceOf(SQLException.class);

        var exchangeId = UuidV7Generator.randomUuid();
        executeAsApp(
                "integration.fhir.exchange.record",
                "Record exact profile validation and provenance without the FHIR body.",
                """
                INSERT INTO fhir_exchange_records(
                    id,organization_id,connection_id,mapping_version_id,direction,resource_type,
                    resource_identifier_digest,resource_digest,fhir_release,profile_package,
                    profile_version,validation_status,validation_code,provenance_digest,created_by)
                VALUES ('%s','%s','%s','%s','import','Patient','%s','%s','R4',
                        'org.hl7.fhir.r4.core','4.0.1','accepted','profile_valid','%s','%s')
                """.formatted(
                        exchangeId, ORGANIZATION_ID, fhirConnection, mappingId,
                        "b".repeat(64), "c".repeat(64), "d".repeat(64), ACTOR_ID));
        assertThat(stringValue(
                        "SELECT validation_status FROM fhir_exchange_records WHERE id='%s'".formatted(exchangeId)))
                .isEqualTo("accepted");

        var sourceEvent = uuidValue("""
                SELECT id FROM outbox_events
                 WHERE aggregate_id='%s' AND event_name='m13.integration-artifact-changed.v1'
                 ORDER BY occurred_at DESC LIMIT 1
                """.formatted(webhookConnection));
        var deliveryId = UuidV7Generator.randomUuid();
        executeAsApp(
                "integration.delivery.create",
                "Create an exact outbound delivery from the transactional outbox.",
                """
                INSERT INTO integration_outbound_deliveries(
                    id,organization_id,connection_id,source_outbox_event_id,event_name,
                    schema_version,payload_digest,status,attempt_count,next_attempt_at,
                    created_at,created_by,updated_at,updated_by)
                SELECT '%s',event.organization_id,'%s',event.id,event.event_name,event.schema_version,
                       encode(sha256(convert_to(event.payload::text,'UTF8')),'hex'),
                       'queued',0,clock_timestamp(),clock_timestamp(),'%s',clock_timestamp(),'%s'
                  FROM outbox_events event WHERE event.organization_id='%s' AND event.id='%s'
                """.formatted(
                        deliveryId, webhookConnection, ACTOR_ID, ACTOR_ID,
                        ORGANIZATION_ID, sourceEvent));
        executeAsApp(
                "integration.delivery.attempt",
                "Record terminal provider failure without retaining its response body.",
                """
                INSERT INTO integration_delivery_attempts(
                    organization_id,delivery_id,attempt_sequence,attempted_at,outcome,
                    response_class,error_code,created_by)
                VALUES ('%s','%s',1,clock_timestamp(),'dead_lettered',
                        'transport_failure','provider_unavailable','%s')
                """.formatted(ORGANIZATION_ID, deliveryId, ACTOR_ID),
                """
                UPDATE integration_outbound_deliveries
                   SET status='dead_lettered',attempt_count=1,dead_lettered_at=clock_timestamp(),
                       last_error_code='provider_unavailable',lock_version=lock_version+1,
                       updated_at=clock_timestamp(),updated_by='%s'
                 WHERE organization_id='%s' AND id='%s' AND lock_version=0
                """.formatted(ACTOR_ID, ORGANIZATION_ID, deliveryId));

        act("P13-10", "authorize-replay", deliveryId, Map.of(),
                etag("P13-10", deliveryId, 1),
                "Authorize one exact successor after the provider outage is reviewed.",
                "m13:authorize-replay:evidence-0001", assurance);
        var replayId = uuidValue(
                "SELECT id FROM integration_replay_requests WHERE delivery_id='%s'".formatted(deliveryId));
        var successorId = UuidV7Generator.randomUuid();
        executeAsApp(
                "integration.delivery.replay",
                "Execute the reviewed replay as one successor delivery.",
                """
                INSERT INTO integration_outbound_deliveries(
                    id,organization_id,connection_id,source_outbox_event_id,event_name,
                    schema_version,payload_digest,predecessor_delivery_id,status,attempt_count,
                    next_attempt_at,created_at,created_by,updated_at,updated_by)
                SELECT '%s',organization_id,connection_id,source_outbox_event_id,event_name,
                       schema_version,payload_digest,id,'queued',0,clock_timestamp(),
                       clock_timestamp(),'%s',clock_timestamp(),'%s'
                  FROM integration_outbound_deliveries
                 WHERE organization_id='%s' AND id='%s'
                """.formatted(successorId, ACTOR_ID, ACTOR_ID, ORGANIZATION_ID, deliveryId),
                """
                UPDATE integration_replay_requests
                   SET status='executed',successor_delivery_id='%s',executed_at=clock_timestamp(),
                       executed_by='%s',lock_version=lock_version+1,
                       updated_at=clock_timestamp(),updated_by='%s'
                 WHERE organization_id='%s' AND id='%s' AND status='authorized'
                """.formatted(successorId, ACTOR_ID, ACTOR_ID, ORGANIZATION_ID, replayId));

        assertThat(stringValue(
                        "SELECT status FROM integration_outbound_deliveries WHERE id='%s'".formatted(deliveryId)))
                .isEqualTo("dead_lettered");
        assertThat(stringValue(
                        "SELECT status FROM integration_outbound_deliveries WHERE id='%s'".formatted(successorId)))
                .isEqualTo("queued");
        assertThat(longValue("""
                        SELECT count(*) FROM information_schema.columns
                         WHERE table_schema='public'
                           AND table_name IN ('inbound_webhook_receipts','fhir_exchange_records',
                                              'integration_api_clients')
                           AND column_name IN ('payload','body','resource_body','secret','api_key',
                                               'access_token','refresh_token','signature_value')
                        """))
                .isZero();

        var projection = integrations.screen(new IntegrationService.ReadCommand(
                ORGANIZATION_ID, ACTOR_ID, "m13-projection-correlation", "P13-10",
                null, null, null, 50, null, assurance, assurance));
        assertThat(projection.rows()).extracting(row -> row.values().get("artifact"))
                .contains("outbound_delivery", "webhook_receipt", "fhir_exchange",
                        "replay_authorization");
        assertThat(projection.rows()).allSatisfy(row ->
                assertThat(row.values().keySet())
                        .doesNotContain("payload", "body", "secret", "accessToken", "resourceBody"));
    }

    private UUID createAndActivateFhirMapping(
            String code, String createKey, String activateKey, java.time.Instant assurance)
            throws SQLException {
        act("P13-03", "create-mapping", null,
                Map.of(
                        "mappingCode", code,
                        "mappingKind", "fhir",
                        "versionNumber", "1",
                        "sourceVersion", "careos-v1",
                        "targetVersion", "partner-v1",
                        "fhirRelease", "R4",
                        "profilePackage", "org.hl7.fhir.r4.core",
                        "profileVersion", "4.0.1",
                        "terminologyVersion", "2026-09",
                        "contentDigest", "e".repeat(64)),
                null, "Create an exact version-pinned FHIR mapping for review.", createKey, assurance);
        var id = uuidValue(
                "SELECT id FROM integration_mapping_versions WHERE mapping_code='%s'".formatted(code));
        act("P13-03", "activate-mapping", id, Map.of(), etag("P13-03", id, 0),
                "Activate the reviewed exact FHIR mapping and terminology package.",
                activateKey, assurance);
        return id;
    }

    private UUID createAndValidateConnection(
            String screen,
            String code,
            UUID mappingId,
            String createKey,
            String validateKey,
            java.time.Instant assurance)
            throws SQLException {
        var fields = new java.util.LinkedHashMap<String, String>();
        fields.put("connectionCode", code);
        fields.put("displayName", code.replace('-', ' '));
        fields.put("contractVersion", "contract-v1");
        fields.put("endpointReference", code + "-endpoint");
        fields.put("securityProfileKey", "signed-contract-v1");
        fields.put("credentialReferenceDigest", "f".repeat(64));
        if (mappingId != null) fields.put("mappingVersionId", mappingId.toString());
        act(screen, "create-connection", null, Map.copyOf(fields), null,
                "Create secret-free integration configuration for controlled review.",
                createKey, assurance);
        var id = uuidValue(
                "SELECT id FROM integration_connections WHERE connection_code='%s'".formatted(code));
        act(screen, "validate-connection", id, Map.of(), etag(screen, id, 0),
                "Validate configuration completeness without enabling external transport.",
                validateKey, assurance);
        return id;
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
        integrations.act(new IntegrationService.ActionCommand(
                ORGANIZATION_ID, ACTOR_ID, "m13-" + action + "-correlation", screen, action,
                target, reason, fields, etag, idempotency, assurance, assurance));
    }

    private void executeAsApp(String operation, String reason, String... statements)
            throws SQLException {
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), APP_USER, APP_PASSWORD)) {
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) {
                setContext(connection, operation, reason);
                execute(statement, statements);
                connection.commit();
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    private void setContext(Connection connection, String operation, String reason)
            throws SQLException {
        try (var statement = connection.prepareStatement("SELECT set_config(?,?,true)")) {
            var settings = Map.of(
                    "app.current_organization_id", ORGANIZATION_ID.toString(),
                    "app.current_actor_id", ACTOR_ID.toString(),
                    "app.current_operation_key", operation,
                    "app.current_authorization_reason", reason,
                    "app.current_purpose", "integration_governance",
                    "app.current_correlation_id", "m13-direct-evidence");
            for (var entry : settings.entrySet()) {
                statement.setString(1, entry.getKey());
                statement.setString(2, entry.getValue());
                statement.executeQuery().close();
            }
        }
    }

    private static String etag(String screen, UUID targetId, long revision) {
        return "\"m13:" + screen + ":" + targetId + ":" + revision + "\"";
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

    private boolean booleanValue(String sql) throws SQLException {
        try (var connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), MIGRATOR_USER, MIGRATOR_PASSWORD);
                var statement = connection.createStatement();
                var resultSet = statement.executeQuery(sql)) {
            assertThat(resultSet.next()).isTrue();
            return resultSet.getBoolean(1);
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
