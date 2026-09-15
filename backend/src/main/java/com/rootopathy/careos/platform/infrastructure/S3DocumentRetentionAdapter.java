package com.rootopathy.careos.platform.infrastructure;

import com.rootopathy.careos.platform.application.CapabilityProbe;
import com.rootopathy.careos.platform.application.DocumentRetentionPort;
import com.rootopathy.careos.platform.application.DocumentStorageException;
import com.rootopathy.careos.platform.domain.CapabilityAvailability;
import com.rootopathy.careos.platform.domain.CapabilityStatus;
import com.rootopathy.careos.platform.domain.DocumentRetentionAuthorization;
import com.rootopathy.careos.platform.domain.DocumentRetentionPolicy;
import com.rootopathy.careos.platform.domain.DocumentRetentionReceipt;
import com.rootopathy.careos.platform.domain.PlatformCapability;
import com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext;
import io.minio.BucketExistsArgs;
import io.minio.EnableObjectLegalHoldArgs;
import io.minio.GetBucketPolicyArgs;
import io.minio.GetBucketVersioningArgs;
import io.minio.GetObjectArgs;
import io.minio.GetObjectLockConfigurationArgs;
import io.minio.GetObjectRetentionArgs;
import io.minio.IsObjectLegalHoldEnabledArgs;
import io.minio.MinioClient;
import io.minio.SetObjectRetentionArgs;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.errors.ErrorResponseException;
import io.minio.errors.MinioException;
import io.minio.messages.Retention;
import io.minio.messages.RetentionMode;
import io.minio.messages.VersioningConfiguration;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;

