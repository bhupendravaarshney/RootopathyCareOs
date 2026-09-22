DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM authorization_registry_releases
        WHERE registry_version='m2-candidate-1'
          AND module_key='M2'
          AND approval_record_id='M2-APPROVAL-20260921-01'
          AND approval_package_sha256='624df2edc0024526040271911d43a1b33a12e723fefb3beb3e985264cef89521'
          AND authorization_artifact_sha256='143f01aafc2a8f2a6f2bfade2365006ac8b781472696f1db233a1307b122ca32'
          AND approval_evidence_sha256='57676a269fee327d5a1f8c7458c901343c8b222af398025a98ca41c6b9aadbbe'
          AND status='active'
    ) THEN
        RAISE EXCEPTION 'Module 2 requires its active checksum-approved authorization release';
    END IF;
END;
$$;

SELECT set_config('app.current_organization_id','01900000-0000-7000-8000-000000000001',true);

WITH catalogues(registry_key,category,display_name) AS (
    VALUES
      ('workforce.profession','profession','Profession'),
      ('workforce.specialty','specialty','Specialty'),
      ('workforce.qualification_type','qualification_type','Qualification type'),
      ('workforce.regulator','regulator','Regulator'),
      ('workforce.registration_type','registration_type','Registration type'),
      ('workforce.credential_type','credential_type','Credential type'),
      ('workforce.credential_risk_tier','credential_risk_tier','Credential risk tier'),
      ('workforce.scope_activity','scope_activity','Scope activity'),
      ('workforce.scope_restriction','scope_restriction','Scope restriction'),
      ('workforce.scope_requirement','scope_requirement','Scope requirement'),
      ('workforce.employment_category','employment_category','Employment category'),
      ('workforce.assignment_type','assignment_type','Assignment type'),
      ('workforce.position','position','Position'),
      ('workforce.supervision_mode','supervision_mode','Supervision mode'),
      ('workforce.offboarding_reason','offboarding_reason','Offboarding reason'),
      ('workforce.notification_milestone','notification_milestone','Notification milestone'),
      ('workforce.notification_template','notification_template_metadata','Notification template metadata')
)
INSERT INTO workforce_registry_definitions
    (organization_id,registry_key,category,display_name,value_schema,review_cadence_days,
     lifecycle_state,status,created_by,updated_by)
SELECT '01900000-0000-7000-8000-000000000001',registry_key,category,display_name,
       '{"type":"object","additionalProperties":false,"properties":{"enabled":{"type":"boolean"}},"required":["enabled"]}'::jsonb,
       365,'active','active','00000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000001'
FROM catalogues
WHERE EXISTS (SELECT 1 FROM organizations WHERE id='01900000-0000-7000-8000-000000000001');

INSERT INTO workforce_registry_entries
    (organization_id,registry_definition_id,entry_key,code,display_label,jurisdiction_country,
     lifecycle_state,status,created_by,updated_by)
SELECT definition.organization_id,definition.id,'standard',
       upper(replace(definition.category,'_','-'))||'-STANDARD',
       CASE definition.category
         WHEN 'profession' THEN 'General practitioner'
         WHEN 'specialty' THEN 'General care'
         WHEN 'qualification_type' THEN 'Professional qualification'
         WHEN 'regulator' THEN 'Approved regulator'
         WHEN 'registration_type' THEN 'Professional registration'
         WHEN 'credential_type' THEN 'Identity and practice credential'
         WHEN 'credential_risk_tier' THEN 'Moderate risk'
         WHEN 'scope_activity' THEN 'General consultation'
         WHEN 'scope_restriction' THEN 'Supervision required'
         WHEN 'scope_requirement' THEN 'Current verified evidence'
         WHEN 'employment_category' THEN 'Standard engagement'
         WHEN 'assignment_type' THEN 'Primary workplace'
         WHEN 'position' THEN 'Team member'
         WHEN 'supervision_mode' THEN 'Direct supervision'
         WHEN 'offboarding_reason' THEN 'Engagement ended'
         WHEN 'notification_milestone' THEN 'Expiry reminder'
         WHEN 'notification_template_metadata' THEN 'Credential expiry email'
         ELSE 'Workforce legal obligation'
       END,
       CASE WHEN definition.category IN ('profession','specialty','qualification_type','regulator','registration_type','credential_type','scope_activity','scope_restriction','scope_requirement') THEN 'IN' ELSE NULL END,
       'active','active','00000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000001'
