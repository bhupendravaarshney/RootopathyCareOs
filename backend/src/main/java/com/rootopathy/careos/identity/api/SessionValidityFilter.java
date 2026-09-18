package com.rootopathy.careos.identity.api;

import static com.rootopathy.careos.identity.infrastructure.security.CareOsAuthorities.MFA_ENROLLMENT_PENDING;

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
        var account = identityStore.findAccountById(principal.id());
        var expired = session == null || expiryHeaders.isAbsolutelyExpired(session);
        var revoked = account.isEmpty() || account.get().securityVersion() != principal.securityVersion();
        var assuranceChanged = account.isPresent()
                && account.get().mfaRequired()
                && !account.get().mfaEnabled()
                && authentication.getAuthorities().stream()
                        .noneMatch(authority -> MFA_ENROLLMENT_PENDING.equals(authority.getAuthority()));

        if (session == null || expired || revoked || assuranceChanged) {
            SecurityContextHolder.clearContext();
            if (session != null) {
                session.invalidate();
            }
            var code = expired
                    ? "session-expired"
                    : assuranceChanged ? "mfa-enrollment-required" : "session-revoked";
            var title = expired
                    ? "Session expired"
                    : assuranceChanged ? "MFA enrollment required" : "Session revoked";
            var detail = assuranceChanged
                    ? "Your access now requires MFA. Sign in again to enroll an authenticator."
                    : "The session is no longer valid. Sign in again.";
            problemWriter.write(
                    request,
                    response,
                    HttpStatus.UNAUTHORIZED,
                    code,
                    title,
                    detail);
            return;
        }
        expiryHeaders.write(session, response);
        filterChain.doFilter(request, response);
    }
}
