CREATE TABLE workforce_registry_definitions (
    id uuid PRIMARY KEY DEFAULT uuidv7(), organization_id uuid NOT NULL REFERENCES organizations(id),
    registry_key varchar(80) NOT NULL, category varchar(48) NOT NULL,
    display_name varchar(160) NOT NULL, owner_membership_id uuid,
    value_schema jsonb NOT NULL, review_cadence_days integer NOT NULL,
    lifecycle_state varchar(24) NOT NULL DEFAULT 'draft', status varchar(24) NOT NULL DEFAULT 'draft',
    lock_version bigint NOT NULL DEFAULT 0, created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL, updated_at timestamptz NOT NULL DEFAULT clock_timestamp(), updated_by uuid NOT NULL,
    UNIQUE (organization_id,id), UNIQUE (organization_id,registry_key),
    FOREIGN KEY (organization_id,owner_membership_id) REFERENCES organization_memberships(organization_id,id),
    CHECK (registry_key ~ '^[a-z][a-z0-9_]*([.:-][a-z0-9_]+)*$'),
    CHECK (category IN ('profession','specialty','qualification_type','regulator','registration_type','credential_type','credential_risk_tier','scope_activity','scope_restriction','scope_requirement','employment_category','assignment_type','position','supervision_mode','offboarding_reason','notification_milestone','notification_template_metadata')),
    CHECK (char_length(btrim(display_name)) BETWEEN 2 AND 160),
    CHECK (jsonb_typeof(value_schema)='object' AND pg_column_size(value_schema)<=65536),
    CHECK (review_cadence_days BETWEEN 30 AND 3660),
    CHECK (lifecycle_state IN ('draft','active','retired') AND status=lifecycle_state), CHECK(lock_version>=0)
);

CREATE TABLE workforce_registry_entries (
    id uuid PRIMARY KEY DEFAULT uuidv7(), organization_id uuid NOT NULL REFERENCES organizations(id),
    registry_definition_id uuid NOT NULL, entry_key varchar(100) NOT NULL, code varchar(80) NOT NULL,
    display_label varchar(180) NOT NULL, jurisdiction_country char(2), context_key varchar(120),
    lifecycle_state varchar(24) NOT NULL DEFAULT 'draft', status varchar(24) NOT NULL DEFAULT 'draft',
    lock_version bigint NOT NULL DEFAULT 0, created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL, updated_at timestamptz NOT NULL DEFAULT clock_timestamp(), updated_by uuid NOT NULL,
    UNIQUE(organization_id,id), UNIQUE(organization_id,registry_definition_id,entry_key),
    FOREIGN KEY(organization_id,registry_definition_id) REFERENCES workforce_registry_definitions(organization_id,id),
    CHECK(entry_key ~ '^[a-z][a-z0-9_]*([.:-][a-z0-9_]+)*$'),
    CHECK(code ~ '^[A-Z0-9][A-Z0-9_.:-]{0,79}$'),
    CHECK(char_length(btrim(display_label)) BETWEEN 1 AND 180),
    CHECK(jurisdiction_country IS NULL OR jurisdiction_country ~ '^[A-Z]{2}$'),
    CHECK(lifecycle_state IN ('draft','active','retired') AND status=lifecycle_state), CHECK(lock_version>=0)
);

CREATE TABLE workforce_registry_versions (
    id uuid PRIMARY KEY DEFAULT uuidv7(), organization_id uuid NOT NULL REFERENCES organizations(id),
    registry_entry_id uuid NOT NULL, version_number integer NOT NULL, version_fields jsonb NOT NULL,
    version_digest char(64) NOT NULL, effective_from timestamptz NOT NULL, effective_to timestamptz,
    maker_id uuid NOT NULL, checker_id uuid, decision_code varchar(80),
    activated_at timestamptz, superseded_at timestamptz,
    lifecycle_state varchar(24) NOT NULL DEFAULT 'draft', status varchar(24) NOT NULL DEFAULT 'draft',
    lock_version bigint NOT NULL DEFAULT 0, created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL, updated_at timestamptz NOT NULL DEFAULT clock_timestamp(), updated_by uuid NOT NULL,
    UNIQUE(organization_id,id), UNIQUE(organization_id,registry_entry_id,id),
    UNIQUE(organization_id,registry_entry_id,version_number),
    FOREIGN KEY(organization_id,registry_entry_id) REFERENCES workforce_registry_entries(organization_id,id),
    CHECK(version_number>0), CHECK(jsonb_typeof(version_fields)='object' AND pg_column_size(version_fields)<=65536),
    CHECK(version_digest ~ '^[0-9a-f]{64}$'), CHECK(effective_to IS NULL OR effective_to>effective_from),
    CHECK(checker_id IS NULL OR checker_id<>maker_id),
    CHECK(lifecycle_state IN ('draft','submitted','approved','active','superseded','rejected','cancelled') AND status=lifecycle_state),
    CHECK((lifecycle_state IN ('approved','active','superseded','rejected'))=(checker_id IS NOT NULL)), CHECK(lock_version>=0)
);

CREATE TABLE scope_definitions (
    id uuid PRIMARY KEY DEFAULT uuidv7(), organization_id uuid NOT NULL REFERENCES organizations(id),
    definition_code varchar(48) NOT NULL, name varchar(180) NOT NULL,
    profession_entry_id uuid NOT NULL, profession_version_id uuid NOT NULL,
    specialty_entry_id uuid, specialty_version_id uuid, service_id uuid,
    jurisdiction_country char(2) NOT NULL, jurisdiction_region varchar(100), owner_membership_id uuid,
    effective_from timestamptz NOT NULL, effective_to timestamptz,
    lifecycle_state varchar(24) NOT NULL DEFAULT 'draft', status varchar(24) NOT NULL DEFAULT 'draft',
    lock_version bigint NOT NULL DEFAULT 0, created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL, updated_at timestamptz NOT NULL DEFAULT clock_timestamp(), updated_by uuid NOT NULL,
    UNIQUE(organization_id,id), UNIQUE(organization_id,definition_code),
    FOREIGN KEY(organization_id,profession_entry_id,profession_version_id) REFERENCES workforce_registry_versions(organization_id,registry_entry_id,id),
    FOREIGN KEY(organization_id,specialty_entry_id,specialty_version_id) REFERENCES workforce_registry_versions(organization_id,registry_entry_id,id),
    FOREIGN KEY(organization_id,service_id) REFERENCES service_definitions(organization_id,id),
    FOREIGN KEY(organization_id,owner_membership_id) REFERENCES organization_memberships(organization_id,id),
    CHECK(definition_code ~ '^[A-Z0-9][A-Z0-9_-]{2,47}$'), CHECK(char_length(btrim(name)) BETWEEN 2 AND 180),
    CHECK((specialty_entry_id IS NULL)=(specialty_version_id IS NULL)), CHECK(jurisdiction_country ~ '^[A-Z]{2}$'),
    CHECK(effective_to IS NULL OR effective_to>effective_from),
    CHECK(lifecycle_state IN ('draft','active','retired') AND status=lifecycle_state), CHECK(lock_version>=0)
);

