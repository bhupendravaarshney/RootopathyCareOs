CREATE TEMP TABLE m6_audit_seed (
    event_name varchar(180) PRIMARY KEY,
    subject_type varchar(120) NOT NULL,
    operation_key varchar(160) NOT NULL,
    required_keys text[] NOT NULL,
    allowed_keys text[] NOT NULL,
    reason_required boolean NOT NULL
) ON COMMIT DROP;

INSERT INTO m6_audit_seed VALUES
 ('assessment.started','assessment_session','assessment.start',
  ARRAY['assessmentSessionId','encounterId','patientId','responsiblePractitionerId','status','revision'],
  ARRAY['assessmentSessionId','encounterId','patientId','responsiblePractitionerId','status','sourcePackageStatus','revision'],true),
 ('assessment.response.versioned','assessment_response','assessment.response.write',
  ARRAY['assessmentSessionId','sectionId','responseId','versionId','versionNumber','contentDigest','revision'],
  ARRAY['assessmentSessionId','sectionId','responseId','versionId','versionNumber','contentDigest','sourceKey','methodKey','interpretationStatus','revision'],false),
 ('assessment.measurement.recorded','measurement','assessment.measurement.write',
  ARRAY['assessmentSessionId','sectionId','measurementId','measurementKey','revision'],
  ARRAY['assessmentSessionId','sectionId','measurementId','measurementKey','baseline','sourceKey','methodKey','unitScale','revision'],false),
 ('assessment.red_flag.raised','red_flag','assessment.red_flag.write',
  ARRAY['assessmentSessionId','sectionId','redFlagId','severityKey','status','summaryDigest','revision'],
  ARRAY['assessmentSessionId','sectionId','redFlagId','severityKey','status','summaryDigest','ownerPractitionerId','revision'],true),
 ('assessment.red_flag.acknowledged','red_flag','assessment.red_flag.write',
  ARRAY['assessmentSessionId','redFlagId','fromStatus','toStatus','revision'],
  ARRAY['assessmentSessionId','redFlagId','fromStatus','toStatus','revision'],true),
 ('assessment.red_flag.resolved','red_flag','assessment.red_flag.write',
  ARRAY['assessmentSessionId','redFlagId','fromStatus','toStatus','revision'],
  ARRAY['assessmentSessionId','redFlagId','fromStatus','toStatus','revision'],true),
 ('assessment.review.submitted','assessment_review','assessment.review',
  ARRAY['assessmentSessionId','reviewId','assessmentRevision','completedSectionCount','sourceReviewed','uncertaintyReviewed'],
  ARRAY['assessmentSessionId','reviewId','assessmentRevision','completedSectionCount','totalSectionCount','completenessConfirmed','sourceReviewed','uncertaintyReviewed','reviewDigest'],true),
 ('assessment.signed','assessment_signature','assessment.sign',
  ARRAY['assessmentSessionId','reviewId','signatureId','assessmentRevision','signatureDigest','revision'],
  ARRAY['assessmentSessionId','reviewId','signatureId','assessmentRevision','signerPractitionerId','signatureDigest','revision'],true),
 ('assessment.amended','assessment_amendment','assessment.amend',
  ARRAY['assessmentSessionId','signatureId','amendmentId','assessmentRevision','amendmentDigest','revision'],
  ARRAY['assessmentSessionId','signatureId','amendmentId','assessmentRevision','authorPractitionerId','amendmentDigest','revision'],true),
 ('assessment.status.changed','assessment_session','assessment.lifecycle.manage',
  ARRAY['assessmentSessionId','fromStatus','toStatus','revision'],
  ARRAY['assessmentSessionId','encounterId','patientId','fromStatus','toStatus','revision'],true);

INSERT INTO audit_event_definitions
    (event_name,schema_version,display_name,description,subject_type,reason_required,
     required_payload_keys,allowed_payload_keys,payload_schema,status,registry_version)
