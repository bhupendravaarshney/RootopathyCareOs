-- Enforce the approved M3 state machines below the application boundary. The
-- generic tenant-write trigger proves tenant/actor/operation/revision context;
-- these guards additionally constrain the exact lifecycle edge and immutable
-- evidence shape for direct application-role SQL.

CREATE FUNCTION careos_guard_m3_patient_profile_transition()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    op text := nullif(current_setting('app.current_operation_key', true), '');
BEGIN
    IF current_user <> '${applicationRole}' THEN
        RETURN NEW;
    END IF;

    IF TG_OP = 'INSERT' THEN
        IF op IS DISTINCT FROM 'patient.registration.manage'
           OR NEW.lifecycle_state IS DISTINCT FROM 'draft'
           OR NEW.merged_into_patient_id IS NOT NULL THEN
            RAISE EXCEPTION 'invalid Module 3 patient creation transition'
                USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;

    IF ROW(NEW.lifecycle_state,NEW.merged_into_patient_id)
       IS NOT DISTINCT FROM ROW(OLD.lifecycle_state,OLD.merged_into_patient_id) THEN
        RETURN NEW;
    END IF;

    IF op = 'patient.registration.submit' THEN
        IF OLD.lifecycle_state NOT IN ('draft','pending_review')
           OR NEW.lifecycle_state IS DISTINCT FROM 'active'
           OR NEW.merged_into_patient_id IS NOT NULL THEN
            RAISE EXCEPTION 'invalid Module 3 registration activation transition'
                USING ERRCODE = '23514';
        END IF;
    ELSIF op = 'patient.profile.manage' THEN
        IF NEW.merged_into_patient_id IS NOT NULL
           OR NOT (
                (OLD.lifecycle_state='active' AND NEW.lifecycle_state='inactive')
                OR (OLD.lifecycle_state='inactive' AND NEW.lifecycle_state='active')
                OR (OLD.lifecycle_state IN
                        ('draft','pending_review','active','inactive','deceased')
                    AND NEW.lifecycle_state='entered_in_error')
           ) THEN
            RAISE EXCEPTION 'invalid Module 3 patient lifecycle transition'
                USING ERRCODE = '23514';
        END IF;
    ELSIF op = 'patient.merge.execute' THEN
        IF OLD.lifecycle_state IN ('merged','entered_in_error')
           OR NEW.lifecycle_state IS DISTINCT FROM 'merged'
           OR NEW.merged_into_patient_id IS NULL
           OR NEW.merged_into_patient_id=NEW.id
           OR NOT EXISTS (
                SELECT 1 FROM patient_profiles target
                WHERE target.organization_id=NEW.organization_id
                  AND target.id=NEW.merged_into_patient_id
                  AND target.lifecycle_state NOT IN ('merged','entered_in_error')) THEN
            RAISE EXCEPTION 'invalid Module 3 patient merge transition'
                USING ERRCODE = '23514';
        END IF;
    ELSE
        RAISE EXCEPTION 'operation cannot change Module 3 patient lifecycle'
            USING ERRCODE = '42501';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER patient_profiles_lifecycle_guard
    BEFORE INSERT OR UPDATE ON patient_profiles
    FOR EACH ROW EXECUTE FUNCTION careos_guard_m3_patient_profile_transition();

