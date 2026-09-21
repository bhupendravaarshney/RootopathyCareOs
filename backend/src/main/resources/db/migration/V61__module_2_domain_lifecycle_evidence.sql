-- Immutable evidence and database-owned edge checks for Module 2 domain lifecycles.

ALTER TABLE practitioner_specialties
    ADD COLUMN supersedes_id uuid,
    ADD CONSTRAINT practitioner_specialties_supersedes_fk
        FOREIGN KEY (organization_id,supersedes_id)
        REFERENCES practitioner_specialties(organization_id,id),
    ADD CONSTRAINT practitioner_specialties_supersedes_self_check
        CHECK (supersedes_id IS NULL OR supersedes_id<>id);

CREATE TABLE workforce_domain_lifecycle_evidence (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    aggregate_type varchar(64) NOT NULL,
    aggregate_id uuid NOT NULL,
    workforce_member_id uuid NOT NULL,
    from_state varchar(40) NOT NULL,
    to_state varchar(40) NOT NULL,
    effective_at timestamptz NOT NULL,
    reason_code varchar(80) NOT NULL,
    authority_evidence_id uuid,
    impact_digest char(64) NOT NULL,
    aggregate_revision bigint NOT NULL,
    actor_id uuid NOT NULL,
    correlation_id varchar(128) NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'recorded',
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    UNIQUE (organization_id,id),
    UNIQUE (organization_id,aggregate_type,aggregate_id,aggregate_revision,to_state),
    FOREIGN KEY (organization_id,workforce_member_id)
        REFERENCES workforce_members(organization_id,id),
    CHECK (aggregate_type IN (
        'professional_registration','practitioner_credential','scope_of_practice',
        'workforce_assignment','practitioner_service_assignment')),
    CHECK (from_state<>to_state),
    CHECK (reason_code ~ '^[a-z][a-z0-9._:-]{1,79}$'),
    CHECK ((aggregate_type IN ('professional_registration','practitioner_credential'))
           =(authority_evidence_id IS NOT NULL)),
    CHECK (impact_digest ~ '^[0-9a-f]{64}$'),
    CHECK (aggregate_revision>0),
    CHECK (correlation_id ~ '^[A-Za-z0-9._:-]{1,128}$'),
    CHECK (status='recorded' AND lock_version=0)
);

CREATE INDEX workforce_domain_lifecycle_member_idx
    ON workforce_domain_lifecycle_evidence(
        organization_id,workforce_member_id,effective_at DESC,id);

ALTER TABLE workforce_domain_lifecycle_evidence ENABLE ROW LEVEL SECURITY;
ALTER TABLE workforce_domain_lifecycle_evidence FORCE ROW LEVEL SECURITY;
CREATE POLICY workforce_domain_lifecycle_evidence_tenant_policy
    ON workforce_domain_lifecycle_evidence
    USING (organization_id=nullif(current_setting('app.current_organization_id',true),'')::uuid)
    WITH CHECK (organization_id=nullif(current_setting('app.current_organization_id',true),'')::uuid);

CREATE TRIGGER workforce_domain_lifecycle_evidence_validate_write
    BEFORE INSERT OR UPDATE ON workforce_domain_lifecycle_evidence
    FOR EACH ROW EXECUTE FUNCTION careos_validate_m2_tenant_write();
CREATE TRIGGER workforce_domain_lifecycle_evidence_append_only
    BEFORE UPDATE OR DELETE ON workforce_domain_lifecycle_evidence
    FOR EACH ROW EXECUTE FUNCTION careos_reject_m2_evidence_mutation();

CREATE FUNCTION careos_validate_domain_lifecycle_evidence_operation()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE expected_operation text;
BEGIN
    expected_operation:=CASE NEW.aggregate_type
        WHEN 'professional_registration' THEN 'credential.registration.lifecycle'
        WHEN 'practitioner_credential' THEN 'credential.lifecycle'
        WHEN 'scope_of_practice' THEN 'practitioner.scope.lifecycle'
        WHEN 'workforce_assignment' THEN 'workforce.assignment.lifecycle'
        WHEN 'practitioner_service_assignment' THEN 'practitioner.service_assignment.lifecycle'
    END;
    IF current_user='${applicationRole}'
       AND (nullif(current_setting('app.current_operation_key',true),'')<>expected_operation
            OR NEW.actor_id<>nullif(current_setting('app.current_actor_id',true),'')::uuid) THEN
        RAISE EXCEPTION 'invalid domain lifecycle evidence operation' USING ERRCODE='42501';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER workforce_domain_lifecycle_evidence_validate_operation
    BEFORE INSERT ON workforce_domain_lifecycle_evidence
    FOR EACH ROW EXECUTE FUNCTION careos_validate_domain_lifecycle_evidence_operation();