SELECT event_name,1,replace(initcap(replace(event_name,'.',' ')),'_',' '),
       'CareOS Module 6 governed assessment evidence.',subject_type,reason_required,
       required_keys,allowed_keys,'{"type":"object","additionalProperties":false}'::jsonb,
       'active','m6-standing-direction-v1'
FROM m6_audit_seed;

INSERT INTO authorization_operation_events
    (operation_key,event_kind,event_name,schema_version,status,registry_version)
SELECT operation_key,'audit',event_name,1,'active','m6-standing-direction-v1'
FROM m6_audit_seed;

CREATE TEMP TABLE m6_outbox_seed (
    event_name varchar(180) PRIMARY KEY,
    aggregate_type varchar(120) NOT NULL,
    operation_key varchar(160) NOT NULL,
    required_keys text[] NOT NULL,
    allowed_keys text[] NOT NULL
) ON COMMIT DROP;

INSERT INTO m6_outbox_seed VALUES
 ('m6.assessment.started.v1','assessment_session','assessment.start',
  ARRAY['assessmentSessionId','encounterId','patientId','responsiblePractitionerId'],
  ARRAY['assessmentSessionId','encounterId','patientId','responsiblePractitionerId']),
 ('m6.assessment.red-flag-raised.v1','red_flag','assessment.red_flag.write',
  ARRAY['assessmentSessionId','redFlagId','severityKey','status'],
  ARRAY['assessmentSessionId','redFlagId','severityKey','status','ownerPractitionerId']),
 ('m6.assessment.red-flag-changed.v1','red_flag','assessment.red_flag.write',
  ARRAY['assessmentSessionId','redFlagId','status'],
  ARRAY['assessmentSessionId','redFlagId','status']),
 ('m6.assessment.signed.v1','assessment_session','assessment.sign',
  ARRAY['assessmentSessionId','reviewId','signatureId','assessmentRevision','signatureDigest'],
  ARRAY['assessmentSessionId','reviewId','signatureId','assessmentRevision','signatureDigest']),
 ('m6.assessment.amended.v1','assessment_session','assessment.amend',
  ARRAY['assessmentSessionId','signatureId','amendmentId','assessmentRevision','amendmentDigest'],
  ARRAY['assessmentSessionId','signatureId','amendmentId','assessmentRevision','amendmentDigest']),
 ('m6.assessment.status-changed.v1','assessment_session','assessment.lifecycle.manage',
  ARRAY['assessmentSessionId','fromStatus','toStatus'],
  ARRAY['assessmentSessionId','encounterId','patientId','fromStatus','toStatus']);

INSERT INTO outbox_event_definitions
    (event_name,schema_version,description,aggregate_type,required_payload_keys,
     allowed_payload_keys,payload_schema,status,registry_version)
SELECT event_name,1,'CareOS Module 6 transactional assessment event.',aggregate_type,
       required_keys,allowed_keys,'{"type":"object","additionalProperties":false}'::jsonb,
       'active','m6-standing-direction-v1'
FROM m6_outbox_seed;

INSERT INTO authorization_operation_events
    (operation_key,event_kind,event_name,schema_version,status,registry_version)
SELECT operation_key,'outbox',event_name,1,'active','m6-standing-direction-v1'
FROM m6_outbox_seed;

CREATE FUNCTION careos_m6_practitioner_can_write(
    requested_organization uuid, requested_session uuid,
    requested_actor uuid, requested_practitioner uuid, requested_at timestamptz)
RETURNS boolean LANGUAGE sql STABLE AS $$
    SELECT EXISTS (
        SELECT 1
        FROM assessment_sessions session
        JOIN encounters encounter
          ON encounter.organization_id=session.organization_id
         AND encounter.id=session.encounter_id
        JOIN encounter_participants participant
          ON participant.organization_id=session.organization_id
         AND participant.encounter_id=session.encounter_id
         AND participant.practitioner_profile_id=requested_practitioner
         AND participant.status='active'
        WHERE session.organization_id=requested_organization
          AND session.id=requested_session
          AND careos_m5_actor_is_practitioner(
                requested_organization,requested_actor,requested_practitioner,requested_at)
          AND careos_m5_practitioner_eligible(
                requested_organization,requested_practitioner,encounter.service_id,
                encounter.facility_id,encounter.location_id,requested_at)
    )
