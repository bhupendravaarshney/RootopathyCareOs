package com.rootopathy.careos.platform.domain;

import java.time.Instant;
import java.util.Objects;

public record DocumentRetentionDirective(String policyKey, Instant retainUntil, boolean legalHold) {
    public DocumentRetentionDirective {
        policyKey = PlatformValues.key(policyKey, "policyKey", 160);
        Objects.requireNonNull(retainUntil, "retainUntil");
    }
}
