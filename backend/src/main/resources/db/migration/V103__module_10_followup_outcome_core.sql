CREATE TABLE followup_plans (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    care_plan_id uuid NOT NULL,
    care_plan_version_id uuid NOT NULL,
    patient_id uuid NOT NULL,
    encounter_id uuid NOT NULL,
    responsible_practitioner_id uuid NOT NULL,
    plan_title varchar(200) NOT NULL,
    monitoring_purpose varchar(1000) NOT NULL,
    timezone varchar(64) NOT NULL,
    starts_on date NOT NULL,
    ends_on date,
    plan_digest char(64),
    frozen_at timestamptz,
    submitted_at timestamptz,
    submitted_by uuid,
    confirmed_at timestamptz,
    confirmed_by_practitioner_id uuid,
    recent_authentication_at timestamptz,
    mfa_authenticated_at timestamptz,
    confirmation_reason varchar(500),
    status varchar(24) NOT NULL DEFAULT 'draft',
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    UNIQUE (organization_id,id),
    UNIQUE (organization_id,care_plan_id),
    FOREIGN KEY (organization_id,care_plan_id) REFERENCES care_plans(organization_id,id),
    FOREIGN KEY (organization_id,care_plan_version_id) REFERENCES care_plan_versions(organization_id,id),
    FOREIGN KEY (organization_id,patient_id) REFERENCES patient_profiles(organization_id,id),
    FOREIGN KEY (organization_id,encounter_id) REFERENCES encounters(organization_id,id),
    FOREIGN KEY (organization_id,responsible_practitioner_id) REFERENCES practitioner_profiles(organization_id,id),
    FOREIGN KEY (organization_id,confirmed_by_practitioner_id) REFERENCES practitioner_profiles(organization_id,id),
    CHECK (char_length(btrim(plan_title)) BETWEEN 2 AND 200),
    CHECK (char_length(btrim(monitoring_purpose)) BETWEEN 10 AND 1000),
    CHECK (timezone~'^[A-Za-z][A-Za-z0-9_+.-]*(/[A-Za-z0-9_+.-]+)+$|^UTC$'),
    CHECK (ends_on IS NULL OR ends_on>=starts_on),
    CHECK (plan_digest IS NULL OR plan_digest~'^[0-9a-f]{64}$'),
    CHECK ((plan_digest IS NULL)=(frozen_at IS NULL)),
    CHECK ((submitted_at IS NULL)=(submitted_by IS NULL)),
    CHECK ((confirmed_at IS NULL)=(confirmed_by_practitioner_id IS NULL)),
    CHECK ((confirmed_at IS NULL)=(recent_authentication_at IS NULL)),
    CHECK ((confirmed_at IS NULL)=(mfa_authenticated_at IS NULL)),
    CHECK ((confirmed_at IS NULL)=(confirmation_reason IS NULL)),
    CHECK (confirmation_reason IS NULL OR char_length(btrim(confirmation_reason)) BETWEEN 10 AND 500),
    CHECK (status IN ('draft','review','active','completed','cancelled')),
    CHECK (status IN ('draft','cancelled') OR (plan_digest IS NOT NULL AND frozen_at IS NOT NULL)),
    CHECK (status NOT IN ('active','completed') OR confirmed_at IS NOT NULL),
    CHECK (lock_version>=0)
);

CREATE INDEX followup_plans_patient_updated_idx
    ON followup_plans(organization_id,patient_id,updated_at DESC,id);

CREATE TABLE outcome_definitions (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    followup_plan_id uuid NOT NULL,
    definition_sequence integer NOT NULL,
    domain_key varchar(80) NOT NULL,
    domain_display varchar(200) NOT NULL,
    measure_key varchar(80) NOT NULL,
    measure_display varchar(200) NOT NULL,
    unit_code varchar(80) NOT NULL,
    direction_key varchar(24) NOT NULL,
    target_lower numeric(18,6),
    target_upper numeric(18,6),
    baseline_required boolean NOT NULL DEFAULT true,
    status varchar(24) NOT NULL DEFAULT 'active',
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    UNIQUE (organization_id,id),
    UNIQUE (organization_id,followup_plan_id,definition_sequence),
    UNIQUE (organization_id,followup_plan_id,measure_key),
    FOREIGN KEY (organization_id,followup_plan_id) REFERENCES followup_plans(organization_id,id),
    CHECK (definition_sequence BETWEEN 1 AND 100),
    CHECK (domain_key~'^[a-z][a-z0-9_.:-]{1,79}$'),
    CHECK (measure_key~'^[a-z][a-z0-9_.:-]{1,79}$'),
    CHECK (char_length(btrim(domain_display)) BETWEEN 2 AND 200),
    CHECK (char_length(btrim(measure_display)) BETWEEN 2 AND 200),
    CHECK (unit_code~'^[A-Za-z0-9%][A-Za-z0-9%/_.:-]{0,79}$'),
    CHECK (direction_key IN ('increase','decrease','range','maintain')),
    CHECK (target_lower IS NOT NULL OR target_upper IS NOT NULL),
    CHECK (target_lower IS NULL OR target_upper IS NULL OR target_lower<=target_upper),
    CHECK (status='active'),
    CHECK (lock_version=0)
);

