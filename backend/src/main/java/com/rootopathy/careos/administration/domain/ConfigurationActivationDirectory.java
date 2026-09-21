package com.rootopathy.careos.administration.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ConfigurationActivationDirectory(
    UUID organizationId, boolean canValidate, boolean canSubmit, boolean canApprove,
    boolean canActivate, List<PendingChange> pendingChanges, List<Configuration> configurations, Instant evaluatedAt) {
  public record PendingChange(String subjectType,UUID subjectId,long expectedRevision,String changeType,String label,String currentStatus) {}
  public record Configuration(UUID configurationId, String displayNumber, UUID parentVersionId,
      String baselineDigest, String changeSummary, Instant requestedEffectiveAt, String status,
      long lockVersion, UUID makerId, boolean canSubmit, boolean canApprove, boolean canActivate,
      UUID validationResultId, String resultDigest,
      int blockerCount, int warningCount, Instant validationExpiresAt, UUID approvalId,
      String decision, UUID checkerId, Instant approvalExpiresAt, Instant activatedAt) {}
}
