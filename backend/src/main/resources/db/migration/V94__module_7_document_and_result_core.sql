CREATE TABLE documents (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    patient_id uuid NOT NULL,
    title varchar(240) NOT NULL,
    document_type_key varchar(80) NOT NULL,
    source_key varchar(120) NOT NULL,
    current_version_id uuid,
    current_version_number integer NOT NULL DEFAULT 0,
    status varchar(24) NOT NULL DEFAULT 'active',
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    UNIQUE (organization_id,id),
    FOREIGN KEY (organization_id,patient_id) REFERENCES patient_profiles(organization_id,id),
    CHECK (char_length(btrim(title)) BETWEEN 2 AND 240),
    CHECK (document_type_key~'^[a-z][a-z0-9_]{1,79}$'),
    CHECK (source_key~'^[a-z][a-z0-9_.:-]{1,119}$'),
    CHECK ((current_version_id IS NULL)=(current_version_number=0)),
    CHECK (current_version_number>=0),
    CHECK (status IN ('active','entered_in_error')),
    CHECK (lock_version>=0)
);

CREATE INDEX documents_patient_updated_idx
    ON documents(organization_id,patient_id,updated_at DESC,id);

CREATE TABLE document_versions (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    document_id uuid NOT NULL,
    version_number integer NOT NULL,
    prior_version_id uuid,
    original_file_name varchar(240) NOT NULL,
    declared_media_type varchar(255) NOT NULL,
    declared_bytes bigint NOT NULL,
    sha256 char(64) NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'quarantined',
    platform_scan_attestation_id uuid,
    quarantined_at timestamptz NOT NULL,
    scanned_at timestamptz,
    promoted_at timestamptz,
    replacement_reason varchar(500),
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    UNIQUE (organization_id,id),
    UNIQUE (organization_id,document_id,version_number),
    FOREIGN KEY (organization_id,document_id) REFERENCES documents(organization_id,id),
    FOREIGN KEY (organization_id,prior_version_id) REFERENCES document_versions(organization_id,id),
    FOREIGN KEY (organization_id,document_id,id)
        REFERENCES document_quarantine_evidence(organization_id,document_id,object_version_id),
    CHECK (version_number>0),
    CHECK ((version_number=1)=(prior_version_id IS NULL)),
    CHECK (char_length(btrim(original_file_name)) BETWEEN 1 AND 240),
    CHECK (declared_media_type~'^[^[:cntrl:][:space:]/]+/[^[:cntrl:][:space:]/]+$'),
    CHECK (declared_bytes BETWEEN 1 AND 20971520),
    CHECK (sha256~'^[0-9a-f]{64}$'),
    CHECK (status IN ('quarantined','scanning','clean','infected','scan_failed')),
    CHECK ((platform_scan_attestation_id IS NULL)=(scanned_at IS NULL)),
    CHECK ((status='clean')=(promoted_at IS NOT NULL)),
    CHECK (status NOT IN ('clean','infected') OR platform_scan_attestation_id IS NOT NULL),
    CHECK (replacement_reason IS NULL OR char_length(btrim(replacement_reason)) BETWEEN 10 AND 500),
    CHECK (lock_version>=0)
);

ALTER TABLE document_versions
    ADD CONSTRAINT document_versions_scan_attestation_fk
    FOREIGN KEY (organization_id,platform_scan_attestation_id,document_id,id)
    REFERENCES document_scan_attestations(
        organization_id,attestation_id,document_id,object_version_id);

ALTER TABLE documents
    ADD CONSTRAINT documents_current_version_fk
    FOREIGN KEY (organization_id,current_version_id)
    REFERENCES document_versions(organization_id,id);

CREATE INDEX document_versions_status_idx
    ON document_versions(organization_id,status,updated_at,id);

CREATE TABLE document_links (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    document_id uuid NOT NULL,
    patient_id uuid NOT NULL,
    encounter_id uuid,
    assessment_session_id uuid,
    link_type varchar(32) NOT NULL,
    linked_at timestamptz NOT NULL,
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    UNIQUE (organization_id,id),
    UNIQUE (organization_id,document_id,patient_id,encounter_id,assessment_session_id,link_type),
    FOREIGN KEY (organization_id,document_id) REFERENCES documents(organization_id,id),
    FOREIGN KEY (organization_id,patient_id) REFERENCES patient_profiles(organization_id,id),
    FOREIGN KEY (organization_id,encounter_id) REFERENCES encounters(organization_id,id),
    FOREIGN KEY (organization_id,assessment_session_id) REFERENCES assessment_sessions(organization_id,id),
    CHECK (link_type IN ('patient_record','encounter','assessment','diagnostic_result')),
    CHECK (lock_version=0)
);

