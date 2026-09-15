ALTER TABLE authorization_permissions
    DROP CONSTRAINT authorization_permissions_status_check,
    ADD COLUMN scope varchar(24) NOT NULL DEFAULT 'organization',
    ADD COLUMN risk_class varchar(24) NOT NULL DEFAULT 'low',
    ADD CONSTRAINT authorization_permissions_status_check
        CHECK (status IN ('active', 'reference', 'retired')),
    ADD CONSTRAINT authorization_permissions_scope_check
        CHECK (scope IN ('organization', 'self')),
    ADD CONSTRAINT authorization_permissions_risk_check
        CHECK (risk_class IN ('low', 'moderate', 'high', 'critical')),
    ADD CONSTRAINT authorization_permissions_registry_version_check
        CHECK (registry_version ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,79}$');

ALTER TABLE authorization_roles
    DROP CONSTRAINT authorization_roles_status_check,
    ADD COLUMN interactive boolean NOT NULL DEFAULT true,
    ADD COLUMN invitation_assignable boolean NOT NULL DEFAULT false,
    ADD COLUMN final_owner boolean NOT NULL DEFAULT false,
    ADD CONSTRAINT authorization_roles_status_check
        CHECK (status IN ('active', 'reference', 'retired')),
    ADD CONSTRAINT authorization_roles_registry_version_check
        CHECK (registry_version ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,79}$'),
    ADD CONSTRAINT authorization_roles_owner_check
        CHECK (NOT final_owner OR (interactive AND NOT invitation_assignable));

CREATE TABLE authorization_operations (
    operation_key varchar(160) PRIMARY KEY,
    permission_key varchar(120) NOT NULL REFERENCES authorization_permissions(permission_key),
    display_name varchar(180) NOT NULL,
    description text NOT NULL,
    mutation boolean NOT NULL DEFAULT false,
    denial_mode varchar(24) NOT NULL DEFAULT 'explicit',
    reason_required boolean NOT NULL DEFAULT false,
    recent_authentication_required boolean NOT NULL DEFAULT false,
    recent_authentication_max_age_seconds integer,
    maximum_future_skew_seconds integer,
    maker_checker_required boolean NOT NULL DEFAULT false,
    status varchar(24) NOT NULL DEFAULT 'active',
    registry_version varchar(80) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CHECK (operation_key ~ '^[a-z][a-z0-9]*([.:-][a-z0-9]+)*$'),
    CHECK (char_length(btrim(display_name)) BETWEEN 1 AND 180),
    CHECK (char_length(btrim(description)) BETWEEN 1 AND 2000),
    CHECK (denial_mode IN ('explicit', 'hidden')),
    CHECK (status IN ('active', 'reference', 'retired')),
    CHECK (registry_version ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,79}$'),
    CHECK (
        (recent_authentication_required
            AND recent_authentication_max_age_seconds BETWEEN 1 AND 3600
            AND maximum_future_skew_seconds BETWEEN 0 AND 60)
        OR
        (NOT recent_authentication_required
            AND recent_authentication_max_age_seconds IS NULL
            AND maximum_future_skew_seconds IS NULL)
    ),
    CHECK (NOT maker_checker_required OR mutation),
    CHECK (isfinite(created_at))
);

CREATE TABLE authorization_role_delegations (
    delegator_role_key varchar(100) NOT NULL REFERENCES authorization_roles(role_key),
    target_role_key varchar(100) NOT NULL REFERENCES authorization_roles(role_key),
    registry_version varchar(80) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (delegator_role_key, target_role_key),
    CHECK (delegator_role_key <> target_role_key),
    CHECK (registry_version ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,79}$'),
    CHECK (isfinite(created_at))
);

INSERT INTO authorization_permissions
    (permission_key, display_name, description, status, registry_version, scope, risk_class)
VALUES
    ('organization.profile.read', 'Read organization profile',
     'View the current organization profile and non-sensitive configuration.',
     'reference', 'careos-phase0-reference-v1', 'organization', 'low'),
    ('organization.membership.read', 'Read organization memberships',
     'View membership summaries for the current organization.',
     'reference', 'careos-phase0-reference-v1', 'organization', 'moderate'),
    ('organization.invitation.issue', 'Issue organization invitations',
     'Issue a bounded invitation for an explicitly delegable non-owner role.',
     'reference', 'careos-phase0-reference-v1', 'organization', 'high'),
    ('organization.invitation.revoke', 'Revoke organization invitations',
     'Revoke an unaccepted organization invitation.',
     'reference', 'careos-phase0-reference-v1', 'organization', 'high'),
    ('organization.membership.manage', 'Manage organization memberships',
     'Change or revoke an existing organization membership subject to owner protection.',
     'reference', 'careos-phase0-reference-v1', 'organization', 'critical'),
    ('organization.audit.read', 'Read organization audit evidence',
     'View minimum-necessary governance evidence for the current organization.',
     'reference', 'careos-phase0-reference-v1', 'organization', 'high'),
    ('identity.mfa.admin-reset', 'Administer MFA reset',
     'Reset another account MFA state only through a separately approved workflow.',
     'reference', 'careos-phase0-reference-v1', 'organization', 'critical');

