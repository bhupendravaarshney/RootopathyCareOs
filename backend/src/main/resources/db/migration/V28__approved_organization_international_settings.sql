CREATE TABLE organization_international_settings (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    source varchar(32) NOT NULL,
    country_code char(2) NOT NULL,
    timezone varchar(120) NOT NULL,
    locale varchar(100) NOT NULL,
    language varchar(100) NOT NULL,
    currency_code char(3) NOT NULL,
    week_start varchar(9) NOT NULL,
    effective_from timestamptz NOT NULL,
    effective_to timestamptz,
    supersedes_id uuid,
    status varchar(24) NOT NULL,
    lock_version bigint NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL REFERENCES users(id),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL REFERENCES users(id),
    UNIQUE (organization_id, id),
    CONSTRAINT organization_international_settings_supersedes_fk
        FOREIGN KEY (organization_id, supersedes_id)
        REFERENCES organization_international_settings(organization_id, id),
    CHECK (source IN ('organization_default', 'configured')),
    CHECK (country_code ~ '^[A-Z]{2}$'),
    CHECK (timezone = btrim(timezone)
           AND timezone = normalize(timezone, NFC)
           AND char_length(timezone) BETWEEN 1 AND 120
           AND timezone !~ '[[:cntrl:]<>]'),
    CHECK (locale = btrim(locale)
           AND locale = normalize(locale, NFC)
           AND char_length(locale) BETWEEN 2 AND 100
           AND locale ~ '^[A-Za-z]{2,8}(-[A-Za-z0-9]{1,8})*$'),
    CHECK (language = btrim(language)
           AND language = normalize(language, NFC)
           AND char_length(language) BETWEEN 2 AND 100
           AND language ~ '^[A-Za-z]{2,8}(-[A-Za-z0-9]{1,8})*$'),
    CHECK (currency_code IN (
        'AED','AFN','ALL','AMD','ANG','AOA','ARS','AUD','AWG','AZN','BAM','BBD',
        'BDT','BGN','BHD','BIF','BMD','BND','BOB','BOV','BRL','BSD','BTN','BWP',
        'BYN','BZD','CAD','CDF','CHE','CHF','CHW','CLF','CLP','CNY','COP','COU',
        'CRC','CUC','CUP','CVE','CZK','DJF','DKK','DOP','DZD','EGP','ERN','ETB',
        'EUR','FJD','FKP','GBP','GEL','GHS','GIP','GMD','GNF','GTQ','GYD','HKD',
        'HNL','HRK','HTG','HUF','IDR','ILS','INR','IQD','IRR','ISK','JMD','JOD',
        'JPY','KES','KGS','KHR','KMF','KPW','KRW','KWD','KYD','KZT','LAK','LBP',
        'LKR','LRD','LSL','LYD','MAD','MDL','MGA','MKD','MMK','MNT','MOP','MRU',
        'MUR','MVR','MWK','MXN','MXV','MYR','MZN','NAD','NGN','NIO','NOK','NPR',
        'NZD','OMR','PAB','PEN','PGK','PHP','PKR','PLN','PYG','QAR','RON','RSD',
        'RUB','RWF','SAR','SBD','SCR','SDG','SEK','SGD','SHP','SLE','SOS','SRD',
        'SSP','STN','SVC','SYP','SZL','THB','TJS','TMT','TND','TOP','TRY','TTD',
        'TWD','TZS','UAH','UGX','USD','USN','UYI','UYU','UZS','VED','VES','VND',
        'VUV','WST','XAF','XAG','XAU','XBA','XBB','XBC','XBD','XCD','XCG','XDR',
        'XOF','XPD','XPF','XPT','XSU','XTS','XUA','XXX','YER','ZAR','ZMW','ZWG',
        'ZWL'
    )),
    CHECK (week_start IN
        ('MONDAY','TUESDAY','WEDNESDAY','THURSDAY','FRIDAY','SATURDAY','SUNDAY')),
    CHECK (isfinite(effective_from)),
    CHECK (effective_to IS NULL
           OR (isfinite(effective_to) AND effective_to > effective_from)),
    CHECK (supersedes_id IS NULL OR supersedes_id <> id),
    CHECK (status IN ('active', 'scheduled', 'superseded')),
    CHECK (lock_version >= 0),
    CHECK (isfinite(created_at) AND isfinite(updated_at) AND updated_at >= created_at)
);

