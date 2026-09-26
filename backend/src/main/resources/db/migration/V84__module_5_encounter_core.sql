CREATE TABLE episodes_of_care (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    patient_id uuid NOT NULL,
    service_id uuid NOT NULL,
    facility_id uuid NOT NULL,
    location_id uuid NOT NULL,
    managing_practitioner_id uuid NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'active',
    started_at timestamptz NOT NULL,
    ended_at timestamptz,
    closure_reason_code varchar(80),
    policy_version varchar(80) NOT NULL DEFAULT 'm5-standing-direction-v1',
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    UNIQUE (organization_id,id),
    FOREIGN KEY (organization_id,patient_id) REFERENCES patient_profiles(organization_id,id),
    FOREIGN KEY (organization_id,service_id) REFERENCES service_definitions(organization_id,id),
    FOREIGN KEY (organization_id,facility_id) REFERENCES facilities(organization_id,id),
    FOREIGN KEY (organization_id,location_id) REFERENCES service_locations(organization_id,id),
    FOREIGN KEY (organization_id,managing_practitioner_id) REFERENCES practitioner_profiles(organization_id,id),
    CHECK (status IN ('active','on_hold','closed','entered_in_error')),
    CHECK ((status='closed')=(ended_at IS NOT NULL)),
    CHECK (ended_at IS NULL OR ended_at>=started_at),
    CHECK (lock_version>=0)
);

CREATE TABLE encounters (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    episode_of_care_id uuid NOT NULL,
    source_appointment_id uuid,
    patient_id uuid NOT NULL,
    service_id uuid NOT NULL,
    facility_id uuid NOT NULL,
    location_id uuid NOT NULL,
    responsible_practitioner_id uuid NOT NULL,
    source_kind varchar(24) NOT NULL,
    encounter_type_key varchar(80) NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'planned',
    planned_start_at timestamptz NOT NULL,
    arrived_at timestamptz,
    in_progress_at timestamptz,
    on_hold_at timestamptz,
    completed_at timestamptz,
    cancelled_at timestamptz,
    entered_in_error_at timestamptz,
    policy_version varchar(80) NOT NULL DEFAULT 'm5-standing-direction-v1',
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    UNIQUE (organization_id,id),
    FOREIGN KEY (organization_id,episode_of_care_id) REFERENCES episodes_of_care(organization_id,id),
    FOREIGN KEY (organization_id,source_appointment_id) REFERENCES appointments(organization_id,id),
    FOREIGN KEY (organization_id,patient_id) REFERENCES patient_profiles(organization_id,id),
    FOREIGN KEY (organization_id,service_id) REFERENCES service_definitions(organization_id,id),
    FOREIGN KEY (organization_id,facility_id) REFERENCES facilities(organization_id,id),
    FOREIGN KEY (organization_id,location_id) REFERENCES service_locations(organization_id,id),
    FOREIGN KEY (organization_id,responsible_practitioner_id) REFERENCES practitioner_profiles(organization_id,id),
    CHECK (source_kind IN ('appointment','unscheduled')),
    CHECK ((source_kind='appointment')=(source_appointment_id IS NOT NULL)),
    CHECK (char_length(btrim(encounter_type_key)) BETWEEN 2 AND 80),
    CHECK (status IN ('planned','arrived','in_progress','on_hold','completed','cancelled','entered_in_error')),
    CHECK (status<>'arrived' OR arrived_at IS NOT NULL),
    CHECK (status<>'in_progress' OR in_progress_at IS NOT NULL),
    CHECK (status<>'on_hold' OR on_hold_at IS NOT NULL),
    CHECK ((status='completed')=(completed_at IS NOT NULL)),
    CHECK ((status='cancelled')=(cancelled_at IS NOT NULL)),
    CHECK ((status='entered_in_error')=(entered_in_error_at IS NOT NULL)),
    CHECK (lock_version>=0)
);

CREATE UNIQUE INDEX encounters_one_live_per_appointment_uq
    ON encounters(organization_id,source_appointment_id)
    WHERE source_appointment_id IS NOT NULL AND status<>'entered_in_error';
