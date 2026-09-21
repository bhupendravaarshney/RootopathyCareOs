package com.rootopathy.careos.workforce.application;

public final class WorkforceException extends RuntimeException {
    private final Reason reason;

    public WorkforceException(Reason reason, String message) {
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
        EVIDENCE_NOT_CLEAN
    }
}
