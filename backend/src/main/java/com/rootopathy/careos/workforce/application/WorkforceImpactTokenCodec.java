package com.rootopathy.careos.workforce.application;

import java.time.Instant;
import java.util.UUID;

public interface WorkforceImpactTokenCodec {
    String encode(Binding binding, String impactDigest, Instant expiresAt);

    Decoded decode(String token, Binding binding);

    record Binding(
            UUID organizationId,
            UUID actorId,
            String screenId,
            String actionKey,
            UUID targetId,
            long revision,
            String requestDigest) {}

    record Decoded(String impactDigest, Instant expiresAt) {}
}