$$;

CREATE FUNCTION careos_guard_m6_session()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    op text:=nullif(current_setting('app.current_operation_key',true),'');
    actor uuid:=nullif(current_setting('app.current_actor_id',true),'')::uuid;
BEGIN
    IF current_user<>'${applicationRole}' THEN RETURN NEW; END IF;
    IF TG_OP='INSERT' THEN
        IF op<>'assessment.start' OR NEW.status<>'in_progress'
           OR NEW.source_package_status<>'unavailable'
           OR NEW.submitted_for_review_at IS NOT NULL OR NEW.signed_at IS NOT NULL
           OR NEW.completed_at IS NOT NULL OR NEW.cancelled_at IS NOT NULL
           OR NEW.entered_in_error_at IS NOT NULL
           OR NOT EXISTS (
                SELECT 1 FROM encounters encounter
                JOIN encounter_participants participant
                  ON participant.organization_id=encounter.organization_id
                 AND participant.encounter_id=encounter.id
                 AND participant.practitioner_profile_id=NEW.responsible_practitioner_id
                 AND participant.role_key='responsible_clinician'
                 AND participant.status='active'
                WHERE encounter.organization_id=NEW.organization_id
                  AND encounter.id=NEW.encounter_id
                  AND encounter.patient_id=NEW.patient_id
                  AND encounter.responsible_practitioner_id=NEW.responsible_practitioner_id
                  AND encounter.status IN ('in_progress','on_hold')
                  AND careos_m5_actor_is_practitioner(
                        NEW.organization_id,actor,NEW.responsible_practitioner_id,NEW.started_at)
                  AND careos_m5_practitioner_eligible(
                        NEW.organization_id,NEW.responsible_practitioner_id,
                        encounter.service_id,encounter.facility_id,encounter.location_id,NEW.started_at)
           ) THEN
            RAISE EXCEPTION 'invalid Module 6 assessment start context' USING ERRCODE='23514';
        END IF;
        RETURN NEW;
    END IF;

    IF ROW(NEW.encounter_id,NEW.patient_id,NEW.responsible_practitioner_id,
           NEW.source_package_status,NEW.started_at,NEW.policy_version)
       IS DISTINCT FROM
       ROW(OLD.encounter_id,OLD.patient_id,OLD.responsible_practitioner_id,
           OLD.source_package_status,OLD.started_at,OLD.policy_version) THEN
        RAISE EXCEPTION 'assessment identity and protected-source state are immutable' USING ERRCODE='23514';
    END IF;

    IF op=ANY(ARRAY['assessment.response.write','assessment.measurement.write','assessment.red_flag.write']) THEN
        IF OLD.status<>'in_progress' OR NEW.status<>OLD.status
           OR ROW(NEW.submitted_for_review_at,NEW.signed_at,NEW.completed_at,
                  NEW.cancelled_at,NEW.entered_in_error_at)
              IS DISTINCT FROM
              ROW(OLD.submitted_for_review_at,OLD.signed_at,OLD.completed_at,
                  OLD.cancelled_at,OLD.entered_in_error_at) THEN
            RAISE EXCEPTION 'assessment content is writable only while in progress' USING ERRCODE='23514';
        END IF;
    ELSIF op='assessment.review' THEN
        IF OLD.status<>'in_progress' OR NEW.status<>'in_review'
           OR NEW.submitted_for_review_at IS NULL
           OR NEW.signed_at IS NOT NULL OR NEW.completed_at IS NOT NULL
           OR NOT EXISTS (
                SELECT 1 FROM assessment_reviews review
                WHERE review.organization_id=NEW.organization_id
                  AND review.assessment_session_id=NEW.id
                  AND review.assessment_revision=NEW.lock_version)
           OR EXISTS (
                SELECT 1 FROM red_flags flag
                WHERE flag.organization_id=NEW.organization_id
                  AND flag.assessment_session_id=NEW.id AND flag.status<>'resolved') THEN
            RAISE EXCEPTION 'invalid assessment review transition or unresolved red flag' USING ERRCODE='23514';
        END IF;
    ELSIF op='assessment.sign' THEN
        IF OLD.status<>'in_review' OR NEW.status<>'signed'
           OR NEW.signed_at IS NULL OR NEW.submitted_for_review_at IS DISTINCT FROM OLD.submitted_for_review_at
           OR NOT EXISTS (
                SELECT 1 FROM assessment_signatures signature
                WHERE signature.organization_id=NEW.organization_id
                  AND signature.assessment_session_id=NEW.id
                  AND signature.assessment_revision=NEW.lock_version)
           OR EXISTS (
                SELECT 1 FROM red_flags flag
                WHERE flag.organization_id=NEW.organization_id
                  AND flag.assessment_session_id=NEW.id AND flag.status<>'resolved') THEN
            RAISE EXCEPTION 'invalid assessment signing transition or unresolved red flag' USING ERRCODE='23514';
        END IF;
    ELSIF op='assessment.amend' THEN
        IF OLD.status NOT IN ('signed','amended') OR NEW.status<>'amended'
           OR NEW.signed_at IS DISTINCT FROM OLD.signed_at
           OR NOT EXISTS (
                SELECT 1 FROM assessment_amendments amendment
                WHERE amendment.organization_id=NEW.organization_id
                  AND amendment.assessment_session_id=NEW.id
                  AND amendment.assessment_revision=NEW.lock_version) THEN
            RAISE EXCEPTION 'invalid assessment amendment transition' USING ERRCODE='23514';
        END IF;
    ELSIF op='assessment.lifecycle.manage' THEN
        IF NOT (
             (OLD.status='in_review' AND NEW.status='in_progress') OR
             (OLD.status IN ('signed','amended') AND NEW.status='completed') OR
             (OLD.status IN ('in_progress','in_review') AND NEW.status IN ('cancelled','entered_in_error'))
        ) THEN
            RAISE EXCEPTION 'invalid assessment lifecycle transition' USING ERRCODE='23514';
        END IF;
        IF NEW.status='in_progress' AND NEW.submitted_for_review_at IS NOT NULL THEN
            RAISE EXCEPTION 'return to draft must clear the review submission timestamp' USING ERRCODE='23514';
        END IF;
        IF NEW.status='completed' AND (
             NEW.completed_at IS NULL OR
             NOT EXISTS (SELECT 1 FROM assessment_signatures signature
                 WHERE signature.organization_id=NEW.organization_id
                   AND signature.assessment_session_id=NEW.id) OR
             EXISTS (SELECT 1 FROM red_flags flag
                 WHERE flag.organization_id=NEW.organization_id
                   AND flag.assessment_session_id=NEW.id AND flag.status<>'resolved')
        ) THEN
            RAISE EXCEPTION 'assessment cannot complete without a signature and resolved red flags' USING ERRCODE='23514';
        END IF;
    ELSE
        RAISE EXCEPTION 'unsupported assessment session mutation' USING ERRCODE='42501';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER assessment_sessions_lifecycle_guard
    BEFORE INSERT OR UPDATE ON assessment_sessions
    FOR EACH ROW EXECUTE FUNCTION careos_guard_m6_session();

