CREATE TABLE organization_identifier_types (
    identifier_type varchar(80) PRIMARY KEY,
    display_name varchar(120) NOT NULL,
    jurisdiction_country_code char(2),
    primary_required boolean NOT NULL DEFAULT false,
    status varchar(24) NOT NULL,
    registry_version varchar(80) NOT NULL
        REFERENCES authorization_registry_releases(registry_version),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CHECK (identifier_type ~ '^[a-z][a-z0-9]*([._:-][a-z0-9]+)*$'),
    CHECK (display_name = btrim(display_name)
           AND display_name = normalize(display_name, NFC)
           AND char_length(display_name) BETWEEN 2 AND 120
           AND display_name !~ '[[:cntrl:]<>]'),
    CHECK (jurisdiction_country_code IS NULL
           OR jurisdiction_country_code ~ '^[A-Z]{2}$'),
    CHECK (status IN ('active', 'retired')),
    CHECK (isfinite(created_at))
);

INSERT INTO organization_identifier_types
    (identifier_type, display_name, jurisdiction_country_code,
     primary_required, status, registry_version)
VALUES
    ('registration', 'Registration identifier', NULL,
     true, 'active', 'm1-candidate-1');

CREATE FUNCTION careos_reject_organization_identifier_type_change()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'organization identifier types are migration-owned and immutable';
END;
$$;

CREATE TRIGGER organization_identifier_types_no_change
    BEFORE UPDATE OR DELETE ON organization_identifier_types
    FOR EACH ROW EXECUTE FUNCTION careos_reject_organization_identifier_type_change();

CREATE TABLE organization_identifiers (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    identifier_type varchar(80) NOT NULL
        REFERENCES organization_identifier_types(identifier_type),
    assigning_authority varchar(160) NOT NULL,
    value_normalized varchar(128) NOT NULL,
    jurisdiction_country_code char(2),
    verification_status varchar(24) NOT NULL DEFAULT 'unverified',
    verification_evidence_reference varchar(160),
    is_primary boolean NOT NULL DEFAULT false,
    issue_date date,
    expiry_date date,
    effective_from timestamptz NOT NULL,
    effective_to timestamptz,
    supersedes_id uuid,
    status varchar(24) NOT NULL DEFAULT 'draft',
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL REFERENCES users(id),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL REFERENCES users(id),
    verified_at timestamptz,
    verified_by uuid REFERENCES users(id),
    UNIQUE (organization_id, id),
    CONSTRAINT organization_identifiers_supersedes_fk
        FOREIGN KEY (organization_id, supersedes_id)
        REFERENCES organization_identifiers(organization_id, id),
    CHECK (assigning_authority = btrim(assigning_authority)
           AND assigning_authority = normalize(assigning_authority, NFC)
           AND char_length(assigning_authority) BETWEEN 2 AND 160
           AND assigning_authority !~ '[[:cntrl:]]'),
    CHECK (value_normalized = btrim(value_normalized)
           AND value_normalized = normalize(value_normalized, NFC)
           AND char_length(value_normalized) BETWEEN 1 AND 128
           AND value_normalized !~ '[[:cntrl:]]'),
    CHECK (jurisdiction_country_code IS NULL
           OR jurisdiction_country_code ~ '^[A-Z]{2}$'),
    CHECK (verification_status IN ('unverified', 'verified')),
    CHECK (verification_evidence_reference IS NULL
           OR (verification_evidence_reference = btrim(verification_evidence_reference)
               AND verification_evidence_reference =
                   normalize(verification_evidence_reference, NFC)
               AND char_length(verification_evidence_reference) BETWEEN 1 AND 160
               AND verification_evidence_reference !~ '[[:cntrl:]]')),
    CHECK (status IN ('draft', 'verified', 'active', 'expired', 'revoked', 'superseded')),
    CHECK (lock_version >= 0),
    CHECK (isfinite(effective_from)),
    CHECK (effective_to IS NULL
           OR (isfinite(effective_to) AND effective_to > effective_from)),
    CHECK (expiry_date IS NULL OR issue_date IS NULL OR expiry_date >= issue_date),
    CHECK (supersedes_id IS NULL OR supersedes_id <> id),
    CHECK (isfinite(created_at) AND isfinite(updated_at) AND updated_at >= created_at),
    CHECK (
        (status = 'draft'
         AND verification_status = 'unverified'
         AND verification_evidence_reference IS NULL
         AND verified_at IS NULL
         AND verified_by IS NULL)
        OR
        (status <> 'draft'
         AND verification_status = 'verified'
         AND verification_evidence_reference IS NOT NULL
         AND verified_at IS NOT NULL
         AND verified_by IS NOT NULL)
    )
);

