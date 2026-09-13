package com.rootopathy.careos.governance.domain;

import java.util.Objects;

public record IdempotencyOutcome(IdempotentResponse response, boolean replayed) {
    public IdempotencyOutcome {
        Objects.requireNonNull(response, "response");
    }
}
