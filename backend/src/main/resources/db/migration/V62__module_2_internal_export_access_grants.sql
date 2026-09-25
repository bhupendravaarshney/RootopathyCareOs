-- Workforce export artifacts are downloaded through an authenticated CareOS endpoint.
-- The short-lived grant is bound to the requester, tenant, export, and immutable
-- artifact digest; provider object paths and signed URLs never cross the API boundary.
ALTER TABLE workforce_export_jobs
    ADD COLUMN retry_of_export_id uuid,
    ADD COLUMN access_granted_to uuid,
    ADD COLUMN access_grant_digest char(64),
    ADD COLUMN access_granted_at timestamptz,
    ADD COLUMN access_grant_expires_at timestamptz,
    ADD CONSTRAINT workforce_export_retry_source_fk
        FOREIGN KEY (organization_id,retry_of_export_id)
        REFERENCES workforce_export_jobs(organization_id,id),
    ADD CONSTRAINT workforce_export_retry_shape_check CHECK (
        retry_of_export_id IS NULL OR retry_of_export_id<>id
    ),
    ADD CONSTRAINT workforce_export_access_grant_shape_check CHECK (
        (
            access_granted_to IS NULL
            AND access_grant_digest IS NULL
            AND access_granted_at IS NULL
            AND access_grant_expires_at IS NULL
        ) OR (
            access_granted_to=requester_id
            AND access_grant_digest IS NOT NULL
            AND artifact_digest IS NOT NULL
            AND access_grant_digest=artifact_digest
            AND access_grant_digest ~ '^[0-9a-f]{64}$'
            AND access_granted_at IS NOT NULL
            AND access_grant_expires_at IS NOT NULL
            AND expires_at IS NOT NULL
            AND access_grant_expires_at>access_granted_at
            AND access_grant_expires_at<=expires_at
        )
    );

CREATE INDEX workforce_export_access_grant_idx
    ON workforce_export_jobs(
        organization_id,requester_id,id,access_grant_expires_at
    )
    WHERE status='ready' AND access_grant_expires_at IS NOT NULL;

CREATE UNIQUE INDEX workforce_export_retry_source_uq
    ON workforce_export_jobs(organization_id,retry_of_export_id)
    WHERE retry_of_export_id IS NOT NULL;

CREATE INDEX workforce_export_generation_recovery_idx
    ON workforce_export_jobs(
        organization_id,status,next_attempt_at,lease_expires_at,created_at,id
    )
    WHERE status IN ('authorized','running');

COMMENT ON COLUMN workforce_export_jobs.retry_of_export_id IS
    'Terminal failed export reissued as a distinct linked job; the source job never reopens.';

CREATE FUNCTION careos_guard_workforce_export_retry_link()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    operation_key text:=nullif(current_setting('app.current_operation_key',true),'');
    actor_id uuid:=nullif(current_setting('app.current_actor_id',true),'')::uuid;
    tenant_id uuid:=nullif(current_setting('app.current_organization_id',true),'')::uuid;
    retry_depth integer;
BEGIN
    IF TG_OP='UPDATE' THEN
        IF NEW.retry_of_export_id IS DISTINCT FROM OLD.retry_of_export_id THEN
            RAISE EXCEPTION 'workforce export retry lineage is immutable'
                USING ERRCODE='55000';
        END IF;
        RETURN NEW;
    END IF;
    IF NEW.retry_of_export_id IS NULL THEN
        RETURN NEW;
    END IF;
    WITH RECURSIVE lineage AS (
        SELECT source.id,source.retry_of_export_id,1 AS depth
        FROM workforce_export_jobs source
        WHERE source.organization_id=NEW.organization_id
          AND source.id=NEW.retry_of_export_id
        UNION ALL
        SELECT parent.id,parent.retry_of_export_id,lineage.depth+1
        FROM workforce_export_jobs parent
        JOIN lineage ON parent.id=lineage.retry_of_export_id
        WHERE parent.organization_id=NEW.organization_id AND lineage.depth<6
    )
    SELECT COALESCE(max(depth),0) INTO retry_depth FROM lineage;
    IF retry_depth>=5 THEN
        RAISE EXCEPTION 'm2.export.retry_limit' USING ERRCODE='23514';
    END IF;
    IF operation_key<>'workforce.export.request'
       OR actor_id IS NULL OR tenant_id IS DISTINCT FROM NEW.organization_id
       OR NEW.requester_id IS DISTINCT FROM actor_id
       OR NEW.created_by IS DISTINCT FROM actor_id
       OR NEW.updated_by IS DISTINCT FROM actor_id
       OR NEW.lock_version<>0 OR NEW.attempt_count<>0
       OR NEW.failure_code IS NOT NULL OR NEW.approval_reference_id IS NOT NULL
       OR NEW.artifact_opaque_id IS NOT NULL OR NEW.artifact_digest IS NOT NULL
       OR NEW.row_count IS NOT NULL OR NEW.ready_at IS NOT NULL
       OR NEW.expires_at IS NOT NULL OR NEW.disposed_at IS NOT NULL
       OR NEW.worker_id IS NOT NULL OR NEW.lease_expires_at IS NOT NULL
       OR NEW.next_attempt_at IS NULL OR NEW.dead_lettered_at IS NOT NULL
       OR NOT (
           (NEW.projection IN (
               'credential-decision-detail-v1','scope-decision-detail-v1',
               'workforce-audit-detail-v1','member-evidence-detail-v1')
               AND NEW.status='requested')
           OR (NEW.projection NOT IN (
               'credential-decision-detail-v1','scope-decision-detail-v1',
               'workforce-audit-detail-v1','member-evidence-detail-v1')
               AND NEW.status='authorized'))
       OR NOT EXISTS (
           SELECT 1 FROM workforce_export_jobs source
           WHERE source.organization_id=NEW.organization_id
             AND source.id=NEW.retry_of_export_id
             AND source.status='failed'
             AND source.failure_code<>'authorization_denied'
             AND source.requester_id=actor_id
             AND NEW.next_attempt_at>=source.updated_at+CASE retry_depth
                 WHEN 1 THEN interval '1 minute'
                 WHEN 2 THEN interval '2 minutes'
                 WHEN 3 THEN interval '4 minutes'
                 WHEN 4 THEN interval '8 minutes'
                 ELSE interval '16 minutes'
             END
             AND ROW(source.requester_id,source.purpose_key,source.legal_basis_key,
                     source.projection,source.filters_digest,source.sort_digest,
                     source.format,source.row_limit,source.size_limit_bytes,
                     source.policy_version,source.filters_json)
                 IS NOT DISTINCT FROM
                 ROW(NEW.requester_id,NEW.purpose_key,NEW.legal_basis_key,
                     NEW.projection,NEW.filters_digest,NEW.sort_digest,
                     NEW.format,NEW.row_limit,NEW.size_limit_bytes,
                     NEW.policy_version,NEW.filters_json)) THEN
        RAISE EXCEPTION 'm2.export.invalid_retry' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER workforce_export_jobs_guard_retry_link
    BEFORE INSERT OR UPDATE OF retry_of_export_id ON workforce_export_jobs
    FOR EACH ROW EXECUTE FUNCTION careos_guard_workforce_export_retry_link();

COMMENT ON COLUMN workforce_export_jobs.access_granted_to IS
    'Requester-bound actor for the current internal GET-only CareOS download grant.';
COMMENT ON COLUMN workforce_export_jobs.access_grant_digest IS
    'Artifact digest bound to the internal download grant; never a provider reference.';
COMMENT ON COLUMN workforce_export_jobs.access_grant_expires_at IS
    'Server-owned grant expiry, no later than ten minutes or artifact expiry.';

ALTER TABLE workforce_notification_deliveries
    ADD CONSTRAINT workforce_notification_attempt_limit_check
        CHECK (attempt_number BETWEEN 1 AND 5);

ALTER TABLE practitioner_service_assignments
    ADD CONSTRAINT practitioner_service_assignments_eligibility_shape_check
        CHECK ((eligibility_evidence_id IS NULL)=(eligibility_digest IS NULL));