CREATE INDEX encounters_patient_status_idx
    ON encounters(organization_id,patient_id,status,planned_start_at DESC,id);

CREATE TABLE encounter_participants (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    encounter_id uuid NOT NULL,
    participant_type varchar(24) NOT NULL,
    patient_id uuid,
    workforce_member_id uuid,
    practitioner_profile_id uuid,
    role_key varchar(80) NOT NULL,
    display_name_snapshot varchar(200) NOT NULL,
    role_snapshot varchar(160) NOT NULL,
    assignment_id uuid,
    eligibility_evidence_id uuid,
    eligibility_digest char(64),
    registration_snapshot jsonb NOT NULL DEFAULT '{}'::jsonb,
    status varchar(24) NOT NULL DEFAULT 'active',
    added_at timestamptz NOT NULL,
    removed_at timestamptz,
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    UNIQUE (organization_id,id),
    FOREIGN KEY (organization_id,encounter_id) REFERENCES encounters(organization_id,id),
    FOREIGN KEY (organization_id,patient_id) REFERENCES patient_profiles(organization_id,id),
    FOREIGN KEY (organization_id,workforce_member_id) REFERENCES workforce_members(organization_id,id),
    FOREIGN KEY (organization_id,practitioner_profile_id) REFERENCES practitioner_profiles(organization_id,id),
    FOREIGN KEY (organization_id,assignment_id) REFERENCES practitioner_service_assignments(organization_id,id),
    FOREIGN KEY (organization_id,eligibility_evidence_id) REFERENCES practitioner_eligibility_evidence(organization_id,id),
    CHECK (participant_type IN ('patient','practitioner','support')),
    CHECK ((patient_id IS NOT NULL)::integer+(workforce_member_id IS NOT NULL)::integer BETWEEN 1 AND 1),
    CHECK ((participant_type='patient')=(patient_id IS NOT NULL)),
    CHECK (practitioner_profile_id IS NULL OR workforce_member_id IS NOT NULL),
    CHECK ((eligibility_evidence_id IS NULL)=(eligibility_digest IS NULL)),
    CHECK (eligibility_digest IS NULL OR eligibility_digest~'^[0-9a-f]{64}$'),
    CHECK (jsonb_typeof(registration_snapshot)='object' AND pg_column_size(registration_snapshot)<=16384),
    CHECK (status IN ('active','removed')),
    CHECK ((status='removed')=(removed_at IS NOT NULL)),
    CHECK (lock_version>=0)
);

CREATE UNIQUE INDEX encounter_participants_one_active_patient_uq
    ON encounter_participants(organization_id,encounter_id,patient_id)
    WHERE patient_id IS NOT NULL AND status='active';
CREATE UNIQUE INDEX encounter_participants_one_active_role_uq
    ON encounter_participants(organization_id,encounter_id,practitioner_profile_id,role_key)
    WHERE practitioner_profile_id IS NOT NULL AND status='active';

CREATE TABLE encounter_status_history (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    encounter_id uuid NOT NULL,
    from_status varchar(24),
    to_status varchar(24) NOT NULL,
    reason_code varchar(80) NOT NULL,
    policy_version varchar(80) NOT NULL,
    effective_at timestamptz NOT NULL,
    actor_id uuid NOT NULL,
    correlation_reference uuid NOT NULL,
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    UNIQUE (organization_id,id),
    FOREIGN KEY (organization_id,encounter_id) REFERENCES encounters(organization_id,id),
    CHECK (from_status IS NULL OR from_status IN ('planned','arrived','in_progress','on_hold','completed','cancelled','entered_in_error')),
    CHECK (to_status IN ('planned','arrived','in_progress','on_hold','completed','cancelled','entered_in_error')),
    CHECK (lock_version=0)
);

