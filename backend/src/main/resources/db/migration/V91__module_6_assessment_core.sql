CREATE TABLE assessment_sessions (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    encounter_id uuid NOT NULL,
    patient_id uuid NOT NULL,
    responsible_practitioner_id uuid NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'in_progress',
    source_package_status varchar(24) NOT NULL DEFAULT 'unavailable',
    started_at timestamptz NOT NULL,
    submitted_for_review_at timestamptz,
    signed_at timestamptz,
    completed_at timestamptz,
    cancelled_at timestamptz,
    entered_in_error_at timestamptz,
    policy_version varchar(80) NOT NULL DEFAULT 'm6-standing-direction-v1',
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    UNIQUE (organization_id,id),
    FOREIGN KEY (organization_id,encounter_id) REFERENCES encounters(organization_id,id),
    FOREIGN KEY (organization_id,patient_id) REFERENCES patient_profiles(organization_id,id),
    FOREIGN KEY (organization_id,responsible_practitioner_id) REFERENCES practitioner_profiles(organization_id,id),
    CHECK (status IN ('in_progress','in_review','signed','amended','completed','cancelled','entered_in_error')),
    CHECK (source_package_status IN ('unavailable','imported','verified')),
    CHECK (status NOT IN ('in_review','signed','amended','completed') OR submitted_for_review_at IS NOT NULL),
    CHECK (status NOT IN ('signed','amended','completed') OR signed_at IS NOT NULL),
    CHECK ((status='completed')=(completed_at IS NOT NULL)),
    CHECK ((status='cancelled')=(cancelled_at IS NOT NULL)),
    CHECK ((status='entered_in_error')=(entered_in_error_at IS NOT NULL)),
    CHECK (lock_version>=0)
);

CREATE UNIQUE INDEX assessment_sessions_one_per_encounter_uq
    ON assessment_sessions(organization_id,encounter_id);
CREATE INDEX assessment_sessions_patient_status_idx
    ON assessment_sessions(organization_id,patient_id,status,updated_at DESC,id);

CREATE TABLE assessment_sections (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    assessment_session_id uuid NOT NULL,
    screen_id varchar(6) NOT NULL,
    sequence_number smallint NOT NULL,
    title varchar(160) NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'not_started',
    response_count integer NOT NULL DEFAULT 0,
    source_package_verified boolean NOT NULL DEFAULT false,
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    UNIQUE (organization_id,id),
    UNIQUE (organization_id,assessment_session_id,screen_id),
    UNIQUE (organization_id,assessment_session_id,sequence_number),
    FOREIGN KEY (organization_id,assessment_session_id) REFERENCES assessment_sessions(organization_id,id),
    CHECK (screen_id~'^COS-(0[1-9]|1[0-9]|2[0-7])$'),
    CHECK (sequence_number BETWEEN 1 AND 27),
    CHECK (char_length(btrim(title)) BETWEEN 2 AND 160),
    CHECK (status IN ('not_started','in_progress','complete','reviewed')),
    CHECK (response_count>=0),
    CHECK (lock_version>=0)
);

CREATE TABLE assessment_responses (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    assessment_session_id uuid NOT NULL,
    assessment_section_id uuid NOT NULL,
    response_key varchar(120) NOT NULL,
    current_version_id uuid,
    current_version_number integer NOT NULL DEFAULT 0,
    status varchar(24) NOT NULL DEFAULT 'draft',
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    UNIQUE (organization_id,id),
    UNIQUE (organization_id,assessment_section_id,response_key),
    FOREIGN KEY (organization_id,assessment_session_id) REFERENCES assessment_sessions(organization_id,id),
    FOREIGN KEY (organization_id,assessment_section_id) REFERENCES assessment_sections(organization_id,id),
    CHECK (char_length(btrim(response_key)) BETWEEN 2 AND 120),
    CHECK (current_version_number>=0),
    CHECK ((current_version_id IS NULL)=(current_version_number=0)),
    CHECK (status IN ('draft','superseded','entered_in_error')),
    CHECK (lock_version>=0)
);

