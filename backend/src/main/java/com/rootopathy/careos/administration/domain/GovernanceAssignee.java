package com.rootopathy.careos.administration.domain;

import java.util.Objects;
import java.util.UUID;

public record GovernanceAssignee(UUID id, String type, String display) {
    public GovernanceAssignee {
        Objects.requireNonNull(id);
        Objects.requireNonNull(type);
        Objects.requireNonNull(display);
        if (!type.equals("membership") && !type.equals("external_contact")) {
            throw new IllegalArgumentException("invalid governance assignee type");
        }
    }
}