CREATE UNIQUE INDEX organization_identifiers_non_revoked_identity_uq
    ON organization_identifiers
        (organization_id, identifier_type, assigning_authority, value_normalized)
    WHERE status <> 'revoked';

CREATE INDEX organization_identifiers_projection_idx
    ON organization_identifiers
        (organization_id, identifier_type, is_primary DESC, created_at DESC, id DESC);

CREATE INDEX organization_identifiers_current_primary_idx
    ON organization_identifiers
        (organization_id, identifier_type, effective_from, effective_to)
    WHERE is_primary AND status IN ('verified', 'active');

CREATE UNIQUE INDEX organization_identifiers_one_replacement_uq
    ON organization_identifiers (organization_id, supersedes_id)
    WHERE supersedes_id IS NOT NULL;

ALTER TABLE organization_identifiers ENABLE ROW LEVEL SECURITY;
ALTER TABLE organization_identifiers FORCE ROW LEVEL SECURITY;
CREATE POLICY organization_identifiers_tenant_policy ON organization_identifiers
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
    ('organization.identifier.read', 'organization.identifier.read',
     'Read organization identifiers',
     'Read the governed organization identifier projection.',
     false, 'hidden', false, false, NULL, NULL, false,
     'active', 'm1-candidate-1', false),
    ('organization.identifier.manage', 'organization.identifier.manage',
     'Manage organization identifiers',
     'Create, update, revoke, or supersede a governed organization identifier.',
     true, 'explicit', true, false, NULL, NULL, false,
     'active', 'm1-candidate-1', false),
    ('organization.identifier.verify', 'organization.identifier.verify',
     'Verify organization identifier',
     'Record assured verification evidence for an exact draft identifier revision.',
     true, 'explicit', true, true, 600, 5, false,
     'active', 'm1-candidate-1', true);

INSERT INTO audit_event_definitions
    (event_name, schema_version, display_name, description, subject_type,
     reason_required, required_payload_keys, allowed_payload_keys,
     payload_schema, status, registry_version)
VALUES
    ('organization.identifier.created', 1, 'Organization identifier created',
     'A governed organization identifier draft was created.',
     'organization_identifier', true,
     ARRAY['identifierId', 'lockVersion', 'state'],
     ARRAY['identifierId', 'lockVersion', 'state'],
     '{"type":"object"}'::jsonb, 'active', 'm1-candidate-1'),
    ('organization.identifier.updated', 1, 'Organization identifier updated',
     'A governed organization identifier draft was updated.',
     'organization_identifier', true,
     ARRAY['identifierId', 'lockVersion', 'state'],
     ARRAY['identifierId', 'lockVersion', 'state'],
     '{"type":"object"}'::jsonb, 'active', 'm1-candidate-1'),
    ('organization.identifier.verified', 1, 'Organization identifier verified',
     'Verification evidence was recorded for an organization identifier.',
     'organization_identifier', true,
     ARRAY['identifierId', 'lockVersion', 'state'],
     ARRAY['identifierId', 'lockVersion', 'state'],
     '{"type":"object"}'::jsonb, 'active', 'm1-candidate-1'),
    ('organization.identifier.revoked', 1, 'Organization identifier revoked',
     'A governed organization identifier was revoked without rewriting history.',
     'organization_identifier', true,
     ARRAY['fromState', 'identifierId', 'lockVersion', 'replacementId', 'toState'],
     ARRAY['fromState', 'identifierId', 'lockVersion', 'replacementId', 'toState'],
     '{"type":"object"}'::jsonb, 'active', 'm1-candidate-1'),
    ('organization.identifier.superseded', 1, 'Organization identifier superseded',
     'A governed organization identifier was superseded by a replacement.',
     'organization_identifier', true,
     ARRAY['fromState', 'identifierId', 'lockVersion', 'replacementId', 'toState'],
     ARRAY['fromState', 'identifierId', 'lockVersion', 'replacementId', 'toState'],
     '{"type":"object"}'::jsonb, 'active', 'm1-candidate-1');

