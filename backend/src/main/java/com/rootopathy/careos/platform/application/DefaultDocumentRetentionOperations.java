package com.rootopathy.careos.platform.application;

import com.rootopathy.careos.platform.domain.DocumentObjectReference;
import com.rootopathy.careos.platform.domain.DocumentRetentionAuthorization;
import com.rootopathy.careos.platform.domain.DocumentRetentionDirective;
import com.rootopathy.careos.platform.domain.DocumentRetentionEvidence;
import com.rootopathy.careos.platform.domain.DocumentRetentionPolicy;
import com.rootopathy.careos.platform.domain.DocumentRetentionReceipt;
import com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext;
import java.time.Clock;
import java.util.Objects;

public final class DefaultDocumentRetentionOperations implements DocumentRetentionOperations {
    private static final String TENANT_MISMATCH = "document-retention-tenant-mismatch";
    private static final String PURPOSE_REJECTED = "document-retention-purpose-not-approved";
    private static final String POLICY_MISMATCH = "document-retention-policy-mismatch";
    private static final String PROMOTION_MISSING = "document-promotion-evidence-not-found";
    private static final String RETENTION_SHORTENING = "document-retention-shortening-rejected";
    private static final String LEGAL_HOLD_RELEASE = "document-legal-hold-release-not-supported";
    private static final String NO_CHANGE = "document-retention-no-change";
    private static final String DIRECTIVE_REJECTED = "document-retention-directive-rejected";
    private static final String RESPONSE_MISMATCH = "document-retention-response-mismatch";
    private static final String EVIDENCE_CONFLICT = "document-retention-evidence-conflict";

    private final DocumentRetentionPort retention;
    private final DocumentEvidenceOperations evidence;
    private final DocumentRetentionPolicy policy;
    private final Clock clock;

    public DefaultDocumentRetentionOperations(
            DocumentRetentionPort retention,
            DocumentEvidenceOperations evidence,
            DocumentRetentionPolicy policy,
            Clock clock) {
        this.retention = Objects.requireNonNull(retention, "retention");
        this.evidence = Objects.requireNonNull(evidence, "evidence");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public DocumentRetentionEvidence apply(
            AuthorizedTenantContext context,
            DocumentObjectReference document,
            DocumentRetentionDirective directive) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(directive, "directive");
        if (!context.organizationId().equals(document.organizationId())) {
            throw rejected(TENANT_MISMATCH);
        }
        if (!policy.acceptsPurpose(context.purpose())) {
            throw rejected(PURPOSE_REJECTED);
        }
        if (!policy.policyKey().equals(directive.policyKey())) {
            throw rejected(POLICY_MISMATCH);
        }

        var existing = evidence.findRetention(context, document, directive.retentionDirectiveId());
        if (existing.isPresent()) {
            var recorded = existing.orElseThrow();
            if (!matchesReplay(recorded, document, directive, policy, context)) {
                throw rejected(EVIDENCE_CONFLICT);
            }
            return recorded;
        }

        var promotion = evidence.findPromotion(context, document)
                .orElseThrow(() -> rejected(PROMOTION_MISSING));
        if (!document.equals(promotion.document())) {
            throw rejected(RESPONSE_MISMATCH);
        }
        var previous = evidence.findLatestRetention(context, document);
        if (previous.isPresent()) {
            var current = previous.orElseThrow();
            if (!document.equals(current.document())) {
                throw rejected(RESPONSE_MISMATCH);
            }
            if (directive.retainUntil().isBefore(current.retainUntil())) {
                throw rejected(RETENTION_SHORTENING);
            }
            if (current.legalHold() && !directive.legalHold()) {
                throw rejected(LEGAL_HOLD_RELEASE);
            }
            if (directive.retainUntil().equals(current.retainUntil())
                    && directive.legalHold() == current.legalHold()) {
                throw rejected(NO_CHANGE);
            }
        }

        final DocumentRetentionAuthorization authorization;
        try {
            authorization = new DocumentRetentionAuthorization(
                    directive.retentionDirectiveId(),
                    promotion,
                    previous.map(DocumentRetentionEvidence::retentionDirectiveId).orElse(null),
                    policy.policyKey(),
                    policy.acceptedPurposes(),
                    policy.minimumRetention(),
                    policy.maximumRetention(),
                    policy.maximumAuthorizationAge(),
                    policy.maximumFutureSkew(),
                    directive.retainUntil(),
                    directive.legalHold(),
                    context.purpose(),
                    clock.instant());
        } catch (IllegalArgumentException exception) {
            throw rejected(DIRECTIVE_REJECTED);
        }

        var receipt = Objects.requireNonNull(
                retention.apply(context, authorization), "retention receipt");
        if (!matches(receipt, authorization)) {
            throw rejected(RESPONSE_MISMATCH);
        }
        var recorded = evidence.recordRetention(context, authorization, receipt);
        if (!matches(recorded, authorization, receipt, context)) {
            throw rejected(EVIDENCE_CONFLICT);
        }
        return recorded;
    }

