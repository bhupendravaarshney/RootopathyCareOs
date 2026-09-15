package com.rootopathy.careos.platform.application;

import com.rootopathy.careos.platform.domain.DocumentObjectReference;
import com.rootopathy.careos.platform.domain.DocumentPromotionAuthorization;
import com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext;

/** Low-level storage promotion accepts only a validated clean-scan authorization snapshot. */
public interface DocumentPromotionPort {
    DocumentObjectReference promote(
            AuthorizedTenantContext context, DocumentPromotionAuthorization authorization);
}
