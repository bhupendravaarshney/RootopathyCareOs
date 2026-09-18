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
            'V23 requires the active checksum-approved Module 1 authorization release';
    END IF;
END;
$$;

INSERT INTO authorization_operations
    (operation_key, permission_key, display_name, description, mutation, denial_mode,
     reason_required, recent_authentication_required,
     recent_authentication_max_age_seconds, maximum_future_skew_seconds,
     maker_checker_required, status, registry_version, mfa_required)
VALUES
    ('access.owner-transfer.request', 'access.owner_transfer.request',
     'Request owner transfer',
     'Request an independently approved owner-role promotion or demotion.',
     true, 'explicit', true, true, 300, 5, false,
     'active', 'm1-candidate-1', true),
    ('access.owner-transfer.approve', 'access.owner_transfer.approve',
     'Approve owner transfer',
     'Independently approve another owner request without changing owner access.',
     true, 'explicit', true, true, 300, 5, false,
     'active', 'm1-candidate-1', true),
    ('access.owner-transfer.execute', 'access.owner_transfer.execute',
     'Execute owner transfer',
     'Apply the exact independently approved owner-role promotion or demotion as its maker.',
     true, 'explicit', true, true, 300, 5, true,
     'active', 'm1-candidate-1', true);

INSERT INTO authorization_approval_workflows
    (target_operation_key, request_operation_key, decision_operation_key,
     subject_type, maximum_approval_ttl_seconds, status, registry_version)
VALUES
    ('access.owner-transfer.execute', 'access.owner-transfer.request',
     'access.owner-transfer.approve', 'membership', 1800,
     'active', 'm1-candidate-1');

CREATE TABLE owner_transfer_requests (
    id uuid PRIMARY KEY DEFAULT uuidv7()
        REFERENCES authorization_approval_requests(id),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    membership_id uuid NOT NULL,
    target_user_id uuid NOT NULL REFERENCES users(id),
    change_type varchar(32) NOT NULL,
    from_role_key varchar(100) NOT NULL REFERENCES authorization_roles(role_key),
    to_role_key varchar(100) NOT NULL REFERENCES authorization_roles(role_key),
    expected_lock_version bigint NOT NULL,
    created_by uuid NOT NULL REFERENCES users(id),
    request_correlation_id varchar(128) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (organization_id, id),
    FOREIGN KEY (organization_id, membership_id, target_user_id)
        REFERENCES organization_memberships(organization_id, id, user_id),
    CHECK (change_type IN ('owner_promotion', 'owner_demotion')),
    CHECK (expected_lock_version >= 0),
    CHECK (request_correlation_id ~ '^[A-Za-z0-9._:-]{1,128}$'),
    CHECK (isfinite(created_at)),
    CHECK (
        (change_type = 'owner_promotion'
            AND from_role_key <> 'organization_owner'
            AND to_role_key = 'organization_owner')
        OR
        (change_type = 'owner_demotion'
            AND from_role_key = 'organization_owner'
            AND to_role_key <> 'organization_owner')
    )
);

CREATE INDEX owner_transfer_requests_membership_idx
    ON owner_transfer_requests (organization_id, membership_id, created_at DESC);

ALTER TABLE owner_transfer_requests ENABLE ROW LEVEL SECURITY;
ALTER TABLE owner_transfer_requests FORCE ROW LEVEL SECURITY;
CREATE POLICY owner_transfer_requests_tenant_policy
    ON owner_transfer_requests
    USING (
        organization_id =
            nullif(current_setting('app.current_organization_id', true), '')::uuid)
    WITH CHECK (
        organization_id =
            nullif(current_setting('app.current_organization_id', true), '')::uuid);