CREATE TABLE response_versions (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    assessment_response_id uuid NOT NULL,
    version_number integer NOT NULL,
    prior_version_id uuid,
    content_text varchar(20000) NOT NULL,
    content_digest char(64) NOT NULL,
    source_key varchar(80) NOT NULL,
    method_key varchar(80) NOT NULL,
    unit_text varchar(80),
    interpretation_status varchar(32) NOT NULL DEFAULT 'uninterpreted',
    uncertainty_text varchar(2000),
    author_practitioner_id uuid NOT NULL,
    reviewer_practitioner_id uuid,
    recorded_at timestamptz NOT NULL,
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    UNIQUE (organization_id,id),
    UNIQUE (organization_id,assessment_response_id,version_number),
    FOREIGN KEY (organization_id,assessment_response_id) REFERENCES assessment_responses(organization_id,id),
    FOREIGN KEY (organization_id,prior_version_id) REFERENCES response_versions(organization_id,id),
    FOREIGN KEY (organization_id,author_practitioner_id) REFERENCES practitioner_profiles(organization_id,id),
    FOREIGN KEY (organization_id,reviewer_practitioner_id) REFERENCES practitioner_profiles(organization_id,id),
    CHECK (version_number>0),
    CHECK ((version_number=1)=(prior_version_id IS NULL)),
    CHECK (char_length(btrim(content_text)) BETWEEN 1 AND 20000),
    CHECK (content_digest~'^[0-9a-f]{64}$'),
    CHECK (char_length(btrim(source_key)) BETWEEN 2 AND 80),
    CHECK (char_length(btrim(method_key)) BETWEEN 2 AND 80),
    CHECK (interpretation_status IN ('uninterpreted','provisional','reviewed','not_applicable')),
    CHECK (lock_version=0)
);

ALTER TABLE assessment_responses
    ADD CONSTRAINT assessment_responses_current_version_fk
    FOREIGN KEY (organization_id,current_version_id) REFERENCES response_versions(organization_id,id);

CREATE TABLE clinical_narratives (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    assessment_session_id uuid NOT NULL,
    assessment_section_id uuid NOT NULL,
    narrative_kind varchar(80) NOT NULL,
    content_text varchar(20000) NOT NULL,
    content_digest char(64) NOT NULL,
    source_key varchar(80) NOT NULL,
    method_key varchar(80) NOT NULL,
    interpretation_status varchar(32) NOT NULL DEFAULT 'uninterpreted',
    uncertainty_text varchar(2000),
    author_practitioner_id uuid NOT NULL,
    recorded_at timestamptz NOT NULL,
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    UNIQUE (organization_id,id),
    FOREIGN KEY (organization_id,assessment_session_id) REFERENCES assessment_sessions(organization_id,id),
    FOREIGN KEY (organization_id,assessment_section_id) REFERENCES assessment_sections(organization_id,id),
    FOREIGN KEY (organization_id,author_practitioner_id) REFERENCES practitioner_profiles(organization_id,id),
    CHECK (char_length(btrim(narrative_kind)) BETWEEN 2 AND 80),
    CHECK (char_length(btrim(content_text)) BETWEEN 1 AND 20000),
    CHECK (content_digest~'^[0-9a-f]{64}$'),
    CHECK (char_length(btrim(source_key)) BETWEEN 2 AND 80),
    CHECK (char_length(btrim(method_key)) BETWEEN 2 AND 80),
    CHECK (interpretation_status IN ('uninterpreted','provisional','reviewed','not_applicable')),
    CHECK (lock_version=0)
);

