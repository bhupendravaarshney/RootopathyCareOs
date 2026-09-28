CREATE TEMP TABLE m13_event_seed (
    event_name varchar(180) PRIMARY KEY,
    subject_type varchar(120) NOT NULL,
    operation_key varchar(160) NOT NULL
) ON COMMIT DROP;

INSERT INTO m13_event_seed VALUES
 ('integration.connection.created','integration_connection','integration.connection.create'),
 ('integration.connection.validated','integration_connection','integration.connection.validate'),
 ('integration.connection.suspended','integration_connection','integration.connection.suspend'),
 ('integration.connection.retired','integration_connection','integration.connection.retire'),
 ('integration.mapping.created','integration_mapping','integration.mapping.create'),
 ('integration.mapping.activated','integration_mapping','integration.mapping.activate'),
 ('integration.mapping.retired','integration_mapping','integration.mapping.retire'),
 ('integration.api-client.registered','integration_api_client','integration.api_client.create'),
 ('integration.api-client.revoked','integration_api_client','integration.api_client.revoke'),
 ('integration.replay.authorized','integration_replay','integration.replay.authorize'),
 ('integration.webhook.received','integration_webhook_receipt','integration.webhook.receive'),
 ('integration.delivery.created','integration_delivery','integration.delivery.create'),
 ('integration.delivery.attempted','integration_delivery','integration.delivery.attempt'),
 ('integration.delivery.replayed','integration_replay','integration.delivery.replay'),
 ('integration.fhir-exchange.recorded','fhir_exchange','integration.fhir.exchange.record');

INSERT INTO audit_event_definitions
    (event_name,schema_version,display_name,description,subject_type,reason_required,
     required_payload_keys,allowed_payload_keys,payload_schema,status,registry_version)
SELECT event_name,1,replace(initcap(replace(event_name,'.',' ')),'_',' '),
       'CareOS Module 13 governed integration evidence.',subject_type,true,
       ARRAY['artifactId','artifactType','fromState','toState','revision'],
       ARRAY['artifactId','artifactType','fromState','toState','revision'],
       '{"type":"object","additionalProperties":false}'::jsonb,
       'active','m13-standing-direction-v1'
FROM m13_event_seed;

INSERT INTO authorization_operation_events
    (operation_key,event_kind,event_name,schema_version,status,registry_version)
SELECT operation_key,'audit',event_name,1,'active','m13-standing-direction-v1'
FROM m13_event_seed;

INSERT INTO outbox_event_definitions
    (event_name,schema_version,description,aggregate_type,required_payload_keys,
     allowed_payload_keys,payload_schema,status,registry_version)
VALUES
    ('m13.integration-artifact-changed.v1',1,
     'A governed integration definition or payload-free exchange artifact changed.',
     'integration_artifact',
     ARRAY['artifactId','artifactType','fromState','toState','revision'],
     ARRAY['artifactId','artifactType','fromState','toState','revision'],
     '{"type":"object","additionalProperties":false}'::jsonb,
     'active','m13-standing-direction-v1');

INSERT INTO authorization_operation_events
    (operation_key,event_kind,event_name,schema_version,status,registry_version)
SELECT permission_key,'outbox','m13.integration-artifact-changed.v1',1,
       'active','m13-standing-direction-v1'
FROM authorization_permissions
WHERE registry_version='m13-standing-direction-v1'
  AND permission_key<>'integration.read';

CREATE UNIQUE INDEX integration_mapping_active_uq
    ON integration_mapping_versions(organization_id,mapping_code)
    WHERE status='active';

CREATE FUNCTION careos_guard_m13_mapping()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    operation text:=nullif(current_setting('app.current_operation_key',true),'');
    actor uuid:=nullif(current_setting('app.current_actor_id',true),'')::uuid;
