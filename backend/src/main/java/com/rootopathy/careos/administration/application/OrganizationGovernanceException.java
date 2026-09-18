package com.rootopathy.careos.administration.application;

public final class OrganizationGovernanceException extends RuntimeException {
    public enum Reason { INVALID_REQUEST, NOT_FOUND, PRECONDITION_REQUIRED, STALE_REVISION, CONFLICT }
    private final Reason reason;
    private final String field;
    public OrganizationGovernanceException(Reason reason, String message) { this(reason, message, null); }
    public OrganizationGovernanceException(Reason reason, String message, String field) {
        super(message); this.reason = reason; this.field = field;
    }
    public Reason reason() { return reason; }
    public String field() { return field; }
}