CREATE FUNCTION careos_guard_m3_registration_transition()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    op text := nullif(current_setting('app.current_operation_key', true), '');
BEGIN
    IF current_user <> '${applicationRole}' THEN
        RETURN NEW;
    END IF;

    IF TG_OP = 'INSERT' THEN
        IF op IS DISTINCT FROM 'patient.registration.start'
           OR NEW.status IS DISTINCT FROM 'collecting'
           OR NEW.current_step IS DISTINCT FROM 'search'
           OR NEW.selected_patient_id IS NOT NULL
           OR NEW.completed_patient_id IS NOT NULL
           OR NEW.completed_at IS NOT NULL THEN
            RAISE EXCEPTION 'invalid Module 3 registration creation transition'
                USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;

    IF ROW(
        NEW.registration_source,NEW.supplier_relationship_key,NEW.purpose_key,
        NEW.facility_id,NEW.creator_id,NEW.expires_at,NEW.urgent,
        NEW.urgent_reason_code,NEW.provenance_source,NEW.classification,
        NEW.policy_version,NEW.created_at,NEW.created_by)
       IS DISTINCT FROM ROW(
        OLD.registration_source,OLD.supplier_relationship_key,OLD.purpose_key,
        OLD.facility_id,OLD.creator_id,OLD.expires_at,OLD.urgent,
        OLD.urgent_reason_code,OLD.provenance_source,OLD.classification,
        OLD.policy_version,OLD.created_at,OLD.created_by) THEN
        RAISE EXCEPTION 'immutable Module 3 registration context changed'
            USING ERRCODE = '23514';
    END IF;

    IF op = 'patient.duplicate.search' THEN
        IF OLD.status NOT IN ('collecting','duplicate_review')
           OR NEW.status IS DISTINCT FROM 'duplicate_review'
           OR NEW.current_step IS DISTINCT FROM 'search'
           OR NEW.duplicate_search_completed_at IS NULL
           OR NEW.duplicate_detector_version IS NULL
           OR NEW.duplicate_result_digest IS NULL
           OR ROW(NEW.duplicate_disposition,NEW.selected_patient_id,
                  NEW.validation_digest,NEW.validation_completed_at,
                  NEW.validation_schema_version,NEW.validation_source_revision,
                  NEW.validated_patient_revision,NEW.validation_expires_at,
                  NEW.validation_result,NEW.completed_patient_id,NEW.completed_at)
              IS DISTINCT FROM
              ROW(OLD.duplicate_disposition,OLD.selected_patient_id,
                  OLD.validation_digest,OLD.validation_completed_at,
                  OLD.validation_schema_version,OLD.validation_source_revision,
                  OLD.validated_patient_revision,OLD.validation_expires_at,
                  OLD.validation_result,OLD.completed_patient_id,OLD.completed_at) THEN
            RAISE EXCEPTION 'invalid Module 3 duplicate-search transition'
                USING ERRCODE = '23514';
        END IF;
    ELSIF op = 'patient.registration.manage' THEN
        IF NEW.status='ready' THEN
            IF OLD.status NOT IN ('collecting','ready')
               OR NEW.current_step IS DISTINCT FROM 'review'
               OR NEW.validation_digest IS NULL
               OR NEW.validation_completed_at IS NULL
               OR NEW.validation_schema_version IS NULL
               OR NEW.validation_source_revision IS NULL
               OR NEW.validated_patient_revision IS NULL
               OR NEW.validation_expires_at IS NULL
               OR NEW.validation_result IS NULL
               OR ROW(NEW.duplicate_search_completed_at,
                      NEW.duplicate_detector_version,NEW.duplicate_result_digest,
                      NEW.duplicate_disposition,NEW.selected_patient_id,
                      NEW.completed_patient_id,NEW.completed_at)
                  IS DISTINCT FROM
                  ROW(OLD.duplicate_search_completed_at,
                      OLD.duplicate_detector_version,OLD.duplicate_result_digest,
                      OLD.duplicate_disposition,OLD.selected_patient_id,
                      OLD.completed_patient_id,OLD.completed_at) THEN
                RAISE EXCEPTION 'invalid Module 3 registration validation transition'
                    USING ERRCODE = '23514';
            END IF;
        ELSE
            IF OLD.status IS DISTINCT FROM 'duplicate_review'
               OR NEW.status NOT IN ('collecting','duplicate_review')
               OR NEW.duplicate_disposition IS NULL
               OR (NEW.status='collecting'
                   AND (NEW.selected_patient_id IS NULL
                        OR NEW.duplicate_disposition NOT IN ('create_new','use_existing','urgent_temporary')
                        OR NEW.current_step IS DISTINCT FROM 'identity'))
               OR (NEW.status='duplicate_review'
                   AND (NEW.selected_patient_id IS NOT NULL
                        OR NEW.duplicate_disposition IS DISTINCT FROM 'escalate_review'
                        OR NEW.current_step IS DISTINCT FROM 'search'))
               OR ROW(NEW.duplicate_search_completed_at,
                      NEW.duplicate_detector_version,NEW.duplicate_result_digest,
                      NEW.validation_digest,NEW.validation_completed_at,
                      NEW.validation_schema_version,NEW.validation_source_revision,
                      NEW.validated_patient_revision,NEW.validation_expires_at,
                      NEW.validation_result,NEW.completed_patient_id,NEW.completed_at)
                  IS DISTINCT FROM
                  ROW(OLD.duplicate_search_completed_at,
                      OLD.duplicate_detector_version,OLD.duplicate_result_digest,
                      OLD.validation_digest,OLD.validation_completed_at,
                      OLD.validation_schema_version,OLD.validation_source_revision,
                      OLD.validated_patient_revision,OLD.validation_expires_at,
                      OLD.validation_result,OLD.completed_patient_id,OLD.completed_at) THEN
                RAISE EXCEPTION 'invalid Module 3 registration disposition transition'
                    USING ERRCODE = '23514';
            END IF;
        END IF;
    ELSIF op = 'patient.registration.submit' THEN
        IF OLD.status IS DISTINCT FROM 'ready'
           OR NEW.status IS DISTINCT FROM 'completed'
           OR NEW.current_step IS DISTINCT FROM 'complete'
           OR NEW.completed_patient_id IS DISTINCT FROM OLD.selected_patient_id
           OR NEW.completed_at IS NULL
           OR ROW(NEW.duplicate_search_completed_at,
                  NEW.duplicate_detector_version,NEW.duplicate_result_digest,
                  NEW.duplicate_disposition,NEW.selected_patient_id,
                  NEW.validation_digest,NEW.validation_completed_at,
                  NEW.validation_schema_version,NEW.validation_source_revision,
                  NEW.validated_patient_revision,NEW.validation_expires_at,
                  NEW.validation_result)
              IS DISTINCT FROM
              ROW(OLD.duplicate_search_completed_at,
                  OLD.duplicate_detector_version,OLD.duplicate_result_digest,
                  OLD.duplicate_disposition,OLD.selected_patient_id,
                  OLD.validation_digest,OLD.validation_completed_at,
                  OLD.validation_schema_version,OLD.validation_source_revision,
                  OLD.validated_patient_revision,OLD.validation_expires_at,
                  OLD.validation_result) THEN
            RAISE EXCEPTION 'invalid Module 3 registration completion transition'
                USING ERRCODE = '23514';
        END IF;
    ELSE
        RAISE EXCEPTION 'operation cannot change Module 3 registration state'
            USING ERRCODE = '42501';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER patient_registration_runs_lifecycle_guard
    BEFORE INSERT OR UPDATE ON patient_registration_runs
    FOR EACH ROW EXECUTE FUNCTION careos_guard_m3_registration_transition();