FROM workforce_registry_definitions definition
WHERE definition.organization_id='01900000-0000-7000-8000-000000000001';

INSERT INTO workforce_registry_versions
    (organization_id,registry_entry_id,version_number,version_fields,version_digest,
     effective_from,maker_id,checker_id,decision_code,activated_at,lifecycle_state,status,
     created_by,updated_by)
SELECT entry.organization_id,entry.id,1,'{"enabled":true}'::jsonb,
       '26b3426b2593763c96d0890b4a77a0bbf66d13fc512b0c6b138a23c290f30a2a',
       '2026-09-21T13:43:43.843Z',
       '00000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000002',
       'initial_approved_catalogue','2026-09-21T13:43:43.843Z','active','active',
       '00000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000001'
FROM workforce_registry_entries entry
WHERE entry.organization_id='01900000-0000-7000-8000-000000000001';

INSERT INTO workforce_configuration_snapshots
    (organization_id,display_number,snapshot_digest,policy_versions,maker_id,checker_id,
     activator_id,effective_at,status,created_by,updated_by)
SELECT '01900000-0000-7000-8000-000000000001','WCFG-2026-000001',
     '7489f86ac5a5418e09fa737cbcb65c1590c3d76ab6c28e63799264fca881acac',
     '{"authorization":"m2-candidate-1","eligibility":"m2-eligibility-v1","readiness":"m2-readiness-v1","registry":"m2-candidate-1"}'::jsonb,
     '00000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000002',
     '00000000-0000-0000-0000-000000000003','2026-09-21T13:43:43.843Z','active',
     '00000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000001'
WHERE EXISTS (SELECT 1 FROM organizations WHERE id='01900000-0000-7000-8000-000000000001');

CREATE INDEX workforce_configuration_effective_idx
    ON workforce_configuration_snapshots(organization_id,effective_at DESC,id)
    WHERE status='active' AND superseded_at IS NULL;

ALTER TABLE access_assignment_scopes
    ADD CONSTRAINT access_assignment_scopes_live_range_excl
    EXCLUDE USING gist (
        organization_id WITH =,
        access_assignment_id WITH =,
        workforce_member_id WITH =,
        COALESCE(facility_id,'00000000-0000-0000-0000-000000000000'::uuid) WITH =,
        COALESCE(organization_unit_id,'00000000-0000-0000-0000-000000000000'::uuid) WITH =,
        COALESCE(location_id,'00000000-0000-0000-0000-000000000000'::uuid) WITH =,
        tstzrange(effective_from,effective_to,'[)') WITH &&)
    WHERE (status IN ('approved','active'));

CREATE FUNCTION careos_validate_m2_person_age()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.birth_date IS NOT NULL AND NEW.birth_date > (current_date - interval '18 years')::date THEN
        RAISE EXCEPTION 'm2.working_age.ineligible' USING ERRCODE='23514';
    END IF;
    IF NEW.birth_date IS NOT NULL AND NEW.birth_date > current_date THEN
        RAISE EXCEPTION 'm2.field.format' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER person_profiles_working_age
    BEFORE INSERT OR UPDATE OF birth_date ON person_profiles
    FOR EACH ROW EXECUTE FUNCTION careos_validate_m2_person_age();

CREATE FUNCTION careos_validate_credential_verification()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    credential practitioner_credentials%ROWTYPE;
    clean_count integer;
