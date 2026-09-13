package com.rootopathy.careos.platform.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A time-bounded notification lease. Parameters are sensitive in memory; the lease token and
 * parameters must never be logged.
 */
public record DurableNotificationClaim(
        UUID organizationId,
        UUID notificationId,
        UUID recipientUserId,
        String templateKey,
        int templateVersion,
        String payloadJson,
        int attempt,
        String leaseToken,
        Instant leaseExpiresAt) {
    public DurableNotificationClaim {
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(notificationId, "notificationId");
        Objects.requireNonNull(recipientUserId, "recipientUserId");
        templateKey = PlatformValues.key(templateKey, "templateKey", 160);
        if (templateVersion < 1) {
            throw new IllegalArgumentException("templateVersion must be positive");
        }
        payloadJson = PlatformValues.jsonObject(payloadJson, "payloadJson");
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt must be positive");
        }
        leaseToken = PlatformValues.token(leaseToken, "leaseToken", 160);
        Objects.requireNonNull(leaseExpiresAt, "leaseExpiresAt");
    }
}
