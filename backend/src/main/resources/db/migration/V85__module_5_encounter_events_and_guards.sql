CREATE TEMP TABLE m5_audit_seed (
    event_name varchar(180) PRIMARY KEY,
    subject_type varchar(120) NOT NULL,
    operation_key varchar(160) NOT NULL,
    required_keys text[] NOT NULL,
    allowed_keys text[] NOT NULL,
    reason_required boolean NOT NULL
) ON COMMIT DROP;

INSERT INTO m5_audit_seed VALUES
 ('encounter.opened','encounter','encounter.open',
  ARRAY['encounterId','episodeId','patientId','sourceKind','status','revision'],
  ARRAY['encounterId','episodeId','patientId','appointmentId','sourceKind','status','revision'],true),
 ('encounter.status.changed','encounter','encounter.lifecycle.manage',
  ARRAY['encounterId','patientId','fromStatus','toStatus','revision'],
  ARRAY['encounterId','patientId','fromStatus','toStatus','revision'],true),
 ('encounter.participant.added','encounter_participant','encounter.participant.manage',
  ARRAY['participantId','encounterId','participantType','roleKey','revision'],
  ARRAY['participantId','encounterId','participantType','roleKey','practitionerId','revision'],true),
 ('encounter.participant.removed','encounter_participant','encounter.participant.manage',
  ARRAY['participantId','encounterId','roleKey','revision'],
  ARRAY['participantId','encounterId','roleKey','revision'],true),
 ('encounter.concern.recorded','presenting_concern','encounter.concern.write',
  ARRAY['concernId','encounterId','redFlag','contentDigest'],
  ARRAY['concernId','encounterId','redFlag','severityKey','contentDigest'],false),
 ('encounter.problem.recorded','clinical_problem','encounter.problem.write',
  ARRAY['problemId','encounterId','clinicalStatus','contentDigest'],
  ARRAY['problemId','encounterId','clinicalStatus','verificationStatus','contentDigest'],true),
 ('encounter.diagnosis.recorded','diagnosis','encounter.problem.write',
  ARRAY['diagnosisId','encounterId','certaintyKey','contentDigest'],
  ARRAY['diagnosisId','encounterId','certaintyKey','diagnosisType','contentDigest'],true),
 ('encounter.order.created','clinical_order','encounter.order.manage',
  ARRAY['orderId','encounterId','priorityKey','status','contentDigest'],
  ARRAY['orderId','encounterId','priorityKey','status','contentDigest'],true),
 ('encounter.task.created','clinical_task','encounter.task.manage',
  ARRAY['taskId','encounterId','priorityKey','status','contentDigest'],
  ARRAY['taskId','encounterId','priorityKey','status','contentDigest'],true),
 ('encounter.task.progressed','clinical_task','encounter.task.manage',
  ARRAY['taskId','encounterId','fromStatus','toStatus','revision'],
  ARRAY['taskId','encounterId','fromStatus','toStatus','revision'],true),
 ('encounter.red_flag.acknowledged','red_flag_escalation','encounter.red_flag.acknowledge',
  ARRAY['escalationId','encounterId','fromStatus','toStatus','revision'],
  ARRAY['escalationId','encounterId','fromStatus','toStatus','revision'],true),
 ('encounter.red_flag.resolved','red_flag_escalation','encounter.red_flag.acknowledge',
  ARRAY['escalationId','encounterId','fromStatus','toStatus','revision'],
  ARRAY['escalationId','encounterId','fromStatus','toStatus','revision'],true),
 ('encounter.note.versioned','encounter_note','encounter.note.write',
  ARRAY['noteId','encounterId','versionId','versionNumber','contentDigest','revision'],
  ARRAY['noteId','encounterId','versionId','versionNumber','contentDigest','lateEntry','revision'],false),
 ('encounter.note.signed','encounter_note','encounter.note.sign',
  ARRAY['noteId','encounterId','versionId','signatureId','contentDigest','revision'],
  ARRAY['noteId','encounterId','versionId','signatureId','signerPractitionerId','contentDigest','revision'],true),
 ('encounter.note.amended','encounter_note','encounter.amend',
  ARRAY['noteId','encounterId','versionId','amendmentId','amendmentDigest','revision'],
  ARRAY['noteId','encounterId','versionId','amendmentId','authorPractitionerId','amendmentDigest','revision'],true);

