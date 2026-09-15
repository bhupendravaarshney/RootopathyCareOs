package com.rootopathy.careos.tenancy.application;

import com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext;
import com.rootopathy.careos.tenancy.domain.ServiceIdentityAuthorizationRequest;
import java.util.function.Function;
import java.util.function.Supplier;

/** Authenticates and authorizes a non-interactive identity inside one tenant transaction. */
public interface ServiceIdentityAuthorizationOperations {
    <T> T execute(
            ServiceIdentityAuthorizationRequest request,
            Function<AuthorizedTenantContext, T> authorizedWork);

    default <T> T execute(
            ServiceIdentityAuthorizationRequest request, Supplier<T> authorizedWork) {
        return execute(request, context -> authorizedWork.get());
    }

    default void execute(ServiceIdentityAuthorizationRequest request, Runnable authorizedWork) {
        execute(request, context -> {
            authorizedWork.run();
            return null;
        });
    }
}
