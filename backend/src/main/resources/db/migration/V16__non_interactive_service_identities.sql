CREATE TABLE service_identities (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    service_key varchar(100) NOT NULL,
    display_name varchar(180) NOT NULL,
    role_key varchar(100) NOT NULL REFERENCES authorization_roles(role_key),
    allowed_purposes text[] NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'active',
    active_from timestamptz NOT NULL DEFAULT clock_timestamp(),
    expires_at timestamptz,
    provisioning_reference varchar(180) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    lock_version bigint NOT NULL DEFAULT 0,
    UNIQUE (organization_id, id),
    UNIQUE (organization_id, service_key),
    CHECK (service_key ~ '^[a-z][a-z0-9]*([._:-][a-z0-9]+)*$'),
    CHECK (char_length(btrim(display_name)) BETWEEN 1 AND 180),
    CHECK (cardinality(allowed_purposes) BETWEEN 1 AND 16),
    CHECK (array_position(allowed_purposes, NULL) IS NULL),
    CHECK (status IN ('active', 'disabled')),
    CHECK (expires_at IS NULL OR expires_at > active_from),
    CHECK (provisioning_reference ~ '^[A-Za-z0-9][A-Za-z0-9 ._:/-]{0,179}$'),
    CHECK (isfinite(active_from)),
    CHECK (expires_at IS NULL OR isfinite(expires_at)),
    CHECK (isfinite(created_at)),
    CHECK (isfinite(updated_at)),
    CHECK (lock_version >= 0)
);

CREATE TABLE service_identity_credentials (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    service_identity_id uuid NOT NULL,
    credential_version integer NOT NULL,
    credential_hash char(64) NOT NULL UNIQUE,
    status varchar(24) NOT NULL DEFAULT 'active',
    active_from timestamptz NOT NULL DEFAULT clock_timestamp(),
    expires_at timestamptz NOT NULL,
    revoked_at timestamptz,
    provisioning_reference varchar(180) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (service_identity_id, credential_version),
    FOREIGN KEY (service_identity_id, organization_id)
        REFERENCES service_identities(id, organization_id),
    CHECK (credential_version > 0),
    CHECK (credential_hash ~ '^[0-9a-f]{64}$'),
    CHECK (status IN ('active', 'revoked')),
    CHECK (
        (status = 'active' AND revoked_at IS NULL)
        OR (status = 'revoked' AND revoked_at IS NOT NULL)
    ),
    CHECK (expires_at > active_from),
    CHECK (expires_at <= active_from + interval '366 days'),
    CHECK (provisioning_reference ~ '^[A-Za-z0-9][A-Za-z0-9 ._:/-]{0,179}$'),
    CHECK (isfinite(active_from)),
    CHECK (isfinite(expires_at)),
    CHECK (revoked_at IS NULL OR isfinite(revoked_at)),
    CHECK (isfinite(created_at))
);

CREATE INDEX service_identities_active_idx
    ON service_identities (organization_id, role_key, active_from, expires_at)
    WHERE status = 'active';

CREATE INDEX service_identity_credentials_active_idx
    ON service_identity_credentials
        (organization_id, service_identity_id, active_from, expires_at)
    WHERE status = 'active';

ALTER TABLE service_identities ENABLE ROW LEVEL SECURITY;
ALTER TABLE service_identities FORCE ROW LEVEL SECURITY;
CREATE POLICY service_identities_tenant_policy ON service_identities
    USING (organization_id = nullif(current_setting('app.current_organization_id', true), '')::uuid)
    WITH CHECK (
        organization_id = nullif(current_setting('app.current_organization_id', true), '')::uuid);

ALTER TABLE service_identity_credentials ENABLE ROW LEVEL SECURITY;
ALTER TABLE service_identity_credentials FORCE ROW LEVEL SECURITY;
CREATE POLICY service_identity_credentials_tenant_policy ON service_identity_credentials
    USING (organization_id = nullif(current_setting('app.current_organization_id', true), '')::uuid)
    WITH CHECK (
        organization_id = nullif(current_setting('app.current_organization_id', true), '')::uuid);

CREATE FUNCTION careos_validate_service_identity()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    allowed_purpose text;
    non_interactive_role boolean;
BEGIN
    SELECT NOT roles.interactive
    INTO non_interactive_role
    FROM authorization_roles roles
    WHERE roles.role_key = NEW.role_key;
    IF NOT coalesce(non_interactive_role, false) THEN
        RAISE EXCEPTION 'service identities require a registered non-interactive role'
            USING ERRCODE = '23514';
    END IF;
    FOREACH allowed_purpose IN ARRAY NEW.allowed_purposes LOOP
        IF allowed_purpose !~ '^[a-z0-9][a-z0-9._:-]{0,127}$' THEN
            RAISE EXCEPTION 'service identity purpose has an invalid format'
                USING ERRCODE = '23514';
        END IF;
    END LOOP;
    IF EXISTS (SELECT 1 FROM users WHERE users.id = NEW.id) THEN
        RAISE EXCEPTION 'service and user identity identifiers must be disjoint'
            USING ERRCODE = '23505';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER service_identities_validate
    BEFORE INSERT OR UPDATE OF id, role_key, allowed_purposes ON service_identities
    FOR EACH ROW EXECUTE FUNCTION careos_validate_service_identity();