CREATE FUNCTION careos_guard_m6_section()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE op text:=nullif(current_setting('app.current_operation_key',true),'');
BEGIN
    IF current_user<>'${applicationRole}' THEN RETURN NEW; END IF;
    IF TG_OP='INSERT' THEN
        IF op<>'assessment.start' OR NEW.status<>'not_started' OR NEW.response_count<>0
           OR NEW.source_package_verified
           OR NOT EXISTS (SELECT 1 FROM assessment_sessions session
                WHERE session.organization_id=NEW.organization_id
                  AND session.id=NEW.assessment_session_id AND session.status='in_progress') THEN
            RAISE EXCEPTION 'invalid Module 6 assessment section creation' USING ERRCODE='23514';
        END IF;
    ELSIF op<>'assessment.response.write'
       OR ROW(NEW.assessment_session_id,NEW.screen_id,NEW.sequence_number,NEW.title,NEW.source_package_verified)
          IS DISTINCT FROM
          ROW(OLD.assessment_session_id,OLD.screen_id,OLD.sequence_number,OLD.title,OLD.source_package_verified)
       OR NEW.status NOT IN ('in_progress','complete')
       OR NEW.response_count<>(SELECT count(*) FROM assessment_responses response
             WHERE response.organization_id=NEW.organization_id
               AND response.assessment_section_id=NEW.id AND response.status='draft')
       OR NOT EXISTS (SELECT 1 FROM assessment_sessions session
             WHERE session.organization_id=NEW.organization_id
               AND session.id=NEW.assessment_session_id AND session.status='in_progress') THEN
        RAISE EXCEPTION 'invalid Module 6 assessment section revision' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER assessment_sections_lifecycle_guard
    BEFORE INSERT OR UPDATE ON assessment_sections
    FOR EACH ROW EXECUTE FUNCTION careos_guard_m6_section();