CREATE FUNCTION careos_guard_m3_duplicate_transition()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    op text := nullif(current_setting('app.current_operation_key', true), '');
    actor uuid := nullif(current_setting('app.current_actor_id', true), '')::uuid;
BEGIN
    IF current_user <> '${applicationRole}' THEN
        RETURN NEW;
    END IF;

    IF TG_OP = 'INSERT' THEN
        IF op IS DISTINCT FROM 'patient.registration.manage'
           OR NEW.status IS DISTINCT FROM 'open'
           OR NEW.assigned_reviewer_id IS NOT NULL
           OR NEW.lease_reference IS NOT NULL
           OR NEW.lease_expires_at IS NOT NULL
           OR NEW.disposition IS NOT NULL
           OR NEW.disposition_reason_code IS NOT NULL
           OR NEW.dismissed_snapshot_digest IS NOT NULL
           OR NOT EXISTS (
                SELECT 1 FROM patient_profiles patient
                WHERE patient.organization_id=NEW.organization_id
                  AND patient.id=NEW.patient_a_id
                  AND patient.lock_version=NEW.patient_a_revision)
           OR NOT EXISTS (
                SELECT 1 FROM patient_profiles patient
                WHERE patient.organization_id=NEW.organization_id
                  AND patient.id=NEW.patient_b_id
                  AND patient.lock_version=NEW.patient_b_revision) THEN
            RAISE EXCEPTION 'invalid Module 3 duplicate-candidate creation'
                USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;

    IF ROW(
        NEW.id,NEW.organization_id,NEW.patient_a_id,NEW.patient_b_id,
        NEW.patient_a_revision,NEW.patient_b_revision,NEW.detector_version,
        NEW.factor_categories,NEW.result_digest,NEW.match_band,
        NEW.calibrated_score,NEW.priority_key,NEW.review_due_at,
        NEW.provenance_source,NEW.classification,NEW.policy_version,
        NEW.created_at,NEW.created_by)
       IS DISTINCT FROM ROW(
        OLD.id,OLD.organization_id,OLD.patient_a_id,OLD.patient_b_id,
        OLD.patient_a_revision,OLD.patient_b_revision,OLD.detector_version,
        OLD.factor_categories,OLD.result_digest,OLD.match_band,
        OLD.calibrated_score,OLD.priority_key,OLD.review_due_at,
        OLD.provenance_source,OLD.classification,OLD.policy_version,
        OLD.created_at,OLD.created_by) THEN
        RAISE EXCEPTION 'immutable Module 3 duplicate evidence changed'
            USING ERRCODE = '23514';
    END IF;

    IF op = 'patient.duplicate.review' AND NEW.status='under_review' THEN
        IF OLD.status NOT IN ('open','under_review')
           OR (OLD.status='under_review'
               AND OLD.assigned_reviewer_id IS DISTINCT FROM actor
               AND OLD.lease_expires_at>clock_timestamp())
           OR NEW.assigned_reviewer_id IS DISTINCT FROM actor
           OR NEW.lease_reference IS NULL
           OR NEW.lease_expires_at<=clock_timestamp()
           OR NEW.lease_expires_at>clock_timestamp()+interval '15 minutes 5 seconds'
           OR ROW(NEW.disposition,NEW.disposition_reason_code,
                  NEW.dismissed_snapshot_digest)
              IS DISTINCT FROM
              ROW(OLD.disposition,OLD.disposition_reason_code,
                  OLD.dismissed_snapshot_digest) THEN
            RAISE EXCEPTION 'invalid Module 3 duplicate claim transition'
                USING ERRCODE = '23514';
        END IF;
    ELSIF op = 'patient.duplicate.review'
          AND NEW.status IN ('open','dismissed') THEN
        IF OLD.status IS DISTINCT FROM 'under_review'
           OR OLD.assigned_reviewer_id IS DISTINCT FROM actor
           OR OLD.lease_expires_at<=clock_timestamp()
           OR NEW.assigned_reviewer_id IS NOT NULL
           OR NEW.lease_reference IS NOT NULL
           OR NEW.lease_expires_at IS NOT NULL
           OR NEW.disposition NOT IN
                ('not_duplicate','same_patient_no_merge','insufficient_evidence')
           OR NEW.disposition_reason_code IS NULL
           OR NEW.dismissed_snapshot_digest IS NULL
           OR (NEW.disposition='insufficient_evidence' AND NEW.status<>'open')
           OR (NEW.disposition<>'insufficient_evidence' AND NEW.status<>'dismissed') THEN
            RAISE EXCEPTION 'invalid Module 3 duplicate disposition transition'
                USING ERRCODE = '23514';
        END IF;
    ELSIF op = 'patient.merge.request' THEN
        IF OLD.status IS DISTINCT FROM 'under_review'
           OR OLD.assigned_reviewer_id IS DISTINCT FROM actor
           OR OLD.lease_expires_at<=clock_timestamp()
           OR NEW.status IS DISTINCT FROM 'merge_requested'
           OR NEW.disposition IS DISTINCT FROM 'merge_requested'
           OR NEW.disposition_reason_code IS NULL
           OR ROW(NEW.assigned_reviewer_id,NEW.lease_reference,NEW.lease_expires_at)
              IS DISTINCT FROM
              ROW(OLD.assigned_reviewer_id,OLD.lease_reference,OLD.lease_expires_at) THEN
            RAISE EXCEPTION 'invalid Module 3 duplicate merge-request transition'
                USING ERRCODE = '23514';
        END IF;
    ELSIF op = 'patient.merge.execute' THEN
        IF OLD.status IS DISTINCT FROM 'merge_requested'
           OR NEW.status IS DISTINCT FROM 'resolved'
           OR ROW(NEW.assigned_reviewer_id,NEW.lease_reference,
                  NEW.lease_expires_at,NEW.disposition,
                  NEW.disposition_reason_code,NEW.dismissed_snapshot_digest)
              IS DISTINCT FROM
              ROW(OLD.assigned_reviewer_id,OLD.lease_reference,
                  OLD.lease_expires_at,OLD.disposition,
                  OLD.disposition_reason_code,OLD.dismissed_snapshot_digest) THEN
            RAISE EXCEPTION 'invalid Module 3 duplicate resolution transition'
                USING ERRCODE = '23514';
        END IF;
    ELSE
        RAISE EXCEPTION 'operation cannot change Module 3 duplicate state'
            USING ERRCODE = '42501';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER patient_duplicate_candidates_lifecycle_guard
    BEFORE INSERT OR UPDATE ON patient_duplicate_candidates
    FOR EACH ROW EXECUTE FUNCTION careos_guard_m3_duplicate_transition();