REVOKE ALL ON workforce_domain_lifecycle_evidence FROM PUBLIC;
GRANT SELECT,INSERT ON workforce_domain_lifecycle_evidence TO "${applicationRole}";

CREATE FUNCTION careos_guard_registration_lifecycle_edge()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE operation_key text:=nullif(current_setting('app.current_operation_key',true),'');
BEGIN
    IF OLD.status=NEW.status THEN RETURN NEW; END IF;
    IF OLD.status IN ('revoked','expired','superseded') THEN
        RAISE EXCEPTION 'terminal registration evidence is immutable' USING ERRCODE='55000';
    END IF;
    IF NOT (
        (OLD.status='draft' AND NEW.status IN ('evidence_pending','submitted')
            AND operation_key='credential.registration.manage') OR
        (OLD.status='evidence_pending' AND NEW.status='submitted'
            AND operation_key='credential.registration.manage') OR
        (OLD.status='submitted' AND NEW.status='verified'
            AND operation_key='credential.registration.lifecycle') OR
        (OLD.status='verified' AND NEW.status IN ('suspended','revoked','superseded')
            AND operation_key='credential.registration.lifecycle') OR
        (OLD.status='suspended' AND NEW.status IN ('revoked','superseded')
            AND operation_key='credential.registration.lifecycle') OR
        (OLD.status IN ('verified','suspended') AND NEW.status='expired'
            AND operation_key='m2.expiry.process')
    ) THEN
        RAISE EXCEPTION 'm2.lifecycle.invalid_transition' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER professional_registrations_guard_lifecycle_edge
    BEFORE UPDATE OF status ON professional_registrations
    FOR EACH ROW EXECUTE FUNCTION careos_guard_registration_lifecycle_edge();

CREATE FUNCTION careos_guard_qualification_lifecycle_edge()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.status=NEW.status THEN RETURN NEW; END IF;
    IF OLD.status IN ('rejected','superseded') THEN
        RAISE EXCEPTION 'terminal qualification evidence is immutable' USING ERRCODE='55000';
    END IF;
    IF nullif(current_setting('app.current_operation_key',true),'')<>'credential.qualification.manage'
       OR NOT (
          (OLD.status='draft' AND NEW.status='submitted') OR
          (OLD.status='submitted' AND NEW.status IN ('verified','rejected','returned_for_correction')) OR
          (OLD.status IN ('verified','returned_for_correction') AND NEW.status='superseded')
       ) THEN
        RAISE EXCEPTION 'm2.lifecycle.invalid_transition' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER qualifications_guard_lifecycle_edge
    BEFORE UPDATE OF status ON qualifications
    FOR EACH ROW EXECUTE FUNCTION careos_guard_qualification_lifecycle_edge();

CREATE FUNCTION careos_guard_credential_lifecycle_edge()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE operation_key text:=nullif(current_setting('app.current_operation_key',true),'');
BEGIN
    IF OLD.status=NEW.status THEN RETURN NEW; END IF;
    IF OLD.status IN ('rejected','revoked','expired','superseded') THEN
        RAISE EXCEPTION 'terminal credential evidence is immutable' USING ERRCODE='55000';
    END IF;
    IF OLD.status IN ('verified','suspended')
       AND NEW.status IN ('suspended','revoked','expired','superseded')
       AND NOT (
           (NEW.status IN ('suspended','revoked') AND operation_key='credential.lifecycle') OR
           (NEW.status='expired' AND operation_key='m2.expiry.process') OR
           (NEW.status='superseded' AND operation_key='credential.review.decide')
       ) THEN
        RAISE EXCEPTION 'm2.lifecycle.invalid_transition' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER practitioner_credentials_guard_lifecycle_edge
    BEFORE UPDATE OF status ON practitioner_credentials
    FOR EACH ROW EXECUTE FUNCTION careos_guard_credential_lifecycle_edge();

