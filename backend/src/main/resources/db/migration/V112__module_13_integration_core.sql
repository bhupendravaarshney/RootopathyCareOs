ALTER TABLE outbox_events
    ADD CONSTRAINT outbox_events_organization_id_id_key UNIQUE (organization_id,id);

CREATE TABLE integration_mapping_versions (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    mapping_code varchar(80) NOT NULL,
    mapping_kind varchar(24) NOT NULL,
    version_number integer NOT NULL,
    source_version varchar(80) NOT NULL,
    target_version varchar(80) NOT NULL,
    fhir_release varchar(40),
    profile_package varchar(160),
    profile_version varchar(80),
    terminology_version varchar(80),
    content_digest char(64) NOT NULL,
    status varchar(20) NOT NULL DEFAULT 'draft',
    activated_at timestamptz,
    activated_by uuid,
    retired_at timestamptz,
    retired_by uuid,
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    UNIQUE (organization_id,id),
    UNIQUE (organization_id,mapping_code,version_number),
    CHECK (mapping_code~'^[a-z][a-z0-9_.-]{2,79}$'),
    CHECK (mapping_kind IN ('fhir','terminology','message','payment','calendar','diagnostic','api')),
    CHECK (version_number BETWEEN 1 AND 1000000),
    CHECK (char_length(source_version) BETWEEN 1 AND 80),
    CHECK (char_length(target_version) BETWEEN 1 AND 80),
    CHECK (content_digest~'^[0-9a-f]{64}$'),
    CHECK (fhir_release IS NULL OR fhir_release~'^[A-Za-z0-9][A-Za-z0-9._-]{0,39}$'),
    CHECK (profile_package IS NULL OR profile_package~'^[A-Za-z0-9][A-Za-z0-9._:/-]{0,159}$'),
    CHECK (profile_version IS NULL OR profile_version~'^[A-Za-z0-9][A-Za-z0-9._-]{0,79}$'),
    CHECK (terminology_version IS NULL OR char_length(terminology_version) BETWEEN 1 AND 80),
    CHECK (mapping_kind<>'fhir' OR
           (fhir_release IS NOT NULL AND profile_package IS NOT NULL
            AND profile_version IS NOT NULL AND terminology_version IS NOT NULL)),
    CHECK (mapping_kind<>'terminology' OR terminology_version IS NOT NULL),
    CHECK (status IN ('draft','active','retired')),
    CHECK ((status IN ('active','retired'))=(activated_at IS NOT NULL)),
    CHECK ((activated_at IS NULL)=(activated_by IS NULL)),
    CHECK ((status='retired')=(retired_at IS NOT NULL)),
    CHECK ((retired_at IS NULL)=(retired_by IS NULL)),
    CHECK (lock_version>=0)
);

CREATE TABLE integration_connections (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    connection_code varchar(80) NOT NULL,
    display_name varchar(160) NOT NULL,
    provider_kind varchar(24) NOT NULL,
    contract_version varchar(80) NOT NULL,
    endpoint_reference varchar(120) NOT NULL,
    security_profile_key varchar(80) NOT NULL,
    credential_reference_digest char(64) NOT NULL,
    mapping_version_id uuid,
    status varchar(20) NOT NULL DEFAULT 'draft',
    validated_at timestamptz,
    validated_by uuid,
    last_transition_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    last_transition_by uuid NOT NULL,
    last_transition_reason varchar(500) NOT NULL,
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    UNIQUE (organization_id,id),
    UNIQUE (organization_id,connection_code),
    FOREIGN KEY (organization_id,mapping_version_id)
        REFERENCES integration_mapping_versions(organization_id,id),
    CHECK (connection_code~'^[a-z][a-z0-9_.-]{2,79}$'),
    CHECK (char_length(display_name) BETWEEN 1 AND 160),
    CHECK (provider_kind IN ('fhir','whatsapp','payment','calendar','lab_imaging','webhook')),
    CHECK (char_length(contract_version) BETWEEN 1 AND 80),
    CHECK (endpoint_reference~'^[a-z][a-z0-9_.:-]{2,119}$'
           AND endpoint_reference!~'://' AND endpoint_reference!~'@'),
    CHECK (security_profile_key~'^[a-z][a-z0-9_.-]{2,79}$'),
    CHECK (credential_reference_digest~'^[0-9a-f]{64}$'),
    CHECK (status IN ('draft','validated','suspended','retired')),
    CHECK ((status IN ('validated','suspended','retired'))=(validated_at IS NOT NULL)),
    CHECK ((validated_at IS NULL)=(validated_by IS NULL)),
    CHECK (char_length(last_transition_reason) BETWEEN 10 AND 500),
    CHECK (lock_version>=0)
);