CREATE FUNCTION careos_guard_m3_merge_request_transition()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    op text := nullif(current_setting('app.current_operation_key', true), '');
    actor uuid := nullif(current_setting('app.current_actor_id', true), '')::uuid;
BEGIN
    IF current_user <> '${applicationRole}' THEN
        RETURN NEW;
    END IF;

    IF TG_OP = 'INSERT' THEN
        IF op IS DISTINCT FROM 'patient.merge.request'
           OR NEW.status IS DISTINCT FROM 'submitted'
           OR NEW.requested_by IS DISTINCT FROM actor
           OR NEW.submitted_at IS NULL
           OR NEW.expires_at<=clock_timestamp()
           OR NEW.expires_at>clock_timestamp()+interval '24 hours 5 seconds'
           OR NEW.survivor_patient_id=NEW.duplicate_patient_id
           OR NOT EXISTS (
                SELECT 1 FROM patient_duplicate_candidates candidate
                WHERE candidate.organization_id=NEW.organization_id
                  AND candidate.id=NEW.duplicate_candidate_id
                  AND candidate.status='under_review'
                  AND candidate.assigned_reviewer_id=actor
                  AND candidate.lease_expires_at>clock_timestamp()
                  AND candidate.patient_a_id IN
                        (NEW.survivor_patient_id,NEW.duplicate_patient_id)
                  AND candidate.patient_b_id IN
                        (NEW.survivor_patient_id,NEW.duplicate_patient_id))
           OR NOT EXISTS (
                SELECT 1 FROM patient_profiles patient
                WHERE patient.organization_id=NEW.organization_id
                  AND patient.id=NEW.survivor_patient_id
                  AND patient.lock_version=NEW.survivor_revision
                  AND patient.lifecycle_state NOT IN ('merged','entered_in_error'))
           OR NOT EXISTS (
                SELECT 1 FROM patient_profiles patient
                WHERE patient.organization_id=NEW.organization_id
                  AND patient.id=NEW.duplicate_patient_id
                  AND patient.lock_version=NEW.duplicate_revision
                  AND patient.lifecycle_state NOT IN ('merged','entered_in_error')) THEN
            RAISE EXCEPTION 'invalid Module 3 merge-request creation'
                USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;

    IF ROW(
        NEW.id,NEW.organization_id,NEW.duplicate_candidate_id,
        NEW.survivor_patient_id,NEW.duplicate_patient_id,
        NEW.survivor_revision,NEW.duplicate_revision,
        NEW.field_disposition_digest,NEW.reference_inventory_digest,
        NEW.impact_digest,NEW.affected_reference_count,NEW.reason_code,
        NEW.requested_by,NEW.submitted_at,NEW.expires_at,
        NEW.provenance_source,NEW.classification,NEW.policy_version,
        NEW.created_at,NEW.created_by)
       IS DISTINCT FROM ROW(
        OLD.id,OLD.organization_id,OLD.duplicate_candidate_id,
        OLD.survivor_patient_id,OLD.duplicate_patient_id,
        OLD.survivor_revision,OLD.duplicate_revision,
        OLD.field_disposition_digest,OLD.reference_inventory_digest,
        OLD.impact_digest,OLD.affected_reference_count,OLD.reason_code,
        OLD.requested_by,OLD.submitted_at,OLD.expires_at,
        OLD.provenance_source,OLD.classification,OLD.policy_version,
        OLD.created_at,OLD.created_by) THEN
        RAISE EXCEPTION 'immutable Module 3 merge-request evidence changed'
            USING ERRCODE = '23514';
    END IF;

    IF op = 'patient.merge.decide' THEN
        IF OLD.status IS DISTINCT FROM 'submitted'
           OR OLD.expires_at<=clock_timestamp()
           OR OLD.requested_by=actor
           OR NEW.status NOT IN ('approved','rejected')
           OR NEW.executed_at IS NOT NULL THEN
            RAISE EXCEPTION 'invalid Module 3 merge decision transition'
                USING ERRCODE = '23514';
        END IF;
    ELSIF op = 'patient.merge.execute' THEN
        IF OLD.status IS DISTINCT FROM 'approved'
           OR OLD.expires_at<=clock_timestamp()
           OR OLD.requested_by=actor
           OR NEW.status IS DISTINCT FROM 'executed'
           OR NEW.executed_at IS NULL
           OR NEW.executed_at>clock_timestamp()+interval '5 seconds' THEN
            RAISE EXCEPTION 'invalid Module 3 merge execution transition'
                USING ERRCODE = '23514';
        END IF;
    ELSE
        RAISE EXCEPTION 'operation cannot change Module 3 merge-request state'
            USING ERRCODE = '42501';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER patient_merge_requests_lifecycle_guard
    BEFORE INSERT OR UPDATE ON patient_merge_requests
    FOR EACH ROW EXECUTE FUNCTION careos_guard_m3_merge_request_transition();

