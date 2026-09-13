package com.rootopathy.careos.identity.api;

import com.rootopathy.careos.identity.application.IdentityStore;
import com.rootopathy.careos.identity.infrastructure.config.IdentitySecurityProperties;
import com.rootopathy.careos.identity.infrastructure.security.CareOsPrincipal;
import com.rootopathy.careos.shared.api.SecurityProblemWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public final class SessionValidityFilter extends OncePerRequestFilter {
    private final IdentityStore identityStore;
    private final IdentitySecurityProperties properties;
    private final SecurityProblemWriter problemWriter;
    private final Clock clock;

    public SessionValidityFilter(
            IdentityStore identityStore,
            IdentitySecurityProperties properties,
            SecurityProblemWriter problemWriter,
            Clock clock) {
        this.identityStore = identityStore;
        this.properties = properties;
        this.problemWriter = problemWriter;
        this.clock = clock;
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
        var authenticatedAt = session == null ? null : session.getAttribute(AuthenticationSessionState.AUTHENTICATED_AT);
        var start = authenticatedAt instanceof Long epochMillis
                ? Instant.ofEpochMilli(epochMillis)
                : session == null ? Instant.EPOCH : Instant.ofEpochMilli(session.getCreationTime());
        var currentVersion = identityStore.findSecurityVersion(principal.id());
        var expired = !clock.instant().isBefore(start.plus(properties.sessionAbsoluteTimeout()));
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
        filterChain.doFilter(request, response);
    }
}
