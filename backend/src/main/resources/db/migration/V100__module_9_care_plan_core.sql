CREATE TABLE care_plans (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    patient_id uuid NOT NULL,
    encounter_id uuid NOT NULL,
    responsible_practitioner_id uuid NOT NULL,
    source_assessment_session_id uuid,
    source_ai_review_id uuid,
    supersedes_care_plan_id uuid,
    current_version_id uuid,
    plan_title varchar(200) NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'draft',
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    UNIQUE (organization_id,id),
    FOREIGN KEY (organization_id,patient_id) REFERENCES patient_profiles(organization_id,id),
    FOREIGN KEY (organization_id,encounter_id) REFERENCES encounters(organization_id,id),
    FOREIGN KEY (organization_id,responsible_practitioner_id) REFERENCES practitioner_profiles(organization_id,id),
    FOREIGN KEY (organization_id,source_assessment_session_id) REFERENCES assessment_sessions(organization_id,id),
    FOREIGN KEY (organization_id,source_ai_review_id) REFERENCES ai_reviews(organization_id,id),
    FOREIGN KEY (organization_id,supersedes_care_plan_id) REFERENCES care_plans(organization_id,id),
    CHECK (char_length(btrim(plan_title)) BETWEEN 2 AND 200),
    CHECK (status IN ('draft','review','approved','active','revised','completed','cancelled')),
    CHECK (supersedes_care_plan_id IS NULL OR supersedes_care_plan_id<>id),
    CHECK (lock_version>=0)
);

CREATE UNIQUE INDEX care_plans_one_open_successor_idx
    ON care_plans(organization_id,supersedes_care_plan_id)
    WHERE supersedes_care_plan_id IS NOT NULL;
CREATE INDEX care_plans_patient_updated_idx
    ON care_plans(organization_id,patient_id,updated_at DESC,id);

CREATE TABLE care_plan_versions (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    care_plan_id uuid NOT NULL,
    version_number integer NOT NULL,
    clinical_summary varchar(4000) NOT NULL,
    patient_summary varchar(4000) NOT NULL,
    content_digest char(64),
    status varchar(24) NOT NULL DEFAULT 'draft',
    frozen_at timestamptz,
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    UNIQUE (organization_id,id),
    UNIQUE (organization_id,care_plan_id,version_number),
    FOREIGN KEY (organization_id,care_plan_id) REFERENCES care_plans(organization_id,id),
    CHECK (version_number>0),
    CHECK (char_length(btrim(clinical_summary)) BETWEEN 10 AND 4000),
    CHECK (char_length(btrim(patient_summary)) BETWEEN 10 AND 4000),
    CHECK (content_digest IS NULL OR content_digest~'^[0-9a-f]{64}$'),
    CHECK (status IN ('draft','review','approved','active','superseded','completed','cancelled')),
    CHECK (status<>'draft' OR (content_digest IS NULL AND frozen_at IS NULL)),
    CHECK (status IN ('draft','cancelled') OR (content_digest IS NOT NULL AND frozen_at IS NOT NULL)),
    CHECK (lock_version>=0)
);

ALTER TABLE care_plans
    ADD CONSTRAINT care_plans_current_version_fk
    FOREIGN KEY (organization_id,current_version_id) REFERENCES care_plan_versions(organization_id,id);

CREATE TABLE care_plan_priorities (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    care_plan_version_id uuid NOT NULL,
    priority_sequence integer NOT NULL,
    source_type varchar(24) NOT NULL,
    source_reference_id uuid,
    problem_code_system varchar(160),
    problem_code varchar(160),
    display_text varchar(500) NOT NULL,
    rationale varchar(2000) NOT NULL,
    priority_key varchar(24) NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'active',
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    UNIQUE (organization_id,id),
    UNIQUE (organization_id,care_plan_version_id,priority_sequence),
    FOREIGN KEY (organization_id,care_plan_version_id) REFERENCES care_plan_versions(organization_id,id),
    CHECK (priority_sequence BETWEEN 1 AND 100),
    CHECK (source_type IN ('patient','assessment','encounter','clinician')),
    CHECK ((problem_code_system IS NULL)=(problem_code IS NULL)),
    CHECK (char_length(btrim(display_text)) BETWEEN 2 AND 500),
    CHECK (char_length(btrim(rationale)) BETWEEN 10 AND 2000),
    CHECK (priority_key IN ('routine','important','urgent','critical')),
    CHECK (status='active'),
    CHECK (lock_version=0)
);

