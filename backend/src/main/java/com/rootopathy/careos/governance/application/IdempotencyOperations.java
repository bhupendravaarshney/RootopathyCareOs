package com.rootopathy.careos.governance.application;

import com.rootopathy.careos.governance.domain.IdempotencyCommand;
import com.rootopathy.careos.governance.domain.IdempotencyOutcome;
import com.rootopathy.careos.governance.domain.IdempotentResponse;
import com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext;
import java.util.function.Supplier;

/** Serializes a retry key and completes its replay record in the caller's tenant transaction. */
public interface IdempotencyOperations {
    IdempotencyOutcome execute(
            AuthorizedTenantContext context,
            IdempotencyCommand command,
            Supplier<IdempotentResponse> firstExecution);
}
