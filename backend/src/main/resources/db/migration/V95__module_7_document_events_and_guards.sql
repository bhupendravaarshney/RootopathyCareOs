CREATE TEMP TABLE m7_audit_seed (
    event_name varchar(160) PRIMARY KEY,
    subject_type varchar(120) NOT NULL,
    operation_key varchar(160) NOT NULL,
    required_keys text[] NOT NULL,
    allowed_keys text[] NOT NULL,
    reason_required boolean NOT NULL
) ON COMMIT DROP;

INSERT INTO m7_audit_seed VALUES
 ('document.version.uploaded','document','document.upload',
  ARRAY['documentId','documentVersionId','patientId','versionNumber','status','digest'],
  ARRAY['documentId','documentVersionId','patientId','encounterId','assessmentSessionId','versionNumber','status','digest'],true),
 ('document.classification.appended','document_classification','document.classify',
  ARRAY['documentId','documentVersionId','classificationId','classificationVersion','categoryKey','confidentialityKey'],
  ARRAY['documentId','documentVersionId','classificationId','classificationVersion','categoryKey','confidentialityKey','retentionClassKey'],true),
 ('document.scan.bound','document_version','document.scan.bind',
  ARRAY['documentId','documentVersionId','scanAttemptId','verdict','status','revision'],
  ARRAY['documentId','documentVersionId','scanAttemptId','verdict','status','revision'],false),
 ('diagnostic.report.recorded','diagnostic_report','document.result.write',
  ARRAY['documentId','documentVersionId','diagnosticReportId','patientId','reportType','status','resultCount','digest'],
  ARRAY['documentId','documentVersionId','diagnosticReportId','patientId','encounterId','reportType','status','resultCount','flagId','flagKind','digest'],true),
 ('result.flag.acknowledged','result_flag','document.result.review',
  ARRAY['diagnosticReportId','resultFlagId','reviewId','fromStatus','toStatus','revision'],
  ARRAY['diagnosticReportId','resultFlagId','reviewId','fromStatus','toStatus','revision'],true),
 ('result.flag.resolved','result_flag','document.result.review',
  ARRAY['diagnosticReportId','resultFlagId','reviewId','fromStatus','toStatus','revision'],
  ARRAY['diagnosticReportId','resultFlagId','reviewId','fromStatus','toStatus','revision'],true),
 ('result.flag.escalated','result_escalation','document.result.escalate',
  ARRAY['diagnosticReportId','resultFlagId','escalationId','escalationLevel','deliveryStatus'],
  ARRAY['diagnosticReportId','resultFlagId','escalationId','escalationLevel','deliveryStatus'],true),
 ('document.access.granted','document_access_intent','document.access',
  ARRAY['documentId','documentVersionId','accessIntentId','accessGrantId','purposeKey','expiresAt'],
  ARRAY['documentId','documentVersionId','accessIntentId','accessGrantId','purposeKey','expiresAt'],true),
 ('document.access_intent.recorded','document_access_intent','document.intent.create',
  ARRAY['documentId','documentVersionId','accessIntentId','intentType','purposeKey','status'],
  ARRAY['documentId','documentVersionId','accessIntentId','intentType','purposeKey','status','recipientType','requestedFormat'],true);

INSERT INTO audit_event_definitions
    (event_name,schema_version,display_name,description,subject_type,reason_required,
     required_payload_keys,allowed_payload_keys,payload_schema,status,registry_version)
SELECT event_name,1,replace(initcap(replace(event_name,'.',' ')),'_',' '),
       'CareOS Module 7 governed document and result evidence.',subject_type,reason_required,
       required_keys,allowed_keys,'{"type":"object","additionalProperties":false}'::jsonb,
       'active','m7-standing-direction-v1'
FROM m7_audit_seed;

INSERT INTO authorization_operation_events
    (operation_key,event_kind,event_name,schema_version,status,registry_version)
SELECT operation_key,'audit',event_name,1,'active','m7-standing-direction-v1'
FROM m7_audit_seed;

