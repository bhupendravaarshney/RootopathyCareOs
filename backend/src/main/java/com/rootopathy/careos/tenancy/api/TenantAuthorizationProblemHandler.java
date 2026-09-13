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
        var hidden = exception.reason() == TenantAuthorizationException.Reason.MEMBERSHIP_NOT_FOUND;
        var status = hidden ? HttpStatus.NOT_FOUND : HttpStatus.FORBIDDEN;
        var code = hidden ? "resource-not-found" : "permission-denied";
        var title = hidden ? "Resource not found" : "Permission denied";
        var problem = ProblemDetail.forStatusAndDetail(status, exception.getMessage());
        problem.setType(URI.create("https://careos.example/problems/" + code));
        problem.setTitle(title);
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", code);
        problem.setProperty("correlationId", CorrelationIdFilter.from(request));
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }
}
