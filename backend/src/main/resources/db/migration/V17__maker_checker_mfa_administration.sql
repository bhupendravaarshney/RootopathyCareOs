CREATE TABLE authorization_approval_workflows (
    target_operation_key varchar(160) PRIMARY KEY
        REFERENCES authorization_operations(operation_key),
    request_operation_key varchar(160) NOT NULL UNIQUE
        REFERENCES authorization_operations(operation_key),
    decision_operation_key varchar(160) NOT NULL UNIQUE
        REFERENCES authorization_operations(operation_key),
    subject_type varchar(120) NOT NULL,
    maximum_approval_ttl_seconds integer NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'active',
    registry_version varchar(80) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CHECK (target_operation_key <> request_operation_key),
    CHECK (target_operation_key <> decision_operation_key),
    CHECK (request_operation_key <> decision_operation_key),
    CHECK (subject_type ~ '^[a-z][a-z0-9]*([._:-][a-z0-9]+)*$'),
    CHECK (maximum_approval_ttl_seconds BETWEEN 60 AND 3600),
    CHECK (status IN ('active', 'reference', 'retired')),
    CHECK (registry_version ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,79}$'),
    CHECK (isfinite(created_at))
);

CREATE TABLE authorization_approval_requests (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    operation_key varchar(160) NOT NULL REFERENCES authorization_operations(operation_key),
    subject_type varchar(120) NOT NULL,
    subject_id uuid NOT NULL,
    requested_by_user_id uuid NOT NULL REFERENCES users(id),
    request_reason text NOT NULL,
    request_correlation_id varchar(128) NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'pending',
    decided_by_user_id uuid REFERENCES users(id),
    decision_reason text,
    decision_correlation_id varchar(128),
    decided_at timestamptz,
    consumed_by_user_id uuid REFERENCES users(id),
    consumed_at timestamptz,
    consumption_correlation_id varchar(128),
    consumed_idempotency_key varchar(128),
    expires_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    lock_version bigint NOT NULL DEFAULT 0,
    UNIQUE (organization_id, id),
    CHECK (subject_type ~ '^[a-z][a-z0-9]*([._:-][a-z0-9]+)*$'),
    CHECK (char_length(btrim(request_reason)) BETWEEN 1 AND 2000),
    CHECK (request_correlation_id ~ '^[A-Za-z0-9._:-]{1,128}$'),
    CHECK (decision_reason IS NULL
        OR char_length(btrim(decision_reason)) BETWEEN 1 AND 2000),
    CHECK (decision_correlation_id IS NULL
        OR decision_correlation_id ~ '^[A-Za-z0-9._:-]{1,128}$'),
    CHECK (consumption_correlation_id IS NULL
        OR consumption_correlation_id ~ '^[A-Za-z0-9._:-]{1,128}$'),
    CHECK (consumed_idempotency_key IS NULL
        OR consumed_idempotency_key ~ '^[A-Za-z0-9._:-]{16,128}$'),
    CHECK (status IN ('pending', 'approved', 'rejected', 'expired', 'consumed')),
    CHECK (expires_at > created_at),
    CHECK (isfinite(expires_at)),
    CHECK (isfinite(created_at)),
    CHECK (isfinite(updated_at)),
    CHECK (decided_at IS NULL OR isfinite(decided_at)),
    CHECK (consumed_at IS NULL OR isfinite(consumed_at)),
    CHECK (lock_version >= 0),
    CHECK (
        (status = 'pending'
            AND decided_by_user_id IS NULL AND decision_reason IS NULL
            AND decision_correlation_id IS NULL AND decided_at IS NULL
            AND consumed_by_user_id IS NULL AND consumed_at IS NULL
            AND consumption_correlation_id IS NULL AND consumed_idempotency_key IS NULL)
        OR
        (status IN ('approved', 'rejected')
            AND decided_by_user_id IS NOT NULL AND decision_reason IS NOT NULL
            AND decision_correlation_id IS NOT NULL AND decided_at IS NOT NULL
            AND consumed_by_user_id IS NULL AND consumed_at IS NULL
            AND consumption_correlation_id IS NULL AND consumed_idempotency_key IS NULL)
        OR
        (status = 'expired'
            AND consumed_by_user_id IS NULL AND consumed_at IS NULL
            AND consumption_correlation_id IS NULL AND consumed_idempotency_key IS NULL
            AND (
                (decided_by_user_id IS NULL AND decision_reason IS NULL
                    AND decision_correlation_id IS NULL AND decided_at IS NULL)
                OR
                (decided_by_user_id IS NOT NULL AND decision_reason IS NOT NULL
                    AND decision_correlation_id IS NOT NULL AND decided_at IS NOT NULL)
            ))
        OR
        (status = 'consumed'
            AND decided_by_user_id IS NOT NULL AND decision_reason IS NOT NULL
            AND decision_correlation_id IS NOT NULL AND decided_at IS NOT NULL
            AND consumed_by_user_id IS NOT NULL AND consumed_at IS NOT NULL
            AND consumption_correlation_id IS NOT NULL
            AND consumed_idempotency_key IS NOT NULL)
    ),
    CHECK (decided_by_user_id IS NULL OR decided_by_user_id <> requested_by_user_id),
    CHECK (decided_by_user_id IS NULL OR decided_by_user_id <> subject_id),
    CHECK (consumed_by_user_id IS NULL OR consumed_by_user_id = requested_by_user_id)
);