CREATE TABLE document_classifications (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    document_id uuid NOT NULL,
    document_version_id uuid NOT NULL,
    classification_version integer NOT NULL,
    category_key varchar(80) NOT NULL,
    confidentiality_key varchar(32) NOT NULL,
    retention_class_key varchar(80) NOT NULL,
    source_key varchar(120) NOT NULL,
    method_key varchar(80) NOT NULL,
    classified_by uuid NOT NULL,
    classified_at timestamptz NOT NULL,
    reason varchar(500) NOT NULL,
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    UNIQUE (organization_id,id),
    UNIQUE (organization_id,document_id,classification_version),
    FOREIGN KEY (organization_id,document_id) REFERENCES documents(organization_id,id),
    FOREIGN KEY (organization_id,document_version_id) REFERENCES document_versions(organization_id,id),
    CHECK (classification_version>0),
    CHECK (category_key~'^[a-z][a-z0-9_]{1,79}$'),
    CHECK (confidentiality_key IN ('normal','restricted','very_restricted')),
    CHECK (retention_class_key~'^[a-z][a-z0-9_.:-]{1,79}$'),
    CHECK (source_key~'^[a-z][a-z0-9_.:-]{1,119}$'),
    CHECK (method_key~'^[a-z][a-z0-9_.:-]{1,79}$'),
    CHECK (char_length(btrim(reason)) BETWEEN 10 AND 500),
    CHECK (lock_version=0)
);

CREATE TABLE document_scan_attempts (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    document_id uuid NOT NULL,
    document_version_id uuid NOT NULL,
    attempt_number integer NOT NULL,
    platform_scan_attestation_id uuid NOT NULL,
    verdict varchar(16) NOT NULL,
    scanner_key varchar(120) NOT NULL,
    definitions_version varchar(120) NOT NULL,
    observed_sha256 char(64) NOT NULL,
    scanned_at timestamptz NOT NULL,
    recorded_at timestamptz NOT NULL,
    failure_code varchar(80),
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    UNIQUE (organization_id,id),
    UNIQUE (organization_id,document_version_id,attempt_number),
    UNIQUE (organization_id,platform_scan_attestation_id),
    FOREIGN KEY (organization_id,document_id) REFERENCES documents(organization_id,id),
    FOREIGN KEY (organization_id,document_version_id) REFERENCES document_versions(organization_id,id),
    FOREIGN KEY (organization_id,platform_scan_attestation_id,document_id,document_version_id)
        REFERENCES document_scan_attestations(
            organization_id,attestation_id,document_id,object_version_id),
    CHECK (attempt_number>0),
    CHECK (verdict IN ('clean','infected','error')),
    CHECK (scanner_key~'^[a-z][a-z0-9]*([.:-][a-z0-9]+)*$'),
    CHECK (definitions_version~'^[A-Za-z0-9][A-Za-z0-9._:-]*$'),
    CHECK (observed_sha256~'^[0-9a-f]{64}$'),
    CHECK ((verdict='error')=(failure_code IS NOT NULL)),
    CHECK (failure_code IS NULL OR failure_code~'^[a-z][a-z0-9_]{1,79}$'),
    CHECK (lock_version=0)
);