-- Module 2 permissions identify what an actor may do. These functions additionally
-- enforce the approved organization/facility/self resource boundary for the member
-- against which that operation is being performed. Access-assignment scopes only
-- narrow a canonical active M1 membership; they never manufacture a permission.
CREATE FUNCTION careos_m2_user_can_access_member(
    requested_organization_id uuid,
    requested_user_id uuid,
    requested_member_id uuid,
    requested_permission_key text
) RETURNS boolean
LANGUAGE sql
STABLE
SET search_path=public,pg_temp
AS $$
    SELECT requested_organization_id IS NOT NULL
       AND requested_user_id IS NOT NULL
       AND requested_member_id IS NOT NULL
       AND requested_permission_key IS NOT NULL
       AND EXISTS (
        SELECT 1
        FROM organization_memberships membership
        JOIN authorization_roles role
          ON role.role_key=membership.role_key
         AND role.status='active'
         AND role.interactive
        JOIN authorization_role_permissions role_permission
          ON role_permission.role_key=role.role_key
         AND role_permission.permission_key=requested_permission_key
        JOIN authorization_permissions permission
          ON permission.permission_key=role_permission.permission_key
         AND permission.status='active'
        WHERE membership.organization_id=requested_organization_id
          AND membership.user_id=requested_user_id
          AND membership.status='active'
          AND membership.effective_from<=clock_timestamp()
          AND (membership.effective_to IS NULL
               OR membership.effective_to>clock_timestamp())
          AND EXISTS (
              SELECT 1 FROM authorization_registry_releases release
              WHERE release.registry_version=role.registry_version
                AND release.status='active')
          AND EXISTS (
              SELECT 1 FROM authorization_registry_releases release
              WHERE release.registry_version=permission.registry_version
                AND release.status='active')
          AND EXISTS (
              SELECT 1 FROM workforce_members target
              WHERE target.organization_id=requested_organization_id
                AND target.id=requested_member_id)
          AND (
              role.role_key IN (
                  'organization_owner','organization_administrator','auditor','local_bootstrap')
              OR (role.role_key='security_administrator'
                  AND requested_permission_key IN (
                      'workforce.account_link.read','workforce.account_link.request'))
              OR (
                  role.role_key IN ('practitioner','clinical_support_staff')
                  AND EXISTS (
                      SELECT 1 FROM access_assignment_scopes scope
                      WHERE scope.organization_id=requested_organization_id
                        AND scope.access_assignment_id=membership.id
                        AND scope.workforce_member_id=requested_member_id
                        AND scope.status='active'
                        AND scope.effective_from<=clock_timestamp()
                        AND (scope.effective_to IS NULL
                             OR scope.effective_to>clock_timestamp())))
              OR (
                  role.role_key='facility_administrator'
                  AND EXISTS (
                      SELECT 1
                      FROM access_assignment_scopes scope
                      WHERE scope.organization_id=requested_organization_id
                        AND scope.access_assignment_id=membership.id
                        AND scope.status='active'
                        AND scope.effective_from<=clock_timestamp()
                        AND (scope.effective_to IS NULL
                             OR scope.effective_to>clock_timestamp())
                        AND scope.facility_id IS NOT NULL
                        AND EXISTS (
                            SELECT 1 FROM workforce_assignments assignment
                            WHERE assignment.organization_id=requested_organization_id
                              AND assignment.workforce_member_id=requested_member_id
                              AND assignment.lifecycle_state IN ('scheduled','active','suspended')
                              AND assignment.effective_from<=clock_timestamp()
                              AND (assignment.effective_to IS NULL
                                   OR assignment.effective_to>clock_timestamp())
                              AND assignment.facility_id=scope.facility_id
                              AND (scope.organization_unit_id IS NULL
                                   OR assignment.organization_unit_id=scope.organization_unit_id)
                              AND (scope.location_id IS NULL
                                   OR assignment.location_id=scope.location_id))))
              OR (
                  role.role_key IN (
                      'workforce_administrator','hr_administrator','credentialing_officer',
                      'clinical_governance_approver','organization_viewer')
                  AND EXISTS (
                      SELECT 1
                      FROM access_assignment_scopes scope
                      WHERE scope.organization_id=requested_organization_id
                        AND scope.access_assignment_id=membership.id
                        AND scope.status='active'
                        AND scope.effective_from<=clock_timestamp()
                        AND (scope.effective_to IS NULL
                             OR scope.effective_to>clock_timestamp())
                        AND (
                            (scope.facility_id IS NULL
                             AND scope.organization_unit_id IS NULL
                             AND scope.location_id IS NULL)
                            OR EXISTS (
                                SELECT 1 FROM workforce_assignments assignment
                                WHERE assignment.organization_id=requested_organization_id
                                  AND assignment.workforce_member_id=requested_member_id
                                  AND assignment.lifecycle_state IN ('scheduled','active','suspended')
                                  AND assignment.effective_from<=clock_timestamp()
                                  AND (assignment.effective_to IS NULL
                                       OR assignment.effective_to>clock_timestamp())
                                  AND (scope.facility_id IS NULL
                                       OR assignment.facility_id=scope.facility_id)
                                  AND (scope.organization_unit_id IS NULL
                                       OR assignment.organization_unit_id=scope.organization_unit_id)
                                  AND (scope.location_id IS NULL
                                       OR assignment.location_id=scope.location_id)))))
          )
    );
$$;

CREATE FUNCTION careos_m2_actor_can_access_member(
    requested_organization_id uuid,
    requested_member_id uuid
) RETURNS boolean
LANGUAGE sql
STABLE
SET search_path=public,pg_temp
AS $$
    SELECT CASE
        WHEN nullif(current_setting('app.current_actor_kind',true),'')='service' THEN true
        WHEN nullif(current_setting('app.current_actor_kind',true),'')='user' THEN
            careos_m2_user_can_access_member(
                requested_organization_id,
                nullif(current_setting('app.current_actor_id',true),'')::uuid,
                requested_member_id,
                (SELECT operation.permission_key
                 FROM authorization_operations operation
                 JOIN authorization_registry_releases release
                   ON release.registry_version=operation.registry_version
                  AND release.status='active'
                 WHERE operation.operation_key=
                       nullif(current_setting('app.current_operation_key',true),'')
                   AND operation.status='active'
                 LIMIT 1))
        ELSE false
    END;
$$;

CREATE FUNCTION careos_m2_user_has_resource_scope(
    requested_organization_id uuid,
    requested_user_id uuid,
    requested_permission_key text
) RETURNS boolean
LANGUAGE sql
STABLE
SET search_path=public,pg_temp
AS $$
    SELECT requested_organization_id IS NOT NULL
       AND requested_user_id IS NOT NULL
       AND requested_permission_key IS NOT NULL
       AND EXISTS (
        SELECT 1
        FROM organization_memberships membership
        JOIN authorization_roles role
          ON role.role_key=membership.role_key
         AND role.status='active'
         AND role.interactive
        JOIN authorization_role_permissions role_permission
          ON role_permission.role_key=role.role_key
         AND role_permission.permission_key=requested_permission_key
        JOIN authorization_permissions permission
          ON permission.permission_key=role_permission.permission_key
         AND permission.status='active'
        WHERE membership.organization_id=requested_organization_id
          AND membership.user_id=requested_user_id
          AND membership.status='active'
          AND membership.effective_from<=clock_timestamp()
          AND (membership.effective_to IS NULL
               OR membership.effective_to>clock_timestamp())
          AND EXISTS (
              SELECT 1 FROM authorization_registry_releases release
              WHERE release.registry_version=role.registry_version
                AND release.status='active')
          AND EXISTS (
              SELECT 1 FROM authorization_registry_releases release
              WHERE release.registry_version=permission.registry_version
                AND release.status='active')
          AND (
              role.role_key IN (
                  'organization_owner','organization_administrator','auditor','local_bootstrap')
              OR (role.role_key='security_administrator'
                  AND requested_permission_key IN (
                      'workforce.account_link.read','workforce.account_link.request'))
              OR (role.role_key='export_approver'
                  AND requested_permission_key='workforce.export.approve')
              OR (role.role_key='facility_administrator' AND EXISTS (
                  SELECT 1 FROM access_assignment_scopes scope
                  WHERE scope.organization_id=requested_organization_id
                    AND scope.access_assignment_id=membership.id
                    AND scope.facility_id IS NOT NULL
                    AND scope.status='active'
                    AND scope.effective_from<=clock_timestamp()
                    AND (scope.effective_to IS NULL
                         OR scope.effective_to>clock_timestamp())))
              OR (role.role_key IN (
                      'workforce_administrator','hr_administrator','credentialing_officer',
                      'clinical_governance_approver','organization_viewer',
                      'practitioner','clinical_support_staff')
                  AND EXISTS (
                      SELECT 1 FROM access_assignment_scopes scope
                      WHERE scope.organization_id=requested_organization_id
                        AND scope.access_assignment_id=membership.id
                        AND scope.status='active'
                        AND scope.effective_from<=clock_timestamp()
                        AND (scope.effective_to IS NULL
                             OR scope.effective_to>clock_timestamp())
                        AND (requested_permission_key<>'workforce.member.create'
                             OR (scope.facility_id IS NULL
                                 AND scope.organization_unit_id IS NULL
                                 AND scope.location_id IS NULL))))
          )
    );
$$;

CREATE FUNCTION careos_m2_actor_has_resource_scope()
RETURNS boolean
LANGUAGE sql
STABLE
SET search_path=public,pg_temp
AS $$
    SELECT CASE
        WHEN nullif(current_setting('app.current_actor_kind',true),'')='service' THEN true
        WHEN nullif(current_setting('app.current_actor_kind',true),'')='user' THEN
            careos_m2_user_has_resource_scope(
                nullif(current_setting('app.current_organization_id',true),'')::uuid,
                nullif(current_setting('app.current_actor_id',true),'')::uuid,
                (SELECT operation.permission_key
                 FROM authorization_operations operation
                 JOIN authorization_registry_releases release
                   ON release.registry_version=operation.registry_version
                  AND release.status='active'
                 WHERE operation.operation_key=
                       nullif(current_setting('app.current_operation_key',true),'')
                   AND operation.status='active'
                 LIMIT 1))
        ELSE false
    END;
