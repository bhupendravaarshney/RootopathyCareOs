ALTER TABLE organization_memberships
    ADD CONSTRAINT organization_memberships_organization_id_id_uq
    UNIQUE (organization_id, id);

CREATE TABLE organization_governance_responsibilities (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    responsibility_type varchar(24) NOT NULL,
    membership_id uuid,
    external_contact_id uuid,
    escalation_email varchar(254),
    escalation_phone varchar(32),
    is_primary boolean NOT NULL DEFAULT true,
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
    CONSTRAINT organization_governance_membership_fk
        FOREIGN KEY (organization_id, membership_id)
        REFERENCES organization_memberships(organization_id, id),
    CONSTRAINT organization_governance_contact_fk
        FOREIGN KEY (organization_id, external_contact_id)
        REFERENCES organization_contacts(organization_id, id),
    CONSTRAINT organization_governance_supersedes_fk
        FOREIGN KEY (organization_id, supersedes_id)
        REFERENCES organization_governance_responsibilities(organization_id, id),
    CHECK (responsibility_type IN ('clinical', 'privacy', 'security', 'billing')),
    CHECK ((membership_id IS NOT NULL) <> (external_contact_id IS NOT NULL)),
    CHECK (escalation_email IS NOT NULL OR escalation_phone IS NOT NULL),
    CHECK (escalation_email IS NULL OR
        (escalation_email = lower(btrim(escalation_email))
         AND escalation_email = normalize(escalation_email, NFC)
         AND char_length(escalation_email) BETWEEN 3 AND 254
         AND escalation_email ~ '^[^[:space:]@]+@[^[:space:]@]+[.][^[:space:]@]+$')),
    CHECK (escalation_phone IS NULL OR escalation_phone ~ '^[+][1-9][0-9]{1,14}$'),
    CHECK (isfinite(effective_from)),
    CHECK (effective_to IS NULL OR (isfinite(effective_to) AND effective_to > effective_from)),
    CHECK (supersedes_id IS NULL OR supersedes_id <> id),
    CHECK (status IN ('scheduled', 'active', 'ended', 'superseded')),
    CHECK (lock_version >= 0),
    CHECK (isfinite(created_at) AND isfinite(updated_at) AND updated_at >= created_at)
);

CREATE UNIQUE INDEX organization_governance_one_replacement_uq
    ON organization_governance_responsibilities (organization_id, supersedes_id)
    WHERE supersedes_id IS NOT NULL;
CREATE UNIQUE INDEX organization_governance_primary_range_uq
    ON organization_governance_responsibilities
        (organization_id, responsibility_type, effective_from)
    WHERE is_primary AND status IN ('scheduled', 'active');
CREATE INDEX organization_governance_projection_idx
    ON organization_governance_responsibilities
        (organization_id, responsibility_type, effective_from DESC, id DESC);

ALTER TABLE organization_governance_responsibilities ENABLE ROW LEVEL SECURITY;
ALTER TABLE organization_governance_responsibilities FORCE ROW LEVEL SECURITY;
CREATE POLICY organization_governance_responsibilities_tenant_policy
    ON organization_governance_responsibilities
    USING (organization_id = nullif(current_setting('app.current_organization_id', true), '')::uuid)
    WITH CHECK (organization_id = nullif(current_setting('app.current_organization_id', true), '')::uuid);

INSERT INTO authorization_operations
    (operation_key, permission_key, display_name, description, mutation, denial_mode,
     reason_required, recent_authentication_required,
     recent_authentication_max_age_seconds, maximum_future_skew_seconds,
     maker_checker_required, status, registry_version, mfa_required)
VALUES
    ('organization.governance.read', 'organization.governance.read',
     'Read governance responsibilities',
     'Read minimum-necessary effective governance responsibility history.',
     false, 'hidden', false, false, NULL, NULL, false, 'active', 'm1-candidate-1', false),
    ('organization.governance.manage', 'organization.governance.manage',
     'Manage governance responsibilities',
     'Create, end, or supersede an effective governance responsibility.',
     true, 'explicit', true, true, 600, 5, false, 'active', 'm1-candidate-1', true);

INSERT INTO audit_event_definitions
    (event_name, schema_version, display_name, description, subject_type,
     reason_required, required_payload_keys, allowed_payload_keys,
     payload_schema, status, registry_version)
VALUES
    ('organization.governance.changed', 1, 'Organization governance changed',
     'A governed effective organization responsibility changed.',
     'organization_governance_responsibility', true,
     ARRAY['changeType', 'effectiveFrom', 'lockVersion', 'responsibilityId', 'responsibilityType'],
     ARRAY['changeType', 'effectiveFrom', 'lockVersion', 'responsibilityId', 'responsibilityType'],
     '{"type":"object"}'::jsonb, 'active', 'm1-candidate-1');

