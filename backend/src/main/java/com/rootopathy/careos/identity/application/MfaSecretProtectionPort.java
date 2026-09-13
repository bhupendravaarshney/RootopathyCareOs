package com.rootopathy.careos.identity.application;

public interface MfaSecretProtectionPort {
    String protect(String plaintext);

    String reveal(String protectedValue);
}
