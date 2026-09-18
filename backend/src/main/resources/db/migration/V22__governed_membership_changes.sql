DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM authorization_registry_releases
        WHERE registry_version = 'm1-candidate-1'
          AND approval_record_id = 'M1-APPROVAL-20260916-01'
          AND approval_package_sha256 =
              '19aff5ce30516b7ee2101c093a8429d8a74394995ca90d486790bcc18a392946'
          AND status = 'active'
    ) THEN
        RAISE EXCEPTION
            'V22 requires the active checksum-approved Module 1 authorization release';
    END IF;
END;
$$;

ALTER TABLE organization_memberships
    ADD COLUMN updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    ADD COLUMN updated_by uuid REFERENCES users(id),
    ADD COLUMN lock_version bigint NOT NULL DEFAULT 0,
    ADD CONSTRAINT organization_memberships_lock_version_check
        CHECK (lock_version >= 0),
    ADD CONSTRAINT organization_memberships_org_id_user_uq
        UNIQUE (organization_id, id, user_id);

INSERT INTO authorization_operations
    (operation_key, permission_key, display_name, description, mutation, denial_mode,
     reason_required, recent_authentication_required,
     recent_authentication_max_age_seconds, maximum_future_skew_seconds,
     maker_checker_required, status, registry_version, mfa_required)
VALUES
    ('access.membership.change.request', 'access.membership.manage',
     'Request membership change',
     'Request an independently approved non-owner role change or membership revocation.',
     true, 'explicit', true, true, 300, 5, false,
     'active', 'm1-candidate-1', true),
    ('access.membership.change.approve', 'access.membership.approve',
     'Approve membership change',
     'Independently approve another owner request without applying the membership change.',
     true, 'explicit', true, true, 300, 5, false,
     'active', 'm1-candidate-1', true),
    ('access.membership.change', 'access.membership.manage',
     'Execute membership change',
     'Apply the exact independently approved non-owner role change or revocation as its maker.',
     true, 'explicit', true, true, 300, 5, true,
     'active', 'm1-candidate-1', true);

INSERT INTO authorization_approval_workflows
    (target_operation_key, request_operation_key, decision_operation_key,
     subject_type, maximum_approval_ttl_seconds, status, registry_version)
VALUES
    ('access.membership.change', 'access.membership.change.request',
     'access.membership.change.approve', 'membership', 1800,
     'active', 'm1-candidate-1');

CREATE TABLE membership_change_requests (
    id uuid PRIMARY KEY DEFAULT uuidv7()
        REFERENCES authorization_approval_requests(id),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    membership_id uuid NOT NULL,
    target_user_id uuid NOT NULL REFERENCES users(id),
    change_type varchar(32) NOT NULL,
    from_role_key varchar(100) NOT NULL REFERENCES authorization_roles(role_key),
    to_role_key varchar(100) REFERENCES authorization_roles(role_key),
    expected_lock_version bigint NOT NULL,
    created_by uuid NOT NULL REFERENCES users(id),
    request_correlation_id varchar(128) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (organization_id, id),
    FOREIGN KEY (organization_id, membership_id, target_user_id)
        REFERENCES organization_memberships(organization_id, id, user_id),
    CHECK (change_type IN ('role_change', 'revoke')),
    CHECK (expected_lock_version >= 0),
    CHECK (request_correlation_id ~ '^[A-Za-z0-9._:-]{1,128}$'),
    CHECK (isfinite(created_at)),
    CHECK (
        (change_type = 'role_change'
            AND to_role_key IS NOT NULL
            AND to_role_key <> from_role_key)
        OR
        (change_type = 'revoke' AND to_role_key IS NULL)
    )
);

CREATE INDEX membership_change_requests_membership_idx
    ON membership_change_requests (organization_id, membership_id, created_at DESC);

ALTER TABLE membership_change_requests ENABLE ROW LEVEL SECURITY;
ALTER TABLE membership_change_requests FORCE ROW LEVEL SECURITY;
CREATE POLICY membership_change_requests_tenant_policy
    ON membership_change_requests
    USING (
        organization_id =
            nullif(current_setting('app.current_organization_id', true), '')::uuid)
    WITH CHECK (
        organization_id =
            nullif(current_setting('app.current_organization_id', true), '')::uuid);