CREATE TABLE escalation_rules (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    followup_plan_id uuid NOT NULL,
    outcome_definition_id uuid NOT NULL,
    rule_sequence integer NOT NULL,
    rule_name varchar(200) NOT NULL,
    operator_key varchar(24) NOT NULL,
    threshold_lower numeric(18,6),
    threshold_upper numeric(18,6),
    severity_key varchar(24) NOT NULL,
    owner_practitioner_id uuid NOT NULL,
    task_priority_key varchar(24) NOT NULL,
    acknowledge_within_minutes integer NOT NULL,
    instruction_text varchar(2000) NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'active',
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    UNIQUE (organization_id,id),
    UNIQUE (organization_id,followup_plan_id,rule_sequence),
    FOREIGN KEY (organization_id,followup_plan_id) REFERENCES followup_plans(organization_id,id),
    FOREIGN KEY (organization_id,outcome_definition_id) REFERENCES outcome_definitions(organization_id,id),
    FOREIGN KEY (organization_id,owner_practitioner_id) REFERENCES practitioner_profiles(organization_id,id),
    CHECK (rule_sequence BETWEEN 1 AND 200),
    CHECK (char_length(btrim(rule_name)) BETWEEN 2 AND 200),
    CHECK (operator_key IN ('lt','lte','gt','gte','outside_range','inside_range')),
    CHECK (
        (operator_key IN ('lt','lte','gt','gte') AND threshold_lower IS NOT NULL AND threshold_upper IS NULL)
        OR
        (operator_key IN ('outside_range','inside_range') AND threshold_lower IS NOT NULL
         AND threshold_upper IS NOT NULL AND threshold_lower<=threshold_upper)
    ),
    CHECK (severity_key IN ('warning','critical')),
    CHECK (task_priority_key IN ('urgent','critical')),
    CHECK (acknowledge_within_minutes BETWEEN 1 AND 10080),
    CHECK (char_length(btrim(instruction_text)) BETWEEN 10 AND 2000),
    CHECK (status='active'),
    CHECK (lock_version=0)
);

CREATE TABLE followup_events (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    followup_plan_id uuid NOT NULL,
    event_sequence integer NOT NULL,
    event_type varchar(24) NOT NULL,
    scheduled_for timestamptz NOT NULL,
    due_at timestamptz NOT NULL,
    owner_practitioner_id uuid NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'scheduled',
    completed_at timestamptz,
    completed_by_practitioner_id uuid,
    completion_reason varchar(500),
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    UNIQUE (organization_id,id),
    UNIQUE (organization_id,followup_plan_id,event_sequence),
    FOREIGN KEY (organization_id,followup_plan_id) REFERENCES followup_plans(organization_id,id),
    FOREIGN KEY (organization_id,owner_practitioner_id) REFERENCES practitioner_profiles(organization_id,id),
    FOREIGN KEY (organization_id,completed_by_practitioner_id) REFERENCES practitioner_profiles(organization_id,id),
    CHECK (event_sequence BETWEEN 1 AND 1000),
    CHECK (event_type IN ('baseline','scheduled','ad_hoc')),
    CHECK (due_at>=scheduled_for),
    CHECK ((completed_at IS NULL)=(completed_by_practitioner_id IS NULL)),
    CHECK (completed_at IS NULL OR completion_reason IS NOT NULL),
    CHECK (completion_reason IS NULL OR char_length(btrim(completion_reason)) BETWEEN 10 AND 500),
    CHECK (status IN ('scheduled','due','completed','missed','cancelled')),
    CHECK ((status='completed')=(completed_at IS NOT NULL)),
    CHECK (lock_version>=0)
);

CREATE INDEX followup_events_schedule_idx
    ON followup_events(organization_id,status,scheduled_for,id);

