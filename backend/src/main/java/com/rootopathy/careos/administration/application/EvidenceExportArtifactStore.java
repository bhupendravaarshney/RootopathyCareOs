package com.rootopathy.careos.administration.application;

import com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/** Private, encrypted artifact boundary. Provider paths never cross the administration API. */
public interface EvidenceExportArtifactStore {
  boolean available();

  StoredArtifact store(
      AuthorizedTenantContext context,
      UUID exportId,
      String contentType,
      String filename,
      byte[] content,
      String sha256);

  AccessGrant createReadGrant(
      AuthorizedTenantContext context,
      UUID exportId,
      String artifactReference,
      String expectedSha256,
      String filename,
      Duration ttl);

  void delete(
      AuthorizedTenantContext context,
      UUID exportId,
      String artifactReference,
      String expectedSha256);

  record StoredArtifact(String opaqueReference, String sha256, long byteCount) {}

  record AccessGrant(URI readUri, Instant expiresAt) {}
}