CREATE TABLE diagnostic_reports (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    document_id uuid NOT NULL,
    document_version_id uuid NOT NULL,
    patient_id uuid NOT NULL,
    encounter_id uuid,
    prior_report_id uuid,
    report_type varchar(24) NOT NULL,
    status varchar(24) NOT NULL,
    source_key varchar(120) NOT NULL,
    source_identifier varchar(240) NOT NULL,
    issued_at timestamptz NOT NULL,
    received_at timestamptz NOT NULL,
    summary_text varchar(8000) NOT NULL,
    report_digest char(64) NOT NULL,
    author_practitioner_id uuid,
    interpretation_status varchar(32) NOT NULL DEFAULT 'uninterpreted',
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    UNIQUE (organization_id,id),
    FOREIGN KEY (organization_id,document_id) REFERENCES documents(organization_id,id),
    FOREIGN KEY (organization_id,document_version_id) REFERENCES document_versions(organization_id,id),
    FOREIGN KEY (organization_id,patient_id) REFERENCES patient_profiles(organization_id,id),
    FOREIGN KEY (organization_id,encounter_id) REFERENCES encounters(organization_id,id),
    FOREIGN KEY (organization_id,prior_report_id) REFERENCES diagnostic_reports(organization_id,id),
    FOREIGN KEY (organization_id,author_practitioner_id) REFERENCES practitioner_profiles(organization_id,id),
    CHECK (report_type IN ('laboratory','imaging','other')),
    CHECK (status IN ('preliminary','final','amended','entered_in_error')),
    CHECK ((status='amended')=(prior_report_id IS NOT NULL)),
    CHECK (source_key~'^[a-z][a-z0-9_.:-]{1,119}$'),
    CHECK (char_length(btrim(source_identifier)) BETWEEN 2 AND 240),
    CHECK (char_length(btrim(summary_text)) BETWEEN 2 AND 8000),
    CHECK (report_digest~'^[0-9a-f]{64}$'),
    CHECK (interpretation_status IN ('uninterpreted','provisional','reviewed','not_applicable')),
    CHECK (lock_version=0)
);

CREATE INDEX diagnostic_reports_inbox_idx
    ON diagnostic_reports(organization_id,patient_id,received_at DESC,id);

CREATE TABLE lab_results (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    diagnostic_report_id uuid NOT NULL,
    test_code varchar(120) NOT NULL,
    test_display varchar(240) NOT NULL,
    value_text varchar(1000) NOT NULL,
    unit_text varchar(120),
    reference_range_text varchar(1000),
    abnormal_flag varchar(24) NOT NULL,
    source_key varchar(120) NOT NULL,
    method_key varchar(120) NOT NULL,
    observed_at timestamptz NOT NULL,
    result_digest char(64) NOT NULL,
    reviewer_practitioner_id uuid,
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    UNIQUE (organization_id,id),
    FOREIGN KEY (organization_id,diagnostic_report_id) REFERENCES diagnostic_reports(organization_id,id),
    FOREIGN KEY (organization_id,reviewer_practitioner_id) REFERENCES practitioner_profiles(organization_id,id),
    CHECK (char_length(btrim(test_code)) BETWEEN 1 AND 120),
    CHECK (char_length(btrim(test_display)) BETWEEN 2 AND 240),
    CHECK (char_length(btrim(value_text)) BETWEEN 1 AND 1000),
    CHECK (abnormal_flag IN ('normal','high','low','abnormal','critical','unknown')),
    CHECK (source_key~'^[a-z][a-z0-9_.:-]{1,119}$'),
    CHECK (method_key~'^[a-z][a-z0-9_.:-]{1,119}$'),
    CHECK (result_digest~'^[0-9a-f]{64}$'),
    CHECK (lock_version=0)
);

CREATE TABLE imaging_results (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    diagnostic_report_id uuid NOT NULL,
    modality_key varchar(80) NOT NULL,
    body_site_text varchar(240) NOT NULL,
    finding_text varchar(8000) NOT NULL,
    impression_text varchar(4000) NOT NULL,
    abnormal_flag varchar(24) NOT NULL,
    source_key varchar(120) NOT NULL,
    method_key varchar(120) NOT NULL,
    observed_at timestamptz NOT NULL,
    result_digest char(64) NOT NULL,
    reviewer_practitioner_id uuid,
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    UNIQUE (organization_id,id),
    FOREIGN KEY (organization_id,diagnostic_report_id) REFERENCES diagnostic_reports(organization_id,id),
    FOREIGN KEY (organization_id,reviewer_practitioner_id) REFERENCES practitioner_profiles(organization_id,id),
    CHECK (modality_key~'^[a-z][a-z0-9_]{1,79}$'),
    CHECK (char_length(btrim(body_site_text)) BETWEEN 2 AND 240),
    CHECK (char_length(btrim(finding_text)) BETWEEN 2 AND 8000),
    CHECK (char_length(btrim(impression_text)) BETWEEN 2 AND 4000),
    CHECK (abnormal_flag IN ('normal','abnormal','critical','unknown')),
    CHECK (source_key~'^[a-z][a-z0-9_.:-]{1,119}$'),
    CHECK (method_key~'^[a-z][a-z0-9_.:-]{1,119}$'),
    CHECK (result_digest~'^[0-9a-f]{64}$'),
    CHECK (lock_version=0)
);

