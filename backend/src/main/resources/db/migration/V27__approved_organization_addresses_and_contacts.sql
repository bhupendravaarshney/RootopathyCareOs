CREATE TABLE organization_contact_purposes (
    purpose_key varchar(80) PRIMARY KEY,
    display_name varchar(120) NOT NULL,
    public_projection_allowed boolean NOT NULL DEFAULT false,
    status varchar(24) NOT NULL,
    registry_version varchar(80) NOT NULL
        REFERENCES authorization_registry_releases(registry_version),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CHECK (purpose_key ~ '^[a-z][a-z0-9]*([._:-][a-z0-9]+)*$'),
    CHECK (display_name = btrim(display_name)
           AND display_name = normalize(display_name, NFC)
           AND char_length(display_name) BETWEEN 2 AND 120
           AND display_name !~ '[[:cntrl:]<>]'),
    CHECK (status IN ('active', 'retired')),
    CHECK (isfinite(created_at))
);

INSERT INTO organization_contact_purposes
    (purpose_key, display_name, public_projection_allowed, status, registry_version)
VALUES
    ('operational', 'Operational contact', false, 'active', 'm1-candidate-1');

CREATE FUNCTION careos_reject_organization_contact_purpose_change()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'organization contact purposes are migration-owned and immutable';
END;
$$;

CREATE TRIGGER organization_contact_purposes_no_change
    BEFORE UPDATE OR DELETE ON organization_contact_purposes
    FOR EACH ROW EXECUTE FUNCTION careos_reject_organization_contact_purpose_change();

CREATE TABLE organization_addresses (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    address_type varchar(24) NOT NULL,
    address_line_1 varchar(120) NOT NULL,
    address_line_2 varchar(120),
    address_line_3 varchar(120),
    address_line_4 varchar(120),
    locality varchar(100) NOT NULL,
    region varchar(100) NOT NULL,
    postcode varchar(24) NOT NULL,
    country_code char(2) NOT NULL,
    validation_status varchar(24) NOT NULL,
    validation_source varchar(160),
    is_primary boolean NOT NULL DEFAULT false,
    effective_from timestamptz NOT NULL,
    effective_to timestamptz,
    supersedes_id uuid,
    status varchar(24) NOT NULL,
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL REFERENCES users(id),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL REFERENCES users(id),
    UNIQUE (organization_id, id),
    CONSTRAINT organization_addresses_supersedes_fk
        FOREIGN KEY (organization_id, supersedes_id)
        REFERENCES organization_addresses(organization_id, id),
    CHECK (address_type IN ('registered', 'postal', 'service', 'billing')),
    CHECK (address_line_1 = btrim(address_line_1)
           AND address_line_1 = normalize(address_line_1, NFC)
           AND char_length(address_line_1) BETWEEN 1 AND 120
           AND address_line_1 !~ '[[:cntrl:]<>]'),
    CHECK (address_line_2 IS NULL OR
           (address_line_2 = btrim(address_line_2)
            AND address_line_2 = normalize(address_line_2, NFC)
            AND char_length(address_line_2) BETWEEN 1 AND 120
            AND address_line_2 !~ '[[:cntrl:]<>]')),
    CHECK (address_line_3 IS NULL OR
           (address_line_3 = btrim(address_line_3)
            AND address_line_3 = normalize(address_line_3, NFC)
            AND char_length(address_line_3) BETWEEN 1 AND 120
            AND address_line_3 !~ '[[:cntrl:]<>]')),
    CHECK (address_line_4 IS NULL OR
           (address_line_4 = btrim(address_line_4)
            AND address_line_4 = normalize(address_line_4, NFC)
            AND char_length(address_line_4) BETWEEN 1 AND 120
            AND address_line_4 !~ '[[:cntrl:]<>]')),
    CHECK (address_line_3 IS NULL OR address_line_2 IS NOT NULL),
    CHECK (address_line_4 IS NULL OR address_line_3 IS NOT NULL),
    CHECK (locality = btrim(locality)
           AND locality = normalize(locality, NFC)
           AND char_length(locality) BETWEEN 1 AND 100
           AND locality !~ '[[:cntrl:]<>]'),
    CHECK (region = btrim(region)
           AND region = normalize(region, NFC)
           AND char_length(region) BETWEEN 1 AND 100
           AND region !~ '[[:cntrl:]<>]'),
    CHECK (postcode = btrim(postcode)
           AND postcode = normalize(postcode, NFC)
           AND char_length(postcode) BETWEEN 1 AND 24
           AND postcode !~ '[[:cntrl:]<>]'),
    CHECK (country_code ~ '^[A-Z]{2}$'),
    CHECK (validation_status IN ('unvalidated', 'validated')),
    CHECK ((validation_status = 'unvalidated' AND validation_source IS NULL)
           OR (validation_status = 'validated'
               AND validation_source IS NOT NULL
               AND validation_source = btrim(validation_source)
               AND validation_source = normalize(validation_source, NFC)
               AND char_length(validation_source) BETWEEN 2 AND 160
               AND validation_source !~ '[[:cntrl:]<>]')),
    CHECK (isfinite(effective_from)),
    CHECK (effective_to IS NULL
           OR (isfinite(effective_to) AND effective_to > effective_from)),
    CHECK (supersedes_id IS NULL OR supersedes_id <> id),
    CHECK (status IN ('scheduled', 'active', 'ended', 'superseded')),
    CHECK (lock_version >= 0),
    CHECK (isfinite(created_at) AND isfinite(updated_at) AND updated_at >= created_at)
);

