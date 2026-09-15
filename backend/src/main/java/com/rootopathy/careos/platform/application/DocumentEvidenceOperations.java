package com.rootopathy.careos.platform.application;

import com.rootopathy.careos.platform.domain.DocumentObjectReference;
import com.rootopathy.careos.platform.domain.DocumentQuarantineEvidence;
import com.rootopathy.careos.platform.domain.DocumentQuarantineRequest;
import com.rootopathy.careos.platform.domain.DocumentScanAttestation;
import com.rootopathy.careos.platform.domain.MalwareScanResult;
import com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext;
import java.util.Optional;

/**
 * Persists append-only quarantine metadata and scan observations. Every call requires the caller's
 * already-authorized writable tenant transaction.
 */
public interface DocumentEvidenceOperations {
    DocumentQuarantineEvidence recordQuarantine(
            AuthorizedTenantContext context,
            DocumentQuarantineRequest request,
            DocumentObjectReference document);

    DocumentScanAttestation recordScan(
            AuthorizedTenantContext context, MalwareScanResult result);

    Optional<DocumentQuarantineEvidence> findQuarantine(
            AuthorizedTenantContext context, DocumentObjectReference document);

    Optional<DocumentScanAttestation> findLatestScan(
            AuthorizedTenantContext context, DocumentObjectReference document);
}
