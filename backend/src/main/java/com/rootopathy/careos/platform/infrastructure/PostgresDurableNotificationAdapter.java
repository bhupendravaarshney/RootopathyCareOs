package com.rootopathy.careos.platform.infrastructure;

import com.rootopathy.careos.platform.application.CapabilityProbe;
import com.rootopathy.careos.platform.application.DurableNotificationException;
import com.rootopathy.careos.platform.application.DurableNotificationPort;
import com.rootopathy.careos.platform.domain.CapabilityAvailability;
import com.rootopathy.careos.platform.domain.CapabilityStatus;
import com.rootopathy.careos.platform.domain.DurableNotification;
import com.rootopathy.careos.platform.domain.DurableNotificationClaim;
import com.rootopathy.careos.platform.domain.NotificationFailureDisposition;
import com.rootopathy.careos.platform.domain.NotificationQueueSnapshot;
import com.rootopathy.careos.platform.domain.PlatformCapability;
import com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext;
import com.rootopathy.careos.tenancy.infrastructure.AuthorizedTenantTransactionGuard;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * PostgreSQL-backed encrypted notification mechanics. This adapter persists and leases requests;
 * it deliberately does not resolve consent/destinations or invoke a delivery provider.
 */
public final class PostgresDurableNotificationAdapter
        implements DurableNotificationPort, CapabilityProbe {
    private static final String READY = "postgres-notification-store-ready";
    private static final String NOT_INITIALIZED = "notification-store-not-initialized";
    private static final String STORE_UNAVAILABLE = "notification-store-unavailable";
    private static final String STORE_REJECTED = "notification-store-rejected";
    private static final String DEDUPLICATION_CONFLICT = "notification-deduplication-conflict";
    private static final String ID_CONFLICT = "notification-id-conflict";
    private static final String SCHEDULE_TOO_DISTANT = "notification-schedule-too-distant";
    private static final String LEASE_NOT_OWNED = "notification-lease-not-owned";
    private static final String LEASE_EXPIRED = "notification-lease-expired";
    private static final String NOT_CLAIMED = "notification-not-claimed";
    private static final String ENCRYPTION_KEY_UNAVAILABLE =
            "notification-encryption-key-unavailable";
    private static final int NONCE_BYTES = 12;
    private static final int MAXIMUM_PAYLOAD_BYTES = 1_048_576;
    private static final Pattern FAILURE_REASON =
            Pattern.compile("[a-z][a-z0-9]*([.:-][a-z0-9]+)*");

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final SecureRandom secureRandom;
    private final Map<String, SecretKeySpec> encryptionKeys;
    private final String activeEncryptionKeyId;
    private final Set<String> allowedTemplateDefinitions;
    private final long leaseMillis;
    private final int maximumAttempts;
    private final long initialRetryMillis;
    private final long maximumRetryMillis;
    private final long terminalRetentionMillis;
    private final long maximumScheduleAheadMillis;
    private final int maximumClaimBatch;
    private final int cleanupBatch;
    private final NotificationMetrics metrics;

    private volatile boolean initialized;

    public PostgresDurableNotificationAdapter(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            PostgresNotificationProperties properties,
            MeterRegistry meterRegistry) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.secureRandom = new SecureRandom();
        Objects.requireNonNull(properties, "properties").validateForActivation();
        encryptionKeys = properties.validatedEncryptionKeys();
        activeEncryptionKeyId = properties.getActiveEncryptionKeyId();
        allowedTemplateDefinitions = properties.validatedAllowedTemplateDefinitions();
        leaseMillis = properties.getLeaseDuration().toMillis();
        maximumAttempts = properties.getMaximumAttempts();
        initialRetryMillis = properties.getInitialRetryDelay().toMillis();
        maximumRetryMillis = properties.getMaximumRetryDelay().toMillis();
        terminalRetentionMillis = properties.getTerminalRetention().toMillis();
        maximumScheduleAheadMillis = properties.getMaximumScheduleAhead().toMillis();
        maximumClaimBatch = properties.getMaximumClaimBatch();
        cleanupBatch = properties.getCleanupBatch();
        metrics = new NotificationMetrics(Objects.requireNonNull(meterRegistry, "meterRegistry"));
    }

    public synchronized void initialize() {
        try {
            var readiness = jdbcTemplate.queryForMap("""
                    SELECT roles.rolsuper,
                           roles.rolbypassrls,
                           tables.relrowsecurity,
                           tables.relforcerowsecurity,
                           pg_get_userbyid(tables.relowner) AS table_owner,
                           current_user AS runtime_role,
                           has_table_privilege(tables.oid, 'SELECT') AS can_select,
                           has_table_privilege(tables.oid, 'INSERT') AS can_insert,
                           has_table_privilege(tables.oid, 'UPDATE') AS can_update,
                           has_table_privilege(tables.oid, 'DELETE') AS can_delete,
                           EXISTS (
                               SELECT 1 FROM pg_policy policies
                               WHERE policies.polrelid = tables.oid
                                 AND policies.polname = 'durable_notifications_tenant_policy'
                           ) AS has_tenant_policy,
                           (
                               SELECT count(*) = 3
                               FROM pg_trigger triggers
                               WHERE triggers.tgrelid = tables.oid
                                 AND triggers.tgenabled <> 'D'
                                 AND triggers.tgname IN (
                                     'durable_notifications_validate_insert',
                                     'durable_notifications_validate_transition',
                                     'durable_notifications_validate_delete')
                           ) AS has_lifecycle_triggers,
                           clock_timestamp() AS database_time
                    FROM pg_class tables
                    JOIN pg_namespace schemas ON schemas.oid = tables.relnamespace
                    JOIN pg_roles roles ON roles.rolname = current_user
                    WHERE schemas.nspname = 'public'
                      AND tables.relname = 'durable_notifications'
                      AND tables.relkind = 'r'
                    """);
            if (Boolean.TRUE.equals(readiness.get("rolsuper"))
                    || Boolean.TRUE.equals(readiness.get("rolbypassrls"))
                    || !Boolean.TRUE.equals(readiness.get("relrowsecurity"))
                    || !Boolean.TRUE.equals(readiness.get("relforcerowsecurity"))
                    || Objects.equals(readiness.get("table_owner"), readiness.get("runtime_role"))
                    || !Boolean.TRUE.equals(readiness.get("can_select"))
                    || !Boolean.TRUE.equals(readiness.get("can_insert"))
                    || !Boolean.TRUE.equals(readiness.get("can_update"))
                    || !Boolean.TRUE.equals(readiness.get("can_delete"))
                    || !Boolean.TRUE.equals(readiness.get("has_tenant_policy"))
                    || !Boolean.TRUE.equals(readiness.get("has_lifecycle_triggers"))
                    || !(readiness.get("database_time") instanceof Timestamp)) {
                throw new IllegalStateException(
                        "PostgreSQL notification store security readiness check failed");
            }
            var visibleWithoutTenant = jdbcTemplate.queryForObject(
                    "select count(*) from durable_notifications", Long.class);
            if (visibleWithoutTenant == null || visibleWithoutTenant != 0L) {
                throw new IllegalStateException(
                        "PostgreSQL notification store is visible without tenant context");
            }
            initialized = true;
        } catch (DataAccessException exception) {
            throw new IllegalStateException(
                    "PostgreSQL notification store readiness check failed", exception);
        }
    }

    @Override
    public void enqueue(AuthorizedTenantContext context, DurableNotification notification) {
        requireContext(context);
        Objects.requireNonNull(notification, "notification");
        requireAllowed(notification.templateKey(), notification.templateVersion());
        cleanup();

        var canonicalPayload = canonicalJson(notification.payloadJson());
        var requestedNotBefore = notification.notBefore().truncatedTo(ChronoUnit.MICROS);
        var databaseNow = databaseNow();
        if (requestedNotBefore.isAfter(databaseNow.plusMillis(maximumScheduleAheadMillis))) {
            metrics.rejected.increment();
            throw new DurableNotificationException(SCHEDULE_TOO_DISTANT);
        }
        var deduplicationHash = sha256(notification.deduplicationKey());
        var payloadDigest = sha256(canonicalPayload);
        var contentDigest = contentDigest(
                context.organizationId(),
                notification.notificationId(),
                notification.recipientUserId(),
                notification.templateKey(),
                notification.templateVersion(),
                payloadDigest,
                deduplicationHash,
                requestedNotBefore);
        var encrypted = encrypt(
                context.organizationId(),
                notification.notificationId(),
                notification.recipientUserId(),
                notification.templateKey(),
                notification.templateVersion(),
                canonicalPayload);
        try {
            var inserted = jdbcTemplate.update(
                    """
                    INSERT INTO durable_notifications
                        (organization_id, notification_id, recipient_user_id,
                         requested_by_user_id, template_key, template_version,
                         encrypted_payload, payload_nonce, encryption_key_id,
                         payload_digest, deduplication_hash, content_digest,
                         requested_not_before, available_at, maximum_attempts,
                         initial_retry_delay_ms, maximum_retry_delay_ms,
                         terminal_retention_ms, purpose, correlation_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT DO NOTHING
                    """,
                    context.organizationId(),
                    notification.notificationId(),
                    notification.recipientUserId(),
                    context.actorId(),
                    notification.templateKey(),
                    notification.templateVersion(),
                    encrypted.ciphertext(),
                    encrypted.nonce(),
                    activeEncryptionKeyId,
                    payloadDigest,
                    deduplicationHash,
                    contentDigest,
                    Timestamp.from(requestedNotBefore),
                    Timestamp.from(requestedNotBefore),
                    maximumAttempts,
                    initialRetryMillis,
                    maximumRetryMillis,
                    terminalRetentionMillis,
                    context.purpose(),
                    context.correlationId());
            if (inserted == 1) {
                metrics.enqueued.increment();
                return;
            }
            classifyDuplicate(
                    context.organizationId(),
                    notification.notificationId(),
                    deduplicationHash,
                    contentDigest);
        } catch (DurableNotificationException exception) {
            throw exception;
        } catch (DataAccessException exception) {
            metrics.rejected.increment();
            throw new DurableNotificationException(STORE_REJECTED, exception);
        }
    }

    @Override
    public List<DurableNotificationClaim> claim(
            AuthorizedTenantContext context, int maximumNotifications) {
        requireContext(context);
        if (maximumNotifications < 1 || maximumNotifications > maximumClaimBatch) {
            throw new IllegalArgumentException(
                    "maximumNotifications is outside the configured claim range");
        }
        try {
            recoverExhaustedLeases();
            cleanup();
            var candidates = jdbcTemplate.query(
                    """
                    SELECT organization_id, notification_id, recipient_user_id,
                           template_key, template_version, encrypted_payload,
                           payload_nonce, encryption_key_id, payload_digest,
                           deduplication_hash, content_digest, requested_not_before,
                           attempt_count, terminal_retention_ms
                    FROM durable_notifications
                    WHERE attempt_count < maximum_attempts
                      AND ((status = 'ready' AND available_at <= clock_timestamp())
                           OR (status = 'leased' AND claimed_until <= clock_timestamp()))
                    ORDER BY available_at, created_at, notification_id
                    FOR UPDATE SKIP LOCKED
                    LIMIT ?
                    """,
                    PostgresDurableNotificationAdapter::mapStoredNotification,
                    maximumNotifications);
            var claims = new ArrayList<DurableNotificationClaim>(candidates.size());
            for (var candidate : candidates) {
                var invalidReason = validateStoredMetadata(context, candidate);
                if (invalidReason != null) {
                    deadLetterInvalid(candidate, invalidReason);
                    continue;
                }
                String payload;
                try {
                    payload = decrypt(candidate);
                } catch (MissingEncryptionKeyException exception) {
                    throw new DurableNotificationException(
                            ENCRYPTION_KEY_UNAVAILABLE, exception);
                } catch (InvalidEncryptedPayloadException exception) {
                    deadLetterInvalid(candidate, "notification.payload-invalid");
                    continue;
                }
                var lease = lease(candidate);
                claims.add(new DurableNotificationClaim(
                        context.organizationId(),
                        candidate.notificationId(),
                        candidate.recipientUserId(),
                        candidate.templateKey(),
                        candidate.templateVersion(),
                        payload,
                        lease.attempt(),
                        lease.rawToken(),
                        lease.expiresAt()));
            }
            metrics.claimed.increment(claims.size());
            return List.copyOf(claims);
        } catch (DurableNotificationException exception) {
            throw exception;
        } catch (DataAccessException exception) {
            throw storeUnavailable(exception);
        }
    }

    @Override
    public void acknowledge(
            AuthorizedTenantContext context, DurableNotificationClaim claim) {
        requireClaimContext(context, claim);
        cleanup();
        var claimHash = sha256(claim.leaseToken());
        setTransactionLocal("app.current_notification_claim_hash", claimHash);
        try {
            var changed = jdbcTemplate.update(
                    """
                    UPDATE durable_notifications
                    SET status = 'completed', claimed_until = NULL,
                        completed_at = clock_timestamp(),
                        expires_at = clock_timestamp()
                            + (terminal_retention_ms * interval '1 millisecond'),
                        updated_at = clock_timestamp()
                    WHERE organization_id = ? AND notification_id = ?
                      AND status = 'leased' AND claim_token_hash = ?
                      AND attempt_count = ? AND claimed_until > clock_timestamp()
                    """,
                    context.organizationId(),
                    claim.notificationId(),
                    claimHash,
                    claim.attempt());
            if (changed == 1) {
                metrics.acknowledged.increment();
                return;
            }
            var state = findClaimState(context.organizationId(), claim.notificationId());
            if (state != null
                    && state.status().equals("completed")
                    && state.attempt() == claim.attempt()
                    && constantTimeEquals(state.claimTokenHash(), claimHash)) {
                metrics.duplicateAcknowledgements.increment();
                return;
            }
            throw claimFailure(state, claim, claimHash);
        } catch (DurableNotificationException exception) {
            throw exception;
        } catch (DataAccessException exception) {
            throw storeUnavailable(exception);
        }
    }

    @Override
    public NotificationFailureDisposition recordFailure(
            AuthorizedTenantContext context,
            DurableNotificationClaim claim,
            String reasonCode) {
        requireClaimContext(context, claim);
        requireFailureReason(reasonCode);
        cleanup();
        var claimHash = sha256(claim.leaseToken());
        setTransactionLocal("app.current_notification_claim_hash", claimHash);
        try {
            var state = lockClaimState(context.organizationId(), claim.notificationId());
            if (state != null
                    && state.attempt() == claim.attempt()
                    && constantTimeEquals(state.claimTokenHash(), claimHash)
                    && reasonCode.equals(state.lastErrorCode())) {
                if (state.status().equals("ready")) {
                    metrics.duplicateFailures.increment();
                    return NotificationFailureDisposition.RETRY_SCHEDULED;
                }
                if (state.status().equals("dead_lettered")) {
                    metrics.duplicateFailures.increment();
                    return NotificationFailureDisposition.DEAD_LETTERED;
                }
            }
            if (state == null
                    || !state.status().equals("leased")
                    || state.attempt() != claim.attempt()
                    || !constantTimeEquals(state.claimTokenHash(), claimHash)) {
                throw claimFailure(state, claim, claimHash);
            }
            if (!state.claimedUntil().isAfter(databaseNow())) {
                metrics.rejected.increment();
                throw new DurableNotificationException(LEASE_EXPIRED);
            }
            if (state.attempt() >= state.maximumAttempts()) {
                deadLetterOwned(
                        context.organizationId(),
                        claim.notificationId(),
                        claimHash,
                        claim.attempt(),
                        reasonCode,
                        state.terminalRetentionMillis());
                metrics.deadLettered.increment();
                return NotificationFailureDisposition.DEAD_LETTERED;
            }
            var retryMillis = retryDelayMillis(state);
            var changed = jdbcTemplate.update(
                    """
                    UPDATE durable_notifications
                    SET status = 'ready', available_at = clock_timestamp()
                            + (? * interval '1 millisecond'),
                        claimed_until = NULL, last_error_code = ?,
                        last_error_at = clock_timestamp(), updated_at = clock_timestamp()
                    WHERE organization_id = ? AND notification_id = ?
                      AND status = 'leased' AND claim_token_hash = ?
                      AND attempt_count = ? AND claimed_until > clock_timestamp()
                    """,
                    retryMillis,
                    reasonCode,
                    context.organizationId(),
                    claim.notificationId(),
                    claimHash,
                    claim.attempt());
            if (changed != 1) {
                throw new DurableNotificationException(LEASE_NOT_OWNED);
            }
            metrics.retried.increment();
            return NotificationFailureDisposition.RETRY_SCHEDULED;
        } catch (DurableNotificationException exception) {
            throw exception;
        } catch (DataAccessException exception) {
            throw storeUnavailable(exception);
        }
    }

    @Override
    public NotificationQueueSnapshot snapshot(AuthorizedTenantContext context) {
        requireContext(context);
        try {
            recoverExhaustedLeases();
            cleanup();
            return jdbcTemplate.queryForObject(
                    """
                    SELECT count(*) FILTER (WHERE status = 'ready') AS ready_count,
                           count(*) FILTER (WHERE status = 'leased') AS leased_count,
                           count(*) FILTER (WHERE status = 'dead_lettered') AS dead_count,
                           count(*) FILTER (WHERE status = 'completed') AS completed_count
                    FROM durable_notifications
                    """,
                    (resultSet, rowNumber) -> new NotificationQueueSnapshot(
                            resultSet.getLong("ready_count"),
                            resultSet.getLong("leased_count"),
                            resultSet.getLong("dead_count"),
                            resultSet.getLong("completed_count")));
        } catch (DataAccessException exception) {
            throw storeUnavailable(exception);
        }
    }

    @Override
    public CapabilityStatus status() {
        return initialized
                ? new CapabilityStatus(
                        PlatformCapability.DURABLE_NOTIFICATION_DELIVERY,
                        CapabilityAvailability.AVAILABLE,
                        READY)
                : new CapabilityStatus(
                        PlatformCapability.DURABLE_NOTIFICATION_DELIVERY,
                        CapabilityAvailability.UNAVAILABLE,
                        NOT_INITIALIZED);
    }

    private void classifyDuplicate(
            UUID organizationId,
            UUID notificationId,
            String deduplicationHash,
            String contentDigest) {
        var byId = jdbcTemplate.query(
                """
                SELECT notification_id, deduplication_hash, content_digest
                FROM durable_notifications
                WHERE organization_id = ? AND notification_id = ?
                """,
                (resultSet, rowNumber) -> new StoredIdentity(
                        resultSet.getObject("notification_id", UUID.class),
                        resultSet.getString("deduplication_hash"),
                        resultSet.getString("content_digest")),
                organizationId,
                notificationId);
        var byDeduplication = jdbcTemplate.query(
                """
                SELECT notification_id, deduplication_hash, content_digest
                FROM durable_notifications
                WHERE organization_id = ? AND deduplication_hash = ?
                """,
                (resultSet, rowNumber) -> new StoredIdentity(
                        resultSet.getObject("notification_id", UUID.class),
                        resultSet.getString("deduplication_hash"),
                        resultSet.getString("content_digest")),
                organizationId,
                deduplicationHash);
        if (byId.size() == 1
                && byDeduplication.size() == 1
                && byId.getFirst().notificationId().equals(notificationId)
                && byDeduplication.getFirst().notificationId().equals(notificationId)
                && constantTimeEquals(byId.getFirst().deduplicationHash(), deduplicationHash)
                && constantTimeEquals(byId.getFirst().contentDigest(), contentDigest)) {
            metrics.deduplicated.increment();
            return;
        }
        metrics.conflicts.increment();
        if (!byId.isEmpty()) {
            throw new DurableNotificationException(ID_CONFLICT);
        }
        throw new DurableNotificationException(DEDUPLICATION_CONFLICT);
    }

    private String validateStoredMetadata(
            AuthorizedTenantContext context, StoredNotification notification) {
        if (!context.organizationId().equals(notification.organizationId())) {
            return "notification.tenant-invalid";
        }
        if (!allowedTemplateDefinitions.contains(
                notification.templateKey() + "@" + notification.templateVersion())) {
            return "notification.template-unavailable";
        }
        var expectedContentDigest = contentDigest(
                notification.organizationId(),
                notification.notificationId(),
                notification.recipientUserId(),
                notification.templateKey(),
                notification.templateVersion(),
                notification.payloadDigest(),
                notification.deduplicationHash(),
                notification.requestedNotBefore());
        if (!constantTimeEquals(notification.contentDigest(), expectedContentDigest)) {
            return "notification.metadata-invalid";
        }
        return null;
    }

    private Lease lease(StoredNotification notification) {
        var rawToken = newLeaseToken();
        var claimHash = sha256(rawToken);
        var states = jdbcTemplate.query(
                """
                UPDATE durable_notifications
                SET status = 'leased', attempt_count = attempt_count + 1,
                    claim_token_hash = ?,
                    claimed_until = clock_timestamp() + (? * interval '1 millisecond'),
                    updated_at = clock_timestamp()
                WHERE organization_id = ? AND notification_id = ?
                  AND attempt_count < maximum_attempts
                  AND ((status = 'ready' AND available_at <= clock_timestamp())
                       OR (status = 'leased' AND claimed_until <= clock_timestamp()))
                RETURNING attempt_count, claimed_until
                """,
                (resultSet, rowNumber) -> new Lease(
                        rawToken,
                        resultSet.getInt("attempt_count"),
                        resultSet.getTimestamp("claimed_until").toInstant()),
                claimHash,
                leaseMillis,
                notification.organizationId(),
                notification.notificationId());
        if (states.size() != 1) {
            throw new DurableNotificationException(NOT_CLAIMED);
        }
        return states.getFirst();
    }

    private void deadLetterInvalid(StoredNotification notification, String reasonCode) {
        var lease = lease(notification);
        var claimHash = sha256(lease.rawToken());
        setTransactionLocal("app.current_notification_claim_hash", claimHash);
        deadLetterOwned(
                notification.organizationId(),
                notification.notificationId(),
                claimHash,
                lease.attempt(),
                reasonCode,
                notification.terminalRetentionMillis());
        metrics.invalidPayloads.increment();
        metrics.deadLettered.increment();
    }

    private void deadLetterOwned(
            UUID organizationId,
            UUID notificationId,
            String claimHash,
            int attempt,
            String reasonCode,
            long retentionMillis) {
        var changed = jdbcTemplate.update(
                """
                UPDATE durable_notifications
                SET status = 'dead_lettered', claimed_until = NULL,
                    last_error_code = ?, last_error_at = clock_timestamp(),
                    dead_lettered_at = clock_timestamp(),
                    expires_at = clock_timestamp() + (? * interval '1 millisecond'),
                    updated_at = clock_timestamp()
                WHERE organization_id = ? AND notification_id = ?
                  AND status = 'leased' AND claim_token_hash = ?
                  AND attempt_count = ? AND claimed_until > clock_timestamp()
                """,
                reasonCode,
                retentionMillis,
                organizationId,
                notificationId,
                claimHash,
                attempt);
        if (changed != 1) {
            throw new DurableNotificationException(LEASE_NOT_OWNED);
        }
    }

    private void recoverExhaustedLeases() {
        try {
            var recovered = jdbcTemplate.update("""
                    UPDATE durable_notifications
                    SET status = 'dead_lettered', claimed_until = NULL,
                        last_error_code = 'notification.lease-exhausted',
                        last_error_at = clock_timestamp(),
                        dead_lettered_at = clock_timestamp(),
                        expires_at = clock_timestamp()
                            + (terminal_retention_ms * interval '1 millisecond'),
                        updated_at = clock_timestamp()
                    WHERE status = 'leased'
                      AND claimed_until <= clock_timestamp()
                      AND attempt_count >= maximum_attempts
                    """);
            metrics.deadLettered.increment(recovered);
        } catch (DataAccessException exception) {
            throw storeUnavailable(exception);
        }
    }

    private void cleanup() {
        try {
            setTransactionLocal("app.current_notification_cleanup", "true");
            var purged = jdbcTemplate.update(
                    """
                    WITH expired AS (
                        SELECT organization_id, notification_id
                        FROM durable_notifications
                        WHERE status IN ('completed', 'dead_lettered')
                          AND expires_at <= clock_timestamp()
                        ORDER BY expires_at, notification_id
                        FOR UPDATE SKIP LOCKED
                        LIMIT ?
                    )
                    DELETE FROM durable_notifications notifications
                    USING expired
                    WHERE notifications.organization_id = expired.organization_id
                      AND notifications.notification_id = expired.notification_id
                    """,
                    cleanupBatch);
            metrics.purged.increment(purged);
        } catch (DataAccessException exception) {
            throw storeUnavailable(exception);
        }
    }

    private ClaimState lockClaimState(UUID organizationId, UUID notificationId) {
        return jdbcTemplate.query(
                        """
                        SELECT status, attempt_count, maximum_attempts,
                               initial_retry_delay_ms, maximum_retry_delay_ms,
                               terminal_retention_ms, claim_token_hash,
                               claimed_until, last_error_code
                        FROM durable_notifications
                        WHERE organization_id = ? AND notification_id = ?
                        FOR UPDATE
                        """,
                        PostgresDurableNotificationAdapter::mapClaimState,
                        organizationId,
                        notificationId)
                .stream()
                .findFirst()
                .orElse(null);
    }

    private ClaimState findClaimState(UUID organizationId, UUID notificationId) {
        return jdbcTemplate.query(
                        """
                        SELECT status, attempt_count, maximum_attempts,
                               initial_retry_delay_ms, maximum_retry_delay_ms,
                               terminal_retention_ms, claim_token_hash,
                               claimed_until, last_error_code
                        FROM durable_notifications
                        WHERE organization_id = ? AND notification_id = ?
                        """,
                        PostgresDurableNotificationAdapter::mapClaimState,
                        organizationId,
                        notificationId)
                .stream()
                .findFirst()
                .orElse(null);
    }

    private DurableNotificationException claimFailure(
            ClaimState state, DurableNotificationClaim claim, String claimHash) {
        metrics.rejected.increment();
        if (state == null) {
            return new DurableNotificationException(NOT_CLAIMED);
        }
        if (state.status().equals("leased")
                && state.attempt() == claim.attempt()
                && constantTimeEquals(state.claimTokenHash(), claimHash)
                && (state.claimedUntil() == null || !state.claimedUntil().isAfter(databaseNow()))) {
            return new DurableNotificationException(LEASE_EXPIRED);
        }
        return new DurableNotificationException(LEASE_NOT_OWNED);
    }

    private long retryDelayMillis(ClaimState state) {
        var exponent = Math.min(state.attempt() - 1, 62);
        if (exponent >= 63
                || state.initialRetryMillis() > (state.maximumRetryMillis() >> exponent)) {
            return state.maximumRetryMillis();
        }
        return Math.min(
                state.maximumRetryMillis(), state.initialRetryMillis() * (1L << exponent));
    }

    private EncryptedPayload encrypt(
            UUID organizationId,
            UUID notificationId,
            UUID recipientUserId,
            String templateKey,
            int templateVersion,
            String canonicalPayload) {
        var nonce = new byte[NONCE_BYTES];
        secureRandom.nextBytes(nonce);
        try {
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.ENCRYPT_MODE,
                    encryptionKeys.get(activeEncryptionKeyId),
                    new GCMParameterSpec(128, nonce),
                    secureRandom);
            cipher.updateAAD(aad(
                    organizationId,
                    notificationId,
                    recipientUserId,
                    templateKey,
                    templateVersion));
            return new EncryptedPayload(
                    cipher.doFinal(canonicalPayload.getBytes(StandardCharsets.UTF_8)), nonce);
        } catch (GeneralSecurityException exception) {
            throw new DurableNotificationException(STORE_UNAVAILABLE, exception);
        }
    }

    private String decrypt(StoredNotification notification) {
        var key = encryptionKeys.get(notification.encryptionKeyId());
        if (key == null) {
            throw new MissingEncryptionKeyException();
        }
        byte[] plaintext;
        try {
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    key,
                    new GCMParameterSpec(128, notification.nonce()));
            cipher.updateAAD(aad(
                    notification.organizationId(),
                    notification.notificationId(),
                    notification.recipientUserId(),
                    notification.templateKey(),
                    notification.templateVersion()));
            plaintext = cipher.doFinal(notification.ciphertext());
        } catch (AEADBadTagException exception) {
            throw new InvalidEncryptedPayloadException(exception);
        } catch (GeneralSecurityException exception) {
            throw new InvalidEncryptedPayloadException(exception);
        }
        if (!constantTimeEquals(sha256(plaintext), notification.payloadDigest())) {
            throw new InvalidEncryptedPayloadException();
        }
        try {
            var decoder = StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            var payload = decoder.decode(ByteBuffer.wrap(plaintext)).toString();
            var canonical = canonicalJson(payload);
            if (!payload.equals(canonical)) {
                throw new InvalidEncryptedPayloadException();
            }
            return payload;
        } catch (InvalidEncryptedPayloadException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new InvalidEncryptedPayloadException(exception);
        }
    }

    private String canonicalJson(String payloadJson) {
        try {
            var json = objectMapper.readTree(payloadJson);
            if (json == null || !json.isObject()) {
                throw new IllegalArgumentException("payloadJson must be a JSON object");
            }
            var canonical = objectMapper.writeValueAsString(json);
            if (canonical.getBytes(StandardCharsets.UTF_8).length > MAXIMUM_PAYLOAD_BYTES) {
                throw new IllegalArgumentException("payloadJson exceeds the supported size");
            }
            return canonical;
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("payloadJson must be valid JSON", exception);
        }
    }

    private void requireAllowed(String templateKey, int templateVersion) {
        if (!allowedTemplateDefinitions.contains(templateKey + "@" + templateVersion)) {
            metrics.rejected.increment();
            throw new IllegalArgumentException(
                    "notification template and version are not configured");
        }
    }

    private void requireContext(AuthorizedTenantContext context) {
        requireInitialized();
        Objects.requireNonNull(context, "context");
        try {
            AuthorizedTenantTransactionGuard.requireWritable(jdbcTemplate, context);
        } catch (DataAccessException exception) {
            throw storeUnavailable(exception);
        }
    }

    private void requireClaimContext(
            AuthorizedTenantContext context, DurableNotificationClaim claim) {
        requireContext(context);
        Objects.requireNonNull(claim, "claim");
        if (!context.organizationId().equals(claim.organizationId())) {
            throw new IllegalArgumentException(
                    "notification claim tenant does not match authorized tenant");
        }
    }

    private void requireInitialized() {
        if (!initialized) {
            throw new DurableNotificationException(NOT_INITIALIZED);
        }
    }

    private void setTransactionLocal(String setting, String value) {
        try {
            jdbcTemplate.queryForObject(
                    "select set_config(?, ?, true)", String.class, setting, value);
        } catch (DataAccessException exception) {
            throw storeUnavailable(exception);
        }
    }

    private Instant databaseNow() {
        try {
            var timestamp =
                    jdbcTemplate.queryForObject("select clock_timestamp()", Timestamp.class);
            if (timestamp == null) {
                throw new DurableNotificationException(STORE_UNAVAILABLE);
            }
            return timestamp.toInstant();
        } catch (DataAccessException exception) {
            throw storeUnavailable(exception);
        }
    }

    private String newLeaseToken() {
        var bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private static void requireFailureReason(String reasonCode) {
        if (reasonCode == null
                || reasonCode.length() > 160
                || !FAILURE_REASON.matcher(reasonCode).matches()) {
            throw new IllegalArgumentException("reasonCode has an invalid format");
        }
    }

    private static byte[] aad(
            UUID organizationId,
            UUID notificationId,
            UUID recipientUserId,
            String templateKey,
            int templateVersion) {
        return (organizationId
                        + "|" + notificationId
                        + "|" + recipientUserId
                        + "|" + templateKey
                        + "|" + templateVersion)
                .getBytes(StandardCharsets.UTF_8);
    }

    private static String contentDigest(
            UUID organizationId,
            UUID notificationId,
            UUID recipientUserId,
            String templateKey,
            int templateVersion,
            String payloadDigest,
            String deduplicationHash,
            Instant requestedNotBefore) {
        return sha256(organizationId
                + "|" + notificationId
                + "|" + recipientUserId
                + "|" + templateKey
                + "|" + templateVersion
                + "|" + payloadDigest
                + "|" + deduplicationHash
                + "|" + requestedNotBefore.truncatedTo(ChronoUnit.MICROS));
    }

    private static String sha256(String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static boolean constantTimeEquals(String left, String right) {
        return left != null
                && right != null
                && MessageDigest.isEqual(
                        left.getBytes(StandardCharsets.US_ASCII),
                        right.getBytes(StandardCharsets.US_ASCII));
    }

    private DurableNotificationException storeUnavailable(DataAccessException exception) {
        metrics.dependencyFailures.increment();
        return new DurableNotificationException(STORE_UNAVAILABLE, exception);
    }

    private static StoredNotification mapStoredNotification(ResultSet resultSet, int rowNumber)
            throws SQLException {
        return new StoredNotification(
                resultSet.getObject("organization_id", UUID.class),
                resultSet.getObject("notification_id", UUID.class),
                resultSet.getObject("recipient_user_id", UUID.class),
                resultSet.getString("template_key"),
                resultSet.getInt("template_version"),
                resultSet.getBytes("encrypted_payload"),
                resultSet.getBytes("payload_nonce"),
                resultSet.getString("encryption_key_id"),
                resultSet.getString("payload_digest"),
                resultSet.getString("deduplication_hash"),
                resultSet.getString("content_digest"),
                resultSet.getTimestamp("requested_not_before").toInstant(),
                resultSet.getInt("attempt_count"),
                resultSet.getLong("terminal_retention_ms"));
    }

    private static ClaimState mapClaimState(ResultSet resultSet, int rowNumber)
            throws SQLException {
        var claimedUntil = resultSet.getTimestamp("claimed_until");
        return new ClaimState(
                resultSet.getString("status"),
                resultSet.getInt("attempt_count"),
                resultSet.getInt("maximum_attempts"),
                resultSet.getLong("initial_retry_delay_ms"),
                resultSet.getLong("maximum_retry_delay_ms"),
                resultSet.getLong("terminal_retention_ms"),
                resultSet.getString("claim_token_hash"),
                claimedUntil == null ? null : claimedUntil.toInstant(),
                resultSet.getString("last_error_code"));
    }

    private record EncryptedPayload(byte[] ciphertext, byte[] nonce) {}

    private record StoredIdentity(
            UUID notificationId, String deduplicationHash, String contentDigest) {}

    private record StoredNotification(
            UUID organizationId,
            UUID notificationId,
            UUID recipientUserId,
            String templateKey,
            int templateVersion,
            byte[] ciphertext,
            byte[] nonce,
            String encryptionKeyId,
            String payloadDigest,
            String deduplicationHash,
            String contentDigest,
            Instant requestedNotBefore,
            int attempt,
            long terminalRetentionMillis) {}

    private record Lease(String rawToken, int attempt, Instant expiresAt) {}

    private record ClaimState(
            String status,
            int attempt,
            int maximumAttempts,
            long initialRetryMillis,
            long maximumRetryMillis,
            long terminalRetentionMillis,
            String claimTokenHash,
            Instant claimedUntil,
            String lastErrorCode) {}

    private static final class MissingEncryptionKeyException extends RuntimeException {}

    private static final class InvalidEncryptedPayloadException extends RuntimeException {
        private InvalidEncryptedPayloadException() {}

        private InvalidEncryptedPayloadException(Throwable cause) {
            super(cause);
        }
    }

    private record NotificationMetrics(
            Counter enqueued,
            Counter deduplicated,
            Counter conflicts,
            Counter claimed,
            Counter acknowledged,
            Counter duplicateAcknowledgements,
            Counter retried,
            Counter duplicateFailures,
            Counter deadLettered,
            Counter invalidPayloads,
            Counter purged,
            Counter rejected,
            Counter dependencyFailures) {
        private NotificationMetrics(MeterRegistry registry) {
            this(
                    counter(registry, "enqueued"),
                    counter(registry, "deduplicated"),
                    counter(registry, "conflict"),
                    counter(registry, "claimed"),
                    counter(registry, "acknowledged"),
                    counter(registry, "duplicate-acknowledgement"),
                    counter(registry, "retried"),
                    counter(registry, "duplicate-failure"),
                    counter(registry, "dead-lettered"),
                    counter(registry, "invalid-payload"),
                    counter(registry, "purged"),
                    counter(registry, "rejected"),
                    counter(registry, "dependency-failure"));
        }

        private static Counter counter(MeterRegistry registry, String outcome) {
            return Counter.builder("careos.notification.transitions")
                    .tag("outcome", outcome)
                    .description("Durable notification store transitions")
                    .register(registry);
        }
    }
}
