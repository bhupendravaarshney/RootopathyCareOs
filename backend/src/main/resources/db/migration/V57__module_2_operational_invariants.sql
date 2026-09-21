-- Operational invariants discovered while wiring the approved Module 2 workflows.
-- This migration is additive to the checksum-bound input release; it does not alter
-- the approved candidate artifacts or introduce another source of truth.

ALTER TABLE workforce_configuration_snapshots
    DROP CONSTRAINT workforce_configuration_snapshots_lock_version_check,
    ADD CONSTRAINT workforce_configuration_snapshots_lock_version_check CHECK (lock_version >= 0);

DROP TRIGGER workforce_configuration_snapshots_append_only ON workforce_configuration_snapshots;
DROP TRIGGER workforce_registry_versions_append_only ON workforce_registry_versions;

CREATE FUNCTION careos_validate_workforce_configuration_snapshot_revision()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF ROW(NEW.id,NEW.organization_id,NEW.display_number,NEW.parent_snapshot_id,
           NEW.snapshot_digest,NEW.policy_versions,NEW.maker_id,NEW.checker_id,
           NEW.activator_id,NEW.effective_at,NEW.created_at,NEW.created_by)
       IS DISTINCT FROM
       ROW(OLD.id,OLD.organization_id,OLD.display_number,OLD.parent_snapshot_id,
           OLD.snapshot_digest,OLD.policy_versions,OLD.maker_id,OLD.checker_id,
           OLD.activator_id,OLD.effective_at,OLD.created_at,OLD.created_by)
       OR OLD.status <> 'active' OR NEW.status <> 'superseded'
       OR OLD.superseded_at IS NOT NULL OR NEW.superseded_at IS NULL
       OR NEW.superseded_at <= OLD.effective_at THEN
        RAISE EXCEPTION 'workforce configuration snapshots are immutable except for supersession'
            USING ERRCODE='55000';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER workforce_configuration_snapshots_revision_guard
    BEFORE UPDATE ON workforce_configuration_snapshots
    FOR EACH ROW EXECUTE FUNCTION careos_validate_workforce_configuration_snapshot_revision();
CREATE TRIGGER workforce_configuration_snapshots_reject_delete
    BEFORE DELETE ON workforce_configuration_snapshots
    FOR EACH ROW EXECUTE FUNCTION careos_reject_m2_evidence_mutation();

CREATE FUNCTION careos_validate_workforce_registry_version_revision()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF ROW(NEW.id,NEW.organization_id,NEW.registry_entry_id,NEW.version_number,
           NEW.version_fields,NEW.version_digest,NEW.effective_from,NEW.maker_id,
           NEW.checker_id,NEW.decision_code,NEW.activated_at,NEW.created_at,NEW.created_by)
       IS DISTINCT FROM
       ROW(OLD.id,OLD.organization_id,OLD.registry_entry_id,OLD.version_number,
           OLD.version_fields,OLD.version_digest,OLD.effective_from,OLD.maker_id,
           OLD.checker_id,OLD.decision_code,OLD.activated_at,OLD.created_at,OLD.created_by)
       OR OLD.lifecycle_state <> 'active' OR NEW.lifecycle_state <> 'superseded'
       OR OLD.superseded_at IS NOT NULL OR NEW.superseded_at IS NULL
       OR NEW.superseded_at <= OLD.effective_from THEN
        RAISE EXCEPTION 'active workforce registry versions are immutable except for supersession'
            USING ERRCODE='55000';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER workforce_registry_versions_revision_guard
    BEFORE UPDATE ON workforce_registry_versions
    FOR EACH ROW EXECUTE FUNCTION careos_validate_workforce_registry_version_revision();
CREATE TRIGGER workforce_registry_versions_reject_delete
    BEFORE DELETE ON workforce_registry_versions
    FOR EACH ROW EXECUTE FUNCTION careos_reject_m2_evidence_mutation();