CREATE TABLE organization_contacts (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    channel varchar(24) NOT NULL,
    purpose_key varchar(80) NOT NULL
        REFERENCES organization_contact_purposes(purpose_key),
    value_normalized varchar(2048) NOT NULL,
    verification_status varchar(24) NOT NULL DEFAULT 'unverified',
    is_primary boolean NOT NULL DEFAULT false,
    is_preferred boolean NOT NULL DEFAULT false,
    effective_from timestamptz NOT NULL,
    effective_to timestamptz,
    supersedes_id uuid,
    status varchar(24) NOT NULL,
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL REFERENCES users(id),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL REFERENCES users(id),
    verified_at timestamptz,
    verified_by uuid REFERENCES users(id),
    UNIQUE (organization_id, id),
    CONSTRAINT organization_contacts_supersedes_fk
        FOREIGN KEY (organization_id, supersedes_id)
        REFERENCES organization_contacts(organization_id, id),
    CHECK (channel IN ('email', 'phone', 'web')),
    CHECK (value_normalized = btrim(value_normalized)
           AND value_normalized = normalize(value_normalized, NFC)
           AND value_normalized !~ '[[:cntrl:]]'),
    CHECK ((channel = 'email'
            AND char_length(value_normalized) BETWEEN 3 AND 254
            AND value_normalized ~ '^[^[:space:]@]+@[^[:space:]@]+[.][^[:space:]@]+$')
           OR (channel = 'phone'
               AND char_length(value_normalized) BETWEEN 3 AND 32
               AND value_normalized ~ '^[+][1-9][0-9]{1,14}$')
           OR (channel = 'web'
               AND char_length(value_normalized) BETWEEN 9 AND 2048
               AND value_normalized ~ '^https://[^[:space:]]+$')),
    CHECK (verification_status IN ('unverified', 'verified')),
    CHECK (is_preferred = false OR is_primary),
    CHECK (isfinite(effective_from)),
    CHECK (effective_to IS NULL
           OR (isfinite(effective_to) AND effective_to > effective_from)),
    CHECK (supersedes_id IS NULL OR supersedes_id <> id),
    CHECK (status IN ('scheduled', 'active', 'ended', 'superseded')),
    CHECK (lock_version >= 0),
    CHECK (isfinite(created_at) AND isfinite(updated_at) AND updated_at >= created_at),
    CHECK ((verification_status = 'unverified'
            AND verified_at IS NULL AND verified_by IS NULL)
           OR (verification_status = 'verified'
               AND verified_at IS NOT NULL AND verified_by IS NOT NULL))
);

