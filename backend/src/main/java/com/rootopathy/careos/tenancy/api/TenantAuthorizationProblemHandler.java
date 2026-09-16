package com.rootopathy.careos.tenancy.api;

import com.rootopathy.careos.shared.api.CorrelationIdFilter;
import com.rootopathy.careos.tenancy.application.TenantAuthorizationException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public final class TenantAuthorizationProblemHandler {
    @ExceptionHandler(TenantAuthorizationException.class)
    ResponseEntity<ProblemDetail> authorizationProblem(
            TenantAuthorizationException exception, HttpServletRequest request) {
        var mapping = mapping(exception.reason());
        var problem = ProblemDetail.forStatusAndDetail(mapping.status(), exception.getMessage());
        problem.setType(URI.create("https://careos.example/problems/" + mapping.code()));
        problem.setTitle(mapping.title());
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", mapping.code());
        problem.setProperty("correlationId", CorrelationIdFilter.from(request));
        return ResponseEntity.status(mapping.status())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }

    private static ProblemMapping mapping(TenantAuthorizationException.Reason reason) {
        return switch (reason) {
            case MEMBERSHIP_NOT_FOUND -> new ProblemMapping(
                    HttpStatus.NOT_FOUND, "resource-not-found", "Resource not found");
            case PERMISSION_DENIED -> new ProblemMapping(
                    HttpStatus.FORBIDDEN, "permission-denied", "Permission denied");
            case REASON_REQUIRED -> new ProblemMapping(
                    HttpStatus.BAD_REQUEST, "reason-required", "Reason required");
            case RECENT_AUTHENTICATION_REQUIRED -> new ProblemMapping(
                    HttpStatus.PRECONDITION_REQUIRED,
                    "recent-authentication-required",
                    "Recent authentication required");
            case MFA_REQUIRED -> new ProblemMapping(
                    HttpStatus.PRECONDITION_REQUIRED,
                    "mfa-required",
                    "Multi-factor authentication required");
            case INDEPENDENT_APPROVAL_REQUIRED -> new ProblemMapping(
                    HttpStatus.CONFLICT,
                    "independent-approval-required",
                    "Independent approval required");
        };
    }

    private record ProblemMapping(HttpStatus status, String code, String title) {}
}