INSERT INTO audit_event_definitions
    (event_name,schema_version,display_name,description,subject_type,reason_required,
     required_payload_keys,allowed_payload_keys,payload_schema,status,registry_version)
SELECT event_name,1,replace(initcap(replace(event_name,'.',' ')),'_',' '),
       'CareOS Module 5 governed encounter evidence.',subject_type,reason_required,
       required_keys,allowed_keys,'{"type":"object","additionalProperties":false}'::jsonb,
       'active','m5-standing-direction-v1'
FROM m5_audit_seed;

INSERT INTO authorization_operation_events
    (operation_key,event_kind,event_name,schema_version,status,registry_version)
SELECT operation_key,'audit',event_name,1,'active','m5-standing-direction-v1'
FROM m5_audit_seed;

CREATE TEMP TABLE m5_outbox_seed (
    event_name varchar(180) PRIMARY KEY,
    aggregate_type varchar(120) NOT NULL,
    operation_key varchar(160) NOT NULL,
    required_keys text[] NOT NULL,
    allowed_keys text[] NOT NULL
) ON COMMIT DROP;

INSERT INTO m5_outbox_seed VALUES
 ('m5.encounter.opened.v1','encounter','encounter.open',
  ARRAY['encounterId','episodeId','patientId','sourceKind'],
  ARRAY['encounterId','episodeId','patientId','appointmentId','sourceKind']),
 ('m5.encounter.status-changed.v1','encounter','encounter.lifecycle.manage',
  ARRAY['encounterId','patientId','fromStatus','toStatus'],
  ARRAY['encounterId','patientId','fromStatus','toStatus']),
 ('m5.red-flag.raised.v1','red_flag_escalation','encounter.concern.write',
  ARRAY['escalationId','encounterId','patientId','severityKey','taskId'],
  ARRAY['escalationId','encounterId','patientId','severityKey','taskId']),
 ('m5.red-flag.changed.v1','red_flag_escalation','encounter.red_flag.acknowledge',
  ARRAY['escalationId','encounterId','patientId','status'],
  ARRAY['escalationId','encounterId','patientId','status']),
 ('m5.order.created.v1','clinical_order','encounter.order.manage',
  ARRAY['orderId','encounterId','patientId','priorityKey'],
  ARRAY['orderId','encounterId','patientId','priorityKey']),
 ('m5.note.signed.v1','encounter_note','encounter.note.sign',
  ARRAY['noteId','encounterId','versionId','signatureId','contentDigest'],
  ARRAY['noteId','encounterId','versionId','signatureId','contentDigest']),
 ('m5.note.amended.v1','encounter_note','encounter.amend',
  ARRAY['noteId','encounterId','versionId','amendmentId','amendmentDigest'],
  ARRAY['noteId','encounterId','versionId','amendmentId','amendmentDigest']);

INSERT INTO outbox_event_definitions
    (event_name,schema_version,description,aggregate_type,required_payload_keys,
     allowed_payload_keys,payload_schema,status,registry_version)
SELECT event_name,1,'CareOS Module 5 transactional encounter event.',aggregate_type,
       required_keys,allowed_keys,'{"type":"object","additionalProperties":false}'::jsonb,
       'active','m5-standing-direction-v1'
FROM m5_outbox_seed;

INSERT INTO authorization_operation_events
    (operation_key,event_kind,event_name,schema_version,status,registry_version)
SELECT operation_key,'outbox',event_name,1,'active','m5-standing-direction-v1'
FROM m5_outbox_seed;

CREATE FUNCTION careos_m5_actor_is_practitioner(
    requested_organization uuid, requested_actor uuid, requested_practitioner uuid,
    requested_at timestamptz)
