package com.rootopathy.careos.tenancy.application;

import com.rootopathy.careos.tenancy.domain.AuthenticatedActorContext;
import java.util.function.Supplier;

/** Runs actor-owned discovery reads with transaction-local PostgreSQL identity metadata. */
public interface ActorTransactionOperations {
    <T> T execute(AuthenticatedActorContext context, Supplier<T> work);
}
