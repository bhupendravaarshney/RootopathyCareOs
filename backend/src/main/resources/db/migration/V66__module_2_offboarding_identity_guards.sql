-- The M2 offboarding worker is allowed to apply only the canonical M1 child
-- effects frozen into one approved, due plan.  Keep this check behind a
-- security-definer predicate because the runtime role intentionally cannot
-- browse provisioned service identities.
CREATE OR REPLACE FUNCTION careos_project_governance_actor_kind()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    configured_kind text := coalesce(
        nullif(current_setting('app.current_actor_kind',true),''),'user');
BEGIN
    NEW.actor_kind := configured_kind;
    IF configured_kind='user' AND NOT EXISTS (
        SELECT 1 FROM public.users WHERE id=NEW.actor_user_id
    ) THEN
        RAISE EXCEPTION 'governance user actor is unavailable' USING ERRCODE='23503';
    ELSIF configured_kind='service' AND NOT EXISTS (
        SELECT 1 FROM public.service_identities
        WHERE id=NEW.actor_user_id AND organization_id=NEW.organization_id
    ) THEN
        RAISE EXCEPTION 'governance service actor is unavailable' USING ERRCODE='23503';
    END IF;
    RETURN NEW;
END;
$$;

REVOKE ALL ON FUNCTION careos_project_governance_actor_kind() FROM PUBLIC;

CREATE FUNCTION careos_is_current_m2_offboarding_execution(candidate_request_id uuid)
RETURNS boolean
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
    SELECT
        candidate_request_id IS NOT NULL
        AND nullif(current_setting('app.current_operation_key',true),'') =
            'm2.offboarding.execute'
        AND nullif(current_setting('app.current_actor_kind',true),'') = 'service'
        AND nullif(current_setting('app.current_purpose',true),'') =
            'm2-offboarding-worker-v1'
        AND nullif(current_setting('app.current_offboarding_request_id',true),'')::uuid =
            candidate_request_id
        AND EXISTS (
            SELECT 1
            FROM public.workforce_offboarding_requests request
            JOIN public.service_identities identity
              ON identity.organization_id=request.organization_id
             AND identity.id=nullif(current_setting('app.current_actor_id',true),'')::uuid
            WHERE request.id=candidate_request_id
              AND request.organization_id=
                  nullif(current_setting('app.current_organization_id',true),'')::uuid
              AND request.status IN ('approved','scheduled','failed','completed')
              AND request.checker_id IS NOT NULL
              AND request.effective_at<=statement_timestamp()
              AND identity.role_key='service_m2_offboarding'
              AND identity.status='active'
              AND identity.active_from<=statement_timestamp()
              AND (identity.expires_at IS NULL OR identity.expires_at>statement_timestamp())
              AND 'm2-offboarding-worker-v1'=ANY(identity.allowed_purposes)
        );
$$;

