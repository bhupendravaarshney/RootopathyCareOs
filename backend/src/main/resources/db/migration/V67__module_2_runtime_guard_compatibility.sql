-- Forward-only repairs for nullable operation guards and heterogeneous readiness
-- trigger records. Earlier migration checksums remain immutable for databases that
-- have already applied Module 2.

CREATE OR REPLACE FUNCTION careos_validate_m2_person_write()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    configured_actor uuid :=
        nullif(current_setting('app.current_actor_id', true), '')::uuid;
    configured_operation text :=
        nullif(current_setting('app.current_operation_key', true), '');
BEGIN
    IF current_user <> '${applicationRole}' THEN
        RETURN NEW;
    END IF;
    IF configured_actor IS NULL
       OR configured_operation IS NULL
       OR configured_operation NOT IN
          ('workforce.member.create','workforce.person.match',
           'workforce.person.correct','workforce.member.manage') THEN
        RAISE EXCEPTION 'invalid Module 2 person write context'
            USING ERRCODE = '42501';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.created_by IS DISTINCT FROM configured_actor
           OR NEW.updated_by IS DISTINCT FROM configured_actor
           OR NEW.lock_version <> 0 THEN
            RAISE EXCEPTION 'invalid person creation evidence'
                USING ERRCODE = '23514';
        END IF;
    ELSE
        IF NEW.id IS DISTINCT FROM OLD.id
           OR NEW.created_at IS DISTINCT FROM OLD.created_at
           OR NEW.created_by IS DISTINCT FROM OLD.created_by
           OR NEW.updated_by IS DISTINCT FROM configured_actor
           OR NEW.lock_version <> OLD.lock_version + 1 THEN
            RAISE EXCEPTION 'invalid person revision evidence'
                USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

-- Shared trigger functions must not dereference a column that is absent from one
-- of their source tables. A JSON row image makes each table-specific lookup
-- explicit and preserves UUID validation at the cast boundary.
CREATE OR REPLACE FUNCTION careos_invalidate_readiness_from_practitioner_source()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    affected_row jsonb;
    affected_organization_id uuid;
    affected_practitioner_id uuid;
    affected_member_id uuid;
BEGIN
    affected_row:=CASE WHEN TG_OP='DELETE' THEN to_jsonb(OLD) ELSE to_jsonb(NEW) END;
    affected_organization_id:=(affected_row->>'organization_id')::uuid;
    IF TG_TABLE_NAME='practitioner_credentials' THEN
        affected_member_id:=(affected_row->>'workforce_member_id')::uuid;
        affected_practitioner_id:=(affected_row->>'practitioner_profile_id')::uuid;
    ELSE
        affected_practitioner_id:=(affected_row->>(
            CASE WHEN TG_TABLE_NAME='practitioner_profiles'
                THEN 'id' ELSE 'practitioner_profile_id' END))::uuid;
        IF TG_TABLE_NAME='practitioner_profiles' THEN
            affected_member_id:=(affected_row->>'workforce_member_id')::uuid;
        END IF;
    END IF;
    IF affected_member_id IS NULL THEN
        SELECT workforce_member_id INTO affected_member_id
          FROM practitioner_profiles
         WHERE organization_id=affected_organization_id
           AND id=affected_practitioner_id;
    END IF;
    PERFORM careos_invalidate_member_readiness(
        affected_organization_id,affected_member_id,'evaluated_state_changed');
    RETURN NULL;
END;
$$;

CREATE OR REPLACE FUNCTION careos_invalidate_readiness_from_scope_source()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    affected_row jsonb;
    affected_organization_id uuid;
    affected_scope_id uuid;
    affected_member_id uuid;
BEGIN
    affected_row:=CASE WHEN TG_OP='DELETE' THEN to_jsonb(OLD) ELSE to_jsonb(NEW) END;
    affected_organization_id:=(affected_row->>'organization_id')::uuid;
    affected_scope_id:=(affected_row->>(
        CASE WHEN TG_TABLE_NAME='scopes_of_practice'
            THEN 'id' ELSE 'scope_of_practice_id' END))::uuid;
    SELECT practitioner.workforce_member_id INTO affected_member_id
      FROM scopes_of_practice scope
      JOIN practitioner_profiles practitioner
        ON practitioner.organization_id=scope.organization_id
       AND practitioner.id=scope.practitioner_profile_id
     WHERE scope.organization_id=affected_organization_id
       AND scope.id=affected_scope_id;
    PERFORM careos_invalidate_member_readiness(
        affected_organization_id,affected_member_id,'evaluated_state_changed');
    RETURN NULL;
END;
$$;

CREATE OR REPLACE FUNCTION careos_invalidate_readiness_from_person_source()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    affected_row jsonb;
    affected_person_id uuid;
    member_record record;