CREATE INDEX integration_connections_kind_status_idx
    ON integration_connections(organization_id,provider_kind,status,id);

CREATE TABLE inbound_webhook_receipts (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    connection_id uuid NOT NULL,
    signed_at timestamptz NOT NULL,
    received_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    signature_scheme varchar(40) NOT NULL,
    signature_key_version varchar(80) NOT NULL,
    signature_digest char(64) NOT NULL,
    nonce_digest char(64) NOT NULL,
    idempotency_digest char(64) NOT NULL,
    payload_digest char(64) NOT NULL,
    content_type varchar(120) NOT NULL,
    payload_byte_count bigint NOT NULL,
    signature_verified boolean NOT NULL,
    status varchar(20) NOT NULL,
    rejection_code varchar(80),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    UNIQUE (organization_id,id),
    FOREIGN KEY (organization_id,connection_id)
        REFERENCES integration_connections(organization_id,id),
    CHECK (signature_scheme~'^[a-z][a-z0-9_.-]{1,39}$'),
    CHECK (char_length(signature_key_version) BETWEEN 1 AND 80),
    CHECK (signature_digest~'^[0-9a-f]{64}$'),
    CHECK (nonce_digest~'^[0-9a-f]{64}$'),
    CHECK (idempotency_digest~'^[0-9a-f]{64}$'),
    CHECK (payload_digest~'^[0-9a-f]{64}$'),
    CHECK (content_type IN ('application/json','application/fhir+json')),
    CHECK (payload_byte_count BETWEEN 1 AND 1048576),
    CHECK (status IN ('accepted','duplicate','rejected')),
    CHECK ((status='accepted')=signature_verified),
    CHECK ((status='rejected')=(rejection_code IS NOT NULL)),
    CHECK (rejection_code IS NULL OR rejection_code~'^[a-z][a-z0-9_.-]{1,79}$')
);

CREATE UNIQUE INDEX inbound_webhook_receipts_nonce_uq
    ON inbound_webhook_receipts(organization_id,connection_id,nonce_digest)
    WHERE status='accepted';
CREATE UNIQUE INDEX inbound_webhook_receipts_idempotency_uq
    ON inbound_webhook_receipts(organization_id,connection_id,idempotency_digest)
    WHERE status='accepted';

CREATE TABLE integration_outbound_deliveries (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    connection_id uuid NOT NULL,
    source_outbox_event_id uuid NOT NULL,
    event_name varchar(180) NOT NULL,
    schema_version integer NOT NULL,
    payload_digest char(64) NOT NULL,
    predecessor_delivery_id uuid,
    status varchar(24) NOT NULL DEFAULT 'queued',
    attempt_count integer NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    delivered_at timestamptz,
    dead_lettered_at timestamptz,
    last_error_code varchar(80),
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    UNIQUE (organization_id,id),
    FOREIGN KEY (organization_id,connection_id)
        REFERENCES integration_connections(organization_id,id),
    FOREIGN KEY (organization_id,source_outbox_event_id)
        REFERENCES outbox_events(organization_id,id),
    FOREIGN KEY (organization_id,predecessor_delivery_id)
        REFERENCES integration_outbound_deliveries(organization_id,id),
    CHECK (event_name~'^[a-z][a-z0-9]*([.:-][a-z0-9]+)*$'),
    CHECK (schema_version BETWEEN 1 AND 1000000),
    CHECK (payload_digest~'^[0-9a-f]{64}$'),
    CHECK (status IN ('queued','retry','delivered','dead_lettered')),
    CHECK (attempt_count BETWEEN 0 AND 100),
    CHECK ((status='delivered')=(delivered_at IS NOT NULL)),
    CHECK ((status='dead_lettered')=(dead_lettered_at IS NOT NULL)),
    CHECK (NOT (delivered_at IS NOT NULL AND dead_lettered_at IS NOT NULL)),
    CHECK (last_error_code IS NULL OR last_error_code~'^[a-z][a-z0-9_.-]{1,79}$'),
    CHECK (lock_version>=0)
);

