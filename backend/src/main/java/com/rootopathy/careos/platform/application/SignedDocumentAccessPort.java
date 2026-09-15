package com.rootopathy.careos.platform.application;

import com.rootopathy.careos.platform.domain.DocumentAccessAuthorization;
import com.rootopathy.careos.platform.domain.SignedDocumentAccess;
import com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext;

/** Issues bounded, read-only access only after tenant authorization and clean-object verification. */
public interface SignedDocumentAccessPort {
    SignedDocumentAccess createReadAccess(
            AuthorizedTenantContext context, DocumentAccessAuthorization authorization);
}