CREATE FUNCTION careos_validate_owner_transfer_request_insert()
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

    IF configured_operation IS DISTINCT FROM 'access.owner-transfer.request'
        OR configured_organization IS DISTINCT FROM NEW.organization_id
        OR configured_actor IS DISTINCT FROM NEW.created_by
        OR configured_actor IS NULL
        OR configured_actor = NEW.target_user_id
        OR configured_correlation IS DISTINCT FROM NEW.request_correlation_id
        OR configured_reason IS NULL THEN
        RAISE EXCEPTION 'invalid owner transfer request context'
            USING ERRCODE = '42501';
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM authorization_approval_requests approval
        WHERE approval.id = NEW.id
          AND approval.organization_id = NEW.organization_id
          AND approval.operation_key = 'access.owner-transfer.execute'
          AND approval.subject_type = 'membership'
          AND approval.subject_id = NEW.membership_id
          AND approval.requested_by_user_id = configured_actor
          AND approval.request_reason = configured_reason
          AND approval.request_correlation_id = configured_correlation
          AND approval.status = 'pending'
    ) THEN
        RAISE EXCEPTION 'owner transfer request lacks exact approval evidence'
            USING ERRCODE = '42501';
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM organization_memberships actor_membership
        JOIN authorization_roles actor_role
          ON actor_role.role_key = actor_membership.role_key
        WHERE actor_membership.organization_id = NEW.organization_id
          AND actor_membership.user_id = configured_actor
          AND actor_membership.role_key = 'organization_owner'
          AND actor_membership.status = 'active'
          AND actor_membership.effective_from <= clock_timestamp()
          AND actor_membership.effective_to IS NULL
          AND actor_role.registry_version = 'm1-candidate-1'
          AND actor_role.status = 'active'
          AND actor_role.interactive
          AND actor_role.final_owner
    ) THEN
        RAISE EXCEPTION 'owner transfer maker is not an active indefinite owner'
            USING ERRCODE = '42501';
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM organization_memberships target_membership
        JOIN users target_user ON target_user.id = target_membership.user_id
        JOIN authorization_roles source_role
          ON source_role.role_key = target_membership.role_key
        JOIN authorization_roles destination_role
          ON destination_role.role_key = NEW.to_role_key
        WHERE target_membership.organization_id = NEW.organization_id
          AND target_membership.id = NEW.membership_id
          AND target_membership.user_id = NEW.target_user_id
          AND target_membership.role_key = NEW.from_role_key
          AND target_membership.lock_version = NEW.expected_lock_version
          AND target_membership.status = 'active'
          AND target_membership.effective_from <= clock_timestamp()
          AND (target_membership.effective_to IS NULL
               OR target_membership.effective_to > clock_timestamp())
          AND target_user.status = 'active'
          AND source_role.registry_version = 'm1-candidate-1'
          AND source_role.status = 'active'
          AND source_role.interactive
          AND destination_role.registry_version = 'm1-candidate-1'
          AND destination_role.status = 'active'
          AND destination_role.interactive
          AND (
              (NEW.change_type = 'owner_promotion'
                  AND NOT source_role.final_owner
                  AND destination_role.final_owner
                  AND destination_role.role_key = 'organization_owner'
                  AND target_membership.effective_to IS NULL)
              OR
              (NEW.change_type = 'owner_demotion'
                  AND source_role.final_owner
                  AND source_role.role_key = 'organization_owner'
                  AND NOT destination_role.final_owner
                  AND EXISTS (
                      SELECT 1
                      FROM authorization_role_delegations delegation
                      WHERE delegation.delegator_role_key = 'organization_owner'
                        AND delegation.target_role_key = destination_role.role_key
                        AND delegation.registry_version = 'm1-candidate-1'
                  ))
          )
    ) THEN
        RAISE EXCEPTION 'owner transfer target or role transition is unavailable'
            USING ERRCODE = '42501';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER owner_transfer_requests_validate_insert
    BEFORE INSERT ON owner_transfer_requests
    FOR EACH ROW EXECUTE FUNCTION careos_validate_owner_transfer_request_insert();

CREATE TRIGGER owner_transfer_requests_no_update
    BEFORE UPDATE ON owner_transfer_requests
    FOR EACH ROW EXECUTE FUNCTION careos_reject_membership_change_request_change();

CREATE TRIGGER owner_transfer_requests_no_delete
    BEFORE DELETE ON owner_transfer_requests
    FOR EACH ROW EXECUTE FUNCTION careos_reject_membership_change_request_change();

CREATE FUNCTION careos_validate_owner_transfer_approval_separation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    configured_actor uuid :=
        nullif(current_setting('app.current_actor_id', true), '')::uuid;
    target_user uuid;
