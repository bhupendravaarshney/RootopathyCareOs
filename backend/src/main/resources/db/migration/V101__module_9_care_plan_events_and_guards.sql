CREATE TEMP TABLE m9_audit_seed (
    event_name varchar(160) PRIMARY KEY,
    subject_type varchar(120) NOT NULL,
    operation_key varchar(160) NOT NULL,
    required_keys text[] NOT NULL,
    allowed_keys text[] NOT NULL
) ON COMMIT DROP;

INSERT INTO m9_audit_seed VALUES
 ('care_plan.created','care_plan','care-plan.create',
  ARRAY['carePlanId','carePlanVersionId','patientId','encounterId','status','revision'],
  ARRAY['carePlanId','carePlanVersionId','patientId','encounterId','status','revision']),
 ('care_plan.priority_added','care_plan_priority','care-plan.priority.add',
  ARRAY['carePlanId','carePlanVersionId','priorityId','sequence','priority','revision'],
  ARRAY['carePlanId','carePlanVersionId','priorityId','sequence','priority','revision']),
 ('care_plan.goal_added','care_plan_goal','care-plan.goal.add',
  ARRAY['carePlanId','carePlanVersionId','goalId','sequence','goalType','revision'],
  ARRAY['carePlanId','carePlanVersionId','goalId','sequence','goalType','revision']),
 ('care_plan.intervention_added','care_plan_intervention','care-plan.intervention.add',
  ARRAY['carePlanId','carePlanVersionId','interventionId','sequence','modality','revision'],
  ARRAY['carePlanId','carePlanVersionId','interventionId','sequence','modality','revision']),
 ('care_plan.assignment_recorded','intervention_assignment','care-plan.assignment.manage',
  ARRAY['carePlanId','carePlanVersionId','interventionId','assignmentId','clinicalTaskId','revision'],
  ARRAY['carePlanId','carePlanVersionId','interventionId','assignmentId','clinicalTaskId','revision']),
 ('care_plan.consent_recorded','plan_consent','care-plan.consent.record',
  ARRAY['carePlanId','carePlanVersionId','planConsentId','consentVersion','consentStatus','revision'],
  ARRAY['carePlanId','carePlanVersionId','planConsentId','consentVersion','consentStatus','revision']),
 ('care_plan.interaction_reviewed','interaction_review','care-plan.safety.review',
  ARRAY['carePlanId','carePlanVersionId','interactionReviewId','reviewVersion','digest','safetyOutcome','revision'],
  ARRAY['carePlanId','carePlanVersionId','interactionReviewId','reviewVersion','digest','safetyOutcome','revision']),
 ('care_plan.submitted','care_plan','care-plan.submit',
  ARRAY['carePlanId','carePlanVersionId','digest','fromStatus','status','revision'],
  ARRAY['carePlanId','carePlanVersionId','digest','fromStatus','status','revision']),
 ('care_plan.approved','care_plan_approval','care-plan.approve',
  ARRAY['carePlanId','carePlanVersionId','carePlanApprovalId','digest','status','revision'],
  ARRAY['carePlanId','carePlanVersionId','carePlanApprovalId','digest','status','revision']),
 ('care_plan.activated','care_plan','care-plan.activate',
  ARRAY['carePlanId','carePlanVersionId','fromStatus','status','revision'],
  ARRAY['carePlanId','carePlanVersionId','fromStatus','status','revision']),
 ('care_plan.amended','care_plan_amendment','care-plan.amend',
  ARRAY['carePlanId','carePlanVersionId','successorCarePlanId','successorVersionId','carePlanAmendmentId','digest','status','revision'],
  ARRAY['carePlanId','carePlanVersionId','successorCarePlanId','successorVersionId','carePlanAmendmentId','digest','status','revision']),
 ('care_plan.closed','care_plan','care-plan.close',
  ARRAY['carePlanId','carePlanVersionId','fromStatus','status','revision'],
  ARRAY['carePlanId','carePlanVersionId','fromStatus','status','revision']);

INSERT INTO audit_event_definitions
    (event_name,schema_version,display_name,description,subject_type,reason_required,
     required_payload_keys,allowed_payload_keys,payload_schema,status,registry_version)
SELECT event_name,1,replace(initcap(replace(event_name,'.',' ')),'_',' '),
       'CareOS Module 9 governed care-plan evidence.',subject_type,true,
       required_keys,allowed_keys,'{"type":"object","additionalProperties":false}'::jsonb,
       'active','m9-standing-direction-v1'
