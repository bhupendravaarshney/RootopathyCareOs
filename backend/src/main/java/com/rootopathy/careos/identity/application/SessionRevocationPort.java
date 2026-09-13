package com.rootopathy.careos.identity.application;

public interface SessionRevocationPort {
    void revokeAllForPrincipal(String principalName);
}