INSERT INTO authorization_roles
    (role_key, display_name, description, status, registry_version,
     interactive, invitation_assignable, final_owner)
VALUES
    ('organization_owner', 'Organization owner',
     'Reference interactive owner role; at least one effective owner must remain.',
     'reference', 'careos-phase0-reference-v1', true, false, true),
    ('organization_administrator', 'Organization administrator',
     'Reference delegated administrator role without owner-transfer or MFA-reset authority.',
     'reference', 'careos-phase0-reference-v1', true, true, false),
    ('organization_member', 'Organization member',
     'Reference minimum-access interactive membership role.',
     'reference', 'careos-phase0-reference-v1', true, true, false),
    ('local_bootstrap', 'Local bootstrap owner',
     'Synthetic local-development owner role; never approved for production activation.',
     'reference', 'careos-phase0-reference-v1', true, false, true);

INSERT INTO authorization_role_permissions (role_key, permission_key)
SELECT roles.role_key, permissions.permission_key
FROM (VALUES ('organization_owner'), ('local_bootstrap')) AS roles(role_key)
CROSS JOIN (VALUES
    ('organization.profile.read'),
    ('organization.membership.read'),
    ('organization.invitation.issue'),
    ('organization.invitation.revoke'),
    ('organization.membership.manage'),
    ('organization.audit.read'),
    ('identity.mfa.admin-reset')
) AS permissions(permission_key);

INSERT INTO authorization_role_permissions (role_key, permission_key)
VALUES
    ('organization_administrator', 'organization.profile.read'),
    ('organization_administrator', 'organization.membership.read'),
    ('organization_administrator', 'organization.invitation.issue'),
    ('organization_administrator', 'organization.invitation.revoke'),
    ('organization_administrator', 'organization.audit.read'),
    ('organization_member', 'organization.profile.read');

INSERT INTO authorization_operations
    (operation_key, permission_key, display_name, description, mutation, denial_mode,
     reason_required, recent_authentication_required,
     recent_authentication_max_age_seconds, maximum_future_skew_seconds,
     maker_checker_required, status, registry_version)
VALUES
    ('organization.profile.read', 'organization.profile.read', 'Read organization profile',
     'Read the selected organization profile after tenant authorization.',
     false, 'hidden', false, false, NULL, NULL, false,
     'reference', 'careos-phase0-reference-v1'),
    ('organization.membership.list', 'organization.membership.read', 'List memberships',
     'List minimum-necessary organization membership summaries.',
     false, 'hidden', false, false, NULL, NULL, false,
     'reference', 'careos-phase0-reference-v1'),
    ('organization.invitation.issue', 'organization.invitation.issue', 'Issue invitation',
     'Issue a time-bounded invitation to an explicitly delegable role.',
     true, 'explicit', true, true, 600, 5, false,
     'reference', 'careos-phase0-reference-v1'),
    ('organization.invitation.revoke', 'organization.invitation.revoke', 'Revoke invitation',
     'Revoke an unaccepted invitation with durable governance evidence.',
     true, 'explicit', true, true, 600, 5, false,
     'reference', 'careos-phase0-reference-v1'),
    ('organization.membership.change-role', 'organization.membership.manage', 'Change membership role',
     'Change an existing role only after independent approval and owner safeguards.',
     true, 'explicit', true, true, 600, 5, true,
     'reference', 'careos-phase0-reference-v1'),
    ('organization.membership.revoke', 'organization.membership.manage', 'Revoke membership',
     'Revoke an existing membership only after independent approval and owner safeguards.',
     true, 'explicit', true, true, 600, 5, true,
     'reference', 'careos-phase0-reference-v1'),
    ('organization.audit.read', 'organization.audit.read', 'Read audit evidence',
     'Read minimum-necessary audit evidence after recent authentication.',
     false, 'hidden', false, true, 600, 5, false,
     'reference', 'careos-phase0-reference-v1'),
    ('identity.mfa.admin-reset', 'identity.mfa.admin-reset', 'Administratively reset MFA',
     'Reset another account MFA only after independent approval and recent authentication.',
     true, 'explicit', true, true, 300, 5, true,
     'reference', 'careos-phase0-reference-v1');