$$;

-- Resolve a safe member correlation for timeline events without copying restricted
-- source fields into audit payloads. Every lookup is constrained by the supplied
-- organization; malformed or unrelated opaque references simply do not correlate.
CREATE FUNCTION careos_m2_audit_event_member_id(
    requested_organization_id uuid,
    event_subject_type text,
    event_subject_id uuid,
    event_payload jsonb
) RETURNS uuid
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path=public,pg_temp
SET row_security=off
AS $$
DECLARE
    candidate text;
    resolved uuid;
BEGIN
    candidate:=event_payload->>'memberId';
    IF candidate~*'^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$' THEN
        SELECT member.id INTO resolved FROM workforce_members member
        WHERE member.organization_id=requested_organization_id
          AND member.id=candidate::uuid;
        IF resolved IS NOT NULL THEN RETURN resolved; END IF;
    END IF;

    candidate:=event_payload->>'practitionerId';
    IF candidate~*'^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$' THEN
        SELECT practitioner.workforce_member_id INTO resolved
        FROM practitioner_profiles practitioner
        WHERE practitioner.organization_id=requested_organization_id
          AND practitioner.id=candidate::uuid;
        IF resolved IS NOT NULL THEN RETURN resolved; END IF;
    END IF;

    candidate:=event_payload->>'credentialId';
    IF candidate~*'^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$' THEN
        SELECT credential.workforce_member_id INTO resolved
        FROM practitioner_credentials credential
        WHERE credential.organization_id=requested_organization_id
          AND credential.id=candidate::uuid;
        IF resolved IS NOT NULL THEN RETURN resolved; END IF;
    END IF;

    candidate:=event_payload->>'registrationId';
    IF candidate~*'^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$' THEN
        SELECT practitioner.workforce_member_id INTO resolved
        FROM professional_registrations registration
        JOIN practitioner_profiles practitioner
          ON practitioner.organization_id=registration.organization_id
         AND practitioner.id=registration.practitioner_profile_id
        WHERE registration.organization_id=requested_organization_id
          AND registration.id=candidate::uuid;
        IF resolved IS NOT NULL THEN RETURN resolved; END IF;
    END IF;

    candidate:=event_payload->>'qualificationId';
    IF candidate~*'^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$' THEN
        SELECT qualification.workforce_member_id INTO resolved
        FROM qualifications qualification
        WHERE qualification.organization_id=requested_organization_id
          AND qualification.id=candidate::uuid;
        IF resolved IS NOT NULL THEN RETURN resolved; END IF;
    END IF;

    candidate:=event_payload->>'scopeId';
    IF candidate~*'^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$' THEN
        SELECT practitioner.workforce_member_id INTO resolved
        FROM scopes_of_practice scope
        JOIN practitioner_profiles practitioner
          ON practitioner.organization_id=scope.organization_id
         AND practitioner.id=scope.practitioner_profile_id
        WHERE scope.organization_id=requested_organization_id
          AND scope.id=candidate::uuid;
        IF resolved IS NOT NULL THEN RETURN resolved; END IF;
    END IF;

    candidate:=COALESCE(event_payload->>'assignmentId',event_payload->>'predecessorId');
    IF candidate~*'^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$' THEN
        SELECT assignment.workforce_member_id INTO resolved
        FROM workforce_assignments assignment
        WHERE assignment.organization_id=requested_organization_id
          AND assignment.id=candidate::uuid;
        IF resolved IS NOT NULL THEN RETURN resolved; END IF;
    END IF;

    candidate:=event_payload->>'serviceAssignmentId';
    IF candidate~*'^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$' THEN
        SELECT practitioner.workforce_member_id INTO resolved
        FROM practitioner_service_assignments assignment
        JOIN practitioner_profiles practitioner
          ON practitioner.organization_id=assignment.organization_id
         AND practitioner.id=assignment.practitioner_profile_id
        WHERE assignment.organization_id=requested_organization_id
          AND assignment.id=candidate::uuid;
        IF resolved IS NOT NULL THEN RETURN resolved; END IF;
    END IF;

    candidate:=event_payload->>'profileId';
    IF candidate~*'^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$' THEN
        SELECT profile.workforce_member_id INTO resolved
        FROM availability_profiles profile
        WHERE profile.organization_id=requested_organization_id
          AND profile.id=candidate::uuid;
        IF resolved IS NOT NULL THEN RETURN resolved; END IF;
    END IF;

    candidate:=event_payload->>'runId';
    IF candidate~*'^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$' THEN
        SELECT run.workforce_member_id INTO resolved
        FROM workforce_readiness_runs run
        WHERE run.organization_id=requested_organization_id AND run.id=candidate::uuid;
        IF resolved IS NOT NULL THEN RETURN resolved; END IF;
    END IF;

    candidate:=event_payload->>'activationRequestId';
    IF candidate~*'^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$' THEN
        SELECT request.workforce_member_id INTO resolved
        FROM workforce_activation_requests request
        WHERE request.organization_id=requested_organization_id
          AND request.id=candidate::uuid;
        IF resolved IS NOT NULL THEN RETURN resolved; END IF;
    END IF;

    candidate:=event_payload->>'offboardingRequestId';
    IF candidate~*'^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$' THEN
        SELECT request.workforce_member_id INTO resolved
        FROM workforce_offboarding_requests request
        WHERE request.organization_id=requested_organization_id
          AND request.id=candidate::uuid;
        IF resolved IS NOT NULL THEN RETURN resolved; END IF;
    END IF;

    candidate:=event_payload->>'documentId';
    IF candidate~*'^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$' THEN
        SELECT credential.workforce_member_id INTO resolved
        FROM credential_documents document
        JOIN practitioner_credentials credential
          ON credential.organization_id=document.organization_id
         AND credential.id=document.practitioner_credential_id
        WHERE document.organization_id=requested_organization_id
          AND document.id=candidate::uuid;
        IF resolved IS NOT NULL THEN RETURN resolved; END IF;
    END IF;

    candidate:=event_payload->>'notificationId';
    IF candidate~*'^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$' THEN
        SELECT COALESCE(delivery.workforce_member_id,credential.workforce_member_id)
          INTO resolved
        FROM workforce_notification_deliveries delivery
        LEFT JOIN practitioner_credentials credential
          ON credential.organization_id=delivery.organization_id
         AND credential.id=delivery.practitioner_credential_id
        WHERE delivery.organization_id=requested_organization_id
          AND delivery.id=candidate::uuid;
        IF resolved IS NOT NULL THEN RETURN resolved; END IF;
    END IF;

    IF event_subject_type='workforce_member' THEN
        SELECT member.id INTO resolved FROM workforce_members member
        WHERE member.organization_id=requested_organization_id
          AND member.id=event_subject_id;
    END IF;
    RETURN resolved;
END;
$$;

REVOKE ALL ON FUNCTION careos_m2_user_can_access_member(uuid,uuid,uuid,text) FROM PUBLIC;
REVOKE ALL ON FUNCTION careos_m2_actor_can_access_member(uuid,uuid) FROM PUBLIC;
REVOKE ALL ON FUNCTION careos_m2_user_has_resource_scope(uuid,uuid,text) FROM PUBLIC;
REVOKE ALL ON FUNCTION careos_m2_actor_has_resource_scope() FROM PUBLIC;
REVOKE ALL ON FUNCTION careos_m2_audit_event_member_id(uuid,text,uuid,jsonb) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION careos_m2_user_can_access_member(uuid,uuid,uuid,text),
                          careos_m2_actor_can_access_member(uuid,uuid),
                          careos_m2_user_has_resource_scope(uuid,uuid,text),
                          careos_m2_actor_has_resource_scope(),
                          careos_m2_audit_event_member_id(uuid,text,uuid,jsonb)
    TO "${applicationRole}";

CREATE FUNCTION careos_guard_access_assignment_member_link()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.status IN ('approved','active') THEN
        PERFORM pg_advisory_xact_lock(hashtextextended(
            NEW.organization_id::text||':'||NEW.access_assignment_id::text,0));
        IF EXISTS (
            SELECT 1 FROM access_assignment_scopes existing_scope
            WHERE existing_scope.organization_id=NEW.organization_id
              AND existing_scope.access_assignment_id=NEW.access_assignment_id
              AND existing_scope.id<>NEW.id
              AND existing_scope.workforce_member_id<>NEW.workforce_member_id
              AND existing_scope.status IN ('approved','active')
              AND tstzrange(existing_scope.effective_from,existing_scope.effective_to,'[)')
                  && tstzrange(NEW.effective_from,NEW.effective_to,'[)')) THEN
            RAISE EXCEPTION 'm2.account_link.membership_already_linked'
                USING ERRCODE='23505';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER access_assignment_scopes_guard_member_link
    BEFORE INSERT OR UPDATE OF access_assignment_id,workforce_member_id,
        effective_from,effective_to,status
    ON access_assignment_scopes
    FOR EACH ROW EXECUTE FUNCTION careos_guard_access_assignment_member_link();

