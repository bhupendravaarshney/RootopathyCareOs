package com.rootopathy.careos.governance.application;

import com.rootopathy.careos.governance.domain.GovernedMutation;
import com.rootopathy.careos.governance.domain.IdempotencyCommand;
import com.rootopathy.careos.governance.domain.IdempotencyOutcome;
import com.rootopathy.careos.tenancy.application.TenantAuthorizationOperations;
import com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext;
import com.rootopathy.careos.tenancy.domain.TenantAuthorizationRequest;
import java.util.Objects;
import java.util.function.Function;
import org.springframework.stereotype.Service;

/**
 * The mandatory transaction shape for retryable governed mutations: authorization, first execution,
 * audit, outbox, and replay completion either commit together or all roll back.
 */
@Service
public final class GovernedMutationExecutor {
    private final TenantAuthorizationOperations authorization;
    private final IdempotencyOperations idempotency;
    private final GovernanceEvidenceOperations evidence;

    public GovernedMutationExecutor(
            TenantAuthorizationOperations authorization,
            IdempotencyOperations idempotency,
            GovernanceEvidenceOperations evidence) {
        this.authorization = authorization;
        this.idempotency = idempotency;
        this.evidence = evidence;
    }

    public IdempotencyOutcome execute(
            TenantAuthorizationRequest authorizationRequest,
            IdempotencyCommand idempotencyCommand,
            Function<AuthorizedTenantContext, GovernedMutation> firstExecution) {
        Objects.requireNonNull(authorizationRequest, "authorizationRequest");
        Objects.requireNonNull(idempotencyCommand, "idempotencyCommand");
        Objects.requireNonNull(firstExecution, "firstExecution");
        return authorization.execute(authorizationRequest, context -> idempotency.execute(
                context,
                idempotencyCommand,
                () -> {
                    var mutation = Objects.requireNonNull(
                            firstExecution.apply(context), "firstExecution result");
                    evidence.record(context, mutation.evidence());
                    mutation.additionalEvidence()
                            .forEach(additional -> evidence.record(context, additional));
                    return mutation.response();
                }));
    }
}