BEGIN
    SELECT * INTO credential
    FROM practitioner_credentials
    WHERE organization_id=NEW.organization_id AND id=NEW.practitioner_credential_id
    FOR UPDATE;
    IF NOT FOUND OR credential.status NOT IN ('submitted','in_review')
       OR credential.lock_version<>NEW.credential_revision
       OR credential.content_digest<>NEW.credential_digest THEN
        RAISE EXCEPTION 'm2.credential.stale' USING ERRCODE='23514';
    END IF;
    IF NEW.reviewer_id=credential.submitted_by OR NEW.reviewer_id=credential.created_by
       OR EXISTS (
           SELECT 1 FROM credential_documents document
           WHERE document.organization_id=NEW.organization_id
             AND document.practitioner_credential_id=credential.id
             AND document.created_by=NEW.reviewer_id
       ) THEN
        RAISE EXCEPTION 'm2.credential.self_review' USING ERRCODE='42501';
    END IF;
    SELECT count(*) INTO clean_count
    FROM credential_documents document
    JOIN document_promotion_evidence promotion
      ON promotion.organization_id=document.organization_id
     AND promotion.document_id=document.platform_document_id
     AND promotion.object_version_id=document.platform_object_version_id
    WHERE document.organization_id=NEW.organization_id
      AND document.practitioner_credential_id=credential.id
      AND document.id=ANY(NEW.evidence_ids)
      AND document.status='clean';
    IF clean_count<>cardinality(NEW.evidence_ids) THEN
        RAISE EXCEPTION 'm2.credential.evidence_not_clean' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER credential_verifications_validate_decision
    BEFORE INSERT ON credential_verifications
    FOR EACH ROW EXECUTE FUNCTION careos_validate_credential_verification();

CREATE FUNCTION careos_validate_scope_decision()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF current_user='${applicationRole}' AND OLD.lifecycle_state IN ('submitted','in_review')
       AND NEW.lifecycle_state IN ('approved','rejected','changes_requested') THEN
        IF nullif(current_setting('app.current_operation_key',true),'')<>'practitioner.scope.approve'
           OR NEW.decided_by IS NULL OR NEW.decided_by=OLD.submitted_by
           OR NEW.result_digest IS DISTINCT FROM OLD.result_digest
           OR NEW.submitted_revision IS DISTINCT FROM OLD.submitted_revision THEN
            RAISE EXCEPTION 'm2.scope.self_approval_or_stale_result' USING ERRCODE='42501';
        END IF;
        IF NEW.lifecycle_state='approved' AND EXISTS (
            SELECT 1 FROM scope_requirements requirement
            WHERE requirement.organization_id=NEW.organization_id
              AND requirement.scope_definition_id=NEW.scope_definition_id
              AND requirement.status='active' AND requirement.mandatory
              AND NEW.result_digest IS NULL
        ) THEN
            RAISE EXCEPTION 'm2.scope.requirements_unmet' USING ERRCODE='23514';
        END IF;
    END IF;
    IF OLD.lifecycle_state IN ('approved','rejected','ended','superseded')
       AND ROW(NEW.scope_definition_id,NEW.definition_version_digest,NEW.effective_from,
               NEW.result_digest,NEW.submitted_revision)
           IS DISTINCT FROM
           ROW(OLD.scope_definition_id,OLD.definition_version_digest,OLD.effective_from,
               OLD.result_digest,OLD.submitted_revision) THEN
        RAISE EXCEPTION 'decided scope content is immutable' USING ERRCODE='55000';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER scopes_of_practice_validate_decision
    BEFORE UPDATE ON scopes_of_practice
    FOR EACH ROW EXECUTE FUNCTION careos_validate_scope_decision();

CREATE FUNCTION careos_validate_availability_period_overlap()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    candidate_start integer:=NEW.local_start_minute;
    candidate_end integer:=NEW.local_end_minute+CASE WHEN NEW.ends_next_day THEN 1440 ELSE 0 END;
