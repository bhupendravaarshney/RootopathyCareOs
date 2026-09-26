package com.rootopathy.careos.governance.domain;

import java.util.List;
import java.util.Objects;

public record GovernedMutation(
        IdempotentResponse response,
        GovernanceEvidence evidence,
        List<GovernanceEvidence> additionalEvidence) {
    public GovernedMutation(IdempotentResponse response, GovernanceEvidence evidence) {
        this(response, evidence, List.of());
    }

    public GovernedMutation {
        Objects.requireNonNull(response, "response");
        Objects.requireNonNull(evidence, "evidence");
        additionalEvidence = List.copyOf(
                additionalEvidence == null ? List.of() : additionalEvidence);
    }
}
