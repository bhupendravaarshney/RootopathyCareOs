ALTER TABLE audit_event_definitions
    DROP CONSTRAINT audit_event_definitions_status_check,
    ADD CONSTRAINT audit_event_definitions_status_check
        CHECK (status IN ('active', 'reference', 'retired'));

ALTER TABLE outbox_event_definitions
    DROP CONSTRAINT outbox_event_definitions_status_check,
    ADD CONSTRAINT outbox_event_definitions_status_check
        CHECK (status IN ('active', 'reference', 'retired'));

CREATE TABLE authorization_operation_events (
    operation_key varchar(160) NOT NULL REFERENCES authorization_operations(operation_key),
    event_kind varchar(16) NOT NULL,
    event_name varchar(180) NOT NULL,
    schema_version integer NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'active',
    registry_version varchar(80) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (operation_key, event_kind, event_name, schema_version),
    CHECK (event_kind IN ('audit', 'outbox')),
    CHECK (event_name ~ '^[a-z][a-z0-9]*([.:-][a-z0-9]+)*$'),
    CHECK (schema_version > 0),
    CHECK (status IN ('active', 'reference', 'retired')),
    CHECK (registry_version ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,79}$'),
    CHECK (isfinite(created_at))
);

ALTER TABLE invitations
    ADD COLUMN issued_reason text,
    ADD COLUMN revoked_by uuid REFERENCES users(id),
    ADD COLUMN revocation_reason text,
    ADD COLUMN revocation_correlation_id varchar(128),
    ADD COLUMN acceptance_correlation_id varchar(128),
    ADD COLUMN accepted_existing_account boolean,
    ADD CONSTRAINT invitations_issued_reason_check
        CHECK (issued_reason IS NULL OR char_length(btrim(issued_reason)) BETWEEN 1 AND 2000),
    ADD CONSTRAINT invitations_revocation_reason_check
        CHECK (revocation_reason IS NULL OR char_length(btrim(revocation_reason)) BETWEEN 1 AND 2000),
    ADD CONSTRAINT invitations_revocation_correlation_check
        CHECK (revocation_correlation_id IS NULL
            OR revocation_correlation_id ~ '^[A-Za-z0-9._:-]{1,128}$'),
    ADD CONSTRAINT invitations_acceptance_correlation_check
        CHECK (acceptance_correlation_id IS NULL
            OR acceptance_correlation_id ~ '^[A-Za-z0-9._:-]{1,128}$'),
    ADD CONSTRAINT invitations_v15_state_check CHECK (
        (status = 'pending'
            AND accepted_by IS NULL AND accepted_at IS NULL
            AND revoked_by IS NULL AND revoked_at IS NULL
            AND revocation_reason IS NULL AND revocation_correlation_id IS NULL
            AND acceptance_correlation_id IS NULL AND accepted_existing_account IS NULL)
        OR
        (status = 'accepted'
            AND accepted_by IS NOT NULL AND accepted_at IS NOT NULL
            AND acceptance_correlation_id IS NOT NULL
            AND accepted_existing_account IS NOT NULL
            AND revoked_by IS NULL AND revoked_at IS NULL
            AND revocation_reason IS NULL AND revocation_correlation_id IS NULL)
        OR
        (status = 'revoked'
            AND accepted_by IS NULL AND accepted_at IS NULL
            AND acceptance_correlation_id IS NULL AND accepted_existing_account IS NULL
            AND revoked_by IS NOT NULL AND revoked_at IS NOT NULL
            AND revocation_reason IS NOT NULL AND revocation_correlation_id IS NOT NULL)
        OR
        (status = 'expired'
            AND accepted_by IS NULL AND accepted_at IS NULL
            AND revoked_by IS NULL AND revoked_at IS NULL
            AND revocation_reason IS NULL AND revocation_correlation_id IS NULL
            AND acceptance_correlation_id IS NULL AND accepted_existing_account IS NULL)
    );

