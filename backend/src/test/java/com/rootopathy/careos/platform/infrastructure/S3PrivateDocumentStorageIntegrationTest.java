package com.rootopathy.careos.platform.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rootopathy.careos.platform.application.DocumentStorageException;
import com.rootopathy.careos.platform.application.PlatformCapabilityRegistry;
import com.rootopathy.careos.platform.application.PrivateDocumentStoragePort;
import com.rootopathy.careos.platform.domain.CapabilityAvailability;
import com.rootopathy.careos.platform.domain.DocumentObjectReference;
import com.rootopathy.careos.platform.domain.DocumentQuarantineRequest;
import com.rootopathy.careos.platform.domain.PlatformCapability;
import com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext;
import io.minio.GetObjectArgs;
import io.minio.ListObjectsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.SetBucketPolicyArgs;
import io.minio.StatObjectArgs;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class S3PrivateDocumentStorageIntegrationTest {
    private static final String ACCESS_KEY = "careos-integration";
    private static final String SECRET_KEY = "careos-integration-secret-never-production";
    private static final UUID ORGANIZATION_ONE =
            UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID ORGANIZATION_TWO =
            UUID.fromString("10000000-0000-0000-0000-000000000002");
    private static final UUID ACTOR_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");

    /** Synthetic, isolated compatibility target; never a production storage recommendation. */
    @Container
    private static final GenericContainer<?> MINIO = new GenericContainer<>(
                    DockerImageName.parse(
                            "quay.io/minio/minio@sha256:14cea493d9a34af32f524e538b8346cf79f3321eff8e708c1e2960462bd8936e"))
            .withEnv("MINIO_ROOT_USER", ACCESS_KEY)
            .withEnv("MINIO_ROOT_PASSWORD", SECRET_KEY)
            .withCommand("server", "/data", "--console-address", ":9001")
            .withExposedPorts(9000)
            .waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000).forStatusCode(200));

    @Test
    void storesOnlyVerifiedContentUnderATenantDerivedPrivateKey() throws Exception {
        var bucket = uniqueBucket();
        var content = "synthetic credential evidence".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var request = request(content, "application/pdf");
        var context = context(ORGANIZATION_ONE, "storage-private-1");

        try (var adapter = adapter(bucket, 1024)) {
            var reference = adapter.quarantine(context, request, new ByteArrayInputStream(content));
            assertThat(reference.organizationId()).isEqualTo(ORGANIZATION_ONE);
            assertThat(adapter.status().availability()).isEqualTo(CapabilityAvailability.AVAILABLE);

            var key = S3PrivateDocumentStorageAdapter.objectKey(reference);
            assertThat(key)
                    .isEqualTo("organizations/%s/documents/%s/versions/%s/quarantine"
                            .formatted(ORGANIZATION_ONE, request.documentId(), request.objectVersionId()))
                    .doesNotContain("credential evidence");

            try (var verifier = client()) {
                var stat = verifier.statObject(
                        StatObjectArgs.builder().bucket(bucket).object(key).build());
                assertThat(stat.size()).isEqualTo(content.length);
                assertThat(stat.contentType()).isEqualTo("application/pdf");
                assertThat(stat.userMetadata().get("careos-sha256"))
                        .containsExactly(sha256(content));
                assertThat(stat.userMetadata().get("careos-state"))
                        .containsExactly("quarantine");
                try (var stored = verifier.getObject(
                        GetObjectArgs.builder().bucket(bucket).object(key).build())) {
                    assertThat(stored.readAllBytes()).isEqualTo(content);
                }
            }

            try (var quarantined = adapter.open(context, reference)) {
                assertThat(quarantined.expectedBytes()).isEqualTo(content.length);
                assertThat(quarantined.expectedSha256()).isEqualTo(sha256(content));
                assertThat(quarantined.stream().readAllBytes()).isEqualTo(content);
            }
            assertStorageError(
                    "document-quarantine-tenant-mismatch",
                    () -> adapter.open(
                            context(ORGANIZATION_TWO, "storage-private-cross-tenant"), reference));

            var invalidContent = new byte[] {4, 5, 6};
            var invalidReference = new DocumentObjectReference(
                    ORGANIZATION_ONE, UUID.randomUUID(), UUID.randomUUID());
            try (var administrator = client()) {
                administrator.putObject(PutObjectArgs.builder()
                        .bucket(bucket)
                        .object(S3PrivateDocumentStorageAdapter.objectKey(invalidReference))
                        .stream(new ByteArrayInputStream(invalidContent), (long) invalidContent.length, -1L)
                        .userMetadata(java.util.Map.of(
                                "careos-state", "promoted",
                                "careos-sha256", sha256(invalidContent)))
                        .build());
            }
            assertStorageError(
                    "document-quarantine-metadata-invalid",
                    () -> adapter.open(context, invalidReference));

            var anonymousRequest = HttpRequest.newBuilder(
                            URI.create(endpoint() + "/" + bucket + "/" + key))
                    .GET()
                    .build();
            var anonymousResponse = HttpClient.newHttpClient()
                    .send(anonymousRequest, HttpResponse.BodyHandlers.discarding());
            assertThat(anonymousResponse.statusCode()).isIn(401, 403);
        }
    }

    @Test
    void removesUploadsWhoseLengthOrDigestDoesNotMatch() throws Exception {
        var bucket = uniqueBucket();
        try (var adapter = adapter(bucket, 1024)) {
            var shortContent = new byte[] {1, 2, 3};
            var shortRequest = new DocumentQuarantineRequest(
                    UUID.randomUUID(), UUID.randomUUID(), 4, "application/octet-stream", sha256(shortContent));
            assertStorageError(
                    "document-content-length-mismatch",
                    () -> adapter.quarantine(
                            context(ORGANIZATION_ONE, "storage-length-short"),
                            shortRequest,
                            new ByteArrayInputStream(shortContent)));

            var longContent = new byte[] {1, 2, 3, 4};
            var declaredContent = new byte[] {1, 2, 3};
            var longRequest = new DocumentQuarantineRequest(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    declaredContent.length,
                    "application/octet-stream",
                    sha256(declaredContent));
            assertStorageError(
                    "document-content-length-mismatch",
                    () -> adapter.quarantine(
                            context(ORGANIZATION_ONE, "storage-length-long"),
                            longRequest,
                            new ByteArrayInputStream(longContent)));

            var digestContent = new byte[] {5, 6, 7};
            var digestRequest = new DocumentQuarantineRequest(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    digestContent.length,
                    "application/octet-stream",
                    "0".repeat(64));
            assertStorageError(
                    "document-content-digest-mismatch",
                    () -> adapter.quarantine(
                            context(ORGANIZATION_ONE, "storage-digest"),
                            digestRequest,
                            new ByteArrayInputStream(digestContent)));

            try (var verifier = client()) {
                assertThat(objectCount(verifier, bucket)).isZero();
            }
        }
    }

    @Test
    void replaysOnlyMatchingObjectsWithoutReadingTheRetryBody() throws Exception {
        var bucket = uniqueBucket();
        var content = new byte[] {9, 8, 7, 6};
        var request = request(content, "application/octet-stream");
        var firstContext = context(ORGANIZATION_ONE, "storage-retry-1");
        var retryBodyRead = new AtomicBoolean();
        var retryBody = new InputStream() {
            @Override
            public int read() throws IOException {
                retryBodyRead.set(true);
                throw new IOException("retry body must not be consumed");
            }
        };

        try (var adapter = adapter(bucket, 1024)) {
            var first = adapter.quarantine(firstContext, request, new ByteArrayInputStream(content));
            var replayed = adapter.quarantine(firstContext, request, retryBody);
            assertThat(replayed).isEqualTo(first);
            assertThat(retryBodyRead).isFalse();

            var conflictingRequest = new DocumentQuarantineRequest(
                    request.documentId(),
                    request.objectVersionId(),
                    request.declaredBytes(),
                    "text/plain",
                    request.sha256());
            assertStorageError(
                    "document-object-conflict",
                    () -> adapter.quarantine(firstContext, conflictingRequest, retryBody));
            assertThat(retryBodyRead).isFalse();

            var secondTenant = adapter.quarantine(
                    context(ORGANIZATION_TWO, "storage-retry-2"),
                    request,
                    new ByteArrayInputStream(content));
            assertThat(secondTenant.organizationId()).isEqualTo(ORGANIZATION_TWO);
            try (var verifier = client()) {
                assertThat(objectCount(verifier, bucket)).isEqualTo(2);
            }
        }
    }

    @Test
    void rejectsOversizeContentBeforeReadingAndRejectsPublicBucketPolicies() throws Exception {
        var oversizeBucket = uniqueBucket();
        var bodyRead = new AtomicBoolean();
        var body = new InputStream() {
            @Override
            public int read() {
                bodyRead.set(true);
                return -1;
            }
        };
        try (var adapter = adapter(oversizeBucket, 3)) {
            var request = new DocumentQuarantineRequest(
                    UUID.randomUUID(), UUID.randomUUID(), 4, "application/pdf", "0".repeat(64));
            assertStorageError(
                    "document-upload-too-large",
                    () -> adapter.quarantine(
                            context(ORGANIZATION_ONE, "storage-oversize"), request, body));
            assertThat(bodyRead).isFalse();
        }

        var publicBucket = uniqueBucket();
        try (var administrator = client()) {
            administrator.makeBucket(
                    MakeBucketArgs.builder().bucket(publicBucket).build());
            administrator.setBucketPolicy(SetBucketPolicyArgs.builder()
                    .bucket(publicBucket)
                    .config("""
                            {
                              "Version":"2012-10-17",
                              "Statement":[{
                                "Effect":"Allow",
                                "Principal":{"AWS":["*"]},
                                "Action":["s3:GetObject"],
                                "Resource":["arn:aws:s3:::%s/*"]
                              }]
                            }
                            """.formatted(publicBucket))
                    .build());
        }
        var publicAdapter = new S3PrivateDocumentStorageAdapter(client(), publicBucket, 1024, false);
        try {
            assertThatIllegalStateException()
                    .isThrownBy(publicAdapter::initialize)
                    .withMessageContaining("must not have a bucket policy");
            assertThat(publicAdapter.status().availability())
                    .isEqualTo(CapabilityAvailability.UNAVAILABLE);
        } finally {
            publicAdapter.close();
        }
    }

    @Test
    void springActivationReplacesOnlyTheQuarantineFailClosedAdapter() {
        var bucket = uniqueBucket();
        new ApplicationContextRunner()
                .withUserConfiguration(
                        PlatformCapabilityConfiguration.class,
                        S3DocumentStorageConfiguration.class)
                .withPropertyValues(
                        "careos.storage.s3.enabled=true",
                        "careos.storage.s3.endpoint=" + endpoint(),
                        "careos.storage.s3.access-key=" + ACCESS_KEY,
                        "careos.storage.s3.secret-key=" + SECRET_KEY,
                        "careos.storage.s3.quarantine-bucket=" + bucket,
                        "careos.storage.s3.maximum-upload-bytes=1024",
                        "careos.storage.s3.allow-http=true",
                        "careos.storage.s3.create-bucket-if-missing=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(PrivateDocumentStoragePort.class);
                    assertThat(context).hasSingleBean(S3PrivateDocumentStorageAdapter.class);
                    var registry = context.getBean(PlatformCapabilityRegistry.class);
                    assertThat(registry.status(PlatformCapability.PRIVATE_DOCUMENT_QUARANTINE)
                                    .availability())
                            .isEqualTo(CapabilityAvailability.AVAILABLE);
                    assertThat(registry.statuses())
                            .filteredOn(status -> status.availability() == CapabilityAvailability.UNAVAILABLE)
                            .hasSize(8);
                });
    }

    @Test
    void activationPropertiesRequireHttpsUnlessTheLocalOverrideIsExplicit() {
        var properties = new S3DocumentStorageProperties();
        properties.setEnabled(true);
        properties.setEndpoint(URI.create(endpoint()));
        properties.setAccessKey(ACCESS_KEY);
        properties.setSecretKey(SECRET_KEY);
        properties.setQuarantineBucket(uniqueBucket());

        assertThatIllegalStateException()
                .isThrownBy(properties::validateForActivation)
                .withMessageContaining("HTTPS");
        properties.setAllowHttp(true);
        properties.validateForActivation();
        assertThat(properties.toString())
                .doesNotContain(ACCESS_KEY)
                .doesNotContain(SECRET_KEY)
                .contains("<redacted>");
    }

    private static S3PrivateDocumentStorageAdapter adapter(String bucket, long maximumBytes) {
        var adapter = new S3PrivateDocumentStorageAdapter(client(), bucket, maximumBytes, true);
        adapter.initialize();
        return adapter;
    }

    private static MinioClient client() {
        return MinioClient.builder()
                .endpoint(endpoint())
                .credentials(ACCESS_KEY, SECRET_KEY)
                .build();
    }

    private static String endpoint() {
        return "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000);
    }

    private static AuthorizedTenantContext context(UUID organizationId, String correlationId) {
        return new AuthorizedTenantContext(
                organizationId, ACTOR_ID, "document.quarantine", correlationId);
    }

    private static DocumentQuarantineRequest request(byte[] content, String mediaType) {
        return new DocumentQuarantineRequest(
                UUID.randomUUID(), UUID.randomUUID(), content.length, mediaType, sha256(content));
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static int objectCount(MinioClient client, String bucket) throws Exception {
        var count = 0;
        for (var result : client.listObjects(
                ListObjectsArgs.builder().bucket(bucket).recursive(true).build())) {
            result.get();
            count++;
        }
        return count;
    }

    private static String uniqueBucket() {
        return "careos-q-" + UUID.randomUUID().toString().replace("-", "");
    }

    private static void assertStorageError(String errorCode, org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOf(DocumentStorageException.class)
                .extracting("errorCode")
                .isEqualTo(errorCode);
    }
}
