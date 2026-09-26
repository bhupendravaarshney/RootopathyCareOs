package com.rootopathy.careos.assessment.api;

import com.rootopathy.careos.assessment.application.AssessmentException;
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

@RestControllerAdvice(assignableTypes = AssessmentController.class)
public final class AssessmentProblemHandler {
    @ExceptionHandler(AssessmentException.class)
    ResponseEntity<ProblemDetail> assessment(
            AssessmentException exception, HttpServletRequest request) {
        var status = switch (exception.reason()) {
            case INVALID -> HttpStatus.BAD_REQUEST;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONFLICT -> HttpStatus.CONFLICT;
            case PRECONDITION_REQUIRED -> HttpStatus.PRECONDITION_REQUIRED;
            case STALE -> HttpStatus.PRECONDITION_FAILED;
            case POLICY_UNAVAILABLE, DEPENDENCY_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
        };
        return response(status, "assessment-" + code(exception.reason()), exception.getMessage(), request);
    }

    @ExceptionHandler(IdempotencyException.class)
    ResponseEntity<ProblemDetail> idempotency(
            IdempotencyException exception, HttpServletRequest request) {
        return response(
                HttpStatus.CONFLICT,
                "assessment-idempotency-conflict",
                "The idempotency key is already bound to a different assessment request.",
                request);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ProblemDetail> integrity(
            DataIntegrityViolationException exception, HttpServletRequest request) {
        return response(
                HttpStatus.CONFLICT,
                "assessment-integrity-conflict",
                "The assessment change conflicts with current lifecycle, signature, eligibility, or safety state.",
                request);
    }

    @ExceptionHandler(EmptyResultDataAccessException.class)
    ResponseEntity<ProblemDetail> missing(
            EmptyResultDataAccessException exception, HttpServletRequest request) {
        return response(
                HttpStatus.NOT_FOUND,
                "assessment-not-found",
                "The requested assessment resource is unavailable.",
                request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ProblemDetail> invalid(
            IllegalArgumentException exception, HttpServletRequest request) {
        return response(
                HttpStatus.BAD_REQUEST,
                "assessment-invalid-request",
                exception.getMessage() == null ? "The assessment request is invalid." : exception.getMessage(),
                request);
    }

    private static ResponseEntity<ProblemDetail> response(
            HttpStatus status, String code, String detail, HttpServletRequest request) {
        var problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle("Assessment request failed");
        problem.setType(URI.create("https://careos.example/problems/" + code));
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", code);
        problem.setProperty("correlationId", CorrelationIdFilter.from(request));
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }

    private static String code(AssessmentException.Reason reason) {
        return reason.name().toLowerCase().replace('_', '-');
    }
}
