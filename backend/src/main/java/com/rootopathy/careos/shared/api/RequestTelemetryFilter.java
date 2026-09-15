package com.rootopathy.careos.shared.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.util.pattern.PathPattern;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public final class RequestTelemetryFilter extends OncePerRequestFilter {
    private static final Logger LOGGER = LoggerFactory.getLogger(RequestTelemetryFilter.class);
    private static final Set<String> SAFE_METHODS =
            Set.of("DELETE", "GET", "HEAD", "OPTIONS", "PATCH", "POST", "PUT", "TRACE");
    private static final Pattern SAFE_ROUTE = Pattern.compile("[A-Za-z0-9_./{}*?\\-]{1,256}");

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        var startedAt = System.nanoTime();
        var completed = false;
        try {
            filterChain.doFilter(request, response);
            completed = true;
        } finally {
            var durationNanos = Math.max(0L, System.nanoTime() - startedAt);
            LOGGER.atInfo()
                    .addKeyValue("eventType", "http.request.completed")
                    .addKeyValue("httpMethod", safeMethod(request.getMethod()))
                    .addKeyValue("httpRoute", safeRoute(request))
                    .addKeyValue("httpStatus", response.getStatus())
                    .addKeyValue("durationMs", TimeUnit.NANOSECONDS.toMillis(durationNanos))
                    .addKeyValue("outcome", completed ? "completed" : "exception")
                    .log("HTTP request completed");
        }
    }

    private static String safeMethod(String method) {
        if (method == null) {
            return "OTHER";
        }
        var normalized = method.toUpperCase(Locale.ROOT);
        return SAFE_METHODS.contains(normalized) ? normalized : "OTHER";
    }

    private static String safeRoute(HttpServletRequest request) {
        var route = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        String candidate;
        if (route instanceof String value) {
            candidate = value;
        } else if (route instanceof PathPattern value) {
            candidate = value.getPatternString();
        } else {
            candidate = "";
        }
        return SAFE_ROUTE.matcher(candidate).matches() ? candidate : "unmatched";
    }
}
