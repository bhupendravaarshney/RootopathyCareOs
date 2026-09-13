package com.rootopathy.careos.identity.api;

import com.rootopathy.careos.identity.application.IdentitySecurityException;
import com.rootopathy.careos.shared.api.CorrelationIdFilter;
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
@RestControllerAdvice(assignableTypes = AuthenticationController.class)
public final class IdentityApiProblemHandler {
    @ExceptionHandler(IdentitySecurityException.class)
    ResponseEntity<ProblemDetail> identityProblem(
            IdentitySecurityException exception, HttpServletRequest request) {
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

    private static ProblemMapping mapping(IdentitySecurityException.Reason reason) {
        return switch (reason) {
            case INVALID_OR_EXPIRED_TOKEN -> new ProblemMapping(
                    HttpStatus.BAD_REQUEST,
                    "invalid-or-expired-token",
                    "Invalid or expired token");
            case WEAK_PASSWORD -> new ProblemMapping(
                    HttpStatus.BAD_REQUEST,
                    "password-policy-violation",
                    "Password policy violation");
            case INVALID_MFA_CODE -> new ProblemMapping(
                    HttpStatus.UNAUTHORIZED,
                    "authentication-verification-failed",
                    "Authentication verification failed");
            case MFA_ALREADY_ENABLED -> new ProblemMapping(
                    HttpStatus.CONFLICT,
                    "mfa-already-enabled",
                    "MFA is already enabled");
            case MFA_ENROLLMENT_NOT_FOUND -> new ProblemMapping(
                    HttpStatus.CONFLICT,
                    "mfa-enrollment-unavailable",
                    "MFA enrollment unavailable");
            case ACCOUNT_UNAVAILABLE -> new ProblemMapping(
                    HttpStatus.UNAUTHORIZED,
                    "account-unavailable",
                    "Account unavailable");
            case RECENT_AUTHENTICATION_REQUIRED -> new ProblemMapping(
                    HttpStatus.PRECONDITION_REQUIRED,
                    "recent-authentication-required",
                    "Recent authentication required");
        };
    }

    private record ProblemMapping(HttpStatus status, String code, String title) {}
}