CREATE TABLE scope_requirements (
    id uuid PRIMARY KEY DEFAULT uuidv7(), organization_id uuid NOT NULL REFERENCES organizations(id),
    scope_definition_id uuid NOT NULL, requirement_order integer NOT NULL, requirement_type varchar(32) NOT NULL,
    registry_entry_id uuid, registry_version_id uuid, mandatory boolean NOT NULL DEFAULT true,
    validity_window_days integer, evidence_rule jsonb NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'draft', lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(), created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(), updated_by uuid NOT NULL,
    UNIQUE(organization_id,id), UNIQUE(organization_id,scope_definition_id,requirement_order),
    FOREIGN KEY(organization_id,scope_definition_id) REFERENCES scope_definitions(organization_id,id),
    FOREIGN KEY(organization_id,registry_entry_id,registry_version_id) REFERENCES workforce_registry_versions(organization_id,registry_entry_id,id),
    CHECK(requirement_order BETWEEN 1 AND 100),
    CHECK(requirement_type IN ('registration','qualification','credential','specialty','supervision','training')),
    CHECK((registry_entry_id IS NULL)=(registry_version_id IS NULL)), CHECK(validity_window_days IS NULL OR validity_window_days BETWEEN 1 AND 3660),
    CHECK(jsonb_typeof(evidence_rule)='object' AND pg_column_size(evidence_rule)<=16384),
    CHECK(status IN ('draft','active','retired')), CHECK(lock_version>=0)
);

CREATE TABLE scopes_of_practice (
    id uuid PRIMARY KEY DEFAULT uuidv7(), organization_id uuid NOT NULL REFERENCES organizations(id),
    practitioner_profile_id uuid NOT NULL, scope_definition_id uuid NOT NULL,
    definition_version_digest char(64) NOT NULL, effective_from timestamptz NOT NULL, effective_to timestamptz,
    submitted_revision bigint, result_digest char(64), submitted_by uuid, submitted_at timestamptz,
    decided_by uuid, decision_code varchar(80), decided_at timestamptz,
    suspension_reason_code varchar(80), end_reason_code varchar(80), supersedes_id uuid,
    lifecycle_state varchar(32) NOT NULL DEFAULT 'draft', status varchar(32) NOT NULL DEFAULT 'draft',
    lock_version bigint NOT NULL DEFAULT 0, created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL, updated_at timestamptz NOT NULL DEFAULT clock_timestamp(), updated_by uuid NOT NULL,
    UNIQUE(organization_id,id),
    FOREIGN KEY(organization_id,practitioner_profile_id) REFERENCES practitioner_profiles(organization_id,id),
    FOREIGN KEY(organization_id,scope_definition_id) REFERENCES scope_definitions(organization_id,id),
    FOREIGN KEY(organization_id,supersedes_id) REFERENCES scopes_of_practice(organization_id,id),
    CHECK(definition_version_digest ~ '^[0-9a-f]{64}$'), CHECK(result_digest IS NULL OR result_digest ~ '^[0-9a-f]{64}$'),
    CHECK(effective_to IS NULL OR effective_to>effective_from), CHECK(decided_by IS NULL OR decided_by<>submitted_by),
    CHECK(lifecycle_state IN ('draft','submitted','in_review','approved','rejected','changes_requested','suspended','superseded','ended') AND status=lifecycle_state),
    CHECK(supersedes_id IS NULL OR supersedes_id<>id), CHECK(lock_version>=0)
);

ALTER TABLE scopes_of_practice
    ADD CONSTRAINT scopes_of_practice_active_range_excl
    EXCLUDE USING gist (organization_id WITH =, practitioner_profile_id WITH =, scope_definition_id WITH =,
        tstzrange(effective_from,effective_to,'[)') WITH &&)
    WHERE (lifecycle_state IN ('approved','suspended'));

CREATE TABLE scope_activities (
    id uuid PRIMARY KEY DEFAULT uuidv7(), organization_id uuid NOT NULL REFERENCES organizations(id),
    scope_of_practice_id uuid NOT NULL, activity_entry_id uuid NOT NULL, activity_version_id uuid NOT NULL,
    service_id uuid, facility_id uuid, location_id uuid, supervision_mode_entry_id uuid,
    supervision_mode_version_id uuid, effective_from timestamptz NOT NULL, effective_to timestamptz,
    status varchar(24) NOT NULL DEFAULT 'active', lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(), created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(), updated_by uuid NOT NULL,
    UNIQUE(organization_id,id),
    FOREIGN KEY(organization_id,scope_of_practice_id) REFERENCES scopes_of_practice(organization_id,id),
    FOREIGN KEY(organization_id,activity_entry_id,activity_version_id) REFERENCES workforce_registry_versions(organization_id,registry_entry_id,id),
    FOREIGN KEY(organization_id,supervision_mode_entry_id,supervision_mode_version_id) REFERENCES workforce_registry_versions(organization_id,registry_entry_id,id),
    FOREIGN KEY(organization_id,service_id) REFERENCES service_definitions(organization_id,id),
    FOREIGN KEY(organization_id,facility_id) REFERENCES facilities(organization_id,id),
    FOREIGN KEY(organization_id,location_id) REFERENCES service_locations(organization_id,id),
    CHECK((supervision_mode_entry_id IS NULL)=(supervision_mode_version_id IS NULL)),
    CHECK(effective_to IS NULL OR effective_to>effective_from), CHECK(status IN ('active','ended')), CHECK(lock_version>=0)
);