CREATE TABLE outcome_measurements (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    followup_plan_id uuid NOT NULL,
    followup_event_id uuid NOT NULL,
    outcome_definition_id uuid NOT NULL,
    measurement_sequence integer NOT NULL,
    numeric_value numeric(18,6) NOT NULL,
    unit_code varchar(80) NOT NULL,
    observed_at timestamptz NOT NULL,
    source_key varchar(80) NOT NULL,
    method_key varchar(80) NOT NULL,
    recorded_by_practitioner_id uuid NOT NULL,
    notes_text varchar(2000),
    status varchar(24) NOT NULL DEFAULT 'final',
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    UNIQUE (organization_id,id),
    UNIQUE (organization_id,followup_event_id,outcome_definition_id,measurement_sequence),
    FOREIGN KEY (organization_id,followup_plan_id) REFERENCES followup_plans(organization_id,id),
    FOREIGN KEY (organization_id,followup_event_id) REFERENCES followup_events(organization_id,id),
    FOREIGN KEY (organization_id,outcome_definition_id) REFERENCES outcome_definitions(organization_id,id),
    FOREIGN KEY (organization_id,recorded_by_practitioner_id) REFERENCES practitioner_profiles(organization_id,id),
    CHECK (measurement_sequence BETWEEN 1 AND 100),
    CHECK (unit_code~'^[A-Za-z0-9%][A-Za-z0-9%/_.:-]{0,79}$'),
    CHECK (source_key~'^[a-z][a-z0-9_.:-]{1,79}$'),
    CHECK (method_key~'^[a-z][a-z0-9_.:-]{1,79}$'),
    CHECK (notes_text IS NULL OR char_length(btrim(notes_text)) BETWEEN 2 AND 2000),
    CHECK (status='final'),
    CHECK (lock_version=0)
);

CREATE TABLE escalation_events (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    followup_plan_id uuid NOT NULL,
    outcome_measurement_id uuid NOT NULL,
    outcome_definition_id uuid NOT NULL,
    escalation_rule_id uuid NOT NULL,
    clinical_task_id uuid NOT NULL,
    severity_key varchar(24) NOT NULL,
    observed_value numeric(18,6) NOT NULL,
    threshold_snapshot varchar(240) NOT NULL,
    owner_practitioner_id uuid NOT NULL,
    triggered_at timestamptz NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'open',
    acknowledged_at timestamptz,
    acknowledged_by_practitioner_id uuid,
    acknowledgement_reason varchar(500),
    resolved_at timestamptz,
    resolved_by_practitioner_id uuid,
    resolution_reason varchar(500),
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    UNIQUE (organization_id,id),
    UNIQUE (organization_id,outcome_measurement_id,escalation_rule_id),
    UNIQUE (organization_id,clinical_task_id),
    FOREIGN KEY (organization_id,followup_plan_id) REFERENCES followup_plans(organization_id,id),
    FOREIGN KEY (organization_id,outcome_measurement_id) REFERENCES outcome_measurements(organization_id,id),
    FOREIGN KEY (organization_id,outcome_definition_id) REFERENCES outcome_definitions(organization_id,id),
    FOREIGN KEY (organization_id,escalation_rule_id) REFERENCES escalation_rules(organization_id,id),
    FOREIGN KEY (organization_id,clinical_task_id) REFERENCES clinical_tasks(organization_id,id),
    FOREIGN KEY (organization_id,owner_practitioner_id) REFERENCES practitioner_profiles(organization_id,id),
    FOREIGN KEY (organization_id,acknowledged_by_practitioner_id) REFERENCES practitioner_profiles(organization_id,id),
    FOREIGN KEY (organization_id,resolved_by_practitioner_id) REFERENCES practitioner_profiles(organization_id,id),
    CHECK (severity_key IN ('warning','critical')),
    CHECK (char_length(btrim(threshold_snapshot)) BETWEEN 2 AND 240),
    CHECK ((acknowledged_at IS NULL)=(acknowledged_by_practitioner_id IS NULL)),
    CHECK ((acknowledged_at IS NULL)=(acknowledgement_reason IS NULL)),
    CHECK ((resolved_at IS NULL)=(resolved_by_practitioner_id IS NULL)),
    CHECK ((resolved_at IS NULL)=(resolution_reason IS NULL)),
    CHECK (acknowledgement_reason IS NULL OR char_length(btrim(acknowledgement_reason)) BETWEEN 10 AND 500),
    CHECK (resolution_reason IS NULL OR char_length(btrim(resolution_reason)) BETWEEN 10 AND 500),
    CHECK (resolved_at IS NULL OR (acknowledged_at IS NOT NULL AND resolved_at>=acknowledged_at)),
    CHECK (status IN ('open','acknowledged','resolved')),
    CHECK ((status='open')=(acknowledged_at IS NULL)),
    CHECK ((status='resolved')=(resolved_at IS NOT NULL)),
    CHECK (lock_version>=0)
);

