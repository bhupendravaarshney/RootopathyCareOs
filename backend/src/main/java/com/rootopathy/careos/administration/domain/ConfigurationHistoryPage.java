package com.rootopathy.careos.administration.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ConfigurationHistoryPage(
    UUID organizationId,
    Instant asOf,
    List<Item> items,
    int pageSize,
    boolean hasMore,
    String nextCursor) {
  public record Item(
      UUID configurationId,
      String displayNumber,
      UUID parentVersionId,
      String status,
      String changeSummary,
      String reasonProjection,
      UUID makerId,
      UUID checkerId,
      UUID activatorId,
      Instant requestedEffectiveAt,
      Instant activatedAt,
      Instant supersededAt,
      String resultDigest,
      String gateCatalogueVersion,
      String approvalPolicyVersion,
      String correlationId,
      long lockVersion,
      Instant recordedAt,
      List<Change> changes) {}

  public record Change(
      String subjectType,
      UUID subjectId,
      long baselineRevision,
      long newRevision,
      String changeType) {}
}
