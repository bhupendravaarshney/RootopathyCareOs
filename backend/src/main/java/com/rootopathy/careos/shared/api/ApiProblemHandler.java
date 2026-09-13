package com.rootopathy.careos.shared.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.net.URI;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public final class ApiProblemHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(ApiProblemHandler.class);
    private static final String TYPE_ROOT = "https://careos.example/problems/";

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemDetail> invalidBody(
            MethodArgumentNotValidException exception, HttpServletRequest request) {
        List<FieldViolation> violations = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldViolation(
                        toJsonPointer(error.getField()),
                        error.getCode() == null ? "invalid" : error.getCode(),
                        error.getDefaultMessage() == null ? "Invalid value" : error.getDefaultMessage()))
                .toList();
        var problem = problem(
                HttpStatus.BAD_REQUEST,
                "request-validation",
                "Request validation failed",
                "One or more request fields are invalid.",
                request);
        problem.setProperty("errors", violations);
        return response(problem);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ProblemDetail> invalidParameters(
            ConstraintViolationException exception, HttpServletRequest request) {
        List<FieldViolation> violations = exception.getConstraintViolations().stream()
                .map(violation -> new FieldViolation(
                        toJsonPointer(violation.getPropertyPath().toString()),
                        "invalid",
                        violation.getMessage()))
                .toList();
        var problem = problem(
                HttpStatus.BAD_REQUEST,
                "request-validation",
                "Request validation failed",
                "One or more request parameters are invalid.",
                request);
        problem.setProperty("errors", violations);
        return response(problem);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ProblemDetail> unreadableBody(
            HttpMessageNotReadableException exception, HttpServletRequest request) {
        return response(problem(
                HttpStatus.BAD_REQUEST,
                "malformed-request",
                "Malformed request",
                "The request body could not be read.",
                request));
    }

    @ExceptionHandler(ApiProblemException.class)
    ResponseEntity<ProblemDetail> knownProblem(ApiProblemException exception, HttpServletRequest request) {
        return response(problem(
                exception.status(), exception.code(), exception.title(), exception.getMessage(), request));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> unexpectedProblem(Exception exception, HttpServletRequest request) {
        var correlationId = CorrelationIdFilter.from(request);
        LOGGER.error(
                "Unhandled request failure; correlationId={}, exceptionType={}",
                correlationId,
                exception.getClass().getName());
        return response(problem(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "internal-error",
                "Internal server error",
                "The request could not be completed.",
                request));
    }

    private static ProblemDetail problem(
            HttpStatus status, String code, String title, String detail, HttpServletRequest request) {
        var problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create(TYPE_ROOT + code));
        problem.setTitle(title);
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", code);
        problem.setProperty("correlationId", CorrelationIdFilter.from(request));
        return problem;
    }

    private static ResponseEntity<ProblemDetail> response(ProblemDetail problem) {
        return ResponseEntity.status(problem.getStatus())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }

    private static String toJsonPointer(String propertyPath) {
        if (propertyPath == null || propertyPath.isBlank()) {
            return "/";
        }
        return "/" + propertyPath.replace("~", "~0").replace("/", "~1").replace('.', '/');
    }
}