INSERT INTO authorization_role_delegations
    (delegator_role_key, target_role_key, registry_version)
VALUES
    ('organization_owner', 'organization_administrator', 'careos-phase0-reference-v1'),
    ('organization_owner', 'organization_member', 'careos-phase0-reference-v1'),
    ('organization_administrator', 'organization_member', 'careos-phase0-reference-v1'),
    ('local_bootstrap', 'organization_administrator', 'careos-phase0-reference-v1'),
    ('local_bootstrap', 'organization_member', 'careos-phase0-reference-v1');

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM organization_memberships memberships
        LEFT JOIN authorization_roles roles ON roles.role_key = memberships.role_key
        WHERE roles.role_key IS NULL
    ) THEN
        RAISE EXCEPTION
            'V14 cannot attach role integrity while organization memberships use unregistered role keys';
    END IF;
END;
$$;

ALTER TABLE organization_memberships
    ADD CONSTRAINT organization_memberships_role_fk
        FOREIGN KEY (role_key) REFERENCES authorization_roles(role_key);

CREATE FUNCTION careos_protect_final_owner_membership()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    old_is_current_owner boolean;
    new_is_indefinite_owner boolean := false;
    another_indefinite_owner_exists boolean;
BEGIN
    SELECT roles.final_owner
           AND OLD.status = 'active'
           AND OLD.effective_from <= clock_timestamp()
           AND (OLD.effective_to IS NULL OR OLD.effective_to > clock_timestamp())
    INTO old_is_current_owner
    FROM authorization_roles roles
    WHERE roles.role_key = OLD.role_key;

    IF NOT coalesce(old_is_current_owner, false) THEN
        IF TG_OP = 'DELETE' THEN
            RETURN OLD;
        END IF;
        RETURN NEW;
    END IF;

    IF TG_OP = 'UPDATE' THEN
        SELECT roles.final_owner
               AND NEW.organization_id = OLD.organization_id
               AND NEW.status = 'active'
               AND NEW.effective_from <= clock_timestamp()
               AND NEW.effective_to IS NULL
        INTO new_is_indefinite_owner
        FROM authorization_roles roles
        WHERE roles.role_key = NEW.role_key;
        IF coalesce(new_is_indefinite_owner, false) THEN
            RETURN NEW;
        END IF;
    END IF;

    PERFORM pg_advisory_xact_lock(hashtextextended(OLD.organization_id::text, 0));
    SELECT EXISTS (
        SELECT 1
        FROM organization_memberships memberships
        JOIN authorization_roles roles ON roles.role_key = memberships.role_key
        WHERE memberships.organization_id = OLD.organization_id
          AND memberships.id <> OLD.id
          AND memberships.status = 'active'
          AND memberships.effective_from <= clock_timestamp()
          AND memberships.effective_to IS NULL
          AND roles.final_owner
    )
    INTO another_indefinite_owner_exists;

    IF NOT another_indefinite_owner_exists THEN
        RAISE EXCEPTION 'the final effective organization owner cannot be removed or scheduled to expire'
            USING ERRCODE = '23514';
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER organization_memberships_protect_final_owner
    BEFORE UPDATE OR DELETE ON organization_memberships
    FOR EACH ROW EXECUTE FUNCTION careos_protect_final_owner_membership();

REVOKE ALL ON authorization_operations FROM PUBLIC;
REVOKE ALL ON authorization_role_delegations FROM PUBLIC;
GRANT SELECT ON authorization_operations TO "${applicationRole}";
GRANT SELECT ON authorization_role_delegations TO "${applicationRole}";

COMMENT ON TABLE authorization_operations IS
    'Migration-owned operation-to-permission and risk requirements. Reference rows require an explicit non-production opt-in.';
COMMENT ON TABLE authorization_role_delegations IS
    'Explicit role assignment ceilings; absence denies delegation and owner transfer is intentionally absent.';
COMMENT ON COLUMN authorization_permissions.status IS
    'active is approved runtime policy, reference requires an explicit non-production opt-in, retired grants nothing.';
COMMENT ON COLUMN authorization_roles.final_owner IS
    'Marks roles protected by the database final-effective-owner invariant.';