FROM m9_audit_seed;

INSERT INTO authorization_operation_events
    (operation_key,event_kind,event_name,schema_version,status,registry_version)
SELECT operation_key,'audit',event_name,1,'active','m9-standing-direction-v1'
FROM m9_audit_seed;

CREATE TEMP TABLE m9_outbox_seed (
    event_name varchar(180) PRIMARY KEY,
    aggregate_type varchar(120) NOT NULL,
    operation_key varchar(160) NOT NULL,
    required_keys text[] NOT NULL,
    allowed_keys text[] NOT NULL
) ON COMMIT DROP;

INSERT INTO m9_outbox_seed VALUES
 ('m9.care-plan-created.v1','care_plan','care-plan.create',
  ARRAY['carePlanId','carePlanVersionId','patientId','encounterId','status'],
  ARRAY['carePlanId','carePlanVersionId','patientId','encounterId','status']),
 ('m9.care-plan-task-assigned.v1','care_plan','care-plan.assignment.manage',
  ARRAY['carePlanId','interventionId','assignmentId','clinicalTaskId'],
  ARRAY['carePlanId','interventionId','assignmentId','clinicalTaskId']),
 ('m9.care-plan-submitted.v1','care_plan','care-plan.submit',
  ARRAY['carePlanId','carePlanVersionId','digest','status'],
  ARRAY['carePlanId','carePlanVersionId','digest','status']),
 ('m9.care-plan-approved.v1','care_plan','care-plan.approve',
  ARRAY['carePlanId','carePlanVersionId','digest','status'],
  ARRAY['carePlanId','carePlanVersionId','digest','status']),
 ('m9.care-plan-activated.v1','care_plan','care-plan.activate',
  ARRAY['carePlanId','carePlanVersionId','status'],
  ARRAY['carePlanId','carePlanVersionId','status']),
 ('m9.care-plan-amended.v1','care_plan','care-plan.amend',
  ARRAY['carePlanId','successorCarePlanId','successorVersionId','status'],
  ARRAY['carePlanId','successorCarePlanId','successorVersionId','status']),
 ('m9.care-plan-closed.v1','care_plan','care-plan.close',
  ARRAY['carePlanId','carePlanVersionId','status'],
  ARRAY['carePlanId','carePlanVersionId','status']);

INSERT INTO outbox_event_definitions
    (event_name,schema_version,description,aggregate_type,required_payload_keys,
     allowed_payload_keys,payload_schema,status,registry_version)
SELECT event_name,1,'CareOS Module 9 transactional care-plan event.',aggregate_type,
       required_keys,allowed_keys,'{"type":"object","additionalProperties":false}'::jsonb,
       'active','m9-standing-direction-v1'
FROM m9_outbox_seed;

INSERT INTO authorization_operation_events
    (operation_key,event_kind,event_name,schema_version,status,registry_version)
SELECT operation_key,'outbox',event_name,1,'active','m9-standing-direction-v1'
FROM m9_outbox_seed;

CREATE FUNCTION careos_m9_plan_digest(requested_organization uuid, requested_version uuid)
RETURNS char(64) LANGUAGE sql STABLE STRICT AS $$
    SELECT encode(sha256(convert_to(concat_ws('|',
        version.id::text,version.version_number::text,version.clinical_summary,version.patient_summary,
        coalesce((SELECT string_agg(concat_ws(':',p.priority_sequence,p.source_type,p.display_text,p.rationale,p.priority_key),'|' ORDER BY p.priority_sequence)
                  FROM care_plan_priorities p WHERE p.organization_id=requested_organization AND p.care_plan_version_id=version.id),''),
        coalesce((SELECT string_agg(concat_ws(':',g.goal_sequence,g.goal_type,g.description_text,g.measure_text,g.target_text,coalesce(g.target_date::text,''),g.priority_key),'|' ORDER BY g.goal_sequence)
                  FROM care_plan_goals g WHERE g.organization_id=requested_organization AND g.care_plan_version_id=version.id),''),
        coalesce((SELECT string_agg(concat_ws(':',i.intervention_sequence,i.modality_key,i.intervention_name,i.rationale,i.priority_key,i.planned_start_date::text,i.review_date::text,i.stop_criteria,i.monitoring_instructions,i.evidence_status),'|' ORDER BY i.intervention_sequence)
                  FROM care_plan_interventions i WHERE i.organization_id=requested_organization AND i.care_plan_version_id=version.id),''),
        coalesce((SELECT string_agg(concat_ws(':',a.intervention_id::text,a.owner_practitioner_id::text,a.responsibility_text,a.assigned_start_date::text,a.review_date::text),'|' ORDER BY a.intervention_id)
                  FROM intervention_assignments a WHERE a.organization_id=requested_organization AND a.care_plan_version_id=version.id),''),
        coalesce((SELECT string_agg(concat_ws(':',c.consent_version,c.consent_status,coalesce(c.consent_reference,''),c.preferences_text,coalesce(c.communication_needs,'')),'|' ORDER BY c.consent_version)
                  FROM plan_consents c WHERE c.organization_id=requested_organization AND c.care_plan_version_id=version.id),'')),
        'UTF8')),'hex')::char(64)
    FROM care_plan_versions version
    WHERE version.organization_id=requested_organization AND version.id=requested_version
