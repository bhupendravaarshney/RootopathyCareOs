package com.rootopathy.careos.governance.infrastructure;

import com.rootopathy.careos.governance.application.OutboxDeliveryOperations;
import com.rootopathy.careos.governance.domain.OutboxEnvelope;
import com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcOutboxDeliveryOperations implements OutboxDeliveryOperations {
    private static final Pattern WORKER_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    private static final Pattern ERROR_CODE = Pattern.compile("[a-z][a-z0-9]*([._:-][a-z0-9]+)*");

    private final JdbcTemplate jdbcTemplate;

    public JdbcOutboxDeliveryOperations(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<OutboxEnvelope> claimDue(
            AuthorizedTenantContext context, String workerId, int batchSize, Duration lease) {
        requireContext(context);
        requireWorkerId(workerId);
        if (batchSize < 1 || batchSize > 100) {
            throw new IllegalArgumentException("batchSize must be between 1 and 100");
        }
        var leaseMillis = requireDurationMillis(lease, 1_000, 900_000, "lease");
        setTransactionLocal("app.current_outbox_worker_id", workerId);
        return jdbcTemplate.query(
                        """
                        WITH candidates AS (
                            SELECT id
                            FROM outbox_events
                            WHERE published_at IS NULL
                              AND dead_lettered_at IS NULL
                              AND available_at <= clock_timestamp()
                              AND (claimed_until IS NULL OR claimed_until <= clock_timestamp())
                            ORDER BY available_at, occurred_at, id
                            FOR UPDATE SKIP LOCKED
                            LIMIT ?
                        )
                        UPDATE outbox_events events
                        SET claim_token = gen_random_uuid(),
                            claimed_by = ?,
                            claimed_until = clock_timestamp() + (? * interval '1 millisecond'),
                            attempt_count = attempt_count + 1
                        FROM candidates
                        WHERE events.id = candidates.id
                        RETURNING events.id, events.organization_id, events.event_name,
                                  events.schema_version, events.aggregate_type, events.aggregate_id,
                                  events.payload::text AS payload_json, events.correlation_id,
                                  events.occurred_at, events.attempt_count, events.claim_token,
                                  events.claimed_by, events.claimed_until
                        """,
                        JdbcOutboxDeliveryOperations::mapEnvelope,
                        batchSize,
                        workerId,
                        leaseMillis)
                .stream()
                .sorted(Comparator.comparing(OutboxEnvelope::occurredAt)
                        .thenComparing(OutboxEnvelope::eventId))
                .toList();
    }

    @Override
    public boolean markPublished(
            AuthorizedTenantContext context, UUID eventId, UUID claimToken, String workerId) {
        bindClaim(context, eventId, claimToken, workerId);
        return jdbcTemplate.update(
                        """
                        UPDATE outbox_events
                        SET published_at = clock_timestamp(), claim_token = NULL,
                            claimed_by = NULL, claimed_until = NULL
                        WHERE id = ? AND claim_token = ? AND claimed_by = ?
                          AND published_at IS NULL AND dead_lettered_at IS NULL
                        """,
                        eventId,
                        claimToken,
                        workerId)
                == 1;
    }

    @Override
    public boolean reschedule(
            AuthorizedTenantContext context,
            UUID eventId,
            UUID claimToken,
            String workerId,
            String errorCode,
            Duration retryDelay) {
        bindClaim(context, eventId, claimToken, workerId);
        requireErrorCode(errorCode);
        var retryMillis = requireDurationMillis(retryDelay, 0, 86_400_000, "retryDelay");
        return jdbcTemplate.update(
                        """
                        UPDATE outbox_events
                        SET available_at = clock_timestamp() + (? * interval '1 millisecond'),
                            last_error_code = ?, last_error_at = clock_timestamp(),
                            claim_token = NULL, claimed_by = NULL, claimed_until = NULL
                        WHERE id = ? AND claim_token = ? AND claimed_by = ?
                          AND published_at IS NULL AND dead_lettered_at IS NULL
                        """,
                        retryMillis,
                        errorCode,
                        eventId,
                        claimToken,
                        workerId)
                == 1;
    }

    @Override
    public boolean deadLetter(
            AuthorizedTenantContext context,
            UUID eventId,
            UUID claimToken,
            String workerId,
            String errorCode) {
        bindClaim(context, eventId, claimToken, workerId);
        requireErrorCode(errorCode);
        return jdbcTemplate.update(
                        """
                        UPDATE outbox_events
                        SET dead_lettered_at = clock_timestamp(), last_error_code = ?,
                            last_error_at = clock_timestamp(), claim_token = NULL,
                            claimed_by = NULL, claimed_until = NULL
                        WHERE id = ? AND claim_token = ? AND claimed_by = ?
                          AND published_at IS NULL AND dead_lettered_at IS NULL
                        """,
                        errorCode,
                        eventId,
                        claimToken,
                        workerId)
                == 1;
    }

    private void bindClaim(
            AuthorizedTenantContext context, UUID eventId, UUID claimToken, String workerId) {
        requireContext(context);
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(claimToken, "claimToken");
        requireWorkerId(workerId);
        setTransactionLocal("app.current_outbox_worker_id", workerId);
        setTransactionLocal("app.current_outbox_claim_token", claimToken.toString());
    }

    private void requireContext(AuthorizedTenantContext context) {
        Objects.requireNonNull(context, "context");
        TenantTransactionContextVerifier.requireAuthorizedWriteTransaction(jdbcTemplate, context);
    }

    private void setTransactionLocal(String setting, String value) {
        jdbcTemplate.queryForObject("select set_config(?, ?, true)", String.class, setting, value);
    }

    private static long requireDurationMillis(
            Duration duration, long minimum, long maximum, String name) {
        Objects.requireNonNull(duration, name);
        long millis;
        try {
            millis = duration.toMillis();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(name + " is outside the supported range", exception);
        }
        if (millis < minimum || millis > maximum) {
            throw new IllegalArgumentException(name + " is outside the supported range");
        }
        return millis;
    }

    private static void requireWorkerId(String workerId) {
        if (workerId == null || !WORKER_ID.matcher(workerId).matches()) {
            throw new IllegalArgumentException("workerId has an invalid format");
        }
    }

    private static void requireErrorCode(String errorCode) {
        if (errorCode == null || errorCode.length() > 120 || !ERROR_CODE.matcher(errorCode).matches()) {
            throw new IllegalArgumentException("errorCode has an invalid format");
        }
    }

    private static OutboxEnvelope mapEnvelope(ResultSet resultSet, int rowNumber)
            throws SQLException {
        return new OutboxEnvelope(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("organization_id", UUID.class),
                resultSet.getString("event_name"),
                resultSet.getInt("schema_version"),
                resultSet.getString("aggregate_type"),
                resultSet.getObject("aggregate_id", UUID.class),
                resultSet.getString("payload_json"),
                resultSet.getString("correlation_id"),
                resultSet.getTimestamp("occurred_at").toInstant(),
                resultSet.getInt("attempt_count"),
                resultSet.getObject("claim_token", UUID.class),
                resultSet.getString("claimed_by"),
                resultSet.getTimestamp("claimed_until").toInstant());
    }
}
