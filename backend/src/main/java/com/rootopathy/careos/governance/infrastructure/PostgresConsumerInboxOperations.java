package com.rootopathy.careos.governance.infrastructure;

import static com.rootopathy.careos.governance.application.ConsumerInboxException.Reason.CONTENT_CONFLICT;
import static com.rootopathy.careos.governance.application.ConsumerInboxException.Reason.NOT_INITIALIZED;
import static com.rootopathy.careos.governance.application.ConsumerInboxException.Reason.STORE_REJECTED;
import static com.rootopathy.careos.governance.application.ConsumerInboxException.Reason.TENANT_MISMATCH;

import com.rootopathy.careos.governance.application.ConsumerInboxException;
import com.rootopathy.careos.governance.application.ConsumerInboxOperations;
import com.rootopathy.careos.governance.domain.ConsumerInboxReceipt;
import com.rootopathy.careos.governance.domain.InboundOutboxEvent;
import com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** PostgreSQL-backed, append-only consumer receipts for transactional event deduplication. */
public final class PostgresConsumerInboxOperations implements ConsumerInboxOperations {
    private final JdbcTemplate jdbcTemplate;
    private volatile boolean initialized;

    public PostgresConsumerInboxOperations(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
    }

    public synchronized void initialize() {
        initialized = false;
        try {
            var role = jdbcTemplate.queryForMap("""
                    SELECT rolsuper, rolbypassrls
                    FROM pg_roles
                    WHERE rolname = current_user
                    """);
            if (Boolean.TRUE.equals(role.get("rolsuper"))
                    || Boolean.TRUE.equals(role.get("rolbypassrls"))) {
                throw new IllegalStateException("PostgreSQL consumer inbox runtime role is unsafe");
            }
            verifyRegistrySecurity();
            verifyInboxSecurity();
            verifyConstraintShape();
            var visibleWithoutTenant = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM consumer_inbox_records", Long.class);
            if (visibleWithoutTenant == null || visibleWithoutTenant != 0L) {
                throw new IllegalStateException(
                        "PostgreSQL consumer inbox missing-context isolation check failed");
            }
            initialized = true;
        } catch (RuntimeException exception) {
            throw new IllegalStateException(
                    "PostgreSQL consumer inbox readiness check failed", exception);
        }
    }

    @Override
    public ConsumerInboxReceipt execute(
            AuthorizedTenantContext context,
            InboundOutboxEvent event,
            Runnable firstProcessing) {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(firstProcessing, "firstProcessing");
        requireContext(context, event);

        String payloadDigest;
        List<Instant> insertedAt;
        try {
            payloadDigest = payloadDigest(event);
            insertedAt = jdbcTemplate.query(
                    """
                    INSERT INTO consumer_inbox_records
                        (organization_id, consumer_key, source_event_id, event_name,
                         schema_version, aggregate_type, aggregate_id, payload_sha256,
                         source_correlation_id, occurred_at, received_by_actor_id,
                         purpose, correlation_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (organization_id, consumer_key, source_event_id)
                    DO NOTHING
                    RETURNING received_at
                    """,
                    (rows, rowNumber) -> rows.getTimestamp("received_at").toInstant(),
                    event.organizationId(),
                    event.consumerKey(),
                    event.sourceEventId(),
                    event.eventName(),
                    event.schemaVersion(),
                    event.aggregateType(),
                    event.aggregateId(),
                    payloadDigest,
                    event.sourceCorrelationId(),
                    Timestamp.from(event.occurredAt()),
                    context.actorId(),
                    context.purpose(),
                    context.correlationId());
            if (insertedAt.size() > 1) {
                throw new ConsumerInboxException(
                        STORE_REJECTED, "The consumer inbox returned an invalid receipt count.");
            }
        } catch (ConsumerInboxException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new ConsumerInboxException(
                    STORE_REJECTED, "The consumer inbox rejected the event.", exception);
        }

        if (!insertedAt.isEmpty()) {
            firstProcessing.run();
            return receipt(event, insertedAt.getFirst(), false);
        }

        StoredReceipt stored;
        try {
            stored = find(event).stream()
                    .findFirst()
                    .orElseThrow(() -> new ConsumerInboxException(
                            STORE_REJECTED, "The conflicting consumer receipt disappeared."));
        } catch (ConsumerInboxException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new ConsumerInboxException(
                    STORE_REJECTED, "The consumer inbox could not read its receipt.", exception);
        }
        if (!stored.matches(event, payloadDigest)) {
            throw new ConsumerInboxException(
                    CONTENT_CONFLICT,
                    "The source event identifier was already used with different content.");
        }
        return receipt(event, stored.receivedAt(), true);
    }