CREATE TABLE assessment_instruments (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    instrument_key varchar(120) NOT NULL,
    display_name varchar(240) NOT NULL,
    purpose_text varchar(1000) NOT NULL,
    provenance_reference varchar(500) NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'draft',
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    UNIQUE (organization_id,id),
    UNIQUE (organization_id,instrument_key),
    CHECK (char_length(btrim(instrument_key)) BETWEEN 2 AND 120),
    CHECK (char_length(btrim(display_name)) BETWEEN 2 AND 240),
    CHECK (char_length(btrim(purpose_text)) BETWEEN 2 AND 1000),
    CHECK (char_length(btrim(provenance_reference)) BETWEEN 2 AND 500),
    CHECK (status IN ('draft','active','retired')),
    CHECK (lock_version>=0)
);

CREATE TABLE instrument_versions (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    assessment_instrument_id uuid NOT NULL,
    version_number integer NOT NULL,
    definition_json jsonb NOT NULL,
    definition_digest char(64) NOT NULL,
    effective_from timestamptz NOT NULL,
    effective_to timestamptz,
    status varchar(24) NOT NULL DEFAULT 'draft',
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    UNIQUE (organization_id,id),
    UNIQUE (organization_id,assessment_instrument_id,version_number),
    FOREIGN KEY (organization_id,assessment_instrument_id) REFERENCES assessment_instruments(organization_id,id),
    CHECK (version_number>0),
    CHECK (jsonb_typeof(definition_json)='object' AND pg_column_size(definition_json)<=65536),
    CHECK (definition_digest~'^[0-9a-f]{64}$'),
    CHECK (effective_to IS NULL OR effective_to>effective_from),
    CHECK (status IN ('draft','active','retired')),
    CHECK (lock_version=0)
);

CREATE TABLE measurements (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    assessment_session_id uuid NOT NULL,
    assessment_section_id uuid NOT NULL,
    instrument_version_id uuid,
    measurement_key varchar(120) NOT NULL,
    purpose_text varchar(1000) NOT NULL,
    baseline boolean NOT NULL DEFAULT false,
    value_text varchar(500) NOT NULL,
    source_key varchar(80) NOT NULL,
    method_key varchar(80) NOT NULL,
    unit_scale varchar(120) NOT NULL,
    cadence_text varchar(240) NOT NULL,
    owner_practitioner_id uuid NOT NULL,
    action_threshold_text varchar(1000) NOT NULL,
    interpretation_status varchar(32) NOT NULL DEFAULT 'uninterpreted',
    recorded_by_practitioner_id uuid NOT NULL,
    recorded_at timestamptz NOT NULL,
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    UNIQUE (organization_id,id),
    FOREIGN KEY (organization_id,assessment_session_id) REFERENCES assessment_sessions(organization_id,id),
    FOREIGN KEY (organization_id,assessment_section_id) REFERENCES assessment_sections(organization_id,id),
    FOREIGN KEY (organization_id,instrument_version_id) REFERENCES instrument_versions(organization_id,id),
    FOREIGN KEY (organization_id,owner_practitioner_id) REFERENCES practitioner_profiles(organization_id,id),
    FOREIGN KEY (organization_id,recorded_by_practitioner_id) REFERENCES practitioner_profiles(organization_id,id),
    CHECK (char_length(btrim(measurement_key)) BETWEEN 2 AND 120),
    CHECK (lower(replace(measurement_key,'-','_'))<>'composite_cure_score'),
    CHECK (char_length(btrim(purpose_text)) BETWEEN 2 AND 1000),
    CHECK (char_length(btrim(value_text)) BETWEEN 1 AND 500),
    CHECK (char_length(btrim(source_key)) BETWEEN 2 AND 80),
    CHECK (char_length(btrim(method_key)) BETWEEN 2 AND 80),
    CHECK (char_length(btrim(unit_scale)) BETWEEN 1 AND 120),
    CHECK (char_length(btrim(cadence_text)) BETWEEN 2 AND 240),
    CHECK (char_length(btrim(action_threshold_text)) BETWEEN 2 AND 1000),
    CHECK (interpretation_status IN ('uninterpreted','provisional','reviewed','not_applicable')),
    CHECK (lock_version=0)
);