BEGIN
    IF current_user<>'${applicationRole}' THEN RETURN NEW; END IF;
    IF TG_OP='INSERT' THEN
        IF operation<>'integration.mapping.create' OR NEW.status<>'draft'
           OR NEW.activated_at IS NOT NULL OR NEW.retired_at IS NOT NULL THEN
            RAISE EXCEPTION 'integration mappings begin as draft versions' USING ERRCODE='23514';
        END IF;
        RETURN NEW;
    END IF;
    IF ROW(NEW.mapping_code,NEW.mapping_kind,NEW.version_number,NEW.source_version,
           NEW.target_version,NEW.fhir_release,NEW.profile_package,NEW.profile_version,
           NEW.terminology_version,NEW.content_digest)
       IS DISTINCT FROM
       ROW(OLD.mapping_code,OLD.mapping_kind,OLD.version_number,OLD.source_version,
           OLD.target_version,OLD.fhir_release,OLD.profile_package,OLD.profile_version,
           OLD.terminology_version,OLD.content_digest) THEN
        RAISE EXCEPTION 'integration mapping content is immutable' USING ERRCODE='23514';
    END IF;
    IF operation='integration.mapping.activate' THEN
        IF OLD.status<>'draft' OR NEW.status<>'active'
           OR NEW.activated_by IS DISTINCT FROM actor OR NEW.activated_at IS NULL
           OR NEW.activated_at>clock_timestamp()+interval '5 seconds'
           OR NEW.retired_at IS NOT NULL THEN
            RAISE EXCEPTION 'only an exact draft mapping may activate' USING ERRCODE='23514';
        END IF;
    ELSIF operation='integration.mapping.retire' THEN
        IF OLD.status<>'active' OR NEW.status<>'retired'
           OR NEW.activated_at IS DISTINCT FROM OLD.activated_at
           OR NEW.activated_by IS DISTINCT FROM OLD.activated_by
           OR NEW.retired_by IS DISTINCT FROM actor OR NEW.retired_at IS NULL
           OR NEW.retired_at>clock_timestamp()+interval '5 seconds'
           OR EXISTS (
                SELECT 1 FROM integration_connections connection
                 WHERE connection.organization_id=NEW.organization_id
                   AND connection.mapping_version_id=NEW.id
                   AND connection.status IN ('validated','suspended')) THEN
            RAISE EXCEPTION 'only an active mapping may retire' USING ERRCODE='23514';
        END IF;
    ELSE
        RAISE EXCEPTION 'invalid integration mapping lifecycle operation' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER integration_mapping_versions_lifecycle_guard
    BEFORE INSERT OR UPDATE ON integration_mapping_versions
    FOR EACH ROW EXECUTE FUNCTION careos_guard_m13_mapping();

CREATE FUNCTION careos_guard_m13_connection()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    operation text:=nullif(current_setting('app.current_operation_key',true),'');
    actor uuid:=nullif(current_setting('app.current_actor_id',true),'')::uuid;
    reason text:=nullif(current_setting('app.current_authorization_reason',true),'');
    mapping_status text;
    mapping_kind text;
