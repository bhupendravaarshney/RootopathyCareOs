package com.rootopathy.careos.platform.application;

import com.rootopathy.careos.platform.domain.DocumentObjectReference;
import com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext;

/**
 * Scanner-only read boundary for content that is still in private quarantine. Implementations
 * must revalidate tenant ownership, quarantine state, object identity, size, and expected digest.
 */
public interface QuarantinedDocumentContentSourcePort {
    QuarantinedDocumentContent open(
            AuthorizedTenantContext context, DocumentObjectReference document);
}