CREATE TABLE result_flags (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    diagnostic_report_id uuid NOT NULL,
    lab_result_id uuid,
    imaging_result_id uuid,
    flag_kind varchar(24) NOT NULL,
    summary_text varchar(2000) NOT NULL,
    summary_digest char(64) NOT NULL,
    owner_practitioner_id uuid NOT NULL,
    sla_policy_version varchar(120) NOT NULL,
    detected_at timestamptz NOT NULL,
    acknowledgement_due_at timestamptz NOT NULL,
    acknowledged_at timestamptz,
    resolved_at timestamptz,
    status varchar(24) NOT NULL DEFAULT 'open',
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    UNIQUE (organization_id,id),
    FOREIGN KEY (organization_id,diagnostic_report_id) REFERENCES diagnostic_reports(organization_id,id),
    FOREIGN KEY (organization_id,lab_result_id) REFERENCES lab_results(organization_id,id),
    FOREIGN KEY (organization_id,imaging_result_id) REFERENCES imaging_results(organization_id,id),
    FOREIGN KEY (organization_id,owner_practitioner_id) REFERENCES practitioner_profiles(organization_id,id),
    CHECK ((lab_result_id IS NOT NULL)::integer+(imaging_result_id IS NOT NULL)::integer=1),
    CHECK (flag_kind IN ('abnormal','critical')),
    CHECK (char_length(btrim(summary_text)) BETWEEN 2 AND 2000),
    CHECK (summary_digest~'^[0-9a-f]{64}$'),
    CHECK (sla_policy_version~'^[A-Za-z0-9][A-Za-z0-9_.:-]{1,119}$'),
    CHECK (acknowledgement_due_at>detected_at),
    CHECK (status IN ('open','acknowledged','resolved')),
    CHECK ((status='open')=(acknowledged_at IS NULL)),
    CHECK ((status='resolved')=(resolved_at IS NOT NULL)),
    CHECK (lock_version>=0)
);

CREATE INDEX result_flags_open_idx
    ON result_flags(organization_id,status,acknowledgement_due_at,id)
    WHERE status<>'resolved';

CREATE TABLE result_reviews (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    result_flag_id uuid NOT NULL,
    diagnostic_report_id uuid NOT NULL,
    review_type varchar(24) NOT NULL,
    flag_revision bigint NOT NULL,
    reviewer_practitioner_id uuid NOT NULL,
    reason varchar(1000) NOT NULL,
    reviewed_at timestamptz NOT NULL,
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    UNIQUE (organization_id,id),
    UNIQUE (organization_id,result_flag_id,flag_revision),
    FOREIGN KEY (organization_id,result_flag_id) REFERENCES result_flags(organization_id,id),
    FOREIGN KEY (organization_id,diagnostic_report_id) REFERENCES diagnostic_reports(organization_id,id),
    FOREIGN KEY (organization_id,reviewer_practitioner_id) REFERENCES practitioner_profiles(organization_id,id),
    CHECK (review_type IN ('acknowledge','resolve')),
    CHECK (flag_revision>0),
    CHECK (char_length(btrim(reason)) BETWEEN 10 AND 1000),
    CHECK (lock_version=0)
);

CREATE TABLE result_escalations (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    result_flag_id uuid NOT NULL,
    diagnostic_report_id uuid NOT NULL,
    escalation_level varchar(24) NOT NULL,
    owner_practitioner_id uuid NOT NULL,
    channel_key varchar(80) NOT NULL,
    reason varchar(1000) NOT NULL,
    due_at timestamptz NOT NULL,
    recorded_at timestamptz NOT NULL,
    delivery_status varchar(24) NOT NULL DEFAULT 'not_dispatched',
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    UNIQUE (organization_id,id),
    FOREIGN KEY (organization_id,result_flag_id) REFERENCES result_flags(organization_id,id),
    FOREIGN KEY (organization_id,diagnostic_report_id) REFERENCES diagnostic_reports(organization_id,id),
    FOREIGN KEY (organization_id,owner_practitioner_id) REFERENCES practitioner_profiles(organization_id,id),
    CHECK (escalation_level IN ('clinical_owner','department_lead','emergency_pathway')),
    CHECK (channel_key~'^[a-z][a-z0-9_.:-]{1,79}$'),
    CHECK (char_length(btrim(reason)) BETWEEN 10 AND 1000),
    CHECK (due_at>=recorded_at),
    CHECK (delivery_status IN ('not_dispatched','pending_provider','dispatched','failed')),
    CHECK (lock_version=0)
);

