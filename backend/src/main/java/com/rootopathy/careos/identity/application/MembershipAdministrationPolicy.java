package com.rootopathy.careos.identity.application;

import java.time.Duration;

public interface MembershipAdministrationPolicy {
    boolean enabled();

    Duration approvalTtl();

    Duration idempotencyTtl();
}