CREATE UNIQUE INDEX organization_addresses_one_replacement_uq
    ON organization_addresses (organization_id, supersedes_id)
    WHERE supersedes_id IS NOT NULL;

CREATE INDEX organization_addresses_projection_idx
    ON organization_addresses
        (organization_id, address_type, is_primary DESC, created_at DESC, id DESC);

CREATE UNIQUE INDEX organization_contacts_one_replacement_uq
    ON organization_contacts (organization_id, supersedes_id)
    WHERE supersedes_id IS NOT NULL;

CREATE UNIQUE INDEX organization_contacts_current_value_uq
    ON organization_contacts
        (organization_id, channel, purpose_key, value_normalized)
    WHERE status IN ('scheduled', 'active');

CREATE INDEX organization_contacts_projection_idx
    ON organization_contacts
        (organization_id, purpose_key, channel, is_primary DESC,
         is_preferred DESC, created_at DESC, id DESC);

ALTER TABLE organization_addresses ENABLE ROW LEVEL SECURITY;
ALTER TABLE organization_addresses FORCE ROW LEVEL SECURITY;
CREATE POLICY organization_addresses_tenant_policy ON organization_addresses
    USING (organization_id =
        nullif(current_setting('app.current_organization_id', true), '')::uuid)
    WITH CHECK (organization_id =
        nullif(current_setting('app.current_organization_id', true), '')::uuid);

ALTER TABLE organization_contacts ENABLE ROW LEVEL SECURITY;
ALTER TABLE organization_contacts FORCE ROW LEVEL SECURITY;
CREATE POLICY organization_contacts_tenant_policy ON organization_contacts
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
    ('organization.contact.read', 'organization.contact.read',
     'Read organization addresses and contacts',
     'Read the minimum-necessary effective organization address and masked contact projection.',
     false, 'hidden', false, false, NULL, NULL, false,
     'active', 'm1-candidate-1', false),
    ('organization.contact.manage', 'organization.contact.manage',
     'Manage organization addresses and contacts',
     'Create, verify, end, or supersede governed effective organization address and contact records.',
     true, 'explicit', true, false, NULL, NULL, false,
     'active', 'm1-candidate-1', false);

INSERT INTO audit_event_definitions
    (event_name, schema_version, display_name, description, subject_type,
     reason_required, required_payload_keys, allowed_payload_keys,
     payload_schema, status, registry_version)
VALUES
    ('organization.address.changed', 1, 'Organization address changed',
     'A governed effective organization address record changed.',
     'organization_address', true,
     ARRAY['changeType', 'effectiveFrom', 'lockVersion', 'recordId'],
     ARRAY['changeType', 'effectiveFrom', 'lockVersion', 'recordId'],
     '{"type":"object"}'::jsonb, 'active', 'm1-candidate-1'),
    ('organization.contact.changed', 1, 'Organization contact changed',
     'A governed effective organization contact record changed without copying its confidential value.',
     'organization_contact', true,
     ARRAY['changeType', 'effectiveFrom', 'lockVersion', 'recordId'],
     ARRAY['changeType', 'effectiveFrom', 'lockVersion', 'recordId'],
     '{"type":"object"}'::jsonb, 'active', 'm1-candidate-1');

INSERT INTO outbox_event_definitions
    (event_name, schema_version, description, aggregate_type,
     required_payload_keys, allowed_payload_keys, payload_schema, status, registry_version)
