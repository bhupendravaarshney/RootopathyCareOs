package com.rootopathy.careos.document.application;

public final class DocumentException extends RuntimeException {
    private final Reason reason;

    public DocumentException(Reason reason, String message) {
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
        POLICY_UNAVAILABLE,
        DEPENDENCY_UNAVAILABLE
    }
}