CREATE UNIQUE INDEX integration_outbound_initial_uq
    ON integration_outbound_deliveries(organization_id,connection_id,source_outbox_event_id)
    WHERE predecessor_delivery_id IS NULL;
CREATE UNIQUE INDEX integration_outbound_successor_uq
    ON integration_outbound_deliveries(organization_id,predecessor_delivery_id)
    WHERE predecessor_delivery_id IS NOT NULL;
CREATE INDEX integration_outbound_status_idx
    ON integration_outbound_deliveries(organization_id,status,next_attempt_at,id);

CREATE TABLE integration_delivery_attempts (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    delivery_id uuid NOT NULL,
    attempt_sequence integer NOT NULL,
    attempted_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    outcome varchar(24) NOT NULL,
    response_class varchar(40),
    provider_reference_digest char(64),
    error_code varchar(80),
    next_attempt_at timestamptz,
    created_by uuid NOT NULL,
    UNIQUE (organization_id,id),
    UNIQUE (organization_id,delivery_id,attempt_sequence),
    FOREIGN KEY (organization_id,delivery_id)
        REFERENCES integration_outbound_deliveries(organization_id,id),
    CHECK (attempt_sequence BETWEEN 1 AND 100),
    CHECK (outcome IN ('delivered','retry','dead_lettered')),
    CHECK (response_class IS NULL OR response_class~'^[a-z][a-z0-9_.-]{1,39}$'),
    CHECK (provider_reference_digest IS NULL OR provider_reference_digest~'^[0-9a-f]{64}$'),
    CHECK (error_code IS NULL OR error_code~'^[a-z][a-z0-9_.-]{1,79}$'),
    CHECK ((outcome='delivered')=(provider_reference_digest IS NOT NULL)),
    CHECK ((outcome IN ('retry','dead_lettered'))=(error_code IS NOT NULL)),
    CHECK ((outcome='retry')=(next_attempt_at IS NOT NULL))
);

CREATE TABLE integration_replay_requests (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    delivery_id uuid NOT NULL,
    delivery_revision bigint NOT NULL,
    authorization_reason varchar(500) NOT NULL,
    authorized_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    authorized_by uuid NOT NULL,
    expires_at timestamptz NOT NULL,
    status varchar(20) NOT NULL DEFAULT 'authorized',
    successor_delivery_id uuid,
    executed_at timestamptz,
    executed_by uuid,
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    UNIQUE (organization_id,id),
    UNIQUE (organization_id,successor_delivery_id),
    FOREIGN KEY (organization_id,delivery_id)
        REFERENCES integration_outbound_deliveries(organization_id,id),
    FOREIGN KEY (organization_id,successor_delivery_id)
        REFERENCES integration_outbound_deliveries(organization_id,id),
    CHECK (delivery_revision>=0),
    CHECK (char_length(authorization_reason) BETWEEN 10 AND 500),
    CHECK (expires_at>authorized_at AND expires_at<=authorized_at+interval '1 hour'),
    CHECK (status IN ('authorized','executed','expired')),
    CHECK ((status='executed')=(successor_delivery_id IS NOT NULL)),
    CHECK ((executed_at IS NULL)=(executed_by IS NULL)),
    CHECK ((status='executed')=(executed_at IS NOT NULL)),
    CHECK (lock_version>=0)
);

CREATE UNIQUE INDEX integration_replay_live_uq
    ON integration_replay_requests(organization_id,delivery_id)
    WHERE status='authorized';

