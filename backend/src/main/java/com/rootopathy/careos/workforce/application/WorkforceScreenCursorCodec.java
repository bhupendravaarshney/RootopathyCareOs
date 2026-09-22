package com.rootopathy.careos.workforce.application;

import java.time.Instant;
import java.util.UUID;

public interface WorkforceScreenCursorCodec {
    String encode(Binding binding, Position position);

    Position decode(String cursor, Binding binding);

    record Binding(
            UUID organizationId,
            UUID actorId,
            String screenId,
            UUID memberId,
            String search,
            String status,
            int pageSize) {}

    record Position(Instant asOf, int offset, String snapshotDigest) {
        public Position {
            if (snapshotDigest != null && !snapshotDigest.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("snapshotDigest must be a lowercase SHA-256 digest");
            }
        }
    }
}
