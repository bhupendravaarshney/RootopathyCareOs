package com.rootopathy.careos.platform.application;

import com.rootopathy.careos.platform.domain.DurableJob;
import com.rootopathy.careos.platform.domain.DurableJobClaim;
import com.rootopathy.careos.platform.domain.JobFailureDisposition;
import com.rootopathy.careos.platform.domain.JobQueueSnapshot;
import com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext;
import java.util.List;

/** Durable, deduplicated tenant-job lifecycle. This port does not execute work. */
public interface JobQueuePort {
    void enqueue(AuthorizedTenantContext context, DurableJob job);

    List<DurableJobClaim> claim(AuthorizedTenantContext context, int maximumJobs);

    void acknowledge(AuthorizedTenantContext context, DurableJobClaim claim);

    JobFailureDisposition recordFailure(
            AuthorizedTenantContext context, DurableJobClaim claim, String reasonCode);

    JobQueueSnapshot snapshot(AuthorizedTenantContext context);
}