CREATE UNIQUE INDEX workforce_registry_versions_one_current_active_uq
    ON workforce_registry_versions(organization_id,registry_entry_id)
    WHERE status='active' AND superseded_at IS NULL;

CREATE FUNCTION careos_validate_workforce_hierarchy_reference()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    unit_facility uuid;
    location_facility uuid;
    location_unit uuid;
BEGIN
    IF NEW.organization_unit_id IS NOT NULL THEN
        SELECT facility_id INTO unit_facility
        FROM organization_units
        WHERE organization_id=NEW.organization_id AND id=NEW.organization_unit_id;
        IF unit_facility IS NULL OR unit_facility<>NEW.facility_id THEN
            RAISE EXCEPTION 'm2.hierarchy.invalid' USING ERRCODE='23514';
        END IF;
    END IF;
    IF NEW.location_id IS NOT NULL THEN
        SELECT facility_id,unit_id INTO location_facility,location_unit
        FROM service_locations
        WHERE organization_id=NEW.organization_id AND id=NEW.location_id;
        IF location_facility IS NULL OR location_facility<>NEW.facility_id
           OR (NEW.organization_unit_id IS NOT NULL
               AND location_unit IS DISTINCT FROM NEW.organization_unit_id) THEN
            RAISE EXCEPTION 'm2.hierarchy.invalid' USING ERRCODE='23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER workforce_assignments_validate_hierarchy
    BEFORE INSERT OR UPDATE OF facility_id,organization_unit_id,location_id
    ON workforce_assignments
    FOR EACH ROW EXECUTE FUNCTION careos_validate_workforce_hierarchy_reference();

CREATE FUNCTION careos_validate_practitioner_service_context()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    location_facility uuid;
    scope_practitioner uuid;
BEGIN
    IF NEW.location_id IS NOT NULL THEN
        SELECT facility_id INTO location_facility
        FROM service_locations
        WHERE organization_id=NEW.organization_id AND id=NEW.location_id;
        IF location_facility IS NULL OR location_facility<>NEW.facility_id THEN
            RAISE EXCEPTION 'm2.hierarchy.invalid' USING ERRCODE='23514';
        END IF;
    END IF;
    SELECT practitioner_profile_id INTO scope_practitioner
    FROM scopes_of_practice
    WHERE organization_id=NEW.organization_id AND id=NEW.scope_of_practice_id;
    IF scope_practitioner IS NULL OR scope_practitioner<>NEW.practitioner_profile_id THEN
        RAISE EXCEPTION 'm2.eligibility.blocked' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER practitioner_service_assignments_validate_context
    BEFORE INSERT OR UPDATE OF practitioner_profile_id,facility_id,location_id,scope_of_practice_id
    ON practitioner_service_assignments
    FOR EACH ROW EXECUTE FUNCTION careos_validate_practitioner_service_context();

-- Readiness and pending activation are invalid as soon as authoritative source state changes.
-- The source operation records its own governed event; invalidated runs remain immutable history.
CREATE FUNCTION careos_invalidate_member_readiness(
    affected_organization_id uuid,
    affected_member_id uuid,
    invalidation_code text)
RETURNS void LANGUAGE plpgsql AS $$
BEGIN
    UPDATE workforce_readiness_runs
       SET status='invalidated',failure_code=invalidation_code,
           lock_version=lock_version+1,updated_at=clock_timestamp(),
           updated_by=nullif(current_setting('app.current_actor_id',true),'')::uuid
     WHERE organization_id=affected_organization_id
       AND workforce_member_id=affected_member_id
       AND status='complete';
    UPDATE workforce_activation_requests
       SET status='invalidated',lock_version=lock_version+1,
           updated_at=clock_timestamp(),
           updated_by=nullif(current_setting('app.current_actor_id',true),'')::uuid
     WHERE organization_id=affected_organization_id
       AND workforce_member_id=affected_member_id
       AND status IN ('draft','submitted','approved');
END;
$$;

CREATE FUNCTION careos_invalidate_readiness_from_member_source()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    affected_organization_id uuid;
    affected_member_id uuid;
