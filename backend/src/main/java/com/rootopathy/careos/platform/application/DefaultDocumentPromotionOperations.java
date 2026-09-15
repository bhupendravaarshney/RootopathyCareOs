package com.rootopathy.careos.platform.application;

import com.rootopathy.careos.platform.domain.DocumentObjectReference;
import com.rootopathy.careos.platform.domain.DocumentPromotionAuthorization;
import com.rootopathy.careos.platform.domain.DocumentPromotionEvidence;
import com.rootopathy.careos.platform.domain.DocumentPromotionPolicy;
import com.rootopathy.careos.platform.domain.MalwareScanVerdict;
import com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext;
import java.time.Clock;
import java.util.Objects;

public final class DefaultDocumentPromotionOperations implements DocumentPromotionOperations {
    private static final String QUARANTINE_MISSING = "document-quarantine-evidence-not-found";
    private static final String SCAN_MISSING = "document-scan-evidence-not-found";
    private static final String REFERENCE_MISMATCH = "document-promotion-reference-mismatch";
    private static final String DIGEST_MISMATCH = "document-promotion-digest-mismatch";
    private static final String CLEAN_REQUIRED = "document-promotion-clean-scan-required";
    private static final String SCANNER_REJECTED = "document-promotion-scanner-not-approved";
    private static final String SCAN_STALE = "document-promotion-scan-stale";
    private static final String SCAN_IN_FUTURE = "document-promotion-scan-in-future";
    private static final String EVIDENCE_CONFLICT = "document-promotion-evidence-conflict";

    private final DocumentPromotionPort promotion;
    private final DocumentEvidenceOperations evidence;
    private final DocumentPromotionPolicy policy;
    private final Clock clock;

    public DefaultDocumentPromotionOperations(
            DocumentPromotionPort promotion,
            DocumentEvidenceOperations evidence,
            DocumentPromotionPolicy policy,
            Clock clock) {
        this.promotion = Objects.requireNonNull(promotion, "promotion");
        this.evidence = Objects.requireNonNull(evidence, "evidence");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public DocumentPromotionEvidence promote(
            AuthorizedTenantContext context, DocumentObjectReference document) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(document, "document");

        var existing = evidence.findPromotion(context, document);
        if (existing.isPresent()) {
            var recorded = existing.orElseThrow();
            if (!document.equals(recorded.document())) {
                throw rejected(REFERENCE_MISMATCH);
            }
            return recorded;
        }
        var quarantine = evidence.findQuarantine(context, document)
                .orElseThrow(() -> rejected(QUARANTINE_MISSING));
        var scan = evidence.findLatestScan(context, document)
                .orElseThrow(() -> rejected(SCAN_MISSING));
        var result = scan.result();
        if (!document.equals(result.document())) {
            throw rejected(REFERENCE_MISMATCH);
        }
        if (result.verdict() != MalwareScanVerdict.CLEAN) {
            throw rejected(CLEAN_REQUIRED);
        }
        if (!quarantine.sha256().equals(result.sha256())) {
            throw rejected(DIGEST_MISMATCH);
        }
        if (!policy.acceptsScanner(result.scannerKey())) {
            throw rejected(SCANNER_REJECTED);
        }
        var now = clock.instant();
        if (result.scannedAt().isAfter(now.plus(policy.maximumFutureSkew()))) {
            throw rejected(SCAN_IN_FUTURE);
        }
        if (result.scannedAt().isBefore(now.minus(policy.maximumScanAge()))) {
            throw rejected(SCAN_STALE);
        }

        var authorization = new DocumentPromotionAuthorization(
                scan,
                policy.policyKey(),
                policy.acceptedScannerKeys(),
                policy.maximumScanAge(),
                policy.maximumFutureSkew(),
                now);
        var promoted = promotion.promote(context, authorization);
        if (!document.equals(promoted)) {
            throw rejected(REFERENCE_MISMATCH);
        }
        var recorded = evidence.recordPromotion(context, scan, policy);
        if (!document.equals(recorded.document())
                || !scan.equals(recorded.scanAttestation())
                || !policy.policyKey().equals(recorded.policyKey())
                || !policy.acceptedScannerKeys().equals(recorded.acceptedScannerKeys())
                || !policy.maximumScanAge().equals(recorded.maximumScanAge())
                || !policy.maximumFutureSkew().equals(recorded.maximumFutureSkew())) {
            throw rejected(EVIDENCE_CONFLICT);
        }
        return recorded;
    }

    private static DocumentPromotionException rejected(String reasonCode) {
        return new DocumentPromotionException(reasonCode);
    }
}
