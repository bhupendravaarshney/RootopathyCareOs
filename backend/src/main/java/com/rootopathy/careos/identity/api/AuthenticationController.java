package com.rootopathy.careos.identity.api;

import static com.rootopathy.careos.identity.infrastructure.security.CareOsAuthorities.AUTHENTICATED;
import static com.rootopathy.careos.identity.infrastructure.security.CareOsAuthorities.MFA_PENDING;

import com.rootopathy.careos.identity.application.IdentitySecurityException;
import com.rootopathy.careos.identity.application.IdentitySecurityService;
import com.rootopathy.careos.identity.application.SecurityRateLimitPort;
import com.rootopathy.careos.identity.infrastructure.config.IdentitySecurityProperties;
import com.rootopathy.careos.identity.infrastructure.security.CareOsPrincipal;
import com.rootopathy.careos.shared.api.ApiProblemException;
import com.rootopathy.careos.shared.api.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public final class AuthenticationController {
    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository securityContextRepository;
    private final CsrfTokenRepository csrfTokenRepository;
    private final IdentitySecurityService identitySecurity;
    private final SecurityRateLimitPort rateLimits;
    private final IdentitySecurityProperties properties;
    private final Clock clock;

    public AuthenticationController(
            AuthenticationManager authenticationManager,
            SecurityContextRepository securityContextRepository,
            CsrfTokenRepository csrfTokenRepository,
            IdentitySecurityService identitySecurity,
            SecurityRateLimitPort rateLimits,
            IdentitySecurityProperties properties,
            Clock clock) {
        this.authenticationManager = authenticationManager;
        this.securityContextRepository = securityContextRepository;
        this.csrfTokenRepository = csrfTokenRepository;
        this.identitySecurity = identitySecurity;
        this.rateLimits = rateLimits;
        this.properties = properties;
        this.clock = clock;
    }

    @GetMapping("/csrf")
    ResponseEntity<CsrfResponse> csrf(HttpServletRequest request) {
        var token = CareOsSpaCsrfTokenRequestHandler.rawToken(request);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new CsrfResponse(token.getHeaderName(), token.getParameterName(), token.getToken()));
    }

    @GetMapping("/session")
    ResponseEntity<SessionResponse> session(Authentication authentication, HttpSession session) {
        if (!(authentication != null && authentication.getPrincipal() instanceof CareOsPrincipal principal)) {
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .body(new SessionResponse("anonymous", null, false, false));
        }
        var state = hasAuthority(authentication, MFA_PENDING) ? "mfa_required" : "authenticated";
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(sessionResponse(state, principal, isRecent(session)));
    }

    @PostMapping("/login")
    ResponseEntity<SessionResponse> login(
            @Valid @RequestBody LoginRequest body, HttpServletRequest request, HttpServletResponse response) {
        var email = IdentitySecurityService.normalizeEmail(body.email());
        var remoteAddress = remoteAddress(request);
        if (!consumeLoginAttempt(email, remoteAddress)) {
            identitySecurity.recordLoginFailed(email, true, correlationId(request), remoteAddress);
            response.setHeader("Retry-After", Long.toString(properties.loginAttemptWindow().toSeconds()));
            throw new ApiProblemException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "login-throttled",
                    "Login temporarily unavailable",
                    "Too many login attempts were received. Try again later.");
        }

        final Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(email, body.password()));
        } catch (AuthenticationException exception) {
            identitySecurity.recordLoginFailed(email, false, correlationId(request), remoteAddress);
            throw new ApiProblemException(
                    HttpStatus.UNAUTHORIZED,
                    "invalid-credentials",
                    "Authentication failed",
                    "The supplied credentials could not be authenticated.");
        }

        if (!(authentication.getPrincipal() instanceof CareOsPrincipal principal)) {
            throw new IllegalStateException("Unsupported authenticated principal");
        }
        var session = request.getSession(true);
        request.changeSessionId();
        var now = clock.instant();
        session.setAttribute(AuthenticationSessionState.AUTHENTICATED_AT, now.toEpochMilli());
        rateLimits.clear("login-email", email);
        rateLimits.clear("login-remote", remoteAddress);

        if (principal.mfaRequired()) {
            saveAuthentication(authentication, request, response);
            return ResponseEntity.status(HttpStatus.ACCEPTED)
                    .cacheControl(CacheControl.noStore())
                    .body(sessionResponse("mfa_required", principal, false));
        }

        session.setAttribute(AuthenticationSessionState.RECENT_AUTHENTICATION_AT, now.toEpochMilli());
        var account = identitySecurity.requireActiveAccount(principal.id());
        identitySecurity.recordLoginSucceeded(
                account, session.getId(), false, correlationId(request), remoteAddress);
        saveAuthentication(authentication, request, response);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(sessionResponse("authenticated", principal, true));
    }

    @PostMapping("/logout")
    ResponseEntity<Void> logout(
            Authentication authentication, HttpServletRequest request, HttpServletResponse response) {
        var principal = requirePrincipal(authentication);
        var session = request.getSession(false);
        var sessionId = session == null ? null : session.getId();
        SecurityContextHolder.clearContext();
        if (session != null) {
            session.invalidate();
        }
        var emptyContext = SecurityContextHolder.getContextHolderStrategy().createEmptyContext();
        securityContextRepository.saveContext(emptyContext, request, response);
        csrfTokenRepository.saveToken(null, request, response);
        identitySecurity.recordLogout(
                principal.id(), principal.email(), sessionId, correlationId(request), remoteAddress(request));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/password-reset-requests")
    ResponseEntity<Void> requestPasswordReset(
            @Valid @RequestBody PasswordResetRequest body, HttpServletRequest request) {
        var email = IdentitySecurityService.normalizeEmail(body.email());
        var remoteAddress = remoteAddress(request);
        var emailAllowed = rateLimits.consume(
                "password-reset-email", email, properties.loginAttemptLimit(), properties.loginAttemptWindow());
        var remoteAllowed = rateLimits.consume(
                "password-reset-remote",
                remoteAddress,
                properties.loginAttemptLimit(),
                properties.loginAttemptWindow());
        if (emailAllowed && remoteAllowed) {
            identitySecurity.requestPasswordReset(email, correlationId(request), remoteAddress);
        }
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/password-resets")
    ResponseEntity<Void> resetPassword(
            @Valid @RequestBody PasswordResetCompletion body, HttpServletRequest request) {
        identitySecurity.resetPassword(
                body.token(), body.newPassword(), correlationId(request), remoteAddress(request));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/mfa/enrollments")
    MfaEnrollmentResponse startMfaEnrollment(
            @Valid @RequestBody MfaEnrollmentRequest body,
            Authentication authentication,
            HttpSession session,
            HttpServletRequest request) {
        requireRecent(session);
        var principal = requirePrincipal(authentication);
        var enrollment = identitySecurity.startMfaEnrollment(
                principal.id(), body.label(), correlationId(request), remoteAddress(request));
        return new MfaEnrollmentResponse(enrollment.secret(), enrollment.provisioningUri());
    }

    @PostMapping("/mfa/enrollments/verification")
    RecoveryCodesResponse verifyMfaEnrollment(
            @Valid @RequestBody MfaCodeRequest body,
            Authentication authentication,
            HttpSession session,
            HttpServletRequest request) {
        requireRecent(session);
        var principal = requirePrincipal(authentication);
        var codes = identitySecurity.verifyMfaEnrollment(
                principal.id(), body.code(), correlationId(request), remoteAddress(request));
        return new RecoveryCodesResponse(codes);
    }

    @PostMapping("/mfa/challenges")
    ResponseEntity<SessionResponse> completeMfaChallenge(
            @Valid @RequestBody MfaCodeRequest body,
            Authentication authentication,
            HttpServletRequest request,
            HttpServletResponse response) {
        var principal = requirePrincipal(authentication);
        if (!hasAuthority(authentication, MFA_PENDING)) {
            throw new ApiProblemException(
                    HttpStatus.CONFLICT,
                    "mfa-challenge-not-pending",
                    "MFA challenge not pending",
                    "This session does not have a pending MFA challenge.");
        }
        var remoteAddress = remoteAddress(request);
        var userAllowed = rateLimits.consume(
                "mfa-user", principal.id().toString(), properties.mfaAttemptLimit(), properties.mfaAttemptWindow());
        var remoteAllowed = rateLimits.consume(
                "mfa-remote", remoteAddress, properties.mfaAttemptLimit(), properties.mfaAttemptWindow());
        if (!userAllowed || !remoteAllowed) {
            response.setHeader("Retry-After", Long.toString(properties.mfaAttemptWindow().toSeconds()));
            throw new ApiProblemException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "mfa-throttled",
                    "MFA temporarily unavailable",
                    "Too many MFA attempts were received. Try again later.");
        }

        identitySecurity.completeMfaChallenge(
                principal.id(), body.code(), correlationId(request), remoteAddress);
        var session = requireSession(request);
        request.changeSessionId();
        var now = clock.instant();
        session.setAttribute(AuthenticationSessionState.AUTHENTICATED_AT, now.toEpochMilli());
        session.setAttribute(AuthenticationSessionState.RECENT_AUTHENTICATION_AT, now.toEpochMilli());
        session.setAttribute(AuthenticationSessionState.MFA_AUTHENTICATED_AT, now.toEpochMilli());

        var upgraded = UsernamePasswordAuthenticationToken.authenticated(
                principal, null, List.of(new SimpleGrantedAuthority(AUTHENTICATED)));
        upgraded.setDetails(authentication.getDetails());
        identitySecurity.recordLoginSucceeded(
                identitySecurity.requireActiveAccount(principal.id()),
                session.getId(),
                true,
                correlationId(request),
                remoteAddress);
        saveAuthentication(upgraded, request, response);
        rateLimits.clear("mfa-user", principal.id().toString());
        rateLimits.clear("mfa-remote", remoteAddress);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(sessionResponse("authenticated", principal, true));
    }

    @PostMapping("/recent-authentications")
    ResponseEntity<Void> verifyRecentAuthentication(
            @Valid @RequestBody RecentAuthenticationRequest body,
            Authentication authentication,
            HttpServletRequest request) {
        var principal = requirePrincipal(authentication);
        var session = requireSession(request);
        var remoteAddress = remoteAddress(request);
        var allowed = rateLimits.consume(
                "recent-auth-user",
                principal.id().toString(),
                properties.mfaAttemptLimit(),
                properties.mfaAttemptWindow());
        if (!allowed) {
            throw new ApiProblemException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "recent-authentication-throttled",
                    "Authentication temporarily unavailable",
                    "Too many authentication attempts were received. Try again later.");
        }
        identitySecurity.verifyRecentAuthentication(
                principal.id(),
                body.password(),
                body.secondFactor(),
                correlationId(request),
                remoteAddress);
        var account = identitySecurity.requireActiveAccount(principal.id());
        identitySecurity.markSessionRecentlyAuthenticated(
                account, session.getId(), account.mfaEnabled());
        session.setAttribute(AuthenticationSessionState.RECENT_AUTHENTICATION_AT, clock.instant().toEpochMilli());
        if (account.mfaEnabled()) {
            session.setAttribute(AuthenticationSessionState.MFA_AUTHENTICATED_AT, clock.instant().toEpochMilli());
        }
        rateLimits.clear("recent-auth-user", principal.id().toString());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/mfa/recovery-codes")
    RecoveryCodesResponse regenerateRecoveryCodes(
            Authentication authentication, HttpSession session, HttpServletRequest request) {
        requireRecent(session);
        var principal = requirePrincipal(authentication);
        return new RecoveryCodesResponse(identitySecurity.regenerateRecoveryCodes(
                principal.id(), correlationId(request), remoteAddress(request)));
    }

    private boolean consumeLoginAttempt(String email, String remoteAddress) {
        var emailAllowed = rateLimits.consume(
                "login-email", email, properties.loginAttemptLimit(), properties.loginAttemptWindow());
        var remoteAllowed = rateLimits.consume(
                "login-remote", remoteAddress, properties.loginAttemptLimit(), properties.loginAttemptWindow());
        return emailAllowed && remoteAllowed;
    }

    private void saveAuthentication(
            Authentication authentication, HttpServletRequest request, HttpServletResponse response) {
        var context = SecurityContextHolder.getContextHolderStrategy().createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.getContextHolderStrategy().setContext(context);
        securityContextRepository.saveContext(context, request, response);
        csrfTokenRepository.saveToken(null, request, response);
    }

    private void requireRecent(HttpSession session) {
        if (!isRecent(session)) {
            throw new IdentitySecurityException(
                    IdentitySecurityException.Reason.RECENT_AUTHENTICATION_REQUIRED,
                    "Recent authentication is required for this operation.");
        }
    }

    private boolean isRecent(HttpSession session) {
        var value = session.getAttribute(AuthenticationSessionState.RECENT_AUTHENTICATION_AT);
        return value instanceof Long epochMillis
                && clock.instant().isBefore(
                        Instant.ofEpochMilli(epochMillis).plus(properties.recentAuthenticationWindow()));
    }

    private static CareOsPrincipal requirePrincipal(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof CareOsPrincipal principal)) {
            throw new ApiProblemException(
                    HttpStatus.UNAUTHORIZED,
                    "authentication-required",
                    "Authentication required",
                    "A valid authenticated session is required.");
        }
        return principal;
    }

    private static HttpSession requireSession(HttpServletRequest request) {
        var session = request.getSession(false);
        if (session == null) {
            throw new ApiProblemException(
                    HttpStatus.UNAUTHORIZED,
                    "authentication-required",
                    "Authentication required",
                    "A valid authenticated session is required.");
        }
        return session;
    }

    private static boolean hasAuthority(Authentication authentication, String authority) {
        return authentication.getAuthorities().stream()
                .anyMatch(candidate -> candidate.getAuthority().equals(authority));
    }

    private SessionResponse sessionResponse(String state, CareOsPrincipal principal, boolean recent) {
        var mfaEnabled = identitySecurity.requireActiveAccount(principal.id()).mfaEnabled();
        return new SessionResponse(
                state,
                new UserResponse(principal.id().toString(), principal.email(), principal.displayName()),
                recent,
                mfaEnabled);
    }

    private static String correlationId(HttpServletRequest request) {
        return CorrelationIdFilter.from(request);
    }

    private static String remoteAddress(HttpServletRequest request) {
        return request.getRemoteAddr() == null ? "unavailable" : request.getRemoteAddr();
    }

    public record CsrfResponse(String headerName, String parameterName, String token) {}

    public record UserResponse(String id, String email, String displayName) {}

    public record SessionResponse(
            String state, UserResponse user, boolean recentAuthentication, boolean mfaEnabled) {}

    public record LoginRequest(
            @NotBlank @Email @Size(max = 320) String email,
            @NotBlank @Size(max = 128) String password) {}

    public record PasswordResetRequest(@NotBlank @Email @Size(max = 320) String email) {}

    public record PasswordResetCompletion(
            @NotBlank @Size(max = 512) String token,
            @NotBlank @Size(max = 128) String newPassword) {}

    public record MfaEnrollmentRequest(@Size(max = 80) String label) {}

    public record MfaEnrollmentResponse(String secret, String provisioningUri) {}

    public record MfaCodeRequest(@NotBlank @Size(max = 32) String code) {}

    public record RecoveryCodesResponse(List<String> recoveryCodes) {
        public RecoveryCodesResponse {
            recoveryCodes = List.copyOf(recoveryCodes);
        }
    }

    public record RecentAuthenticationRequest(
            @NotBlank @Size(max = 128) String password,
            @Size(max = 32) String secondFactor) {}
}
