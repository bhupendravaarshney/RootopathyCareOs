-- The local bootstrap role is deliberately a reference role. The Java authorization
-- boundary permits reference policy only when the transaction explicitly enables it,
-- but the Module 2 resource-scope functions introduced in V62 only accepted active
-- roles. Preserve the production policy and add the same explicit local-policy gate.

ALTER FUNCTION careos_m2_user_can_access_member(uuid, uuid, uuid, text)
    RENAME TO careos_m2_user_can_access_member_active_policy;

ALTER FUNCTION careos_m2_user_has_resource_scope(uuid, uuid, text)
    RENAME TO careos_m2_user_has_resource_scope_active_policy;

CREATE FUNCTION careos_m2_local_bootstrap_has_permission(
    requested_organization_id uuid,
    requested_user_id uuid,
    requested_permission_key text
) RETURNS boolean
LANGUAGE sql
STABLE
SET search_path=public,pg_temp
AS $$
    SELECT coalesce(
               nullif(current_setting('app.reference_authorization_policy_enabled', true), '')::boolean,
               false)
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

CREATE FUNCTION careos_m2_user_can_access_member(
    requested_organization_id uuid,
    requested_user_id uuid,
    requested_member_id uuid,
    requested_permission_key text
) RETURNS boolean
LANGUAGE sql
STABLE
SET search_path=public,pg_temp
AS $$
    SELECT careos_m2_user_can_access_member_active_policy(
               requested_organization_id,
               requested_user_id,
               requested_member_id,
               requested_permission_key)
        OR (
            careos_m2_local_bootstrap_has_permission(
                requested_organization_id,
                requested_user_id,
                requested_permission_key)
            AND requested_member_id IS NOT NULL
            AND EXISTS (
                SELECT 1
                FROM workforce_members member
                WHERE member.organization_id=requested_organization_id
                  AND member.id=requested_member_id));
$$;

CREATE FUNCTION careos_m2_user_has_resource_scope(
    requested_organization_id uuid,
    requested_user_id uuid,
    requested_permission_key text
) RETURNS boolean
LANGUAGE sql
STABLE
SET search_path=public,pg_temp
AS $$
    SELECT careos_m2_user_has_resource_scope_active_policy(
               requested_organization_id,
               requested_user_id,
               requested_permission_key)
        OR careos_m2_local_bootstrap_has_permission(
               requested_organization_id,
               requested_user_id,
               requested_permission_key);
$$;

REVOKE ALL ON FUNCTION careos_m2_local_bootstrap_has_permission(uuid,uuid,text) FROM PUBLIC;
REVOKE ALL ON FUNCTION careos_m2_user_can_access_member(uuid,uuid,uuid,text) FROM PUBLIC;
REVOKE ALL ON FUNCTION careos_m2_user_has_resource_scope(uuid,uuid,text) FROM PUBLIC;

GRANT EXECUTE ON FUNCTION careos_m2_local_bootstrap_has_permission(uuid,uuid,text),
                          careos_m2_user_can_access_member(uuid,uuid,uuid,text),
                          careos_m2_user_has_resource_scope(uuid,uuid,text)
    TO "${applicationRole}";