BEGIN
    IF EXISTS (
        SELECT 1 FROM availability_periods period
        WHERE period.organization_id=NEW.organization_id
          AND period.availability_profile_id=NEW.availability_profile_id
          AND period.id<>NEW.id AND period.status='active'
          AND (
            (period.iso_weekday=NEW.iso_weekday AND
             int4range(period.local_start_minute,period.local_end_minute+CASE WHEN period.ends_next_day THEN 1440 ELSE 0 END,'[)')
             && int4range(candidate_start,candidate_end,'[)'))
            OR (period.ends_next_day AND (period.iso_weekday%7)+1=NEW.iso_weekday
                AND int4range(0,period.local_end_minute,'[)') && int4range(candidate_start,candidate_end,'[)'))
            OR (NEW.ends_next_day AND (NEW.iso_weekday%7)+1=period.iso_weekday
                AND int4range(0,NEW.local_end_minute,'[)') && int4range(period.local_start_minute,period.local_end_minute+CASE WHEN period.ends_next_day THEN 1440 ELSE 0 END,'[)'))
          )
    ) THEN
        RAISE EXCEPTION 'm2.effective.overlap' USING ERRCODE='23P01';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER availability_periods_reject_overlap
    BEFORE INSERT OR UPDATE ON availability_periods
    FOR EACH ROW EXECUTE FUNCTION careos_validate_availability_period_overlap();

CREATE FUNCTION careos_validate_activation_request()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE run workforce_readiness_runs%ROWTYPE;
BEGIN
    SELECT * INTO run FROM workforce_readiness_runs
    WHERE organization_id=NEW.organization_id AND id=NEW.readiness_run_id FOR SHARE;
    IF NOT FOUND OR run.workforce_member_id<>NEW.workforce_member_id OR run.status<>'complete'
       OR run.blocker_count<>0 OR run.result_digest<>NEW.result_digest
       OR run.expires_at<=clock_timestamp() OR NEW.expires_at>run.expires_at THEN
        RAISE EXCEPTION 'm2.eligibility.stale' USING ERRCODE='23514';
    END IF;
    IF NEW.checker_id IS NOT NULL AND (NEW.checker_id=NEW.maker_id OR NEW.checker_id=NEW.created_by) THEN
        RAISE EXCEPTION 'activation checker must be independent' USING ERRCODE='42501';
    END IF;
    IF NEW.activator_id IS NOT NULL AND NEW.activator_id=NEW.maker_id THEN
        RAISE EXCEPTION 'activation executor must be independent of the maker' USING ERRCODE='42501';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER workforce_activation_requests_validate
    BEFORE INSERT OR UPDATE ON workforce_activation_requests
    FOR EACH ROW EXECUTE FUNCTION careos_validate_activation_request();

CREATE FUNCTION careos_validate_lifecycle_transition_edge()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NOT (
        (NEW.from_state='draft' AND NEW.to_state='submitted') OR
        (NEW.from_state='submitted' AND NEW.to_state='active') OR
        (NEW.from_state='active' AND NEW.to_state IN ('suspended','offboarding')) OR
        (NEW.from_state='suspended' AND NEW.to_state IN ('active','offboarding')) OR
        (NEW.from_state='offboarding' AND NEW.to_state='offboarded')
    ) THEN
        RAISE EXCEPTION 'm2.lifecycle.invalid_transition' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER workforce_lifecycle_transitions_validate_edge
    BEFORE INSERT ON workforce_lifecycle_transitions
    FOR EACH ROW EXECUTE FUNCTION careos_validate_lifecycle_transition_edge();

COMMENT ON TABLE workforce_registry_definitions IS 'Allow-listed Module 2 workforce catalogue definitions; canonical application RBAC is excluded.';
COMMENT ON TABLE practitioner_eligibility_evidence IS 'Immutable point-in-time eligibility evidence; it never grants application access or rewrites historical care.';
COMMENT ON TABLE access_assignment_scopes IS 'Narrowing evidence over canonical M1 access assignments; rows cannot grant a role or permission.';