CREATE TABLE red_flags (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    assessment_session_id uuid NOT NULL,
    assessment_section_id uuid NOT NULL,
    severity_key varchar(24) NOT NULL,
    summary_text varchar(4000) NOT NULL,
    summary_digest char(64) NOT NULL,
    source_key varchar(80) NOT NULL,
    method_key varchar(80) NOT NULL,
    owner_practitioner_id uuid NOT NULL,
    raised_by_practitioner_id uuid NOT NULL,
    raised_at timestamptz NOT NULL,
    acknowledged_by_practitioner_id uuid,
    acknowledged_at timestamptz,
    acknowledgement_reason varchar(1000),
    resolved_by_practitioner_id uuid,
    resolved_at timestamptz,
    resolution_reason varchar(1000),
    status varchar(24) NOT NULL DEFAULT 'raised',
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    UNIQUE (organization_id,id),
    FOREIGN KEY (organization_id,assessment_session_id) REFERENCES assessment_sessions(organization_id,id),
    FOREIGN KEY (organization_id,assessment_section_id) REFERENCES assessment_sections(organization_id,id),
    FOREIGN KEY (organization_id,owner_practitioner_id) REFERENCES practitioner_profiles(organization_id,id),
    FOREIGN KEY (organization_id,raised_by_practitioner_id) REFERENCES practitioner_profiles(organization_id,id),
    FOREIGN KEY (organization_id,acknowledged_by_practitioner_id) REFERENCES practitioner_profiles(organization_id,id),
    FOREIGN KEY (organization_id,resolved_by_practitioner_id) REFERENCES practitioner_profiles(organization_id,id),
    CHECK (severity_key IN ('moderate','severe','critical')),
    CHECK (char_length(btrim(summary_text)) BETWEEN 2 AND 4000),
    CHECK (summary_digest~'^[0-9a-f]{64}$'),
    CHECK (char_length(btrim(source_key)) BETWEEN 2 AND 80),
    CHECK (char_length(btrim(method_key)) BETWEEN 2 AND 80),
    CHECK ((acknowledged_at IS NULL)=(acknowledged_by_practitioner_id IS NULL)),
    CHECK (acknowledged_at IS NULL OR acknowledgement_reason IS NOT NULL),
    CHECK ((resolved_at IS NULL)=(resolved_by_practitioner_id IS NULL)),
    CHECK (resolved_at IS NULL OR resolution_reason IS NOT NULL),
    CHECK (status IN ('raised','acknowledged','resolved')),
    CHECK ((status IN ('acknowledged','resolved'))=(acknowledged_at IS NOT NULL)),
    CHECK ((status='resolved')=(resolved_at IS NOT NULL)),
    CHECK (lock_version>=0)
);

CREATE INDEX red_flags_open_idx
    ON red_flags(organization_id,assessment_session_id,status,severity_key,id)
    WHERE status<>'resolved';

CREATE TABLE assessment_reviews (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    assessment_session_id uuid NOT NULL,
    assessment_revision bigint NOT NULL,
    completed_section_count smallint NOT NULL,
    total_section_count smallint NOT NULL DEFAULT 27,
    completeness_confirmed boolean NOT NULL,
    source_reviewed boolean NOT NULL,
    uncertainty_reviewed boolean NOT NULL,
    review_summary_text varchar(4000) NOT NULL,
    review_digest char(64) NOT NULL,
    reviewer_practitioner_id uuid NOT NULL,
    reviewed_at timestamptz NOT NULL,
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    UNIQUE (organization_id,id),
    UNIQUE (organization_id,assessment_session_id,assessment_revision),
    FOREIGN KEY (organization_id,assessment_session_id) REFERENCES assessment_sessions(organization_id,id),
    FOREIGN KEY (organization_id,reviewer_practitioner_id) REFERENCES practitioner_profiles(organization_id,id),
    CHECK (assessment_revision>0),
    CHECK (completed_section_count BETWEEN 0 AND 27),
    CHECK (total_section_count=27),
    CHECK (char_length(btrim(review_summary_text)) BETWEEN 2 AND 4000),
    CHECK (review_digest~'^[0-9a-f]{64}$'),
    CHECK (lock_version=0)
);

