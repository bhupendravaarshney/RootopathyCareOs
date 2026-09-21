package com.rootopathy.careos.administration.application;

import com.rootopathy.careos.administration.domain.EvidenceExportDirectory;
import com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface EvidenceExportStore {
  EvidenceExportDirectory directory(AuthorizedTenantContext context, boolean audit);
  Result request(AuthorizedTenantContext context, Draft draft);
  Result decide(
      AuthorizedTenantContext context, UUID exportId, long revision, boolean authorize, String reason);
  Work claim(AuthorizedTenantContext context, UUID exportId, String workerId);
  List<Map<String, Object>> rows(AuthorizedTenantContext context, Work work, int maximumRows);
  Work ready(
      AuthorizedTenantContext context,
      Work work,
      String artifactReference,
      String artifactDigest,
      String contentType,
      String filename,
      int rowCount,
      long byteCount);
  Work generationFailed(
      AuthorizedTenantContext context, Work work, String failureCode, boolean retryable);
  Access access(AuthorizedTenantContext context, UUID exportId, long revision, String purposeCode);
  Work accessForDisposal(AuthorizedTenantContext context, UUID exportId, long revision);
  Work expire(AuthorizedTenantContext context, UUID exportId, long revision);
  Work dispose(AuthorizedTenantContext context, UUID exportId, long revision);

  record Draft(
      String projection,
      String format,
      String filtersJson,
      String filterDigest,
      String purposeCode,
      String legalBasisKey,
      String reason,
      boolean restricted) {}

  record Result(
      UUID exportId,
      String projection,
      String format,
      String filterDigest,
      String purposeCode,
      UUID approvalId,
      String status,
      long lockVersion,
      EvidenceExportDirectory directory) {}

  record Work(
      UUID exportId,
      UUID requesterId,
      String projection,
      String format,
      String filtersJson,
      String filterDigest,
      String purposeCode,
      String status,
      Instant snapshotTime,
      int attemptCount,
      long lockVersion,
      String artifactReference,
      String artifactDigest,
      String contentType,
      String filename,
      Integer rowCount,
      Long byteCount,
      Instant expiresAt,
      boolean legalHold) {}

  record Access(
      UUID exportId,
      String artifactReference,
      String artifactDigest,
      String contentType,
      String filename,
      Instant expiresAt,
      long lockVersion) {}
}