CREATE FUNCTION careos_guard_m6_response()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF current_user<>'${applicationRole}' THEN RETURN NEW; END IF;
    IF TG_OP='INSERT' THEN
        IF NEW.current_version_id IS NOT NULL OR NEW.current_version_number<>0 OR NEW.status<>'draft'
           OR NOT EXISTS (SELECT 1 FROM assessment_sections section
                JOIN assessment_sessions session
                  ON session.organization_id=section.organization_id
                 AND session.id=section.assessment_session_id
                WHERE section.organization_id=NEW.organization_id
                  AND section.id=NEW.assessment_section_id
                  AND section.assessment_session_id=NEW.assessment_session_id
                  AND session.status='in_progress') THEN
            RAISE EXCEPTION 'invalid Module 6 response creation' USING ERRCODE='23514';
        END IF;
    ELSIF ROW(NEW.assessment_session_id,NEW.assessment_section_id,NEW.response_key,NEW.status)
          IS DISTINCT FROM
          ROW(OLD.assessment_session_id,OLD.assessment_section_id,OLD.response_key,OLD.status)
       OR NEW.current_version_number<>OLD.current_version_number+1
       OR NEW.current_version_id IS NULL OR NEW.current_version_id IS NOT DISTINCT FROM OLD.current_version_id
       OR NOT EXISTS (SELECT 1 FROM response_versions version
            WHERE version.organization_id=NEW.organization_id
              AND version.id=NEW.current_version_id
              AND version.assessment_response_id=NEW.id
              AND version.version_number=NEW.current_version_number) THEN
        RAISE EXCEPTION 'invalid Module 6 response version pointer' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER assessment_responses_version_guard
    BEFORE INSERT OR UPDATE ON assessment_responses
    FOR EACH ROW EXECUTE FUNCTION careos_guard_m6_response();

CREATE FUNCTION careos_guard_m6_response_version()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    actor uuid:=nullif(current_setting('app.current_actor_id',true),'')::uuid;
    expected_version integer;
    expected_prior uuid;
    target_session uuid;