RETURNS boolean LANGUAGE sql STABLE AS $$
    SELECT EXISTS (
        SELECT 1
        FROM organization_memberships membership
        JOIN access_assignment_scopes access_scope
          ON access_scope.organization_id=membership.organization_id
         AND access_scope.access_assignment_id=membership.id
         AND access_scope.status='active'
         AND access_scope.effective_from<=requested_at
         AND (access_scope.effective_to IS NULL OR access_scope.effective_to>requested_at)
        JOIN workforce_members member
          ON member.organization_id=access_scope.organization_id
         AND member.id=access_scope.workforce_member_id
         AND member.lifecycle_state='active'
        JOIN practitioner_profiles practitioner
          ON practitioner.organization_id=member.organization_id
         AND practitioner.workforce_member_id=member.id
         AND practitioner.lifecycle_state='active'
         AND practitioner.effective_from<=requested_at
         AND (practitioner.effective_to IS NULL OR practitioner.effective_to>requested_at)
        WHERE membership.organization_id=requested_organization
          AND membership.user_id=requested_actor
          AND membership.status='active'
          AND membership.effective_from<=requested_at
          AND (membership.effective_to IS NULL OR membership.effective_to>requested_at)
          AND practitioner.id=requested_practitioner
    )
$$;

CREATE FUNCTION careos_m5_practitioner_eligible(
    requested_organization uuid, requested_practitioner uuid,
    requested_service uuid, requested_facility uuid, requested_location uuid,
    requested_at timestamptz)
RETURNS boolean LANGUAGE sql STABLE AS $$
    SELECT EXISTS (
        SELECT 1
        FROM practitioner_profiles practitioner
        JOIN workforce_members member
          ON member.organization_id=practitioner.organization_id
         AND member.id=practitioner.workforce_member_id
         AND member.lifecycle_state='active'
        JOIN practitioner_service_assignments assignment
          ON assignment.organization_id=practitioner.organization_id
         AND assignment.practitioner_profile_id=practitioner.id
         AND assignment.service_id=requested_service
         AND assignment.facility_id=requested_facility
         AND (assignment.location_id IS NULL OR assignment.location_id=requested_location)
         AND assignment.lifecycle_state='active'
         AND assignment.effective_from<=requested_at
         AND (assignment.effective_to IS NULL OR assignment.effective_to>requested_at)
        JOIN practitioner_eligibility_evidence evidence
          ON evidence.organization_id=assignment.organization_id
         AND evidence.id=assignment.eligibility_evidence_id
         AND evidence.result_digest=assignment.eligibility_digest
         AND evidence.practitioner_profile_id=practitioner.id
         AND evidence.service_id=requested_service
         AND evidence.facility_id=requested_facility
         AND (evidence.location_id IS NULL OR evidence.location_id=requested_location)
         AND evidence.outcome='eligible' AND evidence.status='eligible'
         AND evidence.evaluated_from<=requested_at
         AND (evidence.evaluated_to IS NULL OR evidence.evaluated_to>requested_at)
         AND evidence.expires_at>requested_at
        WHERE practitioner.organization_id=requested_organization
          AND practitioner.id=requested_practitioner
          AND practitioner.lifecycle_state='active'
          AND practitioner.effective_from<=requested_at
          AND (practitioner.effective_to IS NULL OR practitioner.effective_to>requested_at)
    )
$$;

CREATE FUNCTION careos_guard_m5_episode()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE op text:=nullif(current_setting('app.current_operation_key',true),'');
BEGIN
    IF current_user<>'${applicationRole}' THEN RETURN NEW; END IF;
    IF TG_OP='INSERT' THEN
        IF op<>'encounter.open' OR NEW.status<>'active'
           OR NOT careos_m5_practitioner_eligible(
                NEW.organization_id,NEW.managing_practitioner_id,
                NEW.service_id,NEW.facility_id,NEW.location_id,NEW.started_at) THEN
            RAISE EXCEPTION 'invalid Module 5 episode opening' USING ERRCODE='23514';
        END IF;
    ELSIF op<>'encounter.lifecycle.manage'
       OR NOT ((OLD.status='active' AND NEW.status IN ('on_hold','closed','entered_in_error'))
            OR (OLD.status='on_hold' AND NEW.status IN ('active','closed','entered_in_error'))) THEN
        RAISE EXCEPTION 'invalid Module 5 episode transition' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER episodes_of_care_lifecycle_guard
    BEFORE INSERT OR UPDATE ON episodes_of_care
    FOR EACH ROW EXECUTE FUNCTION careos_guard_m5_episode();