CREATE TABLE interpretations (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    followup_plan_id uuid NOT NULL,
    followup_event_id uuid NOT NULL,
    outcome_measurement_id uuid NOT NULL,
    interpretation_sequence integer NOT NULL,
    trend_key varchar(24) NOT NULL,
    interpretation_text varchar(4000) NOT NULL,
    recommendation_text varchar(2000) NOT NULL,
    interpreted_by_practitioner_id uuid NOT NULL,
    interpreted_at timestamptz NOT NULL,
    reason varchar(500) NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'final',
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    UNIQUE (organization_id,id),
    UNIQUE (organization_id,followup_plan_id,interpretation_sequence),
    FOREIGN KEY (organization_id,followup_plan_id) REFERENCES followup_plans(organization_id,id),
    FOREIGN KEY (organization_id,followup_event_id) REFERENCES followup_events(organization_id,id),
    FOREIGN KEY (organization_id,outcome_measurement_id) REFERENCES outcome_measurements(organization_id,id),
    FOREIGN KEY (organization_id,interpreted_by_practitioner_id) REFERENCES practitioner_profiles(organization_id,id),
    CHECK (interpretation_sequence BETWEEN 1 AND 1000),
    CHECK (trend_key IN ('improving','stable','worsening','mixed','insufficient_data')),
    CHECK (char_length(btrim(interpretation_text)) BETWEEN 10 AND 4000),
    CHECK (char_length(btrim(recommendation_text)) BETWEEN 10 AND 2000),
    CHECK (char_length(btrim(reason)) BETWEEN 10 AND 500),
    CHECK (status='final'),
    CHECK (lock_version=0)
);

ALTER TABLE clinical_tasks DROP CONSTRAINT clinical_tasks_care_plan_provenance_check;
ALTER TABLE clinical_tasks
    ADD COLUMN followup_plan_id uuid,
    ADD COLUMN outcome_measurement_id uuid,
    ADD COLUMN escalation_rule_id uuid,
    ADD CONSTRAINT clinical_tasks_followup_plan_fk
        FOREIGN KEY (organization_id,followup_plan_id) REFERENCES followup_plans(organization_id,id),
    ADD CONSTRAINT clinical_tasks_outcome_measurement_fk
        FOREIGN KEY (organization_id,outcome_measurement_id) REFERENCES outcome_measurements(organization_id,id),
    ADD CONSTRAINT clinical_tasks_escalation_rule_fk
        FOREIGN KEY (organization_id,escalation_rule_id) REFERENCES escalation_rules(organization_id,id),
    ADD CONSTRAINT clinical_tasks_clinical_provenance_check CHECK (
        (care_plan_id IS NULL AND care_plan_version_id IS NULL
         AND intervention_assignment_id IS NULL AND due_at IS NULL
         AND followup_plan_id IS NULL AND outcome_measurement_id IS NULL
         AND escalation_rule_id IS NULL)
        OR
        (care_plan_id IS NOT NULL AND care_plan_version_id IS NOT NULL
         AND intervention_assignment_id IS NOT NULL AND due_at IS NOT NULL
         AND source_concern_id IS NULL AND followup_plan_id IS NULL
         AND outcome_measurement_id IS NULL AND escalation_rule_id IS NULL)
        OR
        (care_plan_id IS NULL AND care_plan_version_id IS NULL
         AND intervention_assignment_id IS NULL AND due_at IS NOT NULL
         AND source_concern_id IS NULL AND followup_plan_id IS NOT NULL
         AND outcome_measurement_id IS NOT NULL AND escalation_rule_id IS NOT NULL)
    );

DO $$
DECLARE table_name text;
BEGIN
    FOREACH table_name IN ARRAY ARRAY[
        'followup_plans','followup_events','outcome_definitions','outcome_measurements',
        'escalation_rules','escalation_events','interpretations'
    ] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',table_name);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',table_name);
        EXECUTE format(
            'CREATE POLICY %I ON %I USING (organization_id=nullif(current_setting(''app.current_organization_id'',true),'''')::uuid) WITH CHECK (organization_id=nullif(current_setting(''app.current_organization_id'',true),'''')::uuid)',
            table_name||'_tenant_policy',table_name);
    END LOOP;
END $$;

