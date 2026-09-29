package com.rootopathy.careos.shared.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.text.Normalizer;
import java.util.Collections;
import java.util.Enumeration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Captures the governed reason once, then hides the sensitive header from downstream telemetry. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public final class AuthorizationReasonFilter extends OncePerRequestFilter {
    public static final String HEADER_NAME = "X-Authorization-Reason";
    private static final String REQUEST_ATTRIBUTE = "careos.authorizationReason";
    private static final int MAXIMUM_RAW_UTF16_UNITS = 2_000;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        var rawReason = request.getHeader(HEADER_NAME);
        if (rawReason != null) {
            request.setAttribute(REQUEST_ATTRIBUTE, CapturedReason.capture(rawReason));
        }
        filterChain.doFilter(new SensitiveHeaderHidingRequest(request), response);
    }

    public static String from(HttpServletRequest request) {
        var captured = request.getAttribute(REQUEST_ATTRIBUTE);
        if (!(captured instanceof CapturedReason reason)) {
            return null;
        }
        if (!reason.valid()) {
            throw new ApiProblemException(
                    HttpStatus.BAD_REQUEST,
                    "request-validation",
                    "Request validation failed",
                    "Authorization reason must contain 10 to 500 safe characters.");
        }
        return reason.value();
    }

    private static final class CapturedReason {
        private final String value;
        private final boolean valid;

        private CapturedReason(String value, boolean valid) {
            this.value = value;
            this.valid = valid;
        }

        static CapturedReason capture(String rawReason) {
            if (rawReason.length() > MAXIMUM_RAW_UTF16_UNITS) {
                return new CapturedReason(null, false);
            }
            var normalized = Normalizer.normalize(rawReason.strip(), Normalizer.Form.NFC);
            var length = normalized.codePointCount(0, normalized.length());
            var valid = length >= 10
                    && length <= 500
                    && normalized.codePoints().noneMatch(Character::isISOControl);
            return new CapturedReason(valid ? normalized : null, valid);
        }

        String value() {
            return value;
        }

        boolean valid() {
            return valid;
        }

        @Override
        public String toString() {
            return "[REDACTED]";
        }
    }

    private static final class SensitiveHeaderHidingRequest extends HttpServletRequestWrapper {
        private SensitiveHeaderHidingRequest(HttpServletRequest request) {
            super(request);
        }

        @Override
        public String getHeader(String name) {
            return isAuthorizationReason(name) ? null : super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            return isAuthorizationReason(name)
                    ? Collections.emptyEnumeration()
                    : super.getHeaders(name);
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            var names = super.getHeaderNames();
            if (names == null) {
                return Collections.emptyEnumeration();
            }
            return Collections.enumeration(Collections.list(names).stream()
                    .filter(name -> !isAuthorizationReason(name))
                    .toList());
        }

        @Override
        public long getDateHeader(String name) {
            return isAuthorizationReason(name) ? -1L : super.getDateHeader(name);
        }

        @Override
        public int getIntHeader(String name) {
            return isAuthorizationReason(name) ? -1 : super.getIntHeader(name);
        }

        private static boolean isAuthorizationReason(String name) {
            return HEADER_NAME.equalsIgnoreCase(name);
        }
    }
}
