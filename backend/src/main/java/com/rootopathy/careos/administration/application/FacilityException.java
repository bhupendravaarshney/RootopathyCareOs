package com.rootopathy.careos.administration.application;

public final class FacilityException extends RuntimeException {
 public enum Reason { INVALID, PRECONDITION_REQUIRED, STALE, CONFLICT, NOT_FOUND }
 private final Reason reason;
 public FacilityException(Reason reason,String message){super(message);this.reason=reason;}
 public Reason reason(){return reason;}
}