CREATE FUNCTION careos_guard_scope_lifecycle_edge()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE operation_key text:=nullif(current_setting('app.current_operation_key',true),'');
BEGIN
    IF OLD.lifecycle_state=NEW.lifecycle_state THEN RETURN NEW; END IF;
    IF OLD.lifecycle_state IN ('approved','suspended')
       AND NEW.lifecycle_state IN ('suspended','ended','superseded')
       AND NOT (
           (NEW.lifecycle_state IN ('suspended','ended')
              AND operation_key='practitioner.scope.lifecycle') OR
           (NEW.lifecycle_state='superseded' AND operation_key='practitioner.scope.approve')
       ) THEN
        RAISE EXCEPTION 'm2.lifecycle.invalid_transition' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER scopes_of_practice_guard_lifecycle_edge
    BEFORE UPDATE OF lifecycle_state ON scopes_of_practice
    FOR EACH ROW EXECUTE FUNCTION careos_guard_scope_lifecycle_edge();

CREATE FUNCTION careos_guard_specialty_content_and_edge()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE operation_key text:=nullif(current_setting('app.current_operation_key',true),'');
BEGIN
    IF OLD.status<>'scheduled'
       AND ROW(NEW.practitioner_profile_id,NEW.specialty_entry_id,NEW.specialty_version_id,
               NEW.designation,NEW.effective_from,NEW.supersedes_id)
           IS DISTINCT FROM
           ROW(OLD.practitioner_profile_id,OLD.specialty_entry_id,OLD.specialty_version_id,
               OLD.designation,OLD.effective_from,OLD.supersedes_id) THEN
        RAISE EXCEPTION 'active specialty content is immutable' USING ERRCODE='55000';
    END IF;
    IF NEW.effective_to IS DISTINCT FROM OLD.effective_to
       AND NOT (operation_key='practitioner.specialty.manage'
                AND NEW.effective_to>OLD.effective_from
                AND (OLD.effective_to IS NULL OR NEW.effective_to<=OLD.effective_to)) THEN
        RAISE EXCEPTION 'specialty range may only be shortened by its lifecycle operation'
            USING ERRCODE='55000';
    END IF;
    IF OLD.status<>NEW.status
       AND NOT (operation_key='practitioner.specialty.manage' AND (
          (OLD.status='scheduled' AND NEW.status='active') OR
          (OLD.status IN ('scheduled','active') AND NEW.status IN ('ended','superseded'))
       )) THEN
        RAISE EXCEPTION 'm2.lifecycle.invalid_transition' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER practitioner_specialties_guard_content_and_edge
    BEFORE UPDATE ON practitioner_specialties
    FOR EACH ROW EXECUTE FUNCTION careos_guard_specialty_content_and_edge();

CREATE FUNCTION careos_guard_assignment_content_and_edge()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE operation_key text:=nullif(current_setting('app.current_operation_key',true),'');
BEGIN
    IF OLD.lifecycle_state<>'draft'
       AND ROW(NEW.workforce_member_id,NEW.facility_id,NEW.organization_unit_id,NEW.location_id,
               NEW.assignment_type_entry_id,NEW.assignment_type_version_id,
               NEW.position_entry_id,NEW.position_version_id,NEW.primary_assignment,
               NEW.effective_from,NEW.predecessor_id)
           IS DISTINCT FROM
           ROW(OLD.workforce_member_id,OLD.facility_id,OLD.organization_unit_id,OLD.location_id,
               OLD.assignment_type_entry_id,OLD.assignment_type_version_id,
               OLD.position_entry_id,OLD.position_version_id,OLD.primary_assignment,
               OLD.effective_from,OLD.predecessor_id) THEN
        RAISE EXCEPTION 'scheduled assignment content is immutable' USING ERRCODE='55000';
    END IF;
    IF NEW.successor_id IS DISTINCT FROM OLD.successor_id
       AND NOT (operation_key='workforce.assignment.lifecycle'
                AND OLD.successor_id IS NULL AND NEW.successor_id IS NOT NULL) THEN
        RAISE EXCEPTION 'assignment successor lineage is immutable' USING ERRCODE='55000';
    END IF;
    IF NEW.effective_to IS DISTINCT FROM OLD.effective_to
       AND NOT (operation_key IN ('workforce.assignment.lifecycle','workforce.offboarding.execute','m2.offboarding.execute')
                AND NEW.effective_to>OLD.effective_from
                AND (OLD.effective_to IS NULL OR NEW.effective_to<=OLD.effective_to)) THEN
        RAISE EXCEPTION 'assignment range may only be shortened by lifecycle operation' USING ERRCODE='55000';
    END IF;
    IF OLD.lifecycle_state<>NEW.lifecycle_state
       AND NOT (
          (OLD.lifecycle_state IN ('draft','scheduled') AND NEW.lifecycle_state='active'
             AND operation_key='workforce.assignment.lifecycle') OR
          (OLD.lifecycle_state='active' AND NEW.lifecycle_state IN ('suspended','ended')
             AND operation_key='workforce.assignment.lifecycle') OR
          (OLD.lifecycle_state='suspended' AND NEW.lifecycle_state IN ('active','ended')
             AND operation_key='workforce.assignment.lifecycle') OR
          (OLD.lifecycle_state IN ('draft','scheduled') AND NEW.lifecycle_state='cancelled'
             AND operation_key='workforce.assignment.lifecycle') OR
          (OLD.lifecycle_state IN ('scheduled','active','suspended')
             AND NEW.lifecycle_state IN ('ended','cancelled')
             AND operation_key IN ('workforce.offboarding.execute','m2.offboarding.execute'))
       ) THEN
        RAISE EXCEPTION 'm2.lifecycle.invalid_transition' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER workforce_assignments_guard_content_and_edge
    BEFORE UPDATE ON workforce_assignments
    FOR EACH ROW EXECUTE FUNCTION careos_guard_assignment_content_and_edge();