CREATE TEMP TABLE m7_outbox_seed (
    event_name varchar(180) PRIMARY KEY,
    aggregate_type varchar(120) NOT NULL,
    operation_key varchar(160) NOT NULL,
    required_keys text[] NOT NULL,
    allowed_keys text[] NOT NULL
) ON COMMIT DROP;

INSERT INTO m7_outbox_seed VALUES
 ('m7.document.quarantined.v1','document','document.upload',
  ARRAY['documentId','documentVersionId','patientId','versionNumber','status','digest'],
  ARRAY['documentId','documentVersionId','patientId','versionNumber','status','digest']),
 ('m7.document.scan-state-changed.v1','document_version','document.scan.bind',
  ARRAY['documentId','documentVersionId','verdict','status'],
  ARRAY['documentId','documentVersionId','verdict','status']),
 ('m7.diagnostic-report.recorded.v1','diagnostic_report','document.result.write',
  ARRAY['diagnosticReportId','patientId','reportType','status','flagKind'],
  ARRAY['diagnosticReportId','patientId','reportType','status','flagKind']),
 ('m7.result-flag-reviewed.v1','result_flag','document.result.review',
  ARRAY['diagnosticReportId','resultFlagId','status'],
  ARRAY['diagnosticReportId','resultFlagId','status']),
 ('m7.result-flag-escalated.v1','result_flag','document.result.escalate',
  ARRAY['diagnosticReportId','resultFlagId','escalationLevel','deliveryStatus'],
  ARRAY['diagnosticReportId','resultFlagId','escalationLevel','deliveryStatus']);

INSERT INTO outbox_event_definitions
    (event_name,schema_version,description,aggregate_type,required_payload_keys,
     allowed_payload_keys,payload_schema,status,registry_version)
SELECT event_name,1,'CareOS Module 7 transactional document/result event.',aggregate_type,
       required_keys,allowed_keys,'{"type":"object","additionalProperties":false}'::jsonb,
       'active','m7-standing-direction-v1'
FROM m7_outbox_seed;

INSERT INTO authorization_operation_events
    (operation_key,event_kind,event_name,schema_version,status,registry_version)
SELECT operation_key,'outbox',event_name,1,'active','m7-standing-direction-v1'
FROM m7_outbox_seed;

CREATE FUNCTION careos_m7_sha256(value text)
RETURNS char(64) LANGUAGE sql IMMUTABLE STRICT AS $$
    SELECT encode(sha256(convert_to(value,'UTF8')),'hex')::char(64)
$$;

CREATE FUNCTION careos_guard_m7_document()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE configured_operation text:=nullif(current_setting('app.current_operation_key',true),'');
BEGIN
    IF current_user<>'${applicationRole}' THEN RETURN NEW; END IF;
    IF configured_operation NOT IN ('document.upload','document.classify') THEN
        RAISE EXCEPTION 'unsupported Module 7 document mutation' USING ERRCODE='42501';
    END IF;
    IF TG_OP='INSERT' THEN
        IF configured_operation<>'document.upload'
           OR NEW.current_version_id IS NOT NULL OR NEW.current_version_number<>0
           OR NEW.status<>'active'
           OR NOT EXISTS (SELECT 1 FROM patient_profiles patient
                WHERE patient.organization_id=NEW.organization_id AND patient.id=NEW.patient_id
                  AND patient.lifecycle_state='active'
                  AND patient.verification_state<>'unverified') THEN
            RAISE EXCEPTION 'invalid Module 7 document creation' USING ERRCODE='23514';
        END IF;
    ELSIF configured_operation='document.classify' THEN
        IF ROW(NEW.patient_id,NEW.title,NEW.document_type_key,NEW.source_key,NEW.status,
               NEW.current_version_id,NEW.current_version_number)
           IS DISTINCT FROM
           ROW(OLD.patient_id,OLD.title,OLD.document_type_key,OLD.source_key,OLD.status,
               OLD.current_version_id,OLD.current_version_number)
           OR NOT EXISTS (SELECT 1 FROM document_classifications classification
                WHERE classification.organization_id=NEW.organization_id
                  AND classification.document_id=NEW.id
                  AND classification.classification_version=(
                    SELECT max(candidate.classification_version)
                    FROM document_classifications candidate
                    WHERE candidate.organization_id=NEW.organization_id
                      AND candidate.document_id=NEW.id)) THEN
            RAISE EXCEPTION 'invalid Module 7 document classification revision' USING ERRCODE='23514';
        END IF;
    ELSIF ROW(NEW.patient_id,NEW.title,NEW.document_type_key,NEW.source_key,NEW.status)
          IS DISTINCT FROM
          ROW(OLD.patient_id,OLD.title,OLD.document_type_key,OLD.source_key,OLD.status)
       OR NEW.current_version_number<>OLD.current_version_number+1
       OR NEW.current_version_id IS NULL
       OR NOT EXISTS (SELECT 1 FROM document_versions version
            WHERE version.organization_id=NEW.organization_id
              AND version.id=NEW.current_version_id
              AND version.document_id=NEW.id
              AND version.version_number=NEW.current_version_number
              AND version.prior_version_id IS NOT DISTINCT FROM OLD.current_version_id) THEN
        RAISE EXCEPTION 'invalid Module 7 document version pointer' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER documents_lifecycle_guard
    BEFORE INSERT OR UPDATE ON documents
    FOR EACH ROW EXECUTE FUNCTION careos_guard_m7_document();

