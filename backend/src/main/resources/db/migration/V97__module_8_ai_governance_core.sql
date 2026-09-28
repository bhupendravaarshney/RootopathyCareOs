CREATE TABLE ai_model_releases (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    provider_key varchar(80) NOT NULL,
    model_key varchar(120) NOT NULL,
    model_version varchar(120) NOT NULL,
    region_key varchar(80) NOT NULL,
    training_use_prohibited boolean NOT NULL DEFAULT true,
    retention_days integer NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'draft',
    effective_from timestamptz,
    retired_at timestamptz,
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    UNIQUE (organization_id,id),
    UNIQUE (organization_id,provider_key,model_key,model_version),
    CHECK (provider_key~'^[a-z][a-z0-9_.:-]{1,79}$'),
    CHECK (model_key~'^[a-z][a-z0-9_.:-]{1,119}$'),
    CHECK (model_version~'^[A-Za-z0-9][A-Za-z0-9._:-]{0,119}$'),
    CHECK (region_key~'^[a-z][a-z0-9_-]{1,79}$'),
    CHECK (retention_days BETWEEN 0 AND 36525),
    CHECK (status IN ('draft','active','retired')),
    CHECK ((status='active')=(effective_from IS NOT NULL AND retired_at IS NULL)),
    CHECK ((status='retired')=(retired_at IS NOT NULL)),
    CHECK (lock_version=0)
);

CREATE TABLE ai_prompt_releases (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    prompt_key varchar(120) NOT NULL,
    prompt_version varchar(120) NOT NULL,
    purpose_key varchar(80) NOT NULL,
    template_digest char(64) NOT NULL,
    output_schema_key varchar(80) NOT NULL,
    output_schema_version integer NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'draft',
    effective_from timestamptz,
    retired_at timestamptz,
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    UNIQUE (organization_id,id),
    UNIQUE (organization_id,prompt_key,prompt_version),
    CHECK (prompt_key~'^[a-z][a-z0-9_.:-]{1,119}$'),
    CHECK (prompt_version~'^[A-Za-z0-9][A-Za-z0-9._:-]{0,119}$'),
    CHECK (purpose_key~'^[a-z][a-z0-9_]{1,79}$'),
    CHECK (template_digest~'^[0-9a-f]{64}$'),
    CHECK (output_schema_key~'^[a-z][a-z0-9_.:-]{1,79}$'),
    CHECK (output_schema_version>0),
    CHECK (status IN ('draft','active','retired')),
    CHECK ((status='active')=(effective_from IS NOT NULL AND retired_at IS NULL)),
    CHECK ((status='retired')=(retired_at IS NOT NULL)),
    CHECK (lock_version=0)
);

CREATE TABLE ai_evaluation_signoffs (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    model_release_id uuid NOT NULL,
    prompt_release_id uuid NOT NULL,
    dataset_key varchar(120) NOT NULL,
    dataset_version varchar(120) NOT NULL,
    dataset_digest char(64) NOT NULL,
    evaluation_digest char(64) NOT NULL,
    outcome varchar(24) NOT NULL,
    safety_signoff_by uuid NOT NULL,
    safety_signoff_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    reason varchar(500) NOT NULL,
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    UNIQUE (organization_id,id),
    UNIQUE (organization_id,model_release_id,prompt_release_id,dataset_key,dataset_version),
    FOREIGN KEY (organization_id,model_release_id) REFERENCES ai_model_releases(organization_id,id),
    FOREIGN KEY (organization_id,prompt_release_id) REFERENCES ai_prompt_releases(organization_id,id),
    CHECK (dataset_key~'^[a-z][a-z0-9_.:-]{1,119}$'),
    CHECK (dataset_version~'^[A-Za-z0-9][A-Za-z0-9._:-]{0,119}$'),
    CHECK (dataset_digest~'^[0-9a-f]{64}$'),
    CHECK (evaluation_digest~'^[0-9a-f]{64}$'),
    CHECK (outcome IN ('approved','rejected')),
    CHECK (expires_at>safety_signoff_at),
    CHECK (char_length(btrim(reason)) BETWEEN 10 AND 500),
    CHECK (lock_version=0)
);

