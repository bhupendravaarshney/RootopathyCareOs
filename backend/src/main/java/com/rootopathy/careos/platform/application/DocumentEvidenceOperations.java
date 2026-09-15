package com.rootopathy.careos.platform.application;

import com.rootopathy.careos.platform.domain.DocumentAccessAuthorization;
import com.rootopathy.careos.platform.domain.DocumentAccessGrantEvidence;
import com.rootopathy.careos.platform.domain.DocumentObjectReference;
import com.rootopathy.careos.platform.domain.DocumentPromotionEvidence;
import com.rootopathy.careos.platform.domain.DocumentPromotionPolicy;
import com.rootopathy.careos.platform.domain.DocumentQuarantineEvidence;
import com.rootopathy.careos.platform.domain.DocumentQuarantineRequest;
import com.rootopathy.careos.platform.domain.DocumentRetentionAuthorization;
import com.rootopathy.careos.platform.domain.DocumentRetentionEvidence;
import com.rootopathy.careos.platform.domain.DocumentRetentionReceipt;
import com.rootopathy.careos.platform.domain.DocumentScanAttestation;
import com.rootopathy.careos.platform.domain.MalwareScanResult;
import com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext;
import java.util.Optional;
import java.util.UUID;

/**
 * Persists append-only document security, promotion, access, and retention evidence. Every call
 * requires the caller's already-authorized writable tenant transaction.
 */
public interface DocumentEvidenceOperations {
    DocumentQuarantineEvidence recordQuarantine(
            AuthorizedTenantContext context,
            DocumentQuarantineRequest request,
            DocumentObjectReference document);

    DocumentScanAttestation recordScan(
            AuthorizedTenantContext context, MalwareScanResult result);

    DocumentPromotionEvidence recordPromotion(
            AuthorizedTenantContext context,
            DocumentScanAttestation scanAttestation,
            DocumentPromotionPolicy policy);

    DocumentAccessGrantEvidence recordAccessGrant(
            AuthorizedTenantContext context, DocumentAccessAuthorization authorization);

    DocumentRetentionEvidence recordRetention(
            AuthorizedTenantContext context,
            DocumentRetentionAuthorization authorization,
            DocumentRetentionReceipt receipt);

    Optional<DocumentQuarantineEvidence> findQuarantine(
            AuthorizedTenantContext context, DocumentObjectReference document);

    Optional<DocumentScanAttestation> findLatestScan(
            AuthorizedTenantContext context, DocumentObjectReference document);

    Optional<DocumentPromotionEvidence> findPromotion(
            AuthorizedTenantContext context, DocumentObjectReference document);

    Optional<DocumentAccessGrantEvidence> findAccessGrant(
            AuthorizedTenantContext context,
            DocumentObjectReference document,
            UUID accessGrantId);

    Optional<DocumentRetentionEvidence> findRetention(
            AuthorizedTenantContext context,
            DocumentObjectReference document,
            UUID retentionDirectiveId);

    Optional<DocumentRetentionEvidence> findLatestRetention(
            AuthorizedTenantContext context, DocumentObjectReference document);
}