CREATE UNIQUE INDEX invitations_one_pending_email_uq
    ON invitations (organization_id, lower(email))
    WHERE status = 'pending';

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM invitations invitation
        LEFT JOIN authorization_roles roles ON roles.role_key = invitation.role_key
        WHERE roles.role_key IS NULL
    ) THEN
        RAISE EXCEPTION
            'V15 cannot attach invitation role integrity while invitations use unregistered role keys';
    END IF;
END;
$$;

ALTER TABLE invitations
    ADD CONSTRAINT invitations_role_fk
        FOREIGN KEY (role_key) REFERENCES authorization_roles(role_key);

CREATE TABLE invitation_token_index (
    token_hash char(64) PRIMARY KEY,
    invitation_id uuid NOT NULL UNIQUE REFERENCES invitations(id),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    target_user_id uuid REFERENCES users(id),
    expires_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    CHECK (expires_at > created_at),
    CHECK (isfinite(created_at)),
    CHECK (isfinite(expires_at))
);

CREATE FUNCTION careos_sync_invitation_token_index()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        INSERT INTO public.invitation_token_index
            (token_hash, invitation_id, organization_id, target_user_id, expires_at, created_at)
        VALUES
            (NEW.token_hash, NEW.id, NEW.organization_id,
             (SELECT users.id FROM public.users users WHERE lower(users.email) = lower(NEW.email)),
             NEW.expires_at, NEW.created_at);
        RETURN NEW;
    END IF;

    IF OLD.status = 'pending' AND NEW.status <> 'pending' THEN
        DELETE FROM public.invitation_token_index token_index
        WHERE token_index.invitation_id = NEW.id;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER invitations_sync_token_index
    AFTER INSERT OR UPDATE OF status ON invitations
    FOR EACH ROW EXECUTE FUNCTION careos_sync_invitation_token_index();

INSERT INTO invitation_token_index
    (token_hash, invitation_id, organization_id, target_user_id, expires_at, created_at)
SELECT invitations.token_hash,
       invitations.id,
       invitations.organization_id,
       (SELECT users.id FROM users WHERE lower(users.email) = lower(invitations.email)),
       invitations.expires_at,
       invitations.created_at
FROM invitations
WHERE invitations.status = 'pending';

CREATE FUNCTION careos_lookup_invitation_token(candidate_hash text)
RETURNS TABLE (
    invitation_id uuid,
    organization_id uuid,
    target_user_id uuid,
    expires_at timestamptz
)
LANGUAGE plpgsql
SECURITY DEFINER
STABLE
SET search_path = pg_catalog, public
AS $$
BEGIN
    IF candidate_hash IS NULL OR candidate_hash !~ '^[0-9a-f]{64}$' THEN
        RETURN;
    END IF;
    RETURN QUERY
    SELECT token_index.invitation_id,
           token_index.organization_id,
           token_index.target_user_id,
           token_index.expires_at
    FROM public.invitation_token_index token_index
    WHERE token_index.token_hash = candidate_hash
      AND token_index.expires_at > clock_timestamp();
END;
$$;

CREATE FUNCTION careos_validate_invitation_lifecycle()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    configured_organization uuid :=
        nullif(current_setting('app.current_organization_id', true), '')::uuid;
    configured_actor uuid :=
        nullif(current_setting('app.current_actor_id', true), '')::uuid;
    configured_operation text :=
        nullif(current_setting('app.current_operation_key', true), '');
    configured_reason text :=
        nullif(current_setting('app.current_authorization_reason', true), '');
    reference_enabled boolean :=
        coalesce(nullif(current_setting('app.reference_authorization_policy_enabled', true), '')::boolean, false);
