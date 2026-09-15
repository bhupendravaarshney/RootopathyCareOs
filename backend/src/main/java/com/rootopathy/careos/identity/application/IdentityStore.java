package com.rootopathy.careos.identity.application;

import com.rootopathy.careos.identity.domain.AuthenticationEventType;
import com.rootopathy.careos.identity.domain.CredentialAccount;
import com.rootopathy.careos.identity.domain.TotpMethod;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface IdentityStore {
    Optional<CredentialAccount> findAccountByEmail(String normalizedEmail);

    Optional<CredentialAccount> findAccountById(UUID userId);

    Optional<Long> findSecurityVersion(UUID userId);

    void createPasswordResetToken(
            UUID userId,
            String tokenHash,
            Instant expiresAt,
            String correlationId,
            String requestedFromHash);

    Optional<UUID> consumePasswordResetToken(String tokenHash, Instant now);

    void replacePassword(UUID userId, String passwordHash, Instant changedAt);

    Optional<TotpMethod> findCurrentTotp(UUID userId);

    UUID createPendingTotp(UUID userId, String encryptedSecret, String label, Instant createdAt);

    boolean enableTotp(UUID userId, UUID methodId, Instant verifiedAt);

    void replaceRecoveryCodes(UUID userId, UUID methodId, List<String> codeHashes, Instant createdAt);

    boolean consumeRecoveryCode(UUID userId, UUID methodId, String codeHash, Instant usedAt);

    boolean administrativelyResetMfa(UUID userId, Instant resetAt);

    void recordSession(
            String sessionIdHash,
            UUID userId,
            Instant authenticatedAt,
            Instant mfaAuthenticatedAt,
            Instant recentAuthenticationAt,
            Instant absoluteExpiresAt,
            String correlationId);

    void revokeSessionMetadata(UUID userId, Instant revokedAt, String reason);

    void revokeSessionMetadata(String sessionIdHash, UUID userId, Instant revokedAt, String reason);

    boolean updateSessionAuthenticationEvidence(
            String sessionIdHash, UUID userId, Instant recentAuthenticationAt, Instant mfaAuthenticatedAt);

    void recordAuthenticationEvent(
            UUID userId,
            AuthenticationEventType eventType,
            String subjectHash,
            String remoteAddressHash,
            String correlationId);
}
