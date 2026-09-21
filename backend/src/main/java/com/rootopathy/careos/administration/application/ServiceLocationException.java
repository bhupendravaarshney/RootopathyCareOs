package com.rootopathy.careos.administration.application;

public final class ServiceLocationException extends RuntimeException {
    public enum Reason { PRECONDITION_REQUIRED, STALE, CONFLICT, NOT_FOUND }

    private final Reason reason;

    public ServiceLocationException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
