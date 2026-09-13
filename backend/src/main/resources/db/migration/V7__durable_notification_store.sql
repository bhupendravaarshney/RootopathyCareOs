CREATE TABLE durable_notifications (
    organization_id uuid NOT NULL REFERENCES organizations(id),
    notification_id uuid NOT NULL,
    recipient_user_id uuid NOT NULL REFERENCES users(id),
    requested_by_user_id uuid NOT NULL REFERENCES users(id),
    template_key varchar(160) NOT NULL,
    template_version integer NOT NULL,
    encrypted_payload bytea NOT NULL,
    payload_nonce bytea NOT NULL,
    encryption_key_id varchar(64) NOT NULL,
    payload_digest varchar(64) NOT NULL,
    deduplication_hash varchar(64) NOT NULL,
    content_digest varchar(64) NOT NULL,
    requested_not_before timestamptz NOT NULL,
    available_at timestamptz NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'ready',
    attempt_count integer NOT NULL DEFAULT 0,
    maximum_attempts integer NOT NULL,
    initial_retry_delay_ms bigint NOT NULL,
    maximum_retry_delay_ms bigint NOT NULL,
    terminal_retention_ms bigint NOT NULL,
    claim_token_hash varchar(64),
    claimed_until timestamptz,
    last_error_code varchar(160),
    last_error_at timestamptz,
    completed_at timestamptz,
    dead_lettered_at timestamptz,
    expires_at timestamptz,
    purpose varchar(128) NOT NULL,
    correlation_id varchar(128) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (organization_id, notification_id),
    UNIQUE (organization_id, deduplication_hash),
    CHECK (template_key ~ '^[a-z][a-z0-9]*([.:-][a-z0-9]+)*$'),
    CHECK (template_version > 0),
    CHECK (octet_length(encrypted_payload) BETWEEN 17 AND 1048592),
    CHECK (octet_length(payload_nonce) = 12),
    CHECK (encryption_key_id ~ '^[a-z][a-z0-9._-]{0,63}$'),
    CHECK (payload_digest ~ '^[0-9a-f]{64}$'),
    CHECK (deduplication_hash ~ '^[0-9a-f]{64}$'),
    CHECK (content_digest ~ '^[0-9a-f]{64}$'),
    CHECK (claim_token_hash IS NULL OR claim_token_hash ~ '^[0-9a-f]{64}$'),
    CHECK (available_at >= requested_not_before),
    CHECK (status IN ('ready', 'leased', 'completed', 'dead_lettered')),
    CHECK (attempt_count BETWEEN 0 AND maximum_attempts),
    CHECK (maximum_attempts BETWEEN 1 AND 100),
    CHECK (initial_retry_delay_ms BETWEEN 10 AND 3600000),
    CHECK (maximum_retry_delay_ms BETWEEN initial_retry_delay_ms AND 86400000),
    CHECK (terminal_retention_ms BETWEEN 60000 AND 7776000000),
    CHECK ((last_error_code IS NULL) = (last_error_at IS NULL)),
    CHECK (last_error_code IS NULL
        OR last_error_code ~ '^[a-z][a-z0-9]*([.:-][a-z0-9]+)*$'),
    CHECK (purpose ~ '^[a-z0-9][a-z0-9._:-]{0,127}$'),
    CHECK (correlation_id ~ '^[A-Za-z0-9._:-]{1,128}$'),
    CHECK (updated_at >= created_at),
    CHECK (attempt_count > 0 OR (claim_token_hash IS NULL AND last_error_code IS NULL)),
    CHECK (
        (status = 'ready'
            AND claimed_until IS NULL
            AND completed_at IS NULL
            AND dead_lettered_at IS NULL
            AND expires_at IS NULL)
        OR (status = 'leased'
            AND claim_token_hash IS NOT NULL
            AND claimed_until IS NOT NULL
            AND completed_at IS NULL
            AND dead_lettered_at IS NULL
            AND expires_at IS NULL)
        OR (status = 'completed'
            AND claim_token_hash IS NOT NULL
            AND claimed_until IS NULL
            AND completed_at IS NOT NULL
            AND dead_lettered_at IS NULL
            AND expires_at >= completed_at)
        OR (status = 'dead_lettered'
            AND claim_token_hash IS NOT NULL
            AND claimed_until IS NULL
            AND completed_at IS NULL
            AND dead_lettered_at IS NOT NULL
            AND expires_at >= dead_lettered_at
            AND last_error_code IS NOT NULL)
    )
);

CREATE INDEX durable_notifications_due_idx
    ON durable_notifications (organization_id, available_at, created_at, notification_id)
    WHERE status IN ('ready', 'leased');

CREATE INDEX durable_notifications_terminal_expiry_idx
    ON durable_notifications (organization_id, expires_at, notification_id)
    WHERE status IN ('completed', 'dead_lettered');