CREATE FUNCTION careos_validate_membership_change_request_insert()
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
    configured_correlation text :=
        nullif(current_setting('app.current_correlation_id', true), '');
    configured_reason text :=
        nullif(current_setting('app.current_authorization_reason', true), '');
BEGIN
    IF current_user <> '${applicationRole}' THEN
        RETURN NEW;
    END IF;

    IF configured_operation IS DISTINCT FROM 'access.membership.change.request'
        OR configured_organization IS DISTINCT FROM NEW.organization_id
        OR configured_actor IS DISTINCT FROM NEW.created_by
        OR configured_actor IS NULL
        OR configured_actor = NEW.target_user_id
        OR configured_correlation IS DISTINCT FROM NEW.request_correlation_id
        OR configured_reason IS NULL THEN
        RAISE EXCEPTION 'invalid membership change request context'
            USING ERRCODE = '42501';
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM authorization_approval_requests approval
        WHERE approval.id = NEW.id
          AND approval.organization_id = NEW.organization_id
          AND approval.operation_key = 'access.membership.change'
          AND approval.subject_type = 'membership'
          AND approval.subject_id = NEW.membership_id
          AND approval.requested_by_user_id = configured_actor
          AND approval.request_reason = configured_reason
          AND approval.request_correlation_id = configured_correlation
          AND approval.status = 'pending'
    ) THEN
        RAISE EXCEPTION 'membership change request lacks exact approval evidence'
            USING ERRCODE = '42501';
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM organization_memberships membership
        JOIN users target_user ON target_user.id = membership.user_id
        JOIN authorization_roles membership_role
          ON membership_role.role_key = membership.role_key
        WHERE membership.organization_id = NEW.organization_id
          AND membership.id = NEW.membership_id
          AND membership.user_id = NEW.target_user_id
          AND membership.role_key = NEW.from_role_key
          AND membership.lock_version = NEW.expected_lock_version
          AND membership.status = 'active'
          AND membership.effective_from <= clock_timestamp()
          AND (membership.effective_to IS NULL
               OR membership.effective_to > clock_timestamp())
          AND target_user.status = 'active'
          AND membership_role.registry_version = 'm1-candidate-1'
          AND membership_role.interactive
          AND membership_role.status = 'active'
          AND NOT membership_role.final_owner
    ) THEN
        RAISE EXCEPTION 'membership change target is unavailable'
            USING ERRCODE = '42501';
    END IF;

    IF NEW.change_type = 'role_change' AND NOT EXISTS (
        SELECT 1
        FROM authorization_roles target_role
        WHERE target_role.role_key = NEW.to_role_key
          AND target_role.registry_version = 'm1-candidate-1'
          AND target_role.status = 'active'
          AND target_role.interactive
          AND NOT target_role.final_owner
          AND EXISTS (
              SELECT 1
              FROM organization_memberships actor_membership
              JOIN authorization_roles actor_role
                ON actor_role.role_key = actor_membership.role_key
              JOIN authorization_role_delegations delegation
                ON delegation.delegator_role_key = actor_role.role_key
               AND delegation.target_role_key = target_role.role_key
               AND delegation.registry_version = 'm1-candidate-1'
              WHERE actor_membership.organization_id = NEW.organization_id
                AND actor_membership.user_id = configured_actor
                AND actor_membership.status = 'active'
                AND actor_membership.effective_from <= clock_timestamp()
                AND (actor_membership.effective_to IS NULL
                     OR actor_membership.effective_to > clock_timestamp())
                AND actor_role.registry_version = 'm1-candidate-1'
                AND actor_role.status = 'active'
                AND actor_role.interactive
          )
    ) THEN
        RAISE EXCEPTION 'membership role exceeds the actor delegation ceiling'
            USING ERRCODE = '42501';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER membership_change_requests_validate_insert
    BEFORE INSERT ON membership_change_requests
    FOR EACH ROW EXECUTE FUNCTION careos_validate_membership_change_request_insert();

CREATE FUNCTION careos_reject_membership_change_request_change()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'membership change request evidence cannot be changed or deleted'
        USING ERRCODE = '42501';
END;
$$;

CREATE TRIGGER membership_change_requests_no_update
    BEFORE UPDATE ON membership_change_requests
    FOR EACH ROW EXECUTE FUNCTION careos_reject_membership_change_request_change();