BEGIN
    affected_organization_id:=CASE WHEN TG_OP='DELETE' THEN OLD.organization_id ELSE NEW.organization_id END;
    affected_member_id:=CASE WHEN TG_OP='DELETE' THEN OLD.workforce_member_id ELSE NEW.workforce_member_id END;
    PERFORM careos_invalidate_member_readiness(
        affected_organization_id,affected_member_id,'evaluated_state_changed');
    RETURN NULL;
END;
$$;

CREATE FUNCTION careos_invalidate_readiness_from_practitioner_source()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    affected_organization_id uuid;
    affected_practitioner_id uuid;
    affected_member_id uuid;
BEGIN
    affected_organization_id:=CASE WHEN TG_OP='DELETE' THEN OLD.organization_id ELSE NEW.organization_id END;
    affected_practitioner_id:=CASE
        WHEN TG_TABLE_NAME='practitioner_profiles' THEN CASE WHEN TG_OP='DELETE' THEN OLD.id ELSE NEW.id END
        ELSE CASE WHEN TG_OP='DELETE' THEN OLD.practitioner_profile_id ELSE NEW.practitioner_profile_id END
    END;
    SELECT workforce_member_id INTO affected_member_id
      FROM practitioner_profiles
     WHERE organization_id=affected_organization_id AND id=affected_practitioner_id;
    IF affected_member_id IS NOT NULL THEN
        PERFORM careos_invalidate_member_readiness(
            affected_organization_id,affected_member_id,'evaluated_state_changed');
    END IF;
    RETURN NULL;
END;
$$;

CREATE FUNCTION careos_invalidate_readiness_from_scope_source()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    affected_organization_id uuid;
    affected_scope_id uuid;
    affected_member_id uuid;
BEGIN
    affected_organization_id:=CASE WHEN TG_OP='DELETE' THEN OLD.organization_id ELSE NEW.organization_id END;
    affected_scope_id:=CASE
        WHEN TG_TABLE_NAME='scopes_of_practice' THEN CASE WHEN TG_OP='DELETE' THEN OLD.id ELSE NEW.id END
        ELSE CASE WHEN TG_OP='DELETE' THEN OLD.scope_of_practice_id ELSE NEW.scope_of_practice_id END
    END;
    SELECT practitioner.workforce_member_id INTO affected_member_id
      FROM scopes_of_practice scope
      JOIN practitioner_profiles practitioner
        ON practitioner.organization_id=scope.organization_id
       AND practitioner.id=scope.practitioner_profile_id
     WHERE scope.organization_id=affected_organization_id AND scope.id=affected_scope_id;
    IF affected_member_id IS NOT NULL THEN
        PERFORM careos_invalidate_member_readiness(
            affected_organization_id,affected_member_id,'evaluated_state_changed');
    END IF;
    RETURN NULL;
END;
$$;

CREATE FUNCTION careos_invalidate_readiness_from_member_row()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM careos_invalidate_member_readiness(
        NEW.organization_id,NEW.id,'member_state_changed');
    RETURN NULL;
END;
$$;

CREATE TRIGGER workforce_members_invalidate_readiness
    AFTER UPDATE OF organization_person_link_id,pathway,account_access_intent,lifecycle_state
    ON workforce_members
    FOR EACH ROW
    WHEN (current_setting('app.current_operation_key',true) NOT IN
          ('workforce.validation.run','workforce.activation.submit',
           'workforce.activation.execute','workforce.lifecycle.reactivate'))
    EXECUTE FUNCTION careos_invalidate_readiness_from_member_row();

DO $$
DECLARE table_name text;
BEGIN
    FOREACH table_name IN ARRAY ARRAY[
        'workforce_identifiers','employment_engagements','qualifications',
        'workforce_assignments','availability_profiles','access_assignment_scopes'
    ] LOOP
        EXECUTE format(
            'CREATE TRIGGER %I AFTER INSERT OR UPDATE OR DELETE ON %I FOR EACH ROW WHEN (current_setting(''app.current_operation_key'',true)<>''workforce.validation.run'') EXECUTE FUNCTION careos_invalidate_readiness_from_member_source()',
            table_name||'_invalidate_readiness',table_name);
    END LOOP;