CREATE FUNCTION careos_guard_m5_encounter()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE op text:=nullif(current_setting('app.current_operation_key',true),'');
BEGIN
    IF current_user<>'${applicationRole}' THEN RETURN NEW; END IF;
    IF TG_OP='INSERT' THEN
        IF op<>'encounter.open' OR NEW.status<>'planned'
           OR NEW.arrived_at IS NOT NULL OR NEW.in_progress_at IS NOT NULL
           OR NEW.on_hold_at IS NOT NULL OR NEW.completed_at IS NOT NULL
           OR NEW.cancelled_at IS NOT NULL OR NEW.entered_in_error_at IS NOT NULL
           OR NOT EXISTS (SELECT 1 FROM patient_profiles patient
                WHERE patient.organization_id=NEW.organization_id AND patient.id=NEW.patient_id
                  AND patient.lifecycle_state='active' AND patient.merged_into_patient_id IS NULL)
           OR NOT EXISTS (SELECT 1 FROM episodes_of_care episode
                WHERE episode.organization_id=NEW.organization_id AND episode.id=NEW.episode_of_care_id
                  AND episode.patient_id=NEW.patient_id AND episode.service_id=NEW.service_id
                  AND episode.facility_id=NEW.facility_id AND episode.location_id=NEW.location_id
                  AND episode.status='active')
           OR NOT careos_m5_practitioner_eligible(
                NEW.organization_id,NEW.responsible_practitioner_id,
                NEW.service_id,NEW.facility_id,NEW.location_id,NEW.planned_start_at) THEN
            RAISE EXCEPTION 'invalid Module 5 encounter opening' USING ERRCODE='23514';
        END IF;
        IF NEW.source_appointment_id IS NOT NULL AND NOT EXISTS (
            SELECT 1 FROM appointments appointment
            JOIN appointment_assignments assignment
              ON assignment.organization_id=appointment.organization_id
             AND assignment.appointment_id=appointment.id AND assignment.status='active'
            WHERE appointment.organization_id=NEW.organization_id
              AND appointment.id=NEW.source_appointment_id
              AND appointment.status='confirmed'
              AND appointment.patient_id=NEW.patient_id
              AND appointment.service_id=NEW.service_id
              AND appointment.facility_id=NEW.facility_id
              AND appointment.location_id=NEW.location_id
              AND assignment.practitioner_profile_id=NEW.responsible_practitioner_id) THEN
            RAISE EXCEPTION 'appointment provenance does not match the encounter' USING ERRCODE='23514';
        END IF;
        RETURN NEW;
    END IF;
    IF op<>'encounter.lifecycle.manage'
       OR ROW(NEW.episode_of_care_id,NEW.source_appointment_id,NEW.patient_id,NEW.service_id,
              NEW.facility_id,NEW.location_id,NEW.responsible_practitioner_id,NEW.source_kind,
              NEW.encounter_type_key,NEW.planned_start_at)
          IS DISTINCT FROM
          ROW(OLD.episode_of_care_id,OLD.source_appointment_id,OLD.patient_id,OLD.service_id,
              OLD.facility_id,OLD.location_id,OLD.responsible_practitioner_id,OLD.source_kind,
              OLD.encounter_type_key,OLD.planned_start_at)
       OR NOT (
            (OLD.status='planned' AND NEW.status IN ('arrived','cancelled','entered_in_error')) OR
            (OLD.status='arrived' AND NEW.status IN ('in_progress','cancelled','entered_in_error')) OR
            (OLD.status='in_progress' AND NEW.status IN ('on_hold','completed','cancelled','entered_in_error')) OR
            (OLD.status='on_hold' AND NEW.status IN ('in_progress','cancelled','entered_in_error'))
       ) THEN
        RAISE EXCEPTION 'invalid Module 5 encounter transition' USING ERRCODE='23514';
    END IF;
    IF NEW.status='completed' AND (
        NOT EXISTS (SELECT 1 FROM encounter_signatures signature
             WHERE signature.organization_id=NEW.organization_id
               AND signature.encounter_id=NEW.id AND signature.status='signed')
        OR EXISTS (SELECT 1 FROM red_flag_escalations escalation
             WHERE escalation.organization_id=NEW.organization_id
               AND escalation.encounter_id=NEW.id AND escalation.status<>'resolved')
    ) THEN
        RAISE EXCEPTION 'encounter cannot complete without signed content and resolved red flags' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER encounters_lifecycle_guard
    BEFORE INSERT OR UPDATE ON encounters
    FOR EACH ROW EXECUTE FUNCTION careos_guard_m5_encounter();

