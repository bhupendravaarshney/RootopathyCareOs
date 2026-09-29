-- V114 deliberately preserved the production workforce policy while allowing the
-- reference-only local_bootstrap role in local/demo environments. A custom GUC is
-- request context, not an authority boundary: any connected PostgreSQL role can set
-- an arbitrary custom GUC. Require a deployment-owned, non-login role membership as
-- a second factor that the restricted application role cannot create or grant.
--
-- careos_local_reference_authority is provisioned only by the explicit local/test
-- database bootstrap. It has no object privileges, cannot be assumed with SET ROLE,
-- and is intentionally absent from production provisioning.

CREATE FUNCTION careos_reference_authorization_enabled()
RETURNS boolean
LANGUAGE sql
STABLE
SET search_path=pg_catalog,pg_temp
AS $$
    SELECT coalesce(
               nullif(current_setting('app.reference_authorization_policy_enabled', true), '')::boolean,
               false)
       AND EXISTS (
            SELECT 1
            FROM pg_catalog.pg_roles capability
            JOIN pg_catalog.pg_auth_members membership
              ON membership.roleid=capability.oid
            JOIN pg_catalog.pg_roles runtime_role
              ON runtime_role.oid=membership.member
            WHERE capability.rolname='careos_local_reference_authority'
              AND runtime_role.rolname=session_user
              AND NOT capability.rolcanlogin
              AND NOT capability.rolsuper
              AND NOT capability.rolcreatedb
              AND NOT capability.rolcreaterole
              AND NOT capability.rolbypassrls
              AND NOT membership.admin_option
              AND NOT membership.inherit_option
              AND NOT membership.set_option);
$$;

CREATE OR REPLACE FUNCTION careos_m2_local_bootstrap_has_permission(
    requested_organization_id uuid,
    requested_user_id uuid,
    requested_permission_key text
) RETURNS boolean
LANGUAGE sql
STABLE
SET search_path=public,pg_temp
AS $$
    SELECT careos_reference_authorization_enabled()
       AND requested_organization_id IS NOT NULL
       AND requested_user_id IS NOT NULL
       AND requested_permission_key IS NOT NULL
       AND EXISTS (
            SELECT 1
            FROM organization_memberships membership
            JOIN authorization_roles role
              ON role.role_key=membership.role_key
             AND role.role_key='local_bootstrap'
             AND role.status='reference'
             AND role.interactive
            JOIN authorization_role_permissions role_permission
              ON role_permission.role_key=role.role_key
             AND role_permission.permission_key=requested_permission_key
            JOIN authorization_permissions permission
              ON permission.permission_key=role_permission.permission_key
             AND permission.status IN ('active','reference')
            WHERE membership.organization_id=requested_organization_id
              AND membership.user_id=requested_user_id
              AND membership.status='active'
              AND membership.effective_from<=clock_timestamp()
              AND (membership.effective_to IS NULL
                   OR membership.effective_to>clock_timestamp())
              AND EXISTS (
                  SELECT 1
                  FROM authorization_registry_releases release
                  WHERE release.registry_version=role.registry_version
                    AND release.status='active')
              AND EXISTS (
                  SELECT 1
                  FROM authorization_registry_releases release
                  WHERE release.registry_version=permission.registry_version
                    AND release.status='active'));
$$;

REVOKE ALL ON FUNCTION careos_reference_authorization_enabled() FROM PUBLIC;
REVOKE ALL ON FUNCTION careos_m2_local_bootstrap_has_permission(uuid,uuid,text) FROM PUBLIC;

GRANT EXECUTE ON FUNCTION careos_reference_authorization_enabled(),
                          careos_m2_local_bootstrap_has_permission(uuid,uuid,text)
    TO "${applicationRole}";
