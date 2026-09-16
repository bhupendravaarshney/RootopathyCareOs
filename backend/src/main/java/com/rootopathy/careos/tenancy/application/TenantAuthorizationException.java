package com.rootopathy.careos.tenancy.application;

public final class TenantAuthorizationException extends RuntimeException {
    public enum Reason {
        MEMBERSHIP_NOT_FOUND,
        PERMISSION_DENIED,
        REASON_REQUIRED,
        RECENT_AUTHENTICATION_REQUIRED,
        MFA_REQUIRED,
        INDEPENDENT_APPROVAL_REQUIRED
    }

    private final Reason reason;

    public TenantAuthorizationException(Reason reason, String safeMessage) {
        super(safeMessage);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