END;
$$;

DO $$
DECLARE table_name text;
BEGIN
    FOREACH table_name IN ARRAY ARRAY[
        'practitioner_profiles','professional_registrations','practitioner_specialties',
        'practitioner_credentials','practitioner_service_assignments'
    ] LOOP
        EXECUTE format(
            'CREATE TRIGGER %I AFTER INSERT OR UPDATE OR DELETE ON %I FOR EACH ROW WHEN (current_setting(''app.current_operation_key'',true)<>''workforce.validation.run'') EXECUTE FUNCTION careos_invalidate_readiness_from_practitioner_source()',
            table_name||'_invalidate_readiness',table_name);
    END LOOP;
END;
$$;

DO $$
DECLARE table_name text;
BEGIN
    FOREACH table_name IN ARRAY ARRAY['scopes_of_practice','scope_activities','scope_restrictions'] LOOP
        EXECUTE format(
            'CREATE TRIGGER %I AFTER INSERT OR UPDATE OR DELETE ON %I FOR EACH ROW WHEN (current_setting(''app.current_operation_key'',true)<>''workforce.validation.run'') EXECUTE FUNCTION careos_invalidate_readiness_from_scope_source()',
            table_name||'_invalidate_readiness',table_name);
    END LOOP;
END;
$$;

ALTER TABLE workforce_export_jobs
    ADD COLUMN filters_json jsonb NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN artifact_content_type varchar(80),
    ADD COLUMN artifact_filename varchar(160),
    ADD COLUMN artifact_byte_count bigint,
    ADD COLUMN attempt_count integer NOT NULL DEFAULT 0,
    ADD COLUMN worker_id varchar(128),
    ADD COLUMN lease_expires_at timestamptz,
    ADD COLUMN next_attempt_at timestamptz,
    ADD COLUMN dead_lettered_at timestamptz,
    ADD COLUMN legal_hold boolean NOT NULL DEFAULT false,
    ADD CONSTRAINT workforce_export_jobs_operational_check CHECK (
        (jsonb_typeof(filters_json)='object' AND pg_column_size(filters_json)<=16384)
        AND (artifact_byte_count IS NULL OR artifact_byte_count BETWEEN 0 AND size_limit_bytes)
        AND (attempt_count BETWEEN 0 AND 5)
        AND ((worker_id IS NULL)=(lease_expires_at IS NULL))
        AND (artifact_filename IS NULL OR artifact_filename ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,159}$')
        AND (artifact_content_type IS NULL OR artifact_content_type IN ('text/csv','application/x-ndjson'))
    );

CREATE UNIQUE INDEX workforce_export_jobs_one_active_request_uq
    ON workforce_export_jobs(organization_id,requester_id,projection)
    WHERE status IN ('requested','authorized','running','ready');

CREATE INDEX workforce_export_jobs_due_idx
    ON workforce_export_jobs(organization_id,status,next_attempt_at,created_at,id)
    WHERE status IN ('authorized','ready','expired');

-- Summary exports are authorized by their requesting operation after the source permission and
-- assurance checks; restricted projections still require workforce.export.approve.
INSERT INTO authorization_operation_events
    (operation_key,event_kind,event_name,schema_version,status,registry_version)
VALUES ('workforce.export.request','outbox','workforce.export.authorized',1,'active','m2-candidate-1')
ON CONFLICT DO NOTHING;

COMMENT ON COLUMN workforce_export_jobs.artifact_opaque_id IS
    'Opaque UUID used by private artifact storage; it is never a provider path or URL.';
COMMENT ON COLUMN workforce_export_jobs.legal_hold IS
    'Blocks disposal only; it never extends artifact access or changes export state.';