CREATE FUNCTION careos_guard_m5_participant()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE op text:=nullif(current_setting('app.current_operation_key',true),'');
BEGIN
    IF current_user<>'${applicationRole}' THEN RETURN NEW; END IF;
    IF TG_OP='INSERT' THEN
        IF op NOT IN ('encounter.open','encounter.participant.manage') OR NEW.status<>'active'
           OR NOT EXISTS (SELECT 1 FROM encounters encounter
                WHERE encounter.organization_id=NEW.organization_id AND encounter.id=NEW.encounter_id
                  AND encounter.status NOT IN ('completed','cancelled','entered_in_error')) THEN
            RAISE EXCEPTION 'invalid Module 5 participant addition' USING ERRCODE='23514';
        END IF;
        IF NEW.participant_type='practitioner' AND (
            NEW.practitioner_profile_id IS NULL OR NEW.assignment_id IS NULL
            OR NEW.eligibility_evidence_id IS NULL
            OR NOT EXISTS (SELECT 1 FROM practitioner_service_assignments assignment
                JOIN practitioner_eligibility_evidence evidence
                  ON evidence.organization_id=assignment.organization_id
                 AND evidence.id=NEW.eligibility_evidence_id
                 AND evidence.result_digest=NEW.eligibility_digest
                JOIN encounters encounter
                  ON encounter.organization_id=NEW.organization_id AND encounter.id=NEW.encounter_id
                WHERE assignment.organization_id=NEW.organization_id AND assignment.id=NEW.assignment_id
                  AND assignment.practitioner_profile_id=NEW.practitioner_profile_id
                  AND assignment.service_id=encounter.service_id
                  AND assignment.facility_id=encounter.facility_id
                  AND (assignment.location_id IS NULL OR assignment.location_id=encounter.location_id)
                  AND evidence.outcome='eligible' AND evidence.expires_at>NEW.added_at)
        ) THEN
            RAISE EXCEPTION 'practitioner participant snapshot lacks eligibility evidence' USING ERRCODE='23514';
        END IF;
    ELSIF op<>'encounter.participant.manage' OR OLD.status<>'active' OR NEW.status<>'removed'
       OR OLD.participant_type='patient' OR OLD.role_key='responsible_clinician'
       OR ROW(NEW.encounter_id,NEW.participant_type,NEW.patient_id,NEW.workforce_member_id,
              NEW.practitioner_profile_id,NEW.role_key,NEW.display_name_snapshot,NEW.role_snapshot,
              NEW.assignment_id,NEW.eligibility_evidence_id,NEW.eligibility_digest,NEW.registration_snapshot,NEW.added_at)
          IS DISTINCT FROM
          ROW(OLD.encounter_id,OLD.participant_type,OLD.patient_id,OLD.workforce_member_id,
              OLD.practitioner_profile_id,OLD.role_key,OLD.display_name_snapshot,OLD.role_snapshot,
              OLD.assignment_id,OLD.eligibility_evidence_id,OLD.eligibility_digest,OLD.registration_snapshot,OLD.added_at) THEN
        RAISE EXCEPTION 'invalid Module 5 participant removal' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER encounter_participants_lifecycle_guard
    BEFORE INSERT OR UPDATE ON encounter_participants
    FOR EACH ROW EXECUTE FUNCTION careos_guard_m5_participant();

