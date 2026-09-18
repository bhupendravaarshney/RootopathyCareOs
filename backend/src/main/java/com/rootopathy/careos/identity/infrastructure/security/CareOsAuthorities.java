package com.rootopathy.careos.identity.infrastructure.security;

public final class CareOsAuthorities {
    public static final String AUTHENTICATED = "CAREOS_AUTHENTICATED";
    public static final String MFA_ENROLLMENT_PENDING = "CAREOS_MFA_ENROLLMENT_PENDING";
    public static final String MFA_PENDING = "CAREOS_MFA_PENDING";

    private CareOsAuthorities() {}
}
