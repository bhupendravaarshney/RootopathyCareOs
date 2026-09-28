package com.rootopathy.careos.billing.application;

public final class BillingException extends RuntimeException {
    private final Reason reason;

    public BillingException(Reason reason, String message) {
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