CREATE FUNCTION careos_guard_m7_document_version()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    configured_operation text:=nullif(current_setting('app.current_operation_key',true),'');
    current_number integer;
    current_version uuid;
    expected_digest text;
    expected_bytes bigint;
    expected_media text;
BEGIN
    IF current_user<>'${applicationRole}' THEN RETURN NEW; END IF;
    IF TG_OP='INSERT' THEN
        IF configured_operation<>'document.upload' OR NEW.status<>'quarantined'
           OR NEW.platform_scan_attestation_id IS NOT NULL OR NEW.scanned_at IS NOT NULL
           OR NEW.promoted_at IS NOT NULL THEN
            RAISE EXCEPTION 'invalid Module 7 document version creation' USING ERRCODE='23514';
        END IF;
        SELECT document.current_version_number,document.current_version_id
          INTO current_number,current_version
          FROM documents document
         WHERE document.organization_id=NEW.organization_id AND document.id=NEW.document_id
           AND document.status='active' FOR UPDATE;
        SELECT evidence.sha256,evidence.declared_bytes,evidence.media_type
          INTO expected_digest,expected_bytes,expected_media
          FROM document_quarantine_evidence evidence
         WHERE evidence.organization_id=NEW.organization_id
           AND evidence.document_id=NEW.document_id
           AND evidence.object_version_id=NEW.id;
        IF current_number IS NULL OR NEW.version_number<>current_number+1
           OR NEW.prior_version_id IS DISTINCT FROM current_version
           OR expected_digest IS NULL OR NEW.sha256<>expected_digest
           OR NEW.declared_bytes<>expected_bytes
           OR NEW.declared_media_type<>expected_media THEN
            RAISE EXCEPTION 'document version does not match quarantine evidence or predecessor' USING ERRCODE='23514';
        END IF;
    ELSIF configured_operation<>'document.scan.bind'
       OR ROW(NEW.document_id,NEW.version_number,NEW.prior_version_id,
              NEW.original_file_name,NEW.declared_media_type,NEW.declared_bytes,
              NEW.sha256,NEW.quarantined_at,NEW.replacement_reason)
          IS DISTINCT FROM
          ROW(OLD.document_id,OLD.version_number,OLD.prior_version_id,
              OLD.original_file_name,OLD.declared_media_type,OLD.declared_bytes,
              OLD.sha256,OLD.quarantined_at,OLD.replacement_reason)
       OR OLD.status NOT IN ('quarantined','scan_failed')
       OR NEW.status NOT IN ('clean','infected','scan_failed')
       OR NEW.platform_scan_attestation_id IS NULL OR NEW.scanned_at IS NULL
       OR NOT EXISTS (SELECT 1 FROM document_scan_attempts attempt
            WHERE attempt.organization_id=NEW.organization_id
              AND attempt.document_version_id=NEW.id
              AND attempt.platform_scan_attestation_id=NEW.platform_scan_attestation_id
              AND attempt.verdict=CASE NEW.status
                    WHEN 'clean' THEN 'clean' WHEN 'infected' THEN 'infected' ELSE 'error' END)
       OR (NEW.status='clean' AND NOT EXISTS (
            SELECT 1 FROM document_promotion_evidence promotion
             WHERE promotion.organization_id=NEW.organization_id
               AND promotion.document_id=NEW.document_id
               AND promotion.object_version_id=NEW.id
               AND promotion.scan_attestation_id=NEW.platform_scan_attestation_id))
       OR (NEW.status<>'clean' AND NEW.promoted_at IS NOT NULL) THEN
        RAISE EXCEPTION 'invalid Module 7 document scan transition' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER document_versions_lifecycle_guard
    BEFORE INSERT OR UPDATE ON document_versions
    FOR EACH ROW EXECUTE FUNCTION careos_guard_m7_document_version();