INSERT INTO outbox_event_definitions
    (event_name, schema_version, description, aggregate_type,
     required_payload_keys, allowed_payload_keys, payload_schema, status, registry_version)
VALUES
    ('organization.governance.changed', 1,
     'A governed effective organization responsibility changed.',
     'organization_governance_responsibility',
     ARRAY['changeType', 'effectiveFrom', 'lockVersion', 'responsibilityId', 'responsibilityType'],
     ARRAY['changeType', 'effectiveFrom', 'lockVersion', 'responsibilityId', 'responsibilityType'],
     '{"type":"object"}'::jsonb, 'active', 'm1-candidate-1');

INSERT INTO authorization_operation_events
    (operation_key, event_kind, event_name, schema_version, status, registry_version)
VALUES
    ('organization.governance.manage', 'audit', 'organization.governance.changed', 1, 'active', 'm1-candidate-1'),
    ('organization.governance.manage', 'outbox', 'organization.governance.changed', 1, 'active', 'm1-candidate-1');

CREATE FUNCTION careos_validate_organization_governance_write()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    configured_organization uuid := nullif(current_setting('app.current_organization_id', true), '')::uuid;
    configured_actor uuid := nullif(current_setting('app.current_actor_id', true), '')::uuid;
    configured_operation text := nullif(current_setting('app.current_operation_key', true), '');
    configured_reason text := nullif(current_setting('app.current_authorization_reason', true), '');
    predecessor organization_governance_responsibilities%ROWTYPE;
BEGIN
    IF current_user <> '${applicationRole}' THEN RETURN NEW; END IF;
    IF NEW.organization_id IS DISTINCT FROM configured_organization
       OR configured_actor IS NULL
       OR configured_operation IS DISTINCT FROM 'organization.governance.manage'
       OR configured_reason IS NULL OR char_length(configured_reason) NOT BETWEEN 10 AND 500
       OR configured_reason IS DISTINCT FROM btrim(configured_reason)
       OR configured_reason IS DISTINCT FROM normalize(configured_reason, NFC)
       OR configured_reason ~ '[[:cntrl:]]' THEN
        RAISE EXCEPTION 'governance writes require exact tenant, actor, operation, and reason context' USING ERRCODE='42501';
    END IF;
    IF (TG_OP='INSERT' AND NEW.created_by IS DISTINCT FROM configured_actor)
       OR NEW.updated_by IS DISTINCT FROM configured_actor THEN
        RAISE EXCEPTION 'governance actor evidence is invalid' USING ERRCODE='42501';
    END IF;
    IF NEW.effective_from > clock_timestamp() + interval '10 years' THEN
        RAISE EXCEPTION 'governance effective time is outside the allowed horizon' USING ERRCODE='23514';
    END IF;
    IF NEW.membership_id IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM organization_memberships m
        WHERE m.organization_id=NEW.organization_id AND m.id=NEW.membership_id
          AND m.status='active' AND m.effective_from <= NEW.effective_from
          AND (m.effective_to IS NULL OR m.effective_to > NEW.effective_from)) THEN
        RAISE EXCEPTION 'linked governance membership is not eligible' USING ERRCODE='23514';
    END IF;
    IF NEW.external_contact_id IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM organization_contacts c
        WHERE c.organization_id=NEW.organization_id AND c.id=NEW.external_contact_id
          AND c.status='active' AND c.verification_status='verified' AND c.effective_from <= NEW.effective_from
          AND (c.effective_to IS NULL OR c.effective_to > NEW.effective_from)) THEN
        RAISE EXCEPTION 'linked governance contact is not eligible' USING ERRCODE='23514';
    END IF;
    IF TG_OP='INSERT' THEN
        IF (NEW.supersedes_id IS NULL AND NEW.lock_version<>0)
           OR NEW.status NOT IN ('scheduled','active')
           OR (NEW.effective_from>clock_timestamp() AND NEW.status<>'scheduled')
           OR (NEW.effective_from<=clock_timestamp() AND NEW.status<>'active') THEN
            RAISE EXCEPTION 'invalid governance creation evidence' USING ERRCODE='23514';
        END IF;
        IF NEW.supersedes_id IS NOT NULL THEN
            SELECT * INTO predecessor FROM organization_governance_responsibilities
             WHERE organization_id=NEW.organization_id AND id=NEW.supersedes_id FOR UPDATE;
            IF NOT FOUND OR predecessor.responsibility_type<>NEW.responsibility_type
               OR predecessor.status<>'superseded' OR predecessor.effective_to IS DISTINCT FROM NEW.effective_from
               OR predecessor.lock_version<>NEW.lock_version THEN
                RAISE EXCEPTION 'invalid governance replacement link' USING ERRCODE='23514';
            END IF;
        END IF;
    ELSE
        IF OLD.status='active' AND NEW.status='ended' THEN
            RAISE EXCEPTION 'required governance handoff cannot create a gap' USING ERRCODE='23514';
        END IF;
        IF NEW.id<>OLD.id OR NEW.organization_id<>OLD.organization_id
           OR NEW.responsibility_type<>OLD.responsibility_type
           OR NEW.membership_id IS DISTINCT FROM OLD.membership_id
           OR NEW.external_contact_id IS DISTINCT FROM OLD.external_contact_id
           OR NEW.escalation_email IS DISTINCT FROM OLD.escalation_email
           OR NEW.escalation_phone IS DISTINCT FROM OLD.escalation_phone
           OR NEW.is_primary<>OLD.is_primary OR NEW.effective_from<>OLD.effective_from
           OR NEW.supersedes_id IS DISTINCT FROM OLD.supersedes_id
           OR NEW.created_at<>OLD.created_at OR NEW.created_by<>OLD.created_by
           OR NEW.lock_version<>OLD.lock_version+1
           OR NEW.updated_by IS DISTINCT FROM configured_actor OR NEW.updated_at<=OLD.updated_at
           OR OLD.status NOT IN ('active','scheduled') OR NEW.status NOT IN ('ended','superseded')
           OR NEW.effective_to IS NULL OR NEW.effective_to<=NEW.effective_from THEN
            RAISE EXCEPTION 'invalid governance lifecycle transition' USING ERRCODE='23514';
        END IF;
    END IF;
    IF EXISTS (
        SELECT 1 FROM organization_governance_responsibilities other
         WHERE other.organization_id=NEW.organization_id AND other.id<>NEW.id
           AND other.responsibility_type=NEW.responsibility_type
           AND other.is_primary AND NEW.is_primary
           AND other.status IN ('active','scheduled') AND NEW.status IN ('active','scheduled')
           AND tstzrange(other.effective_from,other.effective_to,'[)') && tstzrange(NEW.effective_from,NEW.effective_to,'[)')) THEN
        RAISE EXCEPTION 'governance responsibility effective ranges overlap' USING ERRCODE='23P01';
    END IF;
    RETURN NEW;
