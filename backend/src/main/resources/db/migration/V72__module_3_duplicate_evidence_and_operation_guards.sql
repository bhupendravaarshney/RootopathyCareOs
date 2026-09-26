-- Complete the approved duplicate-candidate evidence vocabulary and narrow every
-- Module 3 table write to the exact operation families that own that table.
INSERT INTO audit_event_definitions
    (event_name,schema_version,display_name,description,subject_type,reason_required,
     required_payload_keys,allowed_payload_keys,payload_schema,status,registry_version)
VALUES
    ('patient.duplicate.detected',1,'Patient duplicate detected',
     'Explainable organization-local patient duplicate candidate creation.',
     'patient_duplicate_candidate',true,
     ARRAY['candidateId','patientAId','patientBId','patientARevision','patientBRevision',
           'detectorVersion','factorCategories','matchBand','resultDigest','reviewDueAt'],
     ARRAY['candidateId','patientAId','patientBId','patientARevision','patientBRevision',
           'detectorVersion','factorCategories','matchBand','resultDigest','reviewDueAt'],
     '{"type":"object","additionalProperties":false}'::jsonb,
     'active','m3-candidate-1');

INSERT INTO authorization_operation_events
    (operation_key,event_kind,event_name,schema_version,status,registry_version)
VALUES
    ('patient.registration.manage','audit','patient.duplicate.detected',1,
     'active','m3-candidate-1');

CREATE OR REPLACE FUNCTION careos_validate_m3_tenant_write()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    configured_organization uuid :=
        nullif(current_setting('app.current_organization_id', true), '')::uuid;
    configured_actor uuid :=
        nullif(current_setting('app.current_actor_id', true), '')::uuid;
    configured_operation text :=
        nullif(current_setting('app.current_operation_key', true), '');
    allowed_operations text[];
BEGIN
    IF current_user <> '${applicationRole}' THEN
        RETURN NEW;
    END IF;

    allowed_operations := CASE TG_TABLE_NAME
        WHEN 'patient_profiles' THEN ARRAY[
            'patient.registration.manage','patient.registration.submit',
            'patient.profile.manage','patient.contact.manage',
            'patient.preference.manage','patient.merge.execute'
        ]
        WHEN 'patient_identifiers' THEN ARRAY['patient.identifier.manage']
        WHEN 'patient_contacts' THEN ARRAY['patient.contact.manage']
        WHEN 'patient_addresses' THEN ARRAY['patient.contact.manage']
        WHEN 'communication_preferences' THEN ARRAY['patient.preference.manage']
        WHEN 'caregiver_relationships' THEN ARRAY['patient.profile.manage']
        WHEN 'patient_authority_grants' THEN ARRAY[
            'patient.proxy.request','patient.proxy.decide','patient.proxy.revoke'
        ]
        WHEN 'patient_portal_links' THEN ARRAY[
            'patient.portal_link.request','patient.portal_link.decide',
            'patient.portal_link.revoke','patient.portal_link.recover'
        ]
        WHEN 'patient_consents' THEN ARRAY['patient.consent.manage']
        WHEN 'privacy_restrictions' THEN ARRAY['patient.privacy.manage']
        WHEN 'patient_safety_flags' THEN ARRAY[
            'patient.safety_flag.propose','patient.safety_flag.verify',
            'patient.safety_flag.acknowledge','patient.safety_flag.resolve'
        ]
        WHEN 'patient_match_keys' THEN ARRAY[
            'patient.profile.manage','patient.contact.manage','patient.identifier.manage'
        ]
        WHEN 'patient_duplicate_candidates' THEN ARRAY[
            'patient.registration.manage','patient.duplicate.review',
            'patient.merge.request','patient.merge.execute'
        ]
        WHEN 'patient_merge_requests' THEN ARRAY[
            'patient.merge.request','patient.merge.decide','patient.merge.execute'
        ]
        WHEN 'patient_merge_decisions' THEN ARRAY[
            'patient.merge.decide','patient.merge.execute'
        ]
        WHEN 'patient_registration_runs' THEN ARRAY[
            'patient.registration.start','patient.duplicate.search',
            'patient.registration.manage','patient.registration.submit'
        ]
        ELSE ARRAY[]::text[]
    END;

    IF NEW.organization_id IS DISTINCT FROM configured_organization
       OR configured_actor IS NULL
       OR configured_operation IS NULL
       OR NOT configured_operation = ANY(allowed_operations)
       OR NOT EXISTS (
           SELECT 1
           FROM authorization_operations operation
           JOIN authorization_registry_releases release
             ON release.registry_version = operation.registry_version
            AND release.status = 'active'
           WHERE operation.operation_key = configured_operation
             AND operation.registry_version = 'm3-candidate-1'
             AND operation.status = 'active'
       ) THEN
        RAISE EXCEPTION 'invalid Module 3 operation-bound tenant write context'
            USING ERRCODE = '42501';
    END IF;

    IF TG_OP = 'INSERT' THEN
        IF NEW.created_by IS DISTINCT FROM configured_actor
           OR NEW.updated_by IS DISTINCT FROM configured_actor
           OR NEW.lock_version <> 0 THEN
            RAISE EXCEPTION 'invalid Module 3 creation evidence'
                USING ERRCODE = '23514';
        END IF;
    ELSE
        IF NEW.id IS DISTINCT FROM OLD.id
           OR NEW.organization_id IS DISTINCT FROM OLD.organization_id
           OR NEW.created_at IS DISTINCT FROM OLD.created_at
           OR NEW.created_by IS DISTINCT FROM OLD.created_by
           OR NEW.updated_by IS DISTINCT FROM configured_actor
           OR NEW.lock_version <> OLD.lock_version + 1
           OR NEW.updated_at < OLD.updated_at
           OR NEW.updated_at > clock_timestamp() + interval '5 seconds' THEN
            RAISE EXCEPTION 'invalid Module 3 revision evidence'
                USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
