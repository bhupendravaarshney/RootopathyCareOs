package com.rootopathy.careos.workforce.application;

import com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface WorkforceExportStore {
    List<UUID> dueExportIds(
            AuthorizedTenantContext context, Instant now, int maximumItems);

    Work claim(AuthorizedTenantContext context, UUID exportId, String workerId);

    List<Map<String, Object>> rows(
            AuthorizedTenantContext context, Work work, int maximumRows);

    Work ready(
            AuthorizedTenantContext context,
            Work work,
            String artifactReference,
            String artifactDigest,
            String contentType,
            String filename,
            int rowCount,
            long byteCount);

    Work failed(
            AuthorizedTenantContext context,
            Work work,
            String failureCode);

    Access access(
            AuthorizedTenantContext context,
            UUID exportId,
            long revision,
            String purposeKey,
            Instant maximumGrantExpiry);

    Download download(AuthorizedTenantContext context, UUID exportId);

    void requireSourceAccess(AuthorizedTenantContext context, UUID exportId);

    Work expire(AuthorizedTenantContext context, UUID exportId, long revision);

    Work forDisposal(AuthorizedTenantContext context, UUID exportId);

    Work forDisposal(AuthorizedTenantContext context, UUID exportId, long revision);

    Work disposed(AuthorizedTenantContext context, UUID exportId, long revision);

    record Work(
            UUID exportId,
            UUID requesterId,
            String projection,
            String format,
            String filtersJson,
            String filterDigest,
            String purposeKey,
            String status,
            Instant snapshotAt,
            int rowLimit,
            long sizeLimitBytes,
            int attemptCount,
            long revision,
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
            String artifactDigest,
            String contentType,
            String filename,
            Integer rowCount,
            Instant grantExpiresAt,
            long revision) {}

    record Download(
            UUID exportId,
            String artifactReference,
            String artifactDigest,
            String contentType,
            String filename,
            long byteCount,
            Instant grantExpiresAt) {}
}
