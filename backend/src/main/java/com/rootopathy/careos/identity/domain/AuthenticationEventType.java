package com.rootopathy.careos.identity.domain;

public enum AuthenticationEventType {
    LOGIN_SUCCEEDED("identity.login.succeeded"),
    LOGIN_FAILED("identity.login.failed"),
    LOGIN_THROTTLED("identity.login.throttled"),
    LOGOUT_COMPLETED("identity.logout.completed"),
    PASSWORD_RESET_REQUESTED("identity.password-reset.requested"),
    PASSWORD_RESET_COMPLETED("identity.password-reset.completed"),
    MFA_ENROLLMENT_STARTED("identity.mfa-enrollment.started"),
    MFA_ENROLLMENT_COMPLETED("identity.mfa-enrollment.completed"),
    MFA_CHALLENGE_SUCCEEDED("identity.mfa-challenge.succeeded"),
    MFA_CHALLENGE_FAILED("identity.mfa-challenge.failed"),
    RECOVERY_CODE_USED("identity.recovery-code.used"),
    RECOVERY_CODES_REGENERATED("identity.recovery-codes.regenerated"),
    RECENT_AUTHENTICATION_SUCCEEDED("identity.recent-authentication.succeeded"),
    RECENT_AUTHENTICATION_FAILED("identity.recent-authentication.failed"),
    SESSIONS_REVOKED("identity.sessions.revoked");

    private final String value;

    AuthenticationEventType(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