BEGIN
    IF current_user<>'${applicationRole}' THEN RETURN NEW; END IF;
    IF TG_OP='INSERT' THEN
        IF operation<>'integration.connection.create' OR NEW.status<>'draft'
           OR NEW.validated_at IS NOT NULL
           OR NEW.last_transition_by IS DISTINCT FROM actor
           OR NEW.last_transition_reason IS DISTINCT FROM reason THEN
            RAISE EXCEPTION 'integration connections begin as attributed drafts' USING ERRCODE='23514';
        END IF;
        RETURN NEW;
    END IF;
    IF ROW(NEW.connection_code,NEW.display_name,NEW.provider_kind,NEW.contract_version,
           NEW.endpoint_reference,NEW.security_profile_key,
           NEW.credential_reference_digest,NEW.mapping_version_id)
       IS DISTINCT FROM
       ROW(OLD.connection_code,OLD.display_name,OLD.provider_kind,OLD.contract_version,
           OLD.endpoint_reference,OLD.security_profile_key,
           OLD.credential_reference_digest,OLD.mapping_version_id)
       OR NEW.last_transition_by IS DISTINCT FROM actor
       OR NEW.last_transition_reason IS DISTINCT FROM reason
       OR NEW.last_transition_at<=OLD.last_transition_at
       OR NEW.last_transition_at>clock_timestamp()+interval '5 seconds' THEN
        RAISE EXCEPTION 'integration connection identity and transition evidence are immutable' USING ERRCODE='23514';
    END IF;
    IF operation='integration.connection.validate' THEN
        IF OLD.status NOT IN ('draft','suspended') OR NEW.status<>'validated'
           OR NEW.validated_by IS DISTINCT FROM actor OR NEW.validated_at IS NULL
           OR NEW.validated_at>clock_timestamp()+interval '5 seconds' THEN
            RAISE EXCEPTION 'only a draft or suspended connection may validate' USING ERRCODE='23514';
        END IF;
        IF NEW.provider_kind IN ('fhir','lab_imaging') THEN
            SELECT mapping.status,mapping.mapping_kind INTO mapping_status,mapping_kind
              FROM integration_mapping_versions mapping
             WHERE mapping.organization_id=NEW.organization_id AND mapping.id=NEW.mapping_version_id;
            IF mapping_status IS DISTINCT FROM 'active'
               OR mapping_kind NOT IN ('fhir','diagnostic') THEN
                RAISE EXCEPTION 'FHIR and diagnostic connections require an active exact mapping' USING ERRCODE='23514';
            END IF;
        END IF;
    ELSIF operation='integration.connection.suspend' THEN
        IF OLD.status<>'validated' OR NEW.status<>'suspended'
           OR NEW.validated_at IS DISTINCT FROM OLD.validated_at
           OR NEW.validated_by IS DISTINCT FROM OLD.validated_by THEN
            RAISE EXCEPTION 'only a validated connection may suspend' USING ERRCODE='23514';
        END IF;
    ELSIF operation='integration.connection.retire' THEN
        IF OLD.status NOT IN ('validated','suspended') OR NEW.status<>'retired'
           OR NEW.validated_at IS DISTINCT FROM OLD.validated_at
           OR NEW.validated_by IS DISTINCT FROM OLD.validated_by THEN
            RAISE EXCEPTION 'only a validated or suspended connection may retire' USING ERRCODE='23514';
        END IF;
    ELSE
        RAISE EXCEPTION 'invalid integration connection lifecycle operation' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER integration_connections_lifecycle_guard
    BEFORE INSERT OR UPDATE ON integration_connections
    FOR EACH ROW EXECUTE FUNCTION careos_guard_m13_connection();

CREATE FUNCTION careos_guard_m13_webhook_receipt()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    connection_status text;
    connection_kind text;
BEGIN
    IF current_user<>'${applicationRole}' THEN RETURN NEW; END IF;
    SELECT status,provider_kind INTO connection_status,connection_kind
      FROM integration_connections
     WHERE organization_id=NEW.organization_id AND id=NEW.connection_id;
    IF connection_status IS DISTINCT FROM 'validated'
       OR connection_kind NOT IN ('whatsapp','payment','calendar','lab_imaging','webhook')
       OR NEW.received_at<clock_timestamp()-interval '5 seconds'
       OR NEW.received_at>clock_timestamp()+interval '5 seconds' THEN
        RAISE EXCEPTION 'webhook receipt requires an exact validated connection' USING ERRCODE='23514';
    END IF;
    IF NEW.status='accepted' AND abs(extract(epoch FROM NEW.received_at-NEW.signed_at))>300 THEN
        RAISE EXCEPTION 'accepted webhook timestamp is outside the replay window' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER inbound_webhook_receipts_integrity_guard
    BEFORE INSERT ON inbound_webhook_receipts
    FOR EACH ROW EXECUTE FUNCTION careos_guard_m13_webhook_receipt();

CREATE FUNCTION careos_guard_m13_delivery()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    operation text:=nullif(current_setting('app.current_operation_key',true),'');
    connection_status text;
    source_name text;
    source_version integer;
    source_digest text;
    predecessor_status text;
    latest_outcome text;
    latest_next timestamptz;
