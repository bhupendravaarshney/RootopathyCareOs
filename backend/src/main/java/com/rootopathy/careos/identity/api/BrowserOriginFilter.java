package com.rootopathy.careos.identity.api;

import com.rootopathy.careos.identity.infrastructure.config.IdentitySecurityProperties;
import com.rootopathy.careos.shared.api.SecurityProblemWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public final class BrowserOriginFilter extends OncePerRequestFilter {
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

    private final Set<String> allowedOrigins;
    private final SecurityProblemWriter problemWriter;

    public BrowserOriginFilter(IdentitySecurityProperties properties, SecurityProblemWriter problemWriter) {
        this.allowedOrigins = properties.allowedOrigins().stream()
                .map(BrowserOriginFilter::originOf)
                .collect(Collectors.toUnmodifiableSet());
        this.problemWriter = problemWriter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        var path = request.getRequestURI();
        return SAFE_METHODS.contains(request.getMethod().toUpperCase(Locale.ROOT))
                || !(path.equals("/api/v1") || path.startsWith("/api/v1/"));
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        var source = request.getHeader("Origin");
        if (source == null || source.isBlank()) {
            source = request.getHeader("Referer");
        }
        if (source == null || source.isBlank() || !allowedOrigins.contains(originOf(source))) {
            problemWriter.write(
                    request,
                    response,
                    HttpStatus.FORBIDDEN,
                    "invalid-request-origin",
                    "Request origin rejected",
                    "State-changing browser requests must originate from an approved CareOS origin.");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private static String originOf(String value) {
        try {
            var uri = new URI(value.strip());
            if (uri.getScheme() == null || uri.getHost() == null || uri.getUserInfo() != null) {
                return "invalid";
            }
            var scheme = uri.getScheme().toLowerCase(Locale.ROOT);
            var host = uri.getHost().toLowerCase(Locale.ROOT);
            var port = uri.getPort();
            if ((scheme.equals("http") && port == 80) || (scheme.equals("https") && port == 443)) {
                port = -1;
            }
            return new URI(scheme, null, host, port, null, null, null).toString();
        } catch (URISyntaxException | IllegalArgumentException exception) {
            return "invalid";
        }
    }
}