BEGIN
    IF current_user <> '${applicationRole}'
        OR OLD.operation_key <> 'access.owner-transfer.execute'
        OR OLD.status <> 'pending'
        OR NEW.status NOT IN ('approved', 'rejected') THEN
        RETURN NEW;
    END IF;

    SELECT request.target_user_id
    INTO target_user
    FROM owner_transfer_requests request
    WHERE request.id = OLD.id
      AND request.organization_id = OLD.organization_id
      AND request.membership_id = OLD.subject_id;

    IF target_user IS NULL OR configured_actor IS NULL OR configured_actor = target_user THEN
        RAISE EXCEPTION 'owner transfer checker must be distinct from maker and target'
            USING ERRCODE = '42501';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER authorization_approval_owner_transfer_separation
    BEFORE UPDATE ON authorization_approval_requests
    FOR EACH ROW EXECUTE FUNCTION careos_validate_owner_transfer_approval_separation();

DROP TRIGGER organization_memberships_validate_runtime_change
    ON organization_memberships;
ALTER FUNCTION careos_validate_runtime_membership_change()
    RENAME TO careos_validate_runtime_non_owner_membership_change;

CREATE TRIGGER organization_memberships_validate_non_owner_change
    BEFORE UPDATE ON organization_memberships
    FOR EACH ROW
    WHEN (OLD.role_key <> 'organization_owner'
          AND NEW.role_key <> 'organization_owner')
    EXECUTE FUNCTION careos_validate_runtime_non_owner_membership_change();

CREATE FUNCTION careos_validate_runtime_owner_transfer()
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
    requested_transfer owner_transfer_requests%ROWTYPE;