CREATE FUNCTION careos_guard_m7_document_link()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF current_user<>'${applicationRole}' THEN RETURN NEW; END IF;
    IF NOT EXISTS (SELECT 1 FROM documents document
            WHERE document.organization_id=NEW.organization_id AND document.id=NEW.document_id
              AND document.patient_id=NEW.patient_id)
       OR (NEW.encounter_id IS NOT NULL AND NOT EXISTS (
            SELECT 1 FROM encounters encounter
             WHERE encounter.organization_id=NEW.organization_id
               AND encounter.id=NEW.encounter_id AND encounter.patient_id=NEW.patient_id))
       OR (NEW.assessment_session_id IS NOT NULL AND NOT EXISTS (
            SELECT 1 FROM assessment_sessions assessment
             WHERE assessment.organization_id=NEW.organization_id
               AND assessment.id=NEW.assessment_session_id
               AND assessment.patient_id=NEW.patient_id
               AND (NEW.encounter_id IS NULL OR assessment.encounter_id=NEW.encounter_id))) THEN
        RAISE EXCEPTION 'invalid Module 7 patient/context document link' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER document_links_context_guard
    BEFORE INSERT ON document_links
    FOR EACH ROW EXECUTE FUNCTION careos_guard_m7_document_link();

CREATE FUNCTION careos_guard_m7_classification()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE expected_version integer;
BEGIN
    IF current_user<>'${applicationRole}' THEN RETURN NEW; END IF;
    SELECT coalesce(max(classification.classification_version),0)+1
      INTO expected_version FROM document_classifications classification
     WHERE classification.organization_id=NEW.organization_id
       AND classification.document_id=NEW.document_id;
    IF NEW.classification_version<>expected_version
       OR NEW.reason IS DISTINCT FROM nullif(current_setting('app.current_authorization_reason',true),'')
       OR NEW.classified_by IS DISTINCT FROM nullif(current_setting('app.current_actor_id',true),'')::uuid
       OR NOT EXISTS (SELECT 1 FROM document_versions version
            WHERE version.organization_id=NEW.organization_id
              AND version.id=NEW.document_version_id
              AND version.document_id=NEW.document_id) THEN
        RAISE EXCEPTION 'invalid Module 7 document classification evidence' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER document_classifications_evidence_guard
    BEFORE INSERT ON document_classifications
    FOR EACH ROW EXECUTE FUNCTION careos_guard_m7_classification();