CREATE FUNCTION careos_validate_m10_tenant_write()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    configured_organization uuid:=nullif(current_setting('app.current_organization_id',true),'')::uuid;
    configured_actor uuid:=nullif(current_setting('app.current_actor_id',true),'')::uuid;
    configured_operation text:=nullif(current_setting('app.current_operation_key',true),'');
    allowed_operations text[];
BEGIN
    IF current_user<>'${applicationRole}' THEN RETURN NEW; END IF;
    allowed_operations:=CASE TG_TABLE_NAME
        WHEN 'followup_plans' THEN ARRAY[
            'followup.plan.create','followup.domain.add','followup.rule.add',
            'followup.schedule.manage','followup.measure.record',
            'followup.escalation.acknowledge','followup.escalation.resolve',
            'followup.interpretation.record','followup.submit','followup.confirm','followup.close']
        WHEN 'outcome_definitions' THEN ARRAY['followup.domain.add']
        WHEN 'escalation_rules' THEN ARRAY['followup.rule.add']
        WHEN 'followup_events' THEN ARRAY['followup.schedule.manage','followup.measure.record']
        WHEN 'outcome_measurements' THEN ARRAY['followup.measure.record']
        WHEN 'escalation_events' THEN ARRAY[
            'followup.measure.record','followup.escalation.acknowledge','followup.escalation.resolve']
        WHEN 'interpretations' THEN ARRAY['followup.interpretation.record']
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
              AND operation.registry_version='m10-standing-direction-v1'
              AND operation.status='active') THEN
        RAISE EXCEPTION 'invalid Module 10 operation-bound tenant write context' USING ERRCODE='42501';
    END IF;
    IF TG_OP='INSERT' THEN
        IF NEW.created_by IS DISTINCT FROM configured_actor
           OR NEW.updated_by IS DISTINCT FROM configured_actor OR NEW.lock_version<>0 THEN
            RAISE EXCEPTION 'invalid Module 10 creation evidence' USING ERRCODE='23514';
        END IF;
    ELSE
        IF NEW.id IS DISTINCT FROM OLD.id OR NEW.organization_id IS DISTINCT FROM OLD.organization_id
           OR NEW.created_at IS DISTINCT FROM OLD.created_at OR NEW.created_by IS DISTINCT FROM OLD.created_by
           OR NEW.updated_by IS DISTINCT FROM configured_actor OR NEW.lock_version<>OLD.lock_version+1
           OR NEW.updated_at<OLD.updated_at OR NEW.updated_at>clock_timestamp()+interval '5 seconds' THEN
            RAISE EXCEPTION 'invalid Module 10 revision evidence' USING ERRCODE='23514';
        END IF;
    END IF;
    RETURN NEW;
END $$;

DO $$
DECLARE table_name text;
BEGIN
    FOREACH table_name IN ARRAY ARRAY[
        'followup_plans','followup_events','outcome_definitions','outcome_measurements',
        'escalation_rules','escalation_events','interpretations'
    ] LOOP
        EXECUTE format(
            'CREATE TRIGGER %I BEFORE INSERT OR UPDATE ON %I FOR EACH ROW EXECUTE FUNCTION careos_validate_m10_tenant_write()',
            table_name||'_write_guard',table_name);
    END LOOP;
END $$;

CREATE FUNCTION careos_reject_m10_evidence_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Module 10 evidence is append-only' USING ERRCODE='42501';
END $$;

DO $$
DECLARE table_name text;
BEGIN
    FOREACH table_name IN ARRAY ARRAY[
        'outcome_definitions','outcome_measurements','escalation_rules','interpretations'
    ] LOOP
        EXECUTE format(
            'CREATE TRIGGER %I BEFORE UPDATE OR DELETE ON %I FOR EACH ROW EXECUTE FUNCTION careos_reject_m10_evidence_mutation()',
            table_name||'_immutable',table_name);
    END LOOP;
END $$;

GRANT SELECT,INSERT,UPDATE ON followup_plans,followup_events,escalation_events TO "${applicationRole}";
GRANT SELECT,INSERT ON outcome_definitions,outcome_measurements,escalation_rules,interpretations TO "${applicationRole}";

COMMENT ON TABLE followup_plans IS
    'Exact active-care-plan-bound outcome monitoring plan; submission freezes a digest and confirmation requires recent MFA.';
COMMENT ON TABLE outcome_measurements IS
    'Append-only attributed outcome value with exact definition, event, unit, source and method provenance.';
COMMENT ON TABLE escalation_events IS
    'One threshold breach with an owned clinical task and attributable one-way acknowledgement/resolution evidence.';
