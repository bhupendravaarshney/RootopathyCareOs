package com.rootopathy.careos.document.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rootopathy.careos.document.application.DocumentException;
import com.rootopathy.careos.document.application.DocumentService;
import com.rootopathy.careos.platform.application.DocumentAccessOperations;
import com.rootopathy.careos.platform.application.DocumentEvidenceOperations;
import com.rootopathy.careos.platform.application.DocumentPromotionOperations;
import com.rootopathy.careos.platform.application.DocumentSecurityOperations;
import com.rootopathy.careos.platform.domain.DocumentAccessAuthorization;
import com.rootopathy.careos.platform.domain.DocumentAccessPolicy;
import com.rootopathy.careos.platform.domain.DocumentObjectReference;
import com.rootopathy.careos.platform.domain.DocumentPromotionPolicy;
import com.rootopathy.careos.platform.domain.DocumentQuarantineRequest;
import com.rootopathy.careos.platform.domain.MalwareScanResult;
import com.rootopathy.careos.platform.domain.MalwareScanVerdict;
import com.rootopathy.careos.platform.domain.SignedDocumentAccess;
import com.rootopathy.careos.shared.domain.UuidV7Generator;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(classes = {com.rootopathy.careos.CareOsApplication.class, DocumentLifecycleIntegrationTest.TestProviders.class})
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DocumentLifecycleIntegrationTest {
    private static final String MIGRATOR_USER = "careos_migrator";
    private static final String MIGRATOR_PASSWORD = "careos-migrator-test-only";
    private static final String APP_USER = "careos_app";
    private static final String APP_PASSWORD = "careos-app-test-only";
    private static final UUID ORGANIZATION_ID =
            UUID.fromString("01900000-0000-7000-8000-000000000001");
    private static final UUID FACILITY_ID =
            UUID.fromString("01900000-0000-7000-8000-000000000101");
    private static final UUID ACTOR_ID =
            UUID.fromString("019e0000-0000-7000-8000-000000000101");
    private static final UUID CHECKER_ID =
            UUID.fromString("019e0000-0000-7000-8000-000000000102");
    private static final UUID MEMBERSHIP_ID =
            UUID.fromString("019e0000-0000-7000-8000-000000000103");
    private static final UUID WORKFORCE_MEMBER_ID =
            UUID.fromString("019e0000-0000-7000-8000-000000000201");
    private static final UUID PRACTITIONER_ID =
            UUID.fromString("019e0000-0000-7000-8000-000000000202");
    private static final UUID PATIENT_ID =
            UUID.fromString("019e0000-0000-7000-8000-000000000301");
    private static final String DIGEST = "d".repeat(64);

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
    private DocumentService documents;

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
                    VALUES ('%s','m7.practitioner@example.test','M7 Practitioner','active'),
                           ('%s','m7.checker@example.test','M7 Checker','active')
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
                    INSERT INTO person_profiles
                        (id,legal_given_name,legal_family_name,display_name,created_by,updated_by)
                    VALUES ('019e0000-0000-7000-8000-000000000203','M7','Practitioner',
                            'M7 Practitioner','%s','%s')
                    """.formatted(ACTOR_ID, ACTOR_ID),
                    """
                    INSERT INTO organization_person_links
                        (id,organization_id,person_id,display_label,relationship_status,
                         effective_from,status,created_by,updated_by)
                    VALUES ('019e0000-0000-7000-8000-000000000204','%s',
                            '019e0000-0000-7000-8000-000000000203','M7 Practitioner','active',
                            clock_timestamp()-interval '30 days','active','%s','%s')
                    """.formatted(ORGANIZATION_ID, ACTOR_ID, ACTOR_ID),
                    """
                    INSERT INTO workforce_members
                        (id,organization_id,organization_person_link_id,pathway,member_number,
                         lifecycle_state,activated_at,status,created_by,updated_by)
                    VALUES ('%s','%s','019e0000-0000-7000-8000-000000000204','clinical',
                            'M7CLINICIAN001','active',clock_timestamp()-interval '30 days',
                            'active','%s','%s')
                    """.formatted(WORKFORCE_MEMBER_ID, ORGANIZATION_ID, ACTOR_ID, ACTOR_ID),
                    """
                    INSERT INTO workforce_registry_definitions
                        (id,organization_id,registry_key,category,display_name,value_schema,
                         review_cadence_days,lifecycle_state,status,created_by,updated_by)
                    VALUES ('019e0000-0000-7000-8000-000000000205','%s','m7_test_profession',
                            'profession','M7 professions','{}',365,'active','active','%s','%s')
                    """.formatted(ORGANIZATION_ID, ACTOR_ID, ACTOR_ID),
                    """
                    INSERT INTO workforce_registry_entries
                        (id,organization_id,registry_definition_id,entry_key,code,display_label,
                         lifecycle_state,status,created_by,updated_by)
                    VALUES ('019e0000-0000-7000-8000-000000000206','%s',
                            '019e0000-0000-7000-8000-000000000205','test_practitioner',
                            'TEST_PRACTITIONER','Test practitioner','active','active','%s','%s')
                    """.formatted(ORGANIZATION_ID, ACTOR_ID, ACTOR_ID),
                    """
                    INSERT INTO workforce_registry_versions
                        (id,organization_id,registry_entry_id,version_number,version_fields,
                         version_digest,effective_from,maker_id,checker_id,decision_code,
                         activated_at,lifecycle_state,status,created_by,updated_by)
                    VALUES ('019e0000-0000-7000-8000-000000000207','%s',
                            '019e0000-0000-7000-8000-000000000206',1,'{}','%s',
                            clock_timestamp()-interval '30 days','%s','%s','approved_for_test',
                            clock_timestamp()-interval '30 days','active','active','%s','%s')
                    """.formatted(ORGANIZATION_ID, DIGEST, ACTOR_ID, CHECKER_ID, ACTOR_ID, ACTOR_ID),
                    """
                    INSERT INTO practitioner_profiles
                        (id,organization_id,workforce_member_id,profession_entry_id,
                         profession_version_id,regulated,clinical_title,effective_from,
                         lifecycle_state,status,created_by,updated_by)
                    VALUES ('%s','%s','%s','019e0000-0000-7000-8000-000000000206',
                            '019e0000-0000-7000-8000-000000000207',true,'Test clinician',
                            clock_timestamp()-interval '30 days','active','active','%s','%s')
                    """.formatted(PRACTITIONER_ID, ORGANIZATION_ID, WORKFORCE_MEMBER_ID, ACTOR_ID, ACTOR_ID),
                    """
                    INSERT INTO access_assignment_scopes
                        (id,organization_id,access_assignment_id,workforce_member_id,
                         facility_id,grant_request_id,effective_from,status,created_by,updated_by)
                    VALUES ('019e0000-0000-7000-8000-000000000208','%s','%s','%s','%s',
                            '019e0000-0000-7000-8000-000000000209',
                            clock_timestamp()-interval '30 days','active','%s','%s')
                    """.formatted(
                            ORGANIZATION_ID,
                            MEMBERSHIP_ID,
                            WORKFORCE_MEMBER_ID,
                            FACILITY_ID,
                            ACTOR_ID,
                            ACTOR_ID),
                    """
                    INSERT INTO patient_profiles
                        (id,organization_id,patient_number,lifecycle_state,official_given_name,
                         official_family_name,name_state,provenance_source,verification_state,
                         created_by,updated_by)
                    VALUES ('%s','%s','M7PATIENT001','active','Document','Patient','provided',
                            'test_fixture','evidence_checked','%s','%s')
                    """.formatted(PATIENT_ID, ORGANIZATION_ID, ACTOR_ID, ACTOR_ID));
        }
    }

    @Test
    void completesVersionedDocumentResultReviewAndIntentWorkflow() throws Exception {
        var now = clock.instant();
        var content = "%PDF-1.7\nCareOS M7 synthetic result\n".getBytes(StandardCharsets.UTF_8);
        var digest = sha256(content);
        documents.upload(new DocumentService.UploadRequest(
                ORGANIZATION_ID,
                ACTOR_ID,
                "m7-upload-correlation",
                PATIENT_ID,
                null,
                null,
                null,
                "Synthetic laboratory report",
                "diagnostic_report",
                "test_laboratory",
                "Upload exact synthetic laboratory evidence",
                "synthetic-result.pdf",
                "application/pdf",
                digest,
                content,
                null,
                "m7-upload-document-0001",
                now,
                now));
        var documentId = uuidValue("SELECT id FROM documents");
        var versionId = uuidValue("SELECT id FROM document_versions");
        assertThat(stringValue("SELECT status FROM document_versions WHERE id='%s'".formatted(versionId)))
                .isEqualTo("quarantined");

        act(
                "P7-04",
                "classify-document",
                documentId,
                documentId,
                versionId,
                null,
                Map.of(
                        "categoryKey", "laboratory_result",
                        "confidentialityKey", "restricted",
                        "retentionClassKey", "clinical_record",
                        "sourceKey", "authorized_clinician",
                        "methodKey", "manual_review"),
                "Classify the synthetic clinical result",
                "\"m7:P7-04:%s:1\"".formatted(documentId),
                "m7-classify-document-0001",
                now);

        act(
                "P7-05",
                "scan-document",
                versionId,
                documentId,
                versionId,
                null,
                Map.of(),
                null,
                "\"m7:P7-05:%s:0\"".formatted(versionId),
                "m7-scan-document-0001",
                now);
        assertThat(stringValue("SELECT status FROM document_versions WHERE id='%s'".formatted(versionId)))
                .isEqualTo("clean");
        assertThat(longValue("SELECT count(*) FROM document_promotion_evidence"))
                .isEqualTo(1);

        var reportFields = new java.util.HashMap<String, String>();
        reportFields.put("reportType", "laboratory");
        reportFields.put("reportStatus", "final");
        reportFields.put("authorPractitionerId", PRACTITIONER_ID.toString());
        reportFields.put("sourceKey", "test_laboratory");
        reportFields.put("sourceIdentifier", "LAB-SYNTHETIC-001");
        reportFields.put("issuedAt", now.minusSeconds(60).toString());
        reportFields.put("summary", "Synthetic potassium result requires immediate clinical review.");
        reportFields.put("interpretationStatus", "provisional");
        reportFields.put("testCode", "K");
        reportFields.put("testDisplay", "Potassium");
        reportFields.put("value", "6.8");
        reportFields.put("unit", "mmol/L");
        reportFields.put("referenceRange", "3.5-5.1 mmol/L");
        reportFields.put("abnormalFlag", "critical");
        reportFields.put("methodKey", "ion_selective_electrode");
        reportFields.put("observedAt", now.minusSeconds(120).toString());
        reportFields.put("ownerPractitionerId", PRACTITIONER_ID.toString());
        reportFields.put("slaPolicyVersion", "synthetic_test_sla_v1");
        reportFields.put("acknowledgementDueAt", now.plusSeconds(3600).toString());
        reportFields.put("flagSummary", "Critical synthetic potassium value.");
        act(
                "P7-08",
                "record-diagnostic-report",
                documentId,
                documentId,
                versionId,
                null,
                Map.copyOf(reportFields),
                "Record exact synthetic result provenance",
                "\"m7:P7-08:%s:2\"".formatted(documentId),
                "m7-record-result-0001",
                now);
        var reportId = uuidValue("SELECT id FROM diagnostic_reports");
        var flagId = uuidValue("SELECT id FROM result_flags");
        assertThat(stringValue("SELECT status FROM result_flags WHERE id='%s'".formatted(flagId)))
                .isEqualTo("open");

        assertThatThrownBy(() -> act(
                        "P7-09",
                        "resolve-result",
                        flagId,
                        documentId,
                        versionId,
                        reportId,
                        Map.of("reviewerPractitionerId", PRACTITIONER_ID.toString()),
                        "Resolve before acknowledgement is unsafe",
                        "\"m7:P7-09:%s:0\"".formatted(flagId),
                        "m7-resolve-result-unsafe",
                        now))
                .isInstanceOf(DocumentException.class);

        act(
                "P7-09",
                "acknowledge-result",
                flagId,
                documentId,
                versionId,
                reportId,
                Map.of("reviewerPractitionerId", PRACTITIONER_ID.toString()),
                "Acknowledge the critical synthetic result",
                "\"m7:P7-09:%s:0\"".formatted(flagId),
                "m7-ack-result-0001",
                now);
        act(
                "P7-09",
                "escalate-result",
                flagId,
                documentId,
                versionId,
                reportId,
                Map.of(
                        "ownerPractitionerId", PRACTITIONER_ID.toString(),
                        "escalationLevel", "clinical_owner",
                        "channelKey", "manual_clinical_handoff",
                        "dueAt", now.plusSeconds(1800).toString()),
                "Escalate pending clinician resolution",
                null,
                "m7-escalate-result-0001",
                now);
        act(
                "P7-09",
                "resolve-result",
                flagId,
                documentId,
                versionId,
                reportId,
                Map.of("reviewerPractitionerId", PRACTITIONER_ID.toString()),
                "Resolve after attributed clinical action",
                "\"m7:P7-09:%s:1\"".formatted(flagId),
                "m7-resolve-result-0001",
                now);
        assertThat(stringValue("SELECT status FROM result_flags WHERE id='%s'".formatted(flagId)))
                .isEqualTo("resolved");

        act(
                "P7-11",
                "record-access-intent",
                documentId,
                documentId,
                versionId,
                reportId,
                Map.of(
                        "intentType", "export",
                        "purposeKey", "patient_request",
                        "requestedFormat", "pdf"),
                "Record export intent without delivery claim",
                null,
                "m7-export-intent-0001",
                now);
        assertThat(stringValue("SELECT status FROM document_access_intents WHERE intent_type='export'"))
                .isEqualTo("recorded");

        var access = documents.createAccess(new DocumentService.AccessCommand(
                ORGANIZATION_ID,
                ACTOR_ID,
                "m7-access-correlation",
                documentId,
                versionId,
                "clinical_care",
                "Review the clean synthetic clinical document",
                "m7-access-document-0001",
                now,
                now));
        assertThat(access.response().statusCode()).isEqualTo(200);
        assertThat(stringValue("SELECT status FROM document_access_intents WHERE intent_type='view'"))
                .isEqualTo("granted");
        assertThat(longValue(
                        "SELECT count(*) FROM document_access_intents WHERE to_jsonb(document_access_intents)::text LIKE '%%https://%%'"))
                .isZero();

        documents.upload(new DocumentService.UploadRequest(
                ORGANIZATION_ID,
                ACTOR_ID,
                "m7-replacement-correlation",
                PATIENT_ID,
                null,
                null,
                documentId,
                "Synthetic laboratory report",
                "diagnostic_report",
                "test_laboratory",
                "Replace the synthetic report with a corrected file",
                "synthetic-result-v2.pdf",
                "application/pdf",
                digest,
                content,
                "\"m7:P7-03:%s:2\"".formatted(documentId),
                "m7-upload-replacement-0001",
                now,
                now));
        assertThat(longValue("SELECT count(*) FROM document_versions WHERE document_id='%s'".formatted(documentId)))
                .isEqualTo(2);
        assertThat(stringValue(
                        "SELECT status FROM document_versions WHERE document_id='%s' ORDER BY version_number LIMIT 1"
                                .formatted(documentId)))
                .isEqualTo("clean");
        assertThat(longValue("SELECT count(*) FROM audit_events WHERE event_name LIKE 'document.%%' OR event_name LIKE 'result.%%' OR event_name='diagnostic.report.recorded'"))
                .isGreaterThanOrEqualTo(8);
        assertThat(longValue("SELECT count(*) FROM outbox_events WHERE event_name LIKE 'm7.%%'"))
                .isGreaterThanOrEqualTo(5);
    }

    private void act(
            String screen,
            String action,
            UUID target,
            UUID documentId,
            UUID versionId,
            UUID reportId,
            Map<String, String> fields,
            String reason,
            String etag,
            String idempotency,
            Instant assurance) {
        documents.act(new DocumentService.ActionCommand(
                ORGANIZATION_ID,
                ACTOR_ID,
                "m7-" + action + "-correlation",
                screen,
                action,
                target,
                PATIENT_ID,
                documentId,
                versionId,
                reportId,
                reason,
                fields,
                etag,
                idempotency,
                assurance,
                assurance));
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

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestProviders {
        @Bean
        @Primary
        DocumentSecurityOperations m7DocumentSecurity(DocumentEvidenceOperations evidence, Clock clock) {
            return new DocumentSecurityOperations() {
                @Override
                public com.rootopathy.careos.platform.domain.DocumentQuarantineEvidence quarantine(
                        com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext context,
                        DocumentQuarantineRequest request,
                        InputStream content) {
                    var reference = new DocumentObjectReference(
                            context.organizationId(), request.documentId(), request.objectVersionId());
                    return evidence.recordQuarantine(context, request, reference);
                }

                @Override
                public com.rootopathy.careos.platform.domain.DocumentScanAttestation scan(
                        com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext context,
                        DocumentObjectReference document) {
                    var quarantine = evidence.findQuarantine(context, document).orElseThrow();
                    return evidence.recordScan(
                            context,
                            new MalwareScanResult(
                                    document,
                                    MalwareScanVerdict.CLEAN,
                                    "m7.test.scanner",
                                    "synthetic-1",
                                    quarantine.sha256(),
                                    clock.instant()));
                }
            };
        }

        @Bean
        @Primary
        DocumentPromotionOperations m7DocumentPromotion(DocumentEvidenceOperations evidence) {
            var policy = new DocumentPromotionPolicy(
                    "m7.test.promotion",
                    Set.of("m7.test.scanner"),
                    Duration.ofDays(1),
                    Duration.ofSeconds(5));
            return (context, document) -> evidence.recordPromotion(
                    context,
                    evidence.findLatestScan(context, document).orElseThrow(),
                    policy);
        }

        @Bean
        @Primary
        DocumentAccessOperations m7DocumentAccess(DocumentEvidenceOperations evidence, Clock clock) {
            var policy = new DocumentAccessPolicy(
                    "m7.test.access",
                    Set.of("clinical_care", "result_review", "patient_request", "security_investigation"),
                    Duration.ofSeconds(60),
                    Duration.ofSeconds(300),
                    Duration.ofSeconds(5));
            return new DocumentAccessOperations() {
                @Override
                public SignedDocumentAccess createReadAccess(
                        com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext context,
                        DocumentObjectReference document,
                        Duration requestedTtl) {
                    var promotion = evidence.findPromotion(context, document).orElseThrow();
                    var authorization = new DocumentAccessAuthorization(
                            UuidV7Generator.randomUuid(),
                            promotion,
                            policy.policyKey(),
                            policy.acceptedPurposes(),
                            requestedTtl,
                            policy.maximumTtl(),
                            policy.maximumAuthorizationAge(),
                            policy.maximumFutureSkew(),
                            context.purpose(),
                            clock.instant());
                    evidence.recordAccessGrant(context, authorization);
                    return signed(authorization.accessGrantId(), document, authorization.expiresAt());
                }

                @Override
                public SignedDocumentAccess reopenReadAccess(
                        com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext context,
                        DocumentObjectReference document,
                        UUID accessGrantId) {
                    var grant = evidence.findAccessGrant(context, document, accessGrantId).orElseThrow();
                    return signed(accessGrantId, document, grant.expiresAt());
                }

                private SignedDocumentAccess signed(
                        UUID grantId, DocumentObjectReference document, Instant expiresAt) {
                    return new SignedDocumentAccess(
                            grantId,
                            document,
                            URI.create("https://documents.example.test/object?signature=synthetic"),
                            expiresAt);
                }
            };
        }
    }
}