CREATE FUNCTION careos_guard_m7_scan_attempt()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE expected_attempt integer;
BEGIN
    IF current_user<>'${applicationRole}' THEN RETURN NEW; END IF;
    SELECT coalesce(max(attempt.attempt_number),0)+1 INTO expected_attempt
      FROM document_scan_attempts attempt
     WHERE attempt.organization_id=NEW.organization_id
       AND attempt.document_version_id=NEW.document_version_id;
    IF NEW.attempt_number<>expected_attempt
       OR NOT EXISTS (SELECT 1 FROM document_versions version
            JOIN document_scan_attestations attestation
              ON attestation.organization_id=version.organization_id
             AND attestation.document_id=version.document_id
             AND attestation.object_version_id=version.id
             AND attestation.attestation_id=NEW.platform_scan_attestation_id
           WHERE version.organization_id=NEW.organization_id
             AND version.id=NEW.document_version_id
             AND version.document_id=NEW.document_id
             AND attestation.verdict=NEW.verdict
             AND attestation.scanner_key=NEW.scanner_key
             AND attestation.definitions_version=NEW.definitions_version
             AND attestation.sha256=NEW.observed_sha256
             AND attestation.scanned_at=NEW.scanned_at) THEN
        RAISE EXCEPTION 'invalid Module 7 scan-attempt evidence' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER document_scan_attempts_evidence_guard
    BEFORE INSERT ON document_scan_attempts
    FOR EACH ROW EXECUTE FUNCTION careos_guard_m7_scan_attempt();

CREATE FUNCTION careos_guard_m7_report()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF current_user<>'${applicationRole}' THEN RETURN NEW; END IF;
    IF NEW.report_digest<>careos_m7_sha256(NEW.summary_text::text)
       OR NOT EXISTS (SELECT 1 FROM documents document
            JOIN document_versions version
              ON version.organization_id=document.organization_id
             AND version.document_id=document.id
             AND version.id=NEW.document_version_id
             AND version.status='clean'
            JOIN document_promotion_evidence promotion
              ON promotion.organization_id=version.organization_id
             AND promotion.document_id=version.document_id
             AND promotion.object_version_id=version.id
           WHERE document.organization_id=NEW.organization_id
             AND document.id=NEW.document_id AND document.patient_id=NEW.patient_id)
       OR (NEW.encounter_id IS NOT NULL AND NOT EXISTS (
            SELECT 1 FROM encounters encounter WHERE encounter.organization_id=NEW.organization_id
              AND encounter.id=NEW.encounter_id AND encounter.patient_id=NEW.patient_id))
       OR (NEW.prior_report_id IS NOT NULL AND NOT EXISTS (
            SELECT 1 FROM diagnostic_reports prior WHERE prior.organization_id=NEW.organization_id
              AND prior.id=NEW.prior_report_id AND prior.document_id=NEW.document_id
              AND prior.patient_id=NEW.patient_id)) THEN
        RAISE EXCEPTION 'invalid Module 7 diagnostic report provenance' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER diagnostic_reports_provenance_guard
    BEFORE INSERT ON diagnostic_reports
    FOR EACH ROW EXECUTE FUNCTION careos_guard_m7_report();

CREATE FUNCTION careos_guard_m7_result_value()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE canonical text;
BEGIN
    IF current_user<>'${applicationRole}' THEN RETURN NEW; END IF;
    IF TG_TABLE_NAME='lab_results' THEN
        canonical:=concat_ws('|',NEW.test_code,NEW.test_display,NEW.value_text,
            coalesce(NEW.unit_text,''),coalesce(NEW.reference_range_text,''),
            NEW.abnormal_flag,NEW.source_key,NEW.method_key);
    ELSE
        canonical:=concat_ws('|',NEW.modality_key,NEW.body_site_text,NEW.finding_text,
            NEW.impression_text,NEW.abnormal_flag,NEW.source_key,NEW.method_key);
    END IF;
    IF NEW.result_digest<>careos_m7_sha256(canonical)
       OR NOT EXISTS (SELECT 1 FROM diagnostic_reports report
            WHERE report.organization_id=NEW.organization_id
              AND report.id=NEW.diagnostic_report_id
              AND report.status<>'entered_in_error') THEN
        RAISE EXCEPTION 'invalid Module 7 result value or digest' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER lab_results_provenance_guard
    BEFORE INSERT ON lab_results FOR EACH ROW EXECUTE FUNCTION careos_guard_m7_result_value();
