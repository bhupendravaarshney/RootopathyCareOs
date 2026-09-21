package com.rootopathy.careos.administration.infrastructure;

import com.rootopathy.careos.administration.application.EvidenceExportArtifactStore;
import com.rootopathy.careos.shared.domain.UuidV7Generator;
import com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext;
import io.minio.*;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class S3EvidenceExportArtifactStore implements EvidenceExportArtifactStore, AutoCloseable {
  private final MinioClient storageClient;
  private final MinioClient signingClient;
  private final String bucket;
  private final boolean createBucket;
  private final Clock clock;

  public S3EvidenceExportArtifactStore(
      MinioClient client, String bucket, boolean createBucket, Clock clock) {
    this(client, client, bucket, createBucket, clock);
  }

  public S3EvidenceExportArtifactStore(
      MinioClient storageClient,
      MinioClient signingClient,
      String bucket,
      boolean createBucket,
      Clock clock) {
    this.storageClient = Objects.requireNonNull(storageClient);
    this.signingClient = Objects.requireNonNull(signingClient);
    this.bucket = Objects.requireNonNull(bucket);
    this.createBucket = createBucket;
    this.clock = Objects.requireNonNull(clock);
  }

  public void initialize() {
    try {
      if (!storageClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
        if (!createBucket) throw new IllegalStateException("Evidence export bucket is missing");
        storageClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
      }
      var policy = storageClient.getBucketPolicy(GetBucketPolicyArgs.builder().bucket(bucket).build());
      if (policy != null && !policy.isBlank()) {
        throw new IllegalStateException("Evidence export bucket must not have a public policy");
      }
    } catch (io.minio.errors.ErrorResponseException exception) {
      if (!"NoSuchBucketPolicy".equals(exception.errorResponse().code())) {
        throw new IllegalStateException("Evidence export storage initialization failed", exception);
      }
    } catch (Exception exception) {
      throw new IllegalStateException("Evidence export storage initialization failed", exception);
    }
  }

  @Override
  public boolean available() {
    return true;
  }

  @Override
  public StoredArtifact store(
      AuthorizedTenantContext context,
      UUID exportId,
      String contentType,
      String filename,
      byte[] content,
      String sha256) {
    var reference = UuidV7Generator.randomUuid().toString();
    var key = objectKey(context, exportId, reference);
    try (var input = new ByteArrayInputStream(content)) {
      storageClient.putObject(PutObjectArgs.builder()
          .bucket(bucket)
          .object(key)
          .stream(input, (long) content.length, -1L)
          .contentType(contentType)
          .sse(new ServerSideEncryption.S3())
          .userMetadata(Map.of(
              "careos-sha256", sha256,
              "careos-export-id", exportId.toString(),
              "careos-filename", filename))
          .build());
      var stat = stat(key);
      requireArtifact(stat, exportId, sha256, (long) content.length);
      return new StoredArtifact(reference, sha256, stat.size());
    } catch (Exception exception) {
      try {
        storageClient.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build());
      } catch (Exception cleanup) {
        exception.addSuppressed(cleanup);
      }
      throw new IllegalStateException("Evidence export artifact storage failed", exception);
    }
  }

  @Override
  public AccessGrant createReadGrant(
      AuthorizedTenantContext context,
      UUID exportId,
      String artifactReference,
      String expectedSha256,
      String filename,
      Duration ttl) {
    if (ttl.isZero() || ttl.isNegative() || ttl.compareTo(Duration.ofMinutes(10)) > 0) {
      throw new IllegalArgumentException("Evidence export access TTL is invalid");
    }
    var key = objectKey(context, exportId, artifactReference);
    try {
      requireArtifact(stat(key), exportId, expectedSha256, null);
      var url = signingClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
          .method(Http.Method.GET)
          .bucket(bucket)
          .object(key)
          .expiry(Math.toIntExact(ttl.toSeconds()))
          .extraQueryParams(Map.of("response-content-disposition", "attachment; filename=\"" + filename + "\""))
          .build());
      return new AccessGrant(URI.create(url), clock.instant().plus(ttl));
    } catch (Exception exception) {
      throw new IllegalStateException("Evidence export access grant failed", exception);
    }
  }

  @Override
  public void delete(
      AuthorizedTenantContext context,
      UUID exportId,
      String artifactReference,
      String expectedSha256) {
    var key = objectKey(context, exportId, artifactReference);
    try {
      try {
        requireArtifact(stat(key), exportId, expectedSha256, null);
      } catch (io.minio.errors.ErrorResponseException exception) {
        if (missing(exception)) return;
        throw exception;
      }
      storageClient.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build());
      try {
        stat(key);
        throw new IllegalStateException("Evidence export artifact still exists after disposal");
      } catch (io.minio.errors.ErrorResponseException exception) {
        if (!missing(exception)) throw exception;
      }
    } catch (Exception exception) {
      throw new IllegalStateException("Evidence export artifact disposal failed", exception);
    }
  }

  private StatObjectResponse stat(String key) throws Exception {
    return storageClient.statObject(StatObjectArgs.builder().bucket(bucket).object(key).build());
  }

  private static boolean missing(io.minio.errors.ErrorResponseException exception) {
    var code = exception.errorResponse() == null ? null : exception.errorResponse().code();
    return "NoSuchKey".equals(code) || "NoSuchObject".equals(code) || "NotFound".equals(code);
  }

  private static void requireArtifact(
      StatObjectResponse stat, UUID exportId, String digest, Long expectedBytes) {
    var metadata = stat.userMetadata();
    if (!digest.equals(single(metadata.get("careos-sha256")))
        || !exportId.toString().equals(single(metadata.get("careos-export-id")))
        || (expectedBytes != null && stat.size() != expectedBytes)) {
      throw new IllegalStateException("Evidence export artifact evidence does not match");
    }
  }

  private static String single(java.util.Collection<String> values) {
    return values != null && values.size() == 1 ? values.iterator().next() : null;
  }

  private static String objectKey(
      AuthorizedTenantContext context, UUID exportId, String artifactReference) {
    UUID.fromString(artifactReference);
    return "organizations/" + context.organizationId() + "/exports/" + exportId + "/" + artifactReference;
  }

  @Override
  public void close() throws Exception {
    Exception failure = null;
    try {
      storageClient.close();
    } catch (Exception exception) {
      failure = exception;
    }
    if (signingClient != storageClient) {
      try {
        signingClient.close();
      } catch (Exception exception) {
        if (failure == null) failure = exception;
        else failure.addSuppressed(exception);
      }
    }
    if (failure != null) throw failure;
  }
}