CREATE UNIQUE INDEX authorization_approval_one_open_subject_uq
    ON authorization_approval_requests
        (organization_id, operation_key, subject_type, subject_id)
    WHERE status IN ('pending', 'approved');

CREATE INDEX authorization_approval_actor_status_idx
    ON authorization_approval_requests
        (organization_id, requested_by_user_id, status, expires_at);

ALTER TABLE authorization_approval_requests ENABLE ROW LEVEL SECURITY;
ALTER TABLE authorization_approval_requests FORCE ROW LEVEL SECURITY;
CREATE POLICY authorization_approval_requests_tenant_policy
    ON authorization_approval_requests
    USING (organization_id = nullif(current_setting('app.current_organization_id', true), '')::uuid)
    WITH CHECK (
        organization_id = nullif(current_setting('app.current_organization_id', true), '')::uuid);

INSERT INTO authorization_operations
    (operation_key, permission_key, display_name, description, mutation, denial_mode,
     reason_required, recent_authentication_required,
     recent_authentication_max_age_seconds, maximum_future_skew_seconds,
     maker_checker_required, status, registry_version)
VALUES
    ('identity.mfa.admin-reset.request', 'identity.mfa.admin-reset',
     'Request administrative MFA reset',
     'Request an independently approved MFA reset for another active organization member.',
     true, 'explicit', true, true, 300, 5, false,
     'reference', 'careos-phase0-reference-v1'),
    ('identity.mfa.admin-reset.approve', 'identity.mfa.admin-reset',
     'Approve administrative MFA reset',
     'Approve another authorized account request without executing it.',
     true, 'explicit', true, true, 300, 5, false,
     'reference', 'careos-phase0-reference-v1');

INSERT INTO authorization_approval_workflows
    (target_operation_key, request_operation_key, decision_operation_key,
     subject_type, maximum_approval_ttl_seconds, status, registry_version)
VALUES
    ('identity.mfa.admin-reset', 'identity.mfa.admin-reset.request',
     'identity.mfa.admin-reset.approve', 'user', 1800,
     'reference', 'careos-phase0-reference-v1');