VALUES
    ('organization.address.changed', 1,
     'A governed effective organization address record changed.',
     'organization_address',
     ARRAY['changeType', 'effectiveFrom', 'lockVersion', 'recordId'],
     ARRAY['changeType', 'effectiveFrom', 'lockVersion', 'recordId'],
     '{"type":"object"}'::jsonb, 'active', 'm1-candidate-1'),
    ('organization.contact.changed', 1,
     'A governed effective organization contact record changed without copying its confidential value.',
     'organization_contact',
     ARRAY['changeType', 'effectiveFrom', 'lockVersion', 'recordId'],
     ARRAY['changeType', 'effectiveFrom', 'lockVersion', 'recordId'],
     '{"type":"object"}'::jsonb, 'active', 'm1-candidate-1');

INSERT INTO authorization_operation_events
    (operation_key, event_kind, event_name, schema_version, status, registry_version)
VALUES
    ('organization.contact.manage', 'audit',
     'organization.address.changed', 1, 'active', 'm1-candidate-1'),
    ('organization.contact.manage', 'outbox',
     'organization.address.changed', 1, 'active', 'm1-candidate-1'),
    ('organization.contact.manage', 'audit',
     'organization.contact.changed', 1, 'active', 'm1-candidate-1'),
    ('organization.contact.manage', 'outbox',
     'organization.contact.changed', 1, 'active', 'm1-candidate-1');

CREATE FUNCTION careos_validate_organization_address_write()
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
    predecessor_status varchar(24);
    predecessor_type varchar(24);
    predecessor_primary boolean;
    predecessor_effective_from timestamptz;
    predecessor_effective_to timestamptz;