CREATE FUNCTION careos_guard_m5_clinical_insert()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    actor uuid:=nullif(current_setting('app.current_actor_id',true),'')::uuid;
    author_id uuid;
    target_encounter uuid;
BEGIN
    IF current_user<>'${applicationRole}' THEN RETURN NEW; END IF;
    author_id:=CASE TG_TABLE_NAME
        WHEN 'presenting_concerns' THEN NEW.author_practitioner_id
        WHEN 'clinical_problems' THEN NEW.author_practitioner_id
        WHEN 'diagnoses' THEN NEW.author_practitioner_id
        WHEN 'note_versions' THEN NEW.author_practitioner_id
        WHEN 'orders' THEN NEW.requester_practitioner_id
        ELSE NULL END;
    target_encounter:=CASE TG_TABLE_NAME
        WHEN 'note_versions' THEN (SELECT note.encounter_id FROM encounter_notes note
                                   WHERE note.organization_id=NEW.organization_id
                                     AND note.id=NEW.encounter_note_id)
        ELSE NEW.encounter_id END;
    IF TG_OP<>'INSERT' OR author_id IS NULL OR target_encounter IS NULL
       OR NOT careos_m5_actor_is_practitioner(NEW.organization_id,actor,author_id,clock_timestamp())
       OR NOT EXISTS (SELECT 1 FROM encounters encounter
            WHERE encounter.organization_id=NEW.organization_id AND encounter.id=target_encounter
              AND encounter.status IN ('in_progress','on_hold')) THEN
        RAISE EXCEPTION 'invalid Module 5 clinical authorship context' USING ERRCODE='42501';
    END IF;
    RETURN NEW;
END $$;

DO $$
DECLARE table_name text;
BEGIN
    FOREACH table_name IN ARRAY ARRAY[
        'presenting_concerns','clinical_problems','diagnoses','note_versions','orders'
    ] LOOP
        EXECUTE format(
            'CREATE TRIGGER %I BEFORE INSERT ON %I FOR EACH ROW EXECUTE FUNCTION careos_guard_m5_clinical_insert()',
            table_name||'_authorship_guard',table_name);
    END LOOP;
END $$;

CREATE FUNCTION careos_guard_m5_note_version()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE expected_version integer; expected_prior uuid;
BEGIN
    IF current_user<>'${applicationRole}' THEN RETURN NEW; END IF;
    SELECT note.current_version_number+1,note.current_version_id
      INTO expected_version,expected_prior
      FROM encounter_notes note
     WHERE note.organization_id=NEW.organization_id AND note.id=NEW.encounter_note_id
       AND note.status='draft' FOR UPDATE;
    IF expected_version IS NULL OR NEW.version_number<>expected_version
       OR NEW.prior_version_id IS DISTINCT FROM expected_prior
       OR NEW.content_digest<>encode(digest(NEW.content_text,'sha256'),'hex')
       OR NEW.status<>'recorded' THEN
        RAISE EXCEPTION 'invalid Module 5 note version' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER note_versions_content_guard
    BEFORE INSERT ON note_versions
    FOR EACH ROW EXECUTE FUNCTION careos_guard_m5_note_version();