$$;

CREATE FUNCTION careos_guard_m9_plan()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE op text:=nullif(current_setting('app.current_operation_key',true),'');
BEGIN
    IF current_user<>'${applicationRole}' THEN RETURN NEW; END IF;
    IF TG_OP='INSERT' THEN
        IF op NOT IN ('care-plan.create','care-plan.amend') OR NEW.status<>'draft'
           OR NEW.current_version_id IS NOT NULL
           OR NOT EXISTS (
                SELECT 1 FROM encounters encounter
                JOIN practitioner_profiles practitioner
                  ON practitioner.organization_id=encounter.organization_id
                 AND practitioner.id=NEW.responsible_practitioner_id
                WHERE encounter.organization_id=NEW.organization_id
                  AND encounter.id=NEW.encounter_id AND encounter.patient_id=NEW.patient_id
                  AND encounter.responsible_practitioner_id=NEW.responsible_practitioner_id
                  AND encounter.status IN ('arrived','in_progress','on_hold')
                  AND practitioner.status='active' AND practitioner.lifecycle_state='active')
           OR (NEW.source_assessment_session_id IS NOT NULL AND NOT EXISTS (
                SELECT 1 FROM assessment_sessions assessment
                WHERE assessment.organization_id=NEW.organization_id
                  AND assessment.id=NEW.source_assessment_session_id
                  AND assessment.patient_id=NEW.patient_id
                  AND assessment.encounter_id=NEW.encounter_id))
           OR (NEW.source_ai_review_id IS NOT NULL AND NOT EXISTS (
                SELECT 1 FROM ai_reviews review
                JOIN ai_sessions session
                  ON session.organization_id=review.organization_id
                 AND session.id=review.ai_session_id
                WHERE review.organization_id=NEW.organization_id
                  AND review.id=NEW.source_ai_review_id AND review.decision='accepted'
                  AND session.patient_id=NEW.patient_id AND session.encounter_id=NEW.encounter_id)) THEN
            RAISE EXCEPTION 'invalid Module 9 care-plan creation' USING ERRCODE='23514';
        END IF;
    ELSE
        IF NEW.patient_id IS DISTINCT FROM OLD.patient_id
           OR NEW.encounter_id IS DISTINCT FROM OLD.encounter_id
           OR NEW.responsible_practitioner_id IS DISTINCT FROM OLD.responsible_practitioner_id
           OR NEW.source_assessment_session_id IS DISTINCT FROM OLD.source_assessment_session_id
           OR NEW.source_ai_review_id IS DISTINCT FROM OLD.source_ai_review_id
           OR NEW.supersedes_care_plan_id IS DISTINCT FROM OLD.supersedes_care_plan_id
           OR NEW.plan_title IS DISTINCT FROM OLD.plan_title THEN
            RAISE EXCEPTION 'Module 9 care-plan identity is immutable' USING ERRCODE='23514';
        END IF;
        IF OLD.current_version_id IS NULL THEN
            IF op NOT IN ('care-plan.create','care-plan.amend') OR NEW.current_version_id IS NULL
               OR NEW.status<>'draft' THEN
                RAISE EXCEPTION 'invalid Module 9 initial version binding' USING ERRCODE='23514';
            END IF;
        ELSIF NEW.current_version_id IS DISTINCT FROM OLD.current_version_id THEN
            RAISE EXCEPTION 'Module 9 current version cannot be replaced in place' USING ERRCODE='23514';
        ELSIF op IN ('care-plan.priority.add','care-plan.goal.add','care-plan.intervention.add',
                     'care-plan.assignment.manage','care-plan.consent.record','care-plan.safety.review') THEN
            IF OLD.status<>'draft' OR NEW.status<>'draft' THEN
                RAISE EXCEPTION 'Module 9 plan content is frozen outside draft' USING ERRCODE='23514';
            END IF;
        ELSIF op='care-plan.activate' AND NOT EXISTS (
            SELECT 1 FROM care_plan_approvals approval
            JOIN care_plan_versions version
              ON version.organization_id=approval.organization_id
             AND version.id=approval.care_plan_version_id
            WHERE approval.organization_id=NEW.organization_id
              AND approval.care_plan_id=NEW.id
              AND version.id=NEW.current_version_id
              AND approval.approved_version_digest=version.content_digest
              AND approval.decision='approved') THEN
            RAISE EXCEPTION 'care plan activation requires exact approval evidence' USING ERRCODE='23514';
        ELSIF NOT (
            (op='care-plan.submit' AND OLD.status='draft' AND NEW.status='review')
            OR (op='care-plan.approve' AND OLD.status='review' AND NEW.status='approved')
            OR (op='care-plan.activate' AND OLD.status='approved' AND NEW.status='active')
            OR (op='care-plan.amend' AND OLD.status='active' AND NEW.status='revised')
            OR (op='care-plan.close' AND OLD.status IN ('draft','review','approved','active')
                AND NEW.status IN ('completed','cancelled')
                AND (NEW.status<>'completed' OR OLD.status='active'))
        ) THEN
            RAISE EXCEPTION 'invalid Module 9 care-plan lifecycle transition' USING ERRCODE='23514';
        END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER care_plans_lifecycle_guard
    BEFORE INSERT OR UPDATE ON care_plans
    FOR EACH ROW EXECUTE FUNCTION careos_guard_m9_plan();

