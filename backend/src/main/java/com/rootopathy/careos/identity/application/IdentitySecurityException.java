package com.rootopathy.careos.identity.application;

public final class IdentitySecurityException extends RuntimeException {
    public enum Reason {
        INVALID_OR_EXPIRED_TOKEN,
        WEAK_PASSWORD,
        INVALID_MFA_CODE,
        MFA_ALREADY_ENABLED,
        MFA_ENROLLMENT_NOT_FOUND,
        ACCOUNT_UNAVAILABLE,
        RECENT_AUTHENTICATION_REQUIRED
    }

    private final Reason reason;

    public IdentitySecurityException(Reason reason, String safeMessage) {
        super(safeMessage);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