END; $$;

CREATE TRIGGER organization_governance_validate_write
    BEFORE INSERT OR UPDATE ON organization_governance_responsibilities
    FOR EACH ROW EXECUTE FUNCTION careos_validate_organization_governance_write();

CREATE FUNCTION careos_require_organization_governance_replacement()
RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
    IF NEW.status='superseded' AND NOT EXISTS (
        SELECT 1 FROM organization_governance_responsibilities replacement
        WHERE replacement.organization_id=NEW.organization_id
          AND replacement.supersedes_id=NEW.id
          AND replacement.responsibility_type=NEW.responsibility_type
          AND replacement.effective_from=NEW.effective_to
          AND replacement.lock_version=NEW.lock_version
          AND replacement.status IN ('active','scheduled')) THEN
        RAISE EXCEPTION 'required governance replacement is missing' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END; $$;
CREATE CONSTRAINT TRIGGER organization_governance_requires_replacement
    AFTER INSERT OR UPDATE ON organization_governance_responsibilities
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION careos_require_organization_governance_replacement();

CREATE FUNCTION careos_reject_organization_governance_delete()
RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
    RAISE EXCEPTION 'governance responsibility history is immutable' USING ERRCODE='42501';
END; $$;
CREATE TRIGGER organization_governance_no_delete
    BEFORE DELETE ON organization_governance_responsibilities
    FOR EACH ROW EXECUTE FUNCTION careos_reject_organization_governance_delete();

REVOKE ALL ON organization_governance_responsibilities FROM PUBLIC;
REVOKE ALL ON FUNCTION careos_validate_organization_governance_write() FROM PUBLIC;
REVOKE ALL ON FUNCTION careos_reject_organization_governance_delete() FROM PUBLIC;
REVOKE ALL ON FUNCTION careos_require_organization_governance_replacement() FROM PUBLIC;
GRANT SELECT, INSERT, UPDATE ON organization_governance_responsibilities TO "${applicationRole}";

COMMENT ON TABLE organization_governance_responsibilities IS
    'Tenant-isolated confidential effective governance responsibility history.';
