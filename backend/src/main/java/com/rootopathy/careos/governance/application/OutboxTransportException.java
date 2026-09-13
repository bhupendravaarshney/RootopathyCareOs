package com.rootopathy.careos.governance.application;

import java.util.regex.Pattern;

public final class OutboxTransportException extends Exception {
    private static final Pattern SAFE_ERROR_CODE =
            Pattern.compile("[a-z][a-z0-9]*([._:-][a-z0-9]+)*");

    private final String errorCode;
    private final boolean retryable;

    public OutboxTransportException(String errorCode, boolean retryable) {
        super("Outbox transport failed: " + requireSafeCode(errorCode));
        this.errorCode = errorCode;
        this.retryable = retryable;
    }

    public String errorCode() {
        return errorCode;
    }

    public boolean retryable() {
        return retryable;
    }

    private static String requireSafeCode(String value) {
        if (value == null || value.length() > 120 || !SAFE_ERROR_CODE.matcher(value).matches()) {
            throw new IllegalArgumentException("errorCode has an invalid format");
        }
        return value;
    }
}
