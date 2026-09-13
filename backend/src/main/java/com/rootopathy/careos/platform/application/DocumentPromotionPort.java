package com.rootopathy.careos.platform.application;

import com.rootopathy.careos.platform.domain.DocumentObjectReference;
import com.rootopathy.careos.platform.domain.MalwareScanResult;
import com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext;

/** Promotion implementations must accept only matching, current CLEAN scan evidence. */
public interface DocumentPromotionPort {
    DocumentObjectReference promote(
            AuthorizedTenantContext context, MalwareScanResult cleanScanEvidence);
}
