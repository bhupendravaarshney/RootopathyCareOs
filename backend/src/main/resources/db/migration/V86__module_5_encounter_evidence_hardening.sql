CREATE FUNCTION careos_reject_m5_clinical_rewrite()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF current_user='${applicationRole}' THEN
        RAISE EXCEPTION 'Module 5 clinical assertions are append-only' USING ERRCODE='42501';
    END IF;
    RETURN OLD;
END $$;

DO $$
DECLARE table_name text;
BEGIN
    FOREACH table_name IN ARRAY ARRAY[
        'presenting_concerns','clinical_problems','diagnoses','orders'
    ] LOOP
        EXECUTE format(
            'CREATE TRIGGER %I BEFORE UPDATE OR DELETE ON %I FOR EACH ROW EXECUTE FUNCTION careos_reject_m5_clinical_rewrite()',
            table_name||'_immutable',table_name);
    END LOOP;
END $$;

CREATE FUNCTION careos_guard_m5_status_history()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    actor uuid:=nullif(current_setting('app.current_actor_id',true),'')::uuid;
    op text:=nullif(current_setting('app.current_operation_key',true),'');
    previous_status text;
BEGIN
    IF current_user<>'${applicationRole}' THEN RETURN NEW; END IF;
    SELECT history.to_status INTO previous_status
      FROM encounter_status_history history
     WHERE history.organization_id=NEW.organization_id
       AND history.encounter_id=NEW.encounter_id
     ORDER BY history.effective_at DESC,history.id DESC LIMIT 1;
    IF op NOT IN ('encounter.open','encounter.lifecycle.manage')
       OR NEW.actor_id IS DISTINCT FROM actor
       OR NEW.policy_version<>'m5-standing-direction-v1'
       OR NEW.effective_at<clock_timestamp()-interval '5 seconds'
       OR NEW.effective_at>clock_timestamp()+interval '5 seconds'
       OR char_length(btrim(NEW.reason_code))<2
       OR NOT EXISTS (SELECT 1 FROM encounters encounter
            WHERE encounter.organization_id=NEW.organization_id
              AND encounter.id=NEW.encounter_id
              AND encounter.status=NEW.to_status)
       OR (op='encounter.open' AND NOT (
            NEW.from_status IS NULL AND NEW.to_status='planned' AND previous_status IS NULL))
       OR (op='encounter.lifecycle.manage' AND NOT (
            NEW.from_status IS NOT NULL
            AND previous_status IS NOT DISTINCT FROM NEW.from_status)) THEN
        RAISE EXCEPTION 'invalid Module 5 encounter status evidence' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER encounter_status_history_transition_guard
    BEFORE INSERT ON encounter_status_history
    FOR EACH ROW EXECUTE FUNCTION careos_guard_m5_status_history();

CREATE FUNCTION careos_guard_m5_note_identity()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF current_user='${applicationRole}' AND
       ROW(NEW.encounter_id,NEW.note_type_key,NEW.regulated_content)
       IS DISTINCT FROM
       ROW(OLD.encounter_id,OLD.note_type_key,OLD.regulated_content) THEN
        RAISE EXCEPTION 'Module 5 note identity is immutable' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER encounter_notes_identity_guard
    BEFORE UPDATE ON encounter_notes
    FOR EACH ROW EXECUTE FUNCTION careos_guard_m5_note_identity();

CREATE FUNCTION careos_guard_m5_task_identity()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF current_user='${applicationRole}' AND
       ROW(NEW.encounter_id,NEW.patient_id,NEW.source_concern_id,NEW.task_type_key,
           NEW.description_text,NEW.priority_key,NEW.owner_practitioner_id,
           NEW.requires_acknowledgement)
       IS DISTINCT FROM
       ROW(OLD.encounter_id,OLD.patient_id,OLD.source_concern_id,OLD.task_type_key,
           OLD.description_text,OLD.priority_key,OLD.owner_practitioner_id,
           OLD.requires_acknowledgement) THEN
        RAISE EXCEPTION 'Module 5 task identity and content are immutable' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER clinical_tasks_identity_guard
    BEFORE UPDATE ON clinical_tasks
    FOR EACH ROW EXECUTE FUNCTION careos_guard_m5_task_identity();

