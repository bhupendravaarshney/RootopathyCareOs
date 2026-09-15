package com.rootopathy.careos.identity.application;

import java.util.Objects;

public final class InvitationException extends RuntimeException {
    public enum Reason {
        UNAVAILABLE,
        INVALID_REQUEST,
        ROLE_NOT_ASSIGNABLE,
        ALREADY_PENDING,
        INVITATION_NOT_FOUND,
        INVALID_OR_EXPIRED_TOKEN,
        AUTHENTICATION_REQUIRED,
        ACCOUNT_MISMATCH,
        ACCOUNT_UNAVAILABLE,
        PASSWORD_REQUIRED,
        ALREADY_MEMBER,
        THROTTLED
    }

    private final Reason reason;

    public InvitationException(Reason reason, String safeMessage) {
        super(Objects.requireNonNull(safeMessage, "safeMessage"));
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public Reason reason() {
        return reason;
    }
}