CREATE TABLE scope_restrictions (
    id uuid PRIMARY KEY DEFAULT uuidv7(), organization_id uuid NOT NULL REFERENCES organizations(id),
    scope_of_practice_id uuid NOT NULL, restriction_entry_id uuid NOT NULL, restriction_version_id uuid NOT NULL,
    display_text varchar(500) NOT NULL, supervision_constraint jsonb, setting_constraint jsonb,
    volume_constraint jsonb, imposed_decision_id uuid NOT NULL, released_decision_id uuid,
    effective_from timestamptz NOT NULL, effective_to timestamptz,
    status varchar(24) NOT NULL DEFAULT 'active', lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(), created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(), updated_by uuid NOT NULL,
    UNIQUE(organization_id,id),
    FOREIGN KEY(organization_id,scope_of_practice_id) REFERENCES scopes_of_practice(organization_id,id),
    FOREIGN KEY(organization_id,restriction_entry_id,restriction_version_id) REFERENCES workforce_registry_versions(organization_id,registry_entry_id,id),
    CHECK(char_length(btrim(display_text)) BETWEEN 2 AND 500), CHECK(effective_to IS NULL OR effective_to>effective_from),
    CHECK(status IN ('active','released')), CHECK((status='released')=(released_decision_id IS NOT NULL)), CHECK(lock_version>=0)
);

CREATE TABLE workforce_assignments (
    id uuid PRIMARY KEY DEFAULT uuidv7(), organization_id uuid NOT NULL REFERENCES organizations(id),
    workforce_member_id uuid NOT NULL, facility_id uuid NOT NULL, organization_unit_id uuid,
    location_id uuid, assignment_type_entry_id uuid NOT NULL, assignment_type_version_id uuid NOT NULL,
    position_entry_id uuid, position_version_id uuid, primary_assignment boolean NOT NULL DEFAULT false,
    effective_from timestamptz NOT NULL, effective_to timestamptz,
    predecessor_id uuid, successor_id uuid, lifecycle_state varchar(24) NOT NULL DEFAULT 'draft',
    status varchar(24) NOT NULL DEFAULT 'draft', lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(), created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(), updated_by uuid NOT NULL,
    UNIQUE(organization_id,id),
    FOREIGN KEY(organization_id,workforce_member_id) REFERENCES workforce_members(organization_id,id),
    FOREIGN KEY(organization_id,facility_id) REFERENCES facilities(organization_id,id),
    FOREIGN KEY(organization_id,organization_unit_id) REFERENCES organization_units(organization_id,id),
    FOREIGN KEY(organization_id,location_id) REFERENCES service_locations(organization_id,id),
    FOREIGN KEY(organization_id,assignment_type_entry_id,assignment_type_version_id) REFERENCES workforce_registry_versions(organization_id,registry_entry_id,id),
    FOREIGN KEY(organization_id,position_entry_id,position_version_id) REFERENCES workforce_registry_versions(organization_id,registry_entry_id,id),
    FOREIGN KEY(organization_id,predecessor_id) REFERENCES workforce_assignments(organization_id,id),
    FOREIGN KEY(organization_id,successor_id) REFERENCES workforce_assignments(organization_id,id),
    CHECK((position_entry_id IS NULL)=(position_version_id IS NULL)), CHECK(effective_to IS NULL OR effective_to>effective_from),
    CHECK(lifecycle_state IN ('draft','scheduled','active','suspended','ended','cancelled') AND status=lifecycle_state),
    CHECK(predecessor_id IS NULL OR predecessor_id<>id), CHECK(successor_id IS NULL OR successor_id<>id), CHECK(lock_version>=0)
);

ALTER TABLE workforce_assignments
    ADD CONSTRAINT workforce_assignments_primary_range_excl
    EXCLUDE USING gist (organization_id WITH =, workforce_member_id WITH =,
        tstzrange(effective_from,effective_to,'[)') WITH &&)
    WHERE (primary_assignment AND lifecycle_state IN ('scheduled','active'));

CREATE TABLE practitioner_service_assignments (
    id uuid PRIMARY KEY DEFAULT uuidv7(), organization_id uuid NOT NULL REFERENCES organizations(id),
    practitioner_profile_id uuid NOT NULL, service_id uuid NOT NULL, facility_id uuid NOT NULL,
    location_id uuid, scope_of_practice_id uuid NOT NULL, supervisor_practitioner_id uuid,
    effective_from timestamptz NOT NULL, effective_to timestamptz,
    eligibility_evidence_id uuid, eligibility_digest char(64),
    lifecycle_state varchar(24) NOT NULL DEFAULT 'draft', status varchar(24) NOT NULL DEFAULT 'draft',
    lock_version bigint NOT NULL DEFAULT 0, created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL, updated_at timestamptz NOT NULL DEFAULT clock_timestamp(), updated_by uuid NOT NULL,
    UNIQUE(organization_id,id),
    FOREIGN KEY(organization_id,practitioner_profile_id) REFERENCES practitioner_profiles(organization_id,id),
    FOREIGN KEY(organization_id,service_id) REFERENCES service_definitions(organization_id,id),
    FOREIGN KEY(organization_id,facility_id) REFERENCES facilities(organization_id,id),
    FOREIGN KEY(organization_id,location_id) REFERENCES service_locations(organization_id,id),
    FOREIGN KEY(organization_id,scope_of_practice_id) REFERENCES scopes_of_practice(organization_id,id),
    FOREIGN KEY(organization_id,supervisor_practitioner_id) REFERENCES practitioner_profiles(organization_id,id),
    CHECK(supervisor_practitioner_id IS NULL OR supervisor_practitioner_id<>practitioner_profile_id),
    CHECK(effective_to IS NULL OR effective_to>effective_from), CHECK(eligibility_digest IS NULL OR eligibility_digest ~ '^[0-9a-f]{64}$'),
    CHECK(lifecycle_state IN ('draft','scheduled','active','suspended','ended','cancelled') AND status=lifecycle_state), CHECK(lock_version>=0)
);