BEGIN
    IF current_user<>'${applicationRole}' THEN RETURN NEW; END IF;
    IF TG_OP='INSERT' THEN
        SELECT status INTO connection_status FROM integration_connections
         WHERE organization_id=NEW.organization_id AND id=NEW.connection_id;
        SELECT event_name,schema_version,
               encode(sha256(convert_to(payload::text,'UTF8')),'hex')
          INTO source_name,source_version,source_digest
          FROM outbox_events
         WHERE organization_id=NEW.organization_id AND id=NEW.source_outbox_event_id;
        IF connection_status IS DISTINCT FROM 'validated'
           OR NEW.event_name IS DISTINCT FROM source_name
           OR NEW.schema_version IS DISTINCT FROM source_version
           OR NEW.payload_digest IS DISTINCT FROM source_digest
           OR NEW.status<>'queued' OR NEW.attempt_count<>0
           OR NEW.delivered_at IS NOT NULL OR NEW.dead_lettered_at IS NOT NULL
           OR NEW.last_error_code IS NOT NULL THEN
            RAISE EXCEPTION 'outbound delivery must bind one exact outbox event and validated destination' USING ERRCODE='23514';
        END IF;
        IF operation='integration.delivery.create' THEN
            IF NEW.predecessor_delivery_id IS NOT NULL THEN
                RAISE EXCEPTION 'an initial outbound delivery cannot have a predecessor' USING ERRCODE='23514';
            END IF;
        ELSIF operation='integration.delivery.replay' THEN
            SELECT status INTO predecessor_status FROM integration_outbound_deliveries
             WHERE organization_id=NEW.organization_id AND id=NEW.predecessor_delivery_id;
            IF predecessor_status IS DISTINCT FROM 'dead_lettered'
               OR NOT EXISTS (
                    SELECT 1 FROM integration_replay_requests request
                     WHERE request.organization_id=NEW.organization_id
                       AND request.delivery_id=NEW.predecessor_delivery_id
                       AND request.status='authorized'
                       AND request.expires_at>clock_timestamp()) THEN
                RAISE EXCEPTION 'replay delivery requires one live exact dead-letter authorization' USING ERRCODE='23514';
            END IF;
        ELSE
            RAISE EXCEPTION 'invalid outbound delivery creation operation' USING ERRCODE='23514';
        END IF;
        RETURN NEW;
    END IF;
    IF operation<>'integration.delivery.attempt'
       OR ROW(NEW.connection_id,NEW.source_outbox_event_id,NEW.event_name,NEW.schema_version,
              NEW.payload_digest,NEW.predecessor_delivery_id)
          IS DISTINCT FROM
          ROW(OLD.connection_id,OLD.source_outbox_event_id,OLD.event_name,OLD.schema_version,
              OLD.payload_digest,OLD.predecessor_delivery_id)
       OR OLD.status NOT IN ('queued','retry')
       OR NEW.attempt_count<>OLD.attempt_count+1
       OR NEW.attempt_count<>(SELECT count(*) FROM integration_delivery_attempts attempt
                              WHERE attempt.organization_id=NEW.organization_id
                                AND attempt.delivery_id=NEW.id) THEN
        RAISE EXCEPTION 'outbound delivery attempt evidence is invalid' USING ERRCODE='23514';
    END IF;
    SELECT outcome,next_attempt_at INTO latest_outcome,latest_next
      FROM integration_delivery_attempts
     WHERE organization_id=NEW.organization_id AND delivery_id=NEW.id
     ORDER BY attempt_sequence DESC LIMIT 1;
    IF (latest_outcome='delivered' AND
        (NEW.status<>'delivered' OR NEW.delivered_at IS NULL OR NEW.dead_lettered_at IS NOT NULL))
       OR (latest_outcome='retry' AND
           (NEW.status<>'retry' OR NEW.next_attempt_at IS DISTINCT FROM latest_next
            OR NEW.delivered_at IS NOT NULL OR NEW.dead_lettered_at IS NOT NULL))
       OR (latest_outcome='dead_lettered' AND
           (NEW.status<>'dead_lettered' OR NEW.dead_lettered_at IS NULL
            OR NEW.delivered_at IS NOT NULL)) THEN
        RAISE EXCEPTION 'delivery status must match its latest append-only attempt' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER integration_outbound_deliveries_lifecycle_guard
    BEFORE INSERT OR UPDATE ON integration_outbound_deliveries
    FOR EACH ROW EXECUTE FUNCTION careos_guard_m13_delivery();