BEGIN
    IF current_user <> '${applicationRole}' THEN
        RETURN NEW;
    END IF;

    IF configured_operation IS DISTINCT FROM 'access.owner-transfer.execute'
        OR configured_organization IS DISTINCT FROM OLD.organization_id
        OR configured_actor IS NULL
        OR configured_reason IS NULL
        OR configured_approval IS NULL THEN
        RAISE EXCEPTION 'owner role changes require the approved owner transfer operation'
            USING ERRCODE = '42501';
    END IF;

    SELECT request.*
    INTO requested_transfer
    FROM owner_transfer_requests request
    JOIN authorization_approval_requests approval ON approval.id = request.id
    WHERE request.id = configured_approval
      AND request.organization_id = OLD.organization_id
      AND request.membership_id = OLD.id
      AND request.target_user_id = OLD.user_id
      AND approval.operation_key = 'access.owner-transfer.execute'
      AND approval.subject_type = 'membership'
      AND approval.subject_id = OLD.id
      AND approval.requested_by_user_id = configured_actor
      AND approval.request_reason = configured_reason
      AND approval.status = 'consumed'
      AND approval.consumed_by_user_id = configured_actor
      AND approval.decided_by_user_id <> OLD.user_id;

    IF NOT FOUND
        OR OLD.user_id = configured_actor
        OR OLD.role_key IS DISTINCT FROM requested_transfer.from_role_key
        OR OLD.lock_version IS DISTINCT FROM requested_transfer.expected_lock_version
        OR OLD.status <> 'active'
        OR OLD.effective_from > clock_timestamp()
        OR (OLD.effective_to IS NOT NULL AND OLD.effective_to <= clock_timestamp())
        OR NOT EXISTS (
            SELECT 1
            FROM organization_memberships actor_membership
            JOIN authorization_roles actor_role
              ON actor_role.role_key = actor_membership.role_key
            WHERE actor_membership.organization_id = OLD.organization_id
              AND actor_membership.user_id = configured_actor
              AND actor_membership.role_key = 'organization_owner'
              AND actor_membership.status = 'active'
              AND actor_membership.effective_from <= clock_timestamp()
              AND actor_membership.effective_to IS NULL
              AND actor_role.registry_version = 'm1-candidate-1'
              AND actor_role.status = 'active'
              AND actor_role.interactive
              AND actor_role.final_owner
        ) THEN
        RAISE EXCEPTION 'approved owner transfer no longer matches its target'
            USING ERRCODE = '23514';
    END IF;

    IF ROW(NEW.id, NEW.organization_id, NEW.user_id, NEW.status,
           NEW.effective_from, NEW.effective_to)
       IS DISTINCT FROM
       ROW(OLD.id, OLD.organization_id, OLD.user_id, OLD.status,
           OLD.effective_from, OLD.effective_to) THEN
        RAISE EXCEPTION 'owner transfer may change only the approved role and revision evidence'
            USING ERRCODE = '23514';
    END IF;

    IF NEW.role_key IS DISTINCT FROM requested_transfer.to_role_key
        OR NEW.lock_version <> OLD.lock_version + 1
        OR NEW.updated_by IS DISTINCT FROM configured_actor THEN
        RAISE EXCEPTION 'owner transfer revision evidence is invalid'
            USING ERRCODE = '23514';
    END IF;
    NEW.updated_at := greatest(clock_timestamp(), OLD.updated_at + interval '1 microsecond');

    IF requested_transfer.change_type = 'owner_promotion' THEN
        IF OLD.role_key = 'organization_owner'
            OR NEW.role_key <> 'organization_owner'
            OR OLD.effective_to IS NOT NULL
            OR NOT EXISTS (
                SELECT 1
                FROM authorization_roles source_role
                JOIN authorization_roles destination_role
                  ON destination_role.role_key = NEW.role_key
                WHERE source_role.role_key = OLD.role_key
                  AND source_role.registry_version = 'm1-candidate-1'
                  AND source_role.status = 'active'
                  AND source_role.interactive
                  AND NOT source_role.final_owner
                  AND destination_role.registry_version = 'm1-candidate-1'
                  AND destination_role.status = 'active'
                  AND destination_role.interactive
                  AND destination_role.final_owner
            ) THEN
            RAISE EXCEPTION 'owner promotion differs from the approved request'
                USING ERRCODE = '42501';
        END IF;
    ELSIF requested_transfer.change_type = 'owner_demotion' THEN
        IF OLD.role_key <> 'organization_owner'
            OR NEW.role_key = 'organization_owner'
            OR NOT EXISTS (
                SELECT 1
                FROM authorization_roles destination_role
                JOIN authorization_role_delegations delegation
                  ON delegation.delegator_role_key = 'organization_owner'
                 AND delegation.target_role_key = destination_role.role_key
                 AND delegation.registry_version = 'm1-candidate-1'
                WHERE destination_role.role_key = NEW.role_key
                  AND destination_role.registry_version = 'm1-candidate-1'
                  AND destination_role.status = 'active'
                  AND destination_role.interactive
                  AND NOT destination_role.final_owner
            ) THEN
            RAISE EXCEPTION 'owner demotion differs from the approved request'
                USING ERRCODE = '42501';
        END IF;
    ELSE
        RAISE EXCEPTION 'owner transfer type is not implemented'
            USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER organization_memberships_validate_owner_transfer
    BEFORE UPDATE ON organization_memberships
    FOR EACH ROW
    WHEN (OLD.role_key = 'organization_owner'
          OR NEW.role_key = 'organization_owner')
    EXECUTE FUNCTION careos_validate_runtime_owner_transfer();

INSERT INTO audit_event_definitions
    (event_name, schema_version, display_name, description, subject_type,
     reason_required, required_payload_keys, allowed_payload_keys,
     payload_schema, status, registry_version)
VALUES
    ('identity.owner-transfer.requested', 1, 'Owner transfer requested',
     'An owner requested an independently approved owner-role transition.',
     'owner_transfer', true,
     ARRAY['approvalId', 'changeType', 'expectedLockVersion', 'fromRole', 'membershipId', 'toRole'],
     ARRAY['approvalId', 'changeType', 'expectedLockVersion', 'fromRole', 'membershipId', 'toRole'],
     '{"type":"object"}'::jsonb, 'active', 'm1-candidate-1'),
    ('identity.owner-transfer.approved', 1, 'Owner transfer approved',
     'A distinct owner approved an owner-role transition without applying it.',
     'owner_transfer', true,
     ARRAY['approvalId', 'changeType', 'fromRole', 'membershipId', 'requestedByUserId', 'toRole'],
     ARRAY['approvalId', 'changeType', 'fromRole', 'membershipId', 'requestedByUserId', 'toRole'],
     '{"type":"object"}'::jsonb, 'active', 'm1-candidate-1'),
    ('identity.owner.transferred', 1, 'Organization ownership transferred',
     'An exact independently approved owner-role promotion or demotion was applied.',
     'membership', true,
     ARRAY['approvalId', 'changeType', 'fromRole', 'lockVersion', 'membershipId', 'toRole'],
     ARRAY['approvalId', 'changeType', 'fromRole', 'lockVersion', 'membershipId', 'toRole'],
     '{"type":"object"}'::jsonb, 'active', 'm1-candidate-1');