CREATE TABLE presenting_concerns (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    encounter_id uuid NOT NULL,
    concern_kind varchar(40) NOT NULL,
    description_text varchar(4000) NOT NULL,
    onset_text varchar(240),
    severity_key varchar(40),
    red_flag boolean NOT NULL DEFAULT false,
    author_practitioner_id uuid NOT NULL,
    recorded_at timestamptz NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'active',
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    UNIQUE (organization_id,id),
    FOREIGN KEY (organization_id,encounter_id) REFERENCES encounters(organization_id,id),
    FOREIGN KEY (organization_id,author_practitioner_id) REFERENCES practitioner_profiles(organization_id,id),
    CHECK (concern_kind IN ('presenting','symptom','referral_reason','red_flag')),
    CHECK (char_length(btrim(description_text)) BETWEEN 2 AND 4000),
    CHECK (severity_key IS NULL OR severity_key IN ('mild','moderate','severe','critical')),
    CHECK (red_flag=(concern_kind='red_flag')),
    CHECK (status IN ('active','resolved','entered_in_error')),
    CHECK (lock_version>=0)
);

CREATE TABLE clinical_problems (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    encounter_id uuid NOT NULL,
    patient_id uuid NOT NULL,
    code_system varchar(240),
    code_value varchar(120),
    display_text varchar(300) NOT NULL,
    clinical_status varchar(32) NOT NULL,
    verification_status varchar(32) NOT NULL,
    onset_at timestamptz,
    resolved_at timestamptz,
    author_practitioner_id uuid NOT NULL,
    recorded_at timestamptz NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'active',
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    UNIQUE (organization_id,id),
    FOREIGN KEY (organization_id,encounter_id) REFERENCES encounters(organization_id,id),
    FOREIGN KEY (organization_id,patient_id) REFERENCES patient_profiles(organization_id,id),
    FOREIGN KEY (organization_id,author_practitioner_id) REFERENCES practitioner_profiles(organization_id,id),
    CHECK ((code_system IS NULL)=(code_value IS NULL)),
    CHECK (char_length(btrim(display_text)) BETWEEN 2 AND 300),
    CHECK (clinical_status IN ('active','inactive','resolved')),
    CHECK (verification_status IN ('provisional','confirmed','refuted','entered_in_error')),
    CHECK (resolved_at IS NULL OR onset_at IS NULL OR resolved_at>=onset_at),
    CHECK (status IN ('active','resolved','entered_in_error')),
    CHECK (lock_version>=0)
);

CREATE TABLE diagnoses (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    encounter_id uuid NOT NULL,
    patient_id uuid NOT NULL,
    clinical_problem_id uuid,
    code_system varchar(240),
    code_value varchar(120),
    display_text varchar(300) NOT NULL,
    certainty_key varchar(32) NOT NULL,
    diagnosis_type varchar(32) NOT NULL,
    author_practitioner_id uuid NOT NULL,
    recorded_at timestamptz NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'active',
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    UNIQUE (organization_id,id),
    FOREIGN KEY (organization_id,encounter_id) REFERENCES encounters(organization_id,id),
    FOREIGN KEY (organization_id,patient_id) REFERENCES patient_profiles(organization_id,id),
    FOREIGN KEY (organization_id,clinical_problem_id) REFERENCES clinical_problems(organization_id,id),
    FOREIGN KEY (organization_id,author_practitioner_id) REFERENCES practitioner_profiles(organization_id,id),
    CHECK ((code_system IS NULL)=(code_value IS NULL)),
    CHECK (char_length(btrim(display_text)) BETWEEN 2 AND 300),
    CHECK (certainty_key IN ('suspected','provisional','confirmed','refuted')),
    CHECK (diagnosis_type IN ('working','differential','final')),
    CHECK (status IN ('active','superseded','entered_in_error')),
    CHECK (lock_version>=0)
);

CREATE TABLE encounter_notes (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    encounter_id uuid NOT NULL,
    note_type_key varchar(80) NOT NULL,
    regulated_content boolean NOT NULL DEFAULT true,
    current_version_id uuid,
    current_version_number integer NOT NULL DEFAULT 0,
    status varchar(24) NOT NULL DEFAULT 'draft',
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    UNIQUE (organization_id,id),
    FOREIGN KEY (organization_id,encounter_id) REFERENCES encounters(organization_id,id),
    CHECK (char_length(btrim(note_type_key)) BETWEEN 2 AND 80),
    CHECK (current_version_number>=0),
    CHECK ((current_version_id IS NULL)=(current_version_number=0)),
    CHECK (status IN ('draft','signed','amended','entered_in_error')),
    CHECK (lock_version>=0)
);