CREATE FUNCTION careos_guard_service_assignment_content_and_edge()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE operation_key text:=nullif(current_setting('app.current_operation_key',true),'');
BEGIN
    IF OLD.lifecycle_state<>'draft'
       AND ROW(NEW.practitioner_profile_id,NEW.service_id,NEW.facility_id,NEW.location_id,
               NEW.scope_of_practice_id,NEW.supervisor_practitioner_id,NEW.effective_from)
           IS DISTINCT FROM
           ROW(OLD.practitioner_profile_id,OLD.service_id,OLD.facility_id,OLD.location_id,
               OLD.scope_of_practice_id,OLD.supervisor_practitioner_id,OLD.effective_from) THEN
        RAISE EXCEPTION 'scheduled service assignment content is immutable' USING ERRCODE='55000';
    END IF;
    IF NEW.effective_to IS DISTINCT FROM OLD.effective_to
       AND NOT (operation_key IN (
                    'practitioner.service_assignment.lifecycle','practitioner.scope.lifecycle',
                    'workforce.offboarding.execute','m2.offboarding.execute')
                AND NEW.effective_to>OLD.effective_from
                AND (OLD.effective_to IS NULL OR NEW.effective_to<=OLD.effective_to)) THEN
        RAISE EXCEPTION 'service-assignment range may only be shortened by lifecycle operation'
            USING ERRCODE='55000';
    END IF;
    IF OLD.lifecycle_state<>NEW.lifecycle_state
       AND NOT (
          (OLD.lifecycle_state IN ('draft','scheduled','suspended') AND NEW.lifecycle_state='active'
             AND operation_key='practitioner.service_assignment.lifecycle') OR
          (OLD.lifecycle_state='active' AND NEW.lifecycle_state='suspended'
             AND operation_key IN ('practitioner.service_assignment.lifecycle',
                                   'practitioner.scope.lifecycle','workforce.lifecycle.suspend')) OR
          (OLD.lifecycle_state IN ('active','suspended') AND NEW.lifecycle_state='ended'
             AND operation_key IN ('practitioner.service_assignment.lifecycle',
                                   'practitioner.scope.lifecycle','workforce.offboarding.execute',
                                   'm2.offboarding.execute')) OR
          (OLD.lifecycle_state IN ('draft','scheduled') AND NEW.lifecycle_state='cancelled'
             AND operation_key IN ('practitioner.service_assignment.lifecycle',
                                   'workforce.offboarding.execute','m2.offboarding.execute')) OR
          (OLD.lifecycle_state='suspended' AND NEW.lifecycle_state='active'
             AND operation_key='workforce.lifecycle.reactivate')
       ) THEN
        RAISE EXCEPTION 'm2.lifecycle.invalid_transition' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER practitioner_service_assignments_guard_content_and_edge
    BEFORE UPDATE ON practitioner_service_assignments
    FOR EACH ROW EXECUTE FUNCTION careos_guard_service_assignment_content_and_edge();
