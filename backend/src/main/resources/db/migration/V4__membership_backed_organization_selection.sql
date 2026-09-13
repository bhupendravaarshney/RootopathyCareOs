ALTER TABLE organizations
    ADD CONSTRAINT organizations_status_check
    CHECK (status IN ('draft', 'active', 'suspended', 'disabled'));

ALTER TABLE organization_memberships
    ADD CONSTRAINT organization_memberships_status_check
    CHECK (status IN ('active', 'suspended', 'revoked', 'expired'));

ALTER TABLE organization_memberships
    ADD CONSTRAINT organization_memberships_effective_range_check
    CHECK (effective_to IS NULL OR effective_to > effective_from);

CREATE INDEX organization_memberships_user_effective_idx
    ON organization_memberships (user_id, status, effective_from, effective_to, organization_id);

DROP POLICY organizations_tenant_policy ON organizations;
CREATE POLICY organizations_tenant_policy ON organizations
    USING (
        id = nullif(current_setting('app.current_organization_id', true), '')::uuid
        OR (
            nullif(current_setting('app.current_organization_id', true), '') IS NULL
            AND EXISTS (
                SELECT 1
                FROM organization_memberships membership
                WHERE membership.organization_id = organizations.id
                  AND membership.user_id = nullif(current_setting('app.current_actor_id', true), '')::uuid
                  AND membership.status = 'active'
                  AND membership.effective_from <= now()
                  AND (membership.effective_to IS NULL OR membership.effective_to > now())
            )
        )
    )
    WITH CHECK (id = nullif(current_setting('app.current_organization_id', true), '')::uuid);

DROP POLICY organization_memberships_tenant_policy ON organization_memberships;
CREATE POLICY organization_memberships_tenant_policy ON organization_memberships
    USING (
        organization_id = nullif(current_setting('app.current_organization_id', true), '')::uuid
        OR (
            nullif(current_setting('app.current_organization_id', true), '') IS NULL
            AND user_id = nullif(current_setting('app.current_actor_id', true), '')::uuid
            AND status = 'active'
            AND effective_from <= now()
            AND (effective_to IS NULL OR effective_to > now())
        )
    )
    WITH CHECK (organization_id = nullif(current_setting('app.current_organization_id', true), '')::uuid);
