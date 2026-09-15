package com.rootopathy.careos.platform.application;

import java.util.Objects;
import java.util.regex.Pattern;

/** Stable clean-promotion rejection without storage keys, content, or vendor details. */
public final class DocumentPromotionException extends RuntimeException {
    private static final Pattern REASON = Pattern.compile("[a-z][a-z0-9]*([.-][a-z0-9]+)*");

    private final String reasonCode;

    public DocumentPromotionException(String reasonCode) {
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