CREATE FUNCTION careos_guard_m5_note()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE op text:=nullif(current_setting('app.current_operation_key',true),'');
BEGIN
    IF current_user<>'${applicationRole}' THEN RETURN NEW; END IF;
    IF TG_OP='INSERT' THEN
        IF op<>'encounter.note.write' OR NEW.status<>'draft'
           OR NEW.current_version_id IS NOT NULL OR NEW.current_version_number<>0 THEN
            RAISE EXCEPTION 'invalid Module 5 note creation' USING ERRCODE='23514';
        END IF;
    ELSIF op='encounter.note.write' THEN
        IF OLD.status<>'draft' OR NEW.status<>'draft'
           OR NEW.current_version_number<>OLD.current_version_number+1
           OR NEW.current_version_id IS NULL OR NEW.current_version_id IS NOT DISTINCT FROM OLD.current_version_id
           OR NOT EXISTS (SELECT 1 FROM note_versions version
                WHERE version.organization_id=NEW.organization_id
                  AND version.id=NEW.current_version_id
                  AND version.encounter_note_id=NEW.id
                  AND version.version_number=NEW.current_version_number) THEN
            RAISE EXCEPTION 'invalid Module 5 note version pointer' USING ERRCODE='23514';
        END IF;
    ELSIF op='encounter.note.sign' THEN
        IF OLD.status<>'draft' OR NEW.status<>'signed'
           OR NEW.current_version_id IS DISTINCT FROM OLD.current_version_id
           OR NEW.current_version_number<>OLD.current_version_number THEN
            RAISE EXCEPTION 'invalid Module 5 note signing transition' USING ERRCODE='23514';
        END IF;
    ELSIF op='encounter.amend' THEN
        IF OLD.status NOT IN ('signed','amended') OR NEW.status<>'amended'
           OR NEW.current_version_id IS DISTINCT FROM OLD.current_version_id
           OR NEW.current_version_number<>OLD.current_version_number THEN
            RAISE EXCEPTION 'invalid Module 5 amendment transition' USING ERRCODE='23514';
        END IF;
    ELSE
        RAISE EXCEPTION 'operation cannot change Module 5 note state' USING ERRCODE='42501';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER encounter_notes_lifecycle_guard
    BEFORE INSERT OR UPDATE ON encounter_notes
    FOR EACH ROW EXECUTE FUNCTION careos_guard_m5_note();

CREATE FUNCTION careos_guard_m5_signature()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    actor uuid:=nullif(current_setting('app.current_actor_id',true),'')::uuid;
    encounter_record encounters%ROWTYPE;
BEGIN
    IF current_user<>'${applicationRole}' THEN RETURN NEW; END IF;
    SELECT encounter.* INTO encounter_record FROM encounters encounter
     WHERE encounter.organization_id=NEW.organization_id AND encounter.id=NEW.encounter_id;
    IF nullif(current_setting('app.current_operation_key',true),'')<>'encounter.note.sign'
       OR encounter_record.id IS NULL OR encounter_record.status NOT IN ('in_progress','on_hold')
       OR NOT careos_m5_actor_is_practitioner(NEW.organization_id,actor,NEW.signer_practitioner_id,NEW.signed_at)
       OR NOT careos_m5_practitioner_eligible(
            NEW.organization_id,NEW.signer_practitioner_id,encounter_record.service_id,
            encounter_record.facility_id,encounter_record.location_id,NEW.signed_at)
       OR NOT EXISTS (SELECT 1 FROM encounter_notes note
            JOIN note_versions version ON version.organization_id=note.organization_id
             AND version.id=note.current_version_id
            JOIN encounter_participants participant ON participant.organization_id=note.organization_id
             AND participant.id=NEW.signer_participant_id
             AND participant.encounter_id=note.encounter_id
             AND participant.practitioner_profile_id=NEW.signer_practitioner_id
             AND participant.status='active'
            JOIN practitioner_eligibility_evidence evidence
              ON evidence.organization_id=participant.organization_id
             AND evidence.id=NEW.eligibility_evidence_id
             AND evidence.result_digest=NEW.eligibility_digest
             AND evidence.outcome='eligible' AND evidence.expires_at>NEW.signed_at
            WHERE note.organization_id=NEW.organization_id AND note.id=NEW.encounter_note_id
              AND note.encounter_id=NEW.encounter_id AND note.status='draft'
              AND version.id=NEW.note_version_id
              AND version.content_digest=NEW.signed_content_digest) THEN
        RAISE EXCEPTION 'invalid Module 5 clinical signature' USING ERRCODE='42501';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER encounter_signatures_eligibility_guard
    BEFORE INSERT ON encounter_signatures
    FOR EACH ROW EXECUTE FUNCTION careos_guard_m5_signature();