CREATE FUNCTION careos_guard_m9_version()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE op text:=nullif(current_setting('app.current_operation_key',true),'');
BEGIN
    IF current_user<>'${applicationRole}' THEN RETURN NEW; END IF;
    IF TG_OP='INSERT' THEN
        IF op NOT IN ('care-plan.create','care-plan.amend') OR NEW.status<>'draft'
           OR NEW.content_digest IS NOT NULL OR NEW.frozen_at IS NOT NULL THEN
            RAISE EXCEPTION 'invalid Module 9 plan-version creation' USING ERRCODE='23514';
        END IF;
    ELSE
        IF NEW.care_plan_id IS DISTINCT FROM OLD.care_plan_id
           OR NEW.version_number IS DISTINCT FROM OLD.version_number
           OR NEW.clinical_summary IS DISTINCT FROM OLD.clinical_summary
           OR NEW.patient_summary IS DISTINCT FROM OLD.patient_summary THEN
            RAISE EXCEPTION 'Module 9 version content is immutable' USING ERRCODE='23514';
        END IF;
        IF NOT (
            (op='care-plan.submit' AND OLD.status='draft' AND NEW.status='review'
             AND OLD.content_digest IS NULL AND NEW.content_digest=careos_m9_plan_digest(NEW.organization_id,NEW.id)
             AND NEW.frozen_at BETWEEN clock_timestamp()-interval '5 seconds' AND clock_timestamp()+interval '5 seconds')
            OR (op='care-plan.approve' AND OLD.status='review' AND NEW.status='approved'
                AND NEW.content_digest=OLD.content_digest AND NEW.frozen_at=OLD.frozen_at)
            OR (op='care-plan.activate' AND OLD.status='approved' AND NEW.status='active'
                AND NEW.content_digest=OLD.content_digest AND NEW.frozen_at=OLD.frozen_at)
            OR (op='care-plan.amend' AND OLD.status='active' AND NEW.status='superseded'
                AND NEW.content_digest=OLD.content_digest AND NEW.frozen_at=OLD.frozen_at)
            OR (op='care-plan.close' AND OLD.status IN ('draft','review','approved','active')
                AND NEW.status IN ('completed','cancelled')
                AND (OLD.status<>'draft' OR (NEW.content_digest IS NULL AND NEW.frozen_at IS NULL)))
        ) THEN
            RAISE EXCEPTION 'invalid Module 9 plan-version lifecycle transition' USING ERRCODE='23514';
        END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER care_plan_versions_lifecycle_guard
    BEFORE INSERT OR UPDATE ON care_plan_versions
    FOR EACH ROW EXECUTE FUNCTION careos_guard_m9_version();

