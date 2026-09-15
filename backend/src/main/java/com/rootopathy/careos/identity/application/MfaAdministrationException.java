package com.rootopathy.careos.identity.application;

import java.util.Objects;

public final class MfaAdministrationException extends RuntimeException {
    private final Reason reason;

    public MfaAdministrationException(Reason reason, String message) {
        super(message);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public Reason reason() {
        return reason;
    }

    public enum Reason {
        UNAVAILABLE,
        TARGET_UNAVAILABLE,
        TARGET_MFA_NOT_ENABLED,
        APPROVAL_UNAVAILABLE,
        APPROVAL_ALREADY_OPEN,
        INVALID_REQUEST
    }
}
