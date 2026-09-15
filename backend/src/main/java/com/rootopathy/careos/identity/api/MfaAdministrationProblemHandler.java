package com.rootopathy.careos.identity.api;

import com.rootopathy.careos.governance.application.IdempotencyException;
import com.rootopathy.careos.identity.application.MfaAdministrationException;
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
@RestControllerAdvice(assignableTypes = MfaAdministrationController.class)
public final class MfaAdministrationProblemHandler {
    @ExceptionHandler(MfaAdministrationException.class)
    ResponseEntity<ProblemDetail> administrationProblem(
            MfaAdministrationException exception, HttpServletRequest request) {
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

    private static ProblemMapping mapping(MfaAdministrationException.Reason reason) {
        return switch (reason) {
            case UNAVAILABLE -> new ProblemMapping(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "mfa-administration-unavailable",
                    "MFA administration unavailable",
                    60);
            case TARGET_UNAVAILABLE -> new ProblemMapping(
                    HttpStatus.NOT_FOUND,
                    "mfa-reset-target-unavailable",
                    "MFA reset target unavailable",
                    null);
            case TARGET_MFA_NOT_ENABLED -> new ProblemMapping(
                    HttpStatus.CONFLICT,
                    "mfa-not-enabled",
                    "MFA is not enabled",
                    null);
            case APPROVAL_UNAVAILABLE -> new ProblemMapping(
                    HttpStatus.NOT_FOUND,
                    "mfa-reset-approval-unavailable",
                    "MFA reset approval unavailable",
                    null);
            case APPROVAL_ALREADY_OPEN -> new ProblemMapping(
                    HttpStatus.CONFLICT,
                    "mfa-reset-approval-already-open",
                    "MFA reset approval already open",
                    null);
            case INVALID_REQUEST -> new ProblemMapping(
                    HttpStatus.BAD_REQUEST,
                    "mfa-administration-request-invalid",
                    "Invalid MFA administration request",
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
                .contentType(MediaType.APPLICATION_PROBLEM_JSON);
        if (mapping.retryAfterSeconds() != null) {
            response.header(HttpHeaders.RETRY_AFTER, mapping.retryAfterSeconds().toString());
        }
        return response.body(problem);
    }

    private record ProblemMapping(
            HttpStatus status, String code, String title, Integer retryAfterSeconds) {}
}