CREATE TABLE note_versions (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    encounter_note_id uuid NOT NULL,
    version_number integer NOT NULL,
    prior_version_id uuid,
    content_text varchar(20000) NOT NULL,
    content_digest char(64) NOT NULL,
    author_practitioner_id uuid NOT NULL,
    late_entry boolean NOT NULL DEFAULT false,
    recorded_at timestamptz NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'recorded',
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    UNIQUE (organization_id,id),
    UNIQUE (organization_id,encounter_note_id,version_number),
    FOREIGN KEY (organization_id,encounter_note_id) REFERENCES encounter_notes(organization_id,id),
    FOREIGN KEY (organization_id,prior_version_id) REFERENCES note_versions(organization_id,id),
    FOREIGN KEY (organization_id,author_practitioner_id) REFERENCES practitioner_profiles(organization_id,id),
    CHECK (version_number>0),
    CHECK (char_length(btrim(content_text)) BETWEEN 2 AND 20000),
    CHECK (content_digest~'^[0-9a-f]{64}$'),
    CHECK ((version_number=1)=(prior_version_id IS NULL)),
    CHECK (status='recorded'),
    CHECK (lock_version=0)
);

ALTER TABLE encounter_notes
    ADD CONSTRAINT encounter_notes_current_version_fk
    FOREIGN KEY (organization_id,current_version_id) REFERENCES note_versions(organization_id,id);

CREATE TABLE orders (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    encounter_id uuid NOT NULL,
    patient_id uuid NOT NULL,
    order_type_key varchar(80) NOT NULL,
    code_system varchar(240),
    code_value varchar(120),
    display_text varchar(300) NOT NULL,
    instruction_text varchar(4000),
    priority_key varchar(32) NOT NULL DEFAULT 'routine',
    requester_practitioner_id uuid NOT NULL,
    requested_at timestamptz NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'draft',
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    UNIQUE (organization_id,id),
    FOREIGN KEY (organization_id,encounter_id) REFERENCES encounters(organization_id,id),
    FOREIGN KEY (organization_id,patient_id) REFERENCES patient_profiles(organization_id,id),
    FOREIGN KEY (organization_id,requester_practitioner_id) REFERENCES practitioner_profiles(organization_id,id),
    CHECK ((code_system IS NULL)=(code_value IS NULL)),
    CHECK (char_length(btrim(display_text)) BETWEEN 2 AND 300),
    CHECK (priority_key IN ('routine','urgent','stat')),
    CHECK (status IN ('draft','active','completed','cancelled','entered_in_error')),
    CHECK (lock_version>=0)
);

CREATE TABLE clinical_tasks (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    encounter_id uuid NOT NULL,
    patient_id uuid NOT NULL,
    source_concern_id uuid,
    task_type_key varchar(80) NOT NULL,
    description_text varchar(2000) NOT NULL,
    priority_key varchar(32) NOT NULL DEFAULT 'routine',
    owner_practitioner_id uuid,
    requires_acknowledgement boolean NOT NULL DEFAULT false,
    acknowledged_by_practitioner_id uuid,
    acknowledged_at timestamptz,
    acknowledgement_reason varchar(500),
    completed_at timestamptz,
    completion_reason varchar(500),
    status varchar(24) NOT NULL DEFAULT 'open',
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    UNIQUE (organization_id,id),
    FOREIGN KEY (organization_id,encounter_id) REFERENCES encounters(organization_id,id),
    FOREIGN KEY (organization_id,patient_id) REFERENCES patient_profiles(organization_id,id),
    FOREIGN KEY (organization_id,source_concern_id) REFERENCES presenting_concerns(organization_id,id),
    FOREIGN KEY (organization_id,owner_practitioner_id) REFERENCES practitioner_profiles(organization_id,id),
    FOREIGN KEY (organization_id,acknowledged_by_practitioner_id) REFERENCES practitioner_profiles(organization_id,id),
    CHECK (char_length(btrim(description_text)) BETWEEN 2 AND 2000),
    CHECK (priority_key IN ('routine','urgent','critical')),
    CHECK ((acknowledged_by_practitioner_id IS NULL)=(acknowledged_at IS NULL)),
    CHECK (acknowledged_by_practitioner_id IS NULL OR acknowledgement_reason IS NOT NULL),
    CHECK ((status='completed')=(completed_at IS NOT NULL)),
    CHECK (status IN ('open','in_progress','acknowledged','completed','cancelled','entered_in_error')),
    CHECK (lock_version>=0)
);

