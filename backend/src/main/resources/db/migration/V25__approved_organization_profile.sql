ALTER TABLE organizations
    DROP CONSTRAINT organizations_legal_name_check,
    DROP CONSTRAINT organizations_display_name_check,
    DROP CONSTRAINT organizations_timezone_check,
    ADD COLUMN trading_name varchar(160),
    ADD COLUMN organization_type varchar(32),
    ADD COLUMN locale varchar(255),
    ADD CONSTRAINT organizations_legal_name_check
        CHECK (legal_name = btrim(legal_name)
               AND legal_name = normalize(legal_name, NFC)
               AND char_length(legal_name) BETWEEN 2 AND 200
               AND legal_name !~ '[[:cntrl:]<>]') NOT VALID,
    ADD CONSTRAINT organizations_display_name_check
        CHECK (display_name = btrim(display_name)
               AND display_name = normalize(display_name, NFC)
               AND char_length(display_name) BETWEEN 2 AND 120
               AND display_name !~ '[[:cntrl:]<>]') NOT VALID,
    ADD CONSTRAINT organizations_trading_name_check
        CHECK (trading_name IS NULL
               OR (trading_name = btrim(trading_name)
                   AND trading_name = normalize(trading_name, NFC)
                   AND char_length(trading_name) BETWEEN 2 AND 160
                   AND trading_name !~ '[[:cntrl:]<>]')),
    ADD CONSTRAINT organizations_type_check
        CHECK (organization_type IS NULL
               OR organization_type IN ('care_provider', 'care_network', 'administrative')),
    ADD CONSTRAINT organizations_timezone_check
        CHECK (timezone = btrim(timezone)
               AND char_length(timezone) BETWEEN 1 AND 80
               AND timezone !~ '[[:cntrl:]]'),
    ADD CONSTRAINT organizations_locale_check
        CHECK (locale IS NULL
               OR (locale = btrim(locale)
                   AND char_length(locale) BETWEEN 2 AND 255
                   AND locale ~ '^[A-Za-z]{2,8}(-[A-Za-z0-9]{1,8})*$')),
    ADD CONSTRAINT organizations_lifecycle_check
        CHECK (status IN ('draft', 'under_review', 'active', 'suspended', 'closed')) NOT VALID,
    ADD CONSTRAINT organizations_profile_required_check
        CHECK (organization_type IS NOT NULL AND locale IS NOT NULL) NOT VALID;

CREATE OR REPLACE FUNCTION careos_validate_runtime_organization_update()
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
    configured_reason text :=
        nullif(current_setting('app.current_authorization_reason', true), '');
BEGIN
    IF current_user <> '${applicationRole}' THEN
        RETURN NEW;
    END IF;

    IF configured_operation IS DISTINCT FROM 'organization.profile.update'
        OR configured_organization IS DISTINCT FROM OLD.id
        OR configured_actor IS NULL
        OR configured_reason IS NULL
        OR configured_reason IS DISTINCT FROM btrim(configured_reason)
        OR configured_reason IS DISTINCT FROM normalize(configured_reason, NFC)
        OR char_length(configured_reason) NOT BETWEEN 10 AND 500
        OR configured_reason ~ '[[:cntrl:]]' THEN
        RAISE EXCEPTION 'organization profile updates require the authorized approved operation'
            USING ERRCODE = '42501';
    END IF;

    IF NEW.id IS DISTINCT FROM OLD.id
        OR NEW.status IS DISTINCT FROM OLD.status
        OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'organization profile update attempted to change protected lifecycle data'
            USING ERRCODE = '23514';
    END IF;

    IF ROW(
            NEW.legal_name,
            NEW.display_name,
            NEW.trading_name,
            NEW.organization_type,
            NEW.country_code,
            NEW.timezone,
            NEW.locale)
        IS NOT DISTINCT FROM
       ROW(
            OLD.legal_name,
            OLD.display_name,
            OLD.trading_name,
            OLD.organization_type,
            OLD.country_code,
            OLD.timezone,
            OLD.locale) THEN
        RAISE EXCEPTION 'organization profile update must change at least one field'
            USING ERRCODE = '23514';
    END IF;

    IF NEW.organization_type IS NULL OR NEW.locale IS NULL THEN
        RAISE EXCEPTION 'organization type and locale are required for governed profile updates'
            USING ERRCODE = '23514';
    END IF;

    NEW.updated_at := greatest(clock_timestamp(), OLD.updated_at + interval '1 microsecond');

    IF NEW.lock_version <> OLD.lock_version + 1
        OR NEW.updated_by IS DISTINCT FROM configured_actor THEN
        RAISE EXCEPTION 'organization profile revision evidence is invalid'
            USING ERRCODE = '23514';
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_timezone_names WHERE name = NEW.timezone) THEN
        RAISE EXCEPTION 'organization timezone is not an IANA timezone identifier'
            USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END;
$$;

COMMENT ON COLUMN organizations.trading_name IS
    'Optional approved public trading identity; blank values normalize to null.';
COMMENT ON COLUMN organizations.organization_type IS
    'Approved organization type. Legacy rows remain null and readiness-blocked until a governed profile update.';
COMMENT ON COLUMN organizations.locale IS
    'Canonical BCP 47 organization locale. Legacy rows remain null and readiness-blocked until a governed profile update.';
COMMENT ON CONSTRAINT organizations_legal_name_check ON organizations IS
    'Enforces the approved trimmed NFC 2-200 boundary on new or changed rows while legacy invalid rows remain readiness-blocked.';
COMMENT ON CONSTRAINT organizations_display_name_check ON organizations IS
    'Enforces the approved trimmed NFC 2-120 boundary on new or changed rows while legacy invalid rows remain readiness-blocked.';
COMMENT ON CONSTRAINT organizations_lifecycle_check ON organizations IS
    'Enforces the approved lifecycle vocabulary on new or changed rows while legacy unknown states remain readiness-blocked.';
COMMENT ON CONSTRAINT organizations_profile_required_check ON organizations IS
    'Enforces required type and locale for new or changed rows without inventing values for pre-V25 tenants.';
COMMENT ON FUNCTION careos_validate_runtime_organization_update() IS
    'Restricts runtime updates to the exact approved organization profile fields, tenant/actor/reason context, IANA timezone, and monotonic revision evidence.';