BEGIN
    IF current_user<>'${applicationRole}' THEN RETURN NEW; END IF;
    SELECT response.current_version_number+1,response.current_version_id,response.assessment_session_id
      INTO expected_version,expected_prior,target_session
      FROM assessment_responses response
     WHERE response.organization_id=NEW.organization_id
       AND response.id=NEW.assessment_response_id AND response.status='draft' FOR UPDATE;
    IF expected_version IS NULL OR NEW.version_number<>expected_version
       OR NEW.prior_version_id IS DISTINCT FROM expected_prior
       OR NEW.content_digest<>encode(digest(NEW.content_text,'sha256'),'hex')
       OR NOT careos_m6_practitioner_can_write(
            NEW.organization_id,target_session,actor,NEW.author_practitioner_id,NEW.recorded_at) THEN
        RAISE EXCEPTION 'invalid Module 6 response version evidence' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER response_versions_content_guard
    BEFORE INSERT ON response_versions
    FOR EACH ROW EXECUTE FUNCTION careos_guard_m6_response_version();

CREATE FUNCTION careos_guard_m6_clinical_append()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    actor uuid:=nullif(current_setting('app.current_actor_id',true),'')::uuid;
    target_session uuid:=NEW.assessment_session_id;
    practitioner uuid;
    content_value text;
    digest_value text;
BEGIN
    IF current_user<>'${applicationRole}' THEN RETURN NEW; END IF;
    practitioner:=CASE TG_TABLE_NAME
        WHEN 'clinical_narratives' THEN NEW.author_practitioner_id
        WHEN 'measurements' THEN NEW.recorded_by_practitioner_id
        WHEN 'red_flags' THEN NEW.raised_by_practitioner_id
        ELSE NULL END;
    IF TG_TABLE_NAME='clinical_narratives' THEN
        content_value:=NEW.content_text; digest_value:=NEW.content_digest;
    ELSIF TG_TABLE_NAME='red_flags' THEN
        content_value:=NEW.summary_text; digest_value:=NEW.summary_digest;
    END IF;
    IF TG_OP<>'INSERT' OR practitioner IS NULL
       OR NOT EXISTS (SELECT 1 FROM assessment_sessions session
            WHERE session.organization_id=NEW.organization_id
              AND session.id=target_session AND session.status='in_progress')
       OR NOT careos_m6_practitioner_can_write(
            NEW.organization_id,target_session,actor,practitioner,
            CASE TG_TABLE_NAME WHEN 'red_flags' THEN NEW.raised_at ELSE NEW.recorded_at END)
       OR (content_value IS NOT NULL AND digest_value<>encode(digest(content_value,'sha256'),'hex')) THEN
        RAISE EXCEPTION 'invalid Module 6 clinical append evidence' USING ERRCODE='23514';
    END IF;
    IF TG_TABLE_NAME='measurements' AND lower(replace(NEW.measurement_key,'-','_'))='composite_cure_score' THEN
        RAISE EXCEPTION 'composite cure scores are prohibited' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;

DO $$
DECLARE table_name text;
BEGIN
    FOREACH table_name IN ARRAY ARRAY['clinical_narratives','measurements','red_flags'] LOOP
        EXECUTE format(
            'CREATE TRIGGER %I BEFORE INSERT ON %I FOR EACH ROW EXECUTE FUNCTION careos_guard_m6_clinical_append()',
            table_name||'_clinical_append_guard',table_name);
    END LOOP;
END $$;

CREATE FUNCTION careos_guard_m6_red_flag_update()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    actor uuid:=nullif(current_setting('app.current_actor_id',true),'')::uuid;
    acting_practitioner uuid;
BEGIN
    IF current_user<>'${applicationRole}' THEN RETURN NEW; END IF;
    acting_practitioner:=CASE WHEN NEW.status='acknowledged'
        THEN NEW.acknowledged_by_practitioner_id ELSE NEW.resolved_by_practitioner_id END;
    IF ROW(NEW.assessment_session_id,NEW.assessment_section_id,NEW.severity_key,
           NEW.summary_text,NEW.summary_digest,NEW.source_key,NEW.method_key,
           NEW.owner_practitioner_id,NEW.raised_by_practitioner_id,NEW.raised_at)
       IS DISTINCT FROM
       ROW(OLD.assessment_session_id,OLD.assessment_section_id,OLD.severity_key,
           OLD.summary_text,OLD.summary_digest,OLD.source_key,OLD.method_key,
           OLD.owner_practitioner_id,OLD.raised_by_practitioner_id,OLD.raised_at)
       OR NOT ((OLD.status='raised' AND NEW.status='acknowledged')
            OR (OLD.status='acknowledged' AND NEW.status='resolved'))
       OR NOT careos_m6_practitioner_can_write(
            NEW.organization_id,NEW.assessment_session_id,actor,acting_practitioner,clock_timestamp()) THEN
        RAISE EXCEPTION 'invalid Module 6 red flag transition' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER red_flags_transition_guard
    BEFORE UPDATE ON red_flags
    FOR EACH ROW EXECUTE FUNCTION careos_guard_m6_red_flag_update();

