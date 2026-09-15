package com.rootopathy.careos.platform.application;

import com.rootopathy.careos.platform.domain.DocumentObjectReference;
import com.rootopathy.careos.platform.domain.DocumentPromotionEvidence;
import com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext;

/** Coordinates authoritative evidence, promotion policy, and private clean-object storage. */
public interface DocumentPromotionOperations {
    DocumentPromotionEvidence promote(
            AuthorizedTenantContext context, DocumentObjectReference document);
}
