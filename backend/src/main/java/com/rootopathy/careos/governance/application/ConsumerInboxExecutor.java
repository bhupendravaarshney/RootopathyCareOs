package com.rootopathy.careos.governance.application;

import com.rootopathy.careos.governance.domain.ConsumerInboxReceipt;
import com.rootopathy.careos.governance.domain.InboundOutboxEvent;
import com.rootopathy.careos.tenancy.application.TenantAuthorizationOperations;
import com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext;
import com.rootopathy.careos.tenancy.domain.TenantAuthorizationRequest;
import java.util.Objects;
import java.util.function.Consumer;
import org.springframework.stereotype.Service;

/**
 * The transaction boundary for at-least-once event consumption: tenant authorization, inbox
 * acquisition, and the first business effect either commit together or all roll back.
 */
@Service
public final class ConsumerInboxExecutor {
    private final TenantAuthorizationOperations authorization;
    private final ConsumerInboxOperations inbox;

    public ConsumerInboxExecutor(
            TenantAuthorizationOperations authorization, ConsumerInboxOperations inbox) {
        this.authorization = authorization;
        this.inbox = inbox;
    }

    public ConsumerInboxReceipt execute(
            TenantAuthorizationRequest authorizationRequest,
            InboundOutboxEvent event,
            Consumer<AuthorizedTenantContext> firstProcessing) {
        Objects.requireNonNull(authorizationRequest, "authorizationRequest");
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(firstProcessing, "firstProcessing");
        return authorization.execute(authorizationRequest, context -> inbox.execute(
                context, event, () -> firstProcessing.accept(context)));
    }
}