-- Person-link consolidation is a preserved, organization-local maker/checker workflow.
-- Direct SQL cannot manufacture a decision, weaken the frozen evidence digests, or
-- mutate a terminal request even when the application role is used correctly.
CREATE FUNCTION careos_guard_person_merge_request_evidence()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    operation_key text:=nullif(current_setting('app.current_operation_key',true),'');
    actor_id uuid:=nullif(current_setting('app.current_actor_id',true),'')::uuid;
    tenant_id uuid:=nullif(current_setting('app.current_organization_id',true),'')::uuid;
BEGIN
    IF TG_OP='DELETE' THEN
        RAISE EXCEPTION 'person merge requests are retained as evidence'
            USING ERRCODE='55000';
    END IF;
    IF TG_OP='INSERT' THEN
        PERFORM pg_advisory_xact_lock(hashtextextended(
            NEW.organization_id::text||':person-link:'||
            LEAST(NEW.retained_link_id::text,NEW.discarded_link_id::text),0));
        PERFORM pg_advisory_xact_lock(hashtextextended(
            NEW.organization_id::text||':person-link:'||
            GREATEST(NEW.retained_link_id::text,NEW.discarded_link_id::text),0));
        IF operation_key<>'workforce.person.merge.request'
           OR actor_id IS NULL OR tenant_id IS DISTINCT FROM NEW.organization_id
           OR NEW.requested_by IS DISTINCT FROM actor_id
           OR NEW.created_by IS DISTINCT FROM actor_id
           OR NEW.updated_by IS DISTINCT FROM actor_id
           OR NEW.decision_state<>'submitted' OR NEW.status<>'submitted'
           OR NEW.submitted_at IS NULL
           OR NEW.submitted_at>clock_timestamp()+interval '5 seconds'
           OR NEW.decision_by IS NOT NULL OR NEW.decided_at IS NOT NULL
           OR NEW.executed_at IS NOT NULL OR NEW.lock_version<>0
           OR NEW.reason_code IS NULL
           OR EXISTS (
               SELECT 1 FROM person_merge_requests existing
               WHERE existing.organization_id=NEW.organization_id
                 AND existing.decision_state IN ('draft','submitted','approved')
                 AND (existing.retained_link_id IN (
                        NEW.retained_link_id,NEW.discarded_link_id)
                   OR existing.discarded_link_id IN (
                        NEW.retained_link_id,NEW.discarded_link_id)))
           OR NOT EXISTS (
               SELECT 1 FROM organization_person_links retained
               JOIN organization_person_links discarded
                 ON discarded.organization_id=retained.organization_id
               WHERE retained.organization_id=NEW.organization_id
                 AND retained.id=NEW.retained_link_id
                 AND discarded.id=NEW.discarded_link_id
                 AND retained.relationship_status='active'
                 AND discarded.relationship_status='active') THEN
            RAISE EXCEPTION 'm2.lifecycle.invalid_transition' USING ERRCODE='23514';
        END IF;
        RETURN NEW;
    END IF;
    IF ROW(NEW.id,NEW.organization_id,NEW.retained_link_id,NEW.discarded_link_id,
           NEW.requested_by,NEW.candidate_evidence_digest,NEW.impact_digest,
           NEW.reason_code,NEW.submitted_at,NEW.created_at,NEW.created_by)
       IS DISTINCT FROM
       ROW(OLD.id,OLD.organization_id,OLD.retained_link_id,OLD.discarded_link_id,
           OLD.requested_by,OLD.candidate_evidence_digest,OLD.impact_digest,
           OLD.reason_code,OLD.submitted_at,OLD.created_at,OLD.created_by)
       OR tenant_id IS DISTINCT FROM NEW.organization_id
       OR actor_id IS NULL OR NEW.status<>NEW.decision_state
       OR NEW.lock_version<>OLD.lock_version+1
       OR NEW.updated_by IS DISTINCT FROM actor_id
       OR NEW.updated_at<OLD.updated_at THEN
        RAISE EXCEPTION 'person merge request evidence is immutable'
            USING ERRCODE='55000';
    END IF;
    IF OLD.decision_state='submitted'
       AND NEW.decision_state IN ('approved','rejected')
       AND operation_key='workforce.person.merge.approve'
       AND actor_id<>OLD.requested_by
       AND OLD.decision_by IS NULL AND NEW.decision_by=actor_id
       AND OLD.decided_at IS NULL AND NEW.decided_at IS NOT NULL
       AND NEW.executed_at IS NULL THEN
        RETURN NEW;
    END IF;
    IF OLD.decision_state='submitted' AND NEW.decision_state='cancelled'
       AND operation_key='workforce.person.merge.request'
       AND actor_id=OLD.requested_by
       AND NEW.decision_by IS NULL AND NEW.decided_at IS NULL
       AND NEW.executed_at IS NULL THEN
        RETURN NEW;
    END IF;
    IF OLD.decision_state='approved' AND NEW.decision_state='executed'
       AND operation_key='workforce.person.merge.execute'
       AND actor_id<>OLD.requested_by
       AND NEW.decision_by=OLD.decision_by
       AND NEW.decided_at=OLD.decided_at
       AND OLD.executed_at IS NULL AND NEW.executed_at IS NOT NULL THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'm2.lifecycle.invalid_transition' USING ERRCODE='23514';
END;
$$;

CREATE TRIGGER person_merge_requests_guard_evidence
    BEFORE INSERT OR UPDATE OR DELETE ON person_merge_requests
    FOR EACH ROW EXECUTE FUNCTION careos_guard_person_merge_request_evidence();

-- Registry content is immutable after creation, while its approval lifecycle advances on
-- the same content-version row. Active versions remain mutable only for supersession.
CREATE FUNCTION careos_m2_registry_entry_has_live_references(
    requested_organization_id uuid,
    requested_entry_id uuid,
    effective_at timestamptz)