CREATE TABLE care_plan_goals (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    care_plan_version_id uuid NOT NULL,
    goal_sequence integer NOT NULL,
    goal_type varchar(24) NOT NULL,
    description_text varchar(2000) NOT NULL,
    measure_text varchar(500) NOT NULL,
    target_text varchar(500) NOT NULL,
    target_date date,
    priority_key varchar(24) NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'proposed',
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    UNIQUE (organization_id,id),
    UNIQUE (organization_id,care_plan_version_id,goal_sequence),
    FOREIGN KEY (organization_id,care_plan_version_id) REFERENCES care_plan_versions(organization_id,id),
    CHECK (goal_sequence BETWEEN 1 AND 100),
    CHECK (goal_type IN ('patient_stated','clinical')),
    CHECK (char_length(btrim(description_text)) BETWEEN 10 AND 2000),
    CHECK (char_length(btrim(measure_text)) BETWEEN 2 AND 500),
    CHECK (char_length(btrim(target_text)) BETWEEN 2 AND 500),
    CHECK (priority_key IN ('routine','important','urgent','critical')),
    CHECK (status='proposed'),
    CHECK (lock_version=0)
);

CREATE TABLE care_plan_interventions (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    care_plan_version_id uuid NOT NULL,
    intervention_sequence integer NOT NULL,
    modality_key varchar(80) NOT NULL,
    intervention_name varchar(300) NOT NULL,
    rationale varchar(2000) NOT NULL,
    priority_key varchar(24) NOT NULL,
    planned_start_date date NOT NULL,
    review_date date NOT NULL,
    stop_criteria varchar(2000) NOT NULL,
    monitoring_instructions varchar(2000) NOT NULL,
    evidence_status varchar(24) NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'proposed',
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    UNIQUE (organization_id,id),
    UNIQUE (organization_id,care_plan_version_id,intervention_sequence),
    FOREIGN KEY (organization_id,care_plan_version_id) REFERENCES care_plan_versions(organization_id,id),
    CHECK (intervention_sequence BETWEEN 1 AND 100),
    CHECK (modality_key~'^[a-z][a-z0-9_.:-]{1,79}$'),
    CHECK (char_length(btrim(intervention_name)) BETWEEN 2 AND 300),
    CHECK (char_length(btrim(rationale)) BETWEEN 10 AND 2000),
    CHECK (priority_key IN ('routine','important','urgent','critical')),
    CHECK (review_date>=planned_start_date),
    CHECK (char_length(btrim(stop_criteria)) BETWEEN 10 AND 2000),
    CHECK (char_length(btrim(monitoring_instructions)) BETWEEN 10 AND 2000),
    CHECK (evidence_status IN ('established','limited','uncertain','not_assessed')),
    CHECK (status='proposed'),
    CHECK (lock_version=0)
);

CREATE TABLE intervention_assignments (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    care_plan_version_id uuid NOT NULL,
    intervention_id uuid NOT NULL,
    owner_practitioner_id uuid NOT NULL,
    responsibility_text varchar(1000) NOT NULL,
    assigned_start_date date NOT NULL,
    review_date date NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'assigned',
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    UNIQUE (organization_id,id),
    UNIQUE (organization_id,intervention_id),
    FOREIGN KEY (organization_id,care_plan_version_id) REFERENCES care_plan_versions(organization_id,id),
    FOREIGN KEY (organization_id,intervention_id) REFERENCES care_plan_interventions(organization_id,id),
    FOREIGN KEY (organization_id,owner_practitioner_id) REFERENCES practitioner_profiles(organization_id,id),
    CHECK (char_length(btrim(responsibility_text)) BETWEEN 10 AND 1000),
    CHECK (review_date>=assigned_start_date),
    CHECK (status='assigned'),
    CHECK (lock_version=0)
);