CREATE TABLE fhir_exchange_records (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    connection_id uuid NOT NULL,
    mapping_version_id uuid NOT NULL,
    direction varchar(12) NOT NULL,
    resource_type varchar(40) NOT NULL,
    resource_identifier_digest char(64) NOT NULL,
    resource_digest char(64) NOT NULL,
    fhir_release varchar(40) NOT NULL,
    profile_package varchar(160) NOT NULL,
    profile_version varchar(80) NOT NULL,
    validation_status varchar(16) NOT NULL,
    validation_code varchar(80) NOT NULL,
    provenance_digest char(64) NOT NULL,
    recorded_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    UNIQUE (organization_id,id),
    FOREIGN KEY (organization_id,connection_id)
        REFERENCES integration_connections(organization_id,id),
    FOREIGN KEY (organization_id,mapping_version_id)
        REFERENCES integration_mapping_versions(organization_id,id),
    CHECK (direction IN ('import','export')),
    CHECK (resource_type IN (
        'Organization','Location','HealthcareService','Practitioner','PractitionerRole',
        'Patient','RelatedPerson','Consent','Schedule','Slot','Appointment','Encounter',
        'EpisodeOfCare','Condition','Observation','Questionnaire','QuestionnaireResponse',
        'ClinicalImpression','DocumentReference','DiagnosticReport','Media','CarePlan',
        'Goal','ServiceRequest','Task','Provenance','AuditEvent')),
    CHECK (resource_identifier_digest~'^[0-9a-f]{64}$'),
    CHECK (resource_digest~'^[0-9a-f]{64}$'),
    CHECK (fhir_release~'^[A-Za-z0-9][A-Za-z0-9._-]{0,39}$'),
    CHECK (profile_package~'^[A-Za-z0-9][A-Za-z0-9._:/-]{0,159}$'),
    CHECK (profile_version~'^[A-Za-z0-9][A-Za-z0-9._-]{0,79}$'),
    CHECK (validation_status IN ('accepted','rejected')),
    CHECK (validation_code~'^[a-z][a-z0-9_.-]{1,79}$'),
    CHECK (provenance_digest~'^[0-9a-f]{64}$')
);

CREATE TABLE integration_api_clients (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    client_code varchar(80) NOT NULL,
    display_name varchar(160) NOT NULL,
    public_identifier_digest char(64) NOT NULL,
    credential_reference_digest char(64) NOT NULL,
    scopes_digest char(64) NOT NULL,
    purpose_key varchar(80) NOT NULL,
    token_policy_version varchar(80) NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'registered',
    access_enabled boolean NOT NULL DEFAULT false,
    revoked_at timestamptz,
    revoked_by uuid,
    last_transition_reason varchar(500) NOT NULL,
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    UNIQUE (organization_id,id),
    UNIQUE (organization_id,client_code),
    UNIQUE (organization_id,public_identifier_digest),
    CHECK (client_code~'^[a-z][a-z0-9_.-]{2,79}$'),
    CHECK (char_length(display_name) BETWEEN 1 AND 160),
    CHECK (public_identifier_digest~'^[0-9a-f]{64}$'),
    CHECK (credential_reference_digest~'^[0-9a-f]{64}$'),
    CHECK (scopes_digest~'^[0-9a-f]{64}$'),
    CHECK (purpose_key~'^[a-z][a-z0-9_]{1,79}$'),
    CHECK (char_length(token_policy_version) BETWEEN 1 AND 80),
    CHECK (status IN ('registered','revoked')),
    CHECK (access_enabled=false),
    CHECK ((status='revoked')=(revoked_at IS NOT NULL)),
    CHECK ((revoked_at IS NULL)=(revoked_by IS NULL)),
    CHECK (char_length(last_transition_reason) BETWEEN 10 AND 500),
    CHECK (lock_version>=0)
);

DO $$
DECLARE table_name text;
BEGIN
    FOREACH table_name IN ARRAY ARRAY[
        'integration_mapping_versions','integration_connections','inbound_webhook_receipts',
        'integration_outbound_deliveries','integration_delivery_attempts',
        'integration_replay_requests','fhir_exchange_records','integration_api_clients'
    ] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',table_name);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',table_name);
        EXECUTE format(
            'CREATE POLICY %I ON %I USING (organization_id=nullif(current_setting(''app.current_organization_id'',true),'''')::uuid) WITH CHECK (organization_id=nullif(current_setting(''app.current_organization_id'',true),'''')::uuid)',
            table_name||'_tenant_policy',table_name);
    END LOOP;