    private static boolean matches(
            DocumentRetentionReceipt receipt, DocumentRetentionAuthorization authorization) {
        return authorization.document().equals(receipt.document())
                && authorization.retainUntil().equals(receipt.retainUntil())
                && authorization.legalHold() == receipt.legalHold();
    }

    private static boolean matches(
            DocumentRetentionEvidence recorded,
            DocumentRetentionAuthorization authorization,
            DocumentRetentionReceipt receipt,
            AuthorizedTenantContext context) {
        return recorded.retentionDirectiveId().equals(authorization.retentionDirectiveId())
                && recorded.document().equals(authorization.document())
                && Objects.equals(
                        recorded.previousRetentionDirectiveId(),
                        authorization.previousRetentionDirectiveId())
                && recorded.policyKey().equals(authorization.policyKey())
                && recorded.acceptedPurposes().equals(authorization.acceptedPurposes())
                && recorded.minimumRetention().equals(authorization.minimumRetention())
                && recorded.maximumRetention().equals(authorization.maximumRetention())
                && recorded.maximumAuthorizationAge()
                        .equals(authorization.maximumAuthorizationAge())
                && recorded.maximumFutureSkew().equals(authorization.maximumFutureSkew())
                && recorded.retainUntil().equals(authorization.retainUntil())
                && recorded.legalHold() == authorization.legalHold()
                && recorded.storageVersionSha256().equals(receipt.storageVersionSha256())
                && recorded.actorId().equals(context.actorId())
                && recorded.purpose().equals(context.purpose())
                && recorded.correlationId().equals(context.correlationId())
                && recorded.authorizedAt().equals(authorization.authorizedAt());
    }

    private static boolean matchesReplay(
            DocumentRetentionEvidence recorded,
            DocumentObjectReference document,
            DocumentRetentionDirective directive,
            DocumentRetentionPolicy policy,
            AuthorizedTenantContext context) {
        return recorded.retentionDirectiveId().equals(directive.retentionDirectiveId())
                && recorded.document().equals(document)
                && recorded.policyKey().equals(directive.policyKey())
                && recorded.policyKey().equals(policy.policyKey())
                && recorded.acceptedPurposes().equals(policy.acceptedPurposes())
                && recorded.minimumRetention().equals(policy.minimumRetention())
                && recorded.maximumRetention().equals(policy.maximumRetention())
                && recorded.maximumAuthorizationAge().equals(policy.maximumAuthorizationAge())
                && recorded.maximumFutureSkew().equals(policy.maximumFutureSkew())
                && recorded.retainUntil().equals(directive.retainUntil())
                && recorded.legalHold() == directive.legalHold()
                && recorded.actorId().equals(context.actorId())
                && recorded.purpose().equals(context.purpose());
    }

    private static DocumentRetentionException rejected(String reasonCode) {
        return new DocumentRetentionException(reasonCode);
    }
}
