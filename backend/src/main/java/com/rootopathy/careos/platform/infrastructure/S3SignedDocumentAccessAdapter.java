package com.rootopathy.careos.platform.infrastructure;

import com.rootopathy.careos.platform.application.CapabilityProbe;
import com.rootopathy.careos.platform.application.DocumentStorageException;
import com.rootopathy.careos.platform.application.SignedDocumentAccessPort;
import com.rootopathy.careos.platform.domain.CapabilityAvailability;
import com.rootopathy.careos.platform.domain.CapabilityStatus;
import com.rootopathy.careos.platform.domain.DocumentAccessAuthorization;
import com.rootopathy.careos.platform.domain.DocumentAccessPolicy;
import com.rootopathy.careos.platform.domain.PlatformCapability;
import com.rootopathy.careos.platform.domain.SignedDocumentAccess;
import com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext;
import io.minio.BucketExistsArgs;
import io.minio.GetBucketPolicyArgs;
import io.minio.GetObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.Http;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.errors.ErrorResponseException;
import io.minio.errors.MinioException;
import java.io.IOException;
import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Signs a bounded read URL only after re-verifying the exact promoted private object. */
public final class S3SignedDocumentAccessAdapter
        implements SignedDocumentAccessPort, CapabilityProbe, AutoCloseable {
    private static final String READY = "s3-signed-document-access-adapter-ready";
    private static final String NOT_INITIALIZED = "s3-signed-document-access-adapter-not-initialized";
    private static final String STORAGE_UNAVAILABLE = "document-access-storage-unavailable";
    private static final String TENANT_MISMATCH = "document-access-tenant-mismatch";
    private static final String PURPOSE_MISMATCH = "document-access-purpose-mismatch";
    private static final String POLICY_MISMATCH = "document-access-policy-mismatch";
    private static final String AUTHORIZATION_EXPIRED = "document-access-authorization-expired";
    private static final String CLEAN_OBJECT_MISSING = "document-access-clean-object-not-found";
    private static final String CLEAN_OBJECT_INVALID = "document-access-clean-object-invalid";
    private static final String URL_INVALID = "document-access-signed-url-invalid";
    private static final Set<String> NO_POLICY_CODES = Set.of("NoSuchBucketPolicy", "NoSuchPolicy");
    private static final Set<String> NOT_FOUND_CODES = Set.of("NoSuchKey", "NoSuchObject");

    private final MinioClient client;
    private final URI endpoint;
    private final String cleanBucket;
    private final long maximumObjectBytes;
    private final boolean createBucketIfMissing;
    private final DocumentAccessPolicy policy;
    private final Clock clock;
    private volatile boolean initialized;

    public S3SignedDocumentAccessAdapter(
            MinioClient client,
            URI endpoint,
            String cleanBucket,
            long maximumObjectBytes,
            boolean createBucketIfMissing,
            DocumentAccessPolicy policy,
            Clock clock) {
        this.client = Objects.requireNonNull(client, "client");
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.cleanBucket = Objects.requireNonNull(cleanBucket, "cleanBucket");
        this.maximumObjectBytes = maximumObjectBytes;
        this.createBucketIfMissing = createBucketIfMissing;
        this.policy = Objects.requireNonNull(policy, "policy");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public synchronized void initialize() {
        initialized = false;
        try {
            var exists = client.bucketExists(
                    BucketExistsArgs.builder().bucket(cleanBucket).build());
            if (!exists && createBucketIfMissing) {
                client.makeBucket(MakeBucketArgs.builder().bucket(cleanBucket).build());
                exists = true;
            }
            if (!exists) {
                throw new IllegalStateException("Dedicated S3 clean document bucket is missing");
            }
            try {
                var bucketPolicy = client.getBucketPolicy(
                        GetBucketPolicyArgs.builder().bucket(cleanBucket).build());
                if (bucketPolicy != null && !bucketPolicy.isBlank()) {
                    throw new IllegalStateException(
                            "Dedicated S3 clean document bucket must not have a bucket policy");
                }
            } catch (ErrorResponseException exception) {
                if (!NO_POLICY_CODES.contains(exception.errorResponse().code())) {
                    throw exception;
                }
            }
            initialized = true;
        } catch (MinioException exception) {
            throw new IllegalStateException("S3 signed document access initialization failed", exception);
        }
    }

    @Override
    public SignedDocumentAccess createReadAccess(
            AuthorizedTenantContext context, DocumentAccessAuthorization authorization) {
        requireInitialized();
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(authorization, "authorization");
        var document = authorization.document();
        if (!context.organizationId().equals(document.organizationId())) {
            throw new DocumentStorageException(TENANT_MISMATCH);
        }
        if (!context.purpose().equals(authorization.purpose())
                || !policy.acceptsPurpose(context.purpose())) {
            throw new DocumentStorageException(PURPOSE_MISMATCH);
        }
        if (!policy.policyKey().equals(authorization.policyKey())
                || !policy.acceptedPurposes().equals(authorization.acceptedPurposes())
                || !policy.maximumTtl().equals(authorization.maximumTtl())
                || !policy.maximumAuthorizationAge()
                        .equals(authorization.maximumAuthorizationAge())
                || !policy.maximumFutureSkew().equals(authorization.maximumFutureSkew())) {
            throw new DocumentStorageException(POLICY_MISMATCH);
        }
        var now = clock.instant();
        if (authorization.authorizedAt().isBefore(now.minus(policy.maximumAuthorizationAge()))
                || authorization.authorizedAt().isAfter(now.plus(policy.maximumFutureSkew()))
                || !authorization.expiresAt().isAfter(now)
                || authorization.promotionEvidence().promotedAt()
                        .isAfter(now.plus(policy.maximumFutureSkew()))) {
            throw new DocumentStorageException(AUTHORIZATION_EXPIRED);
        }

        var objectKey = S3DocumentPromotionAdapter.objectKey(document);
        var clean = statIfPresent(objectKey);
        if (clean == null) {
            throw new DocumentStorageException(CLEAN_OBJECT_MISSING);
        }
        verifyCleanObject(clean, objectKey, authorization);

        try {
            var rawUrl = client.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Http.Method.GET)
                    .bucket(cleanBucket)
                    .object(objectKey)
                    .expiry(Math.toIntExact(authorization.requestedTtl().toSeconds()))
                    .build());
            var readUrl = URI.create(rawUrl);
            requireExpectedOrigin(readUrl);
            return new SignedDocumentAccess(
                    authorization.accessGrantId(), document, readUrl, authorization.expiresAt());
        } catch (DocumentStorageException exception) {
            throw exception;
        } catch (MinioException | IllegalArgumentException | ArithmeticException exception) {
            throw new DocumentStorageException(URL_INVALID);
        }
    }

    @Override
    public CapabilityStatus status() {
        return initialized
                ? new CapabilityStatus(
                        PlatformCapability.SIGNED_DOCUMENT_ACCESS,
                        CapabilityAvailability.AVAILABLE,
                        READY)
                : new CapabilityStatus(
                        PlatformCapability.SIGNED_DOCUMENT_ACCESS,
                        CapabilityAvailability.UNAVAILABLE,
                        NOT_INITIALIZED);
    }

    @Override
    public void close() {
        initialized = false;
        try {
            client.close();
        } catch (Exception exception) {
            throw new IllegalStateException("S3 signed document access client shutdown failed", exception);
        }
    }

    private void verifyCleanObject(
            StatObjectResponse clean,
            String objectKey,
            DocumentAccessAuthorization authorization) {
        var expectedSha256 = authorization.promotionEvidence()
                .scanAttestation()
                .result()
                .sha256();
        if (clean.size() < 1
                || clean.size() > maximumObjectBytes
                || clean.etag() == null
                || clean.etag().isBlank()
                || clean.contentType() == null
                || clean.contentType().isBlank()
                || !"clean".equals(singleMetadata(clean, "careos-state"))
                || !expectedSha256.equals(singleMetadata(clean, "careos-sha256"))) {
            throw new DocumentStorageException(CLEAN_OBJECT_INVALID);
        }
        try (var stored = client.getObject(GetObjectArgs.builder()
                .bucket(cleanBucket)
                .object(objectKey)
                .matchETag(clean.etag())
                .build())) {
            var digest = sha256();
            var buffer = new byte[8192];
            long bytesRead = 0;
            int read;
            while ((read = stored.read(buffer)) != -1) {
                bytesRead += read;
                if (bytesRead > clean.size()) {
                    throw new DocumentStorageException(CLEAN_OBJECT_INVALID);
                }
                digest.update(buffer, 0, read);
            }
            if (bytesRead != clean.size()
                    || !MessageDigest.isEqual(
                            HexFormat.of().parseHex(expectedSha256), digest.digest())) {
                throw new DocumentStorageException(CLEAN_OBJECT_INVALID);
            }
        } catch (DocumentStorageException exception) {
            throw exception;
        } catch (MinioException | IOException exception) {
            throw new DocumentStorageException(STORAGE_UNAVAILABLE);
        }
    }

    private StatObjectResponse statIfPresent(String objectKey) {
        try {
            return client.statObject(StatObjectArgs.builder()
                    .bucket(cleanBucket)
                    .object(objectKey)
                    .build());
        } catch (ErrorResponseException exception) {
            if (NOT_FOUND_CODES.contains(exception.errorResponse().code())) {
                return null;
            }
            throw new DocumentStorageException(STORAGE_UNAVAILABLE);
        } catch (MinioException exception) {
            throw new DocumentStorageException(STORAGE_UNAVAILABLE);
        }
    }

    private void requireExpectedOrigin(URI readUrl) {
        if (!readUrl.isAbsolute()
                || readUrl.getHost() == null
                || readUrl.getUserInfo() != null
                || readUrl.getFragment() != null
                || readUrl.getRawQuery() == null
                || !endpoint.getScheme().equalsIgnoreCase(readUrl.getScheme())
                || !endpoint.getHost().toLowerCase(Locale.ROOT)
                        .equals(readUrl.getHost().toLowerCase(Locale.ROOT))
                || effectivePort(endpoint) != effectivePort(readUrl)) {
            throw new DocumentStorageException(URL_INVALID);
        }
    }

    private void requireInitialized() {
        if (!initialized) {
            throw new DocumentStorageException(STORAGE_UNAVAILABLE);
        }
    }

    private static int effectivePort(URI value) {
        if (value.getPort() >= 0) {
            return value.getPort();
        }
        return "https".equalsIgnoreCase(value.getScheme()) ? 443 : 80;
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
}
