-- Readiness is usable only while every authoritative dependency remains unchanged.
-- This migration completes source coverage, including M1-owned hierarchy/access rows,
-- without allowing an application transaction to mutate another tenant's evidence.

CREATE OR REPLACE FUNCTION careos_invalidate_member_readiness(
    affected_organization_id uuid,
    affected_member_id uuid,
    invalidation_code text)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    configured_organization uuid :=
        nullif(current_setting('app.current_organization_id',true),'')::uuid;
    configured_actor uuid :=
        nullif(current_setting('app.current_actor_id',true),'')::uuid;
BEGIN
    IF affected_organization_id IS NULL OR affected_member_id IS NULL THEN
        RETURN;
    END IF;
    IF configured_organization IS NULL THEN
        IF session_user='${applicationRole}' THEN
            RAISE EXCEPTION 'missing Module 2 readiness invalidation tenant'
                USING ERRCODE='42501';
        END IF;
        RETURN;
    END IF;
    IF configured_organization IS DISTINCT FROM affected_organization_id THEN
        RAISE EXCEPTION 'invalid Module 2 readiness invalidation tenant'
            USING ERRCODE='42501';
    END IF;
    UPDATE workforce_readiness_runs
       SET status='invalidated',failure_code=invalidation_code,
           lock_version=lock_version+1,updated_at=clock_timestamp(),
           updated_by=coalesce(configured_actor,updated_by)
     WHERE organization_id=affected_organization_id
       AND workforce_member_id=affected_member_id
       AND status='complete';
    UPDATE workforce_activation_requests
       SET status='invalidated',lock_version=lock_version+1,
           updated_at=clock_timestamp(),updated_by=coalesce(configured_actor,updated_by)
     WHERE organization_id=affected_organization_id
       AND workforce_member_id=affected_member_id
       AND status IN ('draft','submitted','approved');
END;
$$;

CREATE OR REPLACE FUNCTION careos_invalidate_readiness_from_practitioner_source()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    affected_organization_id uuid;
    affected_practitioner_id uuid;
    affected_member_id uuid;
BEGIN
    affected_organization_id:=CASE WHEN TG_OP='DELETE' THEN OLD.organization_id ELSE NEW.organization_id END;
    IF TG_TABLE_NAME='practitioner_credentials' THEN
        affected_member_id:=CASE WHEN TG_OP='DELETE' THEN OLD.workforce_member_id ELSE NEW.workforce_member_id END;
        affected_practitioner_id:=CASE WHEN TG_OP='DELETE' THEN OLD.practitioner_profile_id ELSE NEW.practitioner_profile_id END;
    ELSE
        affected_practitioner_id:=CASE
            WHEN TG_TABLE_NAME='practitioner_profiles' THEN CASE WHEN TG_OP='DELETE' THEN OLD.id ELSE NEW.id END
            ELSE CASE WHEN TG_OP='DELETE' THEN OLD.practitioner_profile_id ELSE NEW.practitioner_profile_id END
        END;
    END IF;
    IF affected_member_id IS NULL THEN
        SELECT workforce_member_id INTO affected_member_id
          FROM practitioner_profiles
         WHERE organization_id=affected_organization_id AND id=affected_practitioner_id;
    END IF;
    PERFORM careos_invalidate_member_readiness(
        affected_organization_id,affected_member_id,'evaluated_state_changed');
    RETURN NULL;
END;
$$;

CREATE FUNCTION careos_invalidate_readiness_from_person_source()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    affected_person_id uuid;
    member_record record;
