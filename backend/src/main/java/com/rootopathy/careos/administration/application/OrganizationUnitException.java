package com.rootopathy.careos.administration.application;
public final class OrganizationUnitException extends RuntimeException {public enum Reason{PRECONDITION_REQUIRED,STALE,CONFLICT,NOT_FOUND}private final Reason reason;public OrganizationUnitException(Reason r,String m){super(m);reason=r;}public Reason reason(){return reason;}}
