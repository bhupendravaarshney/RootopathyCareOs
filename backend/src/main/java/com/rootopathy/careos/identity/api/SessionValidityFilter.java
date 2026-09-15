package com.rootopathy.careos.identity.api;

import com.rootopathy.careos.identity.application.IdentityStore;
import com.rootopathy.careos.identity.infrastructure.security.CareOsPrincipal;
import com.rootopathy.careos.shared.api.SecurityProblemWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public final class SessionValidityFilter extends OncePerRequestFilter {
    private final IdentityStore identityStore;
    private final SessionExpiryHeaderWriter expiryHeaders;
    private final SecurityProblemWriter problemWriter;

    public SessionValidityFilter(
            IdentityStore identityStore,
            SessionExpiryHeaderWriter expiryHeaders,
            SecurityProblemWriter problemWriter) {
        this.identityStore = identityStore;
        this.expiryHeaders = expiryHeaders;
        this.problemWriter = problemWriter;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof CareOsPrincipal principal)) {
            filterChain.doFilter(request, response);
            return;
        }

        var session = request.getSession(false);
        var currentVersion = identityStore.findSecurityVersion(principal.id());
        var expired = session == null || expiryHeaders.isAbsolutelyExpired(session);
        var revoked = currentVersion.isEmpty() || currentVersion.get() != principal.securityVersion();

        if (session == null || expired || revoked) {
            SecurityContextHolder.clearContext();
            if (session != null) {
                session.invalidate();
            }
            problemWriter.write(
                    request,
                    response,
                    HttpStatus.UNAUTHORIZED,
                    expired ? "session-expired" : "session-revoked",
                    expired ? "Session expired" : "Session revoked",
                    "The session is no longer valid. Sign in again.");
            return;
        }
        expiryHeaders.write(session, response);
        filterChain.doFilter(request, response);
    }
}