CREATE TRIGGER imaging_results_provenance_guard
    BEFORE INSERT ON imaging_results FOR EACH ROW EXECUTE FUNCTION careos_guard_m7_result_value();

CREATE FUNCTION careos_guard_m7_result_flag()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE configured_operation text:=nullif(current_setting('app.current_operation_key',true),'');
BEGIN
    IF current_user<>'${applicationRole}' THEN RETURN NEW; END IF;
    IF TG_OP='INSERT' THEN
        IF configured_operation<>'document.result.write' OR NEW.status<>'open'
           OR NEW.acknowledged_at IS NOT NULL OR NEW.resolved_at IS NOT NULL
           OR NEW.summary_digest<>careos_m7_sha256(NEW.summary_text::text)
           OR NEW.acknowledgement_due_at>NEW.detected_at+interval '7 days'
           OR NOT (
                (NEW.lab_result_id IS NOT NULL AND EXISTS (
                    SELECT 1 FROM lab_results result WHERE result.organization_id=NEW.organization_id
                      AND result.id=NEW.lab_result_id AND result.diagnostic_report_id=NEW.diagnostic_report_id
                      AND result.abnormal_flag<>'normal'
                      AND (NEW.flag_kind<>'critical' OR result.abnormal_flag='critical'))) OR
                (NEW.imaging_result_id IS NOT NULL AND EXISTS (
                    SELECT 1 FROM imaging_results result WHERE result.organization_id=NEW.organization_id
                      AND result.id=NEW.imaging_result_id AND result.diagnostic_report_id=NEW.diagnostic_report_id
                      AND result.abnormal_flag<>'normal'
                      AND (NEW.flag_kind<>'critical' OR result.abnormal_flag='critical')))
           ) THEN
            RAISE EXCEPTION 'invalid Module 7 result flag evidence' USING ERRCODE='23514';
        END IF;
    ELSIF configured_operation<>'document.result.review'
       OR ROW(NEW.diagnostic_report_id,NEW.lab_result_id,NEW.imaging_result_id,
              NEW.flag_kind,NEW.summary_text,NEW.summary_digest,NEW.owner_practitioner_id,
              NEW.sla_policy_version,NEW.detected_at,NEW.acknowledgement_due_at)
          IS DISTINCT FROM
          ROW(OLD.diagnostic_report_id,OLD.lab_result_id,OLD.imaging_result_id,
              OLD.flag_kind,OLD.summary_text,OLD.summary_digest,OLD.owner_practitioner_id,
              OLD.sla_policy_version,OLD.detected_at,OLD.acknowledgement_due_at)
       OR NOT ((OLD.status='open' AND NEW.status='acknowledged' AND NEW.acknowledged_at IS NOT NULL
                AND NEW.resolved_at IS NULL) OR
               (OLD.status='acknowledged' AND NEW.status='resolved'
                AND NEW.acknowledged_at=OLD.acknowledged_at AND NEW.resolved_at IS NOT NULL))
       OR NOT EXISTS (SELECT 1 FROM result_reviews review
            WHERE review.organization_id=NEW.organization_id
              AND review.result_flag_id=NEW.id
              AND review.flag_revision=NEW.lock_version
              AND review.review_type=CASE NEW.status WHEN 'acknowledged' THEN 'acknowledge' ELSE 'resolve' END) THEN
        RAISE EXCEPTION 'invalid Module 7 result flag transition' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER result_flags_lifecycle_guard
    BEFORE INSERT OR UPDATE ON result_flags
    FOR EACH ROW EXECUTE FUNCTION careos_guard_m7_result_flag();

