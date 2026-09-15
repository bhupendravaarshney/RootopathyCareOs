package com.rootopathy.careos.platform.application;

import com.rootopathy.careos.platform.domain.DocumentQuarantineEvidence;
import com.rootopathy.careos.platform.domain.DocumentQuarantineRequest;
import com.rootopathy.careos.platform.domain.DocumentObjectReference;
import com.rootopathy.careos.platform.domain.DocumentScanAttestation;
import com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext;
import java.io.InputStream;

/** Safe application boundary that couples quarantine/scanning I/O to durable evidence. */
public interface DocumentSecurityOperations {
    DocumentQuarantineEvidence quarantine(
            AuthorizedTenantContext context, DocumentQuarantineRequest request, InputStream content);

    DocumentScanAttestation scan(
            AuthorizedTenantContext context, DocumentObjectReference document);
}