BEGIN
    affected_person_id:=CASE
        WHEN TG_TABLE_NAME='person_profiles' THEN CASE WHEN TG_OP='DELETE' THEN OLD.id ELSE NEW.id END
        ELSE CASE WHEN TG_OP='DELETE' THEN OLD.person_id ELSE NEW.person_id END
    END;
    FOR member_record IN
        SELECT member.organization_id,member.id
          FROM organization_person_links link
          JOIN workforce_members member
            ON member.organization_id=link.organization_id
           AND member.organization_person_link_id=link.id
         WHERE link.person_id=affected_person_id
           AND link.organization_id=nullif(current_setting('app.current_organization_id',true),'')::uuid
    LOOP
        PERFORM careos_invalidate_member_readiness(
            member_record.organization_id,member_record.id,'identity_state_changed');
    END LOOP;
    RETURN NULL;
END;
$$;

CREATE FUNCTION careos_invalidate_readiness_from_credential_evidence()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    affected_organization_id uuid;
    affected_credential_id uuid;
    affected_document_id uuid;
    affected_member_id uuid;
BEGIN
    affected_organization_id:=CASE WHEN TG_OP='DELETE' THEN OLD.organization_id ELSE NEW.organization_id END;
    IF TG_TABLE_NAME='credential_documents' THEN
        affected_credential_id:=CASE WHEN TG_OP='DELETE' THEN OLD.practitioner_credential_id ELSE NEW.practitioner_credential_id END;
    ELSIF TG_TABLE_NAME='credential_scan_attempts' THEN
        affected_document_id:=CASE WHEN TG_OP='DELETE' THEN OLD.credential_document_id ELSE NEW.credential_document_id END;
    ELSIF TG_TABLE_NAME='credential_verifications' THEN
        affected_credential_id:=CASE WHEN TG_OP='DELETE' THEN OLD.practitioner_credential_id ELSE NEW.practitioner_credential_id END;
    ELSE
        affected_credential_id:=CASE WHEN TG_OP='DELETE' THEN OLD.practitioner_credential_id ELSE NEW.practitioner_credential_id END;
        affected_document_id:=CASE WHEN TG_OP='DELETE' THEN OLD.credential_document_id ELSE NEW.credential_document_id END;
    END IF;
    IF affected_credential_id IS NULL AND affected_document_id IS NOT NULL THEN
        SELECT practitioner_credential_id INTO affected_credential_id
          FROM credential_documents
         WHERE organization_id=affected_organization_id AND id=affected_document_id;
    END IF;
    SELECT workforce_member_id INTO affected_member_id
      FROM practitioner_credentials
     WHERE organization_id=affected_organization_id AND id=affected_credential_id;
    PERFORM careos_invalidate_member_readiness(
        affected_organization_id,affected_member_id,'credential_evidence_changed');
    RETURN NULL;
END;
$$;

CREATE FUNCTION careos_invalidate_readiness_from_availability_child()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    affected_organization_id uuid;
    affected_profile_id uuid;
    affected_member_id uuid;
BEGIN
    affected_organization_id:=CASE WHEN TG_OP='DELETE' THEN OLD.organization_id ELSE NEW.organization_id END;
    affected_profile_id:=CASE WHEN TG_OP='DELETE' THEN OLD.availability_profile_id ELSE NEW.availability_profile_id END;
    SELECT workforce_member_id INTO affected_member_id
      FROM availability_profiles
     WHERE organization_id=affected_organization_id AND id=affected_profile_id;
    PERFORM careos_invalidate_member_readiness(
        affected_organization_id,affected_member_id,'availability_state_changed');
    RETURN NULL;
END;
$$;

CREATE FUNCTION careos_invalidate_organization_readiness(
    affected_organization_id uuid,
    invalidation_code text)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE member_record record;
BEGIN
    IF nullif(current_setting('app.current_organization_id',true),'') IS NULL THEN
        IF session_user='${applicationRole}' THEN
            RAISE EXCEPTION 'missing Module 2 readiness invalidation tenant'
                USING ERRCODE='42501';
        END IF;
        RETURN;
    END IF;
    IF affected_organization_id IS DISTINCT FROM
       nullif(current_setting('app.current_organization_id',true),'')::uuid THEN
        RAISE EXCEPTION 'invalid Module 2 readiness invalidation tenant'
            USING ERRCODE='42501';
    END IF;
    FOR member_record IN
        SELECT id FROM workforce_members WHERE organization_id=affected_organization_id
    LOOP
        PERFORM careos_invalidate_member_readiness(
            affected_organization_id,member_record.id,invalidation_code);
    END LOOP;
