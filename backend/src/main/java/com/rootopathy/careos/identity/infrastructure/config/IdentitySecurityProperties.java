package com.rootopathy.careos.identity.infrastructure.config;

import com.rootopathy.careos.identity.application.IdentitySecurityPolicy;
import java.net.URI;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("careos.security")
public record IdentitySecurityProperties(
        List<String> allowedOrigins,
        URI applicationBaseUrl,
        String mailFrom,
        String tokenPepper,
        String mfaEncryptionKey,
        Duration sessionAbsoluteTimeout,
        Duration recentAuthenticationWindow,
        Duration passwordResetTokenTtl,
        int loginAttemptLimit,
        Duration loginAttemptWindow,
        int mfaAttemptLimit,
        Duration mfaAttemptWindow,
        int recoveryCodeCount) implements IdentitySecurityPolicy {
    public IdentitySecurityProperties {
        allowedOrigins = List.copyOf(allowedOrigins);
        if (allowedOrigins.isEmpty() || allowedOrigins.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("At least one explicit security origin is required");
        }
        if (applicationBaseUrl == null || !applicationBaseUrl.isAbsolute()) {
            throw new IllegalArgumentException("An absolute application base URL is required");
        }
        requireText(mailFrom, "mailFrom");
        if (requireText(tokenPepper, "tokenPepper").length() < 32) {
            throw new IllegalArgumentException("tokenPepper must contain at least 32 characters");
        }
        byte[] key;
        try {
            key = Base64.getDecoder().decode(requireText(mfaEncryptionKey, "mfaEncryptionKey"));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("mfaEncryptionKey must be valid Base64", exception);
        }
        if (key.length != 32) {
            throw new IllegalArgumentException("mfaEncryptionKey must decode to exactly 32 bytes");
        }
        requirePositive(sessionAbsoluteTimeout, "sessionAbsoluteTimeout");
        requirePositive(recentAuthenticationWindow, "recentAuthenticationWindow");
        requirePositive(passwordResetTokenTtl, "passwordResetTokenTtl");
        requirePositive(loginAttemptWindow, "loginAttemptWindow");
        requirePositive(mfaAttemptWindow, "mfaAttemptWindow");
        if (loginAttemptLimit < 1 || mfaAttemptLimit < 1 || recoveryCodeCount < 1) {
            throw new IllegalArgumentException("Security attempt and recovery-code counts must be positive");
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
