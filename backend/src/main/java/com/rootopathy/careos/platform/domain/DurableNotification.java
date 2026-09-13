package com.rootopathy.careos.platform.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A durable notification request references an internal recipient, never a raw destination address.
 * Payloads are sensitive in memory and must be encrypted by any persistence adapter and never logged.
 */
public record DurableNotification(
        UUID notificationId,
        UUID recipientUserId,
        String templateKey,
        int templateVersion,
        String payloadJson,
        String deduplicationKey,
        Instant notBefore) {
    public DurableNotification {
        Objects.requireNonNull(notificationId, "notificationId");
        Objects.requireNonNull(recipientUserId, "recipientUserId");
        templateKey = PlatformValues.key(templateKey, "templateKey", 160);
        if (templateVersion < 1) {
            throw new IllegalArgumentException("templateVersion must be positive");
        }
        payloadJson = PlatformValues.jsonObject(payloadJson, "payloadJson");
        deduplicationKey = PlatformValues.token(deduplicationKey, "deduplicationKey", 160);
        Objects.requireNonNull(notBefore, "notBefore");
    }
}
