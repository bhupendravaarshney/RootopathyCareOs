package com.rootopathy.careos.identity.application;

import java.util.Objects;

public final class MembershipAdministrationException extends RuntimeException {
    private final Reason reason;

    public MembershipAdministrationException(Reason reason, String message) {
        super(message);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public Reason reason() {
        return reason;
    }

    public enum Reason {
        UNAVAILABLE,
        INVALID_REQUEST,
        TARGET_UNAVAILABLE,
        APPROVAL_ALREADY_OPEN,
        APPROVAL_UNAVAILABLE,
        PRECONDITION_REQUIRED,
        STALE_REVISION,
        CONFLICT
    }
}