CREATE TABLE plan_consents (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    care_plan_version_id uuid NOT NULL,
    consent_version integer NOT NULL,
    consent_status varchar(24) NOT NULL,
    consent_reference varchar(240),
    preferences_text varchar(3000) NOT NULL,
    communication_needs varchar(1000),
    recorded_by_practitioner_id uuid NOT NULL,
    recorded_at timestamptz NOT NULL,
    reason varchar(500) NOT NULL,
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    UNIQUE (organization_id,id),
    UNIQUE (organization_id,care_plan_version_id,consent_version),
    FOREIGN KEY (organization_id,care_plan_version_id) REFERENCES care_plan_versions(organization_id,id),
    FOREIGN KEY (organization_id,recorded_by_practitioner_id) REFERENCES practitioner_profiles(organization_id,id),
    CHECK (consent_version>0),
    CHECK (consent_status IN ('granted','not_required','refused','withdrawn')),
    CHECK ((consent_status='granted')=(consent_reference IS NOT NULL)),
    CHECK (consent_reference IS NULL OR char_length(btrim(consent_reference)) BETWEEN 2 AND 240),
    CHECK (char_length(btrim(preferences_text)) BETWEEN 2 AND 3000),
    CHECK (communication_needs IS NULL OR char_length(btrim(communication_needs)) BETWEEN 2 AND 1000),
    CHECK (char_length(btrim(reason)) BETWEEN 10 AND 500),
    CHECK (lock_version=0)
);

CREATE TABLE interaction_reviews (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    care_plan_version_id uuid NOT NULL,
    review_version integer NOT NULL,
    reviewed_version_digest char(64) NOT NULL,
    modalities text[] NOT NULL,
    interaction_findings varchar(4000) NOT NULL,
    safety_outcome varchar(24) NOT NULL,
    reviewed_by_practitioner_id uuid NOT NULL,
    reviewed_at timestamptz NOT NULL,
    reason varchar(500) NOT NULL,
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    UNIQUE (organization_id,id),
    UNIQUE (organization_id,care_plan_version_id,review_version),
    FOREIGN KEY (organization_id,care_plan_version_id) REFERENCES care_plan_versions(organization_id,id),
    FOREIGN KEY (organization_id,reviewed_by_practitioner_id) REFERENCES practitioner_profiles(organization_id,id),
    CHECK (review_version>0),
    CHECK (reviewed_version_digest~'^[0-9a-f]{64}$'),
    CHECK (cardinality(modalities) BETWEEN 1 AND 32),
    CHECK (char_length(btrim(interaction_findings)) BETWEEN 10 AND 4000),
    CHECK (safety_outcome IN ('clear','needs_changes','unsafe')),
    CHECK (char_length(btrim(reason)) BETWEEN 10 AND 500),
    CHECK (lock_version=0)
);

CREATE TABLE care_plan_approvals (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    care_plan_id uuid NOT NULL,
    care_plan_version_id uuid NOT NULL,
    approved_version_digest char(64) NOT NULL,
    approver_practitioner_id uuid NOT NULL,
    decision varchar(24) NOT NULL DEFAULT 'approved',
    recent_authentication_at timestamptz NOT NULL,
    mfa_authenticated_at timestamptz NOT NULL,
    approved_at timestamptz NOT NULL,
    reason varchar(500) NOT NULL,
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    UNIQUE (organization_id,id),
    UNIQUE (organization_id,care_plan_id,care_plan_version_id),
    FOREIGN KEY (organization_id,care_plan_id) REFERENCES care_plans(organization_id,id),
    FOREIGN KEY (organization_id,care_plan_version_id) REFERENCES care_plan_versions(organization_id,id),
    FOREIGN KEY (organization_id,approver_practitioner_id) REFERENCES practitioner_profiles(organization_id,id),
    CHECK (approved_version_digest~'^[0-9a-f]{64}$'),
    CHECK (decision='approved'),
    CHECK (char_length(btrim(reason)) BETWEEN 10 AND 500),
    CHECK (lock_version=0)
);

