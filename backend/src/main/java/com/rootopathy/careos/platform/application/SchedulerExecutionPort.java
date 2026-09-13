package com.rootopathy.careos.platform.application;

import com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext;
import java.time.Instant;

/** Dispatches due tenant work only after a non-interactive identity has obtained an authorized context. */
public interface SchedulerExecutionPort {
    int dispatchDue(AuthorizedTenantContext context, Instant asOf, int maximumSchedules);
}
