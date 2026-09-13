DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM audit_events)
        OR EXISTS (SELECT 1 FROM outbox_events)
        OR EXISTS (SELECT 1 FROM idempotency_records) THEN
        RAISE EXCEPTION
            'V6 cannot safely invent governance metadata for pre-existing foundation rows; migrate those rows explicitly first';
    END IF;
END
$$;

CREATE TABLE audit_event_definitions (
    event_name varchar(180) NOT NULL,
    schema_version integer NOT NULL,
    display_name varchar(180) NOT NULL,
    description text NOT NULL,
    subject_type varchar(120) NOT NULL,
    reason_required boolean NOT NULL DEFAULT false,
    required_payload_keys text[] NOT NULL DEFAULT '{}'::text[],
    allowed_payload_keys text[] NOT NULL DEFAULT '{}'::text[],
    payload_schema jsonb NOT NULL DEFAULT '{}'::jsonb,
    status varchar(24) NOT NULL DEFAULT 'active',
    registry_version varchar(80) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (event_name, schema_version),
    CHECK (event_name ~ '^[a-z][a-z0-9]*([.:-][a-z0-9]+)*$'),
    CHECK (schema_version > 0),
    CHECK (subject_type ~ '^[a-z][a-z0-9]*([._:-][a-z0-9]+)*$'),
    CHECK (required_payload_keys <@ allowed_payload_keys),
    CHECK (jsonb_typeof(payload_schema) = 'object'),
    CHECK (status IN ('active', 'retired'))
);

CREATE TABLE outbox_event_definitions (
    event_name varchar(180) NOT NULL,
    schema_version integer NOT NULL,
    description text NOT NULL,
    aggregate_type varchar(120) NOT NULL,
    required_payload_keys text[] NOT NULL DEFAULT '{}'::text[],
    allowed_payload_keys text[] NOT NULL DEFAULT '{}'::text[],
    payload_schema jsonb NOT NULL DEFAULT '{}'::jsonb,
    status varchar(24) NOT NULL DEFAULT 'active',
    registry_version varchar(80) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (event_name, schema_version),
    CHECK (event_name ~ '^[a-z][a-z0-9]*([.:-][a-z0-9]+)*$'),
    CHECK (schema_version > 0),
    CHECK (aggregate_type ~ '^[a-z][a-z0-9]*([._:-][a-z0-9]+)*$'),
    CHECK (required_payload_keys <@ allowed_payload_keys),
    CHECK (jsonb_typeof(payload_schema) = 'object'),
    CHECK (status IN ('active', 'retired'))
);

REVOKE ALL ON audit_event_definitions FROM PUBLIC;
REVOKE ALL ON outbox_event_definitions FROM PUBLIC;
GRANT SELECT ON audit_event_definitions TO "${applicationRole}";
GRANT SELECT ON outbox_event_definitions TO "${applicationRole}";

COMMENT ON TABLE audit_event_definitions IS
    'Migration-owned, owner-approved audit event and top-level payload-key registry. Empty is intentional and fails closed.';
COMMENT ON TABLE outbox_event_definitions IS
    'Migration-owned, owner-approved integration event and top-level payload-key registry. Empty is intentional and fails closed.';

ALTER TABLE audit_events
    ALTER COLUMN organization_id SET NOT NULL,
    ALTER COLUMN actor_user_id SET NOT NULL,
    ADD COLUMN schema_version integer NOT NULL DEFAULT 1,
    ADD COLUMN purpose varchar(128) NOT NULL,
    ADD COLUMN correlation_id varchar(128) NOT NULL,
    ADD CONSTRAINT audit_events_definition_fk
        FOREIGN KEY (event_name, schema_version)
        REFERENCES audit_event_definitions(event_name, schema_version),
    ADD CONSTRAINT audit_events_event_name_check
        CHECK (event_name ~ '^[a-z][a-z0-9]*([.:-][a-z0-9]+)*$'),
    ADD CONSTRAINT audit_events_subject_type_check
        CHECK (subject_type ~ '^[a-z][a-z0-9]*([._:-][a-z0-9]+)*$'),
    ADD CONSTRAINT audit_events_schema_version_check CHECK (schema_version > 0),
    ADD CONSTRAINT audit_events_reason_length_check
        CHECK (reason IS NULL OR char_length(reason) BETWEEN 1 AND 2000),
    ADD CONSTRAINT audit_events_payload_check
        CHECK (jsonb_typeof(payload) = 'object' AND pg_column_size(payload) <= 1048576),
    ADD CONSTRAINT audit_events_purpose_check
        CHECK (purpose ~ '^[a-z0-9][a-z0-9._:-]{0,127}$'),
    ADD CONSTRAINT audit_events_correlation_check
        CHECK (correlation_id ~ '^[A-Za-z0-9._:-]{1,128}$');