CREATE FUNCTION careos_reject_non_interactive_membership_role()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM authorization_roles roles
        WHERE roles.role_key = NEW.role_key
          AND NOT roles.interactive
    ) THEN
        RAISE EXCEPTION 'non-interactive roles cannot be assigned to user memberships'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER organization_memberships_require_interactive_role
    BEFORE INSERT OR UPDATE OF role_key ON organization_memberships
    FOR EACH ROW EXECUTE FUNCTION careos_reject_non_interactive_membership_role();

CREATE FUNCTION careos_reject_user_service_identity_collision()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM public.service_identities WHERE service_identities.id = NEW.id) THEN
        RAISE EXCEPTION 'user and service identity identifiers must be disjoint'
            USING ERRCODE = '23505';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER users_reject_service_identity_collision
    BEFORE INSERT OR UPDATE OF id ON users
    FOR EACH ROW EXECUTE FUNCTION careos_reject_user_service_identity_collision();

INSERT INTO authorization_permissions
    (permission_key, display_name, description, status, registry_version, scope, risk_class)
VALUES
    ('platform.outbox.publish', 'Publish tenant outbox events',
     'Claim and publish already-governed tenant outbox events.',
     'reference', 'careos-phase0-reference-v1', 'organization', 'high'),
    ('platform.consumer.process', 'Process tenant integration events',
     'Process an approved integration envelope through the durable tenant inbox.',
     'reference', 'careos-phase0-reference-v1', 'organization', 'high'),
    ('platform.notification.deliver', 'Deliver tenant notifications',
     'Deliver already-governed tenant notification requests through an approved provider.',
     'reference', 'careos-phase0-reference-v1', 'organization', 'high'),
    ('platform.job.execute', 'Execute tenant jobs',
     'Claim and execute approved tenant job definitions.',
     'reference', 'careos-phase0-reference-v1', 'organization', 'high'),
    ('platform.schedule.dispatch', 'Dispatch tenant schedules',
     'Dispatch approved due schedules into governed tenant work.',
     'reference', 'careos-phase0-reference-v1', 'organization', 'high');

INSERT INTO authorization_roles
    (role_key, display_name, description, status, registry_version,
     interactive, invitation_assignable, final_owner)
VALUES
    ('service_outbox_publisher', 'Outbox publisher service',
     'Non-interactive least-privilege tenant outbox publisher.',
     'reference', 'careos-phase0-reference-v1', false, false, false),
    ('service_integration_consumer', 'Integration consumer service',
     'Non-interactive least-privilege tenant integration consumer.',
     'reference', 'careos-phase0-reference-v1', false, false, false),
    ('service_notification_delivery', 'Notification delivery service',
     'Non-interactive least-privilege tenant notification delivery worker.',
     'reference', 'careos-phase0-reference-v1', false, false, false),
    ('service_job_worker', 'Job worker service',
     'Non-interactive least-privilege tenant job worker.',
     'reference', 'careos-phase0-reference-v1', false, false, false),
    ('service_scheduler', 'Scheduler service',
     'Non-interactive least-privilege tenant scheduler.',
     'reference', 'careos-phase0-reference-v1', false, false, false);

INSERT INTO authorization_role_permissions (role_key, permission_key)
VALUES
    ('service_outbox_publisher', 'platform.outbox.publish'),
    ('service_integration_consumer', 'platform.consumer.process'),
    ('service_notification_delivery', 'platform.notification.deliver'),
    ('service_job_worker', 'platform.job.execute'),
    ('service_scheduler', 'platform.schedule.dispatch');

INSERT INTO authorization_operations
    (operation_key, permission_key, display_name, description, mutation, denial_mode,
     reason_required, recent_authentication_required,
     recent_authentication_max_age_seconds, maximum_future_skew_seconds,
     maker_checker_required, status, registry_version)
