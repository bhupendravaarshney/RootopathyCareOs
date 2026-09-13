package com.rootopathy.careos.governance.domain;

import java.util.Objects;

public record GovernedMutation(IdempotentResponse response, GovernanceEvidence evidence) {
    public GovernedMutation {
        Objects.requireNonNull(response, "response");
        Objects.requireNonNull(evidence, "evidence");
    }
}
