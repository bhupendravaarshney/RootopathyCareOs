CREATE TEMP TABLE m8_audit_seed (
    event_name varchar(160) PRIMARY KEY,
    subject_type varchar(120) NOT NULL,
    operation_key varchar(160) NOT NULL,
    required_keys text[] NOT NULL,
    allowed_keys text[] NOT NULL
) ON COMMIT DROP;

INSERT INTO m8_audit_seed VALUES
 ('ai.session.launched','ai_session','ai.session.launch',
  ARRAY['aiSessionId','patientId','encounterId','sessionType','status'],
  ARRAY['aiSessionId','patientId','encounterId','sessionType','status']),
 ('ai.purpose_consent.recorded','ai_purpose_consent','ai.consent.record',
  ARRAY['aiSessionId','purposeConsentId','purposeKey','consentStatus','status','revision'],
  ARRAY['aiSessionId','purposeConsentId','purposeKey','legalBasisKey','consentStatus','status','revision']),
 ('ai.input_manifest.approved','ai_input_manifest','ai.input.select',
  ARRAY['aiSessionId','inputManifestId','manifestVersion','itemCount','digest','status','revision'],
  ARRAY['aiSessionId','inputManifestId','manifestVersion','itemCount','sourceType','digest','status','revision']),
 ('ai.processing.recorded','ai_job_contract','ai.process.request',
  ARRAY['aiSessionId','jobContractId','jobAttemptId','attemptNumber','status','revision'],
  ARRAY['aiSessionId','jobContractId','jobAttemptId','aiOutputId','attemptNumber','status','failureCode','revision']),
 ('ai.output.edited','ai_output_version','ai.output.edit',
  ARRAY['aiSessionId','aiOutputId','aiOutputVersionId','versionNumber','digest','status','revision'],
  ARRAY['aiSessionId','aiOutputId','aiOutputVersionId','versionNumber','digest','status','revision']),
 ('ai.safety_flag.reviewed','ai_safety_flag','ai.safety.review',
  ARRAY['aiSessionId','aiOutputId','safetyFlagId','fromState','toState','revision'],
  ARRAY['aiSessionId','aiOutputId','safetyFlagId','fromState','toState','revision']),
 ('ai.output.decided','ai_review','ai.review.decide',
  ARRAY['aiSessionId','aiOutputId','aiOutputVersionId','aiReviewId','decision','digest','status','revision'],
  ARRAY['aiSessionId','aiOutputId','aiOutputVersionId','aiReviewId','decision','digest','status','revision']),
 ('ai.session.cancelled','ai_session','ai.session.cancel',
  ARRAY['aiSessionId','fromStatus','status','revision'],
  ARRAY['aiSessionId','fromStatus','status','revision']);

INSERT INTO audit_event_definitions
    (event_name,schema_version,display_name,description,subject_type,reason_required,
     required_payload_keys,allowed_payload_keys,payload_schema,status,registry_version)
SELECT event_name,1,replace(initcap(replace(event_name,'.',' ')),'_',' '),
       'CareOS Module 8 governed AI evidence.',subject_type,true,
       required_keys,allowed_keys,'{"type":"object","additionalProperties":false}'::jsonb,
       'active','m8-standing-direction-v1'
FROM m8_audit_seed;

INSERT INTO authorization_operation_events
    (operation_key,event_kind,event_name,schema_version,status,registry_version)
SELECT operation_key,'audit',event_name,1,'active','m8-standing-direction-v1'
FROM m8_audit_seed;

CREATE TEMP TABLE m8_outbox_seed (
    event_name varchar(180) PRIMARY KEY,
    aggregate_type varchar(120) NOT NULL,
    operation_key varchar(160) NOT NULL,
    required_keys text[] NOT NULL,
    allowed_keys text[] NOT NULL
) ON COMMIT DROP;

INSERT INTO m8_outbox_seed VALUES
 ('m8.ai-session-launched.v1','ai_session','ai.session.launch',
  ARRAY['aiSessionId','patientId','encounterId','sessionType','status'],
  ARRAY['aiSessionId','patientId','encounterId','sessionType','status']),
 ('m8.ai-input-ready.v1','ai_session','ai.input.select',
  ARRAY['aiSessionId','inputManifestId','itemCount','status'],
  ARRAY['aiSessionId','inputManifestId','itemCount','status']),
 ('m8.ai-processing-state-changed.v1','ai_session','ai.process.request',
  ARRAY['aiSessionId','jobContractId','status'],
  ARRAY['aiSessionId','jobContractId','aiOutputId','status','failureCode']),
 ('m8.ai-safety-state-changed.v1','ai_safety_flag','ai.safety.review',
  ARRAY['aiSessionId','safetyFlagId','state'],
  ARRAY['aiSessionId','safetyFlagId','state']),
 ('m8.ai-output-decided.v1','ai_session','ai.review.decide',
  ARRAY['aiSessionId','aiOutputId','decision','status'],
  ARRAY['aiSessionId','aiOutputId','decision','status']);

