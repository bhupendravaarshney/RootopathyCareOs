package com.rootopathy.careos.platform.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rootopathy.careos.platform.application.DocumentStorageException;
import com.rootopathy.careos.platform.application.DocumentAccessOperations;
import com.rootopathy.careos.platform.application.DocumentEvidenceOperations;
import com.rootopathy.careos.platform.application.DocumentPromotionOperations;
import com.rootopathy.careos.platform.application.DocumentPromotionPort;
import com.rootopathy.careos.platform.application.DocumentRetentionOperations;
import com.rootopathy.careos.platform.application.DocumentRetentionPort;
import com.rootopathy.careos.platform.application.PlatformCapabilityRegistry;
import com.rootopathy.careos.platform.application.PrivateDocumentStoragePort;
import com.rootopathy.careos.platform.application.SignedDocumentAccessPort;
import com.rootopathy.careos.platform.domain.CapabilityAvailability;
import com.rootopathy.careos.platform.domain.DocumentAccessAuthorization;
import com.rootopathy.careos.platform.domain.DocumentAccessPolicy;
import com.rootopathy.careos.platform.domain.DocumentObjectReference;
import com.rootopathy.careos.platform.domain.DocumentPromotionAuthorization;
import com.rootopathy.careos.platform.domain.DocumentPromotionEvidence;
import com.rootopathy.careos.platform.domain.DocumentPromotionPolicy;
import com.rootopathy.careos.platform.domain.DocumentQuarantineRequest;
import com.rootopathy.careos.platform.domain.DocumentRetentionAuthorization;
import com.rootopathy.careos.platform.domain.DocumentRetentionPolicy;
import com.rootopathy.careos.platform.domain.DocumentScanAttestation;
import com.rootopathy.careos.platform.domain.MalwareScanResult;
import com.rootopathy.careos.platform.domain.MalwareScanVerdict;
import com.rootopathy.careos.platform.domain.PlatformCapability;
import com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext;
import io.minio.GetObjectArgs;
import io.minio.GetObjectRetentionArgs;
import io.minio.IsObjectLegalHoldEnabledArgs;
import io.minio.ListObjectsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.SetBucketPolicyArgs;
import io.minio.StatObjectArgs;
import io.minio.messages.RetentionMode;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.Clock;
import java.util.Set;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
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
    void promotesOnlyTheExactQuarantinedObjectIntoASeparatePrivateCleanBucket()
            throws Exception {
        var quarantineBucket = uniqueBucket();
        var cleanBucket = uniqueCleanBucket();
        var content = "synthetic clean document".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var request = request(content, "application/pdf");
        var context = context(ORGANIZATION_ONE, "promotion-clean-1");

        try (var quarantine = adapter(quarantineBucket, 1024);
                var promotion = promotionAdapter(quarantineBucket, cleanBucket, 1024)) {
            var reference = quarantine.quarantine(
                    context, request, new ByteArrayInputStream(content));
            var authorization = promotionAuthorization(reference, request.sha256());

            assertThat(promotion.promote(context, authorization)).isEqualTo(reference);
            assertThat(promotion.promote(context, authorization)).isEqualTo(reference);
            assertThat(promotion.status().availability()).isEqualTo(CapabilityAvailability.AVAILABLE);

            var cleanKey = S3DocumentPromotionAdapter.objectKey(reference);
            try (var verifier = client()) {
                assertThat(objectCount(verifier, quarantineBucket)).isEqualTo(1);
                assertThat(objectCount(verifier, cleanBucket)).isEqualTo(1);
                var clean = verifier.statObject(StatObjectArgs.builder()
                        .bucket(cleanBucket)
                        .object(cleanKey)
                        .build());
                assertThat(clean.contentType()).isEqualTo("application/pdf");
                assertThat(clean.userMetadata().get("careos-state")).containsExactly("clean");
                assertThat(clean.userMetadata().get("careos-sha256"))
                        .containsExactly(request.sha256());
                try (var stored = verifier.getObject(GetObjectArgs.builder()
                        .bucket(cleanBucket)
                        .object(cleanKey)
                        .build())) {
                    assertThat(stored.readAllBytes()).isEqualTo(content);
                }
            }

            var anonymousResponse = HttpClient.newHttpClient()
                    .send(
                            HttpRequest.newBuilder(URI.create(
                                            endpoint() + "/" + cleanBucket + "/" + cleanKey))
                                    .GET()
                                    .build(),
                            HttpResponse.BodyHandlers.discarding());
            assertThat(anonymousResponse.statusCode()).isIn(401, 403);
        }
    }

    @Test
    void promotionRejectsCrossTenantInvalidSourceAndConflictingCleanObjects()
            throws Exception {
        var quarantineBucket = uniqueBucket();
        var cleanBucket = uniqueCleanBucket();
        var content = new byte[] {7, 8, 9};
        var request = request(content, "application/octet-stream");
        var context = context(ORGANIZATION_ONE, "promotion-reject-1");

        try (var quarantine = adapter(quarantineBucket, 1024);
                var promotion = promotionAdapter(quarantineBucket, cleanBucket, 1024)) {
            var reference = quarantine.quarantine(
                    context, request, new ByteArrayInputStream(content));
            var authorization = promotionAuthorization(reference, request.sha256());

            assertStorageError(
                    "document-promotion-tenant-mismatch",
                    () -> promotion.promote(
                            context(ORGANIZATION_TWO, "promotion-cross-tenant"), authorization));

            var changedPolicy = new DocumentPromotionAuthorization(
                    authorization.scanAttestation(),
                    "foundation.changed",
                    Set.of("clamav"),
                    Duration.ofMinutes(5),
                    Duration.ofSeconds(2),
                    authorization.authorizedAt());
            assertStorageError(
                    "document-promotion-policy-mismatch",
                    () -> promotion.promote(context, changedPolicy));

            var oldScanAt = Instant.now().minus(Duration.ofMinutes(10));
            var oldScan = new MalwareScanResult(
                    reference,
                    MalwareScanVerdict.CLEAN,
                    "clamav",
                    "20260915.0",
                    request.sha256(),
                    oldScanAt);
            var expired = new DocumentPromotionAuthorization(
                    new DocumentScanAttestation(
                            UUID.randomUUID(), oldScan, oldScanAt.plusMillis(1)),
                    "foundation.synthetic",
                    Set.of("clamav"),
                    Duration.ofMinutes(5),
                    Duration.ofSeconds(2),
                    oldScanAt.plusSeconds(1));
            assertStorageError(
                    "document-promotion-authorization-expired",
                    () -> promotion.promote(context, expired));

            var mismatchedAuthorization = promotionAuthorization(reference, "b".repeat(64));
            assertStorageError(
                    "document-promotion-source-invalid",
                    () -> promotion.promote(context, mismatchedAuthorization));

            var corruptReference = new DocumentObjectReference(
                    ORGANIZATION_ONE, UUID.randomUUID(), UUID.randomUUID());
            var claimedDigest = "c".repeat(64);
            try (var administrator = client()) {
                administrator.putObject(PutObjectArgs.builder()
                        .bucket(quarantineBucket)
                        .object(S3PrivateDocumentStorageAdapter.objectKey(corruptReference))
                        .stream(new ByteArrayInputStream(content), (long) content.length, -1L)
                        .contentType("application/octet-stream")
                        .userMetadata(java.util.Map.of(
                                "careos-state", "quarantine",
                                "careos-sha256", claimedDigest))
                        .build());
            }
            assertStorageError(
                    "document-promotion-copy-mismatch",
                    () -> promotion.promote(
                            context, promotionAuthorization(corruptReference, claimedDigest)));
            try (var verifier = client()) {
                assertThat(objectCount(verifier, cleanBucket)).isZero();
            }

            var conflicting = "different clean content"
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            try (var administrator = client()) {
                administrator.putObject(PutObjectArgs.builder()
                        .bucket(cleanBucket)
                        .object(S3DocumentPromotionAdapter.objectKey(reference))
                        .stream(
                                new ByteArrayInputStream(conflicting),
                                (long) conflicting.length,
                                -1L)
                        .contentType("application/octet-stream")
                        .userMetadata(java.util.Map.of(
                                "careos-state", "clean",
                                "careos-sha256", sha256(conflicting)))
                        .build());
            }
            assertStorageError(
                    "document-promotion-object-conflict",
                    () -> promotion.promote(context, authorization));
        }
    }

    @Test
    void signsABoundedReadOnlyUrlOnlyForTheVerifiedPrivateCleanObject() throws Exception {
        var quarantineBucket = uniqueBucket();
        var cleanBucket = uniqueCleanBucket();
        var content = "synthetic signed clean document"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var request = request(content, "application/pdf");
        var context = context(ORGANIZATION_ONE, "signed-access-clean");

        try (var quarantine = adapter(quarantineBucket, 1024);
                var promotion = promotionAdapter(quarantineBucket, cleanBucket, 1024);
                var access = signedAccessAdapter(cleanBucket, 1024)) {
            var reference = quarantine.quarantine(
                    context, request, new ByteArrayInputStream(content));
            var promotionAuthorization = promotionAuthorization(reference, request.sha256());
            promotion.promote(context, promotionAuthorization);
            var authorization = accessAuthorization(
                    promotionEvidence(promotionAuthorization), Duration.ofSeconds(30), Instant.now());

            var signed = access.createReadAccess(context, authorization);

            assertThat(signed.accessGrantId()).isEqualTo(authorization.accessGrantId());
            assertThat(signed.document()).isEqualTo(reference);
            assertThat(signed.expiresAt()).isEqualTo(authorization.expiresAt());
            assertThat(signed.readUrl().getRawQuery()).contains("X-Amz-Expires=30");
            assertThat(access.status().availability()).isEqualTo(CapabilityAvailability.AVAILABLE);
            var response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(signed.readUrl()).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).isEqualTo(content);
            var writeAttempt = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(signed.readUrl())
                            .PUT(HttpRequest.BodyPublishers.ofByteArray(new byte[] {9}))
                            .build(),
                    HttpResponse.BodyHandlers.discarding());
            assertThat(writeAttempt.statusCode() / 100).isNotEqualTo(2);

            var direct = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(
                                    endpoint() + "/" + cleanBucket + "/"
                                            + S3DocumentPromotionAdapter.objectKey(reference)))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.discarding());
            assertThat(direct.statusCode()).isIn(401, 403);
        }
    }

    @Test
    void signedAccessRejectsTenantPurposePolicyAgeMissingAndCorruptCleanObjects()
            throws Exception {
        var quarantineBucket = uniqueBucket();
        var cleanBucket = uniqueCleanBucket();
        var content = new byte[] {3, 1, 4, 1, 5};
        var request = request(content, "application/octet-stream");
        var context = context(ORGANIZATION_ONE, "signed-access-reject");

        try (var quarantine = adapter(quarantineBucket, 1024);
                var promotion = promotionAdapter(quarantineBucket, cleanBucket, 1024);
                var access = signedAccessAdapter(cleanBucket, 1024)) {
            var reference = quarantine.quarantine(
                    context, request, new ByteArrayInputStream(content));
            var promotionAuthorization = promotionAuthorization(reference, request.sha256());
            promotion.promote(context, promotionAuthorization);
            var evidence = promotionEvidence(promotionAuthorization);
            var authorization = accessAuthorization(evidence, Duration.ofMinutes(5), Instant.now());

            assertStorageError(
                    "document-access-tenant-mismatch",
                    () -> access.createReadAccess(
                            context(ORGANIZATION_TWO, "signed-access-cross-tenant"), authorization));
            assertStorageError(
                    "document-access-purpose-mismatch",
                    () -> access.createReadAccess(
                            new AuthorizedTenantContext(
                                    ORGANIZATION_ONE,
                                    ACTOR_ID,
                                    "document.export",
                                    "signed-access-purpose"),
                            authorization));

            var changedPolicy = new DocumentAccessPolicy(
                    "foundation.changed",
                    Set.of("document.quarantine"),
                    Duration.ofMinutes(10),
                    Duration.ofSeconds(30),
                    Duration.ofSeconds(2));
            assertStorageError(
                    "document-access-policy-mismatch",
                    () -> access.createReadAccess(
                            context,
                            accessAuthorization(
                                    evidence,
                                    changedPolicy,
                                    Duration.ofMinutes(5),
                                    Instant.now())));

            var stale = accessAuthorization(
                    evidence, Duration.ofMinutes(5), Instant.now().minusSeconds(120));
            assertStorageError(
                    "document-access-authorization-expired",
                    () -> access.createReadAccess(context, stale));

            var missingReference = new DocumentObjectReference(
                    ORGANIZATION_ONE, UUID.randomUUID(), UUID.randomUUID());
            var missingPromotion = promotionEvidence(
                    promotionAuthorization(missingReference, request.sha256()));
            assertStorageError(
                    "document-access-clean-object-not-found",
                    () -> access.createReadAccess(
                            context,
                            accessAuthorization(
                                    missingPromotion, Duration.ofMinutes(5), Instant.now())));

            var corrupt = "tampered after promotion"
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            try (var administrator = client()) {
                administrator.putObject(PutObjectArgs.builder()
                        .bucket(cleanBucket)
                        .object(S3DocumentPromotionAdapter.objectKey(reference))
                        .stream(new ByteArrayInputStream(corrupt), (long) corrupt.length, -1L)
                        .contentType("application/octet-stream")
                        .userMetadata(java.util.Map.of(
                                "careos-state", "clean",
                                "careos-sha256", request.sha256()))
                        .build());
            }
            assertStorageError(
                    "document-access-clean-object-invalid",
                    () -> access.createReadAccess(
                            context,
                            accessAuthorization(evidence, Duration.ofMinutes(5), Instant.now())));
        }
    }

    @Test
    void appliesComplianceRetentionAndLegalHoldToTheExactCleanVersion() throws Exception {
        var quarantineBucket = uniqueBucket();
        var cleanBucket = uniqueCleanBucket();
        createObjectLockBucket(cleanBucket);
        var content = "synthetic retained evidence"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var request = request(content, "application/pdf");
        var context = context(ORGANIZATION_ONE, "retention-apply");
        final DocumentObjectReference reference;
        try (var storage = adapter(quarantineBucket, 1024)) {
            reference = storage.quarantine(context, request, new ByteArrayInputStream(content));
        }
        var promotionAuthorization = promotionAuthorization(reference, request.sha256());
        try (var promotion = promotionAdapter(quarantineBucket, cleanBucket, 1024)) {
            assertThat(promotion.promote(context, promotionAuthorization)).isEqualTo(reference);
        }
        var promotionEvidence = promotionEvidence(promotionAuthorization);

        try (var retention = retentionAdapter(cleanBucket, 1024)) {
            var first = retentionAuthorization(
                    promotionEvidence,
                    UUID.randomUUID(),
                    null,
                    Instant.now().plus(Duration.ofMinutes(10)),
                    true,
                    Instant.now());
            var receipt = retention.apply(context, first);
            assertThat(receipt.document()).isEqualTo(reference);
            assertThat(receipt.retainUntil()).isEqualTo(first.retainUntil());
            assertThat(receipt.legalHold()).isTrue();
            assertThat(receipt.storageVersionSha256()).matches("[0-9a-f]{64}");
            assertThat(retention.apply(context, first)).isEqualTo(receipt);

            try (var verifier = client()) {
                var key = S3DocumentPromotionAdapter.objectKey(reference);
                var stat = verifier.statObject(
                        StatObjectArgs.builder().bucket(cleanBucket).object(key).build());
                var providerRetention = verifier.getObjectRetention(
                        GetObjectRetentionArgs.builder()
                                .bucket(cleanBucket)
                                .object(key)
                                .versionId(stat.versionId())
                                .build());
                assertThat(providerRetention.mode()).isEqualTo(RetentionMode.COMPLIANCE);
                assertThat(providerRetention.retainUntilDate().toInstant())
                        .isEqualTo(first.retainUntil());
                assertThat(verifier.isObjectLegalHoldEnabled(
                                IsObjectLegalHoldEnabledArgs.builder()
                                        .bucket(cleanBucket)
                                        .object(key)
                                        .versionId(stat.versionId())
                                        .build()))
                        .isTrue();
                assertThatThrownBy(() -> verifier.removeObject(RemoveObjectArgs.builder()
                                .bucket(cleanBucket)
                                .object(key)
                                .versionId(stat.versionId())
                                .build()))
                        .isInstanceOf(io.minio.errors.ErrorResponseException.class);
            }

            var extension = retentionAuthorization(
                    promotionEvidence,
                    UUID.randomUUID(),
                    first.retentionDirectiveId(),
                    first.retainUntil().plus(Duration.ofMinutes(5)),
                    true,
                    Instant.now());
            assertThat(retention.apply(context, extension).retainUntil())
                    .isEqualTo(extension.retainUntil());

            var release = retentionAuthorization(
                    promotionEvidence,
                    UUID.randomUUID(),
                    extension.retentionDirectiveId(),
                    extension.retainUntil().plus(Duration.ofMinutes(5)),
                    false,
                    Instant.now());
            assertStorageError(
                    "document-legal-hold-release-not-supported",
                    () -> retention.apply(context, release));
        }
    }

    @Test
    void retentionRejectsTenantPurposePolicyAuthorizationAndCleanObjectDrift() throws Exception {
        var quarantineBucket = uniqueBucket();
        var cleanBucket = uniqueCleanBucket();
        createObjectLockBucket(cleanBucket);
        var content = "synthetic retention rejection"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var request = request(content, "application/octet-stream");
        var context = context(ORGANIZATION_ONE, "retention-reject");
        final DocumentObjectReference reference;
        try (var storage = adapter(quarantineBucket, 1024)) {
            reference = storage.quarantine(context, request, new ByteArrayInputStream(content));
        }
        var promotionAuthorization = promotionAuthorization(reference, request.sha256());
        try (var promotion = promotionAdapter(quarantineBucket, cleanBucket, 1024)) {
            promotion.promote(context, promotionAuthorization);
        }
        var promotionEvidence = promotionEvidence(promotionAuthorization);
        var authorization = retentionAuthorization(
                promotionEvidence,
                UUID.randomUUID(),
                null,
                Instant.now().plus(Duration.ofMinutes(10)),
                false,
                Instant.now());

        try (var retention = retentionAdapter(cleanBucket, 1024)) {
            assertStorageError(
                    "document-retention-tenant-mismatch",
                    () -> retention.apply(
                            context(ORGANIZATION_TWO, "retention-cross-tenant"), authorization));
            assertStorageError(
                    "document-retention-purpose-mismatch",
                    () -> retention.apply(
                            new AuthorizedTenantContext(
                                    ORGANIZATION_ONE,
                                    ACTOR_ID,
                                    "document.export",
                                    "retention-purpose"),
                            authorization));

            var otherPolicy = new DocumentRetentionPolicy(
                    "foundation.changed",
                    Set.of("document.quarantine"),
                    Duration.ofMinutes(1),
                    Duration.ofDays(30),
                    Duration.ofSeconds(30),
                    Duration.ofSeconds(2));
            assertStorageError(
                    "document-retention-policy-mismatch",
                    () -> retention.apply(
                            context,
                            retentionAuthorization(
                                    promotionEvidence,
                                    otherPolicy,
                                    UUID.randomUUID(),
                                    null,
                                    Instant.now().plus(Duration.ofMinutes(10)),
                                    false,
                                    Instant.now())));

            var staleAuthorizedAt = Instant.now().minus(Duration.ofMinutes(2));
            assertStorageError(
                    "document-retention-authorization-expired",
                    () -> retention.apply(
                            context,
                            retentionAuthorization(
                                    promotionEvidence,
                                    UUID.randomUUID(),
                                    null,
                                    Instant.now().plus(Duration.ofMinutes(10)),
                                    false,
                                    staleAuthorizedAt)));

            var missingReference = new DocumentObjectReference(
                    ORGANIZATION_ONE, UUID.randomUUID(), UUID.randomUUID());
            assertStorageError(
                    "document-retention-clean-object-not-found",
                    () -> retention.apply(
                            context,
                            retentionAuthorization(
                                    promotionEvidenceFor(missingReference, request.sha256()),
                                    UUID.randomUUID(),
                                    null,
                                    Instant.now().plus(Duration.ofMinutes(10)),
                                    false,
                                    Instant.now())));

            var corrupt = "changed after promotion"
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            try (var administrator = client()) {
                administrator.putObject(PutObjectArgs.builder()
                        .bucket(cleanBucket)
                        .object(S3DocumentPromotionAdapter.objectKey(reference))
                        .stream(new ByteArrayInputStream(corrupt), (long) corrupt.length, -1L)
                        .contentType("application/octet-stream")
                        .userMetadata(java.util.Map.of(
                                "careos-state", "clean",
                                "careos-sha256", request.sha256()))
                        .build());
            }
            assertStorageError(
                    "document-retention-clean-object-invalid",
                    () -> retention.apply(context, authorization));
        }
    }

    @Test
    void retentionActivationRequiresExplicitPolicyAndPreprovisionedObjectLock() throws Exception {
        var quarantineBucket = uniqueBucket();
        var cleanBucket = uniqueCleanBucket();
        try (var administrator = client()) {
            administrator.makeBucket(
                    MakeBucketArgs.builder().bucket(quarantineBucket).build());
        }
        createObjectLockBucket(cleanBucket);

        new ApplicationContextRunner()
                .withUserConfiguration(
                        PlatformCapabilityConfiguration.class,
                        S3DocumentStorageConfiguration.class)
                .withBean(
                        DocumentEvidenceOperations.class,
                        () -> Mockito.mock(DocumentEvidenceOperations.class))
                .withPropertyValues(
                        "careos.storage.s3.enabled=true",
                        "careos.storage.s3.endpoint=" + endpoint(),
                        "careos.storage.s3.access-key=" + ACCESS_KEY,
                        "careos.storage.s3.secret-key=" + SECRET_KEY,
                        "careos.storage.s3.quarantine-bucket=" + quarantineBucket,
                        "careos.storage.s3.clean-bucket=" + cleanBucket,
                        "careos.storage.s3.maximum-upload-bytes=1024",
                        "careos.storage.s3.allow-http=true",
                        "careos.storage.s3.create-bucket-if-missing=false",
                        "careos.documents.retention.enabled=true",
                        "careos.documents.retention.policy-key=foundation.synthetic",
                        "careos.documents.retention.accepted-purposes=document.quarantine",
                        "careos.documents.retention.minimum-retention=1m",
                        "careos.documents.retention.maximum-retention=30d",
                        "careos.documents.retention.maximum-authorization-age=30s",
                        "careos.documents.retention.maximum-future-skew=2s")
                .run(contextRunner -> {
                    assertThat(contextRunner).hasNotFailed();
                    assertThat(contextRunner).hasSingleBean(DocumentRetentionPort.class);
                    assertThat(contextRunner).hasSingleBean(S3DocumentRetentionAdapter.class);
                    assertThat(contextRunner).hasSingleBean(DocumentRetentionOperations.class);
                    var registry = contextRunner.getBean(PlatformCapabilityRegistry.class);
                    assertThat(registry.status(PlatformCapability.DOCUMENT_RETENTION)
                                    .availability())
                            .isEqualTo(CapabilityAvailability.AVAILABLE);
                    assertThat(registry.statuses())
                            .filteredOn(status -> status.availability()
                                    == CapabilityAvailability.UNAVAILABLE)
                            .hasSize(7);
                });

        new ApplicationContextRunner()
                .withUserConfiguration(S3DocumentStorageConfiguration.class)
                .withBean(
                        DocumentEvidenceOperations.class,
                        () -> Mockito.mock(DocumentEvidenceOperations.class))
                .withPropertyValues(
                        "careos.documents.retention.enabled=true",
                        "careos.storage.s3.enabled=true",
                        "careos.storage.s3.endpoint=" + endpoint(),
                        "careos.storage.s3.access-key=" + ACCESS_KEY,
                        "careos.storage.s3.secret-key=" + SECRET_KEY,
                        "careos.storage.s3.quarantine-bucket=" + uniqueBucket(),
                        "careos.storage.s3.clean-bucket=" + uniqueCleanBucket(),
                        "careos.storage.s3.allow-http=true",
                        "careos.storage.s3.create-bucket-if-missing=true",
                        "careos.documents.retention.policy-key=foundation.synthetic",
                        "careos.documents.retention.accepted-purposes=document.quarantine",
                        "careos.documents.retention.minimum-retention=1m",
                        "careos.documents.retention.maximum-retention=30d")
                .run(contextRunner -> {
                    assertThat(contextRunner).hasFailed();
                    assertThat(contextRunner.getStartupFailure())
                            .hasStackTraceContaining("pre-provisioned Object Lock");
                });
    }

    @Test
    void promotionActivationRequiresExplicitPolicyAndReplacesOnlyItsFailClosedPort() {
        var quarantineBucket = uniqueBucket();
        var cleanBucket = uniqueCleanBucket();
        new ApplicationContextRunner()
                .withUserConfiguration(
                        PlatformCapabilityConfiguration.class,
                        S3DocumentStorageConfiguration.class)
                .withBean(
                        DocumentEvidenceOperations.class,
                        () -> Mockito.mock(DocumentEvidenceOperations.class))
                .withPropertyValues(
                        "careos.storage.s3.enabled=true",
                        "careos.storage.s3.endpoint=" + endpoint(),
                        "careos.storage.s3.access-key=" + ACCESS_KEY,
                        "careos.storage.s3.secret-key=" + SECRET_KEY,
                        "careos.storage.s3.quarantine-bucket=" + quarantineBucket,
                        "careos.storage.s3.clean-bucket=" + cleanBucket,
                        "careos.storage.s3.maximum-upload-bytes=1024",
                        "careos.storage.s3.allow-http=true",
                        "careos.storage.s3.create-bucket-if-missing=true",
                        "careos.documents.promotion.enabled=true",
                        "careos.documents.promotion.policy-key=foundation.synthetic",
                        "careos.documents.promotion.accepted-scanner-keys=clamav",
                        "careos.documents.promotion.maximum-scan-age=5m",
                        "careos.documents.promotion.maximum-future-skew=2s")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(DocumentPromotionPort.class);
                    assertThat(context).hasSingleBean(S3DocumentPromotionAdapter.class);
                    assertThat(context).hasSingleBean(DocumentPromotionOperations.class);
                    var registry = context.getBean(PlatformCapabilityRegistry.class);
                    assertThat(registry.status(PlatformCapability.DOCUMENT_PROMOTION)
                                    .availability())
                            .isEqualTo(CapabilityAvailability.AVAILABLE);
                    assertThat(registry.statuses())
                            .filteredOn(status -> status.availability()
                                    == CapabilityAvailability.UNAVAILABLE)
                            .hasSize(7);
                });

        new ApplicationContextRunner()
                .withUserConfiguration(S3DocumentStorageConfiguration.class)
                .withBean(
                        DocumentEvidenceOperations.class,
                        () -> Mockito.mock(DocumentEvidenceOperations.class))
                .withPropertyValues(
                        "careos.documents.promotion.enabled=true",
                        "careos.storage.s3.enabled=true",
                        "careos.storage.s3.endpoint=" + endpoint(),
                        "careos.storage.s3.access-key=" + ACCESS_KEY,
                        "careos.storage.s3.secret-key=" + SECRET_KEY,
                        "careos.storage.s3.quarantine-bucket=" + uniqueBucket(),
                        "careos.storage.s3.clean-bucket=" + uniqueCleanBucket(),
                        "careos.storage.s3.allow-http=true",
                        "careos.storage.s3.create-bucket-if-missing=true")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("Document promotion policy is invalid");
                });
    }

    @Test
    void signedAccessActivationRequiresExplicitPolicyAndReplacesOnlyItsFailClosedPort() {
        var quarantineBucket = uniqueBucket();
        var cleanBucket = uniqueCleanBucket();
        new ApplicationContextRunner()
                .withUserConfiguration(
                        PlatformCapabilityConfiguration.class,
                        S3DocumentStorageConfiguration.class)
                .withBean(
                        DocumentEvidenceOperations.class,
                        () -> Mockito.mock(DocumentEvidenceOperations.class))
                .withPropertyValues(
                        "careos.storage.s3.enabled=true",
                        "careos.storage.s3.endpoint=" + endpoint(),
                        "careos.storage.s3.access-key=" + ACCESS_KEY,
                        "careos.storage.s3.secret-key=" + SECRET_KEY,
                        "careos.storage.s3.quarantine-bucket=" + quarantineBucket,
                        "careos.storage.s3.clean-bucket=" + cleanBucket,
                        "careos.storage.s3.maximum-upload-bytes=1024",
                        "careos.storage.s3.allow-http=true",
                        "careos.storage.s3.create-bucket-if-missing=true",
                        "careos.documents.signed-access.enabled=true",
                        "careos.documents.signed-access.policy-key=foundation.synthetic",
                        "careos.documents.signed-access.accepted-purposes=document.quarantine",
                        "careos.documents.signed-access.maximum-ttl=10m",
                        "careos.documents.signed-access.maximum-authorization-age=30s",
                        "careos.documents.signed-access.maximum-future-skew=2s")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(SignedDocumentAccessPort.class);
                    assertThat(context).hasSingleBean(S3SignedDocumentAccessAdapter.class);
                    assertThat(context).hasSingleBean(DocumentAccessOperations.class);
                    var registry = context.getBean(PlatformCapabilityRegistry.class);
                    assertThat(registry.status(PlatformCapability.SIGNED_DOCUMENT_ACCESS)
                                    .availability())
                            .isEqualTo(CapabilityAvailability.AVAILABLE);
                    assertThat(registry.statuses())
                            .filteredOn(status -> status.availability()
                                    == CapabilityAvailability.UNAVAILABLE)
                            .hasSize(7);
                });

        new ApplicationContextRunner()
                .withUserConfiguration(S3DocumentStorageConfiguration.class)
                .withBean(
                        DocumentEvidenceOperations.class,
                        () -> Mockito.mock(DocumentEvidenceOperations.class))
                .withPropertyValues(
                        "careos.documents.signed-access.enabled=true",
                        "careos.storage.s3.enabled=true",
                        "careos.storage.s3.endpoint=" + endpoint(),
                        "careos.storage.s3.access-key=" + ACCESS_KEY,
                        "careos.storage.s3.secret-key=" + SECRET_KEY,
                        "careos.storage.s3.quarantine-bucket=" + uniqueBucket(),
                        "careos.storage.s3.clean-bucket=" + uniqueCleanBucket(),
                        "careos.storage.s3.allow-http=true",
                        "careos.storage.s3.create-bucket-if-missing=true")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("Signed document access policy is invalid");
                });
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

    private static S3DocumentPromotionAdapter promotionAdapter(
            String quarantineBucket, String cleanBucket, long maximumBytes) {
        var adapter = new S3DocumentPromotionAdapter(
                client(),
                quarantineBucket,
                cleanBucket,
                maximumBytes,
                true,
                promotionPolicy(),
                Clock.systemUTC());
        adapter.initialize();
        return adapter;
    }

    private static S3SignedDocumentAccessAdapter signedAccessAdapter(
            String cleanBucket, long maximumBytes) {
        var adapter = new S3SignedDocumentAccessAdapter(
                client(),
                URI.create(endpoint()),
                cleanBucket,
                maximumBytes,
                true,
                accessPolicy(),
                Clock.systemUTC());
        adapter.initialize();
        return adapter;
    }

    private static S3DocumentRetentionAdapter retentionAdapter(
            String cleanBucket, long maximumBytes) {
        var adapter = new S3DocumentRetentionAdapter(
                client(),
                cleanBucket,
                maximumBytes,
                retentionPolicy(),
                Clock.systemUTC());
        adapter.initialize();
        return adapter;
    }

    private static void createObjectLockBucket(String bucket) throws Exception {
        try (var administrator = client()) {
            administrator.makeBucket(
                    MakeBucketArgs.builder().bucket(bucket).objectLock(true).build());
        }
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

    private static String uniqueCleanBucket() {
        return "careos-c-" + UUID.randomUUID().toString().replace("-", "");
    }

    private static DocumentPromotionAuthorization promotionAuthorization(
            DocumentObjectReference reference, String sha256) {
        var scannedAt = Instant.now().minusSeconds(5);
        var scan = new MalwareScanResult(
                reference,
                MalwareScanVerdict.CLEAN,
                "clamav",
                "20260915.1",
                sha256,
                scannedAt);
        return new DocumentPromotionAuthorization(
                new DocumentScanAttestation(UUID.randomUUID(), scan, scannedAt.plusMillis(1)),
                "foundation.synthetic",
                java.util.Set.of("clamav"),
                Duration.ofMinutes(5),
                Duration.ofSeconds(2),
                Instant.now());
    }

    private static DocumentPromotionPolicy promotionPolicy() {
        return new DocumentPromotionPolicy(
                "foundation.synthetic",
                Set.of("clamav"),
                Duration.ofMinutes(5),
                Duration.ofSeconds(2));
    }

    private static DocumentPromotionEvidence promotionEvidence(
            DocumentPromotionAuthorization authorization) {
        return new DocumentPromotionEvidence(
                authorization.scanAttestation(),
                authorization.policyKey(),
                authorization.acceptedScannerKeys(),
                authorization.maximumScanAge(),
                authorization.maximumFutureSkew(),
                Instant.now().minusSeconds(1));
    }

    private static DocumentPromotionEvidence promotionEvidenceFor(
            DocumentObjectReference reference, String sha256) {
        return promotionEvidence(promotionAuthorization(reference, sha256));
    }

    private static DocumentAccessAuthorization accessAuthorization(
            DocumentPromotionEvidence promotion, Duration requestedTtl, Instant authorizedAt) {
        return accessAuthorization(promotion, accessPolicy(), requestedTtl, authorizedAt);
    }

    private static DocumentAccessAuthorization accessAuthorization(
            DocumentPromotionEvidence promotion,
            DocumentAccessPolicy policy,
            Duration requestedTtl,
            Instant authorizedAt) {
        return new DocumentAccessAuthorization(
                UUID.randomUUID(),
                promotion,
                policy.policyKey(),
                policy.acceptedPurposes(),
                requestedTtl,
                policy.maximumTtl(),
                policy.maximumAuthorizationAge(),
                policy.maximumFutureSkew(),
                "document.quarantine",
                authorizedAt);
    }

    private static DocumentAccessPolicy accessPolicy() {
        return new DocumentAccessPolicy(
                "foundation.synthetic",
                Set.of("document.quarantine"),
                Duration.ofMinutes(10),
                Duration.ofSeconds(30),
                Duration.ofSeconds(2));
    }

    private static DocumentRetentionAuthorization retentionAuthorization(
            DocumentPromotionEvidence promotion,
            UUID directiveId,
            UUID previousDirectiveId,
            Instant retainUntil,
            boolean legalHold,
            Instant authorizedAt) {
        return retentionAuthorization(
                promotion,
                retentionPolicy(),
                directiveId,
                previousDirectiveId,
                retainUntil,
                legalHold,
                authorizedAt);
    }

    private static DocumentRetentionAuthorization retentionAuthorization(
            DocumentPromotionEvidence promotion,
            DocumentRetentionPolicy policy,
            UUID directiveId,
            UUID previousDirectiveId,
            Instant retainUntil,
            boolean legalHold,
            Instant authorizedAt) {
        return new DocumentRetentionAuthorization(
                directiveId,
                promotion,
                previousDirectiveId,
                policy.policyKey(),
                policy.acceptedPurposes(),
                policy.minimumRetention(),
                policy.maximumRetention(),
                policy.maximumAuthorizationAge(),
                policy.maximumFutureSkew(),
                retainUntil,
                legalHold,
                "document.quarantine",
                authorizedAt);
    }

    private static DocumentRetentionPolicy retentionPolicy() {
        return new DocumentRetentionPolicy(
                "foundation.synthetic",
                Set.of("document.quarantine"),
                Duration.ofMinutes(1),
                Duration.ofDays(30),
                Duration.ofSeconds(30),
                Duration.ofSeconds(2));
    }

    private static void assertStorageError(String errorCode, org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOf(DocumentStorageException.class)
                .extracting("errorCode")
                .isEqualTo(errorCode);
    }
}
