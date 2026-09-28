package com.rootopathy.careos.integration.application;

public final class IntegrationException extends RuntimeException {
    private final Reason reason;

    public IntegrationException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    public enum Reason {
        INVALID,
        NOT_FOUND,
        CONFLICT,
        PRECONDITION_REQUIRED,
        STALE,
        POLICY_UNAVAILABLE
    }
}