REVOKE ALL ON FUNCTION careos_is_current_m2_offboarding_execution(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION careos_is_current_m2_offboarding_execution(uuid)
    TO "${applicationRole}";

-- Preserve the approved M1 membership-change path and add one narrow branch
-- for a service-authorized, checksum-approved offboarding plan.  The branch
-- cannot touch a final owner, broaden the target, alter role identity, or use
-- a child effect that differs from the frozen plan.
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

-- An offboarding update to a global account can invalidate sessions but cannot
-- disable the account or alter identity data.  Advancing security_version makes
-- already-loaded Redis sessions fail on their next request.
CREATE FUNCTION careos_guard_offboarding_account_invalidation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    request_id uuid:=
        nullif(current_setting('app.current_offboarding_request_id',true),'')::uuid;
BEGIN
    IF nullif(current_setting('app.current_operation_key',true),'')<>
        'm2.offboarding.execute' THEN
        RETURN NEW;
    END IF;
    IF NOT careos_is_current_m2_offboarding_execution(request_id)
       OR NOT EXISTS (
           SELECT 1
           FROM workforce_offboarding_requests request
           JOIN access_assignment_scopes scope
             ON scope.organization_id=request.organization_id
            AND scope.workforce_member_id=request.workforce_member_id
            AND scope.status IN ('approved','active')
           JOIN organization_memberships membership
             ON membership.organization_id=scope.organization_id
            AND membership.id=scope.access_assignment_id
           WHERE request.id=request_id AND membership.user_id=OLD.id)
       OR ROW(NEW.id,NEW.email,NEW.display_name,NEW.status,NEW.created_at,NEW.lock_version)
          IS DISTINCT FROM
          ROW(OLD.id,OLD.email,OLD.display_name,OLD.status,OLD.created_at,OLD.lock_version)
       OR OLD.status<>'active'
       OR NEW.security_version<>OLD.security_version+1
       OR NEW.updated_at<=OLD.updated_at THEN
        RAISE EXCEPTION 'account invalidation differs from the approved offboarding plan'
            USING ERRCODE='42501';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER users_guard_offboarding_invalidation
    BEFORE UPDATE ON users
    FOR EACH ROW EXECUTE FUNCTION careos_guard_offboarding_account_invalidation();

-- Session metadata is global to an account.  Permit only a monotonic revocation
-- for an account linked by the exact plan; all authentication and expiry fields
-- remain immutable under the worker operation.
CREATE FUNCTION careos_guard_offboarding_session_revocation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    request_id uuid:=
        nullif(current_setting('app.current_offboarding_request_id',true),'')::uuid;
    effective_time timestamptz;
BEGIN
    IF nullif(current_setting('app.current_operation_key',true),'')<>
        'm2.offboarding.execute' THEN
        RETURN NEW;
    END IF;
    SELECT request.effective_at INTO effective_time
    FROM workforce_offboarding_requests request
    WHERE request.id=request_id;
    IF NOT FOUND
       OR NOT careos_is_current_m2_offboarding_execution(request_id)
       OR NOT EXISTS (
           SELECT 1
           FROM workforce_offboarding_requests request
           JOIN access_assignment_scopes scope
             ON scope.organization_id=request.organization_id
            AND scope.workforce_member_id=request.workforce_member_id
            AND scope.status IN ('approved','active')
           JOIN organization_memberships membership
             ON membership.organization_id=scope.organization_id
            AND membership.id=scope.access_assignment_id
           WHERE request.id=request_id AND membership.user_id=OLD.user_id)
       OR OLD.revoked_at IS NOT NULL
       OR ROW(NEW.session_id_hash,NEW.user_id,NEW.authenticated_at,
              NEW.mfa_authenticated_at,NEW.recent_authentication_at,
              NEW.absolute_expires_at,NEW.correlation_id,NEW.created_at)
          IS DISTINCT FROM
          ROW(OLD.session_id_hash,OLD.user_id,OLD.authenticated_at,
              OLD.mfa_authenticated_at,OLD.recent_authentication_at,
              OLD.absolute_expires_at,OLD.correlation_id,OLD.created_at)
       OR NEW.revocation_reason<>'workforce_offboarding'
       OR NEW.revoked_at IS NULL
       OR NEW.revoked_at<effective_time
       OR NEW.revoked_at>clock_timestamp()+interval '5 seconds' THEN
        RAISE EXCEPTION 'session revocation differs from the approved offboarding plan'
            USING ERRCODE='42501';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER user_sessions_guard_offboarding_revocation
    BEFORE UPDATE OF revoked_at,revocation_reason ON user_sessions
    FOR EACH ROW EXECUTE FUNCTION careos_guard_offboarding_session_revocation();

-- Retain the pre-change scope state in a row trigger.  That distinction is
-- material: requested scopes are cancelled as domain work, but only an
-- approved/active scope proves that canonical account and session access was
-- actually linked to the departing member.
CREATE FUNCTION careos_guard_offboarding_scope_closure()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    request_id uuid:=
        nullif(current_setting('app.current_offboarding_request_id',true),'')::uuid;
    configured_actor uuid:=
        nullif(current_setting('app.current_actor_id',true),'')::uuid;
    approved_offboarding workforce_offboarding_requests%ROWTYPE;
    expected_status text;
    expected_effective_to timestamptz;
BEGIN
    IF nullif(current_setting('app.current_operation_key',true),'')<>
        'm2.offboarding.execute' THEN
        RETURN NEW;
    END IF;
    SELECT request.* INTO approved_offboarding
    FROM workforce_offboarding_requests request
    WHERE request.id=request_id;
    expected_status:=CASE WHEN OLD.effective_from>=approved_offboarding.effective_at
        THEN 'cancelled' ELSE 'ended' END;
    expected_effective_to:=CASE WHEN OLD.effective_from>=approved_offboarding.effective_at
        THEN OLD.effective_to
        ELSE least(coalesce(OLD.effective_to,approved_offboarding.effective_at),
                   approved_offboarding.effective_at) END;
    IF NOT FOUND
       OR NOT careos_is_current_m2_offboarding_execution(request_id)
       OR OLD.organization_id<>approved_offboarding.organization_id
       OR OLD.workforce_member_id<>approved_offboarding.workforce_member_id
       OR OLD.status NOT IN ('requested','approved','active')
       OR ROW(NEW.id,NEW.organization_id,NEW.access_assignment_id,
              NEW.workforce_member_id,NEW.facility_id,NEW.organization_unit_id,
              NEW.location_id,NEW.grant_request_id,NEW.approval_reference_id,
              NEW.effective_from,NEW.created_at,NEW.created_by)
          IS DISTINCT FROM
          ROW(OLD.id,OLD.organization_id,OLD.access_assignment_id,
              OLD.workforce_member_id,OLD.facility_id,OLD.organization_unit_id,
              OLD.location_id,OLD.grant_request_id,OLD.approval_reference_id,
              OLD.effective_from,OLD.created_at,OLD.created_by)
       OR NEW.status<>expected_status
       OR NEW.effective_to IS DISTINCT FROM expected_effective_to
       OR NEW.lock_version<>OLD.lock_version+1
       OR NEW.updated_by IS DISTINCT FROM configured_actor
       OR NEW.updated_at<=OLD.updated_at
       OR NEW.updated_at<transaction_timestamp() THEN
        RAISE EXCEPTION 'access scope closure differs from the approved offboarding plan'
            USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER access_assignment_scopes_guard_offboarding_closure
    BEFORE UPDATE ON access_assignment_scopes
    FOR EACH ROW EXECUTE FUNCTION careos_guard_offboarding_scope_closure();

CREATE FUNCTION careos_validate_offboarding_scope_access_effects()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    request_id uuid:=
        nullif(current_setting('app.current_offboarding_request_id',true),'')::uuid;
    approved_offboarding workforce_offboarding_requests%ROWTYPE;
    target_user_id uuid;
BEGIN
    IF nullif(current_setting('app.current_operation_key',true),'')<>
        'm2.offboarding.execute' THEN
        RETURN NULL;
    END IF;
    SELECT request.* INTO approved_offboarding
    FROM workforce_offboarding_requests request
    WHERE request.id=request_id;
    SELECT membership.user_id INTO target_user_id
    FROM organization_memberships membership
    WHERE membership.organization_id=OLD.organization_id
      AND membership.id=OLD.access_assignment_id;
    IF NOT FOUND
       OR NOT careos_is_current_m2_offboarding_execution(request_id)
       OR approved_offboarding.status<>'completed'
       OR OLD.organization_id<>approved_offboarding.organization_id
       OR OLD.workforce_member_id<>approved_offboarding.workforce_member_id
       OR NOT EXISTS (
           SELECT 1 FROM users account
           WHERE account.id=target_user_id
             AND account.status='active'
             AND account.updated_at>=transaction_timestamp())
       OR EXISTS (
           SELECT 1 FROM user_sessions user_session
           WHERE user_session.user_id=target_user_id
             AND user_session.revoked_at IS NULL)
       OR (approved_offboarding.access_action<>'none' AND EXISTS (
           SELECT 1 FROM organization_memberships membership
           WHERE membership.organization_id=approved_offboarding.organization_id
             AND membership.user_id=target_user_id
             AND membership.status='active')) THEN
        RAISE EXCEPTION 'completed offboarding left canonical access or sessions active'
            USING ERRCODE='23514';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER access_assignment_scope_offboarding_children
    AFTER UPDATE ON access_assignment_scopes
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    WHEN (OLD.status IN ('approved','active')
          AND NEW.status IN ('ended','cancelled'))
    EXECUTE FUNCTION careos_validate_offboarding_scope_access_effects();

-- Evidence for each canonical membership child effect uses the already-approved
-- M1 schema.  Only this exact worker operation receives the additional mapping.
INSERT INTO authorization_operation_events
    (operation_key,event_kind,event_name,schema_version,status,registry_version)
VALUES
    ('m2.offboarding.execute','audit','identity.membership.revoked',1,
     'active','m2-candidate-1'),
    ('m2.offboarding.execute','outbox','identity.membership.revoked',1,
     'active','m2-candidate-1')
ON CONFLICT DO NOTHING;

-- Completion is checked at commit so the worker can write its audit/outbox
-- evidence after applying the state changes while retaining one atomic unit.
CREATE FUNCTION careos_validate_completed_offboarding_children()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    configured_actor uuid:=
        nullif(current_setting('app.current_actor_id',true),'')::uuid;
    configured_correlation text:=
        nullif(current_setting('app.current_correlation_id',true),'');
    expected_memberships integer;
    audit_memberships integer;
    outbox_memberships integer;
BEGIN
    IF NEW.status<>'completed' THEN
        RETURN NULL;
    END IF;
    IF NOT careos_is_current_m2_offboarding_execution(NEW.id)
       OR NOT EXISTS (
           SELECT 1 FROM workforce_members member
           WHERE member.organization_id=NEW.organization_id
             AND member.id=NEW.workforce_member_id
             AND member.lifecycle_state='offboarded'
             AND member.offboarded_at=NEW.effective_at)
       OR NOT EXISTS (
            SELECT 1 FROM workforce_lifecycle_transitions lifecycle_transition
            WHERE lifecycle_transition.organization_id=NEW.organization_id
              AND lifecycle_transition.workforce_member_id=NEW.workforce_member_id
              AND lifecycle_transition.from_state='offboarding'
              AND lifecycle_transition.to_state='offboarded'
              AND lifecycle_transition.source_request_type='offboarding_request'
              AND lifecycle_transition.source_request_id=NEW.id)
       OR EXISTS (
           SELECT 1 FROM employment_engagements engagement
           WHERE engagement.organization_id=NEW.organization_id
             AND engagement.workforce_member_id=NEW.workforce_member_id
             AND engagement.status IN ('draft','scheduled','active','suspended'))
       OR EXISTS (
           SELECT 1 FROM workforce_assignments assignment
           WHERE assignment.organization_id=NEW.organization_id
             AND assignment.workforce_member_id=NEW.workforce_member_id
             AND assignment.lifecycle_state IN ('draft','scheduled','active','suspended'))
       OR EXISTS (
           SELECT 1
           FROM practitioner_service_assignments assignment
           JOIN practitioner_profiles practitioner
             ON practitioner.organization_id=assignment.organization_id
            AND practitioner.id=assignment.practitioner_profile_id
           WHERE assignment.organization_id=NEW.organization_id
             AND practitioner.workforce_member_id=NEW.workforce_member_id
             AND assignment.lifecycle_state IN ('draft','scheduled','active','suspended'))
       OR EXISTS (
           SELECT 1 FROM practitioner_profiles practitioner
           WHERE practitioner.organization_id=NEW.organization_id
             AND practitioner.workforce_member_id=NEW.workforce_member_id
             AND practitioner.lifecycle_state IN ('active','suspended'))
       OR EXISTS (
           SELECT 1 FROM availability_profiles availability
           WHERE availability.organization_id=NEW.organization_id
             AND availability.workforce_member_id=NEW.workforce_member_id
             AND availability.lifecycle_state IN ('draft','scheduled','active'))
       OR EXISTS (
           SELECT 1 FROM access_assignment_scopes scope
           WHERE scope.organization_id=NEW.organization_id
             AND scope.workforce_member_id=NEW.workforce_member_id
             AND scope.status IN ('requested','approved','active')) THEN
        RAISE EXCEPTION 'completed offboarding is missing an atomic domain child effect'
            USING ERRCODE='23514';
    END IF;

    SELECT count(*) INTO expected_memberships
    FROM organization_memberships membership
    WHERE membership.organization_id=NEW.organization_id
      AND membership.status='revoked'
      AND membership.updated_by=NEW.checker_id
      AND membership.updated_at>=transaction_timestamp();

    SELECT count(*) INTO audit_memberships
    FROM audit_events event
    WHERE event.organization_id=NEW.organization_id
      AND event.actor_user_id=configured_actor
      AND event.actor_kind='service'
      AND event.correlation_id=configured_correlation
      AND event.event_name='identity.membership.revoked'
      AND event.payload->>'approvalId'=NEW.id::text;
    SELECT count(*) INTO outbox_memberships
    FROM outbox_events event
    WHERE event.organization_id=NEW.organization_id
      AND event.actor_user_id=configured_actor
      AND event.actor_kind='service'
      AND event.correlation_id=configured_correlation
      AND event.event_name='identity.membership.revoked'
      AND event.payload->>'approvalId'=NEW.id::text;

    IF audit_memberships<>expected_memberships
       OR outbox_memberships<>expected_memberships
       OR EXISTS (
           SELECT 1
           FROM organization_memberships membership
           WHERE membership.organization_id=NEW.organization_id
             AND membership.status='revoked'
             AND membership.updated_by=NEW.checker_id
             AND membership.updated_at>=transaction_timestamp()
             AND (NOT EXISTS (
                 SELECT 1 FROM audit_events event
                 WHERE event.organization_id=NEW.organization_id
                   AND event.event_name='identity.membership.revoked'
                   AND event.subject_type='membership'
                   AND event.subject_id=membership.id
                   AND event.payload->>'approvalId'=NEW.id::text
                   AND event.payload->>'membershipId'=membership.id::text
                   AND event.payload->>'lockVersion'=membership.lock_version::text)
               OR NOT EXISTS (
                 SELECT 1 FROM outbox_events event
                 WHERE event.organization_id=NEW.organization_id
                   AND event.event_name='identity.membership.revoked'
                   AND event.aggregate_type='membership'
                   AND event.aggregate_id=membership.id
                   AND event.payload->>'approvalId'=NEW.id::text
                   AND event.payload->>'membershipId'=membership.id::text
                   AND event.payload->>'lockVersion'=membership.lock_version::text)))
       OR NOT EXISTS (
           SELECT 1 FROM audit_events event
           WHERE event.organization_id=NEW.organization_id
             AND event.actor_user_id=configured_actor
             AND event.correlation_id=configured_correlation
             AND event.event_name='workforce.offboarding.started'
             AND event.subject_id=NEW.id
             AND event.payload->>'state'='started')
       OR NOT EXISTS (
           SELECT 1 FROM audit_events event
           WHERE event.organization_id=NEW.organization_id
             AND event.actor_user_id=configured_actor
             AND event.correlation_id=configured_correlation
             AND event.event_name='workforce.offboarding.completed'
             AND event.subject_id=NEW.id
             AND event.payload->>'state'='completed')
       OR NOT EXISTS (
           SELECT 1 FROM outbox_events event
           WHERE event.organization_id=NEW.organization_id
             AND event.actor_user_id=configured_actor
             AND event.correlation_id=configured_correlation
             AND event.event_name='workforce.offboarding.completed'
             AND event.aggregate_id=NEW.id
             AND event.payload->>'state'='completed') THEN
        RAISE EXCEPTION 'completed offboarding lacks deterministic child evidence'
            USING ERRCODE='23514';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER workforce_offboarding_completion_children
    AFTER UPDATE ON workforce_offboarding_requests
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    WHEN (NEW.status='completed' AND OLD.status<>'completed')
    EXECUTE FUNCTION careos_validate_completed_offboarding_children();

-- Tighten the request state guard after the child boundary exists.  A direct
-- SQL caller cannot complete or record a worker failure early or without the
-- exact service identity/request binding.
CREATE OR REPLACE FUNCTION careos_guard_offboarding_request_lifecycle()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    operation_key text:=nullif(current_setting('app.current_operation_key',true),'');
    request_id uuid:=nullif(current_setting('app.current_offboarding_request_id',true),'')::uuid;
BEGIN
    IF TG_OP='INSERT' THEN
        IF operation_key<>'workforce.offboarding.request' OR NEW.status<>'submitted'
           OR NEW.checker_id IS NOT NULL OR NEW.failure_code IS NOT NULL
           OR NEW.attempt_count<>0 OR NEW.next_attempt_at IS NOT NULL
           OR NEW.dead_lettered_at IS NOT NULL OR NEW.dead_letter_owner IS NOT NULL THEN
            RAISE EXCEPTION 'invalid offboarding request creation'
                USING ERRCODE='23514';
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.status IN ('completed','cancelled') OR OLD.dead_lettered_at IS NOT NULL THEN
        RAISE EXCEPTION 'terminal offboarding request is immutable'
            USING ERRCODE='55000';
    END IF;
    IF ROW(NEW.workforce_member_id,NEW.engagement_end_at,NEW.effective_at,
           NEW.reason_entry_id,NEW.reason_version_id,NEW.impact_digest,
           NEW.maker_id,NEW.access_action,NEW.assignment_action,
           NEW.service_action,NEW.handover_reference)
       IS DISTINCT FROM
       ROW(OLD.workforce_member_id,OLD.engagement_end_at,OLD.effective_at,
           OLD.reason_entry_id,OLD.reason_version_id,OLD.impact_digest,
           OLD.maker_id,OLD.access_action,OLD.assignment_action,
           OLD.service_action,OLD.handover_reference) THEN
        RAISE EXCEPTION 'offboarding plan evidence is immutable'
            USING ERRCODE='55000';
    END IF;
    IF NOT (
        (OLD.status='submitted' AND NEW.status IN ('approved','scheduled')
         AND operation_key='workforce.offboarding.approve'
         AND OLD.checker_id IS NULL AND NEW.checker_id IS NOT NULL
         AND NEW.checker_id<>OLD.maker_id
         AND NEW.failure_code IS NULL AND NEW.attempt_count=OLD.attempt_count
         AND ((NEW.status='scheduled' AND NEW.effective_at>statement_timestamp())
           OR (NEW.status='approved' AND NEW.effective_at<=statement_timestamp()))) OR
        (OLD.status IN ('approved','scheduled','failed') AND NEW.status='failed'
         AND operation_key='m2.offboarding.execute'
         AND request_id=OLD.id
         AND careos_is_current_m2_offboarding_execution(OLD.id)
         AND NEW.checker_id=OLD.checker_id
         AND NEW.attempt_count=LEAST(OLD.attempt_count+1,5)
         AND NEW.failure_code IS NOT NULL
         AND ((NEW.attempt_count<5 AND NEW.next_attempt_at IS NOT NULL
                                    AND NEW.dead_lettered_at IS NULL
                                    AND NEW.dead_letter_owner IS NULL)
           OR (NEW.attempt_count=5 AND NEW.next_attempt_at IS NULL
                                    AND NEW.dead_lettered_at IS NOT NULL
                                    AND NEW.dead_letter_owner='workforce_operations'))) OR
        (OLD.status IN ('approved','scheduled','failed') AND NEW.status='completed'
         AND operation_key='m2.offboarding.execute'
         AND request_id=OLD.id
         AND careos_is_current_m2_offboarding_execution(OLD.id)
         AND NEW.checker_id=OLD.checker_id
         AND NEW.attempt_count=OLD.attempt_count
         AND NEW.failure_code IS NULL AND NEW.next_attempt_at IS NULL
         AND NEW.dead_lettered_at IS NULL AND NEW.dead_letter_owner IS NULL)
    ) THEN
        RAISE EXCEPTION 'm2.lifecycle.invalid_transition' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;

REVOKE ALL ON FUNCTION careos_guard_offboarding_account_invalidation() FROM PUBLIC;
REVOKE ALL ON FUNCTION careos_guard_offboarding_session_revocation() FROM PUBLIC;
REVOKE ALL ON FUNCTION careos_guard_offboarding_scope_closure() FROM PUBLIC;
REVOKE ALL ON FUNCTION careos_validate_offboarding_scope_access_effects() FROM PUBLIC;
REVOKE ALL ON FUNCTION careos_validate_completed_offboarding_children() FROM PUBLIC;

COMMENT ON FUNCTION careos_is_current_m2_offboarding_execution(uuid) IS
    'Confirms the exact tenant, service identity, purpose, operation, due request, and request transaction binding for an M2 offboarding execution.';
COMMENT ON FUNCTION careos_validate_completed_offboarding_children() IS
    'Defers completion validation until all atomic domain, canonical access, session, and approved audit/outbox child evidence is present.';
COMMENT ON FUNCTION careos_validate_offboarding_scope_access_effects() IS
    'Uses each approved/active scope pre-image to require same-transaction account and session invalidation at offboarding commit.';