CREATE UNIQUE INDEX organization_international_settings_one_replacement_uq
    ON organization_international_settings (organization_id, supersedes_id)
    WHERE supersedes_id IS NOT NULL;

CREATE UNIQUE INDEX organization_international_settings_one_pending_uq
    ON organization_international_settings (organization_id)
    WHERE status = 'scheduled';

CREATE INDEX organization_international_settings_projection_idx
    ON organization_international_settings
        (organization_id, effective_from DESC, lock_version DESC, id DESC);

ALTER TABLE organization_international_settings ENABLE ROW LEVEL SECURITY;
ALTER TABLE organization_international_settings FORCE ROW LEVEL SECURITY;
CREATE POLICY organization_international_settings_tenant_policy
    ON organization_international_settings
    USING (organization_id =
        nullif(current_setting('app.current_organization_id', true), '')::uuid)
    WITH CHECK (organization_id =
        nullif(current_setting('app.current_organization_id', true), '')::uuid);

INSERT INTO authorization_operations
    (operation_key, permission_key, display_name, description, mutation, denial_mode,
     reason_required, recent_authentication_required,
     recent_authentication_max_age_seconds, maximum_future_skew_seconds,
     maker_checker_required, status, registry_version, mfa_required)
VALUES
    ('organization.settings.read', 'organization.settings.read',
     'Read international settings',
     'Read the effective and scheduled organization international-settings projection.',
     false, 'hidden', false, false, NULL, NULL, false,
     'active', 'm1-candidate-1', false),
    ('organization.settings.update', 'organization.settings.manage',
     'Schedule international settings',
     'Schedule one governed effective organization international-settings version.',
     true, 'explicit', true, false, NULL, NULL, false,
     'active', 'm1-candidate-1', false);

INSERT INTO audit_event_definitions
    (event_name, schema_version, display_name, description, subject_type,
     reason_required, required_payload_keys, allowed_payload_keys,
     payload_schema, status, registry_version)
VALUES
    ('organization.settings.changed', 1, 'Organization settings changed',
     'A governed effective organization international-settings version was scheduled.',
     'organization_international_settings', true,
     ARRAY['changedFields', 'effectiveFrom', 'lockVersion'],
     ARRAY['changedFields', 'effectiveFrom', 'lockVersion'],
     '{"type":"object"}'::jsonb, 'active', 'm1-candidate-1');

INSERT INTO outbox_event_definitions
    (event_name, schema_version, description, aggregate_type,
     required_payload_keys, allowed_payload_keys, payload_schema, status, registry_version)
VALUES
    ('organization.settings.changed', 1,
     'A governed effective organization international-settings version was scheduled.',
     'organization_international_settings',
     ARRAY['changedFields', 'effectiveFrom', 'lockVersion'],
     ARRAY['changedFields', 'effectiveFrom', 'lockVersion'],
     '{"type":"object"}'::jsonb, 'active', 'm1-candidate-1');

INSERT INTO authorization_operation_events
    (operation_key, event_kind, event_name, schema_version, status, registry_version)
VALUES
    ('organization.settings.update', 'audit',
     'organization.settings.changed', 1, 'active', 'm1-candidate-1'),
    ('organization.settings.update', 'outbox',
     'organization.settings.changed', 1, 'active', 'm1-candidate-1');

CREATE FUNCTION careos_validate_organization_international_settings_write()
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
    organization_lock_version bigint;
    organization_country char(2);
    organization_timezone varchar(120);
    organization_locale varchar(100);
    predecessor_status varchar(24);
    predecessor_effective_from timestamptz;
    predecessor_effective_to timestamptz;
    predecessor_lock_version bigint;