CREATE TRIGGER membership_change_requests_no_delete
    BEFORE DELETE ON membership_change_requests
    FOR EACH ROW EXECUTE FUNCTION careos_reject_membership_change_request_change();

CREATE FUNCTION careos_validate_membership_change_approval_separation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    configured_actor uuid :=
        nullif(current_setting('app.current_actor_id', true), '')::uuid;
    target_user uuid;
BEGIN
    IF current_user <> '${applicationRole}'
        OR OLD.operation_key <> 'access.membership.change'
        OR OLD.status <> 'pending'
        OR NEW.status NOT IN ('approved', 'rejected') THEN
        RETURN NEW;
    END IF;

    SELECT request.target_user_id
    INTO target_user
    FROM membership_change_requests request
    WHERE request.id = OLD.id
      AND request.organization_id = OLD.organization_id
      AND request.membership_id = OLD.subject_id;

    IF target_user IS NULL OR configured_actor IS NULL OR configured_actor = target_user THEN
        RAISE EXCEPTION 'membership change checker must be distinct from maker and target'
            USING ERRCODE = '42501';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER authorization_approval_membership_change_separation
    BEFORE UPDATE ON authorization_approval_requests
    FOR EACH ROW EXECUTE FUNCTION careos_validate_membership_change_approval_separation();

CREATE FUNCTION careos_validate_runtime_membership_change()
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
    configured_approval uuid :=
        nullif(current_setting('app.current_approval_id', true), '')::uuid;
    requested_change membership_change_requests%ROWTYPE;
BEGIN
    IF current_user <> '${applicationRole}' THEN
        RETURN NEW;
    END IF;

    IF configured_operation IS DISTINCT FROM 'access.membership.change'
        OR configured_organization IS DISTINCT FROM OLD.organization_id
        OR configured_actor IS NULL
        OR configured_reason IS NULL
        OR configured_approval IS NULL THEN
        RAISE EXCEPTION 'organization membership changes require the approved operation'
            USING ERRCODE = '42501';
    END IF;

    SELECT request.*
    INTO requested_change
    FROM membership_change_requests request
    JOIN authorization_approval_requests approval ON approval.id = request.id
    WHERE request.id = configured_approval
      AND request.organization_id = OLD.organization_id
      AND request.membership_id = OLD.id
      AND request.target_user_id = OLD.user_id
      AND approval.operation_key = 'access.membership.change'
      AND approval.subject_type = 'membership'
      AND approval.subject_id = OLD.id
      AND approval.requested_by_user_id = configured_actor
      AND approval.request_reason = configured_reason
      AND approval.status = 'consumed'
      AND approval.consumed_by_user_id = configured_actor;

    IF NOT FOUND
        OR OLD.user_id = configured_actor
        OR OLD.role_key IS DISTINCT FROM requested_change.from_role_key
        OR OLD.lock_version IS DISTINCT FROM requested_change.expected_lock_version
        OR OLD.status <> 'active'
        OR OLD.effective_from > clock_timestamp()
        OR (OLD.effective_to IS NOT NULL AND OLD.effective_to <= clock_timestamp())
        OR EXISTS (
            SELECT 1 FROM authorization_roles role
            WHERE role.role_key = OLD.role_key AND role.final_owner
        ) THEN
        RAISE EXCEPTION 'approved membership change no longer matches its target'
            USING ERRCODE = '23514';
    END IF;

    IF ROW(NEW.id, NEW.organization_id, NEW.user_id, NEW.effective_from)
       IS DISTINCT FROM
       ROW(OLD.id, OLD.organization_id, OLD.user_id, OLD.effective_from) THEN
        RAISE EXCEPTION 'membership identity and start time are immutable'
            USING ERRCODE = '23514';
    END IF;

    IF NEW.lock_version <> OLD.lock_version + 1
        OR NEW.updated_by IS DISTINCT FROM configured_actor THEN
        RAISE EXCEPTION 'membership revision evidence is invalid'
            USING ERRCODE = '23514';
    END IF;
    NEW.updated_at := greatest(clock_timestamp(), OLD.updated_at + interval '1 microsecond');

    IF requested_change.change_type = 'role_change' THEN
        IF NEW.role_key IS DISTINCT FROM requested_change.to_role_key
            OR NEW.status IS DISTINCT FROM OLD.status
            OR NEW.effective_to IS DISTINCT FROM OLD.effective_to
            OR NOT EXISTS (
                SELECT 1
                FROM authorization_roles target_role
                WHERE target_role.role_key = NEW.role_key
                  AND target_role.registry_version = 'm1-candidate-1'
                  AND target_role.status = 'active'
                  AND target_role.interactive
                  AND NOT target_role.final_owner
                  AND EXISTS (
                      SELECT 1
                      FROM organization_memberships actor_membership
                      JOIN authorization_roles actor_role
                        ON actor_role.role_key = actor_membership.role_key
                      JOIN authorization_role_delegations delegation
                        ON delegation.delegator_role_key = actor_role.role_key
                       AND delegation.target_role_key = target_role.role_key
                       AND delegation.registry_version = 'm1-candidate-1'
                      WHERE actor_membership.organization_id = OLD.organization_id
                        AND actor_membership.user_id = configured_actor
                        AND actor_membership.status = 'active'
                        AND actor_membership.effective_from <= clock_timestamp()
                        AND (actor_membership.effective_to IS NULL
                             OR actor_membership.effective_to > clock_timestamp())
                        AND actor_role.registry_version = 'm1-candidate-1'
                        AND actor_role.status = 'active'
                        AND actor_role.interactive
                  )
            ) THEN
            RAISE EXCEPTION 'membership role change differs from the approved request'
                USING ERRCODE = '42501';
        END IF;
    ELSIF requested_change.change_type = 'revoke' THEN
        IF NEW.role_key IS DISTINCT FROM OLD.role_key
            OR NEW.status <> 'revoked'
            OR NEW.effective_to IS NULL
            OR NEW.effective_to < OLD.effective_from
            OR NEW.effective_to > clock_timestamp() + interval '5 seconds' THEN
            RAISE EXCEPTION 'membership revocation differs from the approved request'
                USING ERRCODE = '42501';
        END IF;
    ELSE
        RAISE EXCEPTION 'membership change type is not implemented'
            USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER organization_memberships_validate_runtime_change
    BEFORE UPDATE ON organization_memberships
    FOR EACH ROW EXECUTE FUNCTION careos_validate_runtime_membership_change();

