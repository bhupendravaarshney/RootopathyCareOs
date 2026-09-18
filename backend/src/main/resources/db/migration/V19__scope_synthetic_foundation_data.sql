DO $careos$
DECLARE
    reference_organization_id constant uuid :=
        '01900000-0000-7000-8000-000000000001'::uuid;
    reference_facility_id constant uuid :=
        '01900000-0000-7000-8000-000000000101'::uuid;
    synthetic_data_enabled constant boolean :=
        '${foundationSyntheticDataEnabled}'::boolean;
BEGIN
    IF synthetic_data_enabled THEN
        RETURN;
    END IF;

    PERFORM set_config(
        'app.current_organization_id', reference_organization_id::text, true);

    IF EXISTS (
        SELECT 1
        FROM organizations
        WHERE id = reference_organization_id
          AND (
              legal_name IS DISTINCT FROM 'ROOTOPATHY Care Network Private Limited'
              OR display_name IS DISTINCT FROM 'ROOTOPATHY Care Network'
              OR country_code IS DISTINCT FROM 'IN'
              OR timezone IS DISTINCT FROM 'Asia/Kolkata'
              OR status IS DISTINCT FROM 'draft'
              OR lock_version IS DISTINCT FROM 0
              OR updated_by IS NOT NULL
          )
    ) THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'the legacy synthetic organization has been changed and cannot be removed automatically',
            HINT = 'Migrate the tenant explicitly, then remove the reserved synthetic organization before retrying V19.';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM facilities
        WHERE organization_id = reference_organization_id
          AND (
              id IS DISTINCT FROM reference_facility_id
              OR name IS DISTINCT FROM 'ROOTOPATHY Greater Noida'
              OR code IS DISTINCT FROM 'GNO-01'
              OR status IS DISTINCT FROM 'draft'
              OR lock_version IS DISTINCT FROM 0
          )
    ) THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'the legacy synthetic organization contains changed or additional facilities',
            HINT = 'Migrate the tenant explicitly, then remove the reserved synthetic organization before retrying V19.';
    END IF;

    DELETE FROM facilities
    WHERE id = reference_facility_id
      AND organization_id = reference_organization_id;

    BEGIN
        DELETE FROM organizations
        WHERE id = reference_organization_id;
    EXCEPTION
        WHEN foreign_key_violation THEN
            RAISE EXCEPTION USING
                ERRCODE = '23503',
                MESSAGE = 'the legacy synthetic organization contains tenant data and cannot be removed automatically',
                HINT = 'Migrate the tenant explicitly, then remove the reserved synthetic organization before retrying V19.';
    END;
END
$careos$;

COMMENT ON TABLE organizations IS
    'Tenant organizations. V19 removes the legacy synthetic tenant unless the explicitly non-production Flyway placeholder retains local/test fixtures.';