CREATE FUNCTION careos_guard_m9_draft_child()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF current_user<>'${applicationRole}' THEN RETURN NEW; END IF;
    IF NOT EXISTS (
        SELECT 1 FROM care_plan_versions version
        JOIN care_plans plan ON plan.organization_id=version.organization_id
                            AND plan.id=version.care_plan_id
        WHERE version.organization_id=NEW.organization_id
          AND version.id=NEW.care_plan_version_id
          AND version.status='draft' AND plan.status='draft'
          AND plan.current_version_id=version.id) THEN
        RAISE EXCEPTION 'Module 9 content may be appended only to the current draft version' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER care_plan_priorities_draft_guard BEFORE INSERT ON care_plan_priorities
    FOR EACH ROW EXECUTE FUNCTION careos_guard_m9_draft_child();
CREATE TRIGGER care_plan_goals_draft_guard BEFORE INSERT ON care_plan_goals
    FOR EACH ROW EXECUTE FUNCTION careos_guard_m9_draft_child();
CREATE TRIGGER care_plan_interventions_draft_guard BEFORE INSERT ON care_plan_interventions
    FOR EACH ROW EXECUTE FUNCTION careos_guard_m9_draft_child();
CREATE TRIGGER intervention_assignments_draft_guard BEFORE INSERT ON intervention_assignments
    FOR EACH ROW EXECUTE FUNCTION careos_guard_m9_draft_child();
CREATE TRIGGER plan_consents_draft_guard BEFORE INSERT ON plan_consents
    FOR EACH ROW EXECUTE FUNCTION careos_guard_m9_draft_child();
CREATE TRIGGER interaction_reviews_draft_guard BEFORE INSERT ON interaction_reviews
    FOR EACH ROW EXECUTE FUNCTION careos_guard_m9_draft_child();

CREATE FUNCTION careos_guard_m9_assignment()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF current_user<>'${applicationRole}' THEN RETURN NEW; END IF;
    IF NOT EXISTS (
        SELECT 1 FROM care_plan_interventions intervention
        JOIN practitioner_profiles practitioner
          ON practitioner.organization_id=intervention.organization_id
         AND practitioner.id=NEW.owner_practitioner_id
        WHERE intervention.organization_id=NEW.organization_id
          AND intervention.id=NEW.intervention_id
          AND intervention.care_plan_version_id=NEW.care_plan_version_id
          AND practitioner.status='active' AND practitioner.lifecycle_state='active') THEN
        RAISE EXCEPTION 'invalid Module 9 intervention owner or version' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER intervention_assignments_owner_guard BEFORE INSERT ON intervention_assignments
    FOR EACH ROW EXECUTE FUNCTION careos_guard_m9_assignment();

CREATE FUNCTION careos_guard_m9_consent()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF current_user<>'${applicationRole}' THEN RETURN NEW; END IF;
    IF NEW.recorded_at<clock_timestamp()-interval '5 minutes'
       OR NEW.recorded_at>clock_timestamp()+interval '5 seconds'
       OR NOT EXISTS (
            SELECT 1 FROM practitioner_profiles practitioner
            WHERE practitioner.organization_id=NEW.organization_id
              AND practitioner.id=NEW.recorded_by_practitioner_id
              AND practitioner.status='active' AND practitioner.lifecycle_state='active') THEN
        RAISE EXCEPTION 'invalid Module 9 consent attribution' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER plan_consents_attribution_guard BEFORE INSERT ON plan_consents
    FOR EACH ROW EXECUTE FUNCTION careos_guard_m9_consent();

CREATE FUNCTION careos_guard_m9_interaction_review()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF current_user<>'${applicationRole}' THEN RETURN NEW; END IF;
    IF NEW.reviewed_version_digest<>careos_m9_plan_digest(NEW.organization_id,NEW.care_plan_version_id)
       OR NEW.reviewed_at<clock_timestamp()-interval '5 minutes'
       OR NEW.reviewed_at>clock_timestamp()+interval '5 seconds'
       OR NOT EXISTS (
            SELECT 1 FROM practitioner_profiles practitioner
            WHERE practitioner.organization_id=NEW.organization_id
              AND practitioner.id=NEW.reviewed_by_practitioner_id
              AND practitioner.status='active' AND practitioner.lifecycle_state='active') THEN
        RAISE EXCEPTION 'invalid Module 9 interaction review evidence' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER interaction_reviews_evidence_guard BEFORE INSERT ON interaction_reviews
    FOR EACH ROW EXECUTE FUNCTION careos_guard_m9_interaction_review();