CREATE TABLE availability_profiles (
    id uuid PRIMARY KEY DEFAULT uuidv7(), organization_id uuid NOT NULL REFERENCES organizations(id),
    workforce_member_id uuid NOT NULL, facility_id uuid, timezone varchar(80) NOT NULL,
    profile_version integer NOT NULL, batch_revision bigint NOT NULL, not_required boolean NOT NULL DEFAULT false,
    effective_from timestamptz NOT NULL, effective_to timestamptz,
    lifecycle_state varchar(24) NOT NULL DEFAULT 'draft', status varchar(24) NOT NULL DEFAULT 'draft',
    lock_version bigint NOT NULL DEFAULT 0, created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL, updated_at timestamptz NOT NULL DEFAULT clock_timestamp(), updated_by uuid NOT NULL,
    UNIQUE(organization_id,id), UNIQUE(organization_id,workforce_member_id,profile_version),
    FOREIGN KEY(organization_id,workforce_member_id) REFERENCES workforce_members(organization_id,id),
    FOREIGN KEY(organization_id,facility_id) REFERENCES facilities(organization_id,id),
    CHECK(timezone ~ '^[A-Za-z_]+(?:/[A-Za-z0-9_+.-]+)+$'), CHECK(profile_version>0 AND batch_revision>=0),
    CHECK(effective_to IS NULL OR effective_to>effective_from),
    CHECK(lifecycle_state IN ('draft','scheduled','active','superseded','cancelled') AND status=lifecycle_state), CHECK(lock_version>=0)
);

CREATE TABLE availability_periods (
    id uuid PRIMARY KEY DEFAULT uuidv7(), organization_id uuid NOT NULL REFERENCES organizations(id),
    availability_profile_id uuid NOT NULL, iso_weekday smallint NOT NULL,
    local_start_minute smallint NOT NULL, local_end_minute smallint NOT NULL,
    ends_next_day boolean NOT NULL DEFAULT false, location_id uuid, availability_type_entry_id uuid,
    status varchar(24) NOT NULL DEFAULT 'active', lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(), created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(), updated_by uuid NOT NULL,
    UNIQUE(organization_id,id),
    FOREIGN KEY(organization_id,availability_profile_id) REFERENCES availability_profiles(organization_id,id),
    FOREIGN KEY(organization_id,location_id) REFERENCES service_locations(organization_id,id),
    CHECK(iso_weekday BETWEEN 1 AND 7), CHECK(local_start_minute BETWEEN 0 AND 1439),
    CHECK(local_end_minute BETWEEN 0 AND 1439), CHECK((ends_next_day AND local_end_minute<=local_start_minute) OR (NOT ends_next_day AND local_end_minute>local_start_minute)),
    CHECK(status IN ('active','cancelled')), CHECK(lock_version>=0)
);

CREATE TABLE availability_exceptions (
    id uuid PRIMARY KEY DEFAULT uuidv7(), organization_id uuid NOT NULL REFERENCES organizations(id),
    availability_profile_id uuid NOT NULL, local_start_date date NOT NULL, local_end_date date NOT NULL,
    exception_type varchar(24) NOT NULL, replacement_intervals jsonb, reason_code varchar(80) NOT NULL,
    timezone_snapshot varchar(80) NOT NULL, explicit_offset_minutes smallint,
    status varchar(24) NOT NULL DEFAULT 'active', lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(), created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(), updated_by uuid NOT NULL,
    UNIQUE(organization_id,id),
    FOREIGN KEY(organization_id,availability_profile_id) REFERENCES availability_profiles(organization_id,id),
    CHECK(local_end_date>=local_start_date), CHECK(exception_type IN ('unavailable','replacement')),
    CHECK((exception_type='replacement')=(replacement_intervals IS NOT NULL)),
    CHECK(replacement_intervals IS NULL OR (jsonb_typeof(replacement_intervals)='array' AND pg_column_size(replacement_intervals)<=16384)),
    CHECK(timezone_snapshot ~ '^[A-Za-z_]+(?:/[A-Za-z0-9_+.-]+)+$'),
    CHECK(explicit_offset_minutes IS NULL), CHECK(status IN ('active','cancelled')), CHECK(lock_version>=0)
);

CREATE TABLE access_assignment_scopes (
    id uuid PRIMARY KEY DEFAULT uuidv7(), organization_id uuid NOT NULL REFERENCES organizations(id),
    access_assignment_id uuid NOT NULL, workforce_member_id uuid NOT NULL,
    facility_id uuid, organization_unit_id uuid, location_id uuid,
    grant_request_id uuid NOT NULL, approval_reference_id uuid,
    effective_from timestamptz NOT NULL, effective_to timestamptz,
    status varchar(24) NOT NULL DEFAULT 'requested', lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(), created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(), updated_by uuid NOT NULL,
    UNIQUE(organization_id,id),
    FOREIGN KEY(organization_id,access_assignment_id) REFERENCES organization_memberships(organization_id,id),
    FOREIGN KEY(organization_id,workforce_member_id) REFERENCES workforce_members(organization_id,id),
    FOREIGN KEY(organization_id,facility_id) REFERENCES facilities(organization_id,id),
    FOREIGN KEY(organization_id,organization_unit_id) REFERENCES organization_units(organization_id,id),
    FOREIGN KEY(organization_id,location_id) REFERENCES service_locations(organization_id,id),
    CHECK(effective_to IS NULL OR effective_to>effective_from), CHECK(status IN ('requested','approved','active','ended','cancelled')), CHECK(lock_version>=0)
);

CREATE TABLE workforce_readiness_runs (
    id uuid PRIMARY KEY DEFAULT uuidv7(), organization_id uuid NOT NULL REFERENCES organizations(id),
    workforce_member_id uuid NOT NULL, pathway varchar(24) NOT NULL, member_revision bigint NOT NULL,
    member_digest char(64) NOT NULL, configuration_revision bigint NOT NULL, configuration_digest char(64) NOT NULL,
    gate_catalogue_version varchar(80) NOT NULL DEFAULT 'm2-readiness-v1',
    requested_at timestamptz NOT NULL, completed_at timestamptz, expires_at timestamptz,
    blocker_count integer, warning_count integer, result_digest char(64), failure_code varchar(120),
    status varchar(24) NOT NULL DEFAULT 'requested', lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(), created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(), updated_by uuid NOT NULL,
    UNIQUE(organization_id,id),
    FOREIGN KEY(organization_id,workforce_member_id) REFERENCES workforce_members(organization_id,id),
    CHECK(pathway IN ('clinical','non_clinical')), CHECK(member_revision>=0 AND configuration_revision>=0),
    CHECK(member_digest ~ '^[0-9a-f]{64}$' AND configuration_digest ~ '^[0-9a-f]{64}$'),
    CHECK(result_digest IS NULL OR result_digest ~ '^[0-9a-f]{64}$'), CHECK(blocker_count IS NULL OR blocker_count>=0),
    CHECK(warning_count IS NULL OR warning_count>=0), CHECK(expires_at IS NULL OR expires_at>requested_at),
    CHECK(status IN ('requested','running','complete','failed','expired','invalidated')), CHECK(lock_version>=0)
);

