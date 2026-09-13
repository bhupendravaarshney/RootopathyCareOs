package com.rootopathy.careos.platform.application;

import com.rootopathy.careos.platform.domain.DocumentObjectReference;
import com.rootopathy.careos.platform.domain.DocumentRetentionDirective;
import com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext;

/** Retention implementations must preserve legal holds and record governed lifecycle evidence. */
public interface DocumentRetentionPort {
    void apply(
            AuthorizedTenantContext context,
            DocumentObjectReference document,
            DocumentRetentionDirective directive);
}