CREATE FUNCTION careos_validate_audit_event_insert()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    definition audit_event_definitions%ROWTYPE;
    payload_keys text[];
BEGIN
    IF NEW.organization_id IS DISTINCT FROM
            nullif(current_setting('app.current_organization_id', true), '')::uuid
        OR NEW.actor_user_id IS DISTINCT FROM
            nullif(current_setting('app.current_actor_id', true), '')::uuid
        OR NEW.purpose IS DISTINCT FROM
            nullif(current_setting('app.current_purpose', true), '')
        OR NEW.correlation_id IS DISTINCT FROM
            nullif(current_setting('app.current_correlation_id', true), '') THEN
        RAISE EXCEPTION 'audit event context does not match the authorized transaction'
            USING ERRCODE = '42501';
    END IF;

    SELECT * INTO definition
    FROM audit_event_definitions
    WHERE event_name = NEW.event_name
      AND schema_version = NEW.schema_version
      AND status = 'active';
    IF NOT FOUND THEN
        RAISE EXCEPTION 'audit event definition is unknown or retired'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.subject_type IS DISTINCT FROM definition.subject_type THEN
        RAISE EXCEPTION 'audit subject type does not match its event definition'
            USING ERRCODE = '23514';
    END IF;
    IF definition.reason_required AND nullif(btrim(NEW.reason), '') IS NULL THEN
        RAISE EXCEPTION 'audit reason is required by its event definition'
            USING ERRCODE = '23514';
    END IF;

    SELECT coalesce(array_agg(key), '{}'::text[]) INTO payload_keys
    FROM jsonb_object_keys(NEW.payload) AS keys(key);
    IF NOT definition.required_payload_keys <@ payload_keys
        OR NOT payload_keys <@ definition.allowed_payload_keys THEN
        RAISE EXCEPTION 'audit payload keys do not match its event definition'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION careos_reject_audit_event_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'audit_events are append-only';
END;
$$;

CREATE TRIGGER audit_events_validate_insert
    BEFORE INSERT ON audit_events
    FOR EACH ROW EXECUTE FUNCTION careos_validate_audit_event_insert();

CREATE TRIGGER audit_events_append_only
    BEFORE UPDATE OR DELETE ON audit_events
    FOR EACH ROW EXECUTE FUNCTION careos_reject_audit_event_mutation();

ALTER TABLE outbox_events
    ALTER COLUMN organization_id SET NOT NULL,
    ADD COLUMN actor_user_id uuid NOT NULL REFERENCES users(id),
    ADD COLUMN schema_version integer NOT NULL DEFAULT 1,
    ADD COLUMN purpose varchar(128) NOT NULL,
    ADD COLUMN correlation_id varchar(128) NOT NULL,
    ADD COLUMN available_at timestamptz NOT NULL DEFAULT now(),
    ADD COLUMN attempt_count integer NOT NULL DEFAULT 0,
    ADD COLUMN claim_token uuid,
    ADD COLUMN claimed_by varchar(128),
    ADD COLUMN claimed_until timestamptz,
    ADD COLUMN last_error_code varchar(120),
    ADD COLUMN last_error_at timestamptz,
    ADD COLUMN dead_lettered_at timestamptz,
    ADD CONSTRAINT outbox_events_definition_fk
        FOREIGN KEY (event_name, schema_version)
        REFERENCES outbox_event_definitions(event_name, schema_version),
    ADD CONSTRAINT outbox_events_event_name_check
        CHECK (event_name ~ '^[a-z][a-z0-9]*([.:-][a-z0-9]+)*$'),
    ADD CONSTRAINT outbox_events_aggregate_type_check
        CHECK (aggregate_type ~ '^[a-z][a-z0-9]*([._:-][a-z0-9]+)*$'),
    ADD CONSTRAINT outbox_events_schema_version_check CHECK (schema_version > 0),
    ADD CONSTRAINT outbox_events_payload_check
        CHECK (jsonb_typeof(payload) = 'object' AND pg_column_size(payload) <= 1048576),
    ADD CONSTRAINT outbox_events_purpose_check
        CHECK (purpose ~ '^[a-z0-9][a-z0-9._:-]{0,127}$'),
    ADD CONSTRAINT outbox_events_correlation_check
        CHECK (correlation_id ~ '^[A-Za-z0-9._:-]{1,128}$'),
    ADD CONSTRAINT outbox_events_attempt_count_check CHECK (attempt_count >= 0),
    ADD CONSTRAINT outbox_events_claim_check CHECK (
        (claim_token IS NULL AND claimed_by IS NULL AND claimed_until IS NULL)
        OR (claim_token IS NOT NULL AND claimed_by IS NOT NULL AND claimed_until IS NOT NULL)
    ),
    ADD CONSTRAINT outbox_events_terminal_check CHECK (
        NOT (published_at IS NOT NULL AND dead_lettered_at IS NOT NULL)
        AND ((published_at IS NULL AND dead_lettered_at IS NULL) OR claim_token IS NULL)
    ),
    ADD CONSTRAINT outbox_events_error_check CHECK (
        (last_error_code IS NULL) = (last_error_at IS NULL)
        AND (last_error_code IS NULL OR last_error_code ~ '^[a-z][a-z0-9]*([._:-][a-z0-9]+)*$')
    );