BEGIN
    IF current_user <> '${applicationRole}' THEN
        RETURN NEW;
    END IF;

    IF NEW.organization_id IS DISTINCT FROM configured_organization THEN
        RAISE EXCEPTION 'invitation tenant does not match the authorized transaction'
            USING ERRCODE = '42501';
    END IF;

    IF TG_OP = 'INSERT' THEN
        IF configured_operation IS DISTINCT FROM 'organization.invitation.issue'
            OR NEW.invited_by IS DISTINCT FROM configured_actor
            OR NEW.status <> 'pending'
            OR NEW.issued_reason IS DISTINCT FROM configured_reason
            OR NEW.expires_at <= clock_timestamp()
            OR NEW.expires_at > clock_timestamp() + interval '7 days'
            OR NEW.accepted_by IS NOT NULL OR NEW.accepted_at IS NOT NULL
            OR NEW.revoked_by IS NOT NULL OR NEW.revoked_at IS NOT NULL THEN
            RAISE EXCEPTION 'invalid invitation issuance context or state'
                USING ERRCODE = '42501';
        END IF;

        IF NOT EXISTS (
            SELECT 1
            FROM authorization_roles target_role
            WHERE target_role.role_key = NEW.role_key
              AND target_role.invitation_assignable
              AND (target_role.status = 'active'
                   OR (reference_enabled AND target_role.status = 'reference'))
        ) OR NOT EXISTS (
            SELECT 1
            FROM organization_memberships membership
            JOIN authorization_roles delegator_role
              ON delegator_role.role_key = membership.role_key
            JOIN authorization_role_delegations delegation
              ON delegation.delegator_role_key = delegator_role.role_key
             AND delegation.target_role_key = NEW.role_key
             AND delegation.registry_version = delegator_role.registry_version
            JOIN authorization_roles target_role
              ON target_role.role_key = delegation.target_role_key
             AND target_role.registry_version = delegation.registry_version
            WHERE membership.organization_id = NEW.organization_id
              AND membership.user_id = configured_actor
              AND membership.status = 'active'
              AND membership.effective_from <= clock_timestamp()
              AND (membership.effective_to IS NULL OR membership.effective_to > clock_timestamp())
              AND (delegator_role.status = 'active'
                   OR (reference_enabled AND delegator_role.status = 'reference'))
              AND (target_role.status = 'active'
                   OR (reference_enabled AND target_role.status = 'reference'))
        ) THEN
            RAISE EXCEPTION 'the invitation role exceeds the actor delegation ceiling'
                USING ERRCODE = '42501';
        END IF;

        NEW.created_at := clock_timestamp();
        NEW.updated_at := NEW.created_at;
        NEW.lock_version := 0;
        RETURN NEW;
    END IF;

    IF ROW(NEW.id, NEW.organization_id, NEW.email, NEW.display_name, NEW.role_key,
           NEW.token_hash, NEW.expires_at, NEW.invited_by, NEW.issued_reason, NEW.created_at)
       IS DISTINCT FROM
       ROW(OLD.id, OLD.organization_id, OLD.email, OLD.display_name, OLD.role_key,
           OLD.token_hash, OLD.expires_at, OLD.invited_by, OLD.issued_reason, OLD.created_at)
        OR OLD.status <> 'pending'
        OR NEW.lock_version <> OLD.lock_version + 1 THEN
        RAISE EXCEPTION 'immutable invitation content or terminal state cannot be changed'
            USING ERRCODE = '55000';
    END IF;

    IF configured_operation = 'organization.invitation.revoke' THEN
        IF NEW.status <> 'revoked'
            OR NEW.revoked_by IS DISTINCT FROM configured_actor
            OR NEW.revocation_reason IS DISTINCT FROM configured_reason
            OR NEW.revocation_correlation_id IS DISTINCT FROM
                nullif(current_setting('app.current_correlation_id', true), '')
            OR NEW.revoked_at IS NOT NULL
            OR NEW.accepted_by IS NOT NULL OR NEW.accepted_at IS NOT NULL THEN
            RAISE EXCEPTION 'invalid invitation revocation state'
                USING ERRCODE = '42501';
        END IF;
        NEW.revoked_at := clock_timestamp();
    ELSIF configured_operation = 'organization.invitation.accept' THEN
        IF NEW.status <> 'accepted'
            OR NEW.accepted_by IS DISTINCT FROM configured_actor
            OR NEW.acceptance_correlation_id IS DISTINCT FROM
                nullif(current_setting('app.current_correlation_id', true), '')
            OR NEW.accepted_existing_account IS NULL
            OR NEW.accepted_at IS NOT NULL
            OR NEW.revoked_by IS NOT NULL OR NEW.revoked_at IS NOT NULL
            OR OLD.expires_at <= clock_timestamp() THEN
            RAISE EXCEPTION 'invalid invitation acceptance state'
                USING ERRCODE = '42501';
        END IF;
        NEW.accepted_at := clock_timestamp();
    ELSE
        RAISE EXCEPTION 'invitation transition is not authorized for this operation'
            USING ERRCODE = '42501';
    END IF;

    NEW.updated_at := clock_timestamp();
    RETURN NEW;