CREATE FUNCTION careos_guard_m13_delivery_attempt()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    delivery_status text;
    delivery_attempts integer;
BEGIN
    IF current_user<>'${applicationRole}' THEN RETURN NEW; END IF;
    SELECT status,attempt_count INTO delivery_status,delivery_attempts
      FROM integration_outbound_deliveries
     WHERE organization_id=NEW.organization_id AND id=NEW.delivery_id
     FOR UPDATE;
    IF delivery_status NOT IN ('queued','retry')
       OR NEW.attempt_sequence<>delivery_attempts+1
       OR NEW.attempted_at<clock_timestamp()-interval '5 seconds'
       OR NEW.attempted_at>clock_timestamp()+interval '5 seconds'
       OR (NEW.outcome='retry' AND NEW.next_attempt_at<=NEW.attempted_at) THEN
        RAISE EXCEPTION 'delivery attempt does not extend the exact live delivery' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER integration_delivery_attempts_integrity_guard
    BEFORE INSERT ON integration_delivery_attempts
    FOR EACH ROW EXECUTE FUNCTION careos_guard_m13_delivery_attempt();

CREATE FUNCTION careos_guard_m13_replay_request()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    operation text:=nullif(current_setting('app.current_operation_key',true),'');
    actor uuid:=nullif(current_setting('app.current_actor_id',true),'')::uuid;
    reason text:=nullif(current_setting('app.current_authorization_reason',true),'');
    delivery_status text;
    delivery_revision bigint;
BEGIN
    IF current_user<>'${applicationRole}' THEN RETURN NEW; END IF;
    IF TG_OP='INSERT' THEN
        SELECT status,lock_version INTO delivery_status,delivery_revision
          FROM integration_outbound_deliveries
         WHERE organization_id=NEW.organization_id AND id=NEW.delivery_id;
        IF operation<>'integration.replay.authorize'
           OR delivery_status IS DISTINCT FROM 'dead_lettered'
           OR NEW.delivery_revision IS DISTINCT FROM delivery_revision
           OR NEW.authorization_reason IS DISTINCT FROM reason
           OR NEW.authorized_by IS DISTINCT FROM actor
           OR NEW.status<>'authorized' OR NEW.successor_delivery_id IS NOT NULL THEN
            RAISE EXCEPTION 'replay authorization must bind exact terminal delivery evidence' USING ERRCODE='23514';
        END IF;
        RETURN NEW;
    END IF;
    IF operation<>'integration.delivery.replay' OR OLD.status<>'authorized'
       OR OLD.expires_at<=clock_timestamp() OR NEW.status<>'executed'
       OR ROW(NEW.delivery_id,NEW.delivery_revision,NEW.authorization_reason,
              NEW.authorized_at,NEW.authorized_by,NEW.expires_at)
          IS DISTINCT FROM
          ROW(OLD.delivery_id,OLD.delivery_revision,OLD.authorization_reason,
              OLD.authorized_at,OLD.authorized_by,OLD.expires_at)
       OR NEW.executed_by IS DISTINCT FROM actor OR NEW.executed_at IS NULL
       OR NOT EXISTS (
            SELECT 1 FROM integration_outbound_deliveries successor
             WHERE successor.organization_id=NEW.organization_id
               AND successor.id=NEW.successor_delivery_id
               AND successor.predecessor_delivery_id=NEW.delivery_id) THEN
        RAISE EXCEPTION 'replay execution requires one exact successor delivery' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER integration_replay_requests_lifecycle_guard
    BEFORE INSERT OR UPDATE ON integration_replay_requests
    FOR EACH ROW EXECUTE FUNCTION careos_guard_m13_replay_request();

