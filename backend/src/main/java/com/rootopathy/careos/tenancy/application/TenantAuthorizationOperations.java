package com.rootopathy.careos.tenancy.application;

import com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext;
import com.rootopathy.careos.tenancy.domain.TenantAuthorizationRequest;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Revalidates active membership and a migration-owned operation/permission mapping before running
 * work in the same tenant-bound transaction.
 */
public interface TenantAuthorizationOperations {
    <T> T execute(
            TenantAuthorizationRequest request,
            Function<AuthorizedTenantContext, T> authorizedWork);

    default <T> T execute(TenantAuthorizationRequest request, Supplier<T> authorizedWork) {
        return execute(request, context -> authorizedWork.get());
    }

    default void execute(TenantAuthorizationRequest request, Runnable authorizedWork) {
        execute(request, context -> {
            authorizedWork.run();
            return null;
        });
    }
}