CREATE TABLE ai_sessions (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    patient_id uuid NOT NULL,
    encounter_id uuid NOT NULL,
    requester_id uuid NOT NULL REFERENCES users(id),
    session_type varchar(32) NOT NULL,
    purpose_key varchar(80),
    status varchar(24) NOT NULL DEFAULT 'draft',
    current_manifest_id uuid,
    current_output_id uuid,
    failure_code varchar(80),
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    UNIQUE (organization_id,id),
    FOREIGN KEY (organization_id,patient_id) REFERENCES patient_profiles(organization_id,id),
    FOREIGN KEY (organization_id,encounter_id) REFERENCES encounters(organization_id,id),
    CHECK (session_type IN ('transcription','extraction','summary','clinical_suggestion')),
    CHECK (purpose_key IS NULL OR purpose_key~'^[a-z][a-z0-9_]{1,79}$'),
    CHECK (status IN ('draft','authorized','input_ready','processing','draft_ready','under_review','accepted','rejected','failed','cancelled')),
    CHECK ((status='draft')=(purpose_key IS NULL)),
    CHECK ((status='failed')=(failure_code IS NOT NULL)),
    CHECK (failure_code IS NULL OR failure_code~'^[a-z][a-z0-9_]{1,79}$'),
    CHECK (lock_version>=0)
);

CREATE INDEX ai_sessions_patient_updated_idx
    ON ai_sessions(organization_id,patient_id,updated_at DESC,id);

CREATE TABLE ai_purpose_consents (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    ai_session_id uuid NOT NULL,
    purpose_key varchar(80) NOT NULL,
    legal_basis_key varchar(80) NOT NULL,
    consent_status varchar(24) NOT NULL,
    consent_reference varchar(240),
    minimum_necessary_confirmed boolean NOT NULL,
    recorded_at timestamptz NOT NULL,
    reason varchar(500) NOT NULL,
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    UNIQUE (organization_id,id),
    UNIQUE (organization_id,ai_session_id),
    FOREIGN KEY (organization_id,ai_session_id) REFERENCES ai_sessions(organization_id,id),
    CHECK (purpose_key~'^[a-z][a-z0-9_]{1,79}$'),
    CHECK (legal_basis_key~'^[a-z][a-z0-9_.:-]{1,79}$'),
    CHECK (consent_status IN ('granted','not_required','denied','withdrawn')),
    CHECK ((consent_status='granted')=(consent_reference IS NOT NULL)),
    CHECK (consent_reference IS NULL OR char_length(btrim(consent_reference)) BETWEEN 2 AND 240),
    CHECK (char_length(btrim(reason)) BETWEEN 10 AND 500),
    CHECK (lock_version=0)
);

CREATE TABLE ai_input_manifests (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    ai_session_id uuid NOT NULL,
    manifest_version integer NOT NULL,
    manifest_digest char(64) NOT NULL,
    item_count integer NOT NULL,
    approved_at timestamptz NOT NULL,
    reason varchar(500) NOT NULL,
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    UNIQUE (organization_id,id),
    UNIQUE (organization_id,ai_session_id,manifest_version),
    FOREIGN KEY (organization_id,ai_session_id) REFERENCES ai_sessions(organization_id,id),
    CHECK (manifest_version>0),
    CHECK (manifest_digest~'^[0-9a-f]{64}$'),
    CHECK (item_count BETWEEN 1 AND 64),
    CHECK (char_length(btrim(reason)) BETWEEN 10 AND 500),
    CHECK (lock_version=0)
);