RETURNS boolean
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path=public,pg_temp
SET row_security=off
AS $$
    SELECT requested_organization_id IS DISTINCT FROM
               nullif(current_setting('app.current_organization_id',true),'')::uuid
        OR EXISTS (
        SELECT 1
        FROM (
            SELECT profile.id
            FROM practitioner_profiles profile
            WHERE profile.organization_id=requested_organization_id
              AND profile.profession_entry_id=requested_entry_id
              AND profile.lifecycle_state IN ('draft','active','suspended')
              AND (profile.effective_to IS NULL OR profile.effective_to>effective_at)
            UNION ALL
            SELECT registration.id
            FROM professional_registrations registration
            WHERE registration.organization_id=requested_organization_id
              AND (registration.regulator_entry_id=requested_entry_id
                   OR registration.registration_type_entry_id=requested_entry_id)
              AND registration.status NOT IN ('revoked','expired','superseded')
              AND (registration.expires_on IS NULL OR registration.expires_on>=effective_at::date)
            UNION ALL
            SELECT qualification.id
            FROM qualifications qualification
            WHERE qualification.organization_id=requested_organization_id
              AND qualification.qualification_entry_id=requested_entry_id
              AND qualification.status NOT IN ('rejected','superseded')
              AND (qualification.expires_on IS NULL OR qualification.expires_on>=effective_at::date)
            UNION ALL
            SELECT specialty.id
            FROM practitioner_specialties specialty
            WHERE specialty.organization_id=requested_organization_id
              AND specialty.specialty_entry_id=requested_entry_id
              AND specialty.status IN ('scheduled','active')
              AND (specialty.effective_to IS NULL OR specialty.effective_to>effective_at)
            UNION ALL
            SELECT credential.id
            FROM practitioner_credentials credential
            WHERE credential.organization_id=requested_organization_id
              AND credential.credential_type_entry_id=requested_entry_id
              AND credential.status NOT IN ('rejected','revoked','expired','superseded')
              AND (credential.expires_on IS NULL OR credential.expires_on>=effective_at::date)
            UNION ALL
            SELECT definition.id
            FROM scope_definitions definition
            WHERE definition.organization_id=requested_organization_id
              AND (definition.profession_entry_id=requested_entry_id
                   OR definition.specialty_entry_id=requested_entry_id)
              AND definition.lifecycle_state IN ('draft','active')
              AND (definition.effective_to IS NULL OR definition.effective_to>effective_at)
            UNION ALL
            SELECT requirement.id
            FROM scope_requirements requirement
            JOIN scope_definitions definition
              ON definition.organization_id=requirement.organization_id
             AND definition.id=requirement.scope_definition_id
            WHERE requirement.organization_id=requested_organization_id
              AND requirement.registry_entry_id=requested_entry_id
              AND requirement.status IN ('draft','active')
              AND definition.lifecycle_state IN ('draft','active')
              AND (definition.effective_to IS NULL OR definition.effective_to>effective_at)
            UNION ALL
            SELECT activity.id
            FROM scope_activities activity
            WHERE activity.organization_id=requested_organization_id
              AND (activity.activity_entry_id=requested_entry_id
                   OR activity.supervision_mode_entry_id=requested_entry_id)
              AND activity.status='active'
              AND (activity.effective_to IS NULL OR activity.effective_to>effective_at)
            UNION ALL
            SELECT restriction.id
            FROM scope_restrictions restriction
            WHERE restriction.organization_id=requested_organization_id
              AND restriction.restriction_entry_id=requested_entry_id
              AND restriction.status='active'
              AND (restriction.effective_to IS NULL OR restriction.effective_to>effective_at)
            UNION ALL
            SELECT assignment.id
            FROM workforce_assignments assignment
            WHERE assignment.organization_id=requested_organization_id
              AND (assignment.assignment_type_entry_id=requested_entry_id
                   OR assignment.position_entry_id=requested_entry_id)
              AND assignment.lifecycle_state IN ('draft','scheduled','active','suspended')
              AND (assignment.effective_to IS NULL OR assignment.effective_to>effective_at)
            UNION ALL
            SELECT engagement.id
            FROM employment_engagements engagement
            JOIN workforce_registry_entries entry
              ON entry.organization_id=engagement.organization_id
             AND entry.id=requested_entry_id
             AND engagement.employment_category_key=entry.entry_key
            WHERE engagement.organization_id=requested_organization_id
              AND engagement.status IN ('draft','scheduled','active','suspended')
              AND (engagement.effective_to IS NULL OR engagement.effective_to>effective_at)
            UNION ALL
            SELECT credential.id
            FROM practitioner_credentials credential
            JOIN workforce_registry_entries entry
              ON entry.organization_id=credential.organization_id
             AND entry.id=requested_entry_id
            JOIN workforce_registry_definitions definition
              ON definition.organization_id=entry.organization_id
             AND definition.id=entry.registry_definition_id
             AND definition.category='credential_risk_tier'
            WHERE credential.organization_id=requested_organization_id
              AND credential.risk_tier IN (entry.entry_key,lower(entry.code))
              AND credential.status NOT IN ('rejected','revoked','expired','superseded')
              AND (credential.expires_on IS NULL OR credential.expires_on>=effective_at::date)
            UNION ALL
            SELECT period.id
            FROM availability_periods period
            JOIN availability_profiles profile
              ON profile.organization_id=period.organization_id
             AND profile.id=period.availability_profile_id
            WHERE period.organization_id=requested_organization_id
              AND period.availability_type_entry_id=requested_entry_id
              AND period.status='active'
              AND profile.lifecycle_state IN ('draft','scheduled','active')
              AND (profile.effective_to IS NULL OR profile.effective_to>effective_at)
            UNION ALL
            SELECT request.id
            FROM workforce_offboarding_requests request
            WHERE request.organization_id=requested_organization_id
              AND request.reason_entry_id=requested_entry_id
              AND request.status NOT IN ('completed','cancelled')
            UNION ALL
            SELECT delivery.id
            FROM workforce_notification_deliveries delivery
            WHERE delivery.organization_id=requested_organization_id
              AND delivery.template_entry_id=requested_entry_id
              AND delivery.status IN ('planned','queued','sending')
            UNION ALL
            SELECT delivery.id
            FROM workforce_notification_deliveries delivery
            JOIN workforce_registry_entries entry
              ON entry.organization_id=delivery.organization_id
             AND entry.id=requested_entry_id
            JOIN workforce_registry_definitions definition
              ON definition.organization_id=entry.organization_id
             AND definition.id=entry.registry_definition_id
             AND definition.category='notification_milestone'
            WHERE delivery.organization_id=requested_organization_id
              AND delivery.milestone IN (entry.entry_key,lower(entry.code))
              AND delivery.status IN ('planned','queued','sending')
        ) live_reference
        LIMIT 1
    );
$$;

REVOKE ALL ON FUNCTION careos_m2_registry_entry_has_live_references(uuid,uuid,timestamptz)
    FROM PUBLIC;
GRANT EXECUTE ON FUNCTION careos_m2_registry_entry_has_live_references(uuid,uuid,timestamptz)
    TO "${applicationRole}";

CREATE OR REPLACE FUNCTION careos_validate_workforce_registry_version_revision()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    operation_key text:=nullif(current_setting('app.current_operation_key',true),'');
    actor_id uuid:=nullif(current_setting('app.current_actor_id',true),'')::uuid;
BEGIN
    PERFORM pg_advisory_xact_lock(hashtextextended(
        'workforce-registry-entry:'||NEW.organization_id::text||':'||NEW.registry_entry_id::text,0));
    IF ROW(NEW.id,NEW.organization_id,NEW.registry_entry_id,NEW.version_number,
           NEW.version_fields,NEW.version_digest,NEW.maker_id,NEW.created_at,NEW.created_by)
       IS DISTINCT FROM
       ROW(OLD.id,OLD.organization_id,OLD.registry_entry_id,OLD.version_number,
           OLD.version_fields,OLD.version_digest,OLD.maker_id,OLD.created_at,OLD.created_by) THEN
        RAISE EXCEPTION 'workforce registry version content is immutable'
            USING ERRCODE='55000';
    END IF;
    IF OLD.lifecycle_state IN ('superseded','rejected','cancelled') THEN
        RAISE EXCEPTION 'terminal workforce registry version evidence is immutable'
            USING ERRCODE='55000';
    END IF;
    IF NEW.status<>NEW.lifecycle_state
       OR NEW.lock_version<>OLD.lock_version+1
       OR NEW.updated_by IS DISTINCT FROM actor_id
       OR NEW.updated_at<OLD.updated_at THEN
        RAISE EXCEPTION 'm2.lifecycle.invalid_transition' USING ERRCODE='23514';
    END IF;
    IF OLD.lifecycle_state='draft' AND NEW.lifecycle_state='submitted'
       AND operation_key='workforce.registry.manage'
       AND NEW.checker_id IS NULL AND NEW.decision_code IS NULL
       AND NEW.activated_at IS NULL AND NEW.superseded_at IS NULL
       AND NEW.effective_from=OLD.effective_from
       AND NEW.effective_to IS NOT DISTINCT FROM OLD.effective_to THEN
        IF jsonb_object_length(NEW.version_fields)<>1
           OR jsonb_typeof(NEW.version_fields->'enabled')<>'boolean'
           OR (NOT (NEW.version_fields->>'enabled')::boolean
               AND careos_m2_registry_entry_has_live_references(
                   NEW.organization_id,NEW.registry_entry_id,clock_timestamp())) THEN
            RAISE EXCEPTION 'registry entry has invalid content or unresolved references'
                USING ERRCODE='23514';
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.lifecycle_state='submitted' AND NEW.lifecycle_state IN ('approved','rejected')
       AND operation_key='workforce.registry.approve'
       AND OLD.checker_id IS NULL AND NEW.checker_id=actor_id
       AND NEW.checker_id<>NEW.maker_id AND NEW.decision_code=NEW.lifecycle_state
       AND NEW.activated_at IS NULL AND NEW.superseded_at IS NULL
       AND NEW.effective_from=OLD.effective_from
       AND NEW.effective_to IS NOT DISTINCT FROM OLD.effective_to THEN
        IF NEW.lifecycle_state='approved'
           AND NOT (NEW.version_fields->>'enabled')::boolean
           AND careos_m2_registry_entry_has_live_references(
               NEW.organization_id,NEW.registry_entry_id,clock_timestamp()) THEN
            RAISE EXCEPTION 'registry entry has unresolved references'
                USING ERRCODE='23514';
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.lifecycle_state='approved' AND NEW.lifecycle_state='active'
       AND operation_key='workforce.registry.activate'
       AND actor_id<>NEW.maker_id AND actor_id<>NEW.checker_id
       AND NEW.checker_id=OLD.checker_id AND NEW.decision_code=OLD.decision_code
       AND OLD.activated_at IS NULL AND NEW.activated_at IS NOT NULL
       AND NEW.activated_at<=clock_timestamp()+interval '5 seconds'
       AND NEW.effective_from>=OLD.effective_from
       AND NEW.effective_from<=clock_timestamp()+interval '5 seconds'
       AND NEW.effective_to IS NULL AND NEW.superseded_at IS NULL THEN
        IF NOT (NEW.version_fields->>'enabled')::boolean
           AND careos_m2_registry_entry_has_live_references(
               NEW.organization_id,NEW.registry_entry_id,NEW.effective_from) THEN
            RAISE EXCEPTION 'registry entry has unresolved references'
                USING ERRCODE='23514';
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.lifecycle_state='active' AND NEW.lifecycle_state='superseded'
       AND operation_key='workforce.registry.activate'
       AND actor_id<>NEW.maker_id AND actor_id<>NEW.checker_id
       AND NEW.checker_id=OLD.checker_id AND NEW.decision_code=OLD.decision_code
       AND NEW.activated_at=OLD.activated_at AND NEW.effective_from=OLD.effective_from
       AND OLD.effective_to IS NULL AND NEW.effective_to IS NOT NULL
       AND OLD.superseded_at IS NULL AND NEW.superseded_at IS NOT NULL
       AND NEW.effective_to=NEW.superseded_at
       AND NEW.superseded_at>OLD.effective_from THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'm2.lifecycle.invalid_transition' USING ERRCODE='23514';
