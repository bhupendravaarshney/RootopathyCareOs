package com.rootopathy.careos.identity.application;

import java.time.Instant;

public interface SecurityNotificationPort {
    void sendPasswordReset(String email, String rawToken, Instant expiresAt);

    void sendInvitation(String email, String rawToken, Instant expiresAt);

    void sendMfaAdministrativelyReset(String email);
}