BEGIN
    IF current_user <> '${applicationRole}' THEN
        RETURN NEW;
    END IF;

    IF NEW.organization_id IS DISTINCT FROM configured_organization
        OR configured_actor IS NULL
        OR configured_operation IS DISTINCT FROM 'organization.contact.manage'
        OR configured_reason IS NULL
        OR configured_reason IS DISTINCT FROM btrim(configured_reason)
        OR configured_reason IS DISTINCT FROM normalize(configured_reason, NFC)
        OR char_length(configured_reason) NOT BETWEEN 10 AND 500
        OR configured_reason ~ '[[:cntrl:]]' THEN
        RAISE EXCEPTION 'organization address writes require exact tenant, actor, operation, and reason context'
            USING ERRCODE = '42501';
    END IF;

    PERFORM 1 FROM organizations WHERE id = NEW.organization_id FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'organization address tenant is unavailable'
            USING ERRCODE = '42501';
    END IF;

    IF TG_OP = 'INSERT' THEN
        IF NEW.created_by IS DISTINCT FROM configured_actor
            OR NEW.updated_by IS DISTINCT FROM configured_actor
            OR NEW.lock_version <> 0 THEN
            RAISE EXCEPTION 'invalid organization address creation evidence'
                USING ERRCODE = '23514';
        END IF;
        IF NEW.effective_to IS NOT NULL AND NEW.effective_to <= clock_timestamp() THEN
            RAISE EXCEPTION 'an organization address cannot be created already ended'
                USING ERRCODE = '23514';
        END IF;
        NEW.status := CASE
            WHEN NEW.effective_from > clock_timestamp() THEN 'scheduled'
            ELSE 'active'
        END;
        IF NEW.supersedes_id IS NOT NULL THEN
            SELECT predecessor.status, predecessor.address_type, predecessor.is_primary,
                   predecessor.effective_from, predecessor.effective_to
            INTO predecessor_status, predecessor_type, predecessor_primary,
                 predecessor_effective_from, predecessor_effective_to
            FROM organization_addresses predecessor
            WHERE predecessor.organization_id = NEW.organization_id
              AND predecessor.id = NEW.supersedes_id
            FOR UPDATE;
            IF NOT FOUND
                OR predecessor_status <> 'superseded'
                OR predecessor_type <> NEW.address_type
                OR predecessor_primary IS DISTINCT FROM NEW.is_primary THEN
                RAISE EXCEPTION 'invalid organization address replacement link'
                    USING ERRCODE = '23514';
            END IF;
            IF predecessor_effective_from <= clock_timestamp()
                AND (predecessor_effective_to IS NULL
                     OR predecessor_effective_to > clock_timestamp())
                AND NEW.status <> 'active' THEN
                RAISE EXCEPTION 'a current organization address requires a current replacement'
                    USING ERRCODE = '23514';
            END IF;
        END IF;
        NEW.created_at := clock_timestamp();
        NEW.updated_at := NEW.created_at;
    ELSE
        IF ROW(NEW.id, NEW.organization_id, NEW.address_type,
               NEW.address_line_1, NEW.address_line_2,
               NEW.address_line_3, NEW.address_line_4,
               NEW.locality, NEW.region, NEW.postcode, NEW.country_code,
               NEW.validation_status, NEW.validation_source, NEW.is_primary,
               NEW.effective_from, NEW.created_at, NEW.created_by, NEW.supersedes_id)
           IS DISTINCT FROM
           ROW(OLD.id, OLD.organization_id, OLD.address_type,
               OLD.address_line_1, OLD.address_line_2,
               OLD.address_line_3, OLD.address_line_4,
               OLD.locality, OLD.region, OLD.postcode, OLD.country_code,
               OLD.validation_status, OLD.validation_source, OLD.is_primary,
               OLD.effective_from, OLD.created_at, OLD.created_by, OLD.supersedes_id)
            OR NEW.lock_version <> OLD.lock_version + 1
            OR NEW.updated_by IS DISTINCT FROM configured_actor THEN
            RAISE EXCEPTION 'organization address history or revision evidence is invalid'
                USING ERRCODE = '23514';
        END IF;

        IF OLD.status IN ('scheduled', 'active') AND NEW.status = 'superseded' THEN
            IF NEW.effective_to IS DISTINCT FROM OLD.effective_to THEN
                RAISE EXCEPTION 'address supersession cannot rewrite its effective range'
                    USING ERRCODE = '23514';
            END IF;
        ELSIF OLD.status = 'active' AND NEW.status = 'ended' THEN
            IF NEW.effective_to IS NULL
                OR NEW.effective_to > clock_timestamp()
                OR NEW.effective_to < OLD.effective_from
                OR (OLD.effective_to IS NOT NULL AND NEW.effective_to > OLD.effective_to) THEN
                RAISE EXCEPTION 'invalid organization address ending transition'
                    USING ERRCODE = '23514';
            END IF;
        ELSE
            RAISE EXCEPTION 'organization address transition is not authorized'
                USING ERRCODE = '42501';
        END IF;
        NEW.updated_at := greatest(clock_timestamp(), OLD.updated_at + interval '1 microsecond');
    END IF;

    IF NEW.is_primary AND NEW.status IN ('scheduled', 'active')
        AND EXISTS (
            SELECT 1
            FROM organization_addresses other
            WHERE other.organization_id = NEW.organization_id
              AND other.id <> NEW.id
              AND other.address_type = NEW.address_type
              AND other.is_primary
              AND other.status IN ('scheduled', 'active')
              AND tstzrange(other.effective_from, other.effective_to, '[)')
                  && tstzrange(NEW.effective_from, NEW.effective_to, '[)')
        ) THEN
        RAISE EXCEPTION 'organization primary address effective ranges overlap'
            USING ERRCODE = '23P01';
    END IF;

    RETURN NEW;
END;
$$;

CREATE FUNCTION careos_validate_organization_contact_write()
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
    predecessor_status varchar(24);
    predecessor_channel varchar(24);
    predecessor_purpose varchar(80);
    predecessor_primary boolean;
    predecessor_preferred boolean;
    predecessor_effective_from timestamptz;
    predecessor_effective_to timestamptz;