CREATE FUNCTION careos_guard_m3_merge_decision_insert()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    op text := nullif(current_setting('app.current_operation_key', true), '');
    actor uuid := nullif(current_setting('app.current_actor_id', true), '')::uuid;
BEGIN
    IF current_user <> '${applicationRole}' THEN
        RETURN NEW;
    END IF;
    IF op IS DISTINCT FROM 'patient.merge.decide'
       OR NEW.checker_id IS DISTINCT FROM actor
       OR NEW.decision_expires_at<=clock_timestamp()
       OR NEW.decision_expires_at>clock_timestamp()+interval '30 minutes 5 seconds'
       OR NEW.status IS DISTINCT FROM
            (CASE WHEN NEW.decision='approve' THEN 'approved' ELSE 'rejected' END)
       OR NOT EXISTS (
            SELECT 1 FROM patient_merge_requests request
            WHERE request.organization_id=NEW.organization_id
              AND request.id=NEW.merge_request_id
              AND request.status='submitted'
              AND request.expires_at>clock_timestamp()
              AND request.requested_by<>actor
              AND request.lock_version=NEW.request_revision
              AND request.impact_digest=NEW.impact_digest) THEN
        RAISE EXCEPTION 'invalid Module 3 merge-decision creation'
            USING ERRCODE = '42501';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER patient_merge_decisions_creation_guard
    BEFORE INSERT ON patient_merge_decisions
    FOR EACH ROW EXECUTE FUNCTION careos_guard_m3_merge_decision_insert();

COMMENT ON FUNCTION careos_guard_m3_patient_profile_transition() IS
    'Constrains patient activation, lifecycle and merge edges for application-role SQL.';
COMMENT ON FUNCTION careos_guard_m3_registration_transition() IS
    'Constrains registration search, disposition, validation and completion edges.';
COMMENT ON FUNCTION careos_guard_m3_duplicate_transition() IS
    'Constrains duplicate claim, lease, disposition, merge-request and resolution edges.';
COMMENT ON FUNCTION careos_guard_m3_merge_request_transition() IS
    'Constrains immutable merge request creation, independent decision and execution edges.';
COMMENT ON FUNCTION careos_guard_m3_merge_decision_insert() IS
    'Rejects self, stale, mismatched or overlong Module 3 merge decisions below the application boundary.';