CREATE TABLE care_plan_amendments (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    prior_care_plan_id uuid NOT NULL,
    prior_version_id uuid NOT NULL,
    prior_version_digest char(64) NOT NULL,
    successor_care_plan_id uuid NOT NULL,
    successor_version_id uuid NOT NULL,
    amendment_summary varchar(2000) NOT NULL,
    amended_at timestamptz NOT NULL,
    amended_by_practitioner_id uuid NOT NULL,
    reason varchar(500) NOT NULL,
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    UNIQUE (organization_id,id),
    UNIQUE (organization_id,prior_care_plan_id),
    UNIQUE (organization_id,successor_care_plan_id),
    FOREIGN KEY (organization_id,prior_care_plan_id) REFERENCES care_plans(organization_id,id),
    FOREIGN KEY (organization_id,prior_version_id) REFERENCES care_plan_versions(organization_id,id),
    FOREIGN KEY (organization_id,successor_care_plan_id) REFERENCES care_plans(organization_id,id),
    FOREIGN KEY (organization_id,successor_version_id) REFERENCES care_plan_versions(organization_id,id),
    FOREIGN KEY (organization_id,amended_by_practitioner_id) REFERENCES practitioner_profiles(organization_id,id),
    CHECK (prior_care_plan_id<>successor_care_plan_id),
    CHECK (prior_version_id<>successor_version_id),
    CHECK (prior_version_digest~'^[0-9a-f]{64}$'),
    CHECK (char_length(btrim(amendment_summary)) BETWEEN 10 AND 2000),
    CHECK (char_length(btrim(reason)) BETWEEN 10 AND 500),
    CHECK (lock_version=0)
);

ALTER TABLE clinical_tasks
    ADD COLUMN care_plan_id uuid,
    ADD COLUMN care_plan_version_id uuid,
    ADD COLUMN intervention_assignment_id uuid,
    ADD COLUMN due_at timestamptz,
    ADD CONSTRAINT clinical_tasks_care_plan_fk
        FOREIGN KEY (organization_id,care_plan_id) REFERENCES care_plans(organization_id,id),
    ADD CONSTRAINT clinical_tasks_care_plan_version_fk
        FOREIGN KEY (organization_id,care_plan_version_id) REFERENCES care_plan_versions(organization_id,id),
    ADD CONSTRAINT clinical_tasks_intervention_assignment_fk
        FOREIGN KEY (organization_id,intervention_assignment_id) REFERENCES intervention_assignments(organization_id,id),
    ADD CONSTRAINT clinical_tasks_care_plan_provenance_check CHECK (
        (care_plan_id IS NULL AND care_plan_version_id IS NULL AND intervention_assignment_id IS NULL AND due_at IS NULL)
        OR
        (care_plan_id IS NOT NULL AND care_plan_version_id IS NOT NULL
         AND intervention_assignment_id IS NOT NULL AND due_at IS NOT NULL
         AND source_concern_id IS NULL)
    );

DO $$
DECLARE table_name text;
BEGIN
    FOREACH table_name IN ARRAY ARRAY[
        'care_plans','care_plan_versions','care_plan_priorities','care_plan_goals',
        'care_plan_interventions','intervention_assignments','plan_consents',
        'interaction_reviews','care_plan_approvals','care_plan_amendments'
    ] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',table_name);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',table_name);
        EXECUTE format(
            'CREATE POLICY %I ON %I USING (organization_id=nullif(current_setting(''app.current_organization_id'',true),'''')::uuid) WITH CHECK (organization_id=nullif(current_setting(''app.current_organization_id'',true),'''')::uuid)',
            table_name||'_tenant_policy',table_name);
    END LOOP;
END $$;

CREATE FUNCTION careos_validate_m9_tenant_write()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    configured_organization uuid:=nullif(current_setting('app.current_organization_id',true),'')::uuid;
    configured_actor uuid:=nullif(current_setting('app.current_actor_id',true),'')::uuid;
    configured_operation text:=nullif(current_setting('app.current_operation_key',true),'');
    allowed_operations text[];
