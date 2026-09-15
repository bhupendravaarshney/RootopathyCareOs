package com.rootopathy.careos.governance.application;

import java.util.Objects;

public final class ConsumerInboxException extends RuntimeException {
    public enum Reason {
        NOT_INITIALIZED,
        TENANT_MISMATCH,
        CONTENT_CONFLICT,
        STORE_REJECTED
    }

    private final Reason reason;

    public ConsumerInboxException(Reason reason, String message) {
        super(message);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public ConsumerInboxException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public Reason reason() {
        return reason;
    }
}
