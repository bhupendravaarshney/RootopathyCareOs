package com.rootopathy.careos.tenancy.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("careos.service-identities")
public record ServiceIdentityProperties(boolean enabled, String credentialPepper) {
    public ServiceIdentityProperties {
        credentialPepper = credentialPepper == null ? "" : credentialPepper;
        if (enabled && credentialPepper.length() < 32) {
            throw new IllegalArgumentException(
                    "service identity credentialPepper must contain at least 32 characters when enabled");
        }
    }
}
