package com.rootopathy.careos.platform.domain;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.regex.Pattern;

final class PlatformValues {
    private static final int MAX_JSON_BYTES = 1_048_576;
    private static final Pattern KEY = Pattern.compile("[a-z][a-z0-9]*([.:-][a-z0-9]+)*");
    private static final Pattern PURPOSE = Pattern.compile("[a-z0-9][a-z0-9._:-]{0,127}");
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]*");
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    private PlatformValues() {}

    static String key(String value, String name, int maxLength) {
        Objects.requireNonNull(value, name);
        if (value.length() > maxLength || !KEY.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " has an invalid format");
        }
        return value;
    }

    static String purpose(String value) {
        Objects.requireNonNull(value, "purpose");
        if (!PURPOSE.matcher(value).matches()) {
            throw new IllegalArgumentException("purpose has an invalid format");
        }
        return value;
    }

    static String sha256(String value) {
        Objects.requireNonNull(value, "sha256");
        if (!SHA_256.matcher(value).matches()) {
            throw new IllegalArgumentException("sha256 must be 64 lowercase hexadecimal characters");
        }
        return value;
    }

    static String token(String value, String name, int maxLength) {
        Objects.requireNonNull(value, name);
        if (value.length() > maxLength || !TOKEN.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " has an invalid format");
        }
        return value;
    }

    static String mediaType(String value) {
        Objects.requireNonNull(value, "mediaType");
        if (value.isBlank()
                || value.length() > 255
                || !value.equals(value.strip())
                || value.indexOf('/') < 1
                || value.chars().anyMatch(character -> Character.isISOControl(character)
                        || Character.isWhitespace(character))) {
            throw new IllegalArgumentException("mediaType has an invalid format");
        }
        return value;
    }

    static String jsonObject(String value, String name) {
        Objects.requireNonNull(value, name);
        var json = value.strip();
        if (json.isEmpty()
                || json.getBytes(StandardCharsets.UTF_8).length > MAX_JSON_BYTES
                || !json.startsWith("{")
                || !json.endsWith("}")) {
            throw new IllegalArgumentException(name + " must be a bounded JSON object");
        }
        return json;
    }
}
