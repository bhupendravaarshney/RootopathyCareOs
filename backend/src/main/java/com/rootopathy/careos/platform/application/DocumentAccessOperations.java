package com.rootopathy.careos.platform.application;

import com.rootopathy.careos.platform.domain.DocumentObjectReference;
import com.rootopathy.careos.platform.domain.SignedDocumentAccess;
import com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext;
import java.time.Duration;
import java.util.UUID;

/** Coordinates promotion proof, access policy, signing, and durable grant evidence. */
public interface DocumentAccessOperations {
    SignedDocumentAccess createReadAccess(
            AuthorizedTenantContext context, DocumentObjectReference document, Duration requestedTtl);

    /** Re-signs an existing unexpired actor/purpose-bound grant without persisting a bearer URL. */
    SignedDocumentAccess reopenReadAccess(
            AuthorizedTenantContext context, DocumentObjectReference document, UUID accessGrantId);
}
