package com.rootopathy.careos.administration.api;

import com.rootopathy.careos.administration.application.OrganizationAdministrationException;
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
@RestControllerAdvice(assignableTypes = OrganizationAdministrationController.class)
public final class OrganizationAdministrationProblemHandler {
    @ExceptionHandler(OrganizationAdministrationException.class)
    ResponseEntity<ProblemDetail> administrationProblem(
            OrganizationAdministrationException exception, HttpServletRequest request) {
        var mapping = switch (exception.reason()) {
            case INVALID_REQUEST -> new ProblemMapping(
                    HttpStatus.BAD_REQUEST, "organization-profile-invalid", "Invalid organization profile");
            case MEMBERSHIP_LIST_INVALID -> new ProblemMapping(
                    HttpStatus.BAD_REQUEST,
                    "membership-list-invalid",
                    "Invalid membership list request");
            case PROFILE_NOT_FOUND -> new ProblemMapping(
                    HttpStatus.NOT_FOUND, "resource-not-found", "Resource not found");
            case PRECONDITION_REQUIRED -> new ProblemMapping(
                    HttpStatus.PRECONDITION_REQUIRED,
                    "profile-precondition-required",
                    "Profile precondition required");
            case STALE_REVISION -> new ProblemMapping(
                    HttpStatus.PRECONDITION_FAILED,
                    "profile-stale-revision",
                    "Organization profile changed");
            case NO_CHANGES -> new ProblemMapping(
                    HttpStatus.CONFLICT, "profile-no-changes", "No profile changes");
        };
        var response = response(mapping, exception.getMessage(), request);
        if (exception.reason() == OrganizationAdministrationException.Reason.INVALID_REQUEST
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
