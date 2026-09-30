-- The platform super administrator is an organization-scoped interactive role.
-- It composes every currently approved human-interactive grant, but it is not a
-- database superuser, service identity, RLS bypass, or provider-readiness bypass.
-- Assignment is deliberately unavailable through invitations: an existing
-- membership must be changed through the independently approved membership flow.

-- This is a cross-cutting access release, not a fabricated M14 product module
-- and not an amendment to the immutable M1 approval package.
ALTER TABLE authorization_registry_releases
    DROP CONSTRAINT authorization_registry_releases_module_key_check;
ALTER TABLE authorization_registry_releases
    ADD CONSTRAINT authorization_registry_releases_module_key_check
        CHECK (module_key ~ '^(M[1-9][0-9]*|PLATFORM_ACCESS)$');

INSERT INTO authorization_registry_releases
    (registry_version,approval_record_id,approval_package_sha256,
     authorization_artifact_sha256,approval_evidence_sha256,
     approved_by,approved_at,status,module_key)
VALUES
    ('platform-access-v1','PLATFORM-ACCESS-DIRECTION-20260929-01',
     'dd8e15a5c5ce16addf7e6a0e709766a93837308777008998a2a40d7347f95ed2',
     'a6e4b41f86b762bf71ec032c6894779c4f99f74e50196e7f334f259c5e7d80f5',
     '5aebdff872fb6c4d551744068b116825d04d6d1e535f009c58f2a592bb3beacf',
     'bhupendra, developer','2026-09-29T00:00:00Z','active','PLATFORM_ACCESS');

INSERT INTO authorization_roles
    (role_key, display_name, description, status, registry_version,
     interactive, invitation_assignable, final_owner, mfa_required)
VALUES
    ('platform_super_administrator', 'Platform super administrator',
     'Organization-scoped administrator with every approved interactive permission; tenant isolation, MFA, independent approval, audit and provider readiness remain enforced.',
     'active', 'platform-access-v1', true, false, false, true);

-- Role-to-itself delegation was previously forbidden because no approved role
-- needed to create a peer with the same authority. The platform administrator
-- does need that capability for a different user. Keep the invariant for every
-- other role and admit this one migration-owned exception; application and
-- database membership guards still reject changes targeting the acting user.
ALTER TABLE authorization_role_delegations
    DROP CONSTRAINT authorization_role_delegations_check;
ALTER TABLE authorization_role_delegations
    ADD CONSTRAINT authorization_role_delegations_non_reflexive_check
        CHECK (delegator_role_key <> target_role_key
               OR delegator_role_key='platform_super_administrator');

-- V24 deliberately bound its derived login index to the then-only approved M1
-- registry. Extend the derivation to any active, immutable release so the new
-- role's mandatory MFA attribute is effective at login and readiness. The
-- derived table remains inaccessible to the runtime role.
CREATE OR REPLACE FUNCTION careos_sync_identity_mfa_role_requirement()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path=pg_catalog,public
AS $$
BEGIN
    IF TG_OP='DELETE' THEN
        DELETE FROM public.identity_mfa_role_requirements
        WHERE membership_id=OLD.id;
        RETURN OLD;
    END IF;

    IF EXISTS (
        SELECT 1
        FROM public.authorization_roles role
        WHERE role.role_key=NEW.role_key
          AND role.mfa_required
          AND role.status='active'
          AND role.interactive
          AND EXISTS (
              SELECT 1
              FROM public.authorization_registry_releases release
              WHERE release.registry_version=role.registry_version
                AND release.status='active')
    ) THEN
        INSERT INTO public.identity_mfa_role_requirements
            (membership_id,user_id,role_key,membership_status,
             effective_from,effective_to)
        VALUES
            (NEW.id,NEW.user_id,NEW.role_key,NEW.status,
             NEW.effective_from,NEW.effective_to)
        ON CONFLICT (membership_id) DO UPDATE
        SET user_id=EXCLUDED.user_id,
            role_key=EXCLUDED.role_key,
            membership_status=EXCLUDED.membership_status,
            effective_from=EXCLUDED.effective_from,
            effective_to=EXCLUDED.effective_to;
    ELSE
        DELETE FROM public.identity_mfa_role_requirements
        WHERE membership_id=NEW.id;
    END IF;
    RETURN NEW;
END;
$$;

COMMENT ON FUNCTION careos_sync_identity_mfa_role_requirement() IS
    'Maintains the private login MFA index only for active MFA-mandatory interactive roles backed by an active immutable release.';