INSERT INTO outbox_event_definitions
    (event_name, schema_version, description, aggregate_type,
     required_payload_keys, allowed_payload_keys, payload_schema, status, registry_version)
VALUES
    ('organization.identifier.created', 1,
     'A governed organization identifier draft was created.',
     'organization_identifier',
     ARRAY['identifierId', 'lockVersion', 'state'],
     ARRAY['identifierId', 'lockVersion', 'state'],
     '{"type":"object"}'::jsonb, 'active', 'm1-candidate-1'),
    ('organization.identifier.updated', 1,
     'A governed organization identifier draft was updated.',
     'organization_identifier',
     ARRAY['identifierId', 'lockVersion', 'state'],
     ARRAY['identifierId', 'lockVersion', 'state'],
     '{"type":"object"}'::jsonb, 'active', 'm1-candidate-1'),
    ('organization.identifier.verified', 1,
     'Verification evidence was recorded for an organization identifier.',
     'organization_identifier',
     ARRAY['identifierId', 'lockVersion', 'state'],
     ARRAY['identifierId', 'lockVersion', 'state'],
     '{"type":"object"}'::jsonb, 'active', 'm1-candidate-1'),
    ('organization.identifier.revoked', 1,
     'A governed organization identifier was revoked without rewriting history.',
     'organization_identifier',
     ARRAY['fromState', 'identifierId', 'lockVersion', 'replacementId', 'toState'],
     ARRAY['fromState', 'identifierId', 'lockVersion', 'replacementId', 'toState'],
     '{"type":"object"}'::jsonb, 'active', 'm1-candidate-1'),
    ('organization.identifier.superseded', 1,
     'A governed organization identifier was superseded by a replacement.',
     'organization_identifier',
     ARRAY['fromState', 'identifierId', 'lockVersion', 'replacementId', 'toState'],
     ARRAY['fromState', 'identifierId', 'lockVersion', 'replacementId', 'toState'],
     '{"type":"object"}'::jsonb, 'active', 'm1-candidate-1');

INSERT INTO authorization_operation_events
    (operation_key, event_kind, event_name, schema_version, status, registry_version)
VALUES
    ('organization.identifier.manage', 'audit',
     'organization.identifier.created', 1, 'active', 'm1-candidate-1'),
    ('organization.identifier.manage', 'outbox',
     'organization.identifier.created', 1, 'active', 'm1-candidate-1'),
    ('organization.identifier.manage', 'audit',
     'organization.identifier.updated', 1, 'active', 'm1-candidate-1'),
    ('organization.identifier.manage', 'outbox',
     'organization.identifier.updated', 1, 'active', 'm1-candidate-1'),
    ('organization.identifier.manage', 'audit',
     'organization.identifier.revoked', 1, 'active', 'm1-candidate-1'),
    ('organization.identifier.manage', 'outbox',
     'organization.identifier.revoked', 1, 'active', 'm1-candidate-1'),
    ('organization.identifier.manage', 'audit',
     'organization.identifier.superseded', 1, 'active', 'm1-candidate-1'),
    ('organization.identifier.manage', 'outbox',
     'organization.identifier.superseded', 1, 'active', 'm1-candidate-1'),
    ('organization.identifier.verify', 'audit',
     'organization.identifier.verified', 1, 'active', 'm1-candidate-1'),
    ('organization.identifier.verify', 'outbox',
     'organization.identifier.verified', 1, 'active', 'm1-candidate-1');

