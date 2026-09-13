package com.rootopathy.careos.governance.application;

import java.util.Objects;

public final class IdempotencyException extends RuntimeException {
    public enum Reason {
        KEY_REUSED,
        REQUEST_IN_PROGRESS
    }

    private final Reason reason;

    public IdempotencyException(Reason reason, String message) {
        super(message);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public Reason reason() {
        return reason;
    }
}
