CREATE TABLE users (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    email varchar(320) NOT NULL UNIQUE,
    display_name varchar(160) NOT NULL,
    status varchar(32) NOT NULL DEFAULT 'invited',
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    lock_version bigint NOT NULL DEFAULT 0
);

CREATE TABLE organizations (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    legal_name varchar(240) NOT NULL,
    display_name varchar(160) NOT NULL,
    country_code char(2) NOT NULL,
    timezone varchar(80) NOT NULL,
    status varchar(32) NOT NULL DEFAULT 'draft',
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    lock_version bigint NOT NULL DEFAULT 0
);

CREATE TABLE organization_memberships (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    user_id uuid NOT NULL REFERENCES users(id),
    role_key varchar(100) NOT NULL,
    status varchar(32) NOT NULL DEFAULT 'active',
    effective_from timestamptz NOT NULL DEFAULT now(),
    effective_to timestamptz,
    UNIQUE (organization_id, user_id, role_key)
);

CREATE TABLE facilities (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    name varchar(180) NOT NULL,
    code varchar(40) NOT NULL,
    status varchar(32) NOT NULL DEFAULT 'draft',
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    lock_version bigint NOT NULL DEFAULT 0,
    UNIQUE (organization_id, code)
);

CREATE TABLE audit_events (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid REFERENCES organizations(id),
    actor_user_id uuid REFERENCES users(id),
    event_name varchar(180) NOT NULL,
    subject_type varchar(120) NOT NULL,
    subject_id uuid,
    reason text,
    payload jsonb NOT NULL DEFAULT '{}'::jsonb,
    occurred_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE outbox_events (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid REFERENCES organizations(id),
    event_name varchar(180) NOT NULL,
    aggregate_type varchar(120) NOT NULL,
    aggregate_id uuid,
    payload jsonb NOT NULL,
    occurred_at timestamptz NOT NULL DEFAULT now(),
    published_at timestamptz
);

CREATE TABLE idempotency_records (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid REFERENCES organizations(id),
    idempotency_key varchar(180) NOT NULL,
    request_hash varchar(128) NOT NULL,
    response_code integer,
    response_body jsonb,
    expires_at timestamptz NOT NULL,
    UNIQUE (organization_id, idempotency_key)
);

INSERT INTO organizations (id, legal_name, display_name, country_code, timezone, status)
VALUES ('01900000-0000-7000-8000-000000000001', 'ROOTOPATHY Care Network Private Limited', 'ROOTOPATHY Care Network', 'IN', 'Asia/Kolkata', 'draft');

INSERT INTO facilities (id, organization_id, name, code, status)
VALUES ('01900000-0000-7000-8000-000000000101', '01900000-0000-7000-8000-000000000001', 'ROOTOPATHY Greater Noida', 'GNO-01', 'draft');

ALTER TABLE facilities ENABLE ROW LEVEL SECURITY;
ALTER TABLE facilities FORCE ROW LEVEL SECURITY;
CREATE POLICY facilities_tenant_policy ON facilities
    USING (organization_id = nullif(current_setting('careos.organization_id', true), '')::uuid)
    WITH CHECK (organization_id = nullif(current_setting('careos.organization_id', true), '')::uuid);

ALTER TABLE audit_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit_events FORCE ROW LEVEL SECURITY;
CREATE POLICY audit_events_tenant_policy ON audit_events
    USING (organization_id = nullif(current_setting('careos.organization_id', true), '')::uuid)
    WITH CHECK (organization_id = nullif(current_setting('careos.organization_id', true), '')::uuid);

ALTER TABLE organization_memberships ENABLE ROW LEVEL SECURITY;
ALTER TABLE organization_memberships FORCE ROW LEVEL SECURITY;
CREATE POLICY organization_memberships_tenant_policy ON organization_memberships
    USING (organization_id = nullif(current_setting('careos.organization_id', true), '')::uuid)
    WITH CHECK (organization_id = nullif(current_setting('careos.organization_id', true), '')::uuid);

ALTER TABLE outbox_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE outbox_events FORCE ROW LEVEL SECURITY;
CREATE POLICY outbox_events_tenant_policy ON outbox_events
    USING (organization_id = nullif(current_setting('careos.organization_id', true), '')::uuid)
    WITH CHECK (organization_id = nullif(current_setting('careos.organization_id', true), '')::uuid);

ALTER TABLE idempotency_records ENABLE ROW LEVEL SECURITY;
ALTER TABLE idempotency_records FORCE ROW LEVEL SECURITY;
CREATE POLICY idempotency_records_tenant_policy ON idempotency_records
    USING (organization_id = nullif(current_setting('careos.organization_id', true), '')::uuid)
    WITH CHECK (organization_id = nullif(current_setting('careos.organization_id', true), '')::uuid);