CREATE FUNCTION careos_guard_m5_amendment()
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
       OR NOT careos_m5_actor_is_practitioner(NEW.organization_id,actor,NEW.author_practitioner_id,NEW.amended_at)
       OR NOT careos_m5_practitioner_eligible(
            NEW.organization_id,NEW.author_practitioner_id,encounter_record.service_id,
            encounter_record.facility_id,encounter_record.location_id,NEW.amended_at)
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
CREATE TRIGGER amendments_signature_guard
    BEFORE INSERT ON amendments
    FOR EACH ROW EXECUTE FUNCTION careos_guard_m5_amendment();

CREATE FUNCTION careos_guard_m5_red_flag_escalation()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE op text:=nullif(current_setting('app.current_operation_key',true),'');
BEGIN
    IF current_user<>'${applicationRole}' THEN RETURN NEW; END IF;
    IF TG_OP='INSERT' THEN
        IF op<>'encounter.concern.write' OR NEW.status<>'raised'
           OR NEW.acknowledged_at IS NOT NULL OR NEW.resolved_at IS NOT NULL
           OR NOT EXISTS (SELECT 1 FROM presenting_concerns concern
                JOIN clinical_tasks task ON task.organization_id=concern.organization_id
                 AND task.id=NEW.clinical_task_id
                WHERE concern.organization_id=NEW.organization_id
                  AND concern.id=NEW.presenting_concern_id
                  AND concern.encounter_id=NEW.encounter_id
                  AND concern.red_flag
                  AND task.source_concern_id=concern.id
                  AND task.requires_acknowledgement
                  AND task.priority_key='critical') THEN
            RAISE EXCEPTION 'invalid Module 5 red-flag escalation' USING ERRCODE='23514';
        END IF;
    ELSIF op<>'encounter.red_flag.acknowledge'
       OR NOT ((OLD.status='raised' AND NEW.status='acknowledged'
                AND NEW.acknowledged_at IS NOT NULL AND NEW.resolved_at IS NULL)
            OR (OLD.status='acknowledged' AND NEW.status='resolved'
                AND NEW.resolved_at IS NOT NULL))
       OR ROW(NEW.encounter_id,NEW.patient_id,NEW.presenting_concern_id,NEW.clinical_task_id,
              NEW.severity_key,NEW.policy_version,NEW.raised_at,NEW.raised_by_practitioner_id)
          IS DISTINCT FROM
          ROW(OLD.encounter_id,OLD.patient_id,OLD.presenting_concern_id,OLD.clinical_task_id,
              OLD.severity_key,OLD.policy_version,OLD.raised_at,OLD.raised_by_practitioner_id) THEN
        RAISE EXCEPTION 'invalid Module 5 red-flag acknowledgement' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER red_flag_escalations_lifecycle_guard
    BEFORE INSERT OR UPDATE ON red_flag_escalations
    FOR EACH ROW EXECUTE FUNCTION careos_guard_m5_red_flag_escalation();

CREATE FUNCTION careos_require_m5_red_flag_escalation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.red_flag AND NOT EXISTS (
        SELECT 1 FROM red_flag_escalations escalation
        WHERE escalation.organization_id=NEW.organization_id
          AND escalation.presenting_concern_id=NEW.id) THEN
        RAISE EXCEPTION 'red flag requires an attributed escalation and critical task' USING ERRCODE='23514';
    END IF;
    RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER presenting_concerns_red_flag_guard
    AFTER INSERT ON presenting_concerns
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION careos_require_m5_red_flag_escalation();

CREATE FUNCTION careos_guard_m5_task()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE op text:=nullif(current_setting('app.current_operation_key',true),'');
BEGIN
    IF current_user<>'${applicationRole}' THEN RETURN NEW; END IF;
    IF TG_OP='INSERT' THEN
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
CREATE TRIGGER clinical_tasks_lifecycle_guard
    BEFORE INSERT OR UPDATE ON clinical_tasks
    FOR EACH ROW EXECUTE FUNCTION careos_guard_m5_task();