END $$;

CREATE FUNCTION careos_validate_m13_mutable_write()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    configured_organization uuid:=nullif(current_setting('app.current_organization_id',true),'')::uuid;
    configured_actor uuid:=nullif(current_setting('app.current_actor_id',true),'')::uuid;
    configured_operation text:=nullif(current_setting('app.current_operation_key',true),'');
    allowed_operations text[];
BEGIN
    IF current_user<>'${applicationRole}' THEN RETURN NEW; END IF;
    allowed_operations:=CASE TG_TABLE_NAME
        WHEN 'integration_mapping_versions' THEN ARRAY[
            'integration.mapping.create','integration.mapping.activate','integration.mapping.retire']
        WHEN 'integration_connections' THEN ARRAY[
            'integration.connection.create','integration.connection.validate',
            'integration.connection.suspend','integration.connection.retire']
        WHEN 'integration_outbound_deliveries' THEN ARRAY[
            'integration.delivery.create','integration.delivery.attempt','integration.delivery.replay']
        WHEN 'integration_replay_requests' THEN ARRAY[
            'integration.replay.authorize','integration.delivery.replay']
        WHEN 'integration_api_clients' THEN ARRAY[
            'integration.api_client.create','integration.api_client.revoke']
        ELSE ARRAY[]::text[]
    END;
    IF NEW.organization_id IS DISTINCT FROM configured_organization
       OR configured_actor IS NULL OR configured_operation IS NULL
       OR NOT configured_operation=ANY(allowed_operations)
       OR NOT EXISTS (
            SELECT 1 FROM authorization_operations operation
            JOIN authorization_registry_releases release
              ON release.registry_version=operation.registry_version AND release.status='active'
            WHERE operation.operation_key=configured_operation
              AND operation.registry_version='m13-standing-direction-v1'
              AND operation.status='active') THEN
        RAISE EXCEPTION 'invalid Module 13 operation-bound tenant write context' USING ERRCODE='42501';
    END IF;
    IF TG_OP='INSERT' THEN
        IF NEW.created_by IS DISTINCT FROM configured_actor
           OR NEW.updated_by IS DISTINCT FROM configured_actor OR NEW.lock_version<>0 THEN
            RAISE EXCEPTION 'invalid Module 13 creation evidence' USING ERRCODE='23514';
        END IF;
    ELSE
        IF NEW.id IS DISTINCT FROM OLD.id OR NEW.organization_id IS DISTINCT FROM OLD.organization_id
           OR NEW.created_at IS DISTINCT FROM OLD.created_at OR NEW.created_by IS DISTINCT FROM OLD.created_by
           OR NEW.updated_by IS DISTINCT FROM configured_actor OR NEW.lock_version<>OLD.lock_version+1
           OR NEW.updated_at<OLD.updated_at OR NEW.updated_at>clock_timestamp()+interval '5 seconds' THEN
            RAISE EXCEPTION 'invalid Module 13 revision evidence' USING ERRCODE='23514';
        END IF;
    END IF;
    RETURN NEW;
END $$;

DO $$
DECLARE table_name text;
BEGIN
    FOREACH table_name IN ARRAY ARRAY[
        'integration_mapping_versions','integration_connections',
        'integration_outbound_deliveries','integration_replay_requests','integration_api_clients'
    ] LOOP
        EXECUTE format(
            'CREATE TRIGGER %I BEFORE INSERT OR UPDATE ON %I FOR EACH ROW EXECUTE FUNCTION careos_validate_m13_mutable_write()',
            table_name||'_write_guard',table_name);
    END LOOP;
END $$;

