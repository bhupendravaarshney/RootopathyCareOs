-- Bind registration readiness to the exact run and patient revisions and a
-- short-lived server-calculated result. A child identity/contact change can no
-- longer reuse a previously successful validation digest.
ALTER TABLE patient_registration_runs
    ADD COLUMN validation_schema_version varchar(80),
    ADD COLUMN validation_source_revision bigint,
    ADD COLUMN validated_patient_revision bigint,
    ADD COLUMN validation_expires_at timestamptz,
    ADD COLUMN validation_result jsonb;

UPDATE patient_registration_runs run
SET validation_schema_version='patient-registration-v1-backfill',
    validation_source_revision=greatest(
        run.lock_version - CASE WHEN run.status='completed' THEN 2 ELSE 1 END,
        0),
    validated_patient_revision=patient.lock_version,
    validation_expires_at=run.validation_completed_at,
    validation_result='{"schemaVersion":"patient-registration-v1-backfill","outcome":"blocked","gates":[]}'::jsonb
FROM patient_profiles patient
WHERE run.validation_digest IS NOT NULL
  AND patient.organization_id=run.organization_id
  AND patient.id=run.selected_patient_id;

ALTER TABLE patient_registration_runs
    ADD CONSTRAINT patient_registration_runs_validation_binding_check CHECK (
        (validation_digest IS NULL
         AND validation_completed_at IS NULL
         AND validation_schema_version IS NULL
         AND validation_source_revision IS NULL
         AND validated_patient_revision IS NULL
         AND validation_expires_at IS NULL
         AND validation_result IS NULL)
        OR
        (validation_digest IS NOT NULL
         AND validation_completed_at IS NOT NULL
         AND validation_schema_version IS NOT NULL
         AND validation_source_revision IS NOT NULL
         AND validation_source_revision >= 0
         AND validated_patient_revision IS NOT NULL
         AND validated_patient_revision >= 0
         AND validation_expires_at IS NOT NULL
         AND validation_expires_at >= validation_completed_at
         AND validation_result IS NOT NULL
         AND jsonb_typeof(validation_result)='object')
    );

COMMENT ON COLUMN patient_registration_runs.validation_result IS
    'Server-calculated safe gate outcomes; submission additionally rechecks exact revisions, digest and expiry.';