INSERT INTO outbox_event_definitions
    (event_name,schema_version,description,aggregate_type,required_payload_keys,
     allowed_payload_keys,payload_schema,status,registry_version)
SELECT event_name,1,'CareOS Module 8 transactional AI event.',aggregate_type,
       required_keys,allowed_keys,'{"type":"object","additionalProperties":false}'::jsonb,
       'active','m8-standing-direction-v1'
FROM m8_outbox_seed;

INSERT INTO authorization_operation_events
    (operation_key,event_kind,event_name,schema_version,status,registry_version)
SELECT operation_key,'outbox',event_name,1,'active','m8-standing-direction-v1'
FROM m8_outbox_seed;

CREATE FUNCTION careos_m8_sha256(value text)
RETURNS char(64) LANGUAGE sql IMMUTABLE STRICT AS $$
    SELECT encode(sha256(convert_to(value,'UTF8')),'hex')::char(64)
$$;

CREATE FUNCTION careos_guard_m8_session()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE configured_operation text:=nullif(current_setting('app.current_operation_key',true),'');
BEGIN
    IF current_user<>'${applicationRole}' THEN RETURN NEW; END IF;
    IF TG_OP='INSERT' THEN
        IF configured_operation<>'ai.session.launch' OR NEW.status<>'draft'
           OR NEW.requester_id IS DISTINCT FROM NEW.created_by
           OR NOT EXISTS (
                SELECT 1 FROM patient_profiles patient
                JOIN encounters encounter
                  ON encounter.organization_id=patient.organization_id
                 AND encounter.patient_id=patient.id
                WHERE patient.organization_id=NEW.organization_id
                  AND patient.id=NEW.patient_id
                  AND patient.lifecycle_state='active'
                  AND patient.verification_state<>'unverified'
                  AND encounter.id=NEW.encounter_id
                  AND encounter.status IN ('arrived','in_progress','on_hold')) THEN
            RAISE EXCEPTION 'invalid Module 8 AI session launch' USING ERRCODE='23514';
        END IF;
        RETURN NEW;
    END IF;
    IF ROW(NEW.patient_id,NEW.encounter_id,NEW.requester_id,NEW.session_type)
       IS DISTINCT FROM ROW(OLD.patient_id,OLD.encounter_id,OLD.requester_id,OLD.session_type) THEN
        RAISE EXCEPTION 'Module 8 AI session identity is immutable' USING ERRCODE='42501';
    END IF;
    IF configured_operation='ai.consent.record' THEN
        IF OLD.status<>'draft' OR NEW.status<>'authorized' OR NEW.purpose_key IS NULL
           OR NEW.current_manifest_id IS NOT NULL OR NEW.current_output_id IS NOT NULL
           OR NOT EXISTS (
                SELECT 1 FROM ai_purpose_consents consent
                WHERE consent.organization_id=NEW.organization_id
                  AND consent.ai_session_id=NEW.id
                  AND consent.purpose_key=NEW.purpose_key
                  AND consent.minimum_necessary_confirmed
                  AND consent.consent_status IN ('granted','not_required')) THEN
            RAISE EXCEPTION 'invalid Module 8 purpose and consent transition' USING ERRCODE='23514';
        END IF;
    ELSIF configured_operation='ai.input.select' THEN
        IF OLD.status<>'authorized' OR NEW.status<>'input_ready'
           OR NEW.purpose_key IS DISTINCT FROM OLD.purpose_key
           OR NEW.current_manifest_id IS NULL OR NEW.current_output_id IS NOT NULL
           OR NOT EXISTS (
                SELECT 1 FROM ai_input_manifests manifest
                WHERE manifest.organization_id=NEW.organization_id
                  AND manifest.id=NEW.current_manifest_id
                  AND manifest.ai_session_id=NEW.id) THEN
            RAISE EXCEPTION 'invalid Module 8 input-manifest transition' USING ERRCODE='23514';
        END IF;
    ELSIF configured_operation='ai.process.request' THEN
        IF NOT ((OLD.status IN ('input_ready','failed') AND NEW.status='processing')
             OR (OLD.status='processing' AND NEW.status IN ('draft_ready','failed')))
           OR NEW.purpose_key IS DISTINCT FROM OLD.purpose_key
           OR NEW.current_manifest_id IS DISTINCT FROM OLD.current_manifest_id
           OR (NEW.status='draft_ready' AND NEW.current_output_id IS NULL)
           OR (NEW.status='failed' AND NEW.failure_code IS NULL) THEN
            RAISE EXCEPTION 'invalid Module 8 processing transition' USING ERRCODE='23514';
        END IF;
    ELSIF configured_operation='ai.output.edit' THEN
        IF OLD.status NOT IN ('draft_ready','under_review') OR NEW.status<>'under_review'
           OR NEW.current_output_id IS DISTINCT FROM OLD.current_output_id
           OR NEW.current_manifest_id IS DISTINCT FROM OLD.current_manifest_id THEN
            RAISE EXCEPTION 'invalid Module 8 draft-edit transition' USING ERRCODE='23514';
        END IF;
    ELSIF configured_operation='ai.review.decide' THEN
        IF OLD.status NOT IN ('draft_ready','under_review')
           OR NEW.status NOT IN ('accepted','rejected')
           OR NOT EXISTS (
                SELECT 1 FROM ai_reviews review
                WHERE review.organization_id=NEW.organization_id
                  AND review.ai_session_id=NEW.id
                  AND review.decision=NEW.status) THEN
            RAISE EXCEPTION 'invalid Module 8 clinician decision transition' USING ERRCODE='23514';
        END IF;
    ELSIF configured_operation='ai.session.cancel' THEN
        IF OLD.status IN ('accepted','rejected','failed','cancelled') OR NEW.status<>'cancelled'
           OR NEW.current_manifest_id IS DISTINCT FROM OLD.current_manifest_id
           OR NEW.current_output_id IS DISTINCT FROM OLD.current_output_id THEN
            RAISE EXCEPTION 'invalid Module 8 cancellation transition' USING ERRCODE='23514';
        END IF;
    ELSE
        RAISE EXCEPTION 'unsupported Module 8 AI session mutation' USING ERRCODE='42501';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER ai_sessions_lifecycle_guard
    BEFORE INSERT OR UPDATE ON ai_sessions
    FOR EACH ROW EXECUTE FUNCTION careos_guard_m8_session();

