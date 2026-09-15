package com.rootopathy.careos.platform.application;

import com.rootopathy.careos.platform.domain.DocumentObjectReference;
import com.rootopathy.careos.platform.domain.DocumentRetentionDirective;
import com.rootopathy.careos.platform.domain.DocumentRetentionEvidence;
import com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext;

/** Coordinates promotion proof, monotonic retention, provider enforcement, and durable evidence. */
public interface DocumentRetentionOperations {
    DocumentRetentionEvidence apply(
            AuthorizedTenantContext context,
            DocumentObjectReference document,
            DocumentRetentionDirective directive);
}
