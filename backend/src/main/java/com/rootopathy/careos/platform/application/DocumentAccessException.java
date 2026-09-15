package com.rootopathy.careos.platform.application;

import java.util.Objects;
import java.util.regex.Pattern;

/** Stable signed-access rejection without a bearer URL, storage key, or vendor detail. */
public final class DocumentAccessException extends RuntimeException {
    private static final Pattern REASON = Pattern.compile("[a-z][a-z0-9]*([.-][a-z0-9]+)*");

    private final String reasonCode;

    public DocumentAccessException(String reasonCode) {
        super(validated(reasonCode));
        this.reasonCode = reasonCode;
    }

    public String reasonCode() {
        return reasonCode;
    }

    private static String validated(String reasonCode) {
        Objects.requireNonNull(reasonCode, "reasonCode");
        if (reasonCode.length() > 160 || !REASON.matcher(reasonCode).matches()) {
            throw new IllegalArgumentException("reasonCode has an invalid format");
        }
        return reasonCode;
    }
}