CREATE FUNCTION careos_guard_m9_review_submission()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE version_id uuid; calculated_digest char(64);
BEGIN
    IF current_user<>'${applicationRole}' OR NEW.status<>'review' THEN RETURN NEW; END IF;
    version_id:=NEW.current_version_id;
    calculated_digest:=careos_m9_plan_digest(NEW.organization_id,version_id);
    IF NOT EXISTS (SELECT 1 FROM care_plan_priorities WHERE organization_id=NEW.organization_id AND care_plan_version_id=version_id)
       OR NOT EXISTS (SELECT 1 FROM care_plan_goals WHERE organization_id=NEW.organization_id AND care_plan_version_id=version_id)
       OR NOT EXISTS (SELECT 1 FROM care_plan_interventions WHERE organization_id=NEW.organization_id AND care_plan_version_id=version_id)
       OR EXISTS (
            SELECT 1 FROM care_plan_interventions intervention
            WHERE intervention.organization_id=NEW.organization_id
              AND intervention.care_plan_version_id=version_id
              AND NOT EXISTS (
                  SELECT 1 FROM intervention_assignments assignment
                  JOIN clinical_tasks task
                    ON task.organization_id=assignment.organization_id
                   AND task.intervention_assignment_id=assignment.id
                  WHERE assignment.organization_id=intervention.organization_id
                    AND assignment.intervention_id=intervention.id
                    AND task.care_plan_id=NEW.id AND task.care_plan_version_id=version_id
                    AND task.status IN ('open','in_progress','acknowledged')))
       OR NOT EXISTS (
            SELECT 1 FROM plan_consents consent
            WHERE consent.organization_id=NEW.organization_id
              AND consent.care_plan_version_id=version_id
              AND consent.consent_status IN ('granted','not_required')
              AND consent.consent_version=(SELECT max(latest.consent_version) FROM plan_consents latest
                                           WHERE latest.organization_id=consent.organization_id
                                             AND latest.care_plan_version_id=consent.care_plan_version_id))
       OR NOT EXISTS (
            SELECT 1 FROM interaction_reviews review
            WHERE review.organization_id=NEW.organization_id
              AND review.care_plan_version_id=version_id
              AND review.safety_outcome='clear'
              AND review.reviewed_version_digest=calculated_digest
              AND review.review_version=(SELECT max(latest.review_version) FROM interaction_reviews latest
                                         WHERE latest.organization_id=review.organization_id
                                           AND latest.care_plan_version_id=review.care_plan_version_id)) THEN
        RAISE EXCEPTION 'care plan is incomplete or lacks current consent and clear interaction review' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER care_plans_submission_completeness_guard
    BEFORE UPDATE ON care_plans
    FOR EACH ROW EXECUTE FUNCTION careos_guard_m9_review_submission();

CREATE FUNCTION careos_guard_m9_approval()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF current_user<>'${applicationRole}' THEN RETURN NEW; END IF;
    IF NEW.recent_authentication_at<clock_timestamp()-interval '10 minutes'
       OR NEW.recent_authentication_at>clock_timestamp()+interval '5 seconds'
       OR NEW.mfa_authenticated_at<clock_timestamp()-interval '10 minutes'
       OR NEW.mfa_authenticated_at>clock_timestamp()+interval '5 seconds'
       OR NOT EXISTS (
            SELECT 1 FROM care_plans plan
            JOIN care_plan_versions version
              ON version.organization_id=plan.organization_id AND version.id=plan.current_version_id
            JOIN encounters encounter
              ON encounter.organization_id=plan.organization_id AND encounter.id=plan.encounter_id
            JOIN practitioner_profiles practitioner
              ON practitioner.organization_id=plan.organization_id
             AND practitioner.id=NEW.approver_practitioner_id
            WHERE plan.organization_id=NEW.organization_id AND plan.id=NEW.care_plan_id
              AND version.id=NEW.care_plan_version_id
              AND plan.status='review' AND version.status='review'
              AND version.content_digest=NEW.approved_version_digest
              AND plan.responsible_practitioner_id=NEW.approver_practitioner_id
              AND encounter.responsible_practitioner_id=NEW.approver_practitioner_id
              AND encounter.status IN ('arrived','in_progress','on_hold')
              AND practitioner.status='active' AND practitioner.lifecycle_state='active') THEN
        RAISE EXCEPTION 'invalid Module 9 clinician approval evidence' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER care_plan_approvals_decision_guard BEFORE INSERT ON care_plan_approvals
    FOR EACH ROW EXECUTE FUNCTION careos_guard_m9_approval();

