package com.rootopathy.careos.tenancy.application;

import java.util.Objects;

public final class ServiceIdentityAuthorizationException extends RuntimeException {
    private final Reason reason;

    public ServiceIdentityAuthorizationException(Reason reason) {
        super("Service identity authorization failed.");
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public Reason reason() {
        return reason;
    }

    public enum Reason {
        DISABLED,
        CREDENTIAL_OR_PERMISSION_DENIED
    }
}