INSERT INTO audit_event_definitions
    (event_name, schema_version, display_name, description, subject_type,
     reason_required, required_payload_keys, allowed_payload_keys,
     payload_schema, status, registry_version)
VALUES
    ('identity.membership-change.requested', 1, 'Membership change requested',
     'An owner requested an independently approved non-owner membership change.',
     'membership_change', true,
     ARRAY['approvalId', 'changeType', 'expectedLockVersion', 'fromRole', 'membershipId', 'toRole'],
     ARRAY['approvalId', 'changeType', 'expectedLockVersion', 'fromRole', 'membershipId', 'toRole'],
     '{"type":"object"}'::jsonb, 'active', 'm1-candidate-1'),
    ('identity.membership-change.approved', 1, 'Membership change approved',
     'A distinct owner approved a membership change without applying it.',
     'membership_change', true,
     ARRAY['approvalId', 'changeType', 'fromRole', 'membershipId', 'requestedByUserId', 'toRole'],
     ARRAY['approvalId', 'changeType', 'fromRole', 'membershipId', 'requestedByUserId', 'toRole'],
     '{"type":"object"}'::jsonb, 'active', 'm1-candidate-1'),
    ('identity.membership.changed', 1, 'Membership changed',
     'An exact independently approved non-owner membership role change was applied.',
     'membership', true,
     ARRAY['approvalId', 'changeType', 'fromRole', 'lockVersion', 'membershipId', 'toRole'],
     ARRAY['approvalId', 'changeType', 'fromRole', 'lockVersion', 'membershipId', 'toRole'],
     '{"type":"object"}'::jsonb, 'active', 'm1-candidate-1'),
    ('identity.membership.revoked', 1, 'Membership revoked',
     'An exact independently approved non-owner membership was revoked.',
     'membership', true,
     ARRAY['approvalId', 'changeType', 'fromRole', 'lockVersion', 'membershipId', 'toRole'],
     ARRAY['approvalId', 'changeType', 'fromRole', 'lockVersion', 'membershipId', 'toRole'],
     '{"type":"object"}'::jsonb, 'active', 'm1-candidate-1');