CREATE FUNCTION careos_guard_m8_purpose_consent()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF current_user<>'${applicationRole}' THEN RETURN NEW; END IF;
    IF NEW.consent_status NOT IN ('granted','not_required')
       OR NOT NEW.minimum_necessary_confirmed
       OR NOT EXISTS (
            SELECT 1 FROM ai_sessions session
            WHERE session.organization_id=NEW.organization_id
              AND session.id=NEW.ai_session_id AND session.status='draft') THEN
        RAISE EXCEPTION 'invalid Module 8 purpose or consent evidence' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER ai_purpose_consents_guard
    BEFORE INSERT ON ai_purpose_consents
    FOR EACH ROW EXECUTE FUNCTION careos_guard_m8_purpose_consent();

CREATE FUNCTION careos_guard_m8_input_item()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE session_patient uuid; session_encounter uuid;
BEGIN
    IF current_user<>'${applicationRole}' THEN RETURN NEW; END IF;
    SELECT session.patient_id,session.encounter_id INTO session_patient,session_encounter
    FROM ai_input_manifests manifest
    JOIN ai_sessions session ON session.organization_id=manifest.organization_id
                            AND session.id=manifest.ai_session_id
    WHERE manifest.organization_id=NEW.organization_id AND manifest.id=NEW.input_manifest_id;
    IF session_patient IS NULL OR NOT (
        (NEW.source_type='encounter' AND NEW.source_id=session_encounter AND EXISTS (
            SELECT 1 FROM encounters source WHERE source.organization_id=NEW.organization_id
              AND source.id=NEW.source_id AND source.patient_id=session_patient
              AND source.lock_version=NEW.source_revision))
        OR (NEW.source_type='assessment' AND EXISTS (
            SELECT 1 FROM assessment_sessions source WHERE source.organization_id=NEW.organization_id
              AND source.id=NEW.source_id AND source.patient_id=session_patient
              AND source.encounter_id=session_encounter AND source.lock_version=NEW.source_revision))
        OR (NEW.source_type='document' AND EXISTS (
            SELECT 1 FROM documents source WHERE source.organization_id=NEW.organization_id
              AND source.id=NEW.source_id AND source.patient_id=session_patient
              AND source.lock_version=NEW.source_revision))
        OR (NEW.source_type='diagnostic_report' AND EXISTS (
            SELECT 1 FROM diagnostic_reports source WHERE source.organization_id=NEW.organization_id
              AND source.id=NEW.source_id AND source.patient_id=session_patient
              AND source.lock_version=NEW.source_revision))
    ) THEN
        RAISE EXCEPTION 'invalid Module 8 minimum-necessary input reference' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER ai_input_manifest_items_source_guard
    BEFORE INSERT ON ai_input_manifest_items
    FOR EACH ROW EXECUTE FUNCTION careos_guard_m8_input_item();