CREATE TABLE encounter_signatures (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    encounter_id uuid NOT NULL,
    encounter_note_id uuid NOT NULL,
    note_version_id uuid NOT NULL,
    signer_participant_id uuid NOT NULL,
    signer_practitioner_id uuid NOT NULL,
    eligibility_evidence_id uuid NOT NULL,
    eligibility_digest char(64) NOT NULL,
    signature_meaning varchar(80) NOT NULL,
    signed_content_digest char(64) NOT NULL,
    signed_at timestamptz NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'signed',
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    UNIQUE (organization_id,id),
    UNIQUE (organization_id,note_version_id,signer_practitioner_id),
    FOREIGN KEY (organization_id,encounter_id) REFERENCES encounters(organization_id,id),
    FOREIGN KEY (organization_id,encounter_note_id) REFERENCES encounter_notes(organization_id,id),
    FOREIGN KEY (organization_id,note_version_id) REFERENCES note_versions(organization_id,id),
    FOREIGN KEY (organization_id,signer_participant_id) REFERENCES encounter_participants(organization_id,id),
    FOREIGN KEY (organization_id,signer_practitioner_id) REFERENCES practitioner_profiles(organization_id,id),
    FOREIGN KEY (organization_id,eligibility_evidence_id) REFERENCES practitioner_eligibility_evidence(organization_id,id),
    CHECK (eligibility_digest~'^[0-9a-f]{64}$'),
    CHECK (signed_content_digest~'^[0-9a-f]{64}$'),
    CHECK (signature_meaning IN ('author','reviewer','cosigner')),
    CHECK (status='signed' AND lock_version=0)
);

CREATE TABLE amendments (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    encounter_id uuid NOT NULL,
    encounter_note_id uuid NOT NULL,
    amended_note_version_id uuid NOT NULL,
    prior_signature_id uuid NOT NULL,
    author_practitioner_id uuid NOT NULL,
    eligibility_evidence_id uuid NOT NULL,
    eligibility_digest char(64) NOT NULL,
    reason_text varchar(500) NOT NULL,
    amendment_text varchar(20000) NOT NULL,
    amendment_digest char(64) NOT NULL,
    amended_at timestamptz NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'signed',
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    UNIQUE (organization_id,id),
    FOREIGN KEY (organization_id,encounter_id) REFERENCES encounters(organization_id,id),
    FOREIGN KEY (organization_id,encounter_note_id) REFERENCES encounter_notes(organization_id,id),
    FOREIGN KEY (organization_id,amended_note_version_id) REFERENCES note_versions(organization_id,id),
    FOREIGN KEY (organization_id,prior_signature_id) REFERENCES encounter_signatures(organization_id,id),
    FOREIGN KEY (organization_id,author_practitioner_id) REFERENCES practitioner_profiles(organization_id,id),
    FOREIGN KEY (organization_id,eligibility_evidence_id) REFERENCES practitioner_eligibility_evidence(organization_id,id),
    CHECK (eligibility_digest~'^[0-9a-f]{64}$'),
    CHECK (char_length(btrim(reason_text)) BETWEEN 10 AND 500),
    CHECK (char_length(btrim(amendment_text)) BETWEEN 2 AND 20000),
    CHECK (amendment_digest~'^[0-9a-f]{64}$'),
    CHECK (status='signed' AND lock_version=0)
);

