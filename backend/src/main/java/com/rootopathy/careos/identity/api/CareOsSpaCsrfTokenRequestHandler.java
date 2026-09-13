package com.rootopathy.careos.identity.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.function.Supplier;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.CsrfTokenRequestHandler;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public final class CareOsSpaCsrfTokenRequestHandler implements CsrfTokenRequestHandler {
    public static final String RAW_TOKEN_ATTRIBUTE = "careos.rawCsrfToken";

    private final CsrfTokenRequestAttributeHandler plain = new CsrfTokenRequestAttributeHandler();
    private final CsrfTokenRequestHandler xor = new XorCsrfTokenRequestAttributeHandler();

    @Override
    public void handle(
            HttpServletRequest request, HttpServletResponse response, Supplier<CsrfToken> deferredCsrfToken) {
        request.setAttribute(RAW_TOKEN_ATTRIBUTE, deferredCsrfToken);
        xor.handle(request, response, deferredCsrfToken);
    }

    @Override
    public String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken csrfToken) {
        return StringUtils.hasText(request.getHeader(csrfToken.getHeaderName()))
                ? plain.resolveCsrfTokenValue(request, csrfToken)
                : xor.resolveCsrfTokenValue(request, csrfToken);
    }

    public static CsrfToken rawToken(HttpServletRequest request) {
        var attribute = request.getAttribute(RAW_TOKEN_ATTRIBUTE);
        if (attribute instanceof Supplier<?> supplier && supplier.get() instanceof CsrfToken token) {
            return token;
        }
        throw new IllegalStateException("CSRF token was not initialized");
    }
}
