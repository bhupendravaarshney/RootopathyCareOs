package com.rootopathy.careos.administration.application;

import java.time.Instant;
import java.util.UUID;

public interface OrganizationMembershipCursorCodec {
    String encode(CursorBinding binding, CursorPosition position);

    CursorPosition decode(String cursor, CursorBinding binding);

    record CursorBinding(UUID organizationId, String filterDigest, int limit) {}

    record CursorPosition(Instant asOf, Instant effectiveFrom, UUID membershipId) {}
}
