package com.rootopathy.careos.platform.application;

import com.rootopathy.careos.platform.domain.DocumentObjectReference;
import com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext;
import java.net.URI;
import java.time.Duration;

/** Issues bounded, read-only access only after tenant authorization and clean-object verification. */
public interface SignedDocumentAccessPort {
    URI createReadUrl(
            AuthorizedTenantContext context, DocumentObjectReference document, Duration requestedTtl);
}
