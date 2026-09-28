package com.rootopathy.careos.reporting.application;

public final class ReportingException extends RuntimeException {
    private final Reason reason;

    public ReportingException(Reason reason, String message) {
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