CREATE TABLE ai_input_manifest_items (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    input_manifest_id uuid NOT NULL,
    item_sequence integer NOT NULL,
    source_type varchar(32) NOT NULL,
    source_id uuid NOT NULL,
    source_revision bigint NOT NULL,
    source_digest char(64) NOT NULL,
    selection_reason varchar(500) NOT NULL,
    data_categories text[] NOT NULL,
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    UNIQUE (organization_id,id),
    UNIQUE (organization_id,input_manifest_id,item_sequence),
    UNIQUE (organization_id,input_manifest_id,source_type,source_id),
    FOREIGN KEY (organization_id,input_manifest_id) REFERENCES ai_input_manifests(organization_id,id),
    CHECK (item_sequence BETWEEN 1 AND 64),
    CHECK (source_type IN ('encounter','assessment','document','diagnostic_report')),
    CHECK (source_revision>=0),
    CHECK (source_digest~'^[0-9a-f]{64}$'),
    CHECK (char_length(btrim(selection_reason)) BETWEEN 10 AND 500),
    CHECK (cardinality(data_categories) BETWEEN 1 AND 16),
    CHECK (lock_version=0)
);

ALTER TABLE ai_sessions
    ADD CONSTRAINT ai_sessions_current_manifest_fk
    FOREIGN KEY (organization_id,current_manifest_id) REFERENCES ai_input_manifests(organization_id,id);

CREATE TABLE ai_job_contracts (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    ai_session_id uuid NOT NULL,
    input_manifest_id uuid NOT NULL,
    model_release_id uuid NOT NULL,
    prompt_release_id uuid NOT NULL,
    evaluation_signoff_id uuid NOT NULL,
    contract_version integer NOT NULL,
    output_schema_key varchar(80) NOT NULL,
    output_schema_version integer NOT NULL,
    parameter_digest char(64) NOT NULL,
    contract_digest char(64) NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'requested',
    requested_at timestamptz NOT NULL,
    reason varchar(500) NOT NULL,
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    UNIQUE (organization_id,id),
    UNIQUE (organization_id,ai_session_id,contract_version),
    FOREIGN KEY (organization_id,ai_session_id) REFERENCES ai_sessions(organization_id,id),
    FOREIGN KEY (organization_id,input_manifest_id) REFERENCES ai_input_manifests(organization_id,id),
    FOREIGN KEY (organization_id,model_release_id) REFERENCES ai_model_releases(organization_id,id),
    FOREIGN KEY (organization_id,prompt_release_id) REFERENCES ai_prompt_releases(organization_id,id),
    FOREIGN KEY (organization_id,evaluation_signoff_id) REFERENCES ai_evaluation_signoffs(organization_id,id),
    CHECK (contract_version>0),
    CHECK (output_schema_key~'^[a-z][a-z0-9_.:-]{1,79}$'),
    CHECK (output_schema_version>0),
    CHECK (parameter_digest~'^[0-9a-f]{64}$'),
    CHECK (contract_digest~'^[0-9a-f]{64}$'),
    CHECK (status='requested'),
    CHECK (char_length(btrim(reason)) BETWEEN 10 AND 500),
    CHECK (lock_version=0)
);

CREATE TABLE ai_job_attempts (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    job_contract_id uuid NOT NULL,
    attempt_number integer NOT NULL,
    provider_request_reference varchar(200),
    status varchar(24) NOT NULL,
    started_at timestamptz NOT NULL,
    completed_at timestamptz NOT NULL,
    latency_millis bigint NOT NULL,
    result_digest char(64),
    failure_code varchar(80),
    retryable boolean NOT NULL DEFAULT false,
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    UNIQUE (organization_id,id),
    UNIQUE (organization_id,job_contract_id,attempt_number),
    FOREIGN KEY (organization_id,job_contract_id) REFERENCES ai_job_contracts(organization_id,id),
    CHECK (attempt_number>0),
    CHECK (status IN ('succeeded','failed')),
    CHECK (completed_at>=started_at),
    CHECK (latency_millis>=0),
    CHECK ((status='succeeded')=(result_digest IS NOT NULL)),
    CHECK ((status='failed')=(failure_code IS NOT NULL)),
    CHECK (result_digest IS NULL OR result_digest~'^[0-9a-f]{64}$'),
    CHECK (failure_code IS NULL OR failure_code~'^[a-z][a-z0-9_]{1,79}$'),
    CHECK (lock_version=0)
);

