package com.rootopathy.careos.identity.api;

import com.rootopathy.careos.governance.application.IdempotencyException;
import com.rootopathy.careos.identity.application.InvitationException;
import com.rootopathy.careos.shared.api.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = InvitationController.class)
public final class InvitationApiProblemHandler {
    @ExceptionHandler(InvitationException.class)
    ResponseEntity<ProblemDetail> invitationProblem(
            InvitationException exception, HttpServletRequest request) {
        var mapping = mapping(exception.reason());
        return response(mapping, exception.getMessage(), request);
    }

    @ExceptionHandler(IdempotencyException.class)
    ResponseEntity<ProblemDetail> idempotencyProblem(
            IdempotencyException exception, HttpServletRequest request) {
        var code = exception.reason() == IdempotencyException.Reason.KEY_REUSED
                ? "idempotency-key-reused"
                : "request-in-progress";
        var title = exception.reason() == IdempotencyException.Reason.KEY_REUSED
                ? "Idempotency key reused"
                : "Request in progress";
        return response(new ProblemMapping(HttpStatus.CONFLICT, code, title), exception.getMessage(), request);
    }

    private static ProblemMapping mapping(InvitationException.Reason reason) {
        return switch (reason) {
            case UNAVAILABLE -> new ProblemMapping(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "invitations-unavailable",
                    "Invitations unavailable",
                    60);
            case INVALID_REQUEST -> new ProblemMapping(
                    HttpStatus.BAD_REQUEST,
                    "invitation-request-invalid",
                    "Invalid invitation request");
            case ROLE_NOT_ASSIGNABLE -> new ProblemMapping(
                    HttpStatus.FORBIDDEN,
                    "invitation-role-not-assignable",
                    "Invitation role not assignable");
            case ALREADY_PENDING -> new ProblemMapping(
                    HttpStatus.CONFLICT,
                    "invitation-already-pending",
                    "Invitation already pending");
            case INVITATION_NOT_FOUND -> new ProblemMapping(
                    HttpStatus.NOT_FOUND,
                    "invitation-not-found",
                    "Invitation not found");
            case INVALID_OR_EXPIRED_TOKEN -> new ProblemMapping(
                    HttpStatus.BAD_REQUEST,
                    "invalid-or-expired-invitation",
                    "Invalid or expired invitation");
            case AUTHENTICATION_REQUIRED -> new ProblemMapping(
                    HttpStatus.UNAUTHORIZED,
                    "invited-account-authentication-required",
                    "Invited account authentication required");
            case ACCOUNT_MISMATCH -> new ProblemMapping(
                    HttpStatus.FORBIDDEN,
                    "invited-account-mismatch",
                    "Invited account mismatch");
            case ACCOUNT_UNAVAILABLE -> new ProblemMapping(
                    HttpStatus.CONFLICT,
                    "invited-account-unavailable",
                    "Invited account unavailable");
            case PASSWORD_REQUIRED -> new ProblemMapping(
                    HttpStatus.BAD_REQUEST,
                    "invited-account-password-required",
                    "Invited account password required");
            case ALREADY_MEMBER -> new ProblemMapping(
                    HttpStatus.CONFLICT,
                    "invited-account-already-member",
                    "Invited account already a member");
            case THROTTLED -> new ProblemMapping(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "invitation-acceptance-throttled",
                    "Invitation acceptance temporarily unavailable",
                    900);
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
            HttpStatus status, String code, String title, Integer retryAfterSeconds) {
        private ProblemMapping(HttpStatus status, String code, String title) {
            this(status, code, title, null);
        }
    }
}