    private String payloadDigest(InboundOutboxEvent event) {
        var canonicalPayloads = jdbcTemplate.queryForList(
                """
                WITH candidate AS (
                    SELECT CAST(? AS jsonb) AS payload
                )
                SELECT candidate.payload::text
                FROM candidate
                JOIN outbox_consumer_definitions consumers
                  ON consumers.consumer_key = ?
                 AND consumers.event_name = ?
                 AND consumers.schema_version = ?
                 AND consumers.status = 'active'
                JOIN outbox_event_definitions definitions
                  ON definitions.event_name = consumers.event_name
                 AND definitions.schema_version = consumers.schema_version
                 AND definitions.status = 'active'
                 AND definitions.aggregate_type = ?
                WHERE jsonb_typeof(candidate.payload) = 'object'
                  AND definitions.required_payload_keys <@ ARRAY(
                      SELECT key FROM jsonb_object_keys(candidate.payload) AS keys(key)
                  )
                  AND ARRAY(
                      SELECT key FROM jsonb_object_keys(candidate.payload) AS keys(key)
                  ) <@ definitions.allowed_payload_keys
                """,
                String.class,
                event.payloadJson(),
                event.consumerKey(),
                event.eventName(),
                event.schemaVersion(),
                event.aggregateType());
        if (canonicalPayloads.size() != 1) {
            throw new ConsumerInboxException(
                    STORE_REJECTED,
                    "The consumer or event definition is unavailable or the payload is invalid.");
        }
        return sha256(canonicalPayloads.getFirst());
    }

    private List<StoredReceipt> find(InboundOutboxEvent event) {
        return jdbcTemplate.query(
                """
                SELECT organization_id, consumer_key, source_event_id, event_name,
                       schema_version, aggregate_type, aggregate_id, payload_sha256,
                       source_correlation_id, occurred_at, received_at
                FROM consumer_inbox_records
                WHERE organization_id = ? AND consumer_key = ? AND source_event_id = ?
                """,
                PostgresConsumerInboxOperations::mapStoredReceipt,
                event.organizationId(),
                event.consumerKey(),
                event.sourceEventId());
    }

    private void requireContext(
            AuthorizedTenantContext context, InboundOutboxEvent event) {
        if (!initialized) {
            throw new ConsumerInboxException(
                    NOT_INITIALIZED, "The consumer inbox has not passed readiness checks.");
        }
        Objects.requireNonNull(context, "context");
        if (!context.organizationId().equals(event.organizationId())) {
            throw new ConsumerInboxException(
                    TENANT_MISMATCH, "The source event is not assigned to the authorized tenant.");
        }
        TenantTransactionContextVerifier.requireAuthorizedWriteTransaction(jdbcTemplate, context);
    }

    private void verifyRegistrySecurity() {
        Map<String, Object> readiness = jdbcTemplate.queryForMap("""
                SELECT pg_get_userbyid(tables.relowner) AS table_owner,
                       current_user AS runtime_role,
                       has_table_privilege(tables.oid, 'SELECT') AS can_select,
                       has_table_privilege(tables.oid, 'INSERT') AS can_insert,
                       has_table_privilege(tables.oid, 'UPDATE') AS can_update,
                       has_table_privilege(tables.oid, 'DELETE') AS can_delete,
                       has_table_privilege(tables.oid, 'TRUNCATE') AS can_truncate
                FROM pg_class tables
                JOIN pg_namespace schemas ON schemas.oid = tables.relnamespace
                WHERE schemas.nspname = 'public'
                  AND tables.relname = 'outbox_consumer_definitions'
                  AND tables.relkind = 'r'
                """);
        if (Objects.equals(readiness.get("table_owner"), readiness.get("runtime_role"))
                || !Boolean.TRUE.equals(readiness.get("can_select"))
                || Boolean.TRUE.equals(readiness.get("can_insert"))
                || Boolean.TRUE.equals(readiness.get("can_update"))
                || Boolean.TRUE.equals(readiness.get("can_delete"))
                || Boolean.TRUE.equals(readiness.get("can_truncate"))) {
            throw new IllegalStateException(
                    "PostgreSQL consumer registry security readiness check failed");
        }
    }