CREATE FUNCTION careos_guard_m8_job_contract()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF current_user<>'${applicationRole}' THEN RETURN NEW; END IF;
    IF NOT EXISTS (
        SELECT 1 FROM ai_sessions session
        JOIN ai_purpose_consents consent
          ON consent.organization_id=session.organization_id AND consent.ai_session_id=session.id
        JOIN ai_input_manifests manifest
          ON manifest.organization_id=session.organization_id AND manifest.id=session.current_manifest_id
        JOIN ai_model_releases model
          ON model.organization_id=session.organization_id AND model.id=NEW.model_release_id
        JOIN ai_prompt_releases prompt
          ON prompt.organization_id=session.organization_id AND prompt.id=NEW.prompt_release_id
        JOIN ai_evaluation_signoffs evaluation
          ON evaluation.organization_id=session.organization_id
         AND evaluation.id=NEW.evaluation_signoff_id
         AND evaluation.model_release_id=model.id
         AND evaluation.prompt_release_id=prompt.id
        WHERE session.organization_id=NEW.organization_id AND session.id=NEW.ai_session_id
          AND session.status IN ('input_ready','failed') AND manifest.id=NEW.input_manifest_id
          AND consent.purpose_key=session.purpose_key
          AND consent.consent_status IN ('granted','not_required')
          AND consent.minimum_necessary_confirmed
          AND model.status='active' AND model.training_use_prohibited
          AND model.effective_from<=clock_timestamp()
          AND prompt.status='active' AND prompt.purpose_key=session.purpose_key
          AND prompt.effective_from<=clock_timestamp()
          AND prompt.output_schema_key=NEW.output_schema_key
          AND prompt.output_schema_version=NEW.output_schema_version
          AND evaluation.outcome='approved' AND evaluation.expires_at>clock_timestamp()) THEN
        RAISE EXCEPTION 'Module 8 model, prompt or evaluation release is unavailable' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER ai_job_contracts_release_guard
    BEFORE INSERT ON ai_job_contracts
    FOR EACH ROW EXECUTE FUNCTION careos_guard_m8_job_contract();

CREATE FUNCTION careos_guard_m8_output_version()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE previous_number integer; previous_id uuid;
BEGIN
    IF current_user<>'${applicationRole}' THEN RETURN NEW; END IF;
    SELECT coalesce(max(version.version_number),0),
           (array_agg(version.id ORDER BY version.version_number DESC))[1]
      INTO previous_number,previous_id
      FROM ai_output_versions version
     WHERE version.organization_id=NEW.organization_id AND version.ai_output_id=NEW.ai_output_id;
    IF NEW.version_number<>previous_number+1
       OR NEW.prior_version_id IS DISTINCT FROM previous_id
       OR NEW.content_digest<>careos_m8_sha256(NEW.content)
       OR (NEW.version_number=1 AND NEW.authored_by_type<>'provider')
       OR (NEW.version_number>1 AND NEW.authored_by_type<>'clinician') THEN
        RAISE EXCEPTION 'invalid Module 8 output version evidence' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER ai_output_versions_lineage_guard
    BEFORE INSERT ON ai_output_versions
    FOR EACH ROW EXECUTE FUNCTION careos_guard_m8_output_version();

CREATE FUNCTION careos_guard_m8_citation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF current_user<>'${applicationRole}' THEN RETURN NEW; END IF;
    IF NOT EXISTS (
        SELECT 1 FROM ai_output_versions version
        JOIN ai_outputs output ON output.organization_id=version.organization_id
                              AND output.id=version.ai_output_id
        JOIN ai_sessions session ON session.organization_id=output.organization_id
                                AND session.id=output.ai_session_id
        JOIN ai_input_manifest_items item ON item.organization_id=session.organization_id
                                         AND item.input_manifest_id=session.current_manifest_id
        WHERE version.organization_id=NEW.organization_id
          AND version.id=NEW.ai_output_version_id
          AND item.id=NEW.input_manifest_item_id) THEN
        RAISE EXCEPTION 'invalid Module 8 citation provenance' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER ai_output_citations_provenance_guard
    BEFORE INSERT ON ai_output_citations
    FOR EACH ROW EXECUTE FUNCTION careos_guard_m8_citation();

