package com.rootopathy.careos.platform.domain;

/** Payload-free tenant notification telemetry. Scheduled notifications are included in ready. */
public record NotificationQueueSnapshot(
        long readyNotifications,
        long leasedNotifications,
        long deadLetteredNotifications,
        long retainedCompletedNotifications) {
    public NotificationQueueSnapshot {
        if (readyNotifications < 0
                || leasedNotifications < 0
                || deadLetteredNotifications < 0
                || retainedCompletedNotifications < 0) {
            throw new IllegalArgumentException("notification counts must not be negative");
        }
    }
}
