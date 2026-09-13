package com.rootopathy.careos.platform.application;

import com.rootopathy.careos.platform.domain.DurableNotification;
import com.rootopathy.careos.platform.domain.DurableNotificationClaim;
import com.rootopathy.careos.platform.domain.NotificationFailureDisposition;
import com.rootopathy.careos.platform.domain.NotificationQueueSnapshot;
import com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext;
import java.util.List;

/** Durable tenant-notification lifecycle. This port never resolves destinations or sends messages. */
public interface DurableNotificationPort {
    void enqueue(AuthorizedTenantContext context, DurableNotification notification);

    List<DurableNotificationClaim> claim(AuthorizedTenantContext context, int maximumNotifications);

    void acknowledge(AuthorizedTenantContext context, DurableNotificationClaim claim);

    NotificationFailureDisposition recordFailure(
            AuthorizedTenantContext context,
            DurableNotificationClaim claim,
            String reasonCode);

    NotificationQueueSnapshot snapshot(AuthorizedTenantContext context);
}