    private void verifyInboxSecurity() {
        Map<String, Object> readiness = jdbcTemplate.queryForMap("""
                SELECT tables.relrowsecurity,
                       tables.relforcerowsecurity,
                       pg_get_userbyid(tables.relowner) AS table_owner,
                       current_user AS runtime_role,
                       has_table_privilege(tables.oid, 'SELECT') AS can_select,
                       has_table_privilege(tables.oid, 'INSERT') AS can_insert,
                       has_table_privilege(tables.oid, 'UPDATE') AS can_update,
                       has_table_privilege(tables.oid, 'DELETE') AS can_delete,
                       has_table_privilege(tables.oid, 'TRUNCATE') AS can_truncate,
                       (
                           SELECT count(*) = 1
                              AND bool_and(policies.polname = 'consumer_inbox_records_tenant_policy')
                           FROM pg_policy policies
                           WHERE policies.polrelid = tables.oid
                       ) AS has_exact_tenant_policy,
                       (
                           SELECT count(*) = 2
                              AND bool_and(triggers.tgname IN (
                                  'consumer_inbox_records_validate_insert',
                                  'consumer_inbox_records_reject_mutation'
                              ))
                              AND bool_and(triggers.tgenabled <> 'D')
                           FROM pg_trigger triggers
                           WHERE triggers.tgrelid = tables.oid
                             AND NOT triggers.tgisinternal
                       ) AS has_exact_inbox_triggers,
                       clock_timestamp() AS database_time
                FROM pg_class tables
                JOIN pg_namespace schemas ON schemas.oid = tables.relnamespace
                WHERE schemas.nspname = 'public'
                  AND tables.relname = 'consumer_inbox_records'
                  AND tables.relkind = 'r'
                """);
        if (!Boolean.TRUE.equals(readiness.get("relrowsecurity"))
                || !Boolean.TRUE.equals(readiness.get("relforcerowsecurity"))
                || Objects.equals(readiness.get("table_owner"), readiness.get("runtime_role"))
                || !Boolean.TRUE.equals(readiness.get("can_select"))
                || !Boolean.TRUE.equals(readiness.get("can_insert"))
                || Boolean.TRUE.equals(readiness.get("can_update"))
                || Boolean.TRUE.equals(readiness.get("can_delete"))
                || Boolean.TRUE.equals(readiness.get("can_truncate"))
                || !Boolean.TRUE.equals(readiness.get("has_exact_tenant_policy"))
                || !Boolean.TRUE.equals(readiness.get("has_exact_inbox_triggers"))
                || !(readiness.get("database_time") instanceof Timestamp)) {
            throw new IllegalStateException(
                    "PostgreSQL consumer inbox table security readiness check failed");
        }
    }