CREATE FUNCTION careos_guard_m9_amendment()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF current_user<>'${applicationRole}' THEN RETURN NEW; END IF;
    IF NOT EXISTS (
        SELECT 1 FROM care_plans prior
        JOIN care_plan_versions prior_version
          ON prior_version.organization_id=prior.organization_id AND prior_version.id=NEW.prior_version_id
        JOIN care_plans successor
          ON successor.organization_id=prior.organization_id AND successor.id=NEW.successor_care_plan_id
        JOIN care_plan_versions successor_version
          ON successor_version.organization_id=successor.organization_id
         AND successor_version.id=NEW.successor_version_id
        WHERE prior.organization_id=NEW.organization_id AND prior.id=NEW.prior_care_plan_id
          AND prior.status='active' AND prior.current_version_id=prior_version.id
          AND prior_version.status='active' AND prior_version.content_digest=NEW.prior_version_digest
          AND successor.status='draft' AND successor.supersedes_care_plan_id=prior.id
          AND successor.current_version_id=successor_version.id AND successor_version.status='draft'
          AND successor.patient_id=prior.patient_id AND successor.encounter_id=prior.encounter_id
          AND successor.responsible_practitioner_id=prior.responsible_practitioner_id
          AND NEW.amended_by_practitioner_id=prior.responsible_practitioner_id) THEN
        RAISE EXCEPTION 'invalid Module 9 amendment lineage' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER care_plan_amendments_lineage_guard BEFORE INSERT ON care_plan_amendments
    FOR EACH ROW EXECUTE FUNCTION careos_guard_m9_amendment();

-- Extend the existing shared clinical-task aggregate without weakening Module 5 operations.
CREATE OR REPLACE FUNCTION careos_validate_m5_tenant_write()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    configured_organization uuid:=nullif(current_setting('app.current_organization_id',true),'')::uuid;
    configured_actor uuid:=nullif(current_setting('app.current_actor_id',true),'')::uuid;
    configured_operation text:=nullif(current_setting('app.current_operation_key',true),'');
    allowed_operations text[];
    expected_registry text;
BEGIN
    IF current_user<>'${applicationRole}' THEN RETURN NEW; END IF;
    allowed_operations:=CASE TG_TABLE_NAME
        WHEN 'episodes_of_care' THEN ARRAY['encounter.open','encounter.lifecycle.manage']
        WHEN 'encounters' THEN ARRAY['encounter.open','encounter.lifecycle.manage']
        WHEN 'encounter_participants' THEN ARRAY['encounter.open','encounter.participant.manage']
        WHEN 'encounter_status_history' THEN ARRAY['encounter.open','encounter.lifecycle.manage']
        WHEN 'presenting_concerns' THEN ARRAY['encounter.concern.write']
        WHEN 'clinical_problems' THEN ARRAY['encounter.problem.write']
        WHEN 'diagnoses' THEN ARRAY['encounter.problem.write']
        WHEN 'encounter_notes' THEN ARRAY['encounter.note.write','encounter.note.sign','encounter.amend']
        WHEN 'note_versions' THEN ARRAY['encounter.note.write']
        WHEN 'orders' THEN ARRAY['encounter.order.manage']
        WHEN 'clinical_tasks' THEN ARRAY['encounter.concern.write','encounter.task.manage','encounter.red_flag.acknowledge','care-plan.assignment.manage']
        WHEN 'encounter_signatures' THEN ARRAY['encounter.note.sign']
        WHEN 'amendments' THEN ARRAY['encounter.amend']
        WHEN 'red_flag_escalations' THEN ARRAY['encounter.concern.write','encounter.red_flag.acknowledge']
        ELSE ARRAY[]::text[]
    END;
    expected_registry:=CASE WHEN configured_operation='care-plan.assignment.manage'
                            THEN 'm9-standing-direction-v1' ELSE 'm5-standing-direction-v1' END;
    IF NEW.organization_id IS DISTINCT FROM configured_organization
       OR configured_actor IS NULL OR configured_operation IS NULL
       OR NOT configured_operation=ANY(allowed_operations)
       OR NOT EXISTS (
            SELECT 1 FROM authorization_operations operation
            JOIN authorization_registry_releases release
              ON release.registry_version=operation.registry_version AND release.status='active'
            WHERE operation.operation_key=configured_operation
              AND operation.registry_version=expected_registry
              AND operation.status='active') THEN
        RAISE EXCEPTION 'invalid Module 5/9 operation-bound tenant write context' USING ERRCODE='42501';
    END IF;
    IF TG_OP='INSERT' THEN
        IF NEW.created_by IS DISTINCT FROM configured_actor
           OR NEW.updated_by IS DISTINCT FROM configured_actor OR NEW.lock_version<>0 THEN
            RAISE EXCEPTION 'invalid Module 5/9 creation evidence' USING ERRCODE='23514';
        END IF;
    ELSE
        IF NEW.id IS DISTINCT FROM OLD.id OR NEW.organization_id IS DISTINCT FROM OLD.organization_id
           OR NEW.created_at IS DISTINCT FROM OLD.created_at OR NEW.created_by IS DISTINCT FROM OLD.created_by
           OR NEW.updated_by IS DISTINCT FROM configured_actor OR NEW.lock_version<>OLD.lock_version+1
           OR NEW.updated_at<OLD.updated_at OR NEW.updated_at>clock_timestamp()+interval '5 seconds' THEN
            RAISE EXCEPTION 'invalid Module 5/9 revision evidence' USING ERRCODE='23514';
        END IF;
    END IF;
    RETURN NEW;
