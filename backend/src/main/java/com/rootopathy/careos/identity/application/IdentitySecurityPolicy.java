package com.rootopathy.careos.identity.application;

import java.time.Duration;

public interface IdentitySecurityPolicy {
    Duration sessionAbsoluteTimeout();

    Duration passwordResetTokenTtl();

    int recoveryCodeCount();
}