CREATE TABLE ai_outputs (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    ai_session_id uuid NOT NULL,
    job_attempt_id uuid NOT NULL,
    output_schema_key varchar(80) NOT NULL,
    output_schema_version integer NOT NULL,
    provider_output_digest char(64) NOT NULL,
    uncertainty_label varchar(24) NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'draft',
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    lock_version bigint NOT NULL DEFAULT 0,
    UNIQUE (organization_id,id),
    UNIQUE (organization_id,job_attempt_id),
    FOREIGN KEY (organization_id,ai_session_id) REFERENCES ai_sessions(organization_id,id),
    FOREIGN KEY (organization_id,job_attempt_id) REFERENCES ai_job_attempts(organization_id,id),
    CHECK (output_schema_key~'^[a-z][a-z0-9_.:-]{1,79}$'),
    CHECK (output_schema_version>0),
    CHECK (provider_output_digest~'^[0-9a-f]{64}$'),
    CHECK (uncertainty_label IN ('low','moderate','high','unknown')),
    CHECK (status='draft'),
    CHECK (lock_version=0)
);

ALTER TABLE ai_sessions
    ADD CONSTRAINT ai_sessions_current_output_fk
    FOREIGN KEY (organization_id,current_output_id) REFERENCES ai_outputs(organization_id,id);

CREATE TABLE ai_output_versions (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    ai_output_id uuid NOT NULL,
    version_number integer NOT NULL,
    prior_version_id uuid,
    content_type varchar(24) NOT NULL,
    content text NOT NULL,
    content_digest char(64) NOT NULL,
    authored_by_type varchar(24) NOT NULL,
    edit_summary varchar(500),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    lock_version bigint NOT NULL DEFAULT 0,
    UNIQUE (organization_id,id),
    UNIQUE (organization_id,ai_output_id,version_number),
    FOREIGN KEY (organization_id,ai_output_id) REFERENCES ai_outputs(organization_id,id),
    FOREIGN KEY (organization_id,prior_version_id) REFERENCES ai_output_versions(organization_id,id),
    CHECK (version_number>0),
    CHECK ((version_number=1)=(prior_version_id IS NULL)),
    CHECK (content_type IN ('transcription','extraction','summary','clinical_suggestion')),
    CHECK (char_length(content) BETWEEN 1 AND 20000),
    CHECK (content_digest~'^[0-9a-f]{64}$'),
    CHECK (authored_by_type IN ('provider','clinician')),
    CHECK ((authored_by_type='clinician')=(edit_summary IS NOT NULL)),
    CHECK (edit_summary IS NULL OR char_length(btrim(edit_summary)) BETWEEN 10 AND 500),
    CHECK (lock_version=0)
);

CREATE TABLE ai_output_citations (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    ai_output_version_id uuid NOT NULL,
    input_manifest_item_id uuid NOT NULL,
    citation_sequence integer NOT NULL,
    source_locator varchar(240) NOT NULL,
    claim_digest char(64) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    lock_version bigint NOT NULL DEFAULT 0,
    UNIQUE (organization_id,id),
    UNIQUE (organization_id,ai_output_version_id,citation_sequence),
    FOREIGN KEY (organization_id,ai_output_version_id) REFERENCES ai_output_versions(organization_id,id),
    FOREIGN KEY (organization_id,input_manifest_item_id) REFERENCES ai_input_manifest_items(organization_id,id),
    CHECK (citation_sequence BETWEEN 1 AND 128),
    CHECK (char_length(btrim(source_locator)) BETWEEN 1 AND 240),
    CHECK (claim_digest~'^[0-9a-f]{64}$'),
    CHECK (lock_version=0)
);

