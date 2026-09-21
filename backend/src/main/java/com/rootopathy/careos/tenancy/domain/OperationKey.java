package com.rootopathy.careos.tenancy.domain;

import java.util.regex.Pattern;

/** Stable machine key from the migration-owned operation policy registry. */
public record OperationKey(String value) {
    private static final Pattern SAFE_KEY =
            Pattern.compile("[a-z][a-z0-9_]*(?:[.:-][a-z0-9_]+)*");

    public OperationKey {
        if (value == null || value.length() > 160 || !SAFE_KEY.matcher(value).matches()) {
            throw new IllegalArgumentException("operation key has an invalid format");
        }
    }
}
