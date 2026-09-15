package com.rootopathy.careos.identity.infrastructure.persistence;

import com.rootopathy.careos.identity.application.IdentityStore;
import com.rootopathy.careos.identity.domain.AuthenticationEventType;
import com.rootopathy.careos.identity.domain.CredentialAccount;
import com.rootopathy.careos.identity.domain.TotpMethod;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcIdentityStore implements IdentityStore {
    private static final RowMapper<CredentialAccount> ACCOUNT_MAPPER = JdbcIdentityStore::mapAccount;
    private static final RowMapper<TotpMethod> TOTP_MAPPER = JdbcIdentityStore::mapTotp;

    private final JdbcTemplate jdbcTemplate;

    public JdbcIdentityStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<CredentialAccount> findAccountByEmail(String normalizedEmail) {
        return jdbcTemplate.query(
                        """
                        SELECT users.id, users.email, users.display_name, users.status,
                               credentials.password_hash, users.security_version,
                               EXISTS (
                                   SELECT 1 FROM mfa_methods
                                   WHERE mfa_methods.user_id = users.id AND mfa_methods.status = 'enabled'
                               ) AS mfa_enabled
                        FROM users
                        JOIN password_credentials credentials ON credentials.user_id = users.id
                        WHERE lower(users.email) = ?
                        """,
                        ACCOUNT_MAPPER,
                        normalizedEmail)
                .stream()
                .findFirst();
    }

    @Override
    public Optional<CredentialAccount> findAccountById(UUID userId) {
        return jdbcTemplate.query(
                        """
                        SELECT users.id, users.email, users.display_name, users.status,
                               credentials.password_hash, users.security_version,
                               EXISTS (
                                   SELECT 1 FROM mfa_methods
                                   WHERE mfa_methods.user_id = users.id AND mfa_methods.status = 'enabled'
                               ) AS mfa_enabled
                        FROM users
                        JOIN password_credentials credentials ON credentials.user_id = users.id
                        WHERE users.id = ?
                        """,
                        ACCOUNT_MAPPER,
                        userId)
                .stream()
                .findFirst();
    }

    @Override
    public Optional<Long> findSecurityVersion(UUID userId) {
        return jdbcTemplate.query(
                        "select security_version from users where id = ? and status = 'active'",
                        (resultSet, rowNumber) -> resultSet.getLong(1),
                        userId)
                .stream()
                .findFirst();
    }

    @Override
    public void createPasswordResetToken(
            UUID userId,
            String tokenHash,
            Instant expiresAt,
            String correlationId,
            String requestedFromHash) {
        jdbcTemplate.update(
                """
                UPDATE password_reset_tokens
                SET revoked_at = now()
                WHERE user_id = ? AND consumed_at IS NULL AND revoked_at IS NULL
                """,
                userId);
        jdbcTemplate.update(
                """
                INSERT INTO password_reset_tokens
                    (user_id, token_hash, expires_at, correlation_id, requested_from_hash)
                VALUES (?, ?, ?, ?, ?)
                """,
                userId,
                tokenHash,
                Timestamp.from(expiresAt),
                correlationId,
                requestedFromHash);
    }

    @Override
    public Optional<UUID> consumePasswordResetToken(String tokenHash, Instant now) {
        return jdbcTemplate.query(
                        """
                        UPDATE password_reset_tokens
                        SET consumed_at = ?
                        WHERE token_hash = ?
                          AND consumed_at IS NULL
                          AND revoked_at IS NULL
                          AND expires_at > ?
                        RETURNING user_id
                        """,
                        (resultSet, rowNumber) -> resultSet.getObject("user_id", UUID.class),
                        Timestamp.from(now),
                        tokenHash,
                        Timestamp.from(now))
                .stream()
                .findFirst();
    }

    @Override
    public void replacePassword(UUID userId, String passwordHash, Instant changedAt) {
        var updated = jdbcTemplate.update(
                """
                UPDATE password_credentials
                SET password_hash = ?, changed_at = ?, lock_version = lock_version + 1
                WHERE user_id = ?
                """,
                passwordHash,
                Timestamp.from(changedAt),
                userId);
        if (updated != 1) {
            throw new IllegalStateException("Password credential does not exist");
        }
        jdbcTemplate.update(
                """
                UPDATE users
                SET security_version = security_version + 1, updated_at = ?
                WHERE id = ?
                """,
                Timestamp.from(changedAt),
                userId);
    }

    @Override
    public Optional<TotpMethod> findCurrentTotp(UUID userId) {
        return jdbcTemplate.query(
                        """
                        SELECT id, user_id, status, encrypted_secret
                        FROM mfa_methods
                        WHERE user_id = ? AND status IN ('pending', 'enabled')
                        ORDER BY CASE status WHEN 'enabled' THEN 0 ELSE 1 END
                        LIMIT 1
                        """,
                        TOTP_MAPPER,
                        userId)
                .stream()
                .findFirst();
    }

    @Override
    public UUID createPendingTotp(UUID userId, String encryptedSecret, String label, Instant createdAt) {
        jdbcTemplate.update(
                """
                UPDATE mfa_methods
                SET status = 'revoked', encrypted_secret = NULL, revoked_at = ?, lock_version = lock_version + 1
                WHERE user_id = ? AND status = 'pending'
                """,
                Timestamp.from(createdAt),
                userId);
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO mfa_methods (user_id, encrypted_secret, label, created_at)
                VALUES (?, ?, ?, ?)
                RETURNING id
                """,
                UUID.class,
                userId,
                encryptedSecret,
                label,
                Timestamp.from(createdAt));
    }

    @Override
    public boolean enableTotp(UUID userId, UUID methodId, Instant verifiedAt) {
        return jdbcTemplate.update(
                        """
                        UPDATE mfa_methods
                        SET status = 'enabled', verified_at = ?, lock_version = lock_version + 1
                        WHERE id = ? AND user_id = ? AND status = 'pending'
                        """,
                        Timestamp.from(verifiedAt),
                        methodId,
                        userId)
                == 1;
    }

    @Override
    public void replaceRecoveryCodes(
            UUID userId, UUID methodId, List<String> codeHashes, Instant createdAt) {
        jdbcTemplate.update(
                """
                UPDATE recovery_codes
                SET revoked_at = ?
                WHERE user_id = ? AND used_at IS NULL AND revoked_at IS NULL
                """,
                Timestamp.from(createdAt),
                userId);
        for (var codeHash : codeHashes) {
            jdbcTemplate.update(
                    """
                    INSERT INTO recovery_codes (user_id, mfa_method_id, code_hash, created_at)
                    VALUES (?, ?, ?, ?)
                    """,
                    userId,
                    methodId,
                    codeHash,
                    Timestamp.from(createdAt));
        }
    }

    @Override
    public boolean consumeRecoveryCode(UUID userId, UUID methodId, String codeHash, Instant usedAt) {
        return jdbcTemplate.update(
                        """
                        UPDATE recovery_codes
                        SET used_at = ?
                        WHERE user_id = ? AND mfa_method_id = ? AND code_hash = ?
                          AND used_at IS NULL AND revoked_at IS NULL
                        """,
                        Timestamp.from(usedAt),
                        userId,
                        methodId,
                        codeHash)
                == 1;
    }

    @Override
    public boolean administrativelyResetMfa(UUID userId, Instant resetAt) {
        var reset = Timestamp.from(resetAt);
        var changed = jdbcTemplate.update(
                """
                UPDATE mfa_methods
                SET status = 'revoked', encrypted_secret = NULL, revoked_at = ?,
                    lock_version = lock_version + 1
                WHERE user_id = ? AND status = 'enabled'
                """,
                reset,
                userId);
        if (changed != 1) {
            return false;
        }
        jdbcTemplate.update(
                """
                UPDATE recovery_codes
                SET revoked_at = ?
                WHERE user_id = ? AND used_at IS NULL AND revoked_at IS NULL
                """,
                reset,
                userId);
        jdbcTemplate.update(
                """
                UPDATE user_sessions
                SET revoked_at = ?, revocation_reason = 'mfa_admin_reset'
                WHERE user_id = ? AND revoked_at IS NULL
                """,
                reset,
                userId);
        var versionChanged = jdbcTemplate.update(
                """
                UPDATE users
                SET security_version = security_version + 1, updated_at = ?
                WHERE id = ? AND status = 'active'
                """,
                reset,
                userId);
        if (versionChanged != 1) {
            throw new IllegalStateException("Active reset target disappeared");
        }
        return true;
    }

    @Override
    public void recordSession(
            String sessionIdHash,
            UUID userId,
            Instant authenticatedAt,
            Instant mfaAuthenticatedAt,
            Instant recentAuthenticationAt,
            Instant absoluteExpiresAt,
            String correlationId) {
        jdbcTemplate.update(
                """
                INSERT INTO user_sessions
                    (session_id_hash, user_id, authenticated_at, mfa_authenticated_at,
                     recent_authentication_at, absolute_expires_at, correlation_id)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (session_id_hash) DO NOTHING
                """,
                sessionIdHash,
                userId,
                Timestamp.from(authenticatedAt),
                mfaAuthenticatedAt == null ? null : Timestamp.from(mfaAuthenticatedAt),
                Timestamp.from(recentAuthenticationAt),
                Timestamp.from(absoluteExpiresAt),
                correlationId);
    }

    @Override
    public void revokeSessionMetadata(UUID userId, Instant revokedAt, String reason) {
        jdbcTemplate.update(
                """
                UPDATE user_sessions
                SET revoked_at = ?, revocation_reason = ?
                WHERE user_id = ? AND revoked_at IS NULL
                """,
                Timestamp.from(revokedAt),
                reason,
                userId);
    }

    @Override
    public void revokeSessionMetadata(String sessionIdHash, UUID userId, Instant revokedAt, String reason) {
        jdbcTemplate.update(
                """
                UPDATE user_sessions
                SET revoked_at = ?, revocation_reason = ?
                WHERE session_id_hash = ? AND user_id = ? AND revoked_at IS NULL
                """,
                Timestamp.from(revokedAt),
                reason,
                sessionIdHash,
                userId);
    }

    @Override
    public boolean updateSessionAuthenticationEvidence(
            String sessionIdHash, UUID userId, Instant recentAuthenticationAt, Instant mfaAuthenticatedAt) {
        return jdbcTemplate.update(
                        """
                        UPDATE user_sessions
                        SET recent_authentication_at = ?,
                            mfa_authenticated_at = COALESCE(?, mfa_authenticated_at)
                        WHERE session_id_hash = ? AND user_id = ? AND revoked_at IS NULL
                        """,
                        Timestamp.from(recentAuthenticationAt),
                        mfaAuthenticatedAt == null ? null : Timestamp.from(mfaAuthenticatedAt),
                        sessionIdHash,
                        userId)
                == 1;
    }

    @Override
    public void recordAuthenticationEvent(
            UUID userId,
            AuthenticationEventType eventType,
            String subjectHash,
            String remoteAddressHash,
            String correlationId) {
        jdbcTemplate.update(
                """
                INSERT INTO authentication_events
                    (user_id, event_name, subject_hash, remote_address_hash, correlation_id)
                VALUES (?, ?, ?, ?, ?)
                """,
                userId,
                eventType.value(),
                subjectHash,
                remoteAddressHash,
                correlationId);
    }

    private static CredentialAccount mapAccount(ResultSet resultSet, int rowNumber) throws SQLException {
        return new CredentialAccount(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("email"),
                resultSet.getString("display_name"),
                resultSet.getString("status"),
                resultSet.getString("password_hash"),
                resultSet.getLong("security_version"),
                resultSet.getBoolean("mfa_enabled"));
    }

    private static TotpMethod mapTotp(ResultSet resultSet, int rowNumber) throws SQLException {
        return new TotpMethod(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("user_id", UUID.class),
                resultSet.getString("status"),
                resultSet.getString("encrypted_secret"));
    }
}
