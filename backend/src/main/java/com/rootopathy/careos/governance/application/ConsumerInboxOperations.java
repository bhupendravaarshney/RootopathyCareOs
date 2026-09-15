package com.rootopathy.careos.governance.application;

import com.rootopathy.careos.governance.domain.ConsumerInboxReceipt;
import com.rootopathy.careos.governance.domain.InboundOutboxEvent;
import com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext;

/** Atomically records a source event and runs its first consumer effect in the caller's transaction. */
public interface ConsumerInboxOperations {
    ConsumerInboxReceipt execute(
            AuthorizedTenantContext context,
            InboundOutboxEvent event,
            Runnable firstProcessing);
}
