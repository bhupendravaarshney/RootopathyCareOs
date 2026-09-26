package com.rootopathy.careos.encounter.api;

import com.rootopathy.careos.encounter.application.EncounterException;
import com.rootopathy.careos.governance.application.IdempotencyException;
import com.rootopathy.careos.shared.api.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = EncounterController.class)
public final class EncounterProblemHandler {
    @ExceptionHandler(EncounterException.class)
    ResponseEntity<ProblemDetail> encounter(
            EncounterException exception, HttpServletRequest request) {
        var status = switch (exception.reason()) {
            case INVALID -> HttpStatus.BAD_REQUEST;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONFLICT -> HttpStatus.CONFLICT;
            case PRECONDITION_REQUIRED -> HttpStatus.PRECONDITION_REQUIRED;
            case STALE -> HttpStatus.PRECONDITION_FAILED;
            case POLICY_UNAVAILABLE, DEPENDENCY_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
        };
        return response(status, "encounter-" + code(exception.reason()), exception.getMessage(), request);
    }

    @ExceptionHandler(IdempotencyException.class)
    ResponseEntity<ProblemDetail> idempotency(
            IdempotencyException exception, HttpServletRequest request) {
        return response(
                HttpStatus.CONFLICT,
                "encounter-idempotency-conflict",
                "The idempotency key is already bound to a different encounter request.",
                request);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ProblemDetail> integrity(
            DataIntegrityViolationException exception, HttpServletRequest request) {
        return response(
                HttpStatus.CONFLICT,
                "encounter-integrity-conflict",
                "The encounter change conflicts with current lifecycle, signature, eligibility, or safety state.",
                request);
    }

    @ExceptionHandler(EmptyResultDataAccessException.class)
    ResponseEntity<ProblemDetail> missing(
            EmptyResultDataAccessException exception, HttpServletRequest request) {
        return response(
                HttpStatus.NOT_FOUND,
                "encounter-not-found",
                "The requested encounter resource is unavailable.",
                request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ProblemDetail> invalid(
            IllegalArgumentException exception, HttpServletRequest request) {
        return response(
                HttpStatus.BAD_REQUEST,
                "encounter-invalid-request",
                exception.getMessage() == null ? "The encounter request is invalid." : exception.getMessage(),
                request);
    }

    private static ResponseEntity<ProblemDetail> response(
            HttpStatus status, String code, String detail, HttpServletRequest request) {
        var problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle("Encounter request failed");
        problem.setType(URI.create("https://careos.example/problems/" + code));
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", code);
        problem.setProperty("correlationId", CorrelationIdFilter.from(request));
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }

    private static String code(EncounterException.Reason reason) {
        return reason.name().toLowerCase().replace('_', '-');
    }
}
