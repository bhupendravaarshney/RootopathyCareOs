package com.rootopathy.careos.identity.api;

import com.rootopathy.careos.governance.application.IdempotencyException;
import com.rootopathy.careos.identity.application.MembershipAdministrationException;
import com.rootopathy.careos.shared.api.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = MembershipAdministrationController.class)
public final class MembershipAdministrationProblemHandler {
    @ExceptionHandler(MembershipAdministrationException.class)
    ResponseEntity<ProblemDetail> administrationProblem(
            MembershipAdministrationException exception, HttpServletRequest request) {
        return response(mapping(exception.reason()), exception.getMessage(), request);
    }

    @ExceptionHandler(IdempotencyException.class)
    ResponseEntity<ProblemDetail> idempotencyProblem(
            IdempotencyException exception, HttpServletRequest request) {
        var reused = exception.reason() == IdempotencyException.Reason.KEY_REUSED;
        return response(
                new ProblemMapping(
                        HttpStatus.CONFLICT,
                        reused ? "idempotency-key-reused" : "request-in-progress",
                        reused ? "Idempotency key reused" : "Request in progress",
                        null),
                exception.getMessage(),
                request);
    }

    private static ProblemMapping mapping(MembershipAdministrationException.Reason reason) {
        return switch (reason) {
            case UNAVAILABLE -> new ProblemMapping(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "membership-administration-unavailable",
                    "Membership administration unavailable",
                    60);
            case TARGET_UNAVAILABLE -> new ProblemMapping(
                    HttpStatus.NOT_FOUND,
                    "membership-change-target-unavailable",
                    "Membership change target unavailable",
                    null);
            case APPROVAL_UNAVAILABLE -> new ProblemMapping(
                    HttpStatus.NOT_FOUND,
                    "membership-change-approval-unavailable",
                    "Membership change approval unavailable",
                    null);
            case APPROVAL_ALREADY_OPEN -> new ProblemMapping(
                    HttpStatus.CONFLICT,
                    "membership-change-approval-already-open",
                    "Membership change approval already open",
                    null);
            case PRECONDITION_REQUIRED -> new ProblemMapping(
                    HttpStatus.PRECONDITION_REQUIRED,
                    "membership-revision-required",
                    "Membership revision required",
                    null);
            case STALE_REVISION -> new ProblemMapping(
                    HttpStatus.PRECONDITION_FAILED,
                    "membership-revision-stale",
                    "Membership revision stale",
                    null);
            case CONFLICT -> new ProblemMapping(
                    HttpStatus.CONFLICT,
                    "membership-change-conflict",
                    "Membership change conflict",
                    null);
            case INVALID_REQUEST -> new ProblemMapping(
                    HttpStatus.BAD_REQUEST,
                    "membership-administration-request-invalid",
                    "Invalid membership administration request",
                    null);
        };
    }

    private static ResponseEntity<ProblemDetail> response(
            ProblemMapping mapping, String detail, HttpServletRequest request) {
        var problem = ProblemDetail.forStatusAndDetail(mapping.status(), detail);
        problem.setType(URI.create("https://careos.example/problems/" + mapping.code()));
        problem.setTitle(mapping.title());
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", mapping.code());
        problem.setProperty("correlationId", CorrelationIdFilter.from(request));
        var response = ResponseEntity.status(mapping.status())
                .cacheControl(org.springframework.http.CacheControl.noStore())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON);
        if (mapping.retryAfterSeconds() != null) {
            response.header(HttpHeaders.RETRY_AFTER, mapping.retryAfterSeconds().toString());
        }
        return response.body(problem);
    }

    private record ProblemMapping(
            HttpStatus status, String code, String title, Integer retryAfterSeconds) {}
}
