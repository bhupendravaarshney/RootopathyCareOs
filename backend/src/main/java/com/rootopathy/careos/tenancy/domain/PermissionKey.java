package com.rootopathy.careos.tenancy.domain;

import java.util.regex.Pattern;

/** Stable machine key from the owner-approved permission registry. */
public record PermissionKey(String value) {
    private static final Pattern SAFE_KEY = Pattern.compile("[a-z][a-z0-9]*(?:[.:-][a-z0-9]+)*");

    public PermissionKey {
        if (value == null || value.length() > 120 || !SAFE_KEY.matcher(value).matches()) {
            throw new IllegalArgumentException("permission key has an invalid format");
        }
    }
}
