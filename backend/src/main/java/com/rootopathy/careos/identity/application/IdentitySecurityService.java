package com.rootopathy.careos.identity.application;

import static com.rootopathy.careos.identity.application.IdentitySecurityException.Reason.ACCOUNT_UNAVAILABLE;
import static com.rootopathy.careos.identity.application.IdentitySecurityException.Reason.INVALID_MFA_CODE;
import static com.rootopathy.careos.identity.application.IdentitySecurityException.Reason.INVALID_OR_EXPIRED_TOKEN;
import static com.rootopathy.careos.identity.application.IdentitySecurityException.Reason.MFA_ALREADY_ENABLED;
import static com.rootopathy.careos.identity.application.IdentitySecurityException.Reason.MFA_ENROLLMENT_NOT_FOUND;
import static com.rootopathy.careos.identity.application.IdentitySecurityException.Reason.WEAK_PASSWORD;

import com.rootopathy.careos.identity.domain.AuthenticationEventType;
import com.rootopathy.careos.identity.domain.CredentialAccount;
import com.rootopathy.careos.identity.domain.TotpMethod;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IdentitySecurityService {
    private static final Logger LOGGER = LoggerFactory.getLogger(IdentitySecurityService.class);

    public record MfaEnrollment(String secret, String provisioningUri) {}

    private final IdentityStore identityStore;
    private final PasswordHashingPort passwordHasher;
    private final SecurityTokenPort tokenCodec;
    private final MfaSecretProtectionPort secretProtector;
    private final TotpPort totp;
    private final SecurityNotificationPort notifications;
    private final SessionRevocationPort sessionRevocation;
    private final AuthenticationFailureRecorder failureRecorder;
    private final IdentitySecurityPolicy policy;
    private final Clock clock;

    public IdentitySecurityService(
            IdentityStore identityStore,
            PasswordHashingPort passwordHasher,
            SecurityTokenPort tokenCodec,
            MfaSecretProtectionPort secretProtector,
            TotpPort totp,
            SecurityNotificationPort notifications,
            SessionRevocationPort sessionRevocation,
            AuthenticationFailureRecorder failureRecorder,
            IdentitySecurityPolicy policy,
            Clock clock) {
        this.identityStore = identityStore;
        this.passwordHasher = passwordHasher;
        this.tokenCodec = tokenCodec;
        this.secretProtector = secretProtector;
        this.totp = totp;
        this.notifications = notifications;
        this.sessionRevocation = sessionRevocation;
        this.failureRecorder = failureRecorder;
        this.policy = policy;
        this.clock = clock;
    }

    @Transactional
    public void requestPasswordReset(String email, String correlationId, String remoteAddress) {
        var normalizedEmail = normalizeEmail(email);
        var now = clock.instant();
        var account = identityStore.findAccountByEmail(normalizedEmail).filter(CredentialAccount::isActive);
        var subjectHash = tokenCodec.digest(normalizedEmail);
        var remoteHash = tokenCodec.digest(normalizeRemoteAddress(remoteAddress));

        if (account.isPresent()) {
            var rawToken = tokenCodec.newOpaqueToken();
            var expiresAt = now.plus(policy.passwordResetTokenTtl());
            identityStore.createPasswordResetToken(
                    account.get().id(), tokenCodec.digest(rawToken), expiresAt, correlationId, remoteHash);
            try {
                notifications.sendPasswordReset(account.get().email(), rawToken, expiresAt);
            } catch (RuntimeException exception) {
                // Keep the public response indistinguishable from an unknown account or successful delivery.
                LOGGER.error(
                        "Password-reset notification delivery failed ({})",
                        exception.getClass().getSimpleName());
            }
        }
        identityStore.recordAuthenticationEvent(
                account.map(CredentialAccount::id).orElse(null),
                AuthenticationEventType.PASSWORD_RESET_REQUESTED,
                subjectHash,
                remoteHash,
                correlationId);
    }

    @Transactional
    public void resetPassword(String rawToken, String newPassword, String correlationId, String remoteAddress) {
        validatePassword(newPassword);
        var now = clock.instant();
        var userId = identityStore
                .consumePasswordResetToken(tokenCodec.digest(rawToken), now)
                .orElseThrow(() -> new IdentitySecurityException(
                        INVALID_OR_EXPIRED_TOKEN, "The password-reset token is invalid or expired."));
        var account = requireActiveAccount(userId);
        if (passwordHasher.matches(newPassword, account.passwordHash())) {
            throw new IdentitySecurityException(WEAK_PASSWORD, "The new password must differ from the current password.");
        }

        identityStore.replacePassword(userId, passwordHasher.hash(newPassword), now);
        identityStore.revokeSessionMetadata(userId, now, "password_reset");
        recordEvent(account, AuthenticationEventType.SESSIONS_REVOKED, correlationId, remoteAddress);
        identityStore.recordAuthenticationEvent(
                userId,
                AuthenticationEventType.PASSWORD_RESET_COMPLETED,
                tokenCodec.digest(account.email()),
                tokenCodec.digest(normalizeRemoteAddress(remoteAddress)),
                correlationId);
        sessionRevocation.revokeAllForPrincipal(account.email());
    }

    @Transactional
    public MfaEnrollment startMfaEnrollment(
            UUID userId, String label, String correlationId, String remoteAddress) {
        var account = requireActiveAccount(userId);
        identityStore.findCurrentTotp(userId).filter(TotpMethod::isEnabled).ifPresent(method -> {
            throw new IdentitySecurityException(MFA_ALREADY_ENABLED, "TOTP MFA is already enabled.");
        });
        var secret = totp.newSecret();
        identityStore.createPendingTotp(
                userId, secretProtector.protect(secret), normalizeLabel(label), clock.instant());
        recordEvent(account, AuthenticationEventType.MFA_ENROLLMENT_STARTED, correlationId, remoteAddress);
        return new MfaEnrollment(secret, totp.provisioningUri("ROOTOPATHY CareOS", account.email(), secret));
    }

    @Transactional
    public List<String> verifyMfaEnrollment(
            UUID userId, String code, String correlationId, String remoteAddress) {
        var account = requireActiveAccount(userId);
        var method = identityStore
                .findCurrentTotp(userId)
                .filter(TotpMethod::isPending)
                .orElseThrow(() -> new IdentitySecurityException(
                        MFA_ENROLLMENT_NOT_FOUND, "A pending MFA enrollment was not found."));
        if (!totp.verify(secretProtector.reveal(method.encryptedSecret()), code, clock.instant())) {
            failureRecorder.record(
                    account, AuthenticationEventType.MFA_CHALLENGE_FAILED, correlationId, remoteAddress);
            throw new IdentitySecurityException(INVALID_MFA_CODE, "The MFA code is invalid.");
        }
        var now = clock.instant();
        if (!identityStore.enableTotp(userId, method.id(), now)) {
            throw new IdentitySecurityException(
                    MFA_ENROLLMENT_NOT_FOUND, "A pending MFA enrollment was not found.");
        }
        var recoveryCodes = newRecoveryCodes();
        identityStore.replaceRecoveryCodes(
                userId, method.id(), recoveryCodes.stream().map(this::digestRecoveryCode).toList(), now);
        recordEvent(account, AuthenticationEventType.MFA_ENROLLMENT_COMPLETED, correlationId, remoteAddress);
        return recoveryCodes;
    }

    @Transactional
    public void completeMfaChallenge(UUID userId, String code, String correlationId, String remoteAddress) {
        var account = requireActiveAccount(userId);
        var method = requireEnabledTotp(userId);
        if (!verifyTotpOrConsumeRecovery(userId, method, code, correlationId, remoteAddress)) {
            failureRecorder.record(
                    account, AuthenticationEventType.MFA_CHALLENGE_FAILED, correlationId, remoteAddress);
            throw new IdentitySecurityException(INVALID_MFA_CODE, "The MFA code is invalid.");
        }
        recordEvent(account, AuthenticationEventType.MFA_CHALLENGE_SUCCEEDED, correlationId, remoteAddress);
    }

    @Transactional
    public List<String> regenerateRecoveryCodes(UUID userId, String correlationId, String remoteAddress) {
        var account = requireActiveAccount(userId);
        var method = requireEnabledTotp(userId);
        var codes = newRecoveryCodes();
        identityStore.replaceRecoveryCodes(
                userId,
                method.id(),
                codes.stream().map(this::digestRecoveryCode).toList(),
                clock.instant());
        recordEvent(account, AuthenticationEventType.RECOVERY_CODES_REGENERATED, correlationId, remoteAddress);
        return codes;
    }

    @Transactional
    public void verifyRecentAuthentication(
            UUID userId,
            String password,
            String secondFactor,
            String correlationId,
            String remoteAddress) {
        var account = requireActiveAccount(userId);
        var valid = passwordHasher.matches(password, account.passwordHash());
        if (valid && account.mfaEnabled()) {
            valid = verifyTotpOrConsumeRecovery(
                    userId, requireEnabledTotp(userId), secondFactor, correlationId, remoteAddress);
        }
        if (!valid) {
            failureRecorder.record(
                    account, AuthenticationEventType.RECENT_AUTHENTICATION_FAILED, correlationId, remoteAddress);
            throw new IdentitySecurityException(INVALID_MFA_CODE, "Authentication could not be verified.");
        }
        recordEvent(account, AuthenticationEventType.RECENT_AUTHENTICATION_SUCCEEDED, correlationId, remoteAddress);
    }

    @Transactional
    public void recordLoginSucceeded(
            CredentialAccount account,
            String sessionId,
            boolean mfaAuthenticated,
            String correlationId,
            String remoteAddress) {
        var now = clock.instant();
        identityStore.recordSession(
                tokenCodec.digest(sessionId),
                account.id(),
                now,
                mfaAuthenticated ? now : null,
                now,
                now.plus(policy.sessionAbsoluteTimeout()),
                correlationId);
        recordEvent(account, AuthenticationEventType.LOGIN_SUCCEEDED, correlationId, remoteAddress);
    }

    @Transactional
    public void recordLoginFailed(String email, boolean throttled, String correlationId, String remoteAddress) {
        var normalizedEmail = normalizeEmail(email);
        var account = identityStore.findAccountByEmail(normalizedEmail);
        identityStore.recordAuthenticationEvent(
                account.map(CredentialAccount::id).orElse(null),
                throttled ? AuthenticationEventType.LOGIN_THROTTLED : AuthenticationEventType.LOGIN_FAILED,
                tokenCodec.digest(normalizedEmail),
                tokenCodec.digest(normalizeRemoteAddress(remoteAddress)),
                correlationId);
    }

    @Transactional
    public void markSessionRecentlyAuthenticated(
            CredentialAccount account,
            String sessionId,
            boolean mfaAuthenticated) {
        var now = clock.instant();
        if (!identityStore.updateSessionAuthenticationEvidence(
                tokenCodec.digest(sessionId), account.id(), now, mfaAuthenticated ? now : null)) {
            throw new IllegalStateException("Active session metadata was not found");
        }
    }

    @Transactional
    public void recordLogout(
            UUID userId, String email, String sessionId, String correlationId, String remoteAddress) {
        if (sessionId != null) {
            identityStore.revokeSessionMetadata(
                    tokenCodec.digest(sessionId), userId, clock.instant(), "logout");
        }
        identityStore.recordAuthenticationEvent(
                userId,
                AuthenticationEventType.LOGOUT_COMPLETED,
                tokenCodec.digest(normalizeEmail(email)),
                tokenCodec.digest(normalizeRemoteAddress(remoteAddress)),
                correlationId);
    }

    public CredentialAccount requireActiveAccount(UUID userId) {
        return identityStore
                .findAccountById(userId)
                .filter(CredentialAccount::isActive)
                .orElseThrow(() -> new IdentitySecurityException(ACCOUNT_UNAVAILABLE, "The account is unavailable."));
    }

    private boolean verifyTotpOrConsumeRecovery(
            UUID userId,
            TotpMethod method,
            String code,
            String correlationId,
            String remoteAddress) {
        if (code != null && code.matches("[0-9]{6}")) {
            return totp.verify(secretProtector.reveal(method.encryptedSecret()), code, clock.instant());
        }
        var normalizedCode = normalizeRecoveryCode(code);
        if (normalizedCode == null) {
            return false;
        }
        var used = identityStore.consumeRecoveryCode(
                userId, method.id(), tokenCodec.digest(normalizedCode), clock.instant());
        if (used) {
            recordEvent(
                    requireActiveAccount(userId),
                    AuthenticationEventType.RECOVERY_CODE_USED,
                    correlationId,
                    remoteAddress);
        }
        return used;
    }

    private TotpMethod requireEnabledTotp(UUID userId) {
        return identityStore
                .findCurrentTotp(userId)
                .filter(TotpMethod::isEnabled)
                .orElseThrow(() -> new IdentitySecurityException(
                        MFA_ENROLLMENT_NOT_FOUND, "An enabled MFA method was not found."));
    }

    private List<String> newRecoveryCodes() {
        var codes = new ArrayList<String>(policy.recoveryCodeCount());
        while (codes.size() < policy.recoveryCodeCount()) {
            var candidate = tokenCodec.newRecoveryCode();
            if (!codes.contains(candidate)) {
                codes.add(candidate);
            }
        }
        return List.copyOf(codes);
    }

    private void recordEvent(
            CredentialAccount account,
            AuthenticationEventType eventType,
            String correlationId,
            String remoteAddress) {
        identityStore.recordAuthenticationEvent(
                account.id(),
                eventType,
                tokenCodec.digest(account.email()),
                tokenCodec.digest(normalizeRemoteAddress(remoteAddress)),
                correlationId);
    }

    private String digestRecoveryCode(String code) {
        return tokenCodec.digest(normalizeRecoveryCode(code));
    }

    private static void validatePassword(String password) {
        if (password == null
                || password.length() < 12
                || password.length() > 128
                || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new IdentitySecurityException(
                    WEAK_PASSWORD, "Password must contain 12 to 72 UTF-8 bytes and no more than 128 characters.");
        }
    }

    public static String normalizeEmail(String email) {
        return email == null ? "" : email.strip().toLowerCase(Locale.ROOT);
    }

    private static String normalizeRemoteAddress(String remoteAddress) {
        return remoteAddress == null || remoteAddress.isBlank() ? "unavailable" : remoteAddress;
    }

    private static String normalizeLabel(String label) {
        if (label == null || label.isBlank()) {
            return "Authenticator";
        }
        var normalized = label.strip();
        return normalized.length() <= 80 ? normalized : normalized.substring(0, 80);
    }

    private static String normalizeRecoveryCode(String code) {
        if (code == null) {
            return null;
        }
        var normalized = code.replace("-", "").strip().toUpperCase(Locale.ROOT);
        return normalized.matches("[23456789ABCDEFGHJKLMNPQRSTUVWXYZ]{12}") ? normalized : null;
    }
}