CREATE TABLE ai_safety_flags (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    ai_output_id uuid NOT NULL,
    flag_key varchar(80) NOT NULL,
    severity varchar(24) NOT NULL,
    summary varchar(1000) NOT NULL,
    state varchar(24) NOT NULL DEFAULT 'open',
    acknowledged_at timestamptz,
    resolved_at timestamptz,
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    UNIQUE (organization_id,id),
    UNIQUE (organization_id,ai_output_id,flag_key),
    FOREIGN KEY (organization_id,ai_output_id) REFERENCES ai_outputs(organization_id,id),
    CHECK (flag_key~'^[a-z][a-z0-9_]{1,79}$'),
    CHECK (severity IN ('information','warning','critical','emergency')),
    CHECK (char_length(btrim(summary)) BETWEEN 2 AND 1000),
    CHECK (state IN ('open','acknowledged','resolved')),
    CHECK ((state='open')=(acknowledged_at IS NULL AND resolved_at IS NULL)),
    CHECK ((state='acknowledged')=(acknowledged_at IS NOT NULL AND resolved_at IS NULL)),
    CHECK ((state='resolved')=(acknowledged_at IS NOT NULL AND resolved_at IS NOT NULL)),
    CHECK (lock_version>=0)
);

CREATE TABLE ai_safety_escalations (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    ai_safety_flag_id uuid NOT NULL,
    owner_practitioner_id uuid NOT NULL,
    escalation_level varchar(24) NOT NULL,
    due_at timestamptz NOT NULL,
    delivery_status varchar(24) NOT NULL DEFAULT 'not_dispatched',
    reason varchar(1000) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    lock_version bigint NOT NULL DEFAULT 0,
    UNIQUE (organization_id,id),
    UNIQUE (organization_id,ai_safety_flag_id),
    FOREIGN KEY (organization_id,ai_safety_flag_id) REFERENCES ai_safety_flags(organization_id,id),
    FOREIGN KEY (organization_id,owner_practitioner_id) REFERENCES practitioner_profiles(organization_id,id),
    CHECK (escalation_level IN ('clinical_owner','emergency_pathway')),
    CHECK (delivery_status IN ('not_dispatched','pending_provider','dispatched','failed')),
    CHECK (char_length(btrim(reason)) BETWEEN 10 AND 1000),
    CHECK (lock_version=0)
);

CREATE TABLE ai_reviews (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    ai_session_id uuid NOT NULL,
    ai_output_id uuid NOT NULL,
    ai_output_version_id uuid NOT NULL,
    reviewer_practitioner_id uuid NOT NULL,
    decision varchar(24) NOT NULL,
    reason varchar(1000) NOT NULL,
    reviewed_at timestamptz NOT NULL,
    recent_authentication_at timestamptz NOT NULL,
    mfa_authenticated_at timestamptz NOT NULL,
    final_version_digest char(64) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    lock_version bigint NOT NULL DEFAULT 0,
    UNIQUE (organization_id,id),
    UNIQUE (organization_id,ai_session_id),
    FOREIGN KEY (organization_id,ai_session_id) REFERENCES ai_sessions(organization_id,id),
    FOREIGN KEY (organization_id,ai_output_id) REFERENCES ai_outputs(organization_id,id),
    FOREIGN KEY (organization_id,ai_output_version_id) REFERENCES ai_output_versions(organization_id,id),
    FOREIGN KEY (organization_id,reviewer_practitioner_id) REFERENCES practitioner_profiles(organization_id,id),
    CHECK (decision IN ('accepted','rejected')),
    CHECK (char_length(btrim(reason)) BETWEEN 10 AND 1000),
    CHECK (mfa_authenticated_at<=recent_authentication_at),
    CHECK (recent_authentication_at<=reviewed_at),
    CHECK (final_version_digest~'^[0-9a-f]{64}$'),
    CHECK (lock_version=0)
);