BEGIN
    IF current_user <> '${applicationRole}' THEN
        RETURN NEW;
    END IF;

    IF NEW.organization_id IS DISTINCT FROM configured_organization
        OR configured_actor IS NULL
        OR configured_operation IS DISTINCT FROM 'organization.settings.update'
        OR configured_reason IS NULL
        OR configured_reason IS DISTINCT FROM btrim(configured_reason)
        OR configured_reason IS DISTINCT FROM normalize(configured_reason, NFC)
        OR char_length(configured_reason) NOT BETWEEN 10 AND 500
        OR configured_reason ~ '[[:cntrl:]]' THEN
        RAISE EXCEPTION 'international-settings writes require exact tenant, actor, operation, and reason context'
            USING ERRCODE = '42501';
    END IF;

    SELECT organizations.lock_version, organizations.country_code,
           organizations.timezone, organizations.locale
    INTO organization_lock_version, organization_country,
         organization_timezone, organization_locale
    FROM organizations
    WHERE organizations.id = NEW.organization_id
    FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'international-settings tenant is unavailable'
            USING ERRCODE = '42501';
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_timezone_names WHERE name = NEW.timezone) THEN
        RAISE EXCEPTION 'international-settings timezone is not an IANA identifier'
            USING ERRCODE = '23514';
    END IF;

    IF TG_OP = 'INSERT' THEN
        IF NEW.created_by IS DISTINCT FROM configured_actor
            OR NEW.updated_by IS DISTINCT FROM configured_actor THEN
            RAISE EXCEPTION 'international-settings creation evidence is invalid'
                USING ERRCODE = '23514';
        END IF;

        IF NEW.source = 'organization_default' THEN
            IF NEW.supersedes_id IS NOT NULL
                OR NEW.status <> 'superseded'
                OR NEW.effective_from > clock_timestamp()
                OR NEW.effective_to IS NULL
                OR NEW.effective_to <= clock_timestamp()
                OR NEW.lock_version <> organization_lock_version + 1
                OR NEW.country_code IS DISTINCT FROM organization_country
                OR NEW.timezone IS DISTINCT FROM organization_timezone
                OR (organization_locale IS NOT NULL
                    AND NEW.locale IS DISTINCT FROM organization_locale)
                OR EXISTS (
                    SELECT 1 FROM organization_international_settings existing
                    WHERE existing.organization_id = NEW.organization_id
                ) THEN
                RAISE EXCEPTION 'invalid international-settings default baseline'
                    USING ERRCODE = '23514';
            END IF;
        ELSE
            IF NEW.supersedes_id IS NULL
                OR NEW.status <> 'scheduled'
                OR NEW.effective_from <= clock_timestamp()
                OR NEW.effective_to IS NOT NULL THEN
                RAISE EXCEPTION 'international-settings versions must be future scheduled replacements'
                    USING ERRCODE = '23514';
            END IF;

            SELECT predecessor.status, predecessor.effective_from,
                   predecessor.effective_to, predecessor.lock_version
            INTO predecessor_status, predecessor_effective_from,
                 predecessor_effective_to, predecessor_lock_version
            FROM organization_international_settings predecessor
            WHERE predecessor.organization_id = NEW.organization_id
              AND predecessor.id = NEW.supersedes_id
            FOR UPDATE;
            IF NOT FOUND
                OR predecessor_status <> 'superseded'
                OR predecessor_effective_from > clock_timestamp()
                OR predecessor_effective_to IS DISTINCT FROM NEW.effective_from
                OR predecessor_lock_version <> NEW.lock_version THEN
                RAISE EXCEPTION 'invalid international-settings replacement link'
                    USING ERRCODE = '23514';
            END IF;
        END IF;

        IF EXISTS (
            SELECT 1
            FROM organization_international_settings other
            WHERE other.organization_id = NEW.organization_id
              AND other.id <> coalesce(NEW.supersedes_id, NEW.id)
              AND tstzrange(other.effective_from, other.effective_to, '[)')
                  && tstzrange(NEW.effective_from, NEW.effective_to, '[)')
        ) THEN
            RAISE EXCEPTION 'international-settings effective ranges overlap'
                USING ERRCODE = '23P01';
        END IF;
        NEW.created_at := clock_timestamp();
        NEW.updated_at := NEW.created_at;
    ELSE
        IF ROW(NEW.id, NEW.organization_id, NEW.source,
               NEW.country_code, NEW.timezone, NEW.locale, NEW.language,
               NEW.currency_code, NEW.week_start, NEW.effective_from,
               NEW.supersedes_id, NEW.created_at, NEW.created_by)
           IS DISTINCT FROM
           ROW(OLD.id, OLD.organization_id, OLD.source,
               OLD.country_code, OLD.timezone, OLD.locale, OLD.language,
               OLD.currency_code, OLD.week_start, OLD.effective_from,
               OLD.supersedes_id, OLD.created_at, OLD.created_by)
            OR OLD.status NOT IN ('active', 'scheduled')
            OR OLD.effective_from > clock_timestamp()
            OR OLD.effective_to IS NOT NULL
            OR NEW.status <> 'superseded'
            OR NEW.effective_to IS NULL
            OR NEW.effective_to <= clock_timestamp()
            OR NEW.lock_version <> OLD.lock_version + 1
            OR NEW.updated_by IS DISTINCT FROM configured_actor THEN
            RAISE EXCEPTION 'international-settings history or revision evidence is invalid'
                USING ERRCODE = '23514';
        END IF;
        NEW.updated_at := greatest(clock_timestamp(), OLD.updated_at + interval '1 microsecond');
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER organization_international_settings_validate_write
    BEFORE INSERT OR UPDATE ON organization_international_settings
    FOR EACH ROW EXECUTE FUNCTION
        careos_validate_organization_international_settings_write();

