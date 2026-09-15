package com.rootopathy.careos.identity.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.rootopathy.careos.identity.application.IdentitySecurityPolicy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;

class SessionExpiryHeaderWriterTest {
    private static final Instant NOW = Instant.parse("2026-09-15T12:00:00Z");

    @Test
    void publishesTheEarlierIdleOrAbsoluteDeadline() {
        var writer = writer(Duration.ofHours(8));
        var response = new MockHttpServletResponse();
        var session = session(NOW.minus(Duration.ofHours(7).plusMinutes(45)), 1_800);

        writer.write(session, response);

        assertThat(response.getHeader(SessionExpiryHeaderWriter.HEADER_NAME)).isEqualTo("900");
        assertThat(writer.isAbsolutelyExpired(session)).isFalse();
    }

    @Test
    void publishesTheIdleWindowWhenItEndsFirst() {
        var writer = writer(Duration.ofHours(8));
        var response = new MockHttpServletResponse();
        var session = session(NOW, 1_800);

        writer.write(session, response);

        assertThat(response.getHeader(SessionExpiryHeaderWriter.HEADER_NAME)).isEqualTo("1800");
    }

    @Test
    void reportsAnElapsedAbsoluteSessionWithoutARefreshWindow() {
        var writer = writer(Duration.ofHours(8));
        var response = new MockHttpServletResponse();
        var session = session(NOW.minus(Duration.ofHours(8)), -1);

        writer.write(session, response);

        assertThat(response.getHeader(SessionExpiryHeaderWriter.HEADER_NAME)).isEqualTo("0");
        assertThat(writer.isAbsolutelyExpired(session)).isTrue();
    }

    private static SessionExpiryHeaderWriter writer(Duration absoluteTimeout) {
        var policy = mock(IdentitySecurityPolicy.class);
        when(policy.sessionAbsoluteTimeout()).thenReturn(absoluteTimeout);
        return new SessionExpiryHeaderWriter(policy, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static MockHttpSession session(Instant authenticatedAt, int maxInactiveInterval) {
        var session = new MockHttpSession();
        session.setAttribute(
                AuthenticationSessionState.AUTHENTICATED_AT, authenticatedAt.toEpochMilli());
        session.setMaxInactiveInterval(maxInactiveInterval);
        return session;
    }
}
