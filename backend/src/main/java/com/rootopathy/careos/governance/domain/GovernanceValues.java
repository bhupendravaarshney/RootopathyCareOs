package com.rootopathy.careos.governance.domain;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.regex.Pattern;

final class GovernanceValues {
    static final int MAX_JSON_BYTES = 1_048_576;
    private static final Pattern REGISTRY_KEY =
            Pattern.compile("[a-z][a-z0-9]*([.:-][a-z0-9]+)*");
    private static final Pattern TYPE_KEY =
            Pattern.compile("[a-z][a-z0-9]*([._:-][a-z0-9]+)*");
    private static final Pattern CORRELATION_ID = Pattern.compile("[A-Za-z0-9._:-]{1,128}");

    private GovernanceValues() {}

    static String registryKey(String value, String name, int maxLength) {
        return matching(value, name, maxLength, REGISTRY_KEY);
    }

    static String typeKey(String value, String name, int maxLength) {
        return matching(value, name, maxLength, TYPE_KEY);
    }

    static String correlationId(String value, String name) {
        return matching(value, name, 128, CORRELATION_ID);
    }

    static String jsonObject(String value, String name) {
        var json = boundedJson(value, name);
        if (!json.startsWith("{") || !json.endsWith("}")) {
            throw new IllegalArgumentException(name + " must be a JSON object");
        }
        return json;
    }

    static String boundedJson(String value, String name) {
        Objects.requireNonNull(value, name);
        var json = value.strip();
        if (json.isEmpty() || json.getBytes(StandardCharsets.UTF_8).length > MAX_JSON_BYTES) {
            throw new IllegalArgumentException(name + " has an invalid size");
        }
        return json;
    }

    static String optionalReason(String value) {
        if (value == null) {
            return null;
        }
        var reason = value.strip();
        if (reason.isEmpty() || reason.length() > 2_000) {
            throw new IllegalArgumentException("reason has an invalid size");
        }
        return reason;
    }

    private static String matching(String value, String name, int maxLength, Pattern pattern) {
        Objects.requireNonNull(value, name);
        if (value.length() > maxLength || !pattern.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " has an invalid format");
        }
        return value;
    }
}