CREATE TABLE red_flag_escalations (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    encounter_id uuid NOT NULL,
    patient_id uuid NOT NULL,
    presenting_concern_id uuid NOT NULL,
    clinical_task_id uuid NOT NULL,
    severity_key varchar(32) NOT NULL,
    policy_version varchar(80) NOT NULL DEFAULT 'm5-standing-direction-v1',
    raised_at timestamptz NOT NULL,
    raised_by_practitioner_id uuid NOT NULL,
    acknowledged_at timestamptz,
    acknowledged_by_practitioner_id uuid,
    acknowledgement_reason varchar(500),
    resolved_at timestamptz,
    resolved_by_practitioner_id uuid,
    resolution_reason varchar(500),
    status varchar(24) NOT NULL DEFAULT 'raised',
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    UNIQUE (organization_id,id),
    UNIQUE (organization_id,presenting_concern_id),
    UNIQUE (organization_id,clinical_task_id),
    FOREIGN KEY (organization_id,encounter_id) REFERENCES encounters(organization_id,id),
    FOREIGN KEY (organization_id,patient_id) REFERENCES patient_profiles(organization_id,id),
    FOREIGN KEY (organization_id,presenting_concern_id) REFERENCES presenting_concerns(organization_id,id),
    FOREIGN KEY (organization_id,clinical_task_id) REFERENCES clinical_tasks(organization_id,id),
    FOREIGN KEY (organization_id,raised_by_practitioner_id) REFERENCES practitioner_profiles(organization_id,id),
    FOREIGN KEY (organization_id,acknowledged_by_practitioner_id) REFERENCES practitioner_profiles(organization_id,id),
    FOREIGN KEY (organization_id,resolved_by_practitioner_id) REFERENCES practitioner_profiles(organization_id,id),
    CHECK (severity_key IN ('severe','critical')),
    CHECK ((acknowledged_at IS NULL)=(acknowledged_by_practitioner_id IS NULL)),
    CHECK (acknowledged_at IS NULL OR acknowledgement_reason IS NOT NULL),
    CHECK ((resolved_at IS NULL)=(resolved_by_practitioner_id IS NULL)),
    CHECK (resolved_at IS NULL OR resolution_reason IS NOT NULL),
    CHECK (status IN ('raised','acknowledged','resolved')),
    CHECK ((status IN ('acknowledged','resolved'))=(acknowledged_at IS NOT NULL)),
    CHECK ((status='resolved')=(resolved_at IS NOT NULL)),
    CHECK (lock_version>=0)
);

DO $$
DECLARE table_name text;
BEGIN
    FOREACH table_name IN ARRAY ARRAY[
        'episodes_of_care','encounters','encounter_participants','encounter_status_history',
        'presenting_concerns','clinical_problems','diagnoses','encounter_notes','note_versions',
        'orders','clinical_tasks','encounter_signatures','amendments','red_flag_escalations'
    ] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',table_name);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',table_name);
        EXECUTE format(
            'CREATE POLICY %I ON %I USING (organization_id=nullif(current_setting(''app.current_organization_id'',true),'''')::uuid) WITH CHECK (organization_id=nullif(current_setting(''app.current_organization_id'',true),'''')::uuid)',
            table_name||'_tenant_policy',table_name);
    END LOOP;
END $$;

CREATE FUNCTION careos_validate_m5_tenant_write()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    configured_organization uuid:=nullif(current_setting('app.current_organization_id',true),'')::uuid;
    configured_actor uuid:=nullif(current_setting('app.current_actor_id',true),'')::uuid;
    configured_operation text:=nullif(current_setting('app.current_operation_key',true),'');
    allowed_operations text[];