INSERT INTO outbox_event_definitions
    (event_name, schema_version, description, aggregate_type,
     required_payload_keys, allowed_payload_keys, payload_schema, status, registry_version)
VALUES
    ('identity.membership-change.requested', 1,
     'An independently approved non-owner membership change was requested.',
     'membership_change',
     ARRAY['approvalId', 'changeType', 'expectedLockVersion', 'fromRole', 'membershipId', 'toRole'],
     ARRAY['approvalId', 'changeType', 'expectedLockVersion', 'fromRole', 'membershipId', 'toRole'],
     '{"type":"object"}'::jsonb, 'active', 'm1-candidate-1'),
    ('identity.membership-change.approved', 1,
     'A distinct owner approved a membership change without applying it.',
     'membership_change',
     ARRAY['approvalId', 'changeType', 'fromRole', 'membershipId', 'requestedByUserId', 'toRole'],
     ARRAY['approvalId', 'changeType', 'fromRole', 'membershipId', 'requestedByUserId', 'toRole'],
     '{"type":"object"}'::jsonb, 'active', 'm1-candidate-1'),
    ('identity.membership.changed', 1,
     'An exact independently approved non-owner membership role change was applied.',
     'membership',
     ARRAY['approvalId', 'changeType', 'fromRole', 'lockVersion', 'membershipId', 'toRole'],
     ARRAY['approvalId', 'changeType', 'fromRole', 'lockVersion', 'membershipId', 'toRole'],
     '{"type":"object"}'::jsonb, 'active', 'm1-candidate-1'),
    ('identity.membership.revoked', 1,
     'An exact independently approved non-owner membership was revoked.',
     'membership',
     ARRAY['approvalId', 'changeType', 'fromRole', 'lockVersion', 'membershipId', 'toRole'],
     ARRAY['approvalId', 'changeType', 'fromRole', 'lockVersion', 'membershipId', 'toRole'],
     '{"type":"object"}'::jsonb, 'active', 'm1-candidate-1');

INSERT INTO authorization_operation_events
    (operation_key, event_kind, event_name, schema_version, status, registry_version)
VALUES
    ('access.membership.change.request', 'audit',
     'identity.membership-change.requested', 1, 'active', 'm1-candidate-1'),
    ('access.membership.change.request', 'outbox',
     'identity.membership-change.requested', 1, 'active', 'm1-candidate-1'),
    ('access.membership.change.approve', 'audit',
     'identity.membership-change.approved', 1, 'active', 'm1-candidate-1'),
    ('access.membership.change.approve', 'outbox',
     'identity.membership-change.approved', 1, 'active', 'm1-candidate-1'),
    ('access.membership.change', 'audit',
     'identity.membership.changed', 1, 'active', 'm1-candidate-1'),
    ('access.membership.change', 'outbox',
     'identity.membership.changed', 1, 'active', 'm1-candidate-1'),
    ('access.membership.change', 'audit',
     'identity.membership.revoked', 1, 'active', 'm1-candidate-1'),
    ('access.membership.change', 'outbox',
     'identity.membership.revoked', 1, 'active', 'm1-candidate-1');

REVOKE ALL ON membership_change_requests FROM PUBLIC;
REVOKE ALL ON FUNCTION careos_validate_membership_change_request_insert() FROM PUBLIC;
REVOKE ALL ON FUNCTION careos_reject_membership_change_request_change() FROM PUBLIC;
REVOKE ALL ON FUNCTION careos_validate_membership_change_approval_separation() FROM PUBLIC;
REVOKE ALL ON FUNCTION careos_validate_runtime_membership_change() FROM PUBLIC;
GRANT SELECT, INSERT ON membership_change_requests TO "${applicationRole}";

COMMENT ON TABLE membership_change_requests IS
    'Immutable exact non-owner role-change/revocation request paired with generic maker-checker evidence.';
COMMENT ON COLUMN organization_memberships.lock_version IS
    'Strong revision used by approved membership change requests.';
COMMENT ON FUNCTION careos_validate_runtime_membership_change() IS
    'Restricts runtime membership updates to an exact consumed approval, original maker, target separation, delegation ceiling, and non-owner target.';