END;
$$;

CREATE OR REPLACE FUNCTION careos_validate_workforce_configuration_snapshot_revision()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    operation_key text:=nullif(current_setting('app.current_operation_key',true),'');
    actor_id uuid:=nullif(current_setting('app.current_actor_id',true),'')::uuid;
BEGIN
    IF ROW(NEW.id,NEW.organization_id,NEW.display_number,NEW.parent_snapshot_id,
           NEW.snapshot_digest,NEW.policy_versions,NEW.maker_id,NEW.checker_id,
           NEW.activator_id,NEW.effective_at,NEW.created_at,NEW.created_by)
       IS DISTINCT FROM
       ROW(OLD.id,OLD.organization_id,OLD.display_number,OLD.parent_snapshot_id,
           OLD.snapshot_digest,OLD.policy_versions,OLD.maker_id,OLD.checker_id,
           OLD.activator_id,OLD.effective_at,OLD.created_at,OLD.created_by)
       OR operation_key<>'workforce.registry.activate'
       OR OLD.status<>'active' OR NEW.status<>'superseded'
       OR OLD.superseded_at IS NOT NULL OR NEW.superseded_at IS NULL
       OR NEW.superseded_at<=OLD.effective_at
       OR NEW.lock_version<>OLD.lock_version+1
       OR NEW.updated_by IS DISTINCT FROM actor_id
       OR NEW.updated_at<OLD.updated_at THEN
        RAISE EXCEPTION 'workforce configuration snapshots are immutable except for governed supersession'
            USING ERRCODE='55000';
    END IF;
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION careos_guard_registry_definition_content()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    operation_key text:=nullif(current_setting('app.current_operation_key',true),'');
    actor_id uuid:=nullif(current_setting('app.current_actor_id',true),'')::uuid;
BEGIN
    IF TG_OP='DELETE' THEN
        RAISE EXCEPTION 'registry definitions are retained as historical evidence'
            USING ERRCODE='55000';
    END IF;
    IF ROW(NEW.id,NEW.organization_id,NEW.registry_key,NEW.category,NEW.display_name,
           NEW.owner_membership_id,NEW.value_schema,NEW.review_cadence_days,
           NEW.created_at,NEW.created_by)
       IS DISTINCT FROM
       ROW(OLD.id,OLD.organization_id,OLD.registry_key,OLD.category,OLD.display_name,
           OLD.owner_membership_id,OLD.value_schema,OLD.review_cadence_days,
           OLD.created_at,OLD.created_by) THEN
        RAISE EXCEPTION 'registry definition content is immutable' USING ERRCODE='55000';
    END IF;
    IF NOT (OLD.lifecycle_state='draft' AND NEW.lifecycle_state='active'
            AND operation_key='workforce.registry.activate'
            AND NEW.status='active'
            AND NEW.lock_version=OLD.lock_version+1
            AND NEW.updated_by=actor_id AND NEW.updated_at>=OLD.updated_at) THEN
        RAISE EXCEPTION 'm2.lifecycle.invalid_transition' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION careos_guard_registry_entry_content()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    operation_key text:=nullif(current_setting('app.current_operation_key',true),'');
    actor_id uuid:=nullif(current_setting('app.current_actor_id',true),'')::uuid;
BEGIN
    IF TG_OP='DELETE' THEN
        RAISE EXCEPTION 'registry entries are retained as historical evidence'
            USING ERRCODE='55000';
    END IF;
    IF ROW(NEW.id,NEW.organization_id,NEW.registry_definition_id,NEW.entry_key,
           NEW.code,NEW.display_label,NEW.jurisdiction_country,NEW.context_key,
           NEW.created_at,NEW.created_by)
       IS DISTINCT FROM
       ROW(OLD.id,OLD.organization_id,OLD.registry_definition_id,OLD.entry_key,
           OLD.code,OLD.display_label,OLD.jurisdiction_country,OLD.context_key,
           OLD.created_at,OLD.created_by) THEN
        RAISE EXCEPTION 'registry entry content is immutable' USING ERRCODE='55000';
    END IF;
    IF NOT (operation_key='workforce.registry.activate'
            AND ((OLD.lifecycle_state='draft' AND NEW.lifecycle_state='active')
              OR (OLD.lifecycle_state='active' AND NEW.lifecycle_state='retired')
              OR (OLD.lifecycle_state='retired' AND NEW.lifecycle_state='active'))
            AND NEW.status=NEW.lifecycle_state
            AND NEW.lock_version=OLD.lock_version+1
            AND NEW.updated_by=actor_id AND NEW.updated_at>=OLD.updated_at) THEN
        RAISE EXCEPTION 'm2.lifecycle.invalid_transition' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION careos_guard_workforce_configuration_change_request()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    operation_key text:=nullif(current_setting('app.current_operation_key',true),'');
    actor_id uuid:=nullif(current_setting('app.current_actor_id',true),'')::uuid;
BEGIN
    IF TG_OP='DELETE' THEN
        RAISE EXCEPTION 'configuration change requests are retained as evidence'
            USING ERRCODE='55000';
    END IF;
    IF ROW(NEW.id,NEW.organization_id,NEW.parent_snapshot_id,NEW.summary,NEW.reason_code,
           NEW.maker_id,NEW.requested_effective_at,NEW.created_at,NEW.created_by)
       IS DISTINCT FROM
       ROW(OLD.id,OLD.organization_id,OLD.parent_snapshot_id,OLD.summary,OLD.reason_code,
           OLD.maker_id,OLD.requested_effective_at,OLD.created_at,OLD.created_by)
       OR NEW.lock_version<>OLD.lock_version+1
       OR NEW.updated_by IS DISTINCT FROM actor_id
       OR NEW.updated_at<OLD.updated_at THEN
        RAISE EXCEPTION 'configuration change request content is immutable'
            USING ERRCODE='55000';
    END IF;
    IF OLD.status='draft' AND NEW.status='submitted'
       AND operation_key='workforce.registry.manage'
       AND actor_id=OLD.maker_id
       AND OLD.validation_digest IS NULL AND NEW.validation_digest IS NOT NULL
       AND NEW.validation_expires_at>clock_timestamp()
       AND NEW.validation_expires_at<=clock_timestamp()+interval '15 minutes 5 seconds'
       AND NEW.checker_id IS NULL AND NEW.activator_id IS NULL
       AND NEW.decision_digest IS NULL AND NEW.decision_expires_at IS NULL THEN
        RETURN NEW;
    END IF;
    IF OLD.status='submitted' AND NEW.status IN ('approved','rejected')
       AND operation_key='workforce.registry.approve'
       AND actor_id<>OLD.maker_id AND NEW.checker_id=actor_id
       AND NEW.activator_id IS NULL
       AND NEW.validation_digest=OLD.validation_digest
       AND NEW.validation_expires_at=OLD.validation_expires_at
       AND NEW.decision_digest IS NOT NULL
       AND ((NEW.status='approved'
             AND NEW.decision_expires_at>clock_timestamp()
             AND NEW.decision_expires_at<=clock_timestamp()+interval '30 minutes 5 seconds')
            OR (NEW.status='rejected' AND NEW.decision_expires_at IS NULL)) THEN
        RETURN NEW;
    END IF;
    IF OLD.status='approved' AND NEW.status='active'
       AND operation_key='workforce.registry.activate'
       AND actor_id<>OLD.maker_id AND actor_id<>OLD.checker_id
       AND NEW.activator_id=actor_id
       AND NEW.checker_id=OLD.checker_id
       AND NEW.validation_digest=OLD.validation_digest
       AND NEW.validation_expires_at=OLD.validation_expires_at
       AND NEW.decision_digest=OLD.decision_digest
       AND NEW.decision_expires_at=OLD.decision_expires_at THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'm2.lifecycle.invalid_transition' USING ERRCODE='23514';
END;
$$;

CREATE TRIGGER workforce_configuration_change_requests_guard
    BEFORE UPDATE OR DELETE ON workforce_configuration_change_requests
    FOR EACH ROW EXECUTE FUNCTION careos_guard_workforce_configuration_change_request();

CREATE FUNCTION careos_guard_workforce_configuration_change_item()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    operation_key text:=nullif(current_setting('app.current_operation_key',true),'');
    actor_id uuid:=nullif(current_setting('app.current_actor_id',true),'')::uuid;