END;
$$;

CREATE TRIGGER invitations_validate_lifecycle
    BEFORE INSERT OR UPDATE ON invitations
    FOR EACH ROW EXECUTE FUNCTION careos_validate_invitation_lifecycle();

CREATE OR REPLACE FUNCTION careos_validate_audit_event_insert()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    definition audit_event_definitions%ROWTYPE;
    payload_keys text[];
    configured_operation text :=
        nullif(current_setting('app.current_operation_key', true), '');
    reference_enabled boolean :=
        coalesce(nullif(current_setting('app.reference_authorization_policy_enabled', true), '')::boolean, false);
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
      AND (status = 'active' OR (reference_enabled AND status = 'reference'));
    IF NOT FOUND THEN
        RAISE EXCEPTION 'audit event definition is unknown, inactive, or retired'
            USING ERRCODE = '23514';
    END IF;
    IF NOT EXISTS (
        SELECT 1
        FROM authorization_operation_events mapping
        WHERE mapping.operation_key = configured_operation
          AND mapping.event_kind = 'audit'
          AND mapping.event_name = NEW.event_name
          AND mapping.schema_version = NEW.schema_version
          AND (mapping.status = 'active'
               OR (reference_enabled AND mapping.status = 'reference'))
    ) THEN
        RAISE EXCEPTION 'audit event is not approved for the authorized operation'
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

CREATE OR REPLACE FUNCTION careos_validate_outbox_event_insert()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    definition outbox_event_definitions%ROWTYPE;
    payload_keys text[];
    configured_operation text :=
        nullif(current_setting('app.current_operation_key', true), '');
    reference_enabled boolean :=
        coalesce(nullif(current_setting('app.reference_authorization_policy_enabled', true), '')::boolean, false);
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
      AND (status = 'active' OR (reference_enabled AND status = 'reference'));
    IF NOT FOUND THEN
        RAISE EXCEPTION 'outbox event definition is unknown, inactive, or retired'
            USING ERRCODE = '23514';
    END IF;
    IF NOT EXISTS (
        SELECT 1
        FROM authorization_operation_events mapping
        WHERE mapping.operation_key = configured_operation
          AND mapping.event_kind = 'outbox'
          AND mapping.event_name = NEW.event_name
          AND mapping.schema_version = NEW.schema_version
          AND (mapping.status = 'active'
               OR (reference_enabled AND mapping.status = 'reference'))
    ) THEN
        RAISE EXCEPTION 'outbox event is not approved for the authorized operation'
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

INSERT INTO authorization_permissions
    (permission_key, display_name, description, status, registry_version, scope, risk_class)
VALUES
    ('organization.invitation.accept', 'Accept organization invitation',
     'Accept a one-time invitation for the matching account or a newly created account.',
     'reference', 'careos-phase0-reference-v1', 'self', 'moderate');

INSERT INTO authorization_operations
    (operation_key, permission_key, display_name, description, mutation, denial_mode,
     reason_required, recent_authentication_required,
     recent_authentication_max_age_seconds, maximum_future_skew_seconds,
     maker_checker_required, status, registry_version)
VALUES
    ('organization.invitation.accept', 'organization.invitation.accept',
     'Accept invitation',
     'Consume a valid one-time invitation and link only its normalized email account.',
     true, 'explicit', false, false, NULL, NULL, false,
     'reference', 'careos-phase0-reference-v1');

INSERT INTO audit_event_definitions
    (event_name, schema_version, display_name, description, subject_type,
     reason_required, required_payload_keys, allowed_payload_keys,
     payload_schema, status, registry_version)