CREATE TABLE workforce_readiness_results (
    id uuid PRIMARY KEY DEFAULT uuidv7(), organization_id uuid NOT NULL REFERENCES organizations(id),
    readiness_run_id uuid NOT NULL, gate_key varchar(120) NOT NULL, gate_version varchar(80) NOT NULL,
    outcome varchar(24) NOT NULL, reason_code varchar(120), remediation_code varchar(120),
    evidence_type varchar(80), evidence_id uuid, evidence_digest char(64), deep_link_screen char(5) NOT NULL,
    status varchar(24) NOT NULL, lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(), created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(), updated_by uuid NOT NULL,
    UNIQUE(organization_id,id), UNIQUE(organization_id,readiness_run_id,gate_key),
    FOREIGN KEY(organization_id,readiness_run_id) REFERENCES workforce_readiness_runs(organization_id,id),
    CHECK(gate_key ~ '^[a-z][a-z0-9_]*([.:-][a-z0-9_]+)*$'), CHECK(outcome IN ('complete','warning','blocked','not_applicable')),
    CHECK(evidence_digest IS NULL OR evidence_digest ~ '^[0-9a-f]{64}$'), CHECK(deep_link_screen ~ '^M2-[0-9]{2}$'),
    CHECK(status=outcome), CHECK(lock_version=0)
);

ALTER TABLE workforce_members
    ADD CONSTRAINT workforce_members_current_readiness_fk
        FOREIGN KEY(organization_id,current_readiness_run_id) REFERENCES workforce_readiness_runs(organization_id,id);

CREATE TABLE workforce_activation_requests (
    id uuid PRIMARY KEY DEFAULT uuidv7(), organization_id uuid NOT NULL REFERENCES organizations(id),
    workforce_member_id uuid NOT NULL, member_revision bigint NOT NULL, readiness_run_id uuid NOT NULL,
    result_digest char(64) NOT NULL, maker_id uuid NOT NULL, submitted_reason_code varchar(80) NOT NULL,
    warning_acknowledgements varchar(120)[] NOT NULL DEFAULT '{}', checker_id uuid,
    decision_code varchar(80), activator_id uuid, policy_version varchar(80) NOT NULL DEFAULT 'm2-activation-v1',
    requested_at timestamptz NOT NULL, decided_at timestamptz, expires_at timestamptz NOT NULL, activated_at timestamptz,
    status varchar(24) NOT NULL DEFAULT 'draft', lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(), created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(), updated_by uuid NOT NULL,
    UNIQUE(organization_id,id),
    FOREIGN KEY(organization_id,workforce_member_id) REFERENCES workforce_members(organization_id,id),
    FOREIGN KEY(organization_id,readiness_run_id) REFERENCES workforce_readiness_runs(organization_id,id),
    CHECK(member_revision>=0), CHECK(result_digest ~ '^[0-9a-f]{64}$'),
    CHECK(checker_id IS NULL OR checker_id<>maker_id), CHECK(activator_id IS NULL OR activator_id<>maker_id),
    CHECK(expires_at>requested_at AND expires_at<=requested_at+interval '30 minutes'),
    CHECK(status IN ('draft','submitted','approved','rejected','expired','invalidated','activated')), CHECK(lock_version>=0)
);

CREATE TABLE workforce_offboarding_requests (
    id uuid PRIMARY KEY DEFAULT uuidv7(), organization_id uuid NOT NULL REFERENCES organizations(id),
    workforce_member_id uuid NOT NULL, engagement_end_at timestamptz NOT NULL, effective_at timestamptz NOT NULL,
    reason_entry_id uuid NOT NULL, reason_version_id uuid NOT NULL, impact_digest char(64) NOT NULL,
    maker_id uuid NOT NULL, checker_id uuid, access_action varchar(32) NOT NULL,
    assignment_action varchar(32) NOT NULL, service_action varchar(32) NOT NULL, handover_reference uuid,
    failure_code varchar(120), status varchar(32) NOT NULL DEFAULT 'draft', lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(), created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(), updated_by uuid NOT NULL,
    UNIQUE(organization_id,id),
    FOREIGN KEY(organization_id,workforce_member_id) REFERENCES workforce_members(organization_id,id),
    FOREIGN KEY(organization_id,reason_entry_id,reason_version_id) REFERENCES workforce_registry_versions(organization_id,registry_entry_id,id),
    CHECK(engagement_end_at<=effective_at), CHECK(impact_digest ~ '^[0-9a-f]{64}$'), CHECK(checker_id IS NULL OR checker_id<>maker_id),
    CHECK(access_action IN ('none','revoke_at_effective','revoke_immediately')),
    CHECK(assignment_action IN ('end_at_effective','cancel_future')), CHECK(service_action IN ('end_at_effective','cancel_future')),
    CHECK(status IN ('draft','impact_reviewed','submitted','approved','scheduled','executing','completed','failed','cancelled')), CHECK(lock_version>=0)
);

