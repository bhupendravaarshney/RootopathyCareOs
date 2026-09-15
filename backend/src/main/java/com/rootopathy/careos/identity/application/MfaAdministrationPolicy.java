package com.rootopathy.careos.identity.application;

import java.time.Duration;

public interface MfaAdministrationPolicy {
    boolean enabled();

    Duration approvalTtl();

    Duration idempotencyTtl();
}