VALUES
    ('organization.invitation.issued', 1, 'Invitation issued',
     'A bounded organization invitation was issued.', 'invitation', true,
     ARRAY['emailHash', 'expiresAt', 'roleKey'], ARRAY['emailHash', 'expiresAt', 'roleKey'],
     '{"type":"object"}'::jsonb, 'reference', 'careos-phase0-reference-v1'),
    ('organization.invitation.revoked', 1, 'Invitation revoked',
     'A pending organization invitation was revoked.', 'invitation', true,
     ARRAY['emailHash', 'roleKey'], ARRAY['emailHash', 'roleKey'],
     '{"type":"object"}'::jsonb, 'reference', 'careos-phase0-reference-v1'),
    ('organization.invitation.accepted', 1, 'Invitation accepted',
     'A one-time organization invitation was accepted.', 'invitation', false,
     ARRAY['accountLink', 'emailHash', 'roleKey', 'userId'],
     ARRAY['accountLink', 'emailHash', 'roleKey', 'userId'],
     '{"type":"object"}'::jsonb, 'reference', 'careos-phase0-reference-v1');

INSERT INTO outbox_event_definitions
    (event_name, schema_version, description, aggregate_type,
     required_payload_keys, allowed_payload_keys, payload_schema, status, registry_version)
VALUES
    ('organization.invitation.issued', 1,
     'A bounded organization invitation was issued.', 'invitation',
     ARRAY['emailHash', 'expiresAt', 'roleKey'], ARRAY['emailHash', 'expiresAt', 'roleKey'],
     '{"type":"object"}'::jsonb, 'reference', 'careos-phase0-reference-v1'),
    ('organization.invitation.revoked', 1,
     'A pending organization invitation was revoked.', 'invitation',
     ARRAY['emailHash', 'roleKey'], ARRAY['emailHash', 'roleKey'],
     '{"type":"object"}'::jsonb, 'reference', 'careos-phase0-reference-v1'),
    ('organization.invitation.accepted', 1,
     'A one-time organization invitation was accepted.', 'invitation',
     ARRAY['accountLink', 'emailHash', 'roleKey', 'userId'],
     ARRAY['accountLink', 'emailHash', 'roleKey', 'userId'],
     '{"type":"object"}'::jsonb, 'reference', 'careos-phase0-reference-v1');

INSERT INTO authorization_operation_events
    (operation_key, event_kind, event_name, schema_version, status, registry_version)
VALUES
    ('organization.invitation.issue', 'audit', 'organization.invitation.issued', 1,
     'reference', 'careos-phase0-reference-v1'),
    ('organization.invitation.issue', 'outbox', 'organization.invitation.issued', 1,
     'reference', 'careos-phase0-reference-v1'),
    ('organization.invitation.revoke', 'audit', 'organization.invitation.revoked', 1,
     'reference', 'careos-phase0-reference-v1'),
    ('organization.invitation.revoke', 'outbox', 'organization.invitation.revoked', 1,
     'reference', 'careos-phase0-reference-v1'),
    ('organization.invitation.accept', 'audit', 'organization.invitation.accepted', 1,
     'reference', 'careos-phase0-reference-v1'),
    ('organization.invitation.accept', 'outbox', 'organization.invitation.accepted', 1,
     'reference', 'careos-phase0-reference-v1');

REVOKE ALL ON authorization_operation_events FROM PUBLIC;
REVOKE ALL ON invitation_token_index FROM PUBLIC;
REVOKE ALL ON FUNCTION careos_sync_invitation_token_index() FROM PUBLIC;
REVOKE ALL ON FUNCTION careos_lookup_invitation_token(text) FROM PUBLIC;
GRANT SELECT ON authorization_operation_events TO "${applicationRole}";
GRANT EXECUTE ON FUNCTION careos_lookup_invitation_token(text) TO "${applicationRole}";

COMMENT ON TABLE authorization_operation_events IS
    'Migration-owned allow-list binding each governed operation to its permitted audit/outbox schemas.';
COMMENT ON TABLE invitation_token_index IS
    'Restricted HMAC token lookup containing no raw invitation token or email address.';
COMMENT ON COLUMN invitations.issued_reason IS
    'Mandatory for V15 runtime issuance; nullable only for pre-V15 historical rows.';
