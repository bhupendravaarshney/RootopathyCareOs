package com.rootopathy.careos.document.api;

import com.rootopathy.careos.document.application.DocumentException;
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

@RestControllerAdvice(assignableTypes = DocumentController.class)
public final class DocumentProblemHandler {
    @ExceptionHandler(DocumentException.class)
    ResponseEntity<ProblemDetail> document(
            DocumentException exception, HttpServletRequest request) {
        var status = switch (exception.reason()) {
            case INVALID -> HttpStatus.BAD_REQUEST;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONFLICT -> HttpStatus.CONFLICT;
            case PRECONDITION_REQUIRED -> HttpStatus.PRECONDITION_REQUIRED;
            case STALE -> HttpStatus.PRECONDITION_FAILED;
            case POLICY_UNAVAILABLE, DEPENDENCY_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
        };
        return response(status, "document-" + code(exception.reason()), exception.getMessage(), request);
    }

    @ExceptionHandler(IdempotencyException.class)
    ResponseEntity<ProblemDetail> idempotency(
            IdempotencyException exception, HttpServletRequest request) {
        return response(
                HttpStatus.CONFLICT,
                "document-idempotency-conflict",
                "The idempotency key is already bound to a different document request.",
                request);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ProblemDetail> integrity(
            DataIntegrityViolationException exception, HttpServletRequest request) {
        return response(
                HttpStatus.CONFLICT,
                "document-integrity-conflict",
                "The document or result change conflicts with current version, provenance, scan, or review state.",
                request);
    }

    @ExceptionHandler(EmptyResultDataAccessException.class)
    ResponseEntity<ProblemDetail> missing(
            EmptyResultDataAccessException exception, HttpServletRequest request) {
        return response(
                HttpStatus.NOT_FOUND,
                "document-not-found",
                "The requested document or result resource is unavailable.",
                request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ProblemDetail> invalid(
            IllegalArgumentException exception, HttpServletRequest request) {
        return response(
                HttpStatus.BAD_REQUEST,
                "document-invalid-request",
                exception.getMessage() == null ? "The document request is invalid." : exception.getMessage(),
                request);
    }

    private static ResponseEntity<ProblemDetail> response(
            HttpStatus status, String code, String detail, HttpServletRequest request) {
        var problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle("Document or result request failed");
        problem.setType(URI.create("https://careos.example/problems/" + code));
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", code);
        problem.setProperty("correlationId", CorrelationIdFilter.from(request));
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }

    private static String code(DocumentException.Reason reason) {
        return reason.name().toLowerCase().replace('_', '-');
    }
}