CREATE FUNCTION careos_guard_m13_fhir_exchange()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    connection_status text;
    connection_kind text;
    connection_mapping uuid;
    mapping_status text;
    mapping_kind text;
    mapping_release text;
    mapping_package text;
    mapping_profile_version text;
BEGIN
    IF current_user<>'${applicationRole}' THEN RETURN NEW; END IF;
    SELECT status,provider_kind,mapping_version_id
      INTO connection_status,connection_kind,connection_mapping
      FROM integration_connections
     WHERE organization_id=NEW.organization_id AND id=NEW.connection_id;
    SELECT mapping.status,mapping.mapping_kind,mapping.fhir_release,
           mapping.profile_package,mapping.profile_version
      INTO mapping_status,mapping_kind,mapping_release,mapping_package,mapping_profile_version
      FROM integration_mapping_versions mapping
     WHERE mapping.organization_id=NEW.organization_id AND mapping.id=NEW.mapping_version_id;
    IF connection_status IS DISTINCT FROM 'validated'
       OR connection_kind NOT IN ('fhir','lab_imaging')
       OR connection_mapping IS DISTINCT FROM NEW.mapping_version_id
       OR mapping_status IS DISTINCT FROM 'active' OR mapping_kind<>'fhir'
       OR NEW.fhir_release IS DISTINCT FROM mapping_release
       OR NEW.profile_package IS DISTINCT FROM mapping_package
       OR NEW.profile_version IS DISTINCT FROM mapping_profile_version THEN
        RAISE EXCEPTION 'FHIR exchange must bind an exact active profile and validated connection' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER fhir_exchange_records_profile_guard
    BEFORE INSERT ON fhir_exchange_records
    FOR EACH ROW EXECUTE FUNCTION careos_guard_m13_fhir_exchange();

CREATE FUNCTION careos_guard_m13_api_client()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    operation text:=nullif(current_setting('app.current_operation_key',true),'');
    actor uuid:=nullif(current_setting('app.current_actor_id',true),'')::uuid;
    reason text:=nullif(current_setting('app.current_authorization_reason',true),'');
BEGIN
    IF current_user<>'${applicationRole}' THEN RETURN NEW; END IF;
    IF TG_OP='INSERT' THEN
        IF operation<>'integration.api_client.create' OR NEW.status<>'registered'
           OR NEW.access_enabled OR NEW.revoked_at IS NOT NULL
           OR NEW.last_transition_reason IS DISTINCT FROM reason THEN
            RAISE EXCEPTION 'API client registration must remain secret-free and disabled' USING ERRCODE='23514';
        END IF;
        RETURN NEW;
    END IF;
    IF operation<>'integration.api_client.revoke' OR OLD.status<>'registered'
       OR NEW.status<>'revoked' OR NEW.access_enabled
       OR ROW(NEW.client_code,NEW.display_name,NEW.public_identifier_digest,
              NEW.credential_reference_digest,NEW.scopes_digest,NEW.purpose_key,
              NEW.token_policy_version)
          IS DISTINCT FROM
          ROW(OLD.client_code,OLD.display_name,OLD.public_identifier_digest,
              OLD.credential_reference_digest,OLD.scopes_digest,OLD.purpose_key,
              OLD.token_policy_version)
       OR NEW.revoked_by IS DISTINCT FROM actor OR NEW.revoked_at IS NULL
       OR NEW.last_transition_reason IS DISTINCT FROM reason THEN
        RAISE EXCEPTION 'API client revocation must preserve exact registration evidence' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER integration_api_clients_lifecycle_guard
    BEFORE INSERT OR UPDATE ON integration_api_clients
    FOR EACH ROW EXECUTE FUNCTION careos_guard_m13_api_client();