BEGIN
    IF TG_OP='DELETE' THEN
        RAISE EXCEPTION 'configuration change items are retained as evidence'
            USING ERRCODE='55000';
    END IF;
    IF ROW(NEW.id,NEW.organization_id,NEW.change_request_id,NEW.item_order,
           NEW.target_type,NEW.target_id,NEW.baseline_revision,NEW.new_revision,
           NEW.baseline_digest,NEW.new_digest,NEW.change_type,NEW.changed_fields,
           NEW.created_at,NEW.created_by)
       IS DISTINCT FROM
       ROW(OLD.id,OLD.organization_id,OLD.change_request_id,OLD.item_order,
           OLD.target_type,OLD.target_id,OLD.baseline_revision,OLD.new_revision,
           OLD.baseline_digest,OLD.new_digest,OLD.change_type,OLD.changed_fields,
           OLD.created_at,OLD.created_by)
       OR NEW.lock_version<>OLD.lock_version+1
       OR NEW.updated_by IS DISTINCT FROM actor_id
       OR NEW.updated_at<OLD.updated_at THEN
        RAISE EXCEPTION 'configuration change item content is immutable'
            USING ERRCODE='55000';
    END IF;
    IF (OLD.status='draft' AND NEW.status='submitted'
            AND operation_key='workforce.registry.manage')
       OR (OLD.status='submitted' AND NEW.status IN ('approved','cancelled')
            AND operation_key='workforce.registry.approve')
       OR (OLD.status='approved' AND NEW.status='activated'
            AND operation_key='workforce.registry.activate') THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'm2.lifecycle.invalid_transition' USING ERRCODE='23514';
END;
$$;

CREATE TRIGGER workforce_configuration_change_items_guard
    BEFORE UPDATE OR DELETE ON workforce_configuration_change_items
    FOR EACH ROW EXECUTE FUNCTION careos_guard_workforce_configuration_change_item();

GRANT UPDATE ON workforce_registry_versions,workforce_configuration_snapshots
    TO "${applicationRole}";

-- Replace permissive edge checks introduced while the lifecycle surface was being
-- assembled. Every state change now has one explicit owning operation.
CREATE OR REPLACE FUNCTION careos_guard_credential_lifecycle_edge()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE operation_key text:=nullif(current_setting('app.current_operation_key',true),'');
BEGIN
    IF OLD.status=NEW.status THEN RETURN NEW; END IF;
    IF OLD.status IN ('rejected','revoked','expired','superseded') THEN
        RAISE EXCEPTION 'terminal credential evidence is immutable' USING ERRCODE='55000';
    END IF;
    IF NOT (
        (OLD.status='draft' AND NEW.status='evidence_pending'
            AND operation_key='credential.document.upload') OR
        (OLD.status IN ('draft','evidence_pending') AND NEW.status='submitted'
            AND operation_key='credential.record.manage') OR
        (OLD.status='submitted' AND NEW.status='in_review'
            AND operation_key='credential.review.claim') OR
        (OLD.status='in_review'
            AND NEW.status IN ('verified','rejected','more_information_required',
                               'returned_for_correction')
            AND operation_key='credential.review.decide') OR
        (OLD.status IN ('verified','suspended','more_information_required',
                       'returned_for_correction') AND NEW.status='superseded'
            AND operation_key='credential.review.decide') OR
        (OLD.status='verified' AND NEW.status IN ('suspended','revoked')
            AND operation_key='credential.lifecycle') OR
        (OLD.status='suspended' AND NEW.status='revoked'
            AND operation_key='credential.lifecycle') OR
        (OLD.status IN ('verified','suspended') AND NEW.status='expired'
            AND operation_key='m2.expiry.process')
    ) THEN
        RAISE EXCEPTION 'm2.lifecycle.invalid_transition' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION careos_guard_scope_lifecycle_edge()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE operation_key text:=nullif(current_setting('app.current_operation_key',true),'');
BEGIN
    IF OLD.lifecycle_state=NEW.lifecycle_state THEN RETURN NEW; END IF;
    IF OLD.lifecycle_state IN ('rejected','superseded','ended') THEN
        RAISE EXCEPTION 'terminal scope evidence is immutable' USING ERRCODE='55000';
    END IF;
    IF NOT (
        (OLD.lifecycle_state IN ('draft','changes_requested')
            AND NEW.lifecycle_state='submitted'
            AND operation_key='practitioner.scope.submit') OR
        (OLD.lifecycle_state='submitted' AND NEW.lifecycle_state='in_review'
            AND operation_key='practitioner.scope.approve') OR
        (OLD.lifecycle_state IN ('submitted','in_review')
            AND NEW.lifecycle_state IN ('approved','rejected','changes_requested')
            AND operation_key='practitioner.scope.approve') OR
        (OLD.lifecycle_state IN ('approved','suspended')
            AND NEW.lifecycle_state='superseded'
            AND operation_key='practitioner.scope.approve') OR
        (OLD.lifecycle_state='approved'
            AND NEW.lifecycle_state IN ('suspended','ended')
            AND operation_key='practitioner.scope.lifecycle') OR
        (OLD.lifecycle_state='suspended' AND NEW.lifecycle_state='ended'
            AND operation_key='practitioner.scope.lifecycle')
    ) THEN
        RAISE EXCEPTION 'm2.lifecycle.invalid_transition' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION careos_guard_specialty_content_and_edge()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE operation_key text:=nullif(current_setting('app.current_operation_key',true),'');
BEGIN
    IF OLD.status IN ('ended','superseded') AND NEW IS DISTINCT FROM OLD THEN
        RAISE EXCEPTION 'terminal specialty evidence is immutable' USING ERRCODE='55000';
    END IF;
    IF ROW(NEW.practitioner_profile_id,NEW.specialty_entry_id,NEW.specialty_version_id,
           NEW.designation,NEW.effective_from,NEW.supersedes_id)
       IS DISTINCT FROM
       ROW(OLD.practitioner_profile_id,OLD.specialty_entry_id,OLD.specialty_version_id,
           OLD.designation,OLD.effective_from,OLD.supersedes_id) THEN
        RAISE EXCEPTION 'specialty content is immutable' USING ERRCODE='55000';
    END IF;
    IF NEW.effective_to IS DISTINCT FROM OLD.effective_to
       AND NOT (operation_key='practitioner.specialty.manage'
                AND NEW.effective_to>OLD.effective_from
                AND (OLD.effective_to IS NULL OR NEW.effective_to<=OLD.effective_to)) THEN
        RAISE EXCEPTION 'specialty range may only be shortened by its lifecycle operation'
            USING ERRCODE='55000';
    END IF;
    IF OLD.status<>NEW.status
       AND NOT (operation_key='practitioner.specialty.manage' AND (
          (OLD.status='scheduled' AND NEW.status='active') OR
          (OLD.status IN ('scheduled','active') AND NEW.status IN ('ended','superseded'))
       )) THEN
        RAISE EXCEPTION 'm2.lifecycle.invalid_transition' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION careos_guard_assignment_content_and_edge()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE operation_key text:=nullif(current_setting('app.current_operation_key',true),'');
BEGIN
    IF OLD.lifecycle_state IN ('ended','cancelled') AND NEW IS DISTINCT FROM OLD THEN
        RAISE EXCEPTION 'terminal assignment evidence is immutable' USING ERRCODE='55000';
    END IF;
    IF ROW(NEW.workforce_member_id,NEW.facility_id,NEW.organization_unit_id,NEW.location_id,
           NEW.assignment_type_entry_id,NEW.assignment_type_version_id,
           NEW.position_entry_id,NEW.position_version_id,NEW.primary_assignment,
           NEW.effective_from,NEW.predecessor_id)
       IS DISTINCT FROM
       ROW(OLD.workforce_member_id,OLD.facility_id,OLD.organization_unit_id,OLD.location_id,
           OLD.assignment_type_entry_id,OLD.assignment_type_version_id,
           OLD.position_entry_id,OLD.position_version_id,OLD.primary_assignment,
           OLD.effective_from,OLD.predecessor_id) THEN
        RAISE EXCEPTION 'assignment content is immutable' USING ERRCODE='55000';
    END IF;
    IF NEW.successor_id IS DISTINCT FROM OLD.successor_id
       AND NOT (operation_key='workforce.assignment.lifecycle'
                AND OLD.successor_id IS NULL AND NEW.successor_id IS NOT NULL) THEN
        RAISE EXCEPTION 'assignment successor lineage is immutable' USING ERRCODE='55000';
    END IF;
    IF NEW.effective_to IS DISTINCT FROM OLD.effective_to
       AND NOT (operation_key IN ('workforce.assignment.lifecycle',
                                  'workforce.offboarding.execute','m2.offboarding.execute')
                AND NEW.effective_to>OLD.effective_from
                AND (OLD.effective_to IS NULL OR NEW.effective_to<=OLD.effective_to)) THEN
        RAISE EXCEPTION 'assignment range may only be shortened by lifecycle operation'
            USING ERRCODE='55000';
    END IF;
    IF OLD.lifecycle_state<>NEW.lifecycle_state
       AND NOT (
          (OLD.lifecycle_state IN ('draft','scheduled') AND NEW.lifecycle_state='active'
             AND operation_key='workforce.assignment.lifecycle') OR
          (OLD.lifecycle_state='active' AND NEW.lifecycle_state IN ('suspended','ended')
             AND operation_key='workforce.assignment.lifecycle') OR
          (OLD.lifecycle_state='suspended' AND NEW.lifecycle_state IN ('active','ended')
             AND operation_key='workforce.assignment.lifecycle') OR
          (OLD.lifecycle_state='scheduled' AND NEW.lifecycle_state='ended'
             AND operation_key='workforce.assignment.lifecycle') OR
          (OLD.lifecycle_state IN ('draft','scheduled') AND NEW.lifecycle_state='cancelled'
             AND operation_key='workforce.assignment.lifecycle') OR
          (OLD.lifecycle_state IN ('draft','scheduled','active','suspended')
             AND NEW.lifecycle_state IN ('ended','cancelled')
             AND operation_key IN ('workforce.offboarding.execute','m2.offboarding.execute'))
       ) THEN
        RAISE EXCEPTION 'm2.lifecycle.invalid_transition' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION careos_guard_service_assignment_content_and_edge()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE operation_key text:=nullif(current_setting('app.current_operation_key',true),'');