CREATE FUNCTION careos_guard_m6_review()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    actor uuid:=nullif(current_setting('app.current_actor_id',true),'')::uuid;
    expected_completed integer;
BEGIN
    IF current_user<>'${applicationRole}' THEN RETURN NEW; END IF;
    SELECT count(*) INTO expected_completed FROM assessment_sections section
     WHERE section.organization_id=NEW.organization_id
       AND section.assessment_session_id=NEW.assessment_session_id
       AND section.status='complete';
    IF NOT NEW.completeness_confirmed OR NOT NEW.source_reviewed OR NOT NEW.uncertainty_reviewed
       OR NEW.completed_section_count<>expected_completed OR NEW.total_section_count<>27
       OR NEW.review_digest<>encode(digest(NEW.review_summary_text,'sha256'),'hex')
       OR NOT EXISTS (SELECT 1 FROM assessment_sessions session
            WHERE session.organization_id=NEW.organization_id
              AND session.id=NEW.assessment_session_id
              AND session.status='in_progress'
              AND NEW.assessment_revision=session.lock_version+1
              AND NEW.reviewer_practitioner_id=session.responsible_practitioner_id)
       OR NOT careos_m6_practitioner_can_write(
            NEW.organization_id,NEW.assessment_session_id,actor,
            NEW.reviewer_practitioner_id,NEW.reviewed_at)
       OR EXISTS (SELECT 1 FROM red_flags flag
            WHERE flag.organization_id=NEW.organization_id
              AND flag.assessment_session_id=NEW.assessment_session_id
              AND flag.status<>'resolved') THEN
        RAISE EXCEPTION 'invalid Module 6 assessment review evidence' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER assessment_reviews_evidence_guard
    BEFORE INSERT ON assessment_reviews
    FOR EACH ROW EXECUTE FUNCTION careos_guard_m6_review();

CREATE FUNCTION careos_guard_m6_signature()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE actor uuid:=nullif(current_setting('app.current_actor_id',true),'')::uuid;
BEGIN
    IF current_user<>'${applicationRole}' THEN RETURN NEW; END IF;
    IF NEW.attestation_key<>'structural-clinician-review-v1'
       OR NEW.signature_digest<>encode(digest(concat_ws('|',
            NEW.assessment_session_id::text,NEW.assessment_review_id::text,
            NEW.assessment_revision::text,NEW.signer_practitioner_id::text,
            NEW.attestation_key),'sha256'),'hex')
       OR NOT EXISTS (SELECT 1 FROM assessment_sessions session
            JOIN assessment_reviews review
              ON review.organization_id=session.organization_id
             AND review.id=NEW.assessment_review_id
             AND review.assessment_session_id=session.id
             AND review.assessment_revision=session.lock_version
            WHERE session.organization_id=NEW.organization_id
              AND session.id=NEW.assessment_session_id
              AND session.status='in_review'
              AND NEW.assessment_revision=session.lock_version+1
              AND NEW.signer_practitioner_id=session.responsible_practitioner_id)
       OR NOT careos_m6_practitioner_can_write(
            NEW.organization_id,NEW.assessment_session_id,actor,
            NEW.signer_practitioner_id,NEW.signed_at)
       OR EXISTS (SELECT 1 FROM red_flags flag
            WHERE flag.organization_id=NEW.organization_id
              AND flag.assessment_session_id=NEW.assessment_session_id
              AND flag.status<>'resolved') THEN
        RAISE EXCEPTION 'invalid Module 6 assessment signature evidence' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER assessment_signatures_evidence_guard
    BEFORE INSERT ON assessment_signatures
    FOR EACH ROW EXECUTE FUNCTION careos_guard_m6_signature();

