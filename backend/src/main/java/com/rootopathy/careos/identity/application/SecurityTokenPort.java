package com.rootopathy.careos.identity.application;

public interface SecurityTokenPort {
    String newOpaqueToken();

    String newRecoveryCode();

    String digest(String value);
}