CREATE TABLE ai_usage_records (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    job_attempt_id uuid NOT NULL,
    input_tokens bigint NOT NULL,
    output_tokens bigint NOT NULL,
    cost_minor_units bigint NOT NULL,
    currency char(3) NOT NULL,
    measured_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    lock_version bigint NOT NULL DEFAULT 0,
    UNIQUE (organization_id,id),
    UNIQUE (organization_id,job_attempt_id),
    FOREIGN KEY (organization_id,job_attempt_id) REFERENCES ai_job_attempts(organization_id,id),
    CHECK (input_tokens>=0 AND output_tokens>=0),
    CHECK (cost_minor_units>=0),
    CHECK (currency~'^[A-Z]{3}$'),
    CHECK (lock_version=0)
);

CREATE TABLE ai_retention_metadata (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    ai_session_id uuid NOT NULL,
    policy_key varchar(80) NOT NULL,
    retain_until timestamptz NOT NULL,
    provider_deletion_due_at timestamptz,
    training_use_prohibited boolean NOT NULL DEFAULT true,
    recorded_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    lock_version bigint NOT NULL DEFAULT 0,
    UNIQUE (organization_id,id),
    UNIQUE (organization_id,ai_session_id),
    FOREIGN KEY (organization_id,ai_session_id) REFERENCES ai_sessions(organization_id,id),
    CHECK (policy_key~'^[a-z][a-z0-9_.:-]{1,79}$'),
    CHECK (retain_until>=recorded_at),
    CHECK (provider_deletion_due_at IS NULL OR provider_deletion_due_at>=recorded_at),
    CHECK (lock_version=0)
);

DO $$
DECLARE table_name text;
BEGIN
    FOREACH table_name IN ARRAY ARRAY[
        'ai_model_releases','ai_prompt_releases','ai_evaluation_signoffs','ai_sessions',
        'ai_purpose_consents','ai_input_manifests','ai_input_manifest_items','ai_job_contracts',
        'ai_job_attempts','ai_outputs','ai_output_versions','ai_output_citations',
        'ai_safety_flags','ai_safety_escalations','ai_reviews','ai_usage_records',
        'ai_retention_metadata'
    ] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',table_name);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',table_name);
        EXECUTE format(
            'CREATE POLICY %I ON %I USING (organization_id=nullif(current_setting(''app.current_organization_id'',true),'''')::uuid) WITH CHECK (organization_id=nullif(current_setting(''app.current_organization_id'',true),'''')::uuid)',
            table_name||'_tenant_policy',table_name);
    END LOOP;
END $$;

CREATE FUNCTION careos_validate_m8_tenant_write()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    configured_organization uuid:=nullif(current_setting('app.current_organization_id',true),'')::uuid;
    configured_actor uuid:=nullif(current_setting('app.current_actor_id',true),'')::uuid;
    configured_operation text:=nullif(current_setting('app.current_operation_key',true),'');
    allowed_operations text[];