CREATE FUNCTION careos_guard_m6_amendment()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE actor uuid:=nullif(current_setting('app.current_actor_id',true),'')::uuid;
BEGIN
    IF current_user<>'${applicationRole}' THEN RETURN NEW; END IF;
    IF NEW.amendment_digest<>encode(digest(NEW.amendment_text,'sha256'),'hex')
       OR NOT EXISTS (SELECT 1 FROM assessment_sessions session
            JOIN assessment_signatures signature
              ON signature.organization_id=session.organization_id
             AND signature.id=NEW.assessment_signature_id
             AND signature.assessment_session_id=session.id
            WHERE session.organization_id=NEW.organization_id
              AND session.id=NEW.assessment_session_id
              AND session.status IN ('signed','amended')
              AND NEW.assessment_revision=session.lock_version+1
              AND NEW.author_practitioner_id=session.responsible_practitioner_id)
       OR NOT careos_m6_practitioner_can_write(
            NEW.organization_id,NEW.assessment_session_id,actor,
            NEW.author_practitioner_id,NEW.recorded_at) THEN
        RAISE EXCEPTION 'invalid Module 6 assessment amendment evidence' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER assessment_amendments_evidence_guard
    BEFORE INSERT ON assessment_amendments
    FOR EACH ROW EXECUTE FUNCTION careos_guard_m6_amendment();

CREATE FUNCTION careos_check_m6_review_committed()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF current_user='${applicationRole}' AND NOT EXISTS (
        SELECT 1 FROM assessment_sessions session
        WHERE session.organization_id=NEW.organization_id
          AND session.id=NEW.assessment_session_id
          AND session.lock_version>=NEW.assessment_revision
          AND session.status IN ('in_review','signed','amended','completed')) THEN
        RAISE EXCEPTION 'assessment review must commit with its session transition' USING ERRCODE='23514';
    END IF;
    RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER assessment_reviews_commit_guard
    AFTER INSERT ON assessment_reviews DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION careos_check_m6_review_committed();

CREATE FUNCTION careos_check_m6_signature_committed()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF current_user='${applicationRole}' AND NOT EXISTS (
        SELECT 1 FROM assessment_sessions session
        WHERE session.organization_id=NEW.organization_id
          AND session.id=NEW.assessment_session_id
          AND session.lock_version>=NEW.assessment_revision
          AND session.status IN ('signed','amended','completed')) THEN
        RAISE EXCEPTION 'assessment signature must commit with its session transition' USING ERRCODE='23514';
    END IF;
    RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER assessment_signatures_commit_guard
    AFTER INSERT ON assessment_signatures DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION careos_check_m6_signature_committed();

CREATE FUNCTION careos_check_m6_amendment_committed()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF current_user='${applicationRole}' AND NOT EXISTS (
        SELECT 1 FROM assessment_sessions session
        WHERE session.organization_id=NEW.organization_id
          AND session.id=NEW.assessment_session_id
          AND session.lock_version>=NEW.assessment_revision
          AND session.status IN ('amended','completed')) THEN
        RAISE EXCEPTION 'assessment amendment must commit with its session transition' USING ERRCODE='23514';
    END IF;
    RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER assessment_amendments_commit_guard
    AFTER INSERT ON assessment_amendments DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION careos_check_m6_amendment_committed();

COMMENT ON FUNCTION careos_m6_practitioner_can_write IS
    'Requires authenticated actor/practitioner correlation, active encounter participation and current service eligibility.';
