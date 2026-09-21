package com.rootopathy.careos.administration.application;

import com.rootopathy.careos.administration.domain.AdministrationReadiness;
import com.rootopathy.careos.administration.domain.ConfigurationActivationDirectory;
import com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext;
import java.time.Instant;
import java.util.UUID;

public interface ConfigurationActivationStore {
  ConfigurationActivationDirectory directory(AuthorizedTenantContext context);
  Baseline baseline(AuthorizedTenantContext context, UUID configurationId, long expectedRevision);
  ActivationEvidence activationEvidence(AuthorizedTenantContext context, UUID configurationId);
  java.util.Set<String> candidateSatisfiedGates(AuthorizedTenantContext context, java.util.List<ChangeItem> changes);
  ValidationResult validate(AuthorizedTenantContext context, ValidationDraft draft,
      AdministrationReadiness readiness, String gatesJson, String resultDigest);
  Result submit(AuthorizedTenantContext context, UUID configurationId, long revision,
      UUID resultId, String resultDigest);
  DecisionResult decide(AuthorizedTenantContext context, UUID configurationId, long revision,
      UUID resultId, String resultDigest, boolean approve, String decisionCode, String reason);
  Result activate(AuthorizedTenantContext context, UUID configurationId, long revision,
      UUID approvalId, String resultDigest, Instant effectiveFrom);

  record Baseline(UUID configurationId, boolean newConfiguration, UUID parentVersionId,
      String parentDigest, long baselineRevision, String canonicalState) {}
  record ActivationEvidence(String baselineDigest, long baselineRevision, UUID parentVersionId,
      String parentDigest, String canonicalState) {}
  record ChangeItem(String subjectType,UUID subjectId,long expectedRevision,String changeType) {}
  record ValidationDraft(UUID configurationId, boolean newConfiguration, long expectedRevision, UUID parentVersionId,
      String parentDigest, long baselineRevision, String baselineDigest, String changeSummary,
      String reason, Instant requestedEffectiveAt, java.util.List<ChangeItem> changeItems) {}
  record ValidationResult(UUID configurationId, UUID resultId, String resultDigest,
      int blockerCount, int warningCount, Instant expiresAt, long lockVersion,
      ConfigurationActivationDirectory directory) {}
  record Result(UUID configurationId, UUID resultId, String resultDigest, long lockVersion,
      ConfigurationActivationDirectory directory) {}
  record DecisionResult(UUID configurationId, UUID approvalId, String resultDigest,
      String decisionCode, long lockVersion, ConfigurationActivationDirectory directory) {}
}