CREATE INDEX durable_notifications_recipient_idx
    ON durable_notifications (organization_id, recipient_user_id, created_at DESC);

ALTER TABLE durable_notifications ENABLE ROW LEVEL SECURITY;
ALTER TABLE durable_notifications FORCE ROW LEVEL SECURITY;
CREATE POLICY durable_notifications_tenant_policy ON durable_notifications
    USING (organization_id = nullif(current_setting('app.current_organization_id', true), '')::uuid)
    WITH CHECK (organization_id = nullif(current_setting('app.current_organization_id', true), '')::uuid);

CREATE FUNCTION careos_validate_notification_insert()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.organization_id IS DISTINCT FROM
            nullif(current_setting('app.current_organization_id', true), '')::uuid
        OR NEW.requested_by_user_id IS DISTINCT FROM
            nullif(current_setting('app.current_actor_id', true), '')::uuid
        OR NEW.purpose IS DISTINCT FROM
            nullif(current_setting('app.current_purpose', true), '')
        OR NEW.correlation_id IS DISTINCT FROM
            nullif(current_setting('app.current_correlation_id', true), '') THEN
        RAISE EXCEPTION 'notification context does not match the authorized transaction'
            USING ERRCODE = '42501';
    END IF;
    IF NEW.status <> 'ready'
        OR NEW.attempt_count <> 0
        OR NEW.available_at IS DISTINCT FROM NEW.requested_not_before
        OR NEW.claim_token_hash IS NOT NULL
        OR NEW.claimed_until IS NOT NULL
        OR NEW.last_error_code IS NOT NULL
        OR NEW.last_error_at IS NOT NULL
        OR NEW.completed_at IS NOT NULL
        OR NEW.dead_lettered_at IS NOT NULL
        OR NEW.expires_at IS NOT NULL THEN
        RAISE EXCEPTION 'notification must begin in the ready state'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION careos_validate_notification_transition()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    configured_claim_hash text :=
        nullif(current_setting('app.current_notification_claim_hash', true), '');
