package com.rootopathy.careos.administration.domain;

import java.text.Normalizer;
import java.util.Objects;
import java.util.regex.Pattern;

public record OrganizationContactPurpose(
        String key, String displayName, boolean publicProjectionAllowed) {
    private static final Pattern REGISTRY_KEY =
            Pattern.compile("[a-z][a-z0-9]*([._:-][a-z0-9]+)*");

    public OrganizationContactPurpose {
        key = requireText(key, "key", 1, 80);
        if (!REGISTRY_KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("key must be a registry key");
        }
        displayName = requireText(displayName, "displayName", 2, 120);
    }

    private static String requireText(String value, String name, int minimum, int maximum) {
        var checked = Objects.requireNonNull(value, name);
        var length = checked.codePointCount(0, checked.length());
        if (checked.isBlank()
                || !checked.equals(checked.strip())
                || !Normalizer.isNormalized(checked, Normalizer.Form.NFC)
                || checked.codePoints().anyMatch(Character::isISOControl)
                || length < minimum
                || length > maximum) {
            throw new IllegalArgumentException(name + " has an invalid format or length");
        }
        return checked;
    }
}
