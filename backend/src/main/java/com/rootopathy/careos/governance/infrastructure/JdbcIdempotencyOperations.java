package com.rootopathy.careos.governance.infrastructure;

import static com.rootopathy.careos.governance.application.IdempotencyException.Reason.KEY_REUSED;
import static com.rootopathy.careos.governance.application.IdempotencyException.Reason.REQUEST_IN_PROGRESS;

import com.rootopathy.careos.governance.application.IdempotencyException;
import com.rootopathy.careos.governance.application.IdempotencyOperations;
import com.rootopathy.careos.governance.domain.IdempotencyCommand;
import com.rootopathy.careos.governance.domain.IdempotencyOutcome;
import com.rootopathy.careos.governance.domain.IdempotentResponse;
import com.rootopathy.careos.shared.domain.UuidV7Generator;
import com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcIdempotencyOperations implements IdempotencyOperations {
    private final JdbcTemplate jdbcTemplate;

    public JdbcIdempotencyOperations(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public IdempotencyOutcome execute(
            AuthorizedTenantContext context,
            IdempotencyCommand command,
            Supplier<IdempotentResponse> firstExecution) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(firstExecution, "firstExecution");
        TenantTransactionContextVerifier.requireAuthorizedWriteTransaction(jdbcTemplate, context);

        var databaseNow = databaseNow();
        if (!command.expiresAt().isAfter(databaseNow)) {
            throw new IllegalArgumentException("expiresAt must be in the future");
        }
        deleteExpiredMatchingRecord(context, command, databaseNow);

        var recordId = UuidV7Generator.randomUuid();
        var inserted = jdbcTemplate.update(
                """
                INSERT INTO idempotency_records
                    (id, organization_id, actor_user_id, operation_key, idempotency_key,
                     request_hash, expires_at, purpose, correlation_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (organization_id, actor_user_id, operation_key, idempotency_key)
                DO NOTHING
                """,
                recordId,
                context.organizationId(),
                context.actorId(),
                command.operationKey(),
                command.idempotencyKey(),
                command.requestHash(),
                Timestamp.from(command.expiresAt()),
                context.purpose(),
                context.correlationId());
        if (inserted == 1) {
            var response = Objects.requireNonNull(firstExecution.get(), "firstExecution response");
            return new IdempotencyOutcome(complete(recordId, response), false);
        }

        var existing = findAndLock(context, command).orElseThrow(
                () -> new IllegalStateException("Conflicting idempotency record disappeared"));
        if (!existing.requestHash().equals(command.requestHash())) {
            throw new IdempotencyException(
                    KEY_REUSED, "The idempotency key was already used for a different request.");
        }
        var replayCheckTime = databaseNow();
        if (!existing.expiresAt().isAfter(replayCheckTime)) {
            jdbcTemplate.update("delete from idempotency_records where id = ?", existing.id());
            return execute(context, command, firstExecution);
        }
        if (!"completed".equals(existing.state())) {
            throw new IdempotencyException(
                    REQUEST_IN_PROGRESS, "The request with this idempotency key is still in progress.");
        }
        return new IdempotencyOutcome(existing.response(), true);
    }

    private IdempotentResponse complete(UUID recordId, IdempotentResponse response) {
        var stored = jdbcTemplate.query(
                """
                UPDATE idempotency_records
                SET state = 'completed', response_code = ?, response_media_type = ?,
                    response_body = CAST(? AS jsonb), updated_at = clock_timestamp(),
                    completed_at = clock_timestamp()
                WHERE id = ? AND state = 'in_progress'
                RETURNING response_code, response_media_type, response_body::text AS response_body_json
                """,
                JdbcIdempotencyOperations::mapResponse,
                response.statusCode(),
                response.mediaType(),
                response.bodyJson(),
                recordId);
        if (stored.size() != 1) {
            throw new IllegalStateException("Idempotency record could not be completed");
        }
        return stored.getFirst();
    }

    private void deleteExpiredMatchingRecord(
            AuthorizedTenantContext context, IdempotencyCommand command, Instant databaseNow) {
        jdbcTemplate.update(
                """
                DELETE FROM idempotency_records
                WHERE organization_id = ? AND actor_user_id = ?
                  AND operation_key = ? AND idempotency_key = ? AND expires_at <= ?
                """,
                context.organizationId(),
                context.actorId(),
                command.operationKey(),
                command.idempotencyKey(),
                Timestamp.from(databaseNow));
    }

    private Optional<StoredRecord> findAndLock(
            AuthorizedTenantContext context, IdempotencyCommand command) {
        return jdbcTemplate.query(
                        """
                        SELECT id, request_hash, state, expires_at, response_code,
                               response_media_type, response_body::text AS response_body_json
                        FROM idempotency_records
                        WHERE organization_id = ? AND actor_user_id = ?
                          AND operation_key = ? AND idempotency_key = ?
                        FOR UPDATE
                        """,
                        (resultSet, rowNumber) -> new StoredRecord(
                                resultSet.getObject("id", UUID.class),
                                resultSet.getString("request_hash").strip(),
                                resultSet.getString("state"),
                                resultSet.getTimestamp("expires_at").toInstant(),
                                "completed".equals(resultSet.getString("state"))
                                        ? mapResponse(resultSet, rowNumber)
                                        : null),
                        context.organizationId(),
                        context.actorId(),
                        command.operationKey(),
                        command.idempotencyKey())
                .stream()
                .findFirst();
    }

    private Instant databaseNow() {
        return jdbcTemplate.queryForObject(
                "select clock_timestamp()",
                (resultSet, rowNumber) -> resultSet.getTimestamp(1).toInstant());
    }

    private static IdempotentResponse mapResponse(ResultSet resultSet, int rowNumber)
            throws SQLException {
        return new IdempotentResponse(
                resultSet.getInt("response_code"),
                resultSet.getString("response_media_type"),
                resultSet.getString("response_body_json"));
    }

    private record StoredRecord(
            UUID id, String requestHash, String state, Instant expiresAt, IdempotentResponse response) {}
}