BEGIN
    IF current_user<>'${applicationRole}' THEN RETURN NEW; END IF;
    allowed_operations:=CASE TG_TABLE_NAME
        WHEN 'episodes_of_care' THEN ARRAY['encounter.open','encounter.lifecycle.manage']
        WHEN 'encounters' THEN ARRAY['encounter.open','encounter.lifecycle.manage']
        WHEN 'encounter_participants' THEN ARRAY['encounter.open','encounter.participant.manage']
        WHEN 'encounter_status_history' THEN ARRAY['encounter.open','encounter.lifecycle.manage']
        WHEN 'presenting_concerns' THEN ARRAY['encounter.concern.write']
        WHEN 'clinical_problems' THEN ARRAY['encounter.problem.write']
        WHEN 'diagnoses' THEN ARRAY['encounter.problem.write']
        WHEN 'encounter_notes' THEN ARRAY['encounter.note.write','encounter.note.sign','encounter.amend']
        WHEN 'note_versions' THEN ARRAY['encounter.note.write']
        WHEN 'orders' THEN ARRAY['encounter.order.manage']
        WHEN 'clinical_tasks' THEN ARRAY['encounter.concern.write','encounter.task.manage','encounter.red_flag.acknowledge']
        WHEN 'encounter_signatures' THEN ARRAY['encounter.note.sign']
        WHEN 'amendments' THEN ARRAY['encounter.amend']
        WHEN 'red_flag_escalations' THEN ARRAY['encounter.concern.write','encounter.red_flag.acknowledge']
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
              AND operation.registry_version='m5-standing-direction-v1'
              AND operation.status='active') THEN
        RAISE EXCEPTION 'invalid Module 5 operation-bound tenant write context' USING ERRCODE='42501';
    END IF;
    IF TG_OP='INSERT' THEN
        IF NEW.created_by IS DISTINCT FROM configured_actor
           OR NEW.updated_by IS DISTINCT FROM configured_actor
           OR NEW.lock_version<>0 THEN
            RAISE EXCEPTION 'invalid Module 5 creation evidence' USING ERRCODE='23514';
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
            RAISE EXCEPTION 'invalid Module 5 revision evidence' USING ERRCODE='23514';
        END IF;
    END IF;
    RETURN NEW;
END $$;

DO $$
DECLARE table_name text;
BEGIN
    FOREACH table_name IN ARRAY ARRAY[
        'episodes_of_care','encounters','encounter_participants','encounter_status_history',
        'presenting_concerns','clinical_problems','diagnoses','encounter_notes','note_versions',
        'orders','clinical_tasks','encounter_signatures','amendments','red_flag_escalations'
    ] LOOP
        EXECUTE format(
            'CREATE TRIGGER %I BEFORE INSERT OR UPDATE ON %I FOR EACH ROW EXECUTE FUNCTION careos_validate_m5_tenant_write()',
            table_name||'_write_guard',table_name);
    END LOOP;
END $$;

CREATE FUNCTION careos_reject_m5_evidence_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Module 5 evidence is append-only' USING ERRCODE='42501';
END $$;

DO $$
DECLARE table_name text;
BEGIN
    FOREACH table_name IN ARRAY ARRAY[
        'encounter_status_history','note_versions','encounter_signatures','amendments'
    ] LOOP
        EXECUTE format(
            'CREATE TRIGGER %I BEFORE UPDATE OR DELETE ON %I FOR EACH ROW EXECUTE FUNCTION careos_reject_m5_evidence_mutation()',
            table_name||'_immutable',table_name);
    END LOOP;
END $$;

GRANT SELECT,INSERT,UPDATE ON
    episodes_of_care,encounters,encounter_participants,presenting_concerns,
    clinical_problems,diagnoses,encounter_notes,orders,clinical_tasks,red_flag_escalations
TO "${applicationRole}";
GRANT SELECT,INSERT ON
    encounter_status_history,note_versions,encounter_signatures,amendments
TO "${applicationRole}";

COMMENT ON TABLE encounter_participants IS
    'Historical participant identity, role, assignment, eligibility and registration display snapshots.';
COMMENT ON TABLE note_versions IS
    'Append-only encounter note versions; signed content is corrected only by a linked amendment.';
COMMENT ON TABLE red_flag_escalations IS
    'Visible, reason-bound clinical safety escalation state; silent dismissal and deletion are unavailable.';
