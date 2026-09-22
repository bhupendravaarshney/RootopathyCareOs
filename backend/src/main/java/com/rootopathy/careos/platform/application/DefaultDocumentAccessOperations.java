package com.rootopathy.careos.platform.application;

import com.rootopathy.careos.platform.domain.DocumentAccessAuthorization;
import com.rootopathy.careos.platform.domain.DocumentAccessGrantEvidence;
import com.rootopathy.careos.platform.domain.DocumentAccessPolicy;
import com.rootopathy.careos.platform.domain.DocumentObjectReference;
import com.rootopathy.careos.platform.domain.SignedDocumentAccess;
import com.rootopathy.careos.shared.domain.UuidV7Generator;
import com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

public final class DefaultDocumentAccessOperations implements DocumentAccessOperations {
    private static final String TENANT_MISMATCH = "document-access-tenant-mismatch";
    private static final String PURPOSE_REJECTED = "document-access-purpose-not-approved";
    private static final String PROMOTION_MISSING = "document-promotion-evidence-not-found";
    private static final String RESPONSE_MISMATCH = "document-access-response-mismatch";
    private static final String EVIDENCE_CONFLICT = "document-access-evidence-conflict";

    private final SignedDocumentAccessPort access;
    private final DocumentEvidenceOperations evidence;
    private final DocumentAccessPolicy policy;
    private final Clock clock;
    private final Supplier<UUID> grantIds;

    public DefaultDocumentAccessOperations(
            SignedDocumentAccessPort access,
            DocumentEvidenceOperations evidence,
            DocumentAccessPolicy policy,
            Clock clock) {
        this(access, evidence, policy, clock, UuidV7Generator::randomUuid);
    }

    DefaultDocumentAccessOperations(
            SignedDocumentAccessPort access,
            DocumentEvidenceOperations evidence,
            DocumentAccessPolicy policy,
            Clock clock,
            Supplier<UUID> grantIds) {
        this.access = Objects.requireNonNull(access, "access");
        this.evidence = Objects.requireNonNull(evidence, "evidence");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.grantIds = Objects.requireNonNull(grantIds, "grantIds");
    }

    @Override
    public SignedDocumentAccess createReadAccess(
            AuthorizedTenantContext context,
            DocumentObjectReference document,
            Duration requestedTtl) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(document, "document");
        if (!context.organizationId().equals(document.organizationId())) {
            throw rejected(TENANT_MISMATCH);
        }
        if (!policy.acceptsPurpose(context.purpose())) {
            throw rejected(PURPOSE_REJECTED);
        }
        var promotion = evidence.findPromotion(context, document)
                .orElseThrow(() -> rejected(PROMOTION_MISSING));
        if (!document.equals(promotion.document())) {
            throw rejected(RESPONSE_MISMATCH);
        }
        var authorization = new DocumentAccessAuthorization(
                Objects.requireNonNull(grantIds.get(), "grantId"),
                promotion,
                policy.policyKey(),
                policy.acceptedPurposes(),
                requestedTtl,
                policy.maximumTtl(),
                policy.maximumAuthorizationAge(),
                policy.maximumFutureSkew(),
                context.purpose(),
                clock.instant());

        var signed = access.createReadAccess(context, authorization);
        if (!authorization.accessGrantId().equals(signed.accessGrantId())
                || !document.equals(signed.document())
                || !authorization.expiresAt().equals(signed.expiresAt())) {
            throw rejected(RESPONSE_MISMATCH);
        }
        var recorded = evidence.recordAccessGrant(context, authorization);
        if (!matches(recorded, authorization, context)) {
            throw rejected(EVIDENCE_CONFLICT);
        }
        return signed;
    }

    @Override
    public SignedDocumentAccess reopenReadAccess(
            AuthorizedTenantContext context,
            DocumentObjectReference document,
            UUID accessGrantId) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(accessGrantId, "accessGrantId");
        if (!context.organizationId().equals(document.organizationId())) {
            throw rejected(TENANT_MISMATCH);
        }
        var grant = evidence.findAccessGrant(context, document, accessGrantId)
                .orElseThrow(() -> rejected("document-access-grant-not-found"));
        var now = clock.instant();
        if (!context.actorId().equals(grant.actorId())
                || !context.purpose().equals(grant.purpose())
                || !policy.policyKey().equals(grant.policyKey())
                || !policy.acceptedPurposes().equals(grant.acceptedPurposes())
                || !grant.expiresAt().isAfter(now)) {
            throw rejected("document-access-grant-unavailable");
        }
        var remainingSeconds = java.time.Duration.between(now, grant.expiresAt()).getSeconds();
        if (remainingSeconds < 1) {
            throw rejected("document-access-grant-unavailable");
        }
        var authorization = new DocumentAccessAuthorization(
                grant.accessGrantId(),
                grant.promotionEvidence(),
                grant.policyKey(),
                grant.acceptedPurposes(),
                Duration.ofSeconds(remainingSeconds),
                grant.maximumTtl(),
                grant.maximumAuthorizationAge(),
                grant.maximumFutureSkew(),
                grant.purpose(),
                now);
        var signed = access.createReadAccess(context, authorization);
        if (!grant.accessGrantId().equals(signed.accessGrantId())
                || !document.equals(signed.document())
                || signed.expiresAt().isAfter(grant.expiresAt())) {
            throw rejected(RESPONSE_MISMATCH);
        }
        return signed;
    }

    private static boolean matches(
            DocumentAccessGrantEvidence recorded,
            DocumentAccessAuthorization authorization,
            AuthorizedTenantContext context) {
        return authorization.accessGrantId().equals(recorded.accessGrantId())
                && authorization.promotionEvidence().equals(recorded.promotionEvidence())
                && authorization.policyKey().equals(recorded.policyKey())
                && authorization.acceptedPurposes().equals(recorded.acceptedPurposes())
                && authorization.requestedTtl().equals(recorded.requestedTtl())
                && authorization.maximumTtl().equals(recorded.maximumTtl())
                && authorization.maximumAuthorizationAge()
                        .equals(recorded.maximumAuthorizationAge())
                && authorization.maximumFutureSkew().equals(recorded.maximumFutureSkew())
                && authorization.authorizedAt().equals(recorded.authorizedAt())
                && authorization.expiresAt().equals(recorded.expiresAt())
                && context.actorId().equals(recorded.actorId())
                && context.purpose().equals(recorded.purpose())
                && context.correlationId().equals(recorded.correlationId());
    }

    private static DocumentAccessException rejected(String reasonCode) {
        return new DocumentAccessException(reasonCode);
    }
}