/** Applies non-bypassable S3 Object Lock retention without supporting release or deletion. */
public final class S3DocumentRetentionAdapter
        implements DocumentRetentionPort, CapabilityProbe, AutoCloseable {
    private static final String READY = "s3-document-retention-adapter-ready";
    private static final String NOT_INITIALIZED = "s3-document-retention-adapter-not-initialized";
    private static final String STORAGE_UNAVAILABLE = "document-retention-storage-unavailable";
    private static final String TENANT_MISMATCH = "document-retention-tenant-mismatch";
    private static final String PURPOSE_MISMATCH = "document-retention-purpose-mismatch";
    private static final String POLICY_MISMATCH = "document-retention-policy-mismatch";
    private static final String AUTHORIZATION_EXPIRED = "document-retention-authorization-expired";
    private static final String CLEAN_OBJECT_MISSING = "document-retention-clean-object-not-found";
    private static final String CLEAN_OBJECT_INVALID = "document-retention-clean-object-invalid";
    private static final String RETENTION_CONFLICT = "document-retention-provider-state-conflict";
    private static final String LEGAL_HOLD_RELEASE = "document-legal-hold-release-not-supported";
    private static final Set<String> NO_POLICY_CODES = Set.of("NoSuchBucketPolicy", "NoSuchPolicy");
    private static final Set<String> NOT_FOUND_CODES = Set.of("NoSuchKey", "NoSuchObject");

    private final MinioClient client;
    private final String cleanBucket;
    private final long maximumObjectBytes;
    private final DocumentRetentionPolicy policy;
    private final Clock clock;
    private volatile boolean initialized;

    public S3DocumentRetentionAdapter(
            MinioClient client,
            String cleanBucket,
            long maximumObjectBytes,
            DocumentRetentionPolicy policy,
            Clock clock) {
        this.client = Objects.requireNonNull(client, "client");
        this.cleanBucket = Objects.requireNonNull(cleanBucket, "cleanBucket");
        this.maximumObjectBytes = maximumObjectBytes;
        this.policy = Objects.requireNonNull(policy, "policy");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public synchronized void initialize() {
        initialized = false;
        try {
            if (!client.bucketExists(BucketExistsArgs.builder().bucket(cleanBucket).build())) {
                throw new IllegalStateException(
                        "Dedicated S3 Object Lock clean document bucket is missing");
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
            var versioning = client.getBucketVersioning(
                    GetBucketVersioningArgs.builder().bucket(cleanBucket).build());
            if (versioning == null
                    || versioning.status() != VersioningConfiguration.Status.ENABLED) {
                throw new IllegalStateException(
                        "S3 document retention requires enabled clean-bucket versioning");
            }
            if (client.getObjectLockConfiguration(
                            GetObjectLockConfigurationArgs.builder().bucket(cleanBucket).build())
                    == null) {
                throw new IllegalStateException(
                        "S3 document retention requires clean-bucket Object Lock");
            }
            initialized = true;
        } catch (MinioException exception) {
            throw new IllegalStateException("S3 document retention initialization failed", exception);
        }
    }

    @Override
    public DocumentRetentionReceipt apply(
            AuthorizedTenantContext context, DocumentRetentionAuthorization authorization) {
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
                || !policy.minimumRetention().equals(authorization.minimumRetention())
                || !policy.maximumRetention().equals(authorization.maximumRetention())
                || !policy.maximumAuthorizationAge()
                        .equals(authorization.maximumAuthorizationAge())
                || !policy.maximumFutureSkew().equals(authorization.maximumFutureSkew())) {
            throw new DocumentStorageException(POLICY_MISMATCH);
        }
        var now = clock.instant();
        if (authorization.authorizedAt().isBefore(now.minus(policy.maximumAuthorizationAge()))
                || authorization.authorizedAt().isAfter(now.plus(policy.maximumFutureSkew()))
                || !authorization.retainUntil().isAfter(now)
                || authorization.promotionEvidence().promotedAt()
                        .isAfter(now.plus(policy.maximumFutureSkew()))) {
            throw new DocumentStorageException(AUTHORIZATION_EXPIRED);
        }

        var objectKey = S3DocumentPromotionAdapter.objectKey(document);
        var clean = statIfPresent(objectKey, null);
        if (clean == null) {
            throw new DocumentStorageException(CLEAN_OBJECT_MISSING);
        }
        var versionId = clean.versionId();
        if (versionId == null || versionId.isBlank()) {
            throw new DocumentStorageException(CLEAN_OBJECT_INVALID);
        }
        verifyCleanObject(clean, objectKey, versionId, authorization);

        try {
            var existingRetention = client.getObjectRetention(GetObjectRetentionArgs.builder()
                    .bucket(cleanBucket)
                    .object(objectKey)
                    .versionId(versionId)
                    .build());
            var existingHold = client.isObjectLegalHoldEnabled(
                    IsObjectLegalHoldEnabledArgs.builder()
                            .bucket(cleanBucket)
                            .object(objectKey)
                            .versionId(versionId)
                            .build());
            if (existingHold && !authorization.legalHold()) {
                throw new DocumentStorageException(LEGAL_HOLD_RELEASE);
            }
            var mustApplyRetention = existingRetention == null;
            if (existingRetention != null) {
                var existingUntil = existingRetention
                        .retainUntilDate()
                        .toInstant()
                        .truncatedTo(ChronoUnit.SECONDS);
                if (existingRetention.mode() != RetentionMode.COMPLIANCE
                        || existingUntil.isAfter(authorization.retainUntil())) {
                    throw new DocumentStorageException(RETENTION_CONFLICT);
                }
                mustApplyRetention = existingUntil.isBefore(authorization.retainUntil());
            }
            if (mustApplyRetention) {
                client.setObjectRetention(SetObjectRetentionArgs.builder()
                        .bucket(cleanBucket)
                        .object(objectKey)
                        .versionId(versionId)
                        .config(new Retention(
                                RetentionMode.COMPLIANCE,
                                authorization.retainUntil().atZone(ZoneOffset.UTC)))
                        .bypassGovernanceMode(false)
                        .build());
            }
            if (authorization.legalHold() && !existingHold) {
                client.enableObjectLegalHold(EnableObjectLegalHoldArgs.builder()
                        .bucket(cleanBucket)
                        .object(objectKey)
                        .versionId(versionId)
                        .build());
            }
            verifyAppliedState(objectKey, versionId, clean, authorization);
            return new DocumentRetentionReceipt(
                    document,
                    authorization.retainUntil(),
                    authorization.legalHold(),
                    sha256(versionId.getBytes(StandardCharsets.UTF_8)));
        } catch (DocumentStorageException exception) {
            throw exception;
        } catch (MinioException exception) {
            throw new DocumentStorageException(STORAGE_UNAVAILABLE);
        }
    }

    @Override
    public CapabilityStatus status() {
        return initialized
                ? new CapabilityStatus(
                        PlatformCapability.DOCUMENT_RETENTION,
                        CapabilityAvailability.AVAILABLE,
                        READY)
                : new CapabilityStatus(
                        PlatformCapability.DOCUMENT_RETENTION,
                        CapabilityAvailability.UNAVAILABLE,
                        NOT_INITIALIZED);
    }

    @Override
    public void close() {
        initialized = false;
        try {
            client.close();
        } catch (Exception exception) {
            throw new IllegalStateException("S3 document retention client shutdown failed", exception);
        }
    }

    private void verifyAppliedState(
            String objectKey,
            String versionId,
            StatObjectResponse before,
            DocumentRetentionAuthorization authorization)
            throws MinioException {
        var applied = client.getObjectRetention(GetObjectRetentionArgs.builder()
                .bucket(cleanBucket)
                .object(objectKey)
                .versionId(versionId)
                .build());
        var hold = client.isObjectLegalHoldEnabled(IsObjectLegalHoldEnabledArgs.builder()
                .bucket(cleanBucket)
                .object(objectKey)
                .versionId(versionId)
                .build());
        var exact = statIfPresent(objectKey, versionId);
        var current = statIfPresent(objectKey, null);
        if (applied == null
                || applied.mode() != RetentionMode.COMPLIANCE
                || !applied.retainUntilDate()
                        .toInstant()
                        .truncatedTo(ChronoUnit.SECONDS)
                        .equals(authorization.retainUntil())
                || hold != authorization.legalHold()
                || exact == null
                || current == null
                || !versionId.equals(exact.versionId())
                || !versionId.equals(current.versionId())
                || !Objects.equals(before.etag(), exact.etag())
                || !Objects.equals(before.etag(), current.etag())) {
            throw new DocumentStorageException(RETENTION_CONFLICT);
        }
    }

    private void verifyCleanObject(
            StatObjectResponse clean,
            String objectKey,
            String versionId,
            DocumentRetentionAuthorization authorization) {
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
                .versionId(versionId)
                .matchETag(clean.etag())
                .build())) {
            var digest = sha256Digest();
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

    private StatObjectResponse statIfPresent(String objectKey, String versionId) {
        try {
            var builder = StatObjectArgs.builder().bucket(cleanBucket).object(objectKey);
            if (versionId != null) {
                builder.versionId(versionId);
            }
            return client.statObject(builder.build());
        } catch (ErrorResponseException exception) {
            if (NOT_FOUND_CODES.contains(exception.errorResponse().code())) {
                return null;
            }
            throw new DocumentStorageException(STORAGE_UNAVAILABLE);
        } catch (MinioException exception) {
            throw new DocumentStorageException(STORAGE_UNAVAILABLE);
        }
    }

    private void requireInitialized() {
        if (!initialized) {
            throw new DocumentStorageException(STORAGE_UNAVAILABLE);
        }
    }

    private static String singleMetadata(StatObjectResponse object, String name) {
        var values = object.userMetadata().get(name);
        if (values == null || values.size() != 1) {
            return null;
        }
        return values.iterator().next();
    }

    private static String sha256(byte[] value) {
        return HexFormat.of().formatHex(sha256Digest().digest(value));
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
