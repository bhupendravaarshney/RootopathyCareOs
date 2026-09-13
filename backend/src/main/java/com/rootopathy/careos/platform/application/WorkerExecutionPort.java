package com.rootopathy.careos.platform.application;

import com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext;

/** Executes tenant jobs only after a non-interactive identity has obtained an authorized context. */
public interface WorkerExecutionPort {
    int executeNext(AuthorizedTenantContext context, int maximumJobs);
}