CREATE FUNCTION careos_guard_m8_safety_flag()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE configured_operation text:=nullif(current_setting('app.current_operation_key',true),'');
BEGIN
    IF current_user<>'${applicationRole}' THEN RETURN NEW; END IF;
    IF TG_OP='INSERT' THEN
        IF configured_operation<>'ai.process.request' OR NEW.state<>'open' THEN
            RAISE EXCEPTION 'invalid Module 8 safety flag creation' USING ERRCODE='23514';
        END IF;
    ELSIF configured_operation<>'ai.safety.review'
       OR ROW(NEW.ai_output_id,NEW.flag_key,NEW.severity,NEW.summary)
          IS DISTINCT FROM ROW(OLD.ai_output_id,OLD.flag_key,OLD.severity,OLD.summary)
       OR NOT ((OLD.state='open' AND NEW.state='acknowledged')
            OR (OLD.state='acknowledged' AND NEW.state='resolved'))
       OR (OLD.severity IN ('critical','emergency') AND NOT EXISTS (
            SELECT 1 FROM ai_safety_escalations escalation
            WHERE escalation.organization_id=OLD.organization_id
              AND escalation.ai_safety_flag_id=OLD.id)) THEN
        RAISE EXCEPTION 'invalid Module 8 safety flag transition' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER ai_safety_flags_lifecycle_guard
    BEFORE INSERT OR UPDATE ON ai_safety_flags
    FOR EACH ROW EXECUTE FUNCTION careos_guard_m8_safety_flag();

CREATE FUNCTION careos_guard_m8_safety_escalation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF current_user<>'${applicationRole}' THEN RETURN NEW; END IF;
    IF NOT EXISTS (
        SELECT 1 FROM ai_safety_flags flag
        WHERE flag.organization_id=NEW.organization_id AND flag.id=NEW.ai_safety_flag_id
          AND flag.severity IN ('critical','emergency') AND flag.state='open')
       OR NEW.due_at<clock_timestamp()-interval '5 seconds' THEN
        RAISE EXCEPTION 'invalid Module 8 emergency escalation evidence' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER ai_safety_escalations_guard
    BEFORE INSERT ON ai_safety_escalations
    FOR EACH ROW EXECUTE FUNCTION careos_guard_m8_safety_escalation();

CREATE FUNCTION careos_guard_m8_review()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE latest_version uuid; latest_digest char(64);
BEGIN
    IF current_user<>'${applicationRole}' THEN RETURN NEW; END IF;
    SELECT version.id,version.content_digest INTO latest_version,latest_digest
    FROM ai_output_versions version
    WHERE version.organization_id=NEW.organization_id AND version.ai_output_id=NEW.ai_output_id
    ORDER BY version.version_number DESC LIMIT 1;
    IF latest_version IS DISTINCT FROM NEW.ai_output_version_id
       OR latest_digest IS DISTINCT FROM NEW.final_version_digest
       OR NEW.recent_authentication_at<clock_timestamp()-interval '10 minutes'
       OR NEW.recent_authentication_at>clock_timestamp()+interval '5 seconds'
       OR NEW.mfa_authenticated_at<clock_timestamp()-interval '10 minutes'
       OR NEW.mfa_authenticated_at>clock_timestamp()+interval '5 seconds'
       OR NOT EXISTS (
            SELECT 1 FROM ai_outputs output
            JOIN ai_sessions session ON session.organization_id=output.organization_id
                                    AND session.id=output.ai_session_id
            JOIN encounters encounter ON encounter.organization_id=session.organization_id
                                     AND encounter.id=session.encounter_id
            WHERE output.organization_id=NEW.organization_id AND output.id=NEW.ai_output_id
              AND session.id=NEW.ai_session_id
              AND session.status IN ('draft_ready','under_review')
              AND encounter.responsible_practitioner_id=NEW.reviewer_practitioner_id
              AND encounter.status IN ('arrived','in_progress','on_hold'))
       OR (NEW.decision='accepted' AND EXISTS (
            SELECT 1 FROM ai_safety_flags flag
            WHERE flag.organization_id=NEW.organization_id AND flag.ai_output_id=NEW.ai_output_id
              AND flag.state<>'resolved')) THEN
        RAISE EXCEPTION 'invalid Module 8 clinician review evidence' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER ai_reviews_decision_guard
    BEFORE INSERT ON ai_reviews
    FOR EACH ROW EXECUTE FUNCTION careos_guard_m8_review();

COMMENT ON TABLE ai_reviews IS
    'Explicit clinician accept/reject evidence bound to the latest immutable output version; acceptance is never defaulted.';