VALUES
    ('platform.outbox.publish', 'platform.outbox.publish', 'Publish tenant outbox',
     'Claim and publish already-governed outbox events for one tenant.',
     true, 'explicit', false, false, NULL, NULL, false,
     'reference', 'careos-phase0-reference-v1'),
    ('platform.consumer.process', 'platform.consumer.process', 'Process tenant integration event',
     'Apply one approved integration event through the durable tenant inbox boundary.',
     true, 'explicit', false, false, NULL, NULL, false,
     'reference', 'careos-phase0-reference-v1'),
    ('platform.notification.deliver', 'platform.notification.deliver',
     'Deliver tenant notification',
     'Deliver already-governed notification work for one tenant.',
     true, 'explicit', false, false, NULL, NULL, false,
     'reference', 'careos-phase0-reference-v1'),
    ('platform.job.execute', 'platform.job.execute', 'Execute tenant job',
     'Execute approved job work for one tenant.',
     true, 'explicit', false, false, NULL, NULL, false,
     'reference', 'careos-phase0-reference-v1'),
    ('platform.schedule.dispatch', 'platform.schedule.dispatch', 'Dispatch tenant schedule',
     'Dispatch approved scheduled work for one tenant.',
     true, 'explicit', false, false, NULL, NULL, false,
     'reference', 'careos-phase0-reference-v1');

CREATE FUNCTION careos_authorize_service_identity(
    candidate_organization_id uuid,
    candidate_credential_hash text,
    candidate_purpose text,
    candidate_operation_key text)
RETURNS TABLE (service_identity_id uuid)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    configured_organization_id uuid :=
        nullif(current_setting('app.current_organization_id', true), '')::uuid;
    reference_enabled boolean := coalesce(
        nullif(current_setting('app.reference_authorization_policy_enabled', true), '')::boolean,
        false);
BEGIN
    IF candidate_organization_id IS NULL
        OR configured_organization_id IS DISTINCT FROM candidate_organization_id
        OR candidate_credential_hash IS NULL
        OR candidate_credential_hash !~ '^[0-9a-f]{64}$'
        OR candidate_purpose IS NULL
        OR candidate_purpose !~ '^[a-z0-9][a-z0-9._:-]{0,127}$'
        OR candidate_operation_key IS NULL
        OR candidate_operation_key !~ '^[a-z][a-z0-9]*([.:-][a-z0-9]+)*$' THEN
        RETURN;
    END IF;

    RETURN QUERY
    SELECT identities.id
    FROM public.service_identity_credentials credentials
    JOIN public.service_identities identities
      ON identities.id = credentials.service_identity_id
     AND identities.organization_id = credentials.organization_id
    JOIN public.organizations organizations
      ON organizations.id = identities.organization_id
    JOIN public.authorization_roles roles
      ON roles.role_key = identities.role_key
    JOIN public.authorization_role_permissions role_permissions
      ON role_permissions.role_key = roles.role_key
    JOIN public.authorization_permissions permissions
      ON permissions.permission_key = role_permissions.permission_key
    JOIN public.authorization_operations operations
      ON operations.permission_key = permissions.permission_key
     AND operations.registry_version = permissions.registry_version
    WHERE credentials.organization_id = candidate_organization_id
      AND credentials.credential_hash = candidate_credential_hash
      AND credentials.status = 'active'
      AND credentials.active_from <= clock_timestamp()
      AND credentials.expires_at > clock_timestamp()
      AND identities.status = 'active'
      AND identities.active_from <= clock_timestamp()
      AND (identities.expires_at IS NULL OR identities.expires_at > clock_timestamp())
      AND candidate_purpose = ANY(identities.allowed_purposes)
      AND organizations.status IN ('draft', 'active')
      AND NOT roles.interactive
      AND NOT roles.invitation_assignable
      AND (roles.status = 'active' OR (reference_enabled AND roles.status = 'reference'))
      AND roles.registry_version = operations.registry_version
      AND (permissions.status = 'active'
           OR (reference_enabled AND permissions.status = 'reference'))
      AND operations.operation_key = candidate_operation_key
      AND (operations.status = 'active'
           OR (reference_enabled AND operations.status = 'reference'))
      AND NOT operations.reason_required
      AND NOT operations.recent_authentication_required
      AND NOT operations.maker_checker_required
    FOR SHARE OF credentials, identities, organizations;
END;
$$;

REVOKE ALL ON service_identities FROM PUBLIC;
REVOKE ALL ON service_identity_credentials FROM PUBLIC;
REVOKE ALL ON FUNCTION careos_validate_service_identity() FROM PUBLIC;
REVOKE ALL ON FUNCTION careos_reject_non_interactive_membership_role() FROM PUBLIC;
REVOKE ALL ON FUNCTION careos_reject_user_service_identity_collision() FROM PUBLIC;
REVOKE ALL ON FUNCTION careos_authorize_service_identity(uuid, text, text, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION careos_authorize_service_identity(uuid, text, text, text)
    TO "${applicationRole}";

COMMENT ON TABLE service_identities IS
    'Tenant-scoped non-interactive identities with one least-privilege role and an explicit purpose allow-list; owner provisioning only.';
COMMENT ON TABLE service_identity_credentials IS
    'Bounded HMAC credential records. Raw service credentials are never stored and runtime has no direct table access.';
COMMENT ON FUNCTION careos_authorize_service_identity(uuid, text, text, text) IS
    'Exact credential, tenant, purpose, non-interactive role, and operation authorization with row locks; returns no secret material.';