CREATE TABLE workforce_lifecycle_transitions (
    id uuid PRIMARY KEY DEFAULT uuidv7(), organization_id uuid NOT NULL REFERENCES organizations(id),
    workforce_member_id uuid NOT NULL, from_state varchar(24) NOT NULL, to_state varchar(24) NOT NULL,
    effective_at timestamptz NOT NULL, reason_code varchar(80) NOT NULL, protected_reason_reference uuid,
    source_request_type varchar(48) NOT NULL, source_request_id uuid NOT NULL,
    actor_kind varchar(16) NOT NULL, actor_id uuid NOT NULL, member_revision bigint NOT NULL,
    correlation_id varchar(128) NOT NULL, status varchar(24) NOT NULL DEFAULT 'recorded', lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(), created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(), updated_by uuid NOT NULL,
    UNIQUE(organization_id,id),
    FOREIGN KEY(organization_id,workforce_member_id) REFERENCES workforce_members(organization_id,id),
    CHECK(from_state IN ('draft','submitted','active','suspended','offboarding','offboarded')),
    CHECK(to_state IN ('submitted','active','suspended','offboarding','offboarded')), CHECK(from_state<>to_state),
    CHECK(actor_kind IN ('user','service')), CHECK(member_revision>=0), CHECK(correlation_id ~ '^[A-Za-z0-9._:-]{1,128}$'),
    CHECK(status='recorded' AND lock_version=0)
);

CREATE TABLE workforce_configuration_snapshots (
    id uuid PRIMARY KEY DEFAULT uuidv7(), organization_id uuid NOT NULL REFERENCES organizations(id),
    display_number varchar(32) NOT NULL, parent_snapshot_id uuid, snapshot_digest char(64) NOT NULL,
    policy_versions jsonb NOT NULL, maker_id uuid NOT NULL, checker_id uuid NOT NULL, activator_id uuid NOT NULL,
    effective_at timestamptz NOT NULL, superseded_at timestamptz,
    status varchar(24) NOT NULL DEFAULT 'active', lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(), created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(), updated_by uuid NOT NULL,
    UNIQUE(organization_id,id), UNIQUE(organization_id,display_number),
    FOREIGN KEY(organization_id,parent_snapshot_id) REFERENCES workforce_configuration_snapshots(organization_id,id),
    CHECK(display_number ~ '^WCFG-[0-9]{4}-[0-9]{6}$'), CHECK(snapshot_digest ~ '^[0-9a-f]{64}$'),
    CHECK(jsonb_typeof(policy_versions)='object' AND pg_column_size(policy_versions)<=65536),
    CHECK(maker_id<>checker_id AND maker_id<>activator_id), CHECK(superseded_at IS NULL OR superseded_at>effective_at),
    CHECK(status IN ('active','superseded') AND lock_version=0)
);

CREATE TABLE workforce_configuration_change_requests (
    id uuid PRIMARY KEY DEFAULT uuidv7(), organization_id uuid NOT NULL REFERENCES organizations(id),
    parent_snapshot_id uuid, summary varchar(240) NOT NULL, reason_code varchar(80) NOT NULL,
    maker_id uuid NOT NULL, checker_id uuid, activator_id uuid, validation_digest char(64), decision_digest char(64),
    validation_expires_at timestamptz, decision_expires_at timestamptz, requested_effective_at timestamptz NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'draft', lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(), created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(), updated_by uuid NOT NULL,
    UNIQUE(organization_id,id),
    FOREIGN KEY(organization_id,parent_snapshot_id) REFERENCES workforce_configuration_snapshots(organization_id,id),
    CHECK(char_length(btrim(summary)) BETWEEN 5 AND 240),
    CHECK(validation_digest IS NULL OR validation_digest ~ '^[0-9a-f]{64}$'), CHECK(decision_digest IS NULL OR decision_digest ~ '^[0-9a-f]{64}$'),
    CHECK(checker_id IS NULL OR checker_id<>maker_id), CHECK(activator_id IS NULL OR activator_id<>maker_id),
    CHECK(status IN ('draft','validating','ready','submitted','approved','rejected','active','superseded','cancelled')), CHECK(lock_version>=0)
);

CREATE TABLE workforce_configuration_change_items (
    id uuid PRIMARY KEY DEFAULT uuidv7(), organization_id uuid NOT NULL REFERENCES organizations(id),
    change_request_id uuid NOT NULL, item_order integer NOT NULL, target_type varchar(48) NOT NULL,
    target_id uuid NOT NULL, baseline_revision bigint, new_revision bigint NOT NULL,
    baseline_digest char(64), new_digest char(64) NOT NULL, change_type varchar(24) NOT NULL,
    changed_fields varchar(80)[] NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'draft', lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(), created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(), updated_by uuid NOT NULL,
    UNIQUE(organization_id,id), UNIQUE(organization_id,change_request_id,item_order),
    FOREIGN KEY(organization_id,change_request_id) REFERENCES workforce_configuration_change_requests(organization_id,id),
    CHECK(item_order BETWEEN 1 AND 1000), CHECK(target_type IN ('registry_definition','registry_entry','registry_version','scope_definition','workflow_policy')),
    CHECK(new_revision>=0), CHECK(baseline_digest IS NULL OR baseline_digest ~ '^[0-9a-f]{64}$'), CHECK(new_digest ~ '^[0-9a-f]{64}$'),
    CHECK(change_type IN ('added','changed','removed','superseded')), CHECK(cardinality(changed_fields) BETWEEN 1 AND 64 AND array_position(changed_fields,NULL) IS NULL),
    CHECK(status IN ('draft','submitted','approved','activated','cancelled')), CHECK(lock_version>=0)
);

CREATE TABLE workforce_export_jobs (
    id uuid PRIMARY KEY DEFAULT uuidv7(), organization_id uuid NOT NULL REFERENCES organizations(id),
    requester_id uuid NOT NULL, purpose_key varchar(80) NOT NULL, legal_basis_key varchar(80) NOT NULL,
    projection varchar(80) NOT NULL, filters_digest char(64) NOT NULL, sort_digest char(64) NOT NULL,
    snapshot_at timestamptz NOT NULL, format varchar(16) NOT NULL, row_limit integer NOT NULL, size_limit_bytes bigint NOT NULL,
    artifact_opaque_id uuid, artifact_digest char(64), row_count integer, ready_at timestamptz,
    expires_at timestamptz, disposed_at timestamptz, approval_reference_id uuid, policy_version varchar(80) NOT NULL,
    failure_code varchar(120), status varchar(24) NOT NULL DEFAULT 'requested', lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(), created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(), updated_by uuid NOT NULL,
    UNIQUE(organization_id,id),
    CHECK(purpose_key IN ('workforce_operations','credentialing_review','regulatory_evidence','security_investigation','employment_record_request','data_correction')),
    CHECK(projection IN ('workforce-directory-summary-v1','credential-expiry-summary-v1','workforce-configuration-summary-v1','workforce-audit-summary-v1','member-timeline-summary-v1','credential-decision-detail-v1','scope-decision-detail-v1','workforce-audit-detail-v1','member-evidence-detail-v1')),
    CHECK(filters_digest ~ '^[0-9a-f]{64}$' AND sort_digest ~ '^[0-9a-f]{64}$'), CHECK(format IN ('csv','jsonl')),
    CHECK(row_limit BETWEEN 1 AND 100000), CHECK(size_limit_bytes BETWEEN 1 AND 262144000),
    CHECK(artifact_digest IS NULL OR artifact_digest ~ '^[0-9a-f]{64}$'), CHECK(row_count IS NULL OR row_count BETWEEN 0 AND row_limit),
    CHECK(expires_at IS NULL OR ready_at IS NULL OR expires_at<=ready_at+interval '24 hours'),
    CHECK(status IN ('requested','authorized','running','ready','failed','expired','disposed')), CHECK(lock_version>=0)
);