CREATE TABLE document_access_intents (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    document_id uuid NOT NULL,
    document_version_id uuid NOT NULL,
    patient_id uuid NOT NULL,
    intent_type varchar(24) NOT NULL,
    purpose_key varchar(80) NOT NULL,
    requested_format varchar(80),
    recipient_type varchar(32),
    recipient_reference varchar(240),
    platform_access_grant_id uuid,
    status varchar(24) NOT NULL DEFAULT 'recorded',
    reason varchar(500) NOT NULL,
    requested_at timestamptz NOT NULL,
    expires_at timestamptz,
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    UNIQUE (organization_id,id),
    FOREIGN KEY (organization_id,document_id) REFERENCES documents(organization_id,id),
    FOREIGN KEY (organization_id,document_version_id) REFERENCES document_versions(organization_id,id),
    FOREIGN KEY (organization_id,patient_id) REFERENCES patient_profiles(organization_id,id),
    FOREIGN KEY (organization_id,platform_access_grant_id)
        REFERENCES document_access_grant_evidence(organization_id,access_grant_id),
    CHECK (intent_type IN ('view','export','share')),
    CHECK (purpose_key~'^[a-z][a-z0-9_]{1,79}$'),
    CHECK ((recipient_type IS NULL)=(recipient_reference IS NULL)),
    CHECK (recipient_type IS NULL OR recipient_type IN ('patient','practitioner','organization','external_party')),
    CHECK (status IN ('recorded','granted','dependency_unavailable','cancelled')),
    CHECK ((status='granted')=(platform_access_grant_id IS NOT NULL)),
    CHECK ((status='granted')=(expires_at IS NOT NULL)),
    CHECK (char_length(btrim(reason)) BETWEEN 10 AND 500),
    CHECK (lock_version=0)
);

DO $$
DECLARE table_name text;
BEGIN
    FOREACH table_name IN ARRAY ARRAY[
        'documents','document_versions','document_links','document_classifications',
        'document_scan_attempts','diagnostic_reports','lab_results','imaging_results',
        'result_flags','result_reviews','result_escalations','document_access_intents'
    ] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',table_name);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',table_name);
        EXECUTE format(
            'CREATE POLICY %I ON %I USING (organization_id=nullif(current_setting(''app.current_organization_id'',true),'''')::uuid) WITH CHECK (organization_id=nullif(current_setting(''app.current_organization_id'',true),'''')::uuid)',
            table_name||'_tenant_policy',table_name);
    END LOOP;
END $$;

CREATE FUNCTION careos_validate_m7_tenant_write()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    configured_organization uuid:=nullif(current_setting('app.current_organization_id',true),'')::uuid;
    configured_actor uuid:=nullif(current_setting('app.current_actor_id',true),'')::uuid;
    configured_operation text:=nullif(current_setting('app.current_operation_key',true),'');
    allowed_operations text[];