INSERT INTO authorization_role_permissions (role_key, permission_key)
SELECT 'platform_super_administrator', approved.permission_key
FROM (
    SELECT DISTINCT role_permission.permission_key
    FROM authorization_role_permissions role_permission
    JOIN authorization_roles source_role
      ON source_role.role_key=role_permission.role_key
     AND source_role.status='active'
     AND source_role.interactive
    JOIN authorization_permissions permission
      ON permission.permission_key=role_permission.permission_key
     AND permission.status='active'
    WHERE source_role.role_key<>'platform_super_administrator'
      AND EXISTS (
          SELECT 1
          FROM authorization_registry_releases release
          WHERE release.registry_version=source_role.registry_version
            AND release.status='active')
      AND EXISTS (
          SELECT 1
          FROM authorization_registry_releases release
          WHERE release.registry_version=permission.registry_version
            AND release.status='active')
) approved;

-- One reusable, context-bound delegation predicate keeps Java checks and the
-- database write guards aligned. Reference authority is admitted only through
-- the V115 deployment-owned capability; setting a custom GUC is insufficient.
CREATE FUNCTION careos_can_delegate_active_interactive_role(
    requested_organization_id uuid,
    requested_actor_id uuid,
    requested_target_role_key text
) RETURNS boolean
LANGUAGE sql
STABLE
SECURITY INVOKER
SET search_path=public,pg_temp
AS $$
    SELECT requested_organization_id IS NOT NULL
       AND requested_actor_id IS NOT NULL
       AND requested_target_role_key IS NOT NULL
       AND requested_organization_id=
           nullif(current_setting('app.current_organization_id', true), '')::uuid
       AND requested_actor_id=
           nullif(current_setting('app.current_actor_id', true), '')::uuid
       AND EXISTS (
            SELECT 1
            FROM organization_memberships actor_membership
            JOIN authorization_roles actor_role
              ON actor_role.role_key=actor_membership.role_key
             AND actor_role.interactive
            JOIN authorization_role_delegations delegation
              ON delegation.delegator_role_key=actor_role.role_key
             AND delegation.target_role_key=requested_target_role_key
            JOIN authorization_roles target_role
              ON target_role.role_key=delegation.target_role_key
             AND target_role.status='active'
             AND target_role.interactive
             AND NOT target_role.final_owner
            WHERE actor_membership.organization_id=requested_organization_id
              AND actor_membership.user_id=requested_actor_id
              AND actor_membership.status='active'
              AND actor_membership.effective_from<=clock_timestamp()
              AND (actor_membership.effective_to IS NULL
                   OR actor_membership.effective_to>clock_timestamp())
              AND (
                  (actor_role.status='active' AND EXISTS (
                      SELECT 1
                      FROM authorization_registry_releases release
                      WHERE release.registry_version=actor_role.registry_version
                        AND release.status='active'))
                  OR (
                      actor_role.role_key='local_bootstrap'
                      AND actor_role.status='reference'
                      AND careos_reference_authorization_enabled()))
              AND EXISTS (
                  SELECT 1
                  FROM authorization_registry_releases release
                  WHERE release.registry_version=target_role.registry_version
                    AND release.status='active')
              AND EXISTS (
                  SELECT 1
                  FROM authorization_registry_releases release
                  WHERE release.registry_version=delegation.registry_version
                    AND release.status='active'));
$$;

REVOKE ALL ON FUNCTION careos_can_delegate_active_interactive_role(uuid,uuid,text)
    FROM PUBLIC;
GRANT EXECUTE ON FUNCTION careos_can_delegate_active_interactive_role(uuid,uuid,text)
    TO "${applicationRole}";

COMMENT ON FUNCTION careos_can_delegate_active_interactive_role(uuid,uuid,text) IS
    'Checks an active, released, non-owner interactive role delegation for the exact transaction tenant and actor; local reference authority additionally requires the V115 database capability.';

-- The final owner can initiate a governed promotion into the super-admin role.
INSERT INTO authorization_role_delegations
    (delegator_role_key, target_role_key, registry_version)
VALUES
    ('organization_owner', 'platform_super_administrator', 'platform-access-v1');

-- Super administrators can assign every approved non-owner human role through
-- the independently approved existing-membership flow, including another
-- super administrator. The synthetic local/UAT administrator receives the
-- same catalogue, but remains reference-only and capability-gated. Direct
-- invitation to the super-administrator role remains prohibited.
INSERT INTO authorization_role_delegations
    (delegator_role_key, target_role_key, registry_version)