INSERT INTO outbox_event_definitions
    (event_name, schema_version, description, aggregate_type,
     required_payload_keys, allowed_payload_keys, payload_schema, status, registry_version)
VALUES
    ('identity.owner-transfer.requested', 1,
     'An independently approved owner-role transition was requested.',
     'owner_transfer',
     ARRAY['approvalId', 'changeType', 'expectedLockVersion', 'fromRole', 'membershipId', 'toRole'],
     ARRAY['approvalId', 'changeType', 'expectedLockVersion', 'fromRole', 'membershipId', 'toRole'],
     '{"type":"object"}'::jsonb, 'active', 'm1-candidate-1'),
    ('identity.owner-transfer.approved', 1,
     'A distinct owner approved an owner-role transition without applying it.',
     'owner_transfer',
     ARRAY['approvalId', 'changeType', 'fromRole', 'membershipId', 'requestedByUserId', 'toRole'],
     ARRAY['approvalId', 'changeType', 'fromRole', 'membershipId', 'requestedByUserId', 'toRole'],
     '{"type":"object"}'::jsonb, 'active', 'm1-candidate-1'),
    ('identity.owner.transferred', 1,
     'An exact independently approved owner-role transition was applied.',
     'membership',
     ARRAY['approvalId', 'changeType', 'fromRole', 'lockVersion', 'membershipId', 'toRole'],
     ARRAY['approvalId', 'changeType', 'fromRole', 'lockVersion', 'membershipId', 'toRole'],
     '{"type":"object"}'::jsonb, 'active', 'm1-candidate-1');

INSERT INTO authorization_operation_events
    (operation_key, event_kind, event_name, schema_version, status, registry_version)
VALUES
    ('access.owner-transfer.request', 'audit',
     'identity.owner-transfer.requested', 1, 'active', 'm1-candidate-1'),
    ('access.owner-transfer.request', 'outbox',
     'identity.owner-transfer.requested', 1, 'active', 'm1-candidate-1'),
    ('access.owner-transfer.approve', 'audit',
     'identity.owner-transfer.approved', 1, 'active', 'm1-candidate-1'),
    ('access.owner-transfer.approve', 'outbox',
     'identity.owner-transfer.approved', 1, 'active', 'm1-candidate-1'),
    ('access.owner-transfer.execute', 'audit',
     'identity.owner.transferred', 1, 'active', 'm1-candidate-1'),
    ('access.owner-transfer.execute', 'outbox',
     'identity.owner.transferred', 1, 'active', 'm1-candidate-1');

REVOKE ALL ON owner_transfer_requests FROM PUBLIC;
REVOKE ALL ON FUNCTION careos_validate_owner_transfer_request_insert() FROM PUBLIC;
REVOKE ALL ON FUNCTION careos_validate_owner_transfer_approval_separation() FROM PUBLIC;
REVOKE ALL ON FUNCTION careos_validate_runtime_non_owner_membership_change() FROM PUBLIC;
REVOKE ALL ON FUNCTION careos_validate_runtime_owner_transfer() FROM PUBLIC;
GRANT SELECT, INSERT ON owner_transfer_requests TO "${applicationRole}";

COMMENT ON TABLE owner_transfer_requests IS
    'Immutable exact owner promotion/demotion request paired with generic maker-checker evidence.';
COMMENT ON FUNCTION careos_validate_runtime_non_owner_membership_change() IS
    'Restricts runtime non-owner membership updates to their exact consumed approval.';
COMMENT ON FUNCTION careos_validate_runtime_owner_transfer() IS
    'Restricts runtime owner-role changes to an exact consumed approval, original owner maker, target separation, approved role transition, and final-owner guard.';