CREATE FUNCTION careos_guard_m7_result_review()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE actor uuid:=nullif(current_setting('app.current_actor_id',true),'')::uuid;
DECLARE expected_revision bigint;
DECLARE current_status text;
BEGIN
    IF current_user<>'${applicationRole}' THEN RETURN NEW; END IF;
    SELECT flag.lock_version+1,flag.status INTO expected_revision,current_status
      FROM result_flags flag WHERE flag.organization_id=NEW.organization_id
       AND flag.id=NEW.result_flag_id AND flag.diagnostic_report_id=NEW.diagnostic_report_id FOR UPDATE;
    IF expected_revision IS NULL OR NEW.flag_revision<>expected_revision
       OR (NEW.review_type='acknowledge' AND current_status<>'open')
       OR (NEW.review_type='resolve' AND current_status<>'acknowledged')
       OR NEW.reason IS DISTINCT FROM nullif(current_setting('app.current_authorization_reason',true),'')
       OR NOT careos_m5_actor_is_practitioner(
            NEW.organization_id,actor,NEW.reviewer_practitioner_id,NEW.reviewed_at) THEN
        RAISE EXCEPTION 'invalid Module 7 result review evidence' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER result_reviews_evidence_guard
    BEFORE INSERT ON result_reviews
    FOR EACH ROW EXECUTE FUNCTION careos_guard_m7_result_review();

CREATE FUNCTION careos_guard_m7_result_escalation()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE actor uuid:=nullif(current_setting('app.current_actor_id',true),'')::uuid;
BEGIN
    IF current_user<>'${applicationRole}' THEN RETURN NEW; END IF;
    IF NOT EXISTS (SELECT 1 FROM result_flags flag
            WHERE flag.organization_id=NEW.organization_id AND flag.id=NEW.result_flag_id
              AND flag.diagnostic_report_id=NEW.diagnostic_report_id AND flag.status<>'resolved')
       OR NOT careos_m5_actor_is_practitioner(
            NEW.organization_id,actor,NEW.owner_practitioner_id,NEW.recorded_at) THEN
        RAISE EXCEPTION 'invalid Module 7 result escalation evidence' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER result_escalations_evidence_guard
    BEFORE INSERT ON result_escalations
    FOR EACH ROW EXECUTE FUNCTION careos_guard_m7_result_escalation();

CREATE FUNCTION careos_guard_m7_access_intent()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE configured_operation text:=nullif(current_setting('app.current_operation_key',true),'');
BEGIN
    IF current_user<>'${applicationRole}' THEN RETURN NEW; END IF;
    IF NOT EXISTS (SELECT 1 FROM documents document
            JOIN document_versions version ON version.organization_id=document.organization_id
             AND version.document_id=document.id AND version.id=NEW.document_version_id
             AND version.status='clean'
           WHERE document.organization_id=NEW.organization_id
             AND document.id=NEW.document_id AND document.patient_id=NEW.patient_id)
       OR (configured_operation='document.access' AND (
            NEW.intent_type<>'view' OR NEW.status<>'granted'
            OR NOT EXISTS (SELECT 1 FROM document_access_grant_evidence grant_evidence
                 WHERE grant_evidence.organization_id=NEW.organization_id
                   AND grant_evidence.access_grant_id=NEW.platform_access_grant_id
                   AND grant_evidence.document_id=NEW.document_id
                   AND grant_evidence.object_version_id=NEW.document_version_id
                   AND grant_evidence.purpose=NEW.purpose_key
                   AND grant_evidence.expires_at=NEW.expires_at)))
       OR (configured_operation='document.intent.create' AND (
            NEW.intent_type NOT IN ('export','share') OR NEW.status<>'recorded'
            OR NEW.platform_access_grant_id IS NOT NULL OR NEW.expires_at IS NOT NULL)) THEN
        RAISE EXCEPTION 'invalid Module 7 document access intent' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER document_access_intents_evidence_guard
    BEFORE INSERT ON document_access_intents
    FOR EACH ROW EXECUTE FUNCTION careos_guard_m7_access_intent();

COMMENT ON FUNCTION careos_m7_sha256(text) IS
    'Dependency-free Module 7 SHA-256 helper backed by PostgreSQL core sha256(bytea).';