CREATE OR REPLACE FUNCTION careos_guard_m5_amendment()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    actor uuid:=nullif(current_setting('app.current_actor_id',true),'')::uuid;
    encounter_record encounters%ROWTYPE;
BEGIN
    IF current_user<>'${applicationRole}' THEN RETURN NEW; END IF;
    SELECT encounter.* INTO encounter_record FROM encounters encounter
     WHERE encounter.organization_id=NEW.organization_id AND encounter.id=NEW.encounter_id;
    IF nullif(current_setting('app.current_operation_key',true),'')<>'encounter.amend'
       OR encounter_record.id IS NULL
       OR encounter_record.status NOT IN ('in_progress','on_hold','completed')
       OR NEW.amendment_digest<>encode(digest(NEW.amendment_text,'sha256'),'hex')
       OR NEW.reason_text IS DISTINCT FROM
          nullif(current_setting('app.current_authorization_reason',true),'')
       OR NOT careos_m5_actor_is_practitioner(
            NEW.organization_id,actor,NEW.author_practitioner_id,NEW.amended_at)
       OR NOT careos_m5_practitioner_eligible(
            NEW.organization_id,NEW.author_practitioner_id,encounter_record.service_id,
            encounter_record.facility_id,encounter_record.location_id,NEW.amended_at)
       OR NOT EXISTS (SELECT 1 FROM practitioner_eligibility_evidence evidence
            WHERE evidence.organization_id=NEW.organization_id
              AND evidence.id=NEW.eligibility_evidence_id
              AND evidence.result_digest=NEW.eligibility_digest
              AND evidence.practitioner_profile_id=NEW.author_practitioner_id
              AND evidence.service_id=encounter_record.service_id
              AND evidence.facility_id=encounter_record.facility_id
              AND (evidence.location_id IS NULL
                   OR evidence.location_id=encounter_record.location_id)
              AND evidence.outcome='eligible' AND evidence.status='eligible'
              AND evidence.evaluated_from<=NEW.amended_at
              AND (evidence.evaluated_to IS NULL OR evidence.evaluated_to>NEW.amended_at)
              AND evidence.expires_at>NEW.amended_at)
       OR NOT EXISTS (SELECT 1 FROM encounter_signatures signature
            JOIN note_versions version ON version.organization_id=signature.organization_id
             AND version.id=signature.note_version_id
            WHERE signature.organization_id=NEW.organization_id
              AND signature.id=NEW.prior_signature_id
              AND signature.encounter_id=NEW.encounter_id
              AND signature.encounter_note_id=NEW.encounter_note_id
              AND signature.note_version_id=NEW.amended_note_version_id
              AND signature.status='signed'
              AND version.content_digest=signature.signed_content_digest) THEN
        RAISE EXCEPTION 'invalid Module 5 signed amendment' USING ERRCODE='42501';
    END IF;
    RETURN NEW;
END $$;

CREATE OR REPLACE FUNCTION careos_guard_m5_red_flag_escalation()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    op text:=nullif(current_setting('app.current_operation_key',true),'');
    actor uuid:=nullif(current_setting('app.current_actor_id',true),'')::uuid;
    authorization_reason text:=nullif(current_setting('app.current_authorization_reason',true),'');
    encounter_record encounters%ROWTYPE;
