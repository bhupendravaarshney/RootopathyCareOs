DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '${applicationRole}') THEN
        RAISE EXCEPTION 'Configured application role does not exist';
    END IF;
END
$$;

REVOKE CREATE ON SCHEMA public FROM PUBLIC;
REVOKE ALL ON ALL TABLES IN SCHEMA public FROM PUBLIC;
REVOKE ALL ON ALL SEQUENCES IN SCHEMA public FROM PUBLIC;

GRANT USAGE ON SCHEMA public TO "${applicationRole}";
GRANT SELECT, INSERT, UPDATE ON users TO "${applicationRole}";
GRANT SELECT, UPDATE ON organizations TO "${applicationRole}";
GRANT SELECT, INSERT, UPDATE, DELETE ON organization_memberships TO "${applicationRole}";
GRANT SELECT, INSERT, UPDATE, DELETE ON facilities TO "${applicationRole}";
GRANT SELECT, INSERT ON audit_events TO "${applicationRole}";
GRANT SELECT, INSERT, UPDATE ON outbox_events TO "${applicationRole}";
GRANT SELECT, INSERT, UPDATE, DELETE ON idempotency_records TO "${applicationRole}";

ALTER TABLE organizations ENABLE ROW LEVEL SECURITY;
ALTER TABLE organizations FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS organizations_tenant_policy ON organizations;
CREATE POLICY organizations_tenant_policy ON organizations
    USING (id = nullif(current_setting('app.current_organization_id', true), '')::uuid)
    WITH CHECK (id = nullif(current_setting('app.current_organization_id', true), '')::uuid);

DROP POLICY IF EXISTS facilities_tenant_policy ON facilities;
CREATE POLICY facilities_tenant_policy ON facilities
    USING (organization_id = nullif(current_setting('app.current_organization_id', true), '')::uuid)
    WITH CHECK (organization_id = nullif(current_setting('app.current_organization_id', true), '')::uuid);

DROP POLICY IF EXISTS audit_events_tenant_policy ON audit_events;
CREATE POLICY audit_events_tenant_policy ON audit_events
    USING (organization_id = nullif(current_setting('app.current_organization_id', true), '')::uuid)
    WITH CHECK (organization_id = nullif(current_setting('app.current_organization_id', true), '')::uuid);

DROP POLICY IF EXISTS organization_memberships_tenant_policy ON organization_memberships;
CREATE POLICY organization_memberships_tenant_policy ON organization_memberships
    USING (organization_id = nullif(current_setting('app.current_organization_id', true), '')::uuid)
    WITH CHECK (organization_id = nullif(current_setting('app.current_organization_id', true), '')::uuid);

DROP POLICY IF EXISTS outbox_events_tenant_policy ON outbox_events;
CREATE POLICY outbox_events_tenant_policy ON outbox_events
    USING (organization_id = nullif(current_setting('app.current_organization_id', true), '')::uuid)
    WITH CHECK (organization_id = nullif(current_setting('app.current_organization_id', true), '')::uuid);

DROP POLICY IF EXISTS idempotency_records_tenant_policy ON idempotency_records;
CREATE POLICY idempotency_records_tenant_policy ON idempotency_records
    USING (organization_id = nullif(current_setting('app.current_organization_id', true), '')::uuid)
    WITH CHECK (organization_id = nullif(current_setting('app.current_organization_id', true), '')::uuid);
