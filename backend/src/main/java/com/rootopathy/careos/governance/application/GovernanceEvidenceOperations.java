package com.rootopathy.careos.governance.application;

import com.rootopathy.careos.governance.domain.GovernanceEvidence;
import com.rootopathy.careos.governance.domain.GovernanceEvidenceIds;
import com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext;

/** Writes audit and outbox evidence in the caller's already-authorized tenant transaction. */
public interface GovernanceEvidenceOperations {
    GovernanceEvidenceIds record(AuthorizedTenantContext context, GovernanceEvidence evidence);
}
