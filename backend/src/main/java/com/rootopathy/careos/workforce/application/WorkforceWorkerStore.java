package com.rootopathy.careos.workforce.application;

import com.rootopathy.careos.platform.domain.DocumentObjectReference;
import com.rootopathy.careos.platform.domain.DocumentPromotionEvidence;
import com.rootopathy.careos.platform.domain.DocumentScanAttestation;
import com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext;
import java.util.UUID;

public interface WorkforceWorkerStore {
    DocumentWork document(AuthorizedTenantContext context, UUID documentId);

    DocumentBinding bindScan(
            AuthorizedTenantContext context,
            DocumentWork document,
            DocumentScanAttestation scan,
            DocumentPromotionEvidence promotion);

    record DocumentWork(
            UUID documentId,
            UUID credentialId,
            UUID memberId,
            DocumentObjectReference object,
            String declaredDigest,
            int nextAttempt,
            long revision) {}

    record DocumentBinding(
            UUID documentId,
            UUID credentialId,
            UUID scanAttemptId,
            String digest,
            String outcome,
            String failureCode,
            long revision) {}
}
