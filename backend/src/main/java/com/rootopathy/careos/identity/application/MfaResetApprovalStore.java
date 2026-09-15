package com.rootopathy.careos.identity.application;

import com.rootopathy.careos.identity.domain.MfaResetApproval;
import com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext;
import java.time.Instant;
import java.util.UUID;

public interface MfaResetApprovalStore {
    MfaResetApproval request(
            AuthorizedTenantContext context,
            UUID approvalId,
            UUID targetUserId,
            String reason,
            Instant expiresAt);

    MfaResetApproval approve(
            AuthorizedTenantContext context,
            UUID approvalId,
            UUID targetUserId,
            String decisionReason,
            Instant decidedAt);

    MfaResetApproval requireConsumed(
            AuthorizedTenantContext context,
            UUID approvalId,
            UUID targetUserId,
            String idempotencyKey);
}