CREATE INDEX outbox_events_due_idx
    ON outbox_events (organization_id, available_at, occurred_at, id)
    WHERE published_at IS NULL AND dead_lettered_at IS NULL;

CREATE FUNCTION careos_validate_outbox_event_insert()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    definition outbox_event_definitions%ROWTYPE;
    payload_keys text[];
BEGIN
    IF NEW.organization_id IS DISTINCT FROM
            nullif(current_setting('app.current_organization_id', true), '')::uuid
        OR NEW.actor_user_id IS DISTINCT FROM
            nullif(current_setting('app.current_actor_id', true), '')::uuid
        OR NEW.purpose IS DISTINCT FROM
            nullif(current_setting('app.current_purpose', true), '')
        OR NEW.correlation_id IS DISTINCT FROM
            nullif(current_setting('app.current_correlation_id', true), '') THEN
        RAISE EXCEPTION 'outbox event context does not match the authorized transaction'
            USING ERRCODE = '42501';
    END IF;

    SELECT * INTO definition
    FROM outbox_event_definitions
    WHERE event_name = NEW.event_name
      AND schema_version = NEW.schema_version
      AND status = 'active';
    IF NOT FOUND THEN
        RAISE EXCEPTION 'outbox event definition is unknown or retired'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.aggregate_type IS DISTINCT FROM definition.aggregate_type THEN
        RAISE EXCEPTION 'outbox aggregate type does not match its event definition'
            USING ERRCODE = '23514';
    END IF;

    SELECT coalesce(array_agg(key), '{}'::text[]) INTO payload_keys
    FROM jsonb_object_keys(NEW.payload) AS keys(key);
    IF NOT definition.required_payload_keys <@ payload_keys
        OR NOT payload_keys <@ definition.allowed_payload_keys THEN
        RAISE EXCEPTION 'outbox payload keys do not match its event definition'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION careos_validate_outbox_delivery_transition()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    configured_worker text := nullif(current_setting('app.current_outbox_worker_id', true), '');
    configured_claim uuid := nullif(current_setting('app.current_outbox_claim_token', true), '')::uuid;