CREATE FUNCTION careos_validate_organization_identifier_write()
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
    type_jurisdiction char(2);
    type_primary_required boolean;
    predecessor_status varchar(24);
    predecessor_type varchar(80);
    predecessor_primary boolean;
    predecessor_effective_from timestamptz;
    predecessor_effective_to timestamptz;
BEGIN
    IF current_user <> '${applicationRole}' THEN
        RETURN NEW;
    END IF;

    IF NEW.organization_id IS DISTINCT FROM configured_organization
        OR configured_actor IS NULL
        OR configured_reason IS NULL
        OR configured_reason IS DISTINCT FROM btrim(configured_reason)
        OR configured_reason IS DISTINCT FROM normalize(configured_reason, NFC)
        OR char_length(configured_reason) NOT BETWEEN 10 AND 500
        OR configured_reason ~ '[[:cntrl:]]' THEN
        RAISE EXCEPTION 'organization identifier writes require exact tenant, actor, and reason context'
            USING ERRCODE = '42501';
    END IF;

    PERFORM 1 FROM organizations WHERE id = NEW.organization_id FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'organization identifier tenant is unavailable'
            USING ERRCODE = '42501';
    END IF;

    SELECT types.jurisdiction_country_code, types.primary_required
    INTO type_jurisdiction, type_primary_required
    FROM organization_identifier_types types
    WHERE types.identifier_type = NEW.identifier_type
      AND types.status = 'active'
      AND types.registry_version = 'm1-candidate-1';
    IF NOT FOUND THEN
        RAISE EXCEPTION 'organization identifier type is unknown or inactive'
            USING ERRCODE = '23514';
    END IF;
    IF type_jurisdiction IS NOT NULL
        AND NEW.jurisdiction_country_code IS DISTINCT FROM type_jurisdiction THEN
        RAISE EXCEPTION 'organization identifier jurisdiction does not match its type registry'
            USING ERRCODE = '23514';
    END IF;

    IF TG_OP = 'INSERT' THEN
        IF configured_operation IS DISTINCT FROM 'organization.identifier.manage'
            OR NEW.status <> 'draft'
            OR NEW.verification_status <> 'unverified'
            OR NEW.verification_evidence_reference IS NOT NULL
            OR NEW.verified_at IS NOT NULL
            OR NEW.verified_by IS NOT NULL
            OR NEW.created_by IS DISTINCT FROM configured_actor
            OR NEW.updated_by IS DISTINCT FROM configured_actor
            OR NEW.lock_version <> 0
            OR NEW.supersedes_id IS NOT NULL THEN
            RAISE EXCEPTION 'invalid organization identifier draft creation'
                USING ERRCODE = '42501';
        END IF;
        NEW.created_at := clock_timestamp();
        NEW.updated_at := NEW.created_at;
    ELSE
        IF ROW(NEW.id, NEW.organization_id, NEW.created_at, NEW.created_by)
           IS DISTINCT FROM
           ROW(OLD.id, OLD.organization_id, OLD.created_at, OLD.created_by)
            OR NEW.lock_version <> OLD.lock_version + 1
            OR NEW.updated_by IS DISTINCT FROM configured_actor THEN
            RAISE EXCEPTION 'organization identifier revision evidence is invalid'
                USING ERRCODE = '23514';
        END IF;

        IF configured_operation = 'organization.identifier.manage'
            AND OLD.status = 'draft' AND NEW.status = 'draft' THEN
            IF ROW(NEW.verification_status, NEW.verification_evidence_reference,
                   NEW.verified_at, NEW.verified_by, NEW.supersedes_id)
               IS DISTINCT FROM
                ROW(OLD.verification_status, OLD.verification_evidence_reference,
                   OLD.verified_at, OLD.verified_by, OLD.supersedes_id)
                OR ROW(NEW.identifier_type, NEW.assigning_authority,
                       NEW.value_normalized, NEW.jurisdiction_country_code,
                       NEW.is_primary, NEW.issue_date, NEW.expiry_date,
                       NEW.effective_from, NEW.effective_to)
                   IS NOT DISTINCT FROM
                   ROW(OLD.identifier_type, OLD.assigning_authority,
                       OLD.value_normalized, OLD.jurisdiction_country_code,
                       OLD.is_primary, OLD.issue_date, OLD.expiry_date,
                       OLD.effective_from, OLD.effective_to) THEN
                RAISE EXCEPTION 'identifier draft update must change only editable content'
                    USING ERRCODE = '23514';
            END IF;
        ELSIF configured_operation = 'organization.identifier.verify'
            AND OLD.status = 'draft' AND NEW.status = 'verified' THEN
            IF ROW(NEW.identifier_type, NEW.assigning_authority,
                   NEW.value_normalized, NEW.jurisdiction_country_code,
                   NEW.is_primary, NEW.issue_date, NEW.expiry_date,
                   NEW.effective_from, NEW.effective_to, NEW.supersedes_id)
               IS DISTINCT FROM
                ROW(OLD.identifier_type, OLD.assigning_authority,
                   OLD.value_normalized, OLD.jurisdiction_country_code,
                   OLD.is_primary, OLD.issue_date, OLD.expiry_date,
                   OLD.effective_from, OLD.effective_to, OLD.supersedes_id)
                OR NEW.verification_status <> 'verified'
                OR NEW.verification_evidence_reference IS NULL
                OR NEW.verified_at IS NOT NULL
                OR NEW.verified_by IS DISTINCT FROM configured_actor THEN
                RAISE EXCEPTION 'invalid organization identifier verification transition'
                    USING ERRCODE = '23514';
            END IF;
            NEW.verified_at := clock_timestamp();
        ELSIF configured_operation = 'organization.identifier.manage'
            AND OLD.status IN ('verified', 'active') AND NEW.status = 'revoked' THEN
            IF ROW(NEW.identifier_type, NEW.assigning_authority,
                   NEW.value_normalized, NEW.jurisdiction_country_code,
                   NEW.verification_status, NEW.verification_evidence_reference,
                   NEW.is_primary, NEW.issue_date, NEW.expiry_date,
                   NEW.effective_from, NEW.effective_to,
                   NEW.verified_at, NEW.verified_by, NEW.supersedes_id)
               IS DISTINCT FROM
                ROW(OLD.identifier_type, OLD.assigning_authority,
                   OLD.value_normalized, OLD.jurisdiction_country_code,
                   OLD.verification_status, OLD.verification_evidence_reference,
                   OLD.is_primary, OLD.issue_date, OLD.expiry_date,
                   OLD.effective_from, OLD.effective_to,
                   OLD.verified_at, OLD.verified_by, OLD.supersedes_id) THEN
                RAISE EXCEPTION 'revocation cannot rewrite organization identifier history'
                    USING ERRCODE = '23514';
            END IF;
            IF type_primary_required AND OLD.is_primary
                AND OLD.effective_from <= clock_timestamp()
                AND (OLD.effective_to IS NULL OR OLD.effective_to > clock_timestamp())
                AND NOT EXISTS (
                    SELECT 1
                    FROM organization_identifiers replacement
                    WHERE replacement.organization_id = OLD.organization_id
                      AND replacement.id <> OLD.id
                      AND replacement.identifier_type = OLD.identifier_type
                      AND replacement.is_primary
                      AND replacement.status IN ('verified', 'active')
                      AND replacement.effective_from <= clock_timestamp()
                      AND (replacement.effective_to IS NULL
                           OR replacement.effective_to > clock_timestamp())
                      AND (replacement.expiry_date IS NULL
                           OR replacement.expiry_date >=
                              (clock_timestamp() AT TIME ZONE
                               (SELECT timezone FROM organizations
                                WHERE id = OLD.organization_id))::date)
                ) THEN
                RAISE EXCEPTION 'a required current primary identifier cannot be revoked without replacement'
                    USING ERRCODE = '23514';
            END IF;
        ELSIF configured_operation = 'organization.identifier.manage'
            AND OLD.status IN ('verified', 'active') AND NEW.status = 'superseded' THEN
            IF ROW(NEW.identifier_type, NEW.assigning_authority,
                   NEW.value_normalized, NEW.jurisdiction_country_code,
                   NEW.verification_status, NEW.verification_evidence_reference,
                   NEW.is_primary, NEW.issue_date, NEW.expiry_date,
                   NEW.effective_from, NEW.effective_to,
                   NEW.verified_at, NEW.verified_by, NEW.supersedes_id)
               IS DISTINCT FROM
               ROW(OLD.identifier_type, OLD.assigning_authority,
                   OLD.value_normalized, OLD.jurisdiction_country_code,
                   OLD.verification_status, OLD.verification_evidence_reference,
                   OLD.is_primary, OLD.issue_date, OLD.expiry_date,
                   OLD.effective_from, OLD.effective_to,
                   OLD.verified_at, OLD.verified_by, OLD.supersedes_id) THEN
                RAISE EXCEPTION 'supersession cannot rewrite organization identifier history'
                    USING ERRCODE = '23514';
            END IF;
        ELSIF configured_operation = 'organization.identifier.manage'
            AND OLD.status IN ('verified', 'active')
            AND NEW.status = OLD.status
            AND OLD.supersedes_id IS NULL
            AND NEW.supersedes_id IS NOT NULL THEN
            SELECT predecessor.status, predecessor.identifier_type,
                   predecessor.is_primary, predecessor.effective_from,
                   predecessor.effective_to
            INTO predecessor_status, predecessor_type, predecessor_primary,
                 predecessor_effective_from, predecessor_effective_to
            FROM organization_identifiers predecessor
            WHERE predecessor.organization_id = NEW.organization_id
              AND predecessor.id = NEW.supersedes_id
            FOR UPDATE;
            IF NOT FOUND
                OR predecessor_status <> 'superseded'
                OR predecessor_type <> NEW.identifier_type
                OR NEW.is_primary IS DISTINCT FROM
                    (OLD.is_primary OR predecessor_primary)
                OR ROW(NEW.identifier_type, NEW.assigning_authority,
                       NEW.value_normalized, NEW.jurisdiction_country_code,
                       NEW.verification_status, NEW.verification_evidence_reference,
                       NEW.issue_date, NEW.expiry_date,
                       NEW.effective_from, NEW.effective_to,
                       NEW.verified_at, NEW.verified_by)
                   IS DISTINCT FROM
                   ROW(OLD.identifier_type, OLD.assigning_authority,
                       OLD.value_normalized, OLD.jurisdiction_country_code,
                       OLD.verification_status, OLD.verification_evidence_reference,
                       OLD.issue_date, OLD.expiry_date,
                       OLD.effective_from, OLD.effective_to,
                       OLD.verified_at, OLD.verified_by) THEN
                RAISE EXCEPTION 'invalid organization identifier replacement link'
                    USING ERRCODE = '23514';
            END IF;
            IF predecessor_primary
                AND predecessor_effective_from <= clock_timestamp()
                AND (predecessor_effective_to IS NULL
                     OR predecessor_effective_to > clock_timestamp())
                AND (NEW.effective_from > clock_timestamp()
                     OR (NEW.effective_to IS NOT NULL
                         AND NEW.effective_to <= clock_timestamp())
                     OR (NEW.expiry_date IS NOT NULL
                         AND NEW.expiry_date <
                             (clock_timestamp() AT TIME ZONE
                              (SELECT timezone FROM organizations
                               WHERE id = NEW.organization_id))::date)) THEN
                RAISE EXCEPTION 'a current primary identifier requires a current replacement'
                    USING ERRCODE = '23514';
            END IF;
        ELSE
            RAISE EXCEPTION 'organization identifier transition is not authorized'
                USING ERRCODE = '42501';
        END IF;
        NEW.updated_at := greatest(clock_timestamp(), OLD.updated_at + interval '1 microsecond');
    END IF;

    IF NEW.is_primary AND NEW.status IN ('verified', 'active')
        AND EXISTS (
            SELECT 1
            FROM organization_identifiers other
            WHERE other.organization_id = NEW.organization_id
              AND other.id <> NEW.id
              AND other.identifier_type = NEW.identifier_type
              AND other.is_primary
              AND other.status IN ('verified', 'active')
              AND tstzrange(other.effective_from, other.effective_to, '[)')
                  && tstzrange(NEW.effective_from, NEW.effective_to, '[)')
        ) THEN
        RAISE EXCEPTION 'organization primary identifier effective ranges overlap'
            USING ERRCODE = '23P01';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER organization_identifiers_validate_write
    BEFORE INSERT OR UPDATE ON organization_identifiers
    FOR EACH ROW EXECUTE FUNCTION careos_validate_organization_identifier_write();

