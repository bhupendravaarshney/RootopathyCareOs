package com.rootopathy.careos.platform.application;

import com.rootopathy.careos.platform.domain.DocumentRetentionAuthorization;
import com.rootopathy.careos.platform.domain.DocumentRetentionReceipt;
import com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext;

/** Applies only immutable retention extensions and legal-hold enablement to verified content. */
public interface DocumentRetentionPort {
    DocumentRetentionReceipt apply(
            AuthorizedTenantContext context, DocumentRetentionAuthorization authorization);
}