CREATE FUNCTION careos_validate_m13_append_write()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    configured_organization uuid:=nullif(current_setting('app.current_organization_id',true),'')::uuid;
    configured_actor uuid:=nullif(current_setting('app.current_actor_id',true),'')::uuid;
    configured_operation text:=nullif(current_setting('app.current_operation_key',true),'');
    required_operation text:=CASE TG_TABLE_NAME
        WHEN 'inbound_webhook_receipts' THEN 'integration.webhook.receive'
        WHEN 'integration_delivery_attempts' THEN 'integration.delivery.attempt'
        WHEN 'fhir_exchange_records' THEN 'integration.fhir.exchange.record'
        ELSE NULL
    END;
BEGIN
    IF current_user<>'${applicationRole}' THEN RETURN NEW; END IF;
    IF NEW.organization_id IS DISTINCT FROM configured_organization
       OR NEW.created_by IS DISTINCT FROM configured_actor
       OR configured_operation IS DISTINCT FROM required_operation
       OR NOT EXISTS (
            SELECT 1 FROM authorization_operations operation
            JOIN authorization_registry_releases release
              ON release.registry_version=operation.registry_version AND release.status='active'
            WHERE operation.operation_key=configured_operation
              AND operation.registry_version='m13-standing-direction-v1'
              AND operation.status='active') THEN
        RAISE EXCEPTION 'invalid Module 13 append-only tenant write context' USING ERRCODE='42501';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER inbound_webhook_receipts_write_guard
    BEFORE INSERT ON inbound_webhook_receipts FOR EACH ROW
    EXECUTE FUNCTION careos_validate_m13_append_write();
CREATE TRIGGER integration_delivery_attempts_write_guard
    BEFORE INSERT ON integration_delivery_attempts FOR EACH ROW
    EXECUTE FUNCTION careos_validate_m13_append_write();
CREATE TRIGGER fhir_exchange_records_write_guard
    BEFORE INSERT ON fhir_exchange_records FOR EACH ROW
    EXECUTE FUNCTION careos_validate_m13_append_write();

CREATE FUNCTION careos_reject_m13_append_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Module 13 integration evidence is append-only' USING ERRCODE='42501';
END $$;

DO $$
DECLARE table_name text;
BEGIN
    FOREACH table_name IN ARRAY ARRAY[
        'inbound_webhook_receipts','integration_delivery_attempts','fhir_exchange_records'
    ] LOOP
        EXECUTE format(
            'CREATE TRIGGER %I BEFORE UPDATE OR DELETE ON %I FOR EACH ROW EXECUTE FUNCTION careos_reject_m13_append_mutation()',
            table_name||'_immutable',table_name);
    END LOOP;
END $$;

CREATE FUNCTION careos_reject_m13_delete()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Module 13 integration records cannot be deleted' USING ERRCODE='42501';
END $$;

DO $$
DECLARE table_name text;
BEGIN
    FOREACH table_name IN ARRAY ARRAY[
        'integration_mapping_versions','integration_connections','integration_outbound_deliveries',
        'integration_replay_requests','integration_api_clients'
    ] LOOP
        EXECUTE format(
            'CREATE TRIGGER %I BEFORE DELETE ON %I FOR EACH ROW EXECUTE FUNCTION careos_reject_m13_delete()',
            table_name||'_no_delete',table_name);
    END LOOP;
END $$;

REVOKE ALL ON integration_mapping_versions,integration_connections,inbound_webhook_receipts,
    integration_outbound_deliveries,integration_delivery_attempts,integration_replay_requests,
    fhir_exchange_records,integration_api_clients FROM PUBLIC;
GRANT SELECT,INSERT,UPDATE ON integration_mapping_versions,integration_connections,
    integration_outbound_deliveries,integration_replay_requests,integration_api_clients
    TO "${applicationRole}";
GRANT SELECT,INSERT ON inbound_webhook_receipts,integration_delivery_attempts,
    fhir_exchange_records TO "${applicationRole}";

COMMENT ON TABLE integration_connections IS
    'Secret-free versioned partner definitions; validated does not mean transport-enabled or production-approved.';
COMMENT ON TABLE inbound_webhook_receipts IS
    'Digest-only signature, timestamp, nonce and idempotency evidence; raw webhook content is prohibited.';
COMMENT ON TABLE fhir_exchange_records IS
    'Payload-free profile-validation and provenance evidence; FHIR remains an external representation.';