BEGIN
    IF current_user<>'${applicationRole}' THEN RETURN NEW; END IF;
    SELECT encounter.* INTO encounter_record FROM encounters encounter
     WHERE encounter.organization_id=NEW.organization_id AND encounter.id=NEW.encounter_id;
    IF TG_OP='INSERT' THEN
        IF op<>'encounter.concern.write' OR NEW.status<>'raised'
           OR NEW.acknowledged_at IS NOT NULL OR NEW.resolved_at IS NOT NULL
           OR encounter_record.id IS NULL
           OR NEW.patient_id IS DISTINCT FROM encounter_record.patient_id
           OR NOT careos_m5_actor_is_practitioner(
                NEW.organization_id,actor,NEW.raised_by_practitioner_id,NEW.raised_at)
           OR NOT EXISTS (SELECT 1 FROM presenting_concerns concern
                JOIN clinical_tasks task ON task.organization_id=concern.organization_id
                 AND task.id=NEW.clinical_task_id
                WHERE concern.organization_id=NEW.organization_id
                  AND concern.id=NEW.presenting_concern_id
                  AND concern.encounter_id=NEW.encounter_id
                  AND concern.red_flag
                  AND concern.severity_key=NEW.severity_key
                  AND concern.author_practitioner_id=NEW.raised_by_practitioner_id
                  AND task.source_concern_id=concern.id
                  AND task.encounter_id=NEW.encounter_id
                  AND task.patient_id=NEW.patient_id
                  AND task.requires_acknowledgement
                  AND task.priority_key='critical') THEN
            RAISE EXCEPTION 'invalid Module 5 red-flag escalation' USING ERRCODE='23514';
        END IF;
    ELSIF op<>'encounter.red_flag.acknowledge'
       OR encounter_record.id IS NULL
       OR ROW(NEW.encounter_id,NEW.patient_id,NEW.presenting_concern_id,NEW.clinical_task_id,
              NEW.severity_key,NEW.policy_version,NEW.raised_at,NEW.raised_by_practitioner_id)
          IS DISTINCT FROM
          ROW(OLD.encounter_id,OLD.patient_id,OLD.presenting_concern_id,OLD.clinical_task_id,
              OLD.severity_key,OLD.policy_version,OLD.raised_at,OLD.raised_by_practitioner_id)
       OR NOT (
            (OLD.status='raised' AND NEW.status='acknowledged'
             AND NEW.acknowledged_at IS NOT NULL AND NEW.resolved_at IS NULL
             AND NEW.acknowledgement_reason IS NOT DISTINCT FROM authorization_reason
             AND careos_m5_actor_is_practitioner(
                  NEW.organization_id,actor,NEW.acknowledged_by_practitioner_id,NEW.acknowledged_at)
             AND careos_m5_practitioner_eligible(
                  NEW.organization_id,NEW.acknowledged_by_practitioner_id,
                  encounter_record.service_id,encounter_record.facility_id,
                  encounter_record.location_id,NEW.acknowledged_at))
            OR
            (OLD.status='acknowledged' AND NEW.status='resolved'
             AND NEW.acknowledged_at IS NOT DISTINCT FROM OLD.acknowledged_at
             AND NEW.acknowledged_by_practitioner_id IS NOT DISTINCT FROM OLD.acknowledged_by_practitioner_id
             AND NEW.acknowledgement_reason IS NOT DISTINCT FROM OLD.acknowledgement_reason
             AND NEW.resolved_at IS NOT NULL
             AND NEW.resolution_reason IS NOT DISTINCT FROM authorization_reason
             AND careos_m5_actor_is_practitioner(
                  NEW.organization_id,actor,NEW.resolved_by_practitioner_id,NEW.resolved_at)
             AND careos_m5_practitioner_eligible(
                  NEW.organization_id,NEW.resolved_by_practitioner_id,
                  encounter_record.service_id,encounter_record.facility_id,
                  encounter_record.location_id,NEW.resolved_at))
       ) THEN
        RAISE EXCEPTION 'invalid Module 5 red-flag acknowledgement' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;

COMMENT ON FUNCTION careos_guard_m5_status_history() IS
    'Binds append-only encounter status evidence to the actual current transition and authenticated actor.';
