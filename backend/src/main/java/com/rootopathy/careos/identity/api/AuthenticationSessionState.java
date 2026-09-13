package com.rootopathy.careos.identity.api;

public final class AuthenticationSessionState {
    public static final String AUTHENTICATED_AT = "careos.authenticatedAt";
    public static final String RECENT_AUTHENTICATION_AT = "careos.recentAuthenticationAt";
    public static final String MFA_AUTHENTICATED_AT = "careos.mfaAuthenticatedAt";

    private AuthenticationSessionState() {}
}