CREATE FUNCTION careos_validate_authorization_approval_lifecycle()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    workflow authorization_approval_workflows%ROWTYPE;
    configured_actor uuid :=
        nullif(current_setting('app.current_actor_id', true), '')::uuid;
    configured_operation text :=
        nullif(current_setting('app.current_operation_key', true), '');
    configured_correlation text :=
        nullif(current_setting('app.current_correlation_id', true), '');
    configured_reason text :=
        nullif(current_setting('app.current_authorization_reason', true), '');
    configured_approval uuid :=
        nullif(current_setting('app.current_approval_id', true), '')::uuid;
    reference_enabled boolean := coalesce(
        nullif(current_setting('app.reference_authorization_policy_enabled', true), '')::boolean,
        false);
BEGIN
    SELECT * INTO workflow
    FROM authorization_approval_workflows registered
    WHERE registered.target_operation_key = NEW.operation_key
      AND (registered.status = 'active'
           OR (reference_enabled AND registered.status = 'reference'));
    IF NOT FOUND THEN
        RAISE EXCEPTION 'approval workflow is unknown, inactive, or retired'
            USING ERRCODE = '23514';
    END IF;

    IF TG_OP = 'INSERT' THEN
        IF configured_operation IS DISTINCT FROM workflow.request_operation_key
            OR NEW.subject_type IS DISTINCT FROM workflow.subject_type
            OR NEW.requested_by_user_id IS DISTINCT FROM configured_actor
            OR NEW.request_reason IS DISTINCT FROM configured_reason
            OR NEW.request_correlation_id IS DISTINCT FROM configured_correlation
            OR NEW.status <> 'pending'
            OR NEW.expires_at > NEW.created_at
                + make_interval(secs => workflow.maximum_approval_ttl_seconds)
            OR (NEW.operation_key = 'identity.mfa.admin-reset'
                AND NEW.subject_id = NEW.requested_by_user_id) THEN
            RAISE EXCEPTION 'invalid authorization approval request'
                USING ERRCODE = '42501';
        END IF;
        RETURN NEW;
    END IF;

    IF ROW(NEW.id, NEW.organization_id, NEW.operation_key, NEW.subject_type,
           NEW.subject_id, NEW.requested_by_user_id, NEW.request_reason,
           NEW.request_correlation_id, NEW.expires_at, NEW.created_at)
       IS DISTINCT FROM
       ROW(OLD.id, OLD.organization_id, OLD.operation_key, OLD.subject_type,
           OLD.subject_id, OLD.requested_by_user_id, OLD.request_reason,
           OLD.request_correlation_id, OLD.expires_at, OLD.created_at) THEN
        RAISE EXCEPTION 'authorization approval request identity is immutable'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.lock_version <> OLD.lock_version + 1
        OR NEW.updated_at < OLD.updated_at
        OR NEW.updated_at > clock_timestamp() + interval '5 seconds' THEN
        RAISE EXCEPTION 'invalid authorization approval revision'
            USING ERRCODE = '23514';
    END IF;

    IF OLD.status = 'pending' AND NEW.status IN ('approved', 'rejected') THEN
        IF configured_operation IS DISTINCT FROM workflow.decision_operation_key
            OR NEW.decided_by_user_id IS DISTINCT FROM configured_actor
            OR NEW.decided_by_user_id = OLD.requested_by_user_id
            OR NEW.decided_by_user_id = OLD.subject_id
            OR NEW.decision_reason IS DISTINCT FROM configured_reason
            OR NEW.decision_correlation_id IS DISTINCT FROM configured_correlation
            OR NEW.decided_at < OLD.created_at
            OR NEW.decided_at > clock_timestamp() + interval '5 seconds'
            OR NEW.expires_at <= clock_timestamp()
            OR NEW.consumed_by_user_id IS NOT NULL
            OR NEW.consumed_at IS NOT NULL
            OR NEW.consumption_correlation_id IS NOT NULL
            OR NEW.consumed_idempotency_key IS NOT NULL THEN
            RAISE EXCEPTION 'invalid authorization approval decision'
                USING ERRCODE = '42501';
        END IF;
        RETURN NEW;
    END IF;

    IF OLD.status IN ('pending', 'approved') AND NEW.status = 'expired' THEN
        IF configured_operation IS DISTINCT FROM workflow.request_operation_key
            OR configured_actor IS NULL
            OR OLD.expires_at > clock_timestamp()
            OR ROW(NEW.decided_by_user_id, NEW.decision_reason,
                   NEW.decision_correlation_id, NEW.decided_at)
               IS DISTINCT FROM
               ROW(OLD.decided_by_user_id, OLD.decision_reason,
                   OLD.decision_correlation_id, OLD.decided_at)
            OR NEW.consumed_by_user_id IS NOT NULL
            OR NEW.consumed_at IS NOT NULL
            OR NEW.consumption_correlation_id IS NOT NULL
            OR NEW.consumed_idempotency_key IS NOT NULL THEN
            RAISE EXCEPTION 'invalid authorization approval expiration'
                USING ERRCODE = '42501';
        END IF;
        RETURN NEW;
    END IF;

    IF OLD.status = 'approved' AND NEW.status = 'consumed' THEN
        IF configured_operation IS DISTINCT FROM workflow.target_operation_key
            OR configured_approval IS DISTINCT FROM OLD.id
            OR NEW.consumed_by_user_id IS DISTINCT FROM configured_actor
            OR NEW.consumed_by_user_id IS DISTINCT FROM OLD.requested_by_user_id
            OR NEW.consumption_correlation_id IS DISTINCT FROM configured_correlation
            OR NEW.consumed_idempotency_key IS NULL
            OR NEW.consumed_at < OLD.decided_at
            OR NEW.consumed_at > clock_timestamp() + interval '5 seconds'
            OR NEW.expires_at <= clock_timestamp()
            OR ROW(NEW.decided_by_user_id, NEW.decision_reason,
                   NEW.decision_correlation_id, NEW.decided_at)
               IS DISTINCT FROM
               ROW(OLD.decided_by_user_id, OLD.decision_reason,
                   OLD.decision_correlation_id, OLD.decided_at) THEN
            RAISE EXCEPTION 'invalid authorization approval consumption'
                USING ERRCODE = '42501';
        END IF;
        RETURN NEW;
    END IF;

    RAISE EXCEPTION 'invalid authorization approval lifecycle transition'
        USING ERRCODE = '23514';
