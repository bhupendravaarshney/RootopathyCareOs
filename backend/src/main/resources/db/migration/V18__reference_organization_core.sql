ALTER TABLE organizations
    ADD COLUMN updated_by uuid REFERENCES users(id),
    ADD CONSTRAINT organizations_legal_name_check
        CHECK (char_length(btrim(legal_name)) BETWEEN 1 AND 240
               AND legal_name !~ '[[:cntrl:]]'),
    ADD CONSTRAINT organizations_display_name_check
        CHECK (char_length(btrim(display_name)) BETWEEN 1 AND 160
               AND display_name !~ '[[:cntrl:]]'),
    ADD CONSTRAINT organizations_country_code_check
        CHECK (country_code ~ '^[A-Z]{2}$'),
    ADD CONSTRAINT organizations_timezone_check
        CHECK (char_length(btrim(timezone)) BETWEEN 1 AND 80
               AND timezone !~ '[[:cntrl:]]'),
    ADD CONSTRAINT organizations_lock_version_check CHECK (lock_version >= 0),
    ADD CONSTRAINT organizations_timestamps_check
        CHECK (isfinite(created_at) AND isfinite(updated_at));

INSERT INTO authorization_permissions
    (permission_key, display_name, description, status, registry_version, scope, risk_class)
VALUES
    ('organization.profile.manage', 'Manage organization profile',
     'Update the bounded organization legal/display identity and international baseline.',
     'reference', 'careos-phase0-reference-v1', 'organization', 'moderate');

INSERT INTO authorization_role_permissions (role_key, permission_key)
VALUES
    ('organization_owner', 'organization.profile.manage'),
    ('organization_administrator', 'organization.profile.manage'),
    ('local_bootstrap', 'organization.profile.manage');

INSERT INTO authorization_operations
    (operation_key, permission_key, display_name, description, mutation, denial_mode,
     reason_required, recent_authentication_required,
     recent_authentication_max_age_seconds, maximum_future_skew_seconds,
     maker_checker_required, status, registry_version)
VALUES
    ('organization.readiness.read', 'organization.profile.read',
     'Read organization setup readiness',
     'Read server-calculated provisional Module 1 readiness and bounded counts.',
     false, 'hidden', false, false, NULL, NULL, false,
     'reference', 'careos-phase0-reference-v1'),
    ('organization.profile.update', 'organization.profile.manage',
     'Update organization profile',
     'Update the bounded organization profile with an explicit reason and optimistic revision.',
     true, 'explicit', true, false, NULL, NULL, false,
     'reference', 'careos-phase0-reference-v1');

INSERT INTO audit_event_definitions
    (event_name, schema_version, display_name, description, subject_type,
     reason_required, required_payload_keys, allowed_payload_keys,
     payload_schema, status, registry_version)
VALUES
    ('organization.profile.updated', 1, 'Organization profile updated',
     'The bounded organization profile was updated.', 'organization', true,
     ARRAY['changedFields', 'lockVersion'], ARRAY['changedFields', 'lockVersion'],
     '{"type":"object"}'::jsonb, 'reference', 'careos-phase0-reference-v1');

INSERT INTO outbox_event_definitions
    (event_name, schema_version, description, aggregate_type,
     required_payload_keys, allowed_payload_keys, payload_schema, status, registry_version)
VALUES
    ('organization.profile.updated', 1,
     'The bounded organization profile was updated.', 'organization',
     ARRAY['changedFields', 'lockVersion'], ARRAY['changedFields', 'lockVersion'],
     '{"type":"object"}'::jsonb, 'reference', 'careos-phase0-reference-v1');

INSERT INTO authorization_operation_events
    (operation_key, event_kind, event_name, schema_version, status, registry_version)
VALUES
    ('organization.profile.update', 'audit', 'organization.profile.updated', 1,
     'reference', 'careos-phase0-reference-v1'),
    ('organization.profile.update', 'outbox', 'organization.profile.updated', 1,
     'reference', 'careos-phase0-reference-v1');

CREATE FUNCTION careos_validate_runtime_organization_update()
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

    IF configured_operation IS DISTINCT FROM 'organization.profile.update'
        OR NOT reference_enabled
        OR configured_organization IS DISTINCT FROM OLD.id
        OR configured_actor IS NULL
        OR configured_reason IS NULL THEN
        RAISE EXCEPTION 'organization profile updates require the authorized reference operation'
            USING ERRCODE = '42501';
    END IF;

    IF NEW.id IS DISTINCT FROM OLD.id
        OR NEW.status IS DISTINCT FROM OLD.status
        OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'organization profile update attempted to change protected lifecycle data'
            USING ERRCODE = '23514';
    END IF;

    IF ROW(NEW.legal_name, NEW.display_name, NEW.country_code, NEW.timezone)
        IS NOT DISTINCT FROM
       ROW(OLD.legal_name, OLD.display_name, OLD.country_code, OLD.timezone) THEN
        RAISE EXCEPTION 'organization profile update must change at least one field'
            USING ERRCODE = '23514';
    END IF;

    NEW.updated_at := greatest(clock_timestamp(), OLD.updated_at + interval '1 microsecond');

    IF NEW.lock_version <> OLD.lock_version + 1
        OR NEW.updated_by IS DISTINCT FROM configured_actor THEN
        RAISE EXCEPTION 'organization profile revision evidence is invalid'
            USING ERRCODE = '23514';
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_timezone_names WHERE name = NEW.timezone) THEN
        RAISE EXCEPTION 'organization timezone is not an IANA timezone identifier'
            USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER organizations_validate_runtime_update
    BEFORE UPDATE ON organizations
    FOR EACH ROW EXECUTE FUNCTION careos_validate_runtime_organization_update();

COMMENT ON COLUMN organizations.updated_by IS
    'Actor responsible for the latest mutable organization-profile revision; legacy/migration-owned rows may be null until first governed update.';
COMMENT ON FUNCTION careos_validate_runtime_organization_update() IS
    'Restricts runtime organization updates to the provisional profile operation, exact tenant/actor/reason context, and monotonic revision evidence.';
