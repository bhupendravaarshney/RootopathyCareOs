-- Screen action projection must use the same local-reference boundary as the
-- authorization executor. Active production roles remain unchanged. The exact
-- reference-only local_bootstrap role is visible only when both the transaction
-- flag and the deployment-owned V115 capability are present.

CREATE FUNCTION careos_projected_interactive_permissions(
    requested_organization_id uuid,
    requested_user_id uuid
) RETURNS TABLE(permission_key text)
LANGUAGE sql
STABLE
SECURITY INVOKER
SET search_path=public,pg_temp
AS $$
    SELECT DISTINCT permission.permission_key
    FROM organization_memberships membership
    JOIN authorization_roles role
      ON role.role_key=membership.role_key
     AND role.interactive
    JOIN authorization_role_permissions role_permission
      ON role_permission.role_key=role.role_key
    JOIN authorization_permissions permission
      ON permission.permission_key=role_permission.permission_key
     AND permission.status='active'
    WHERE requested_organization_id IS NOT NULL
      AND requested_user_id IS NOT NULL
      AND requested_organization_id=
          nullif(current_setting('app.current_organization_id', true), '')::uuid
      AND requested_user_id=
          nullif(current_setting('app.current_actor_id', true), '')::uuid
      AND membership.organization_id=requested_organization_id
      AND membership.user_id=requested_user_id
      AND membership.status='active'
      AND membership.effective_from<=clock_timestamp()
      AND (membership.effective_to IS NULL
           OR membership.effective_to>clock_timestamp())
      AND (
          role.status='active'
          OR (
              role.role_key='local_bootstrap'
              AND role.status='reference'
              AND careos_reference_authorization_enabled()))
      AND EXISTS (
          SELECT 1
          FROM authorization_registry_releases release
          WHERE release.registry_version=role.registry_version
            AND release.status='active')
      AND EXISTS (
          SELECT 1
          FROM authorization_registry_releases release
          WHERE release.registry_version=permission.registry_version
            AND release.status='active');
$$;

REVOKE ALL ON FUNCTION careos_projected_interactive_permissions(uuid,uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION careos_projected_interactive_permissions(uuid,uuid)
    TO "${applicationRole}";

COMMENT ON FUNCTION careos_projected_interactive_permissions(uuid,uuid) IS
    'Projects active-role permissions plus capability-gated local_bootstrap permissions for the exact transaction tenant and actor; it does not authorize an operation.';