END;
$$;

CREATE TRIGGER authorization_approval_requests_validate_insert
    BEFORE INSERT ON authorization_approval_requests
    FOR EACH ROW EXECUTE FUNCTION careos_validate_authorization_approval_lifecycle();

CREATE TRIGGER authorization_approval_requests_validate_update
    BEFORE UPDATE ON authorization_approval_requests
    FOR EACH ROW EXECUTE FUNCTION careos_validate_authorization_approval_lifecycle();

CREATE FUNCTION careos_reject_authorization_approval_delete()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'authorization approval evidence cannot be deleted';
END;
$$;

CREATE TRIGGER authorization_approval_requests_no_delete
    BEFORE DELETE ON authorization_approval_requests
    FOR EACH ROW EXECUTE FUNCTION careos_reject_authorization_approval_delete();

INSERT INTO audit_event_definitions
    (event_name, schema_version, display_name, description, subject_type,
     reason_required, required_payload_keys, allowed_payload_keys,
     payload_schema, status, registry_version)
VALUES
    ('identity.mfa-admin-reset.requested', 1, 'MFA administrator reset requested',
     'An authorized account requested independent approval to reset another account MFA.',
     'authorization_approval', true,
     ARRAY['targetUserId'], ARRAY['targetUserId'],
     '{"type":"object"}'::jsonb, 'reference', 'careos-phase0-reference-v1'),
    ('identity.mfa-admin-reset.approved', 1, 'MFA administrator reset approved',
     'A different authorized account approved an MFA reset request.',
     'authorization_approval', true,
     ARRAY['requestedByUserId', 'targetUserId'],
     ARRAY['requestedByUserId', 'targetUserId'],
     '{"type":"object"}'::jsonb, 'reference', 'careos-phase0-reference-v1'),
    ('identity.mfa-admin-reset.completed', 1, 'MFA administrator reset completed',
     'An independently approved MFA reset revoked the target MFA material and sessions.',
     'user', true,
     ARRAY['approvalId', 'approvedByUserId', 'requestedByUserId'],
     ARRAY['approvalId', 'approvedByUserId', 'requestedByUserId'],
     '{"type":"object"}'::jsonb, 'reference', 'careos-phase0-reference-v1');