END;
$$;

CREATE FUNCTION careos_invalidate_readiness_from_organization_source()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE affected_organization_id uuid;
BEGIN
    affected_organization_id:=CASE
        WHEN TG_TABLE_NAME='organizations' THEN CASE WHEN TG_OP='DELETE' THEN OLD.id ELSE NEW.id END
        ELSE CASE WHEN TG_OP='DELETE' THEN OLD.organization_id ELSE NEW.organization_id END
    END;
    PERFORM careos_invalidate_organization_readiness(
        affected_organization_id,'organization_dependency_changed');
    RETURN NULL;
END;
$$;

DO $$
DECLARE table_name text;
BEGIN
    FOREACH table_name IN ARRAY ARRAY[
        'person_profiles','person_profile_aliases','person_contacts','person_addresses'
    ] LOOP
        EXECUTE format(
            'CREATE TRIGGER %I AFTER INSERT OR UPDATE OR DELETE ON %I FOR EACH ROW WHEN (current_setting(''app.current_operation_key'',true)<>''workforce.validation.run'') EXECUTE FUNCTION careos_invalidate_readiness_from_person_source()',
            table_name||'_invalidate_readiness',table_name);
    END LOOP;
    FOREACH table_name IN ARRAY ARRAY[
        'credential_documents','credential_scan_attempts','credential_verifications','credential_legal_holds'
    ] LOOP
        EXECUTE format(
            'CREATE TRIGGER %I AFTER INSERT OR UPDATE OR DELETE ON %I FOR EACH ROW WHEN (current_setting(''app.current_operation_key'',true)<>''workforce.validation.run'') EXECUTE FUNCTION careos_invalidate_readiness_from_credential_evidence()',
            table_name||'_invalidate_readiness',table_name);
    END LOOP;
    FOREACH table_name IN ARRAY ARRAY['availability_periods','availability_exceptions'] LOOP
        EXECUTE format(
            'CREATE TRIGGER %I AFTER INSERT OR UPDATE OR DELETE ON %I FOR EACH ROW WHEN (current_setting(''app.current_operation_key'',true)<>''workforce.validation.run'') EXECUTE FUNCTION careos_invalidate_readiness_from_availability_child()',
            table_name||'_invalidate_readiness',table_name);
    END LOOP;
    FOREACH table_name IN ARRAY ARRAY[
        'organizations','organization_memberships','facilities','organization_units',
        'service_locations','service_definitions','service_assignments',
        'workforce_configuration_snapshots','workforce_registry_definitions',
        'workforce_registry_entries','workforce_registry_versions'
    ] LOOP
        EXECUTE format(
            'CREATE TRIGGER %I AFTER INSERT OR UPDATE OR DELETE ON %I FOR EACH ROW WHEN (current_setting(''app.current_operation_key'',true) NOT IN (''workforce.validation.run'',''workforce.activation.execute'')) EXECUTE FUNCTION careos_invalidate_readiness_from_organization_source()',
            table_name||'_invalidate_readiness',table_name);
    END LOOP;
END;
$$;

REVOKE ALL ON FUNCTION careos_invalidate_member_readiness(uuid,uuid,text) FROM PUBLIC;
REVOKE ALL ON FUNCTION careos_invalidate_organization_readiness(uuid,text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION careos_invalidate_member_readiness(uuid,uuid,text) TO "${applicationRole}";
GRANT EXECUTE ON FUNCTION careos_invalidate_organization_readiness(uuid,text) TO "${applicationRole}";