BEGIN
    IF current_user<>'${applicationRole}' THEN RETURN NEW; END IF;
    allowed_operations:=CASE TG_TABLE_NAME
        WHEN 'ai_sessions' THEN ARRAY['ai.session.launch','ai.consent.record','ai.input.select','ai.process.request','ai.output.edit','ai.review.decide','ai.session.cancel']
        WHEN 'ai_purpose_consents' THEN ARRAY['ai.consent.record']
        WHEN 'ai_input_manifests' THEN ARRAY['ai.input.select']
        WHEN 'ai_input_manifest_items' THEN ARRAY['ai.input.select']
        WHEN 'ai_job_contracts' THEN ARRAY['ai.process.request']
        WHEN 'ai_job_attempts' THEN ARRAY['ai.process.request']
        WHEN 'ai_outputs' THEN ARRAY['ai.process.request']
        WHEN 'ai_output_versions' THEN ARRAY['ai.process.request','ai.output.edit']
        WHEN 'ai_output_citations' THEN ARRAY['ai.process.request','ai.output.edit']
        WHEN 'ai_safety_flags' THEN ARRAY['ai.process.request','ai.safety.review']
        WHEN 'ai_safety_escalations' THEN ARRAY['ai.process.request']
        WHEN 'ai_reviews' THEN ARRAY['ai.review.decide']
        WHEN 'ai_usage_records' THEN ARRAY['ai.process.request']
        WHEN 'ai_retention_metadata' THEN ARRAY['ai.process.request']
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
              AND operation.registry_version='m8-standing-direction-v1'
              AND operation.status='active') THEN
        RAISE EXCEPTION 'invalid Module 8 operation-bound tenant write context' USING ERRCODE='42501';
    END IF;
    IF TG_OP='INSERT' THEN
        IF NEW.created_by IS DISTINCT FROM configured_actor
           OR NEW.updated_by IS DISTINCT FROM configured_actor
           OR NEW.lock_version<>0 THEN
            RAISE EXCEPTION 'invalid Module 8 creation evidence' USING ERRCODE='23514';
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
            RAISE EXCEPTION 'invalid Module 8 revision evidence' USING ERRCODE='23514';
        END IF;
    END IF;
    RETURN NEW;
END $$;

DO $$
DECLARE table_name text;
BEGIN
    FOREACH table_name IN ARRAY ARRAY[
        'ai_sessions','ai_purpose_consents','ai_input_manifests','ai_input_manifest_items',
        'ai_job_contracts','ai_job_attempts','ai_outputs','ai_output_versions',
        'ai_output_citations','ai_safety_flags','ai_safety_escalations','ai_reviews',
        'ai_usage_records','ai_retention_metadata'
    ] LOOP
        EXECUTE format(
            'CREATE TRIGGER %I BEFORE INSERT OR UPDATE ON %I FOR EACH ROW EXECUTE FUNCTION careos_validate_m8_tenant_write()',
            table_name||'_write_guard',table_name);
    END LOOP;
END $$;

CREATE FUNCTION careos_reject_m8_evidence_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Module 8 evidence is append-only' USING ERRCODE='42501';
END $$;

DO $$
DECLARE table_name text;
BEGIN
    FOREACH table_name IN ARRAY ARRAY[
        'ai_model_releases','ai_prompt_releases','ai_evaluation_signoffs','ai_purpose_consents',
        'ai_input_manifests','ai_input_manifest_items','ai_job_contracts','ai_job_attempts',
        'ai_outputs','ai_output_versions','ai_output_citations','ai_safety_escalations',
        'ai_reviews','ai_usage_records','ai_retention_metadata'
    ] LOOP
        EXECUTE format(
            'CREATE TRIGGER %I BEFORE UPDATE OR DELETE ON %I FOR EACH ROW EXECUTE FUNCTION careos_reject_m8_evidence_mutation()',
            table_name||'_immutable',table_name);
    END LOOP;
END $$;

GRANT SELECT ON ai_model_releases,ai_prompt_releases,ai_evaluation_signoffs TO "${applicationRole}";
GRANT SELECT,INSERT,UPDATE ON ai_sessions,ai_safety_flags TO "${applicationRole}";
GRANT SELECT,INSERT ON
    ai_purpose_consents,ai_input_manifests,ai_input_manifest_items,ai_job_contracts,
    ai_job_attempts,ai_outputs,ai_output_versions,ai_output_citations,
    ai_safety_escalations,ai_reviews,ai_usage_records,ai_retention_metadata
TO "${applicationRole}";

COMMENT ON TABLE ai_job_contracts IS
    'Versioned minimum-necessary AI provider contract containing references and digests, never unrestricted raw patient context.';
COMMENT ON TABLE ai_outputs IS
    'Provider output identity retained permanently as draft evidence; clinician edits append ai_output_versions and approval is a separate ai_reviews record.';