END $$;

CREATE OR REPLACE FUNCTION careos_guard_m5_task()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE op text:=nullif(current_setting('app.current_operation_key',true),'');
BEGIN
    IF current_user<>'${applicationRole}' THEN RETURN NEW; END IF;
    IF NEW.care_plan_id IS NOT NULL THEN
        IF TG_OP<>'INSERT' OR op<>'care-plan.assignment.manage' OR NEW.status<>'open'
           OR NEW.owner_practitioner_id IS NULL OR NOT NEW.requires_acknowledgement
           OR NEW.due_at<=clock_timestamp()
           OR NOT EXISTS (
                SELECT 1 FROM intervention_assignments assignment
                JOIN care_plan_versions version
                  ON version.organization_id=assignment.organization_id
                 AND version.id=assignment.care_plan_version_id
                JOIN care_plans plan
                  ON plan.organization_id=version.organization_id AND plan.id=version.care_plan_id
                WHERE assignment.organization_id=NEW.organization_id
                  AND assignment.id=NEW.intervention_assignment_id
                  AND assignment.care_plan_version_id=NEW.care_plan_version_id
                  AND assignment.owner_practitioner_id=NEW.owner_practitioner_id
                  AND plan.id=NEW.care_plan_id AND plan.patient_id=NEW.patient_id
                  AND plan.encounter_id=NEW.encounter_id
                  AND plan.status='draft' AND version.status='draft') THEN
            RAISE EXCEPTION 'invalid Module 9 care-plan clinical task' USING ERRCODE='23514';
        END IF;
    ELSIF TG_OP='INSERT' THEN
        IF op NOT IN ('encounter.concern.write','encounter.task.manage') OR NEW.status<>'open'
           OR (op='encounter.concern.write' AND
               (NOT NEW.requires_acknowledgement OR NEW.priority_key<>'critical' OR NEW.source_concern_id IS NULL)) THEN
            RAISE EXCEPTION 'invalid Module 5 clinical task creation' USING ERRCODE='23514';
        END IF;
    ELSIF NEW.source_concern_id IS NOT NULL THEN
        IF op<>'encounter.red_flag.acknowledge'
           OR NOT ((OLD.status='open' AND NEW.status='acknowledged')
                OR (OLD.status='acknowledged' AND NEW.status='completed')) THEN
            RAISE EXCEPTION 'red-flag tasks require the explicit acknowledgement workflow' USING ERRCODE='23514';
        END IF;
    ELSIF op<>'encounter.task.manage'
       OR NOT ((OLD.status='open' AND NEW.status IN ('in_progress','completed','cancelled','entered_in_error'))
            OR (OLD.status='in_progress' AND NEW.status IN ('completed','cancelled','entered_in_error'))) THEN
        RAISE EXCEPTION 'invalid Module 5 clinical task transition' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;

CREATE FUNCTION careos_reject_m9_delete()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Module 9 clinical evidence cannot be deleted' USING ERRCODE='42501';
END $$;
CREATE TRIGGER care_plans_no_delete BEFORE DELETE ON care_plans
    FOR EACH ROW EXECUTE FUNCTION careos_reject_m9_delete();
CREATE TRIGGER care_plan_versions_no_delete BEFORE DELETE ON care_plan_versions
    FOR EACH ROW EXECUTE FUNCTION careos_reject_m9_delete();

COMMENT ON TABLE clinical_tasks IS
    'Shared encounter and care-plan task aggregate; care-plan tasks retain exact plan/version/intervention-assignment provenance and accountable ownership.';