BEGIN
    IF OLD.lifecycle_state IN ('ended','cancelled') AND NEW IS DISTINCT FROM OLD THEN
        RAISE EXCEPTION 'terminal service-assignment evidence is immutable' USING ERRCODE='55000';
    END IF;
    IF ROW(NEW.practitioner_profile_id,NEW.service_id,NEW.facility_id,NEW.location_id,
           NEW.scope_of_practice_id,NEW.supervisor_practitioner_id,NEW.effective_from)
       IS DISTINCT FROM
       ROW(OLD.practitioner_profile_id,OLD.service_id,OLD.facility_id,OLD.location_id,
           OLD.scope_of_practice_id,OLD.supervisor_practitioner_id,OLD.effective_from) THEN
        RAISE EXCEPTION 'service-assignment content is immutable' USING ERRCODE='55000';
    END IF;
    IF ROW(NEW.eligibility_evidence_id,NEW.eligibility_digest)
       IS DISTINCT FROM ROW(OLD.eligibility_evidence_id,OLD.eligibility_digest)
       AND NOT (
           operation_key IN (
               'practitioner.service_assignment.lifecycle','m2.eligibility.evaluate',
               'credential.registration.lifecycle','credential.lifecycle',
               'practitioner.scope.lifecycle','workforce.lifecycle.suspend')
           OR (operation_key='practitioner.service_assignment.manage'
               AND OLD.eligibility_evidence_id IS NULL AND OLD.eligibility_digest IS NULL
               AND NEW.eligibility_evidence_id IS NOT NULL AND NEW.eligibility_digest IS NOT NULL)
       ) THEN
        RAISE EXCEPTION 'service-assignment eligibility binding is operation controlled'
            USING ERRCODE='55000';
    END IF;
    IF NEW.effective_to IS DISTINCT FROM OLD.effective_to
       AND NOT (operation_key IN (
                    'practitioner.service_assignment.lifecycle','practitioner.scope.lifecycle',
                    'workforce.offboarding.execute','m2.offboarding.execute')
                AND NEW.effective_to>OLD.effective_from
                AND (OLD.effective_to IS NULL OR NEW.effective_to<=OLD.effective_to)) THEN
        RAISE EXCEPTION 'service-assignment range may only be shortened by lifecycle operation'
            USING ERRCODE='55000';
    END IF;
    IF OLD.lifecycle_state<>NEW.lifecycle_state
       AND NOT (
          (OLD.lifecycle_state IN ('draft','scheduled','suspended')
             AND NEW.lifecycle_state='active'
             AND operation_key='practitioner.service_assignment.lifecycle') OR
          (OLD.lifecycle_state='active' AND NEW.lifecycle_state='suspended'
             AND operation_key IN ('practitioner.service_assignment.lifecycle',
                                   'practitioner.scope.lifecycle','workforce.lifecycle.suspend',
                                   'credential.registration.lifecycle','credential.lifecycle')) OR
          (OLD.lifecycle_state IN ('scheduled','active') AND NEW.lifecycle_state='suspended'
             AND operation_key IN ('m2.eligibility.evaluate','practitioner.scope.lifecycle',
                                   'workforce.lifecycle.suspend',
                                   'credential.registration.lifecycle','credential.lifecycle')) OR
          (OLD.lifecycle_state IN ('active','suspended') AND NEW.lifecycle_state='ended'
             AND operation_key IN ('practitioner.service_assignment.lifecycle',
                                   'practitioner.scope.lifecycle','workforce.offboarding.execute',
                                   'm2.offboarding.execute')) OR
          (OLD.lifecycle_state IN ('draft','scheduled') AND NEW.lifecycle_state='cancelled'
             AND operation_key='practitioner.service_assignment.lifecycle') OR
          (OLD.lifecycle_state IN ('draft','scheduled','active','suspended')
             AND NEW.lifecycle_state IN ('ended','cancelled')
             AND operation_key IN ('workforce.offboarding.execute','m2.offboarding.execute')) OR
          (OLD.lifecycle_state='suspended' AND NEW.lifecycle_state='active'
             AND operation_key='workforce.lifecycle.reactivate')
       ) THEN
        RAISE EXCEPTION 'm2.lifecycle.invalid_transition' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION careos_guard_registration_terminal_evidence()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.status IN ('revoked','expired','superseded') AND NEW IS DISTINCT FROM OLD THEN
        RAISE EXCEPTION 'terminal registration evidence is immutable' USING ERRCODE='55000';
    END IF;
    IF OLD.status IN ('verified','suspended')
       AND NEW.decision_reference_id IS DISTINCT FROM OLD.decision_reference_id THEN
        RAISE EXCEPTION 'registration decision evidence is immutable' USING ERRCODE='55000';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER professional_registrations_guard_terminal_evidence
    BEFORE UPDATE ON professional_registrations
    FOR EACH ROW EXECUTE FUNCTION careos_guard_registration_terminal_evidence();

CREATE FUNCTION careos_guard_qualification_decision_evidence()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.status IN ('rejected','superseded') AND NEW IS DISTINCT FROM OLD THEN
        RAISE EXCEPTION 'terminal qualification evidence is immutable' USING ERRCODE='55000';
    END IF;
    IF OLD.status='verified'
       AND NEW.verification_reference_id IS DISTINCT FROM OLD.verification_reference_id THEN
        RAISE EXCEPTION 'qualification verification evidence is immutable' USING ERRCODE='55000';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER qualifications_guard_decision_evidence
    BEFORE UPDATE ON qualifications
    FOR EACH ROW EXECUTE FUNCTION careos_guard_qualification_decision_evidence();

CREATE FUNCTION careos_guard_credential_historical_evidence()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE operation_key text:=nullif(current_setting('app.current_operation_key',true),'');
BEGIN
    IF OLD.status IN ('rejected','revoked','expired','superseded')
       AND NEW IS DISTINCT FROM OLD THEN
        RAISE EXCEPTION 'terminal credential evidence is immutable' USING ERRCODE='55000';
    END IF;
    IF OLD.status<>'in_review'
       AND NEW.current_verification_id IS DISTINCT FROM OLD.current_verification_id THEN
        RAISE EXCEPTION 'credential verification evidence is immutable' USING ERRCODE='55000';
    END IF;
    IF ROW(NEW.practitioner_profile_id,NEW.workforce_member_id,
           NEW.credential_type_entry_id,NEW.credential_type_version_id,
           NEW.issuer,NEW.issued_on,NEW.expires_on,NEW.risk_tier,
           NEW.supersedes_id,NEW.content_digest)
       IS DISTINCT FROM
       ROW(OLD.practitioner_profile_id,OLD.workforce_member_id,
           OLD.credential_type_entry_id,OLD.credential_type_version_id,
           OLD.issuer,OLD.issued_on,OLD.expires_on,OLD.risk_tier,
           OLD.supersedes_id,OLD.content_digest)
       AND NOT (OLD.status IN ('draft','evidence_pending')
                AND operation_key='credential.record.manage') THEN
        RAISE EXCEPTION 'credential content is immutable' USING ERRCODE='55000';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER practitioner_credentials_guard_historical_evidence
    BEFORE UPDATE ON practitioner_credentials
    FOR EACH ROW EXECUTE FUNCTION careos_guard_credential_historical_evidence();

CREATE FUNCTION careos_guard_scope_historical_evidence()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.lifecycle_state IN ('rejected','superseded','ended')
       AND NEW IS DISTINCT FROM OLD THEN
        RAISE EXCEPTION 'terminal scope evidence is immutable' USING ERRCODE='55000';
    END IF;
    IF OLD.lifecycle_state IN ('approved','suspended')
       AND ROW(NEW.submitted_revision,NEW.result_digest,NEW.submitted_by,NEW.submitted_at,
               NEW.decided_by,NEW.decision_code,NEW.decided_at)
           IS DISTINCT FROM
           ROW(OLD.submitted_revision,OLD.result_digest,OLD.submitted_by,OLD.submitted_at,
               OLD.decided_by,OLD.decision_code,OLD.decided_at) THEN
        RAISE EXCEPTION 'approved scope decision evidence is immutable' USING ERRCODE='55000';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER scopes_of_practice_guard_historical_evidence
    BEFORE UPDATE ON scopes_of_practice
    FOR EACH ROW EXECUTE FUNCTION careos_guard_scope_historical_evidence();