CREATE FUNCTION careos_validate_organization_identifier_supersession_commit()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF current_user <> '${applicationRole}' THEN
        RETURN NULL;
    END IF;
    IF OLD.status IN ('verified', 'active') AND NEW.status = 'superseded'
        AND NOT EXISTS (
            SELECT 1
            FROM organization_identifiers replacement
            WHERE replacement.organization_id = NEW.organization_id
              AND replacement.supersedes_id = NEW.id
              AND replacement.identifier_type = NEW.identifier_type
              AND replacement.status IN ('verified', 'active')
              AND (NOT NEW.is_primary OR replacement.is_primary)
              AND (NOT NEW.is_primary
                   OR NEW.effective_from > clock_timestamp()
                   OR (NEW.effective_to IS NOT NULL
                       AND NEW.effective_to <= clock_timestamp())
                   OR (replacement.effective_from <= clock_timestamp()
                       AND (replacement.effective_to IS NULL
                            OR replacement.effective_to > clock_timestamp())))
        ) THEN
        RAISE EXCEPTION 'a superseded organization identifier requires its verified replacement'
            USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER organization_identifiers_supersession_commit
    AFTER UPDATE ON organization_identifiers
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION
        careos_validate_organization_identifier_supersession_commit();

CREATE FUNCTION careos_reject_organization_identifier_delete()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'organization identifier history cannot be deleted';
END;
$$;

