package com.rootopathy.careos.identity.api;

import com.rootopathy.careos.shared.api.SecurityProblemWriter;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.stereotype.Component;

@Component
public final class SecurityAccessFailureHandler implements AuthenticationEntryPoint, AccessDeniedHandler {
    private final SecurityProblemWriter problemWriter;

    public SecurityAccessFailureHandler(SecurityProblemWriter problemWriter) {
        this.problemWriter = problemWriter;
    }

    @Override
    public void commence(
            HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
            throws IOException, ServletException {
        problemWriter.write(
                request,
                response,
                HttpStatus.UNAUTHORIZED,
                "authentication-required",
                "Authentication required",
                "A valid authenticated session is required.");
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException exception)
            throws IOException, ServletException {
        var csrfFailure = exception instanceof CsrfException;
        problemWriter.write(
                request,
                response,
                HttpStatus.FORBIDDEN,
                csrfFailure ? "csrf-token-invalid" : "access-denied",
                csrfFailure ? "CSRF validation failed" : "Access denied",
                csrfFailure
                        ? "A current CSRF token is required for this request."
                        : "The authenticated session is not permitted to perform this request.");
    }
}
