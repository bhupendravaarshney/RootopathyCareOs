package com.rootopathy.careos.administration.api;

import com.rootopathy.careos.administration.application.OrganizationIdentifierException;
import com.rootopathy.careos.governance.application.IdempotencyException;
import com.rootopathy.careos.shared.api.CorrelationIdFilter;
import com.rootopathy.careos.shared.api.FieldViolation;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.List;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = OrganizationIdentifierController.class)
public final class OrganizationIdentifierProblemHandler {
    @ExceptionHandler(OrganizationIdentifierException.class)
    ResponseEntity<ProblemDetail> identifierProblem(
            OrganizationIdentifierException exception, HttpServletRequest request) {
        var mapping = switch (exception.reason()) {
            case INVALID_REQUEST -> new ProblemMapping(
                    HttpStatus.BAD_REQUEST,
                    "organization-identifier-invalid",
                    "Invalid organization identifier");
            case NOT_FOUND -> new ProblemMapping(
                    HttpStatus.NOT_FOUND, "resource-not-found", "Resource not found");
            case PRECONDITION_REQUIRED -> new ProblemMapping(
                    HttpStatus.PRECONDITION_REQUIRED,
                    "identifier-precondition-required",
                    "Identifier precondition required");
            case STALE_REVISION -> new ProblemMapping(
                    HttpStatus.PRECONDITION_FAILED,
                    "identifier-stale-revision",
                    "Organization identifier changed");
            case NO_CHANGES -> new ProblemMapping(
                    HttpStatus.CONFLICT, "identifier-no-changes", "No identifier changes");
            case DUPLICATE -> new ProblemMapping(
                    HttpStatus.CONFLICT,
                    "m1.duplicate",
                    "Duplicate organization identifier");
            case EFFECTIVE_OVERLAP -> new ProblemMapping(
                    HttpStatus.CONFLICT,
                    "m1.effective.overlap",
                    "Primary identifier overlap");
            case INVALID_TRANSITION -> new ProblemMapping(
                    HttpStatus.CONFLICT,
                    "m1.lifecycle.invalid_transition",
                    "Invalid identifier transition");
            case PRIMARY_REPLACEMENT_REQUIRED -> new ProblemMapping(
                    HttpStatus.CONFLICT,
                    "identifier-primary-replacement-required",
                    "Primary replacement required");
        };
        var response = response(mapping, exception.getMessage(), request);
        if (exception.reason() == OrganizationIdentifierException.Reason.INVALID_REQUEST
                && exception.field() != null
                && exception.fieldCode() != null
                && response.getBody() != null) {
            response.getBody()
                    .setProperty(
                            "errors",
                            List.of(new FieldViolation(
                                    "/" + exception.field(),
                                    exception.fieldCode(),
                                    exception.getMessage())));
        }
        return response;
    }

    @ExceptionHandler(IdempotencyException.class)
    ResponseEntity<ProblemDetail> idempotencyProblem(
            IdempotencyException exception, HttpServletRequest request) {
        var reused = exception.reason() == IdempotencyException.Reason.KEY_REUSED;
        return response(
                new ProblemMapping(
                        HttpStatus.CONFLICT,
                        reused ? "idempotency-key-reused" : "request-in-progress",
                        reused ? "Idempotency key reused" : "Request in progress"),
                exception.getMessage(),
                request);
    }

    private static ResponseEntity<ProblemDetail> response(
            ProblemMapping mapping, String detail, HttpServletRequest request) {
        var problem = ProblemDetail.forStatusAndDetail(mapping.status(), detail);
        problem.setType(URI.create("https://careos.example/problems/" + mapping.code()));
        problem.setTitle(mapping.title());
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", mapping.code());
        problem.setProperty("correlationId", CorrelationIdFilter.from(request));
        return ResponseEntity.status(mapping.status())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }

    private record ProblemMapping(HttpStatus status, String code, String title) {}
}
