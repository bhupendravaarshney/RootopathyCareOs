CREATE TABLE outbox_consumer_definitions (
    consumer_key varchar(160) NOT NULL,
    event_name varchar(180) NOT NULL,
    schema_version integer NOT NULL,
    description text NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'active',
    registry_version varchar(80) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (consumer_key, event_name, schema_version),
    CONSTRAINT outbox_consumer_definitions_event_fk
        FOREIGN KEY (event_name, schema_version)
        REFERENCES outbox_event_definitions (event_name, schema_version),
    CHECK (consumer_key ~ '^[a-z][a-z0-9]*([.:-][a-z0-9]+)*$'),
    CHECK (event_name ~ '^[a-z][a-z0-9]*([.:-][a-z0-9]+)*$'),
    CHECK (schema_version > 0),
    CHECK (char_length(btrim(description)) BETWEEN 1 AND 2000),
    CHECK (status IN ('active', 'retired')),
    CHECK (registry_version ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,79}$'),
    CHECK (isfinite(created_at))
);

CREATE TABLE consumer_inbox_records (
    organization_id uuid NOT NULL REFERENCES organizations(id),
    consumer_key varchar(160) NOT NULL,
    source_event_id uuid NOT NULL,
    event_name varchar(180) NOT NULL,
    schema_version integer NOT NULL,
    aggregate_type varchar(120) NOT NULL,
    aggregate_id uuid NOT NULL,
    payload_sha256 char(64) NOT NULL,
    source_correlation_id varchar(128) NOT NULL,
    occurred_at timestamptz NOT NULL,
    received_by_actor_id uuid NOT NULL,
    purpose varchar(128) NOT NULL,
    correlation_id varchar(128) NOT NULL,
    received_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (organization_id, consumer_key, source_event_id),
    CONSTRAINT consumer_inbox_records_definition_fk
        FOREIGN KEY (consumer_key, event_name, schema_version)
        REFERENCES outbox_consumer_definitions (consumer_key, event_name, schema_version),
    CHECK (consumer_key ~ '^[a-z][a-z0-9]*([.:-][a-z0-9]+)*$'),
    CHECK (event_name ~ '^[a-z][a-z0-9]*([.:-][a-z0-9]+)*$'),
    CHECK (schema_version > 0),
    CHECK (aggregate_type ~ '^[a-z][a-z0-9]*([._:-][a-z0-9]+)*$'),
    CHECK (payload_sha256 ~ '^[0-9a-f]{64}$'),
    CHECK (source_correlation_id ~ '^[A-Za-z0-9._:-]{1,128}$'),
    CHECK (purpose ~ '^[a-z0-9][a-z0-9._:-]{0,127}$'),
    CHECK (correlation_id ~ '^[A-Za-z0-9._:-]{1,128}$'),
    CHECK (isfinite(occurred_at)),
    CHECK (isfinite(received_at))
);

CREATE INDEX consumer_inbox_records_received_idx
    ON consumer_inbox_records (organization_id, received_at, source_event_id);

ALTER TABLE consumer_inbox_records ENABLE ROW LEVEL SECURITY;
ALTER TABLE consumer_inbox_records FORCE ROW LEVEL SECURITY;
CREATE POLICY consumer_inbox_records_tenant_policy ON consumer_inbox_records
    USING (organization_id = nullif(current_setting('app.current_organization_id', true), '')::uuid)
    WITH CHECK (organization_id = nullif(current_setting('app.current_organization_id', true), '')::uuid);

CREATE FUNCTION careos_validate_consumer_inbox_insert()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    expected_aggregate_type text;
BEGIN
    IF NEW.organization_id IS DISTINCT FROM
            nullif(current_setting('app.current_organization_id', true), '')::uuid
        OR NEW.received_by_actor_id IS DISTINCT FROM
            nullif(current_setting('app.current_actor_id', true), '')::uuid
        OR NEW.purpose IS DISTINCT FROM
            nullif(current_setting('app.current_purpose', true), '')
        OR NEW.correlation_id IS DISTINCT FROM
            nullif(current_setting('app.current_correlation_id', true), '') THEN
        RAISE EXCEPTION 'consumer inbox receipt does not match the authorized transaction'
            USING ERRCODE = '42501';
    END IF;

    SELECT events.aggregate_type
    INTO expected_aggregate_type
    FROM outbox_consumer_definitions consumers
    JOIN outbox_event_definitions events
      ON events.event_name = consumers.event_name
     AND events.schema_version = consumers.schema_version
    WHERE consumers.consumer_key = NEW.consumer_key
      AND consumers.event_name = NEW.event_name
      AND consumers.schema_version = NEW.schema_version
      AND consumers.status = 'active'
      AND events.status = 'active';

    IF NOT FOUND THEN
        RAISE EXCEPTION 'outbox consumer definition is unknown or retired'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.aggregate_type IS DISTINCT FROM expected_aggregate_type THEN
        RAISE EXCEPTION 'consumer inbox aggregate type does not match its event definition'
            USING ERRCODE = '23514';
    END IF;

    NEW.received_at := clock_timestamp();
    RETURN NEW;
END;
$$;

CREATE FUNCTION careos_reject_consumer_inbox_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'consumer inbox receipts are append-only'
        USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER consumer_inbox_records_validate_insert
    BEFORE INSERT ON consumer_inbox_records
    FOR EACH ROW EXECUTE FUNCTION careos_validate_consumer_inbox_insert();

CREATE TRIGGER consumer_inbox_records_reject_mutation
    BEFORE UPDATE OR DELETE ON consumer_inbox_records
    FOR EACH ROW EXECUTE FUNCTION careos_reject_consumer_inbox_mutation();

REVOKE ALL ON outbox_consumer_definitions FROM PUBLIC;
REVOKE ALL ON consumer_inbox_records FROM PUBLIC;
GRANT SELECT ON outbox_consumer_definitions TO "${applicationRole}";
GRANT SELECT, INSERT ON consumer_inbox_records TO "${applicationRole}";

COMMENT ON TABLE outbox_consumer_definitions IS
    'Migration-owned allow-list mapping approved consumers to versioned outbox event definitions. Empty is intentional and fails closed.';
COMMENT ON TABLE consumer_inbox_records IS
    'Append-only tenant-scoped receipts that atomically deduplicate source outbox events without copying their payload.';