BEGIN
    IF current_user<>'${applicationRole}' THEN RETURN NEW; END IF;
    allowed_operations:=CASE TG_TABLE_NAME
        WHEN 'care_plans' THEN ARRAY[
            'care-plan.create','care-plan.priority.add','care-plan.goal.add',
            'care-plan.intervention.add','care-plan.assignment.manage',
            'care-plan.consent.record','care-plan.safety.review','care-plan.submit',
            'care-plan.approve','care-plan.activate','care-plan.amend','care-plan.close']
        WHEN 'care_plan_versions' THEN ARRAY['care-plan.create','care-plan.submit','care-plan.approve','care-plan.activate','care-plan.amend','care-plan.close']
        WHEN 'care_plan_priorities' THEN ARRAY['care-plan.priority.add']
        WHEN 'care_plan_goals' THEN ARRAY['care-plan.goal.add']
        WHEN 'care_plan_interventions' THEN ARRAY['care-plan.intervention.add']
        WHEN 'intervention_assignments' THEN ARRAY['care-plan.assignment.manage']
        WHEN 'plan_consents' THEN ARRAY['care-plan.consent.record']
        WHEN 'interaction_reviews' THEN ARRAY['care-plan.safety.review']
        WHEN 'care_plan_approvals' THEN ARRAY['care-plan.approve']
        WHEN 'care_plan_amendments' THEN ARRAY['care-plan.amend']
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
              AND operation.registry_version='m9-standing-direction-v1'
              AND operation.status='active') THEN
        RAISE EXCEPTION 'invalid Module 9 operation-bound tenant write context' USING ERRCODE='42501';
    END IF;
    IF TG_OP='INSERT' THEN
        IF NEW.created_by IS DISTINCT FROM configured_actor
           OR NEW.updated_by IS DISTINCT FROM configured_actor
           OR NEW.lock_version<>0 THEN
            RAISE EXCEPTION 'invalid Module 9 creation evidence' USING ERRCODE='23514';
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
            RAISE EXCEPTION 'invalid Module 9 revision evidence' USING ERRCODE='23514';
        END IF;
    END IF;
    RETURN NEW;
END $$;

DO $$
DECLARE table_name text;
BEGIN
    FOREACH table_name IN ARRAY ARRAY[
        'care_plans','care_plan_versions','care_plan_priorities','care_plan_goals',
        'care_plan_interventions','intervention_assignments','plan_consents',
        'interaction_reviews','care_plan_approvals','care_plan_amendments'
    ] LOOP
        EXECUTE format(
            'CREATE TRIGGER %I BEFORE INSERT OR UPDATE ON %I FOR EACH ROW EXECUTE FUNCTION careos_validate_m9_tenant_write()',
            table_name||'_write_guard',table_name);
    END LOOP;
END $$;

CREATE FUNCTION careos_reject_m9_evidence_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Module 9 evidence is append-only' USING ERRCODE='42501';
END $$;

DO $$
DECLARE table_name text;
BEGIN
    FOREACH table_name IN ARRAY ARRAY[
        'care_plan_priorities','care_plan_goals','care_plan_interventions',
        'intervention_assignments','plan_consents','interaction_reviews',
        'care_plan_approvals','care_plan_amendments'
    ] LOOP
        EXECUTE format(
            'CREATE TRIGGER %I BEFORE UPDATE OR DELETE ON %I FOR EACH ROW EXECUTE FUNCTION careos_reject_m9_evidence_mutation()',
            table_name||'_immutable',table_name);
    END LOOP;
END $$;

GRANT SELECT,INSERT,UPDATE ON care_plans,care_plan_versions TO "${applicationRole}";
GRANT SELECT,INSERT ON
    care_plan_priorities,care_plan_goals,care_plan_interventions,
    intervention_assignments,plan_consents,interaction_reviews,
    care_plan_approvals,care_plan_amendments
TO "${applicationRole}";

COMMENT ON TABLE care_plan_versions IS
    'Versioned coordinated plan content; an exact digest is frozen at review submission and later clinical correction uses a successor plan.';
COMMENT ON TABLE interaction_reviews IS
    'Explicit exact-version cross-modality interaction and safety review; only a current clear outcome can support approval.';
COMMENT ON TABLE care_plan_amendments IS
    'Immutable lineage from an active plan/version to a new draft successor; prior approved content is never overwritten.';