INSERT INTO outbox_event_definitions
    (event_name, schema_version, description, aggregate_type,
     required_payload_keys, allowed_payload_keys, payload_schema, status, registry_version)
VALUES
    ('identity.mfa-admin-reset.requested', 1,
     'An authorized account requested an independently approved MFA reset.',
     'authorization_approval', ARRAY['targetUserId'], ARRAY['targetUserId'],
     '{"type":"object"}'::jsonb, 'reference', 'careos-phase0-reference-v1'),
    ('identity.mfa-admin-reset.approved', 1,
     'A different authorized account approved an MFA reset request.',
     'authorization_approval', ARRAY['requestedByUserId', 'targetUserId'],
     ARRAY['requestedByUserId', 'targetUserId'],
     '{"type":"object"}'::jsonb, 'reference', 'careos-phase0-reference-v1'),
    ('identity.mfa-admin-reset.completed', 1,
     'An independently approved MFA reset revoked target MFA material and sessions.',
     'user', ARRAY['approvalId', 'approvedByUserId', 'requestedByUserId'],
     ARRAY['approvalId', 'approvedByUserId', 'requestedByUserId'],
     '{"type":"object"}'::jsonb, 'reference', 'careos-phase0-reference-v1');

INSERT INTO authorization_operation_events
    (operation_key, event_kind, event_name, schema_version, status, registry_version)
VALUES
    ('identity.mfa.admin-reset.request', 'audit',
     'identity.mfa-admin-reset.requested', 1, 'reference', 'careos-phase0-reference-v1'),
    ('identity.mfa.admin-reset.request', 'outbox',
     'identity.mfa-admin-reset.requested', 1, 'reference', 'careos-phase0-reference-v1'),
    ('identity.mfa.admin-reset.approve', 'audit',
     'identity.mfa-admin-reset.approved', 1, 'reference', 'careos-phase0-reference-v1'),
    ('identity.mfa.admin-reset.approve', 'outbox',
     'identity.mfa-admin-reset.approved', 1, 'reference', 'careos-phase0-reference-v1'),
    ('identity.mfa.admin-reset', 'audit',
     'identity.mfa-admin-reset.completed', 1, 'reference', 'careos-phase0-reference-v1'),
    ('identity.mfa.admin-reset', 'outbox',
     'identity.mfa-admin-reset.completed', 1, 'reference', 'careos-phase0-reference-v1');

REVOKE ALL ON authorization_approval_workflows FROM PUBLIC;
REVOKE ALL ON authorization_approval_requests FROM PUBLIC;
REVOKE ALL ON FUNCTION careos_validate_authorization_approval_lifecycle() FROM PUBLIC;
REVOKE ALL ON FUNCTION careos_reject_authorization_approval_delete() FROM PUBLIC;
GRANT SELECT ON authorization_approval_workflows TO "${applicationRole}";
GRANT SELECT, INSERT, UPDATE ON authorization_approval_requests TO "${applicationRole}";

COMMENT ON TABLE authorization_approval_workflows IS
    'Migration-owned mapping for operations that require independent maker-checker evidence.';
COMMENT ON TABLE authorization_approval_requests IS
    'Tenant-bound immutable-subject approval lifecycle; approved evidence is consumed only by its original maker and exact idempotent execution.';
