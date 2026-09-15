package com.rootopathy.careos.identity.application;

import java.time.Duration;

public interface InvitationPolicy {
    boolean enabled();

    Duration tokenTtl();

    Duration idempotencyTtl();

    int acceptanceAttemptLimit();

    Duration acceptanceAttemptWindow();
}
