package com.rootopathy.careos.administration.application;

public final class OrganizationIdentifierException extends RuntimeException {
    private final Reason reason;
    private final String field;
    private final String fieldCode;

    public OrganizationIdentifierException(Reason reason, String message) {
        this(reason, message, null, null);
    }

    public OrganizationIdentifierException(
            Reason reason, String message, String field, String fieldCode) {
        super(message);
        this.reason = reason;
        this.field = field;
        this.fieldCode = fieldCode;
    }

    public Reason reason() {
        return reason;
    }

    public String field() {
        return field;
    }

    public String fieldCode() {
        return fieldCode;
    }

    public enum Reason {
        INVALID_REQUEST,
        NOT_FOUND,
        PRECONDITION_REQUIRED,
        STALE_REVISION,
        NO_CHANGES,
        DUPLICATE,
        EFFECTIVE_OVERLAP,
        INVALID_TRANSITION,
        PRIMARY_REPLACEMENT_REQUIRED
    }
}