CREATE TABLE credential_legal_holds (
    id uuid PRIMARY KEY DEFAULT uuidv7(), organization_id uuid NOT NULL REFERENCES organizations(id),
    practitioner_credential_id uuid, credential_document_id uuid, hold_type varchar(48) NOT NULL,
    authority_code varchar(80) NOT NULL, authority_reference varchar(160) NOT NULL,
    evidence_digest char(64) NOT NULL, imposed_by uuid NOT NULL, imposed_at timestamptz NOT NULL,
    released_by uuid, released_at timestamptz, reason_code varchar(80) NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'imposed', lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(), created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(), updated_by uuid NOT NULL,
    UNIQUE(organization_id,id),
    FOREIGN KEY(organization_id,practitioner_credential_id) REFERENCES practitioner_credentials(organization_id,id),
    FOREIGN KEY(organization_id,credential_document_id) REFERENCES credential_documents(organization_id,id),
    CHECK(practitioner_credential_id IS NOT NULL OR credential_document_id IS NOT NULL), CHECK(evidence_digest ~ '^[0-9a-f]{64}$'),
    CHECK(status IN ('imposed','released')), CHECK((status='released')=(released_by IS NOT NULL AND released_at IS NOT NULL)),
    CHECK(released_by IS NULL OR released_by<>imposed_by), CHECK(lock_version=0)
);

CREATE TABLE practitioner_eligibility_evidence (
    id uuid PRIMARY KEY DEFAULT uuidv7(), organization_id uuid NOT NULL REFERENCES organizations(id),
    practitioner_profile_id uuid NOT NULL, service_id uuid NOT NULL, facility_id uuid NOT NULL,
    location_id uuid, activity_entry_id uuid, evaluated_from timestamptz NOT NULL, evaluated_to timestamptz,
    evaluator_version varchar(80) NOT NULL DEFAULT 'm2-eligibility-v1', catalogue_version varchar(80) NOT NULL,
    registration_evidence jsonb NOT NULL, credential_evidence jsonb NOT NULL, scope_evidence jsonb NOT NULL,
    assignment_evidence jsonb NOT NULL, supervision_evidence jsonb NOT NULL,
    outcome varchar(24) NOT NULL, reason_codes varchar(120)[] NOT NULL DEFAULT '{}', result_digest char(64) NOT NULL,
    evaluated_at timestamptz NOT NULL, expires_at timestamptz NOT NULL,
    status varchar(24) NOT NULL, lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(), created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(), updated_by uuid NOT NULL,
    UNIQUE(organization_id,id),
    FOREIGN KEY(organization_id,practitioner_profile_id) REFERENCES practitioner_profiles(organization_id,id),
    FOREIGN KEY(organization_id,service_id) REFERENCES service_definitions(organization_id,id),
    FOREIGN KEY(organization_id,facility_id) REFERENCES facilities(organization_id,id),
    FOREIGN KEY(organization_id,location_id) REFERENCES service_locations(organization_id,id),
    CHECK(evaluated_to IS NULL OR evaluated_to>evaluated_from), CHECK(outcome IN ('eligible','ineligible','indeterminate')),
    CHECK(status=outcome), CHECK(result_digest ~ '^[0-9a-f]{64}$'), CHECK(expires_at>evaluated_at),
    CHECK(jsonb_typeof(registration_evidence)='object' AND jsonb_typeof(credential_evidence)='object' AND jsonb_typeof(scope_evidence)='object' AND jsonb_typeof(assignment_evidence)='object' AND jsonb_typeof(supervision_evidence)='object'),
    CHECK(lock_version=0)
);

ALTER TABLE practitioner_service_assignments
    ADD CONSTRAINT practitioner_service_assignments_eligibility_fk
        FOREIGN KEY(organization_id,eligibility_evidence_id) REFERENCES practitioner_eligibility_evidence(organization_id,id);

CREATE TABLE workforce_notification_deliveries (
    id uuid PRIMARY KEY DEFAULT uuidv7(), organization_id uuid NOT NULL REFERENCES organizations(id),
    workforce_member_id uuid, practitioner_credential_id uuid, source_request_id uuid,
    template_entry_id uuid NOT NULL, template_version_id uuid NOT NULL, milestone varchar(24) NOT NULL,
    channel varchar(16) NOT NULL, recipient_opaque_reference uuid NOT NULL, purpose_key varchar(80) NOT NULL,
    attempt_number integer NOT NULL, provider_opaque_id varchar(160), queued_at timestamptz,
    sent_at timestamptz, delivered_at timestamptz, failure_code varchar(120),
    status varchar(24) NOT NULL DEFAULT 'planned', lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(), created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(), updated_by uuid NOT NULL,
    UNIQUE(organization_id,id), UNIQUE(organization_id,practitioner_credential_id,milestone,template_version_id,attempt_number),
    FOREIGN KEY(organization_id,workforce_member_id) REFERENCES workforce_members(organization_id,id),
    FOREIGN KEY(organization_id,practitioner_credential_id) REFERENCES practitioner_credentials(organization_id,id),
    FOREIGN KEY(organization_id,template_entry_id,template_version_id) REFERENCES workforce_registry_versions(organization_id,registry_entry_id,id),
    CHECK(milestone IN ('90','60','30','7','0','expired')), CHECK(channel='email'), CHECK(attempt_number>0),
    CHECK(status IN ('planned','queued','sending','delivered','failed','suppressed','cancelled')), CHECK(lock_version>=0)
);

