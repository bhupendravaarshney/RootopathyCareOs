package com.rootopathy.careos.governance.domain;

import java.util.Objects;
import java.util.regex.Pattern;

public record IdempotentResponse(int statusCode, String mediaType, String bodyJson) {
    private static final Pattern MEDIA_TYPE =
            Pattern.compile("[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+(?:;[ A-Za-z0-9=._+-]+)?");

    public IdempotentResponse {
        if (statusCode < 200 || statusCode > 599) {
            throw new IllegalArgumentException("statusCode must be between 200 and 599");
        }
        Objects.requireNonNull(mediaType, "mediaType");
        if (mediaType.length() > 120 || !MEDIA_TYPE.matcher(mediaType).matches()) {
            throw new IllegalArgumentException("mediaType has an invalid format");
        }
        bodyJson = GovernanceValues.boundedJson(bodyJson, "bodyJson");
    }
}