BEGIN
    IF current_user<>'${applicationRole}' THEN RETURN NEW; END IF;
    allowed_operations:=CASE TG_TABLE_NAME
        WHEN 'documents' THEN ARRAY['document.upload','document.classify']
        WHEN 'document_versions' THEN ARRAY['document.upload','document.scan.bind']
        WHEN 'document_links' THEN ARRAY['document.upload']
        WHEN 'document_classifications' THEN ARRAY['document.classify']
        WHEN 'document_scan_attempts' THEN ARRAY['document.scan.bind']
        WHEN 'diagnostic_reports' THEN ARRAY['document.result.write']
        WHEN 'lab_results' THEN ARRAY['document.result.write']
        WHEN 'imaging_results' THEN ARRAY['document.result.write']
        WHEN 'result_flags' THEN ARRAY['document.result.write','document.result.review']
        WHEN 'result_reviews' THEN ARRAY['document.result.review']
        WHEN 'result_escalations' THEN ARRAY['document.result.escalate']
        WHEN 'document_access_intents' THEN ARRAY['document.access','document.intent.create']
        ELSE ARRAY[]::text[]
    END;
    IF NEW.organization_id IS DISTINCT FROM configured_organization
       OR configured_actor IS NULL OR configured_operation IS NULL
       OR NOT configured_operation=ANY(allowed_operations)
       OR NOT EXISTS (
            SELECT 1 FROM authorization_operations operation
            JOIN authorization_registry_releases release
              ON release.registry_version=operation.registry_version AND release.status='active'
            WHERE operation.operation_key=configured_operation
              AND operation.registry_version='m7-standing-direction-v1'
              AND operation.status='active') THEN
        RAISE EXCEPTION 'invalid Module 7 operation-bound tenant write context' USING ERRCODE='42501';
    END IF;
    IF TG_OP='INSERT' THEN
        IF NEW.created_by IS DISTINCT FROM configured_actor
           OR NEW.updated_by IS DISTINCT FROM configured_actor
           OR NEW.lock_version<>0 THEN
            RAISE EXCEPTION 'invalid Module 7 creation evidence' USING ERRCODE='23514';
        END IF;
    ELSE
        IF NEW.id IS DISTINCT FROM OLD.id
           OR NEW.organization_id IS DISTINCT FROM OLD.organization_id
           OR NEW.created_at IS DISTINCT FROM OLD.created_at
           OR NEW.created_by IS DISTINCT FROM OLD.created_by
           OR NEW.updated_by IS DISTINCT FROM configured_actor
           OR NEW.lock_version<>OLD.lock_version+1
           OR NEW.updated_at<OLD.updated_at
           OR NEW.updated_at>clock_timestamp()+interval '5 seconds' THEN
            RAISE EXCEPTION 'invalid Module 7 revision evidence' USING ERRCODE='23514';
        END IF;
    END IF;
    RETURN NEW;
END $$;

DO $$
DECLARE table_name text;
BEGIN
    FOREACH table_name IN ARRAY ARRAY[
        'documents','document_versions','document_links','document_classifications',
        'document_scan_attempts','diagnostic_reports','lab_results','imaging_results',
        'result_flags','result_reviews','result_escalations','document_access_intents'
    ] LOOP
        EXECUTE format(
            'CREATE TRIGGER %I BEFORE INSERT OR UPDATE ON %I FOR EACH ROW EXECUTE FUNCTION careos_validate_m7_tenant_write()',
            table_name||'_write_guard',table_name);
    END LOOP;
END $$;

CREATE FUNCTION careos_reject_m7_evidence_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Module 7 evidence is append-only' USING ERRCODE='42501';
END $$;

DO $$
DECLARE table_name text;
BEGIN
    FOREACH table_name IN ARRAY ARRAY[
        'document_links','document_classifications','document_scan_attempts',
        'diagnostic_reports','lab_results','imaging_results','result_reviews',
        'result_escalations','document_access_intents'
    ] LOOP
        EXECUTE format(
            'CREATE TRIGGER %I BEFORE UPDATE OR DELETE ON %I FOR EACH ROW EXECUTE FUNCTION careos_reject_m7_evidence_mutation()',
            table_name||'_immutable',table_name);
    END LOOP;
END $$;

GRANT SELECT,INSERT,UPDATE ON documents,document_versions,result_flags TO "${applicationRole}";
GRANT SELECT,INSERT ON
    document_links,document_classifications,document_scan_attempts,diagnostic_reports,
    lab_results,imaging_results,result_reviews,result_escalations,document_access_intents
TO "${applicationRole}";

COMMENT ON TABLE document_versions IS
    'Immutable uploaded bytes and provenance keyed to exact platform quarantine/scan/promotion evidence; only derived scan state may advance.';
COMMENT ON TABLE diagnostic_reports IS
    'Append-only provenance-bearing diagnostic report versions linked to exact clean promoted content.';
COMMENT ON TABLE result_flags IS
    'Visible abnormal/critical result state with snapshotted acknowledgement SLA and attributed review/escalation evidence.';
COMMENT ON TABLE document_access_intents IS
    'Purpose-bound view/export/share intent evidence. Bearer provider URLs are never persisted.';