BEGIN
    IF ROW(NEW.organization_id, NEW.notification_id, NEW.recipient_user_id,
           NEW.requested_by_user_id, NEW.template_key, NEW.template_version,
           NEW.encrypted_payload, NEW.payload_nonce, NEW.encryption_key_id,
           NEW.payload_digest, NEW.deduplication_hash, NEW.content_digest,
           NEW.requested_not_before, NEW.maximum_attempts,
           NEW.initial_retry_delay_ms, NEW.maximum_retry_delay_ms,
           NEW.terminal_retention_ms, NEW.purpose, NEW.correlation_id, NEW.created_at)
       IS DISTINCT FROM
       ROW(OLD.organization_id, OLD.notification_id, OLD.recipient_user_id,
           OLD.requested_by_user_id, OLD.template_key, OLD.template_version,
           OLD.encrypted_payload, OLD.payload_nonce, OLD.encryption_key_id,
           OLD.payload_digest, OLD.deduplication_hash, OLD.content_digest,
           OLD.requested_not_before, OLD.maximum_attempts,
           OLD.initial_retry_delay_ms, OLD.maximum_retry_delay_ms,
           OLD.terminal_retention_ms, OLD.purpose, OLD.correlation_id, OLD.created_at) THEN
        RAISE EXCEPTION 'notification content and policy are immutable';
    END IF;
    IF NEW.organization_id IS DISTINCT FROM
            nullif(current_setting('app.current_organization_id', true), '')::uuid THEN
        RAISE EXCEPTION 'notification tenant context does not match the authorized transaction'
            USING ERRCODE = '42501';
    END IF;
    IF OLD.status IN ('completed', 'dead_lettered') THEN
        RAISE EXCEPTION 'terminal notification evidence is immutable';
    END IF;
    IF NEW.updated_at < OLD.updated_at THEN
        RAISE EXCEPTION 'notification update time cannot move backwards';
    END IF;

    IF NEW.status = 'leased' THEN
        IF NOT (OLD.status = 'ready' AND OLD.available_at <= clock_timestamp()
                OR OLD.status = 'leased' AND OLD.claimed_until <= clock_timestamp())
            OR NEW.attempt_count <> OLD.attempt_count + 1
            OR NEW.attempt_count > NEW.maximum_attempts
            OR NEW.claim_token_hash IS NULL
            OR NEW.claim_token_hash IS NOT DISTINCT FROM OLD.claim_token_hash
            OR NEW.claimed_until <= clock_timestamp()
            OR NEW.available_at IS DISTINCT FROM OLD.available_at
            OR NEW.last_error_code IS DISTINCT FROM OLD.last_error_code
            OR NEW.last_error_at IS DISTINCT FROM OLD.last_error_at
            OR NEW.completed_at IS NOT NULL
            OR NEW.dead_lettered_at IS NOT NULL
            OR NEW.expires_at IS NOT NULL THEN
            RAISE EXCEPTION 'invalid notification claim transition';
        END IF;
        RETURN NEW;
    END IF;

    IF OLD.status = 'leased'
        AND OLD.claimed_until <= clock_timestamp()
        AND OLD.attempt_count >= OLD.maximum_attempts
        AND NEW.status = 'dead_lettered' THEN
        IF NEW.attempt_count <> OLD.attempt_count
            OR NEW.claim_token_hash IS DISTINCT FROM OLD.claim_token_hash
            OR NEW.claimed_until IS NOT NULL
            OR NEW.available_at IS DISTINCT FROM OLD.available_at
            OR NEW.last_error_code IS DISTINCT FROM 'notification.lease-exhausted'
            OR NEW.last_error_at IS NULL
            OR NEW.completed_at IS NOT NULL
            OR NEW.dead_lettered_at IS NULL
            OR NEW.expires_at < NEW.dead_lettered_at THEN
            RAISE EXCEPTION 'invalid notification expired-lease transition';
        END IF;
        RETURN NEW;
    END IF;

    IF OLD.status <> 'leased'
        OR configured_claim_hash IS DISTINCT FROM OLD.claim_token_hash
        OR OLD.claimed_until <= clock_timestamp()
        OR NEW.attempt_count <> OLD.attempt_count
        OR NEW.claim_token_hash IS DISTINCT FROM OLD.claim_token_hash
        OR NEW.claimed_until IS NOT NULL THEN
        RAISE EXCEPTION 'invalid notification finalization claim';
    END IF;

    IF NEW.status = 'completed' THEN
        IF NEW.available_at IS DISTINCT FROM OLD.available_at
            OR NEW.last_error_code IS DISTINCT FROM OLD.last_error_code
            OR NEW.last_error_at IS DISTINCT FROM OLD.last_error_at
            OR NEW.completed_at IS NULL
            OR NEW.dead_lettered_at IS NOT NULL
            OR NEW.expires_at < NEW.completed_at THEN
            RAISE EXCEPTION 'invalid notification acknowledgement transition';
        END IF;
    ELSIF NEW.status = 'dead_lettered' THEN
        IF NEW.available_at IS DISTINCT FROM OLD.available_at
            OR NEW.last_error_code IS NULL
            OR NEW.last_error_at IS NULL
            OR NEW.last_error_at <= coalesce(OLD.last_error_at, '-infinity'::timestamptz)
            OR NEW.completed_at IS NOT NULL
            OR NEW.dead_lettered_at IS NULL
            OR NEW.expires_at < NEW.dead_lettered_at THEN
            RAISE EXCEPTION 'invalid notification dead-letter transition';
        END IF;
    ELSIF NEW.status = 'ready' THEN
        IF NEW.available_at <= clock_timestamp()
            OR NEW.last_error_code IS NULL
            OR NEW.last_error_at IS NULL
            OR NEW.last_error_at <= coalesce(OLD.last_error_at, '-infinity'::timestamptz)
            OR NEW.completed_at IS NOT NULL
            OR NEW.dead_lettered_at IS NOT NULL
            OR NEW.expires_at IS NOT NULL THEN
            RAISE EXCEPTION 'invalid notification retry transition';
        END IF;
    ELSE
        RAISE EXCEPTION 'invalid notification state transition';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION careos_validate_notification_delete()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.organization_id IS DISTINCT FROM
            nullif(current_setting('app.current_organization_id', true), '')::uuid
        OR nullif(current_setting('app.current_notification_cleanup', true), '') IS DISTINCT FROM 'true'
        OR OLD.status NOT IN ('completed', 'dead_lettered')
        OR OLD.expires_at IS NULL
        OR OLD.expires_at > clock_timestamp() THEN
        RAISE EXCEPTION 'notification retention has not expired';
    END IF;
    RETURN OLD;
END;
$$;

CREATE TRIGGER durable_notifications_validate_insert
    BEFORE INSERT ON durable_notifications
    FOR EACH ROW EXECUTE FUNCTION careos_validate_notification_insert();

CREATE TRIGGER durable_notifications_validate_transition
    BEFORE UPDATE ON durable_notifications
    FOR EACH ROW EXECUTE FUNCTION careos_validate_notification_transition();

CREATE TRIGGER durable_notifications_validate_delete
    BEFORE DELETE ON durable_notifications
    FOR EACH ROW EXECUTE FUNCTION careos_validate_notification_delete();

REVOKE ALL ON durable_notifications FROM PUBLIC;
GRANT SELECT, INSERT, UPDATE, DELETE ON durable_notifications TO "${applicationRole}";

COMMENT ON TABLE durable_notifications IS
    'Tenant-scoped encrypted notification requests with deduplication, leases, bounded retries, dead letters, and retained terminal evidence; no destination is stored.';
