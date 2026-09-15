package com.rootopathy.careos.platform.infrastructure;

import com.rootopathy.careos.platform.application.CapabilityProbe;
import com.rootopathy.careos.platform.application.DocumentPromotionPort;
import com.rootopathy.careos.platform.application.DocumentStorageException;
import com.rootopathy.careos.platform.domain.CapabilityAvailability;
import com.rootopathy.careos.platform.domain.CapabilityStatus;
import com.rootopathy.careos.platform.domain.DocumentObjectReference;
import com.rootopathy.careos.platform.domain.DocumentPromotionAuthorization;
import com.rootopathy.careos.platform.domain.DocumentPromotionPolicy;
import com.rootopathy.careos.platform.domain.MalwareScanVerdict;
import com.rootopathy.careos.platform.domain.PlatformCapability;
import com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext;
import io.minio.BucketExistsArgs;
import io.minio.GetBucketPolicyArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.errors.ErrorResponseException;
import io.minio.errors.MinioException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Copies only durably authorized clean evidence into a separate private S3-compatible bucket. */
public final class S3DocumentPromotionAdapter
        implements DocumentPromotionPort, CapabilityProbe, AutoCloseable {
    private static final String READY = "s3-clean-promotion-adapter-ready";
    private static final String NOT_INITIALIZED = "s3-clean-promotion-adapter-not-initialized";
    private static final String STORAGE_UNAVAILABLE = "document-promotion-storage-unavailable";
    private static final String TENANT_MISMATCH = "document-promotion-tenant-mismatch";
    private static final String CLEAN_REQUIRED = "document-promotion-clean-evidence-required";
    private static final String POLICY_MISMATCH = "document-promotion-policy-mismatch";
    private static final String AUTHORIZATION_EXPIRED = "document-promotion-authorization-expired";
    private static final String SOURCE_MISSING = "document-promotion-source-not-found";
    private static final String SOURCE_INVALID = "document-promotion-source-invalid";
    private static final String OBJECT_CONFLICT = "document-promotion-object-conflict";
    private static final String COPY_MISMATCH = "document-promotion-copy-mismatch";
    private static final String CLEANUP_FAILED = "document-promotion-cleanup-failed";
    private static final Set<String> NO_POLICY_CODES = Set.of("NoSuchBucketPolicy", "NoSuchPolicy");
    private static final Set<String> NOT_FOUND_CODES = Set.of("NoSuchKey", "NoSuchObject");
    private static final Set<String> PRECONDITION_CODES =
            Set.of("PreconditionFailed", "ConditionalRequestConflict");

    private final MinioClient client;
    private final String quarantineBucket;
    private final String cleanBucket;
    private final long maximumObjectBytes;
    private final boolean createBucketIfMissing;
    private final DocumentPromotionPolicy policy;
    private final Clock clock;
    private volatile boolean initialized;

    public S3DocumentPromotionAdapter(
            MinioClient client,
            String quarantineBucket,
            String cleanBucket,
            long maximumObjectBytes,
            boolean createBucketIfMissing,
            DocumentPromotionPolicy policy,
            Clock clock) {
        this.client = Objects.requireNonNull(client, "client");
        this.quarantineBucket = Objects.requireNonNull(quarantineBucket, "quarantineBucket");
        this.cleanBucket = Objects.requireNonNull(cleanBucket, "cleanBucket");
        this.maximumObjectBytes = maximumObjectBytes;
        this.createBucketIfMissing = createBucketIfMissing;
        this.policy = Objects.requireNonNull(policy, "policy");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (quarantineBucket.equals(cleanBucket)) {
            throw new IllegalArgumentException("quarantineBucket and cleanBucket must be distinct");
        }
    }

    public synchronized void initialize() {
        initialized = false;
        try {
            requirePrivateBucket(quarantineBucket);
            requirePrivateBucket(cleanBucket);
            initialized = true;
        } catch (MinioException exception) {
            throw new IllegalStateException("S3 clean promotion initialization failed", exception);
        }
    }

    @Override
    public DocumentObjectReference promote(
            AuthorizedTenantContext context, DocumentPromotionAuthorization authorization) {
        requireInitialized();
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(authorization, "authorization");
        var document = authorization.document();
        if (!context.organizationId().equals(document.organizationId())) {
            throw new DocumentStorageException(TENANT_MISMATCH);
        }
        var result = authorization.scanAttestation().result();
        if (result.verdict() != MalwareScanVerdict.CLEAN) {
            throw new DocumentStorageException(CLEAN_REQUIRED);
        }
        if (!policy.policyKey().equals(authorization.policyKey())
                || !policy.acceptedScannerKeys().equals(authorization.acceptedScannerKeys())
                || !policy.maximumScanAge().equals(authorization.maximumScanAge())
                || !policy.maximumFutureSkew().equals(authorization.maximumFutureSkew())) {
            throw new DocumentStorageException(POLICY_MISMATCH);
        }
        var now = clock.instant();
        if (result.scannedAt().isBefore(now.minus(policy.maximumScanAge()))
                || result.scannedAt().isAfter(now.plus(policy.maximumFutureSkew()))) {
            throw new DocumentStorageException(AUTHORIZATION_EXPIRED);
        }

        var sourceKey = S3PrivateDocumentStorageAdapter.objectKey(document);
        var source = statIfPresent(quarantineBucket, sourceKey);
        if (source == null) {
            throw new DocumentStorageException(SOURCE_MISSING);
        }
        requireValidSource(source, authorization);

        var targetKey = objectKey(document);
        var target = statIfPresent(cleanBucket, targetKey);
        if (target != null) {
            requireMatchingTarget(target, targetKey, source, authorization);
            return document;
        }

        var expectedSha256 = authorization.scanAttestation().result().sha256();
        VerifiedCopyInputStream verifiedContent = null;
        try (var stored = client.getObject(GetObjectArgs.builder()
                .bucket(quarantineBucket)
                .object(sourceKey)
                .matchETag(source.etag())
                .build())) {
            verifiedContent = new VerifiedCopyInputStream(stored, source.size());
            client.putObject(PutObjectArgs.builder()
                    .bucket(cleanBucket)
                    .object(targetKey)
                    .stream(verifiedContent, source.size(), -1L)
                    .contentType(source.contentType())
                    .headers(Map.of("If-None-Match", "*"))
                    .userMetadata(Map.of(
                            "careos-sha256", expectedSha256,
                            "careos-state", "clean"))
                    .build());
        } catch (ErrorResponseException exception) {
            if (PRECONDITION_CODES.contains(exception.errorResponse().code())) {
                var concurrent = statIfPresent(cleanBucket, targetKey);
                if (concurrent != null) {
                    requireMatchingTarget(concurrent, targetKey, source, authorization);
                    return document;
                }
                throw new DocumentStorageException(OBJECT_CONFLICT);
            }
            throw new DocumentStorageException(STORAGE_UNAVAILABLE);
        } catch (MinioException | IOException exception) {
            throw new DocumentStorageException(STORAGE_UNAVAILABLE);
        }

        if (verifiedContent == null
                || !verifiedContent.hasExactLength()
                || !MessageDigest.isEqual(
                        HexFormat.of().parseHex(expectedSha256), verifiedContent.digest())) {
            removeInvalidTarget(targetKey);
            throw new DocumentStorageException(COPY_MISMATCH);
        }
        return document;
    }

    @Override
    public CapabilityStatus status() {
        return initialized
                ? new CapabilityStatus(
                        PlatformCapability.DOCUMENT_PROMOTION,
                        CapabilityAvailability.AVAILABLE,
                        READY)
                : new CapabilityStatus(
                        PlatformCapability.DOCUMENT_PROMOTION,
                        CapabilityAvailability.UNAVAILABLE,
                        NOT_INITIALIZED);
    }

    @Override
    public void close() {
        initialized = false;
        try {
            client.close();
        } catch (Exception exception) {
            throw new IllegalStateException("S3 clean promotion client shutdown failed", exception);
        }
    }

    private void requirePrivateBucket(String bucket) throws MinioException {
        var exists = client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
        if (!exists && createBucketIfMissing) {
            client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
            exists = true;
        }
        if (!exists) {
            throw new IllegalStateException("Dedicated S3 document bucket is missing");
        }
        try {
            var policy = client.getBucketPolicy(
                    GetBucketPolicyArgs.builder().bucket(bucket).build());
            if (policy != null && !policy.isBlank()) {
                throw new IllegalStateException("Dedicated S3 document buckets must not have bucket policies");
            }
        } catch (ErrorResponseException exception) {
            if (!NO_POLICY_CODES.contains(exception.errorResponse().code())) {
                throw exception;
            }
        }
    }

    private void requireValidSource(
            StatObjectResponse source, DocumentPromotionAuthorization authorization) {
        var expectedSha256 = authorization.scanAttestation().result().sha256();
        if (source.size() < 1
                || source.size() > maximumObjectBytes
                || source.etag() == null
                || source.etag().isBlank()
                || source.contentType() == null
                || source.contentType().isBlank()
                || !"quarantine".equals(singleMetadata(source, "careos-state"))
                || !expectedSha256.equals(singleMetadata(source, "careos-sha256"))) {
            throw new DocumentStorageException(SOURCE_INVALID);
        }
    }

    private void requireMatchingTarget(
            StatObjectResponse target,
            String targetKey,
            StatObjectResponse source,
            DocumentPromotionAuthorization authorization) {
        var expectedSha256 = authorization.scanAttestation().result().sha256();
        if (target.size() != source.size()
                || target.etag() == null
                || target.etag().isBlank()
                || !Objects.equals(target.contentType(), source.contentType())
                || !"clean".equals(singleMetadata(target, "careos-state"))
                || !expectedSha256.equals(singleMetadata(target, "careos-sha256"))) {
            throw new DocumentStorageException(OBJECT_CONFLICT);
        }
        try (var stored = client.getObject(GetObjectArgs.builder()
                .bucket(cleanBucket)
                .object(targetKey)
                .matchETag(target.etag())
                .build())) {
            var digest = sha256();
            var buffer = new byte[8192];
            long bytesRead = 0;
            int read;
            while ((read = stored.read(buffer)) != -1) {
                bytesRead += read;
                if (bytesRead > source.size()) {
                    throw new DocumentStorageException(OBJECT_CONFLICT);
                }
                digest.update(buffer, 0, read);
            }
            if (bytesRead != source.size()
                    || !MessageDigest.isEqual(
                            HexFormat.of().parseHex(expectedSha256), digest.digest())) {
                throw new DocumentStorageException(OBJECT_CONFLICT);
            }
        } catch (DocumentStorageException exception) {
            throw exception;
        } catch (MinioException | IOException exception) {
            throw new DocumentStorageException(STORAGE_UNAVAILABLE);
        }
    }

    private StatObjectResponse statIfPresent(String bucket, String objectKey) {
        try {
            return client.statObject(
                    StatObjectArgs.builder().bucket(bucket).object(objectKey).build());
        } catch (ErrorResponseException exception) {
            if (NOT_FOUND_CODES.contains(exception.errorResponse().code())) {
                return null;
            }
            throw new DocumentStorageException(STORAGE_UNAVAILABLE);
        } catch (MinioException exception) {
            throw new DocumentStorageException(STORAGE_UNAVAILABLE);
        }
    }

    private void removeInvalidTarget(String targetKey) {
        try {
            client.removeObject(
                    RemoveObjectArgs.builder().bucket(cleanBucket).object(targetKey).build());
        } catch (MinioException exception) {
            throw new DocumentStorageException(CLEANUP_FAILED);
        }
    }

    private void requireInitialized() {
        if (!initialized) {
            throw new DocumentStorageException(STORAGE_UNAVAILABLE);
        }
    }

    static String objectKey(DocumentObjectReference reference) {
        return "organizations/%s/documents/%s/versions/%s/clean"
                .formatted(
                        reference.organizationId(),
                        reference.documentId(),
                        reference.objectVersionId());
    }

    private static String singleMetadata(StatObjectResponse object, String name) {
        var values = object.userMetadata().get(name);
        if (values == null || values.size() != 1) {
            return null;
        }
        return values.iterator().next();
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static final class VerifiedCopyInputStream extends FilterInputStream {
        private final long expectedBytes;
        private final MessageDigest digest = sha256();
        private long bytesRead;
        private boolean endObserved;
        private boolean overflow;

        private VerifiedCopyInputStream(InputStream delegate, long expectedBytes) {
            super(delegate);
            this.expectedBytes = expectedBytes;
        }

        @Override
        public int read() throws IOException {
            var value = super.read();
            if (value == -1) {
                endObserved = true;
                return -1;
            }
            if (bytesRead == expectedBytes) {
                overflow = true;
                throw new IOException("Content exceeds its expected length");
            }
            bytesRead++;
            digest.update((byte) value);
            return value;
        }

        @Override
        public int read(byte[] target, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, target.length);
            if (length == 0) {
                return 0;
            }
            if (bytesRead == expectedBytes) {
                return read() == -1 ? -1 : 1;
            }
            var remaining = expectedBytes - bytesRead;
            var boundedLength = (int) Math.min(length, remaining);
            var result = super.read(target, offset, boundedLength);
            if (result == -1) {
                endObserved = true;
                return -1;
            }
            bytesRead += result;
            digest.update(target, offset, result);
            return result;
        }

        @Override
        public void close() {
            // The enclosing try-with-resources owns the source object stream.
        }

        private boolean hasExactLength() {
            if (bytesRead != expectedBytes) {
                return false;
            }
            if (!endObserved && !overflow) {
                try {
                    if (super.read() == -1) {
                        endObserved = true;
                    } else {
                        overflow = true;
                    }
                } catch (IOException exception) {
                    overflow = true;
                }
            }
            return endObserved && !overflow;
        }

        private byte[] digest() {
            return digest.digest();
        }
    }
}
