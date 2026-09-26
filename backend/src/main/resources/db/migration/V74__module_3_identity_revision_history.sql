-- Retain provenance-aware identity versions without copying confidential values
-- into audit/outbox evidence. The patient profile remains the current projection;
-- this append-only table preserves correction lineage and the exact supplied facts.
CREATE TABLE patient_identity_revisions (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    patient_id uuid NOT NULL,
    predecessor_revision_id uuid,
    profile_revision bigint NOT NULL,
    official_given_name varchar(120),
    official_family_name varchar(120),
    name_to_use varchar(160),
    name_state varchar(32) NOT NULL,
    birth_date_value date,
    birth_date_precision varchar(16),
    birth_date_certainty varchar(16) NOT NULL,
    administrative_sex_code varchar(80),
    gender_identity_code varchar(80),
    pronouns_code varchar(80),
    provenance_source varchar(80) NOT NULL,
    effective_from timestamptz NOT NULL,
    classification varchar(24) NOT NULL DEFAULT 'CONFIDENTIAL',
    policy_version varchar(80) NOT NULL DEFAULT 'm3-candidate-1',
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    UNIQUE (organization_id, id),
    UNIQUE (organization_id, patient_id, profile_revision),
    FOREIGN KEY (organization_id, patient_id)
        REFERENCES patient_profiles(organization_id, id),
    FOREIGN KEY (organization_id, predecessor_revision_id)
        REFERENCES patient_identity_revisions(organization_id, id),
    CHECK (profile_revision >= 0),
    CHECK (predecessor_revision_id IS NULL OR predecessor_revision_id <> id),
    CHECK (name_state IN ('provided','temporary','unnamed','unknown')),
    CHECK (official_given_name IS NULL
           OR char_length(btrim(official_given_name)) BETWEEN 1 AND 120),
    CHECK (official_family_name IS NULL
           OR char_length(btrim(official_family_name)) BETWEEN 1 AND 120),
    CHECK (name_to_use IS NULL OR char_length(btrim(name_to_use)) BETWEEN 1 AND 160),
    CHECK (birth_date_precision IS NULL OR birth_date_precision IN ('year','month','day')),
    CHECK (birth_date_certainty IN ('exact','estimated','unknown')),
    CHECK ((birth_date_certainty = 'unknown') = (birth_date_value IS NULL)),
    CHECK ((birth_date_value IS NULL) = (birth_date_precision IS NULL)),
    CHECK (birth_date_value IS NULL OR birth_date_value <= CURRENT_DATE),
    CHECK (classification IN ('CONFIDENTIAL','RESTRICTED')),
    CHECK (isfinite(effective_from) AND isfinite(created_at))
);

INSERT INTO patient_identity_revisions
    (organization_id,patient_id,profile_revision,official_given_name,
     official_family_name,name_to_use,name_state,birth_date_value,
     birth_date_precision,birth_date_certainty,administrative_sex_code,
     gender_identity_code,pronouns_code,provenance_source,effective_from,
     classification,policy_version,created_at,created_by)
SELECT organization_id,id,lock_version,official_given_name,official_family_name,
       name_to_use,name_state,birth_date_value,birth_date_precision,
       birth_date_certainty,administrative_sex_code,gender_identity_code,
       pronouns_code,provenance_source,updated_at,classification,policy_version,
       updated_at,updated_by
FROM patient_profiles;

CREATE INDEX patient_identity_revisions_timeline_idx
    ON patient_identity_revisions
       (organization_id,patient_id,effective_from DESC,id DESC);

ALTER TABLE patient_identity_revisions ENABLE ROW LEVEL SECURITY;
ALTER TABLE patient_identity_revisions FORCE ROW LEVEL SECURITY;

CREATE POLICY patient_identity_revisions_tenant_policy
    ON patient_identity_revisions
    USING (organization_id =
        nullif(current_setting('app.current_organization_id', true), '')::uuid)
    WITH CHECK (organization_id =
        nullif(current_setting('app.current_organization_id', true), '')::uuid);

CREATE FUNCTION careos_validate_m3_identity_revision_insert()
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
BEGIN
    IF current_user <> '${applicationRole}' THEN
        RETURN NEW;
    END IF;

    IF configured_operation NOT IN ('patient.registration.manage','patient.profile.manage')
       OR NEW.organization_id IS DISTINCT FROM configured_organization
       OR configured_actor IS NULL
       OR NEW.created_by IS DISTINCT FROM configured_actor
       OR NOT EXISTS (
            SELECT 1
            FROM authorization_operations operation
            JOIN authorization_registry_releases release
              ON release.registry_version=operation.registry_version
             AND release.status='active'
            WHERE operation.operation_key=configured_operation
              AND operation.registry_version='m3-candidate-1'
              AND operation.status='active'
       )
       OR NOT EXISTS (
            SELECT 1
            FROM patient_profiles patient
            WHERE patient.organization_id=NEW.organization_id
              AND patient.id=NEW.patient_id
              AND patient.lock_version=NEW.profile_revision
       )
       OR (
            EXISTS (
                SELECT 1 FROM patient_identity_revisions prior
                WHERE prior.organization_id=NEW.organization_id
                  AND prior.patient_id=NEW.patient_id
            )
            AND (
                NEW.predecessor_revision_id IS NULL
                OR NOT EXISTS (
                    SELECT 1 FROM patient_identity_revisions predecessor
                    WHERE predecessor.organization_id=NEW.organization_id
                      AND predecessor.patient_id=NEW.patient_id
                      AND predecessor.id=NEW.predecessor_revision_id
                      AND predecessor.profile_revision<NEW.profile_revision
                )
            )
       ) THEN
        RAISE EXCEPTION 'invalid Module 3 identity-revision evidence'
            USING ERRCODE = '42501';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER patient_identity_revisions_validate_insert
    BEFORE INSERT ON patient_identity_revisions
    FOR EACH ROW EXECUTE FUNCTION careos_validate_m3_identity_revision_insert();

CREATE TRIGGER patient_identity_revisions_append_only
    BEFORE UPDATE OR DELETE ON patient_identity_revisions
    FOR EACH ROW EXECUTE FUNCTION careos_reject_m3_evidence_mutation();

REVOKE ALL ON patient_identity_revisions FROM PUBLIC;
GRANT SELECT, INSERT ON patient_identity_revisions TO "${applicationRole}";

COMMENT ON TABLE patient_identity_revisions IS
    'Append-only confidential identity versions; audit/outbox retain opaque lineage references only.';