    private void verifyConstraintShape() {
        var valid = jdbcTemplate.queryForObject(
                """
                SELECT EXISTS (
                    SELECT 1
                    FROM pg_constraint constraints
                    JOIN pg_class tables ON tables.oid = constraints.conrelid
                    JOIN pg_namespace schemas ON schemas.oid = tables.relnamespace
                    WHERE schemas.nspname = 'public'
                      AND tables.relname = 'consumer_inbox_records'
                      AND constraints.contype = 'p'
                      AND (
                          SELECT array_agg(attributes.attname::text ORDER BY keys.ordinality)
                          FROM unnest(constraints.conkey) WITH ORDINALITY keys(attribute_number, ordinality)
                          JOIN pg_attribute attributes
                            ON attributes.attrelid = constraints.conrelid
                           AND attributes.attnum = keys.attribute_number
                      ) = ARRAY['organization_id', 'consumer_key', 'source_event_id']::text[]
                )
                AND EXISTS (
                    SELECT 1
                    FROM pg_constraint constraints
                    JOIN pg_class tables ON tables.oid = constraints.conrelid
                    JOIN pg_class referenced_tables ON referenced_tables.oid = constraints.confrelid
                    JOIN pg_namespace schemas ON schemas.oid = tables.relnamespace
                    WHERE schemas.nspname = 'public'
                      AND tables.relname = 'consumer_inbox_records'
                      AND referenced_tables.relname = 'outbox_consumer_definitions'
                      AND constraints.conname = 'consumer_inbox_records_definition_fk'
                      AND constraints.contype = 'f'
                      AND (
                          SELECT array_agg(attributes.attname::text ORDER BY keys.ordinality)
                          FROM unnest(constraints.conkey) WITH ORDINALITY keys(attribute_number, ordinality)
                          JOIN pg_attribute attributes
                            ON attributes.attrelid = constraints.conrelid
                           AND attributes.attnum = keys.attribute_number
                      ) = ARRAY['consumer_key', 'event_name', 'schema_version']::text[]
                      AND (
                          SELECT array_agg(attributes.attname::text ORDER BY keys.ordinality)
                          FROM unnest(constraints.confkey) WITH ORDINALITY keys(attribute_number, ordinality)
                          JOIN pg_attribute attributes
                            ON attributes.attrelid = constraints.confrelid
                           AND attributes.attnum = keys.attribute_number
                      ) = ARRAY['consumer_key', 'event_name', 'schema_version']::text[]
                )
                AND EXISTS (
                    SELECT 1
                    FROM pg_constraint constraints
                    JOIN pg_class tables ON tables.oid = constraints.conrelid
                    JOIN pg_class referenced_tables ON referenced_tables.oid = constraints.confrelid
                    JOIN pg_namespace schemas ON schemas.oid = tables.relnamespace
                    WHERE schemas.nspname = 'public'
                      AND tables.relname = 'outbox_consumer_definitions'
                      AND referenced_tables.relname = 'outbox_event_definitions'
                      AND constraints.conname = 'outbox_consumer_definitions_event_fk'
                      AND constraints.contype = 'f'
                      AND (
                          SELECT array_agg(attributes.attname::text ORDER BY keys.ordinality)
                          FROM unnest(constraints.conkey) WITH ORDINALITY keys(attribute_number, ordinality)
                          JOIN pg_attribute attributes
                            ON attributes.attrelid = constraints.conrelid
                           AND attributes.attnum = keys.attribute_number
                      ) = ARRAY['event_name', 'schema_version']::text[]
                      AND (
                          SELECT array_agg(attributes.attname::text ORDER BY keys.ordinality)
                          FROM unnest(constraints.confkey) WITH ORDINALITY keys(attribute_number, ordinality)
                          JOIN pg_attribute attributes
                            ON attributes.attrelid = constraints.confrelid
                           AND attributes.attnum = keys.attribute_number
                      ) = ARRAY['event_name', 'schema_version']::text[]
                )
                """,
                Boolean.class);
        if (!Boolean.TRUE.equals(valid)) {
            throw new IllegalStateException(
                    "PostgreSQL consumer inbox constraint readiness check failed");
        }
    }

    private static ConsumerInboxReceipt receipt(
            InboundOutboxEvent event, Instant receivedAt, boolean replayed) {
        return new ConsumerInboxReceipt(
                event.organizationId(),
                event.consumerKey(),
                event.sourceEventId(),
                receivedAt,
                replayed);
    }

    private static StoredReceipt mapStoredReceipt(ResultSet rows, int rowNumber)
            throws SQLException {
        return new StoredReceipt(
                rows.getObject("organization_id", UUID.class),
                rows.getString("consumer_key"),
                rows.getObject("source_event_id", UUID.class),
                rows.getString("event_name"),
                rows.getInt("schema_version"),
                rows.getString("aggregate_type"),
                rows.getObject("aggregate_id", UUID.class),
                rows.getString("payload_sha256").strip(),
                rows.getString("source_correlation_id"),
                rows.getTimestamp("occurred_at").toInstant(),
                rows.getTimestamp("received_at").toInstant());
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record StoredReceipt(
            UUID organizationId,
            String consumerKey,
            UUID sourceEventId,
            String eventName,
            int schemaVersion,
            String aggregateType,
            UUID aggregateId,
            String payloadSha256,
            String sourceCorrelationId,
            Instant occurredAt,
            Instant receivedAt) {
        boolean matches(InboundOutboxEvent event, String expectedPayloadSha256) {
            return organizationId.equals(event.organizationId())
                    && consumerKey.equals(event.consumerKey())
                    && sourceEventId.equals(event.sourceEventId())
                    && eventName.equals(event.eventName())
                    && schemaVersion == event.schemaVersion()
                    && aggregateType.equals(event.aggregateType())
                    && aggregateId.equals(event.aggregateId())
                    && payloadSha256.equals(expectedPayloadSha256)
                    && sourceCorrelationId.equals(event.sourceCorrelationId())
                    && occurredAt.equals(event.occurredAt());
        }
    }
}