BEGIN
    IF current_user <> '${applicationRole}' THEN
        RETURN NEW;
    END IF;

    IF NEW.organization_id IS DISTINCT FROM configured_organization
        OR configured_actor IS NULL
        OR configured_operation IS DISTINCT FROM 'organization.contact.manage'
        OR configured_reason IS NULL
        OR configured_reason IS DISTINCT FROM btrim(configured_reason)
        OR configured_reason IS DISTINCT FROM normalize(configured_reason, NFC)
        OR char_length(configured_reason) NOT BETWEEN 10 AND 500
        OR configured_reason ~ '[[:cntrl:]]' THEN
        RAISE EXCEPTION 'organization contact writes require exact tenant, actor, operation, and reason context'
            USING ERRCODE = '42501';
    END IF;

    PERFORM 1 FROM organizations WHERE id = NEW.organization_id FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'organization contact tenant is unavailable'
            USING ERRCODE = '42501';
    END IF;
    PERFORM 1 FROM organization_contact_purposes purposes
    WHERE purposes.purpose_key = NEW.purpose_key
      AND purposes.status = 'active'
      AND purposes.registry_version = 'm1-candidate-1';
    IF NOT FOUND THEN
        RAISE EXCEPTION 'organization contact purpose is unknown or inactive'
            USING ERRCODE = '23514';
    END IF;

    IF TG_OP = 'INSERT' THEN
        IF NEW.created_by IS DISTINCT FROM configured_actor
            OR NEW.updated_by IS DISTINCT FROM configured_actor
            OR NEW.lock_version <> 0
            OR NEW.verification_status <> 'unverified'
            OR NEW.verified_at IS NOT NULL
            OR NEW.verified_by IS NOT NULL THEN
            RAISE EXCEPTION 'invalid organization contact creation evidence'
                USING ERRCODE = '23514';
        END IF;
        IF NEW.effective_to IS NOT NULL AND NEW.effective_to <= clock_timestamp() THEN
            RAISE EXCEPTION 'an organization contact cannot be created already ended'
                USING ERRCODE = '23514';
        END IF;
        NEW.status := CASE
            WHEN NEW.effective_from > clock_timestamp() THEN 'scheduled'
            ELSE 'active'
        END;
        IF NEW.supersedes_id IS NOT NULL THEN
            SELECT predecessor.status, predecessor.channel, predecessor.purpose_key,
                   predecessor.is_primary, predecessor.is_preferred,
                   predecessor.effective_from, predecessor.effective_to
            INTO predecessor_status, predecessor_channel, predecessor_purpose,
                 predecessor_primary, predecessor_preferred,
                 predecessor_effective_from, predecessor_effective_to
            FROM organization_contacts predecessor
            WHERE predecessor.organization_id = NEW.organization_id
              AND predecessor.id = NEW.supersedes_id
            FOR UPDATE;
            IF NOT FOUND
                OR predecessor_status <> 'superseded'
                OR predecessor_channel <> NEW.channel
                OR predecessor_purpose <> NEW.purpose_key
                OR predecessor_primary IS DISTINCT FROM NEW.is_primary
                OR predecessor_preferred IS DISTINCT FROM NEW.is_preferred THEN
                RAISE EXCEPTION 'invalid organization contact replacement link'
                    USING ERRCODE = '23514';
            END IF;
            IF predecessor_effective_from <= clock_timestamp()
                AND (predecessor_effective_to IS NULL
                     OR predecessor_effective_to > clock_timestamp())
                AND NEW.status <> 'active' THEN
                RAISE EXCEPTION 'a current organization contact requires a current replacement'
                    USING ERRCODE = '23514';
            END IF;
        END IF;
        NEW.created_at := clock_timestamp();
        NEW.updated_at := NEW.created_at;
    ELSE
        IF ROW(NEW.id, NEW.organization_id, NEW.channel, NEW.purpose_key,
               NEW.value_normalized, NEW.is_primary, NEW.is_preferred,
               NEW.effective_from, NEW.created_at, NEW.created_by, NEW.supersedes_id)
           IS DISTINCT FROM
           ROW(OLD.id, OLD.organization_id, OLD.channel, OLD.purpose_key,
               OLD.value_normalized, OLD.is_primary, OLD.is_preferred,
               OLD.effective_from, OLD.created_at, OLD.created_by, OLD.supersedes_id)
            OR NEW.lock_version <> OLD.lock_version + 1
            OR NEW.updated_by IS DISTINCT FROM configured_actor THEN
            RAISE EXCEPTION 'organization contact history or revision evidence is invalid'
                USING ERRCODE = '23514';
        END IF;

        IF OLD.status IN ('scheduled', 'active')
            AND NEW.status = OLD.status
            AND OLD.verification_status = 'unverified'
            AND NEW.verification_status = 'verified' THEN
            IF NEW.effective_to IS DISTINCT FROM OLD.effective_to
                OR NEW.verified_at IS NOT NULL
                OR NEW.verified_by IS DISTINCT FROM configured_actor THEN
                RAISE EXCEPTION 'invalid organization contact verification transition'
                    USING ERRCODE = '23514';
            END IF;
            NEW.verified_at := clock_timestamp();
        ELSIF OLD.status IN ('scheduled', 'active') AND NEW.status = 'superseded' THEN
            IF NEW.effective_to IS DISTINCT FROM OLD.effective_to
                OR NEW.verification_status IS DISTINCT FROM OLD.verification_status
                OR NEW.verified_at IS DISTINCT FROM OLD.verified_at
                OR NEW.verified_by IS DISTINCT FROM OLD.verified_by THEN
                RAISE EXCEPTION 'contact supersession cannot rewrite history'
                    USING ERRCODE = '23514';
            END IF;
        ELSIF OLD.status = 'active' AND NEW.status = 'ended' THEN
            IF NEW.effective_to IS NULL
                OR NEW.effective_to > clock_timestamp()
                OR NEW.effective_to < OLD.effective_from
                OR (OLD.effective_to IS NOT NULL AND NEW.effective_to > OLD.effective_to)
                OR NEW.verification_status IS DISTINCT FROM OLD.verification_status
                OR NEW.verified_at IS DISTINCT FROM OLD.verified_at
                OR NEW.verified_by IS DISTINCT FROM OLD.verified_by THEN
                RAISE EXCEPTION 'invalid organization contact ending transition'
                    USING ERRCODE = '23514';
            END IF;
        ELSE
            RAISE EXCEPTION 'organization contact transition is not authorized'
                USING ERRCODE = '42501';
        END IF;
        NEW.updated_at := greatest(clock_timestamp(), OLD.updated_at + interval '1 microsecond');
    END IF;

    IF NEW.is_primary AND NEW.status IN ('scheduled', 'active')
        AND EXISTS (
            SELECT 1
            FROM organization_contacts other
            WHERE other.organization_id = NEW.organization_id
              AND other.id <> NEW.id
              AND other.channel = NEW.channel
              AND other.purpose_key = NEW.purpose_key
              AND other.is_primary
              AND other.status IN ('scheduled', 'active')
              AND tstzrange(other.effective_from, other.effective_to, '[)')
                  && tstzrange(NEW.effective_from, NEW.effective_to, '[)')
        ) THEN
        RAISE EXCEPTION 'organization primary contact effective ranges overlap'
            USING ERRCODE = '23P01';
    END IF;

    IF NEW.is_preferred AND NEW.status IN ('scheduled', 'active')
        AND EXISTS (
            SELECT 1
            FROM organization_contacts other
            WHERE other.organization_id = NEW.organization_id
              AND other.id <> NEW.id
              AND other.purpose_key = NEW.purpose_key
              AND other.is_preferred
              AND other.status IN ('scheduled', 'active')
              AND tstzrange(other.effective_from, other.effective_to, '[)')
                  && tstzrange(NEW.effective_from, NEW.effective_to, '[)')
        ) THEN
        RAISE EXCEPTION 'organization preferred contact effective ranges overlap'
            USING ERRCODE = '23P01';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER organization_addresses_validate_write
    BEFORE INSERT OR UPDATE ON organization_addresses
    FOR EACH ROW EXECUTE FUNCTION careos_validate_organization_address_write();