BEGIN
    IF ROW(NEW.id, NEW.organization_id, NEW.actor_user_id, NEW.event_name,
           NEW.schema_version, NEW.aggregate_type, NEW.aggregate_id, NEW.payload,
           NEW.purpose, NEW.correlation_id, NEW.occurred_at)
       IS DISTINCT FROM
       ROW(OLD.id, OLD.organization_id, OLD.actor_user_id, OLD.event_name,
           OLD.schema_version, OLD.aggregate_type, OLD.aggregate_id, OLD.payload,
           OLD.purpose, OLD.correlation_id, OLD.occurred_at) THEN
        RAISE EXCEPTION 'outbox event content is immutable';
    END IF;
    IF OLD.published_at IS NOT NULL OR OLD.dead_lettered_at IS NOT NULL THEN
        RAISE EXCEPTION 'terminal outbox delivery evidence is immutable';
    END IF;

    IF NEW.claim_token IS NOT NULL THEN
        IF configured_worker IS DISTINCT FROM NEW.claimed_by
            OR NEW.claimed_until <= clock_timestamp()
            OR NEW.attempt_count <> OLD.attempt_count + 1
            OR NEW.available_at IS DISTINCT FROM OLD.available_at
            OR NEW.last_error_code IS DISTINCT FROM OLD.last_error_code
            OR NEW.last_error_at IS DISTINCT FROM OLD.last_error_at
            OR NEW.published_at IS DISTINCT FROM OLD.published_at
            OR NEW.dead_lettered_at IS DISTINCT FROM OLD.dead_lettered_at
            OR (OLD.claim_token IS NOT NULL AND OLD.claimed_until > clock_timestamp()) THEN
            RAISE EXCEPTION 'invalid outbox claim transition';
        END IF;
        RETURN NEW;
    END IF;

    IF OLD.claim_token IS NULL
        OR configured_worker IS DISTINCT FROM OLD.claimed_by
        OR configured_claim IS DISTINCT FROM OLD.claim_token
        OR NEW.claimed_by IS NOT NULL
        OR NEW.claimed_until IS NOT NULL
        OR NEW.attempt_count <> OLD.attempt_count THEN
        RAISE EXCEPTION 'invalid outbox finalization claim';
    END IF;

    IF NEW.published_at IS NOT NULL THEN
        IF NEW.dead_lettered_at IS NOT NULL
            OR NEW.available_at IS DISTINCT FROM OLD.available_at
            OR NEW.last_error_code IS DISTINCT FROM OLD.last_error_code
            OR NEW.last_error_at IS DISTINCT FROM OLD.last_error_at THEN
            RAISE EXCEPTION 'invalid outbox publication transition';
        END IF;
    ELSIF NEW.dead_lettered_at IS NOT NULL THEN
        IF NEW.last_error_code IS NULL OR NEW.last_error_at IS NULL
            OR NEW.available_at IS DISTINCT FROM OLD.available_at THEN
            RAISE EXCEPTION 'invalid outbox dead-letter transition';
        END IF;
    ELSE
        IF NEW.last_error_code IS NULL OR NEW.last_error_at IS NULL
            OR NEW.available_at < OLD.available_at THEN
            RAISE EXCEPTION 'invalid outbox retry transition';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION careos_reject_outbox_event_delete()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'outbox_events cannot be deleted';
END;
$$;

CREATE TRIGGER outbox_events_validate_insert
    BEFORE INSERT ON outbox_events
    FOR EACH ROW EXECUTE FUNCTION careos_validate_outbox_event_insert();

CREATE TRIGGER outbox_events_validate_delivery_update
    BEFORE UPDATE ON outbox_events
    FOR EACH ROW EXECUTE FUNCTION careos_validate_outbox_delivery_transition();

CREATE TRIGGER outbox_events_reject_delete
    BEFORE DELETE ON outbox_events
    FOR EACH ROW EXECUTE FUNCTION careos_reject_outbox_event_delete();

ALTER TABLE idempotency_records
    DROP CONSTRAINT idempotency_records_organization_id_idempotency_key_key,
    ALTER COLUMN organization_id SET NOT NULL,
    ALTER COLUMN request_hash TYPE char(64),
    ADD COLUMN actor_user_id uuid NOT NULL REFERENCES users(id),
    ADD COLUMN operation_key varchar(160) NOT NULL,
    ADD COLUMN state varchar(24) NOT NULL DEFAULT 'in_progress',
    ADD COLUMN response_media_type varchar(120),
    ADD COLUMN purpose varchar(128) NOT NULL,
    ADD COLUMN correlation_id varchar(128) NOT NULL,
    ADD COLUMN created_at timestamptz NOT NULL DEFAULT now(),
    ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now(),
    ADD COLUMN completed_at timestamptz,
    ADD CONSTRAINT idempotency_records_scope_uq
        UNIQUE (organization_id, actor_user_id, operation_key, idempotency_key),
    ADD CONSTRAINT idempotency_records_operation_check
        CHECK (operation_key ~ '^[a-z][a-z0-9]*([.:-][a-z0-9]+)*$'),
    ADD CONSTRAINT idempotency_records_key_check
        CHECK (idempotency_key ~ '^[A-Za-z0-9._:-]{16,180}$'),
    ADD CONSTRAINT idempotency_records_hash_check
        CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    ADD CONSTRAINT idempotency_records_state_check CHECK (
        (state = 'in_progress'
            AND response_code IS NULL
            AND response_body IS NULL
            AND response_media_type IS NULL
            AND completed_at IS NULL)
        OR
        (state = 'completed'
            AND response_code IS NOT NULL
            AND response_body IS NOT NULL
            AND response_media_type IS NOT NULL
            AND completed_at IS NOT NULL)
    ),
    ADD CONSTRAINT idempotency_records_response_code_check
        CHECK (response_code IS NULL OR response_code BETWEEN 200 AND 599),
    ADD CONSTRAINT idempotency_records_body_size_check
        CHECK (response_body IS NULL OR pg_column_size(response_body) <= 1048576),
    ADD CONSTRAINT idempotency_records_expiry_check CHECK (expires_at > created_at),
    ADD CONSTRAINT idempotency_records_purpose_check
        CHECK (purpose ~ '^[a-z0-9][a-z0-9._:-]{0,127}$'),
    ADD CONSTRAINT idempotency_records_correlation_check
        CHECK (correlation_id ~ '^[A-Za-z0-9._:-]{1,128}$');

