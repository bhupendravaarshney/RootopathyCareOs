package com.rootopathy.careos.platform.infrastructure;

import com.rootopathy.careos.platform.application.CapabilityProbe;
import com.rootopathy.careos.platform.application.DocumentStorageException;
import com.rootopathy.careos.platform.application.PrivateDocumentStoragePort;
import com.rootopathy.careos.platform.application.QuarantinedDocumentContent;
import com.rootopathy.careos.platform.application.QuarantinedDocumentContentSourcePort;
import com.rootopathy.careos.platform.domain.CapabilityAvailability;
import com.rootopathy.careos.platform.domain.CapabilityStatus;
import com.rootopathy.careos.platform.domain.DocumentObjectReference;
import com.rootopathy.careos.platform.domain.DocumentQuarantineRequest;
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
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * S3-compatible private quarantine. It deliberately implements neither scanning nor promotion;
 * content cannot leave quarantine through this adapter.
 */
public final class S3PrivateDocumentStorageAdapter
        implements PrivateDocumentStoragePort,
                QuarantinedDocumentContentSourcePort,
                CapabilityProbe,
                AutoCloseable {
    private static final String READY = "s3-quarantine-adapter-ready";
    private static final String NOT_INITIALIZED = "s3-quarantine-adapter-not-initialized";
    private static final String STORAGE_UNAVAILABLE = "document-storage-unavailable";
    private static final String TOO_LARGE = "document-upload-too-large";
    private static final String LENGTH_MISMATCH = "document-content-length-mismatch";
    private static final String DIGEST_MISMATCH = "document-content-digest-mismatch";
    private static final String OBJECT_CONFLICT = "document-object-conflict";
    private static final String CLEANUP_FAILED = "document-quarantine-cleanup-failed";
    private static final String TENANT_MISMATCH = "document-quarantine-tenant-mismatch";
    private static final String INVALID_QUARANTINE = "document-quarantine-metadata-invalid";
    private static final Set<String> NO_POLICY_CODES = Set.of("NoSuchBucketPolicy", "NoSuchPolicy");
    private static final Set<String> NOT_FOUND_CODES = Set.of("NoSuchKey", "NoSuchObject");
    private static final Set<String> PRECONDITION_CODES = Set.of("PreconditionFailed", "ConditionalRequestConflict");

    private final MinioClient client;
    private final String bucket;
    private final long maximumUploadBytes;
    private final boolean createBucketIfMissing;
    private volatile boolean initialized;

    public S3PrivateDocumentStorageAdapter(
            MinioClient client, String bucket, long maximumUploadBytes, boolean createBucketIfMissing) {
        this.client = Objects.requireNonNull(client, "client");
        this.bucket = Objects.requireNonNull(bucket, "bucket");
        this.maximumUploadBytes = maximumUploadBytes;
        this.createBucketIfMissing = createBucketIfMissing;
    }

    public synchronized void initialize() {
        try {
            var exists = client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
            if (!exists && createBucketIfMissing) {
                client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                exists = true;
            }
            if (!exists) {
                throw new IllegalStateException("Dedicated S3 quarantine bucket is missing");
            }
            requireBucketWithoutPolicy();
            initialized = true;
        } catch (MinioException exception) {
            throw new IllegalStateException("S3 quarantine storage initialization failed", exception);
        }
    }

    @Override
    public DocumentObjectReference quarantine(
            AuthorizedTenantContext context,
            DocumentQuarantineRequest request,
            InputStream content) {
        requireInitialized();
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(content, "content");
        if (request.declaredBytes() > maximumUploadBytes) {
            throw new DocumentStorageException(TOO_LARGE);
        }

        var reference = new DocumentObjectReference(
                context.organizationId(), request.documentId(), request.objectVersionId());
        var objectKey = objectKey(reference);
        var existing = statIfPresent(objectKey);
        if (existing != null) {
            requireMatchingExistingObject(existing, objectKey, request);
            return reference;
        }

        var verifiedContent = new ExactLengthDigestInputStream(content, request.declaredBytes());
        try {
            client.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .stream(verifiedContent, request.declaredBytes(), -1L)
                    .contentType(request.mediaType())
                    .headers(Map.of("If-None-Match", "*"))
                    .userMetadata(Map.of(
                            "careos-sha256", request.sha256(),
                            "careos-state", "quarantine"))
                    .build());
        } catch (ErrorResponseException exception) {
            if (PRECONDITION_CODES.contains(exception.errorResponse().code())) {
                var concurrent = statIfPresent(objectKey);
                if (concurrent != null) {
                    requireMatchingExistingObject(concurrent, objectKey, request);
                    return reference;
                }
                throw new DocumentStorageException(OBJECT_CONFLICT);
            }
            if (verifiedContent.lengthMismatch()) {
                throw new DocumentStorageException(LENGTH_MISMATCH);
            }
            throw new DocumentStorageException(STORAGE_UNAVAILABLE);
        } catch (MinioException exception) {
            if (verifiedContent.lengthMismatch()) {
                throw new DocumentStorageException(LENGTH_MISMATCH);
            }
            throw new DocumentStorageException(STORAGE_UNAVAILABLE);
        }

        if (!verifiedContent.hasExactLength()) {
            removeInvalidObject(objectKey);
            throw new DocumentStorageException(LENGTH_MISMATCH);
        }
        if (!MessageDigest.isEqual(
                HexFormat.of().parseHex(request.sha256()), verifiedContent.digest())) {
            removeInvalidObject(objectKey);
            throw new DocumentStorageException(DIGEST_MISMATCH);
        }
        return reference;
    }

    @Override
    public QuarantinedDocumentContent open(
            AuthorizedTenantContext context, DocumentObjectReference document) {
        requireInitialized();
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(document, "document");
        if (!context.organizationId().equals(document.organizationId())) {
            throw new DocumentStorageException(TENANT_MISMATCH);
        }

        var objectKey = objectKey(document);
        var existing = statIfPresent(objectKey);
        if (existing == null) {
            throw new DocumentStorageException(STORAGE_UNAVAILABLE);
        }
        var state = singleMetadata(existing, "careos-state");
        var sha256 = singleMetadata(existing, "careos-sha256");
        if (!"quarantine".equals(state)
                || sha256 == null
                || !sha256.matches("[0-9a-f]{64}")
                || existing.size() < 1
                || existing.size() > maximumUploadBytes
                || existing.etag() == null
                || existing.etag().isBlank()) {
            throw new DocumentStorageException(INVALID_QUARANTINE);
        }

        try {
            var stored = client.getObject(GetObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .matchETag(existing.etag())
                    .build());
            return new QuarantinedDocumentContent(stored, existing.size(), sha256);
        } catch (MinioException exception) {
            throw new DocumentStorageException(STORAGE_UNAVAILABLE);
        }
    }

    @Override
    public CapabilityStatus status() {
        return initialized
                ? new CapabilityStatus(
                        PlatformCapability.PRIVATE_DOCUMENT_QUARANTINE,
                        CapabilityAvailability.AVAILABLE,
                        READY)
                : new CapabilityStatus(
                        PlatformCapability.PRIVATE_DOCUMENT_QUARANTINE,
                        CapabilityAvailability.UNAVAILABLE,
                        NOT_INITIALIZED);
    }

    @Override
    public void close() {
        initialized = false;
        try {
            client.close();
        } catch (Exception exception) {
            throw new IllegalStateException("S3 quarantine client shutdown failed", exception);
        }
    }

    private void requireBucketWithoutPolicy() throws MinioException {
        try {
            var policy = client.getBucketPolicy(
                    GetBucketPolicyArgs.builder().bucket(bucket).build());
            if (policy != null && !policy.isBlank()) {
                throw new IllegalStateException("Dedicated S3 quarantine bucket must not have a bucket policy");
            }
        } catch (ErrorResponseException exception) {
            if (!NO_POLICY_CODES.contains(exception.errorResponse().code())) {
                throw exception;
            }
        }
    }

    private StatObjectResponse statIfPresent(String objectKey) {
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

    private void requireMatchingExistingObject(
            StatObjectResponse existing, String objectKey, DocumentQuarantineRequest request) {
        if (existing.size() != request.declaredBytes()
                || !request.mediaType().equals(existing.contentType())) {
            throw new DocumentStorageException(OBJECT_CONFLICT);
        }
        try (var stored = client.getObject(
                GetObjectArgs.builder().bucket(bucket).object(objectKey).build())) {
            var digest = sha256();
            var buffer = new byte[8192];
            long bytesRead = 0;
            int read;
            while ((read = stored.read(buffer)) != -1) {
                bytesRead += read;
                if (bytesRead > request.declaredBytes()) {
                    throw new DocumentStorageException(OBJECT_CONFLICT);
                }
                digest.update(buffer, 0, read);
            }
            if (bytesRead != request.declaredBytes()
                    || !MessageDigest.isEqual(
                            HexFormat.of().parseHex(request.sha256()), digest.digest())) {
                throw new DocumentStorageException(OBJECT_CONFLICT);
            }
        } catch (DocumentStorageException exception) {
            throw exception;
        } catch (MinioException | IOException exception) {
            throw new DocumentStorageException(STORAGE_UNAVAILABLE);
        }
    }

    private static String singleMetadata(StatObjectResponse existing, String name) {
        var values = existing.userMetadata().get(name);
        if (values == null || values.size() != 1) {
            return null;
        }
        return values.iterator().next();
    }

    private void removeInvalidObject(String objectKey) {
        try {
            client.removeObject(
                    RemoveObjectArgs.builder().bucket(bucket).object(objectKey).build());
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
        return "organizations/%s/documents/%s/versions/%s/quarantine"
                .formatted(
                        reference.organizationId(),
                        reference.documentId(),
                        reference.objectVersionId());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static final class ExactLengthDigestInputStream extends FilterInputStream {
        private final long expectedBytes;
        private final MessageDigest digest = sha256();
        private long bytesRead;
        private boolean endObserved;
        private boolean overflow;

        private ExactLengthDigestInputStream(InputStream delegate, long expectedBytes) {
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
                throw new IOException("Content exceeds its declared length");
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
            // The caller owns the source stream. Some clients close upload wrappers eagerly.
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

        private boolean lengthMismatch() {
            return overflow || (endObserved && bytesRead != expectedBytes);
        }

        private byte[] digest() {
            return digest.digest();
        }
    }
}
