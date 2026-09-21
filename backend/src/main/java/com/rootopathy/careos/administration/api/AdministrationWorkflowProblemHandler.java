package com.rootopathy.careos.administration.api;

import com.rootopathy.careos.governance.application.IdempotencyException;
import com.rootopathy.careos.shared.api.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.Locale;
import java.util.NoSuchElementException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = {
    ConfigurationActivationController.class,
    EvidenceExportController.class,
    EvidenceProjectionController.class,
    IdentifierSchemeController.class,
    OperatingHoursController.class,
    ServiceAssignmentController.class,
    ServiceCatalogueController.class
})
public final class AdministrationWorkflowProblemHandler {
  private static final String TYPE_ROOT = "https://careos.example/problems/";

  @ExceptionHandler(IllegalArgumentException.class)
  ResponseEntity<ProblemDetail> invalid(
      IllegalArgumentException exception, HttpServletRequest request) {
    var detail = exception.getMessage() == null ? "The request is invalid." : exception.getMessage();
    var normalized = detail.toLowerCase(Locale.ROOT);
    if (normalized.contains("if-match required")) {
      return response(
          HttpStatus.PRECONDITION_REQUIRED,
          "precondition-required",
          "Precondition required",
          detail,
          request);
    }
    if (normalized.startsWith("stale ") || normalized.contains("stale revision")) {
      return response(
          HttpStatus.PRECONDITION_FAILED,
          "stale-revision",
          "Stale revision",
          detail,
          request);
    }
    if (normalized.contains("unavailable")
        || normalized.contains("invalid transition")
        || normalized.contains("changed after approval")
        || normalized.contains("blockers remain")) {
      return response(
          HttpStatus.CONFLICT,
          "workflow-conflict",
          "Workflow conflict",
          detail,
          request);
    }
    return response(
        HttpStatus.BAD_REQUEST,
        "invalid-administration-request",
        "Invalid administration request",
        detail,
        request);
  }

  @ExceptionHandler(NoSuchElementException.class)
  ResponseEntity<ProblemDetail> notFound(
      NoSuchElementException exception, HttpServletRequest request) {
    return response(
        HttpStatus.NOT_FOUND,
        "administration-resource-not-found",
        "Administration resource not found",
        exception.getMessage() == null ? "The requested resource is unavailable." : exception.getMessage(),
        request);
  }

  @ExceptionHandler(DataIntegrityViolationException.class)
  ResponseEntity<ProblemDetail> conflict(
      DataIntegrityViolationException exception, HttpServletRequest request) {
    return response(
        HttpStatus.CONFLICT,
        "administration-integrity-conflict",
        "Administration integrity conflict",
        "The request conflicts with an existing or protected administration record.",
        request);
  }

  @ExceptionHandler(IdempotencyException.class)
  ResponseEntity<ProblemDetail> idempotency(
      IdempotencyException exception, HttpServletRequest request) {
    var reused = exception.reason() == IdempotencyException.Reason.KEY_REUSED;
    return response(
        HttpStatus.CONFLICT,
        reused ? "idempotency-key-reused" : "request-in-progress",
        reused ? "Idempotency key reused" : "Request in progress",
        exception.getMessage(),
        request);
  }

  private static ResponseEntity<ProblemDetail> response(
      HttpStatus status,
      String code,
      String title,
      String detail,
      HttpServletRequest request) {
    var problem = ProblemDetail.forStatusAndDetail(status, detail);
    problem.setType(URI.create(TYPE_ROOT + code));
    problem.setTitle(title);
    problem.setInstance(URI.create(request.getRequestURI()));
    problem.setProperty("code", code);
    problem.setProperty("correlationId", CorrelationIdFilter.from(request));
    return ResponseEntity.status(status)
        .cacheControl(CacheControl.noStore())
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(problem);
  }
}