CREATE TABLE assessment_signatures (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    assessment_session_id uuid NOT NULL,
    assessment_review_id uuid NOT NULL,
    assessment_revision bigint NOT NULL,
    signer_practitioner_id uuid NOT NULL,
    attestation_key varchar(120) NOT NULL,
    signature_digest char(64) NOT NULL,
    signed_at timestamptz NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'signed',
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    UNIQUE (organization_id,id),
    UNIQUE (organization_id,assessment_session_id,assessment_revision),
    FOREIGN KEY (organization_id,assessment_session_id) REFERENCES assessment_sessions(organization_id,id),
    FOREIGN KEY (organization_id,assessment_review_id) REFERENCES assessment_reviews(organization_id,id),
    FOREIGN KEY (organization_id,signer_practitioner_id) REFERENCES practitioner_profiles(organization_id,id),
    CHECK (assessment_revision>0),
    CHECK (char_length(btrim(attestation_key)) BETWEEN 2 AND 120),
    CHECK (signature_digest~'^[0-9a-f]{64}$'),
    CHECK (status='signed'),
    CHECK (lock_version=0)
);

CREATE TABLE assessment_amendments (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    assessment_session_id uuid NOT NULL,
    assessment_signature_id uuid NOT NULL,
    assessment_revision bigint NOT NULL,
    amendment_text varchar(20000) NOT NULL,
    amendment_digest char(64) NOT NULL,
    source_key varchar(80) NOT NULL,
    method_key varchar(80) NOT NULL,
    uncertainty_text varchar(2000),
    author_practitioner_id uuid NOT NULL,
    recorded_at timestamptz NOT NULL,
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    UNIQUE (organization_id,id),
    UNIQUE (organization_id,assessment_session_id,assessment_revision),
    FOREIGN KEY (organization_id,assessment_session_id) REFERENCES assessment_sessions(organization_id,id),
    FOREIGN KEY (organization_id,assessment_signature_id) REFERENCES assessment_signatures(organization_id,id),
    FOREIGN KEY (organization_id,author_practitioner_id) REFERENCES practitioner_profiles(organization_id,id),
    CHECK (assessment_revision>0),
    CHECK (char_length(btrim(amendment_text)) BETWEEN 2 AND 20000),
    CHECK (amendment_digest~'^[0-9a-f]{64}$'),
    CHECK (char_length(btrim(source_key)) BETWEEN 2 AND 80),
    CHECK (char_length(btrim(method_key)) BETWEEN 2 AND 80),
    CHECK (lock_version=0)
);

DO $$
DECLARE table_name text;
BEGIN
    FOREACH table_name IN ARRAY ARRAY[
        'assessment_sessions','assessment_sections','assessment_responses','response_versions',
        'clinical_narratives','assessment_instruments','instrument_versions','measurements',
        'red_flags','assessment_reviews','assessment_signatures','assessment_amendments'
    ] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',table_name);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',table_name);
        EXECUTE format(
            'CREATE POLICY %I ON %I USING (organization_id=nullif(current_setting(''app.current_organization_id'',true),'''')::uuid) WITH CHECK (organization_id=nullif(current_setting(''app.current_organization_id'',true),'''')::uuid)',
            table_name||'_tenant_policy',table_name);
    END LOOP;
END $$;

CREATE FUNCTION careos_validate_m6_tenant_write()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    configured_organization uuid:=nullif(current_setting('app.current_organization_id',true),'')::uuid;
    configured_actor uuid:=nullif(current_setting('app.current_actor_id',true),'')::uuid;
    configured_operation text:=nullif(current_setting('app.current_operation_key',true),'');
    allowed_operations text[];