CREATE TRIGGER organization_contacts_validate_write
    BEFORE INSERT OR UPDATE ON organization_contacts
    FOR EACH ROW EXECUTE FUNCTION careos_validate_organization_contact_write();

CREATE FUNCTION careos_validate_organization_address_supersession_commit()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF current_user <> '${applicationRole}' THEN
        RETURN NULL;
    END IF;
    IF OLD.status IN ('scheduled', 'active') AND NEW.status = 'superseded'
        AND NOT EXISTS (
            SELECT 1 FROM organization_addresses replacement
            WHERE replacement.organization_id = NEW.organization_id
              AND replacement.supersedes_id = NEW.id
              AND replacement.address_type = NEW.address_type
              AND replacement.status IN ('scheduled', 'active')
        ) THEN
        RAISE EXCEPTION 'a superseded organization address requires its replacement'
            USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$;

CREATE FUNCTION careos_validate_organization_contact_supersession_commit()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF current_user <> '${applicationRole}' THEN
        RETURN NULL;
    END IF;
    IF OLD.status IN ('scheduled', 'active') AND NEW.status = 'superseded'
        AND NOT EXISTS (
            SELECT 1 FROM organization_contacts replacement
            WHERE replacement.organization_id = NEW.organization_id
              AND replacement.supersedes_id = NEW.id
              AND replacement.channel = NEW.channel
              AND replacement.purpose_key = NEW.purpose_key
              AND replacement.status IN ('scheduled', 'active')
        ) THEN
        RAISE EXCEPTION 'a superseded organization contact requires its replacement'
            USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER organization_addresses_supersession_commit
    AFTER UPDATE ON organization_addresses
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION
        careos_validate_organization_address_supersession_commit();