SELECT delegator.role_key, target.role_key, 'platform-access-v1'
FROM (VALUES ('platform_super_administrator'), ('local_bootstrap')) delegator(role_key)
CROSS JOIN authorization_roles target
WHERE target.status='active'
  AND target.interactive
  AND NOT target.final_owner
  AND EXISTS (
      SELECT 1
      FROM authorization_registry_releases release
      WHERE release.registry_version=target.registry_version
        AND release.status='active')
ON CONFLICT (delegator_role_key, target_role_key) DO NOTHING;

UPDATE authorization_roles
SET display_name='Local/UAT super administrator',
    description='Synthetic capability-gated local/UAT administrator with the approved interactive permission surface; never production eligible.'
WHERE role_key='local_bootstrap'
  AND status='reference';

-- Invitation issuance remains reason-bound and recent-authentication protected.
-- Only the delegation predicate changes: roles from later approved module
-- registries can now be assigned without pretending they belong to M1.
CREATE OR REPLACE FUNCTION careos_validate_invitation_lifecycle()
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
BEGIN
    IF current_user <> '${applicationRole}' THEN
        RETURN NEW;
    END IF;

    IF NEW.organization_id IS DISTINCT FROM configured_organization THEN
        RAISE EXCEPTION 'invitation tenant does not match the authorized transaction'
            USING ERRCODE = '42501';
    END IF;

    IF TG_OP = 'INSERT' THEN
        IF configured_operation NOT IN ('identity.invitation.issue', 'organization.invitation.issue')
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
            WHERE target_role.role_key=NEW.role_key
              AND target_role.status='active'
              AND target_role.interactive
              AND target_role.invitation_assignable
              AND EXISTS (
                  SELECT 1
                  FROM authorization_registry_releases release
                  WHERE release.registry_version=target_role.registry_version
                    AND release.status='active'))
            OR NOT careos_can_delegate_active_interactive_role(
                NEW.organization_id, configured_actor, NEW.role_key) THEN
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

    IF configured_operation IN ('identity.invitation.revoke', 'organization.invitation.revoke') THEN
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
    ELSIF configured_operation IN ('identity.invitation.accept', 'organization.invitation.accept') THEN
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

-- Requests can target any active interactive role backed by an active release.
-- The existing approval evidence, self-change denial and final-owner protection
-- remain unchanged.
CREATE OR REPLACE FUNCTION careos_validate_membership_change_request_insert()
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
          AND membership_role.interactive
          AND membership_role.status = 'active'
          AND NOT membership_role.final_owner
          AND EXISTS (
              SELECT 1
              FROM authorization_registry_releases release
              WHERE release.registry_version=membership_role.registry_version
                AND release.status='active')
    ) THEN
        RAISE EXCEPTION 'membership change target is unavailable'
            USING ERRCODE = '42501';
    END IF;

    IF NEW.change_type = 'role_change'
       AND NOT careos_can_delegate_active_interactive_role(
           NEW.organization_id, configured_actor, NEW.to_role_key) THEN
        RAISE EXCEPTION 'membership role exceeds the actor delegation ceiling'
            USING ERRCODE = '42501';
    END IF;

    RETURN NEW;
END;
$$;

-- Preserve the V66 service-authorized offboarding branch exactly. Human role
-- changes now consume the same context-bound delegation predicate as requests.
CREATE OR REPLACE FUNCTION careos_validate_runtime_non_owner_membership_change()
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
    configured_offboarding uuid :=
        nullif(current_setting('app.current_offboarding_request_id', true), '')::uuid;
    requested_change membership_change_requests%ROWTYPE;
    approved_offboarding workforce_offboarding_requests%ROWTYPE;
    expected_effective_to timestamptz;
