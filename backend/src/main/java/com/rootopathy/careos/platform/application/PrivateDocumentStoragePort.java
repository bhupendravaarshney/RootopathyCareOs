package com.rootopathy.careos.platform.application;

import com.rootopathy.careos.platform.domain.DocumentObjectReference;
import com.rootopathy.careos.platform.domain.DocumentQuarantineRequest;
import com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext;
import java.io.InputStream;

/** Stores new content only in private quarantine. The adapter must verify size and digest while streaming. */
public interface PrivateDocumentStoragePort {
    DocumentObjectReference quarantine(
            AuthorizedTenantContext context, DocumentQuarantineRequest request, InputStream content);
}