BEGIN
    IF current_user<>'${applicationRole}' THEN RETURN NEW; END IF;
    allowed_operations:=CASE TG_TABLE_NAME
        WHEN 'assessment_sessions' THEN ARRAY['assessment.start','assessment.response.write','assessment.measurement.write','assessment.red_flag.write','assessment.review','assessment.sign','assessment.amend','assessment.lifecycle.manage']
        WHEN 'assessment_sections' THEN ARRAY['assessment.start','assessment.response.write']
        WHEN 'assessment_responses' THEN ARRAY['assessment.response.write']
        WHEN 'response_versions' THEN ARRAY['assessment.response.write']
        WHEN 'clinical_narratives' THEN ARRAY['assessment.response.write']
        WHEN 'measurements' THEN ARRAY['assessment.measurement.write']
        WHEN 'red_flags' THEN ARRAY['assessment.red_flag.write']
        WHEN 'assessment_reviews' THEN ARRAY['assessment.review']
        WHEN 'assessment_signatures' THEN ARRAY['assessment.sign']
        WHEN 'assessment_amendments' THEN ARRAY['assessment.amend']
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
              AND operation.registry_version='m6-standing-direction-v1'
              AND operation.status='active') THEN
        RAISE EXCEPTION 'invalid Module 6 operation-bound tenant write context' USING ERRCODE='42501';
    END IF;
    IF TG_OP='INSERT' THEN
        IF NEW.created_by IS DISTINCT FROM configured_actor
           OR NEW.updated_by IS DISTINCT FROM configured_actor
           OR NEW.lock_version<>0 THEN
            RAISE EXCEPTION 'invalid Module 6 creation evidence' USING ERRCODE='23514';
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
            RAISE EXCEPTION 'invalid Module 6 revision evidence' USING ERRCODE='23514';
        END IF;
    END IF;
    RETURN NEW;
END $$;

DO $$
DECLARE table_name text;
BEGIN
    FOREACH table_name IN ARRAY ARRAY[
        'assessment_sessions','assessment_sections','assessment_responses','response_versions',
        'clinical_narratives','measurements','red_flags','assessment_reviews',
        'assessment_signatures','assessment_amendments'
    ] LOOP
        EXECUTE format(
            'CREATE TRIGGER %I BEFORE INSERT OR UPDATE ON %I FOR EACH ROW EXECUTE FUNCTION careos_validate_m6_tenant_write()',
            table_name||'_write_guard',table_name);
    END LOOP;
END $$;

CREATE FUNCTION careos_reject_m6_evidence_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Module 6 evidence is append-only' USING ERRCODE='42501';
END $$;

DO $$
DECLARE table_name text;
BEGIN
    FOREACH table_name IN ARRAY ARRAY[
        'response_versions','clinical_narratives','instrument_versions','measurements',
        'assessment_reviews','assessment_signatures','assessment_amendments'
    ] LOOP
        EXECUTE format(
            'CREATE TRIGGER %I BEFORE UPDATE OR DELETE ON %I FOR EACH ROW EXECUTE FUNCTION careos_reject_m6_evidence_mutation()',
            table_name||'_immutable',table_name);
    END LOOP;
END $$;

GRANT SELECT,INSERT,UPDATE ON
    assessment_sessions,assessment_sections,assessment_responses,red_flags
TO "${applicationRole}";
GRANT SELECT,INSERT ON
    response_versions,clinical_narratives,measurements,assessment_reviews,
    assessment_signatures,assessment_amendments
TO "${applicationRole}";
GRANT SELECT ON assessment_instruments,instrument_versions TO "${applicationRole}";

COMMENT ON TABLE assessment_sessions IS
    'Governed 27-section COS assessment bound to one active encounter and responsible clinician.';
COMMENT ON TABLE response_versions IS
    'Append-only, source- and method-attributed response versions; autosave never overwrites prior content.';
COMMENT ON TABLE assessment_instruments IS
    'Migration-owned clinical instrument catalogue; intentionally empty until locally approved source assets exist.';
COMMENT ON TABLE measurements IS
    'Purpose-bound individual outcome measurements. Composite cure scores are prohibited.';
COMMENT ON TABLE red_flags IS
    'Visible clinical safety flags with attributed acknowledgement and resolution; silent dismissal is unavailable.';