ALTER TABLE practitioner_profiles
    ADD CONSTRAINT practitioner_profiles_profession_registry_fk
        FOREIGN KEY(organization_id,profession_entry_id,profession_version_id) REFERENCES workforce_registry_versions(organization_id,registry_entry_id,id);
ALTER TABLE professional_registrations
    ADD CONSTRAINT professional_registrations_regulator_registry_fk
        FOREIGN KEY(organization_id,regulator_entry_id,regulator_version_id) REFERENCES workforce_registry_versions(organization_id,registry_entry_id,id),
    ADD CONSTRAINT professional_registrations_type_registry_fk
        FOREIGN KEY(organization_id,registration_type_entry_id,registration_type_version_id) REFERENCES workforce_registry_versions(organization_id,registry_entry_id,id);
ALTER TABLE qualifications
    ADD CONSTRAINT qualifications_registry_fk
        FOREIGN KEY(organization_id,qualification_entry_id,qualification_version_id) REFERENCES workforce_registry_versions(organization_id,registry_entry_id,id);
ALTER TABLE practitioner_specialties
    ADD CONSTRAINT practitioner_specialties_registry_fk
        FOREIGN KEY(organization_id,specialty_entry_id,specialty_version_id) REFERENCES workforce_registry_versions(organization_id,registry_entry_id,id);
ALTER TABLE practitioner_credentials
    ADD CONSTRAINT practitioner_credentials_registry_fk
        FOREIGN KEY(organization_id,credential_type_entry_id,credential_type_version_id) REFERENCES workforce_registry_versions(organization_id,registry_entry_id,id);

DO $$
DECLARE table_name text;
BEGIN
    FOREACH table_name IN ARRAY ARRAY[
        'scope_definitions','scope_requirements','scopes_of_practice','scope_activities','scope_restrictions',
        'workforce_assignments','practitioner_service_assignments','availability_profiles','availability_periods',
        'availability_exceptions','access_assignment_scopes','workforce_readiness_runs','workforce_readiness_results',
        'workforce_activation_requests','workforce_offboarding_requests','workforce_lifecycle_transitions',
        'workforce_configuration_snapshots','workforce_configuration_change_requests','workforce_configuration_change_items',
        'workforce_export_jobs','credential_legal_holds','practitioner_eligibility_evidence',
        'workforce_notification_deliveries','workforce_registry_definitions','workforce_registry_entries','workforce_registry_versions'
    ] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',table_name);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',table_name);
        EXECUTE format('CREATE POLICY %I ON %I USING (organization_id=nullif(current_setting(''app.current_organization_id'',true),'''')::uuid) WITH CHECK (organization_id=nullif(current_setting(''app.current_organization_id'',true),'''')::uuid)',table_name||'_tenant_policy',table_name);
        EXECUTE format('CREATE TRIGGER %I BEFORE INSERT OR UPDATE ON %I FOR EACH ROW EXECUTE FUNCTION careos_validate_m2_tenant_write()',table_name||'_validate_write',table_name);
    END LOOP;
END;
$$;

DO $$
DECLARE table_name text;
BEGIN
    FOREACH table_name IN ARRAY ARRAY[
        'workforce_readiness_results','workforce_lifecycle_transitions','workforce_configuration_snapshots',
        'credential_legal_holds','practitioner_eligibility_evidence','workforce_registry_versions'
    ] LOOP
        EXECUTE format('CREATE TRIGGER %I BEFORE UPDATE OR DELETE ON %I FOR EACH ROW EXECUTE FUNCTION careos_reject_m2_evidence_mutation()',table_name||'_append_only',table_name);
    END LOOP;
END;
$$;

REVOKE ALL ON scope_definitions,scope_requirements,scopes_of_practice,scope_activities,scope_restrictions,
    workforce_assignments,practitioner_service_assignments,availability_profiles,availability_periods,
    availability_exceptions,access_assignment_scopes,workforce_readiness_runs,workforce_readiness_results,
    workforce_activation_requests,workforce_offboarding_requests,workforce_lifecycle_transitions,
    workforce_configuration_snapshots,workforce_configuration_change_requests,workforce_configuration_change_items,
    workforce_export_jobs,credential_legal_holds,practitioner_eligibility_evidence,
    workforce_notification_deliveries,workforce_registry_definitions,workforce_registry_entries,
    workforce_registry_versions FROM PUBLIC;

GRANT SELECT,INSERT,UPDATE ON scope_definitions,scope_requirements,scopes_of_practice,scope_activities,scope_restrictions,
    workforce_assignments,practitioner_service_assignments,availability_profiles,availability_periods,
    availability_exceptions,access_assignment_scopes,workforce_readiness_runs,
    workforce_activation_requests,workforce_offboarding_requests,
    workforce_configuration_change_requests,workforce_configuration_change_items,
    workforce_export_jobs,workforce_notification_deliveries,workforce_registry_definitions,
    workforce_registry_entries TO "${applicationRole}";
GRANT SELECT,INSERT ON workforce_readiness_results,workforce_lifecycle_transitions,
    workforce_configuration_snapshots,credential_legal_holds,practitioner_eligibility_evidence,
    workforce_registry_versions TO "${applicationRole}";

CREATE INDEX workforce_assignments_member_idx ON workforce_assignments(organization_id,workforce_member_id,effective_from DESC,id);
CREATE INDEX practitioner_service_assignments_context_idx ON practitioner_service_assignments(organization_id,service_id,facility_id,location_id,status,id);
CREATE INDEX readiness_runs_member_idx ON workforce_readiness_runs(organization_id,workforce_member_id,requested_at DESC,id);
CREATE INDEX activation_requests_member_idx ON workforce_activation_requests(organization_id,workforce_member_id,requested_at DESC,id);
CREATE INDEX workforce_export_jobs_requester_idx ON workforce_export_jobs(organization_id,requester_id,created_at DESC,id);
CREATE INDEX workforce_registry_versions_active_idx ON workforce_registry_versions(organization_id,registry_entry_id,effective_from DESC,id) WHERE status='active';