CREATE FUNCTION careos_validate_organization_international_settings_commit()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF current_user <> '${applicationRole}' THEN
        RETURN NULL;
    END IF;
    IF NEW.status = 'superseded'
        AND NOT EXISTS (
            SELECT 1
            FROM organization_international_settings replacement
            WHERE replacement.organization_id = NEW.organization_id
              AND replacement.supersedes_id = NEW.id
              AND replacement.status = 'scheduled'
              AND replacement.effective_from = NEW.effective_to
              AND replacement.lock_version = NEW.lock_version
        ) THEN
        RAISE EXCEPTION 'a superseded international-settings version requires its replacement'
            USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER organization_international_settings_commit
    AFTER INSERT OR UPDATE ON organization_international_settings
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION
        careos_validate_organization_international_settings_commit();

CREATE FUNCTION careos_reject_organization_international_settings_delete()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'international-settings history cannot be deleted';
END;
$$;

CREATE TRIGGER organization_international_settings_no_delete
    BEFORE DELETE ON organization_international_settings
    FOR EACH ROW EXECUTE FUNCTION
        careos_reject_organization_international_settings_delete();

REVOKE ALL ON organization_international_settings FROM PUBLIC;
REVOKE ALL ON FUNCTION
    careos_validate_organization_international_settings_write() FROM PUBLIC;
REVOKE ALL ON FUNCTION
    careos_validate_organization_international_settings_commit() FROM PUBLIC;
REVOKE ALL ON FUNCTION
    careos_reject_organization_international_settings_delete() FROM PUBLIC;

GRANT SELECT, INSERT, UPDATE ON organization_international_settings
    TO "${applicationRole}";

COMMENT ON TABLE organization_international_settings IS
    'Tenant-isolated immutable effective international-settings versions with one future scheduled replacement.';
COMMENT ON COLUMN organization_international_settings.source IS
    'Distinguishes the persisted organization-profile default baseline from explicitly configured versions.';
