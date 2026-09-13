package com.rootopathy.careos.identity.application;

import java.time.Duration;

public interface SecurityRateLimitPort {
    boolean consume(String scope, String discriminator, int limit, Duration window);

    void clear(String scope, String discriminator);
}