BEGIN
    affected_row:=CASE WHEN TG_OP='DELETE' THEN to_jsonb(OLD) ELSE to_jsonb(NEW) END;
    affected_person_id:=(affected_row->>(
        CASE WHEN TG_TABLE_NAME='person_profiles' THEN 'id' ELSE 'person_id' END))::uuid;
    FOR member_record IN
        SELECT member.organization_id,member.id
          FROM organization_person_links link
          JOIN workforce_members member
            ON member.organization_id=link.organization_id
           AND member.organization_person_link_id=link.id
         WHERE link.person_id=affected_person_id
           AND link.organization_id=
               nullif(current_setting('app.current_organization_id',true),'')::uuid
    LOOP
        PERFORM careos_invalidate_member_readiness(
            member_record.organization_id,member_record.id,'identity_state_changed');
    END LOOP;
    RETURN NULL;
END;
$$;

CREATE OR REPLACE FUNCTION careos_invalidate_readiness_from_credential_evidence()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    affected_row jsonb;
    affected_organization_id uuid;
    affected_credential_id uuid;
    affected_document_id uuid;
    affected_member_id uuid;
BEGIN
    affected_row:=CASE WHEN TG_OP='DELETE' THEN to_jsonb(OLD) ELSE to_jsonb(NEW) END;
    affected_organization_id:=(affected_row->>'organization_id')::uuid;
    affected_credential_id:=(affected_row->>'practitioner_credential_id')::uuid;
    affected_document_id:=(affected_row->>'credential_document_id')::uuid;
    IF affected_credential_id IS NULL AND affected_document_id IS NOT NULL THEN
        SELECT practitioner_credential_id INTO affected_credential_id
          FROM credential_documents
         WHERE organization_id=affected_organization_id
           AND id=affected_document_id;
    END IF;
    SELECT workforce_member_id INTO affected_member_id
      FROM practitioner_credentials
     WHERE organization_id=affected_organization_id
       AND id=affected_credential_id;
    PERFORM careos_invalidate_member_readiness(
        affected_organization_id,affected_member_id,'credential_evidence_changed');
    RETURN NULL;
END;
$$;

CREATE OR REPLACE FUNCTION careos_invalidate_readiness_from_organization_source()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    affected_row jsonb;
    affected_organization_id uuid;
BEGIN
    affected_row:=CASE WHEN TG_OP='DELETE' THEN to_jsonb(OLD) ELSE to_jsonb(NEW) END;
    affected_organization_id:=(affected_row->>(
        CASE WHEN TG_TABLE_NAME='organizations' THEN 'id' ELSE 'organization_id' END))::uuid;
    PERFORM careos_invalidate_organization_readiness(
        affected_organization_id,'organization_dependency_changed');
    RETURN NULL;
END;
$$;

-- Ordinary identity operations may leave the operation setting unset. They must
-- bypass offboarding-only guards, while the exact worker binding remains strict.
CREATE OR REPLACE FUNCTION careos_guard_offboarding_account_invalidation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    request_id uuid:=
        nullif(current_setting('app.current_offboarding_request_id',true),'')::uuid;
BEGIN
    IF nullif(current_setting('app.current_operation_key',true),'')
       IS DISTINCT FROM 'm2.offboarding.execute' THEN
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

CREATE OR REPLACE FUNCTION careos_guard_offboarding_session_revocation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    request_id uuid:=
        nullif(current_setting('app.current_offboarding_request_id',true),'')::uuid;
    effective_time timestamptz;
BEGIN
    IF nullif(current_setting('app.current_operation_key',true),'')
       IS DISTINCT FROM 'm2.offboarding.execute' THEN
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

CREATE OR REPLACE FUNCTION careos_guard_offboarding_scope_closure()
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
    IF nullif(current_setting('app.current_operation_key',true),'')
       IS DISTINCT FROM 'm2.offboarding.execute' THEN
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

CREATE OR REPLACE FUNCTION careos_validate_offboarding_scope_access_effects()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    request_id uuid:=
        nullif(current_setting('app.current_offboarding_request_id',true),'')::uuid;
    approved_offboarding workforce_offboarding_requests%ROWTYPE;
    target_user_id uuid;
BEGIN
    IF nullif(current_setting('app.current_operation_key',true),'')
       IS DISTINCT FROM 'm2.offboarding.execute' THEN
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

CREATE OR REPLACE FUNCTION careos_guard_offboarding_request_lifecycle()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    operation_key text:=nullif(current_setting('app.current_operation_key',true),'');
    request_id uuid:=
        nullif(current_setting('app.current_offboarding_request_id',true),'')::uuid;
BEGIN
    IF TG_OP='INSERT' THEN
        IF operation_key IS DISTINCT FROM 'workforce.offboarding.request'
           OR NEW.status<>'submitted'
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

COMMENT ON FUNCTION careos_validate_m2_person_write() IS
    'Requires an explicit approved Module 2 operation for every application-role person write, including when the session setting is absent.';
COMMENT ON FUNCTION careos_invalidate_readiness_from_organization_source() IS
    'Invalidates organization readiness using a table-safe JSON row image across organization and organization-owned sources.';
COMMENT ON FUNCTION careos_guard_offboarding_account_invalidation() IS
    'Restricts account invalidation only during an exact M2 offboarding execution and bypasses unrelated identity operations null-safely.';
