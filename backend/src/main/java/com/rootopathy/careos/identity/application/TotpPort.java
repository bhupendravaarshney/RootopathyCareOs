package com.rootopathy.careos.identity.application;

import java.time.Instant;

public interface TotpPort {
    String newSecret();

    boolean verify(String secret, String code, Instant now);

    String provisioningUri(String issuer, String accountName, String secret);
}