CREATE TRIGGER organization_identifiers_no_delete
    BEFORE DELETE ON organization_identifiers
    FOR EACH ROW EXECUTE FUNCTION careos_reject_organization_identifier_delete();

REVOKE ALL ON organization_identifier_types FROM PUBLIC;
REVOKE ALL ON organization_identifiers FROM PUBLIC;
REVOKE ALL ON FUNCTION careos_reject_organization_identifier_type_change() FROM PUBLIC;
REVOKE ALL ON FUNCTION careos_validate_organization_identifier_write() FROM PUBLIC;
REVOKE ALL ON FUNCTION careos_validate_organization_identifier_supersession_commit() FROM PUBLIC;
REVOKE ALL ON FUNCTION careos_reject_organization_identifier_delete() FROM PUBLIC;

GRANT SELECT ON organization_identifier_types TO "${applicationRole}";
GRANT SELECT, INSERT, UPDATE ON organization_identifiers TO "${applicationRole}";

COMMENT ON TABLE organization_identifier_types IS
    'Migration-owned approved identifier-type registry. Jurisdiction-specific extensions require a versioned registry migration.';
COMMENT ON TABLE organization_identifiers IS
    'Tenant-isolated governed organization registration identifiers with immutable verified and terminal history.';
COMMENT ON COLUMN organization_identifiers.value_normalized IS
    'NFC-normalized exact identifier value; no jurisdiction-specific rewriting is invented by the base registry.';
COMMENT ON COLUMN organization_identifiers.verification_evidence_reference IS
    'Bounded confidential evidence reference; raw evidence content and provider locations are not stored here.';