BEGIN
    IF current_user <> '${applicationRole}' THEN
        RETURN NEW;
    END IF;

    IF configured_operation='m2.offboarding.execute' THEN
        SELECT request.*
        INTO approved_offboarding
        FROM workforce_offboarding_requests request
        WHERE request.organization_id=OLD.organization_id
          AND request.id=configured_offboarding;

        IF NOT FOUND
            OR NOT careos_is_current_m2_offboarding_execution(configured_offboarding)
            OR configured_organization IS DISTINCT FROM OLD.organization_id
            OR configured_actor IS NULL
            OR approved_offboarding.access_action NOT IN
                ('revoke_at_effective','revoke_immediately')
            OR OLD.status<>'active'
            OR EXISTS (
                SELECT 1 FROM authorization_roles role
                WHERE role.role_key=OLD.role_key AND role.final_owner)
            OR NOT EXISTS (
                SELECT 1
                FROM access_assignment_scopes scope
                JOIN organization_memberships linked_membership
                  ON linked_membership.organization_id=scope.organization_id
                 AND linked_membership.id=scope.access_assignment_id
                WHERE scope.organization_id=OLD.organization_id
                  AND scope.workforce_member_id=approved_offboarding.workforce_member_id
                  AND scope.status IN ('approved','active')
                  AND linked_membership.user_id=OLD.user_id)
            OR EXISTS (
                SELECT 1
                FROM access_assignment_scopes other_scope
                JOIN organization_memberships other_membership
                  ON other_membership.organization_id=other_scope.organization_id
                 AND other_membership.id=other_scope.access_assignment_id
                JOIN workforce_members other_member
                  ON other_member.organization_id=other_scope.organization_id
                 AND other_member.id=other_scope.workforce_member_id
                WHERE other_scope.organization_id=OLD.organization_id
                  AND other_membership.user_id=OLD.user_id
                  AND other_scope.workforce_member_id<>
                      approved_offboarding.workforce_member_id
                  AND other_scope.status IN ('approved','active')
                  AND other_member.lifecycle_state<>'offboarded') THEN
            RAISE EXCEPTION 'membership is outside the approved offboarding plan'
                USING ERRCODE='42501';
        END IF;

        IF ROW(NEW.id,NEW.organization_id,NEW.user_id,NEW.role_key,NEW.effective_from)
           IS DISTINCT FROM
           ROW(OLD.id,OLD.organization_id,OLD.user_id,OLD.role_key,OLD.effective_from) THEN
            RAISE EXCEPTION 'offboarding cannot rewrite membership identity or role'
                USING ERRCODE='23514';
        END IF;

        expected_effective_to:=greatest(
            OLD.effective_from+interval '1 microsecond',
            approved_offboarding.effective_at);
        IF NEW.status<>'revoked'
            OR NEW.effective_to IS DISTINCT FROM expected_effective_to
            OR NEW.lock_version<>OLD.lock_version+1
            OR NEW.updated_by IS DISTINCT FROM approved_offboarding.checker_id THEN
            RAISE EXCEPTION 'membership revocation differs from the approved offboarding plan'
                USING ERRCODE='23514';
        END IF;
        NEW.updated_at:=greatest(clock_timestamp(),OLD.updated_at+interval '1 microsecond');
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
            OR NOT careos_can_delegate_active_interactive_role(
                OLD.organization_id, configured_actor, NEW.role_key) THEN
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

DO $$
BEGIN
    IF EXISTS (
        SELECT role_permission.permission_key
        FROM authorization_role_permissions role_permission
        WHERE role_permission.role_key='platform_super_administrator'
        EXCEPT
        SELECT DISTINCT role_permission.permission_key
        FROM authorization_role_permissions role_permission
        JOIN authorization_roles source_role
          ON source_role.role_key=role_permission.role_key
         AND source_role.status='active'
         AND source_role.interactive
        JOIN authorization_permissions permission
          ON permission.permission_key=role_permission.permission_key
         AND permission.status='active'
        WHERE source_role.role_key<>'platform_super_administrator'
    ) OR EXISTS (
        SELECT DISTINCT role_permission.permission_key
        FROM authorization_role_permissions role_permission
        JOIN authorization_roles source_role
          ON source_role.role_key=role_permission.role_key
         AND source_role.status='active'
         AND source_role.interactive
        JOIN authorization_permissions permission
          ON permission.permission_key=role_permission.permission_key
         AND permission.status='active'
        WHERE source_role.role_key<>'platform_super_administrator'
        EXCEPT
        SELECT role_permission.permission_key
        FROM authorization_role_permissions role_permission
        WHERE role_permission.role_key='platform_super_administrator'
    ) THEN
        RAISE EXCEPTION 'platform super administrator permission composition is incomplete';
    END IF;
END;
$$;

COMMENT ON TABLE authorization_role_delegations IS
    'Explicit cross-release role assignment ceilings. Every delegator, target and delegation release must be active; absence denies delegation.';