CREATE INDEX idempotency_records_expiry_idx
    ON idempotency_records (organization_id, actor_user_id, expires_at);

DROP POLICY idempotency_records_tenant_policy ON idempotency_records;
CREATE POLICY idempotency_records_tenant_policy ON idempotency_records
    USING (
        organization_id = nullif(current_setting('app.current_organization_id', true), '')::uuid
        AND actor_user_id = nullif(current_setting('app.current_actor_id', true), '')::uuid
    )
    WITH CHECK (
        organization_id = nullif(current_setting('app.current_organization_id', true), '')::uuid
        AND actor_user_id = nullif(current_setting('app.current_actor_id', true), '')::uuid
    );

CREATE FUNCTION careos_validate_idempotency_insert()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.organization_id IS DISTINCT FROM
            nullif(current_setting('app.current_organization_id', true), '')::uuid
        OR NEW.actor_user_id IS DISTINCT FROM
            nullif(current_setting('app.current_actor_id', true), '')::uuid
        OR NEW.purpose IS DISTINCT FROM
            nullif(current_setting('app.current_purpose', true), '')
        OR NEW.correlation_id IS DISTINCT FROM
            nullif(current_setting('app.current_correlation_id', true), '') THEN
        RAISE EXCEPTION 'idempotency context does not match the authorized transaction'
            USING ERRCODE = '42501';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION careos_validate_idempotency_update()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF ROW(NEW.id, NEW.organization_id, NEW.actor_user_id, NEW.operation_key,
           NEW.idempotency_key, NEW.request_hash, NEW.purpose, NEW.correlation_id,
           NEW.created_at, NEW.expires_at)
       IS DISTINCT FROM
       ROW(OLD.id, OLD.organization_id, OLD.actor_user_id, OLD.operation_key,
           OLD.idempotency_key, OLD.request_hash, OLD.purpose, OLD.correlation_id,
           OLD.created_at, OLD.expires_at)
        OR OLD.state <> 'in_progress'
        OR NEW.state <> 'completed'
        OR NEW.updated_at < OLD.updated_at THEN
        RAISE EXCEPTION 'invalid idempotency completion transition';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION careos_validate_idempotency_delete()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.expires_at > clock_timestamp() THEN
        RAISE EXCEPTION 'unexpired idempotency evidence cannot be deleted';
    END IF;
    RETURN OLD;
END;
$$;

CREATE TRIGGER idempotency_records_validate_insert
    BEFORE INSERT ON idempotency_records
    FOR EACH ROW EXECUTE FUNCTION careos_validate_idempotency_insert();

CREATE TRIGGER idempotency_records_validate_update
    BEFORE UPDATE ON idempotency_records
    FOR EACH ROW EXECUTE FUNCTION careos_validate_idempotency_update();

CREATE TRIGGER idempotency_records_validate_delete
    BEFORE DELETE ON idempotency_records
    FOR EACH ROW EXECUTE FUNCTION careos_validate_idempotency_delete();

COMMENT ON TABLE audit_events IS
    'Tenant, actor, purpose, correlation, and schema-bound append-only governance evidence.';
COMMENT ON TABLE outbox_events IS
    'Tenant-scoped transactional outbox with immutable event content and leased at-least-once delivery evidence.';
COMMENT ON TABLE idempotency_records IS
    'Actor-scoped request replay evidence completed atomically with its governed mutation.';
