DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM authorization_registry_releases
        WHERE registry_version = 'm1-candidate-1'
          AND approval_record_id = 'M1-APPROVAL-20260916-01'
          AND approval_package_sha256 =
              '19aff5ce30516b7ee2101c093a8429d8a74394995ca90d486790bcc18a392946'
          AND status = 'active'
    ) THEN
        RAISE EXCEPTION
            'V24 requires the active checksum-approved Module 1 authorization release';
    END IF;
END;
$$;

ALTER TABLE authorization_roles
    ADD COLUMN mfa_required boolean NOT NULL DEFAULT false,
    ADD CONSTRAINT authorization_roles_mfa_required_check
        CHECK (NOT mfa_required OR interactive);

UPDATE authorization_roles
SET mfa_required = role_key IN (
    'organization_owner',
    'organization_administrator',
    'configuration_approver',
    'security_administrator',
    'auditor',
    'export_approver'
);

CREATE TABLE identity_mfa_role_requirements (
    membership_id uuid PRIMARY KEY
        REFERENCES organization_memberships(id) ON DELETE CASCADE,
    user_id uuid NOT NULL REFERENCES users(id),
    role_key varchar(100) NOT NULL REFERENCES authorization_roles(role_key),
    membership_status varchar(32) NOT NULL,
    effective_from timestamptz NOT NULL,
    effective_to timestamptz,
    CHECK (membership_status IN ('active', 'suspended', 'revoked', 'expired')),
    CHECK (isfinite(effective_from)),
    CHECK (effective_to IS NULL OR (isfinite(effective_to) AND effective_to > effective_from))
);

CREATE INDEX identity_mfa_role_requirements_user_effective_idx
    ON identity_mfa_role_requirements
        (user_id, membership_status, effective_from, effective_to);

INSERT INTO identity_mfa_role_requirements
    (membership_id, user_id, role_key, membership_status, effective_from, effective_to)
SELECT memberships.id,
       memberships.user_id,
       memberships.role_key,
       memberships.status,
       memberships.effective_from,
       memberships.effective_to
FROM organization_memberships memberships
JOIN authorization_roles roles ON roles.role_key = memberships.role_key
WHERE roles.mfa_required
  AND roles.status = 'active'
  AND roles.registry_version = 'm1-candidate-1';

CREATE FUNCTION careos_sync_identity_mfa_role_requirement()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        DELETE FROM public.identity_mfa_role_requirements
        WHERE membership_id = OLD.id;
        RETURN OLD;
    END IF;

    IF EXISTS (
        SELECT 1
        FROM public.authorization_roles roles
        WHERE roles.role_key = NEW.role_key
          AND roles.mfa_required
          AND roles.status = 'active'
          AND roles.registry_version = 'm1-candidate-1'
    ) THEN
        INSERT INTO public.identity_mfa_role_requirements
            (membership_id, user_id, role_key, membership_status,
             effective_from, effective_to)
        VALUES
            (NEW.id, NEW.user_id, NEW.role_key, NEW.status,
             NEW.effective_from, NEW.effective_to)
        ON CONFLICT (membership_id) DO UPDATE
        SET user_id = EXCLUDED.user_id,
            role_key = EXCLUDED.role_key,
            membership_status = EXCLUDED.membership_status,
            effective_from = EXCLUDED.effective_from,
            effective_to = EXCLUDED.effective_to;
    ELSE
        DELETE FROM public.identity_mfa_role_requirements
        WHERE membership_id = NEW.id;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER organization_memberships_sync_mfa_requirement
    AFTER INSERT OR UPDATE OF user_id, role_key, status, effective_from, effective_to
        OR DELETE ON organization_memberships
    FOR EACH ROW EXECUTE FUNCTION careos_sync_identity_mfa_role_requirement();

CREATE FUNCTION careos_user_requires_mfa(candidate_user_id uuid)
RETURNS boolean
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
    SELECT EXISTS (
        SELECT 1
        FROM public.identity_mfa_role_requirements requirements
        WHERE requirements.user_id = candidate_user_id
          AND requirements.membership_status = 'active'
          AND requirements.effective_from <= clock_timestamp()
          AND (requirements.effective_to IS NULL
               OR requirements.effective_to > clock_timestamp())
    )
$$;

CREATE FUNCTION careos_protect_mfa_method_disable()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, public
AS $$
DECLARE
    configured_organization uuid :=
        nullif(current_setting('app.current_organization_id', true), '')::uuid;
    configured_actor uuid :=
        nullif(current_setting('app.current_actor_id', true), '')::uuid;
    configured_operation text :=
        nullif(current_setting('app.current_operation_key', true), '');
    configured_correlation text :=
        nullif(current_setting('app.current_correlation_id', true), '');
    configured_approval uuid :=
        nullif(current_setting('app.current_approval_id', true), '')::uuid;
BEGIN
    IF OLD.status = 'enabled' AND NEW.status <> 'enabled' THEN
        IF configured_operation IS DISTINCT FROM 'identity.mfa.admin-reset.execute'
            OR configured_organization IS NULL
            OR configured_actor IS NULL
            OR configured_correlation IS NULL
            OR configured_approval IS NULL
            OR NOT EXISTS (
                SELECT 1
                FROM public.authorization_approval_requests approval
                WHERE approval.id = configured_approval
                  AND approval.organization_id = configured_organization
                  AND approval.operation_key = 'identity.mfa.admin-reset.execute'
                  AND approval.subject_type = 'user'
                  AND approval.subject_id = OLD.user_id
                  AND approval.status = 'consumed'
                  AND approval.consumed_by_user_id = configured_actor
                  AND approval.consumption_correlation_id = configured_correlation
            ) THEN
            RAISE EXCEPTION
                'enabled MFA may be removed only by the exact governed administrative reset'
                USING ERRCODE = '42501';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER mfa_methods_protect_disable
    BEFORE UPDATE OF status ON mfa_methods
    FOR EACH ROW EXECUTE FUNCTION careos_protect_mfa_method_disable();

REVOKE ALL ON identity_mfa_role_requirements FROM PUBLIC;
REVOKE ALL ON identity_mfa_role_requirements FROM "${applicationRole}";
REVOKE ALL ON FUNCTION careos_sync_identity_mfa_role_requirement() FROM PUBLIC;
REVOKE ALL ON FUNCTION careos_user_requires_mfa(uuid) FROM PUBLIC;
REVOKE ALL ON FUNCTION careos_protect_mfa_method_disable() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION careos_user_requires_mfa(uuid) TO "${applicationRole}";

COMMENT ON COLUMN authorization_roles.mfa_required IS
    'Approved role-level requirement to enroll and use MFA while any effective membership is active.';
COMMENT ON TABLE identity_mfa_role_requirements IS
    'Non-tenant-disclosing login index derived from approved mandatory-role memberships; runtime access is function-only.';
COMMENT ON FUNCTION careos_user_requires_mfa(uuid) IS
    'Returns only whether an account currently holds any effective approved role that mandates MFA.';
COMMENT ON FUNCTION careos_protect_mfa_method_disable() IS
    'Rejects enabled-factor removal unless exact consumed maker-checker administrative-reset evidence is transaction-bound.';