CREATE CONSTRAINT TRIGGER organization_contacts_supersession_commit
    AFTER UPDATE ON organization_contacts
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION
        careos_validate_organization_contact_supersession_commit();

CREATE FUNCTION careos_reject_organization_contact_record_delete()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'organization address and contact history cannot be deleted';
END;
$$;

CREATE TRIGGER organization_addresses_no_delete
    BEFORE DELETE ON organization_addresses
    FOR EACH ROW EXECUTE FUNCTION careos_reject_organization_contact_record_delete();

CREATE TRIGGER organization_contacts_no_delete
    BEFORE DELETE ON organization_contacts
    FOR EACH ROW EXECUTE FUNCTION careos_reject_organization_contact_record_delete();

REVOKE ALL ON organization_contact_purposes FROM PUBLIC;
REVOKE ALL ON organization_addresses FROM PUBLIC;
REVOKE ALL ON organization_contacts FROM PUBLIC;
REVOKE ALL ON FUNCTION careos_reject_organization_contact_purpose_change() FROM PUBLIC;
REVOKE ALL ON FUNCTION careos_validate_organization_address_write() FROM PUBLIC;
REVOKE ALL ON FUNCTION careos_validate_organization_contact_write() FROM PUBLIC;
REVOKE ALL ON FUNCTION careos_validate_organization_address_supersession_commit() FROM PUBLIC;
REVOKE ALL ON FUNCTION careos_validate_organization_contact_supersession_commit() FROM PUBLIC;
REVOKE ALL ON FUNCTION careos_reject_organization_contact_record_delete() FROM PUBLIC;

GRANT SELECT ON organization_contact_purposes TO "${applicationRole}";
GRANT SELECT, INSERT, UPDATE ON organization_addresses TO "${applicationRole}";
GRANT SELECT, INSERT, UPDATE ON organization_contacts TO "${applicationRole}";

COMMENT ON TABLE organization_contact_purposes IS
    'Migration-owned approved organization contact-purpose registry.';
COMMENT ON TABLE organization_addresses IS
    'Tenant-isolated effective organization address history; no geocode is stored.';
COMMENT ON TABLE organization_contacts IS
    'Tenant-isolated effective confidential organization contact history; list projections are masked.';
COMMENT ON COLUMN organization_contacts.value_normalized IS
    'Confidential normalized contact value. Audit and outbox payloads must never copy it.';
