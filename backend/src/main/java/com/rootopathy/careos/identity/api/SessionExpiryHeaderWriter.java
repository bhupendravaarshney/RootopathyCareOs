package com.rootopathy.careos.identity.api;

import com.rootopathy.careos.identity.application.IdentitySecurityPolicy;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Component;

@Component
public final class SessionExpiryHeaderWriter {
    public static final String HEADER_NAME = "X-CareOS-Session-Expires-In";
    private static final long MAX_HEADER_SECONDS = Integer.MAX_VALUE;

    private final IdentitySecurityPolicy policy;
    private final Clock clock;

    public SessionExpiryHeaderWriter(IdentitySecurityPolicy policy, Clock clock) {
        this.policy = policy;
        this.clock = clock;
    }

    public boolean isAbsolutelyExpired(HttpSession session) {
        return !clock.instant().isBefore(absoluteExpiresAt(session));
    }

    public void write(HttpSession session, HttpServletResponse response) {
        var now = clock.instant();
        var expiresAt = absoluteExpiresAt(session);
        var maxInactiveInterval = session.getMaxInactiveInterval();
        if (maxInactiveInterval >= 0) {
            var idleExpiresAt = now.plusSeconds(maxInactiveInterval);
            if (idleExpiresAt.isBefore(expiresAt)) {
                expiresAt = idleExpiresAt;
            }
        }
        var remainingSeconds = Duration.between(now, expiresAt).toSeconds();
        response.setHeader(
                HEADER_NAME,
                Long.toString(Math.clamp(remainingSeconds, 0, MAX_HEADER_SECONDS)));
    }

    private Instant absoluteExpiresAt(HttpSession session) {
        var authenticatedAt = session.getAttribute(AuthenticationSessionState.AUTHENTICATED_AT);
        var start = authenticatedAt instanceof Long epochMillis
                ? Instant.ofEpochMilli(epochMillis)
                : Instant.ofEpochMilli(session.getCreationTime());
        return start.plus(policy.sessionAbsoluteTimeout());
    }
}
