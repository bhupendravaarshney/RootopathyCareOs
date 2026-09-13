package com.rootopathy.careos.platform.application;

import java.util.Objects;
import java.util.regex.Pattern;

/** Stable storage failure without vendor error text, endpoint details, object keys, or content. */
public final class DocumentStorageException extends RuntimeException {
    private static final Pattern ERROR_CODE = Pattern.compile("[a-z][a-z0-9]*([.-][a-z0-9]+)*");

    private final String errorCode;

    public DocumentStorageException(String errorCode) {
        super(requireErrorCode(errorCode));
        this.errorCode = errorCode;
    }

    public String errorCode() {
        return errorCode;
    }

    private static String requireErrorCode(String value) {
        Objects.requireNonNull(value, "errorCode");
        if (value.length() > 160 || !ERROR_CODE.matcher(value).matches()) {
            throw new IllegalArgumentException("errorCode has an invalid format");
        }
        return value;
    }
}
