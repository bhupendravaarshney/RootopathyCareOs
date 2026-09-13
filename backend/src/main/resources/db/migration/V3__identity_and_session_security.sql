ALTER TABLE users
    ADD COLUMN security_version bigint NOT NULL DEFAULT 0;

ALTER TABLE users
    ADD CONSTRAINT users_status_check
    CHECK (status IN ('invited', 'active', 'suspended', 'disabled'));

CREATE UNIQUE INDEX users_normalized_email_uq ON users (lower(email));

CREATE TABLE password_credentials (
    user_id uuid PRIMARY KEY REFERENCES users(id),
    password_hash varchar(255) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    changed_at timestamptz NOT NULL DEFAULT now(),
    lock_version bigint NOT NULL DEFAULT 0
);

CREATE TABLE password_reset_tokens (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES users(id),
    token_hash char(64) NOT NULL UNIQUE,
    expires_at timestamptz NOT NULL,
    consumed_at timestamptz,
    revoked_at timestamptz,
    correlation_id varchar(128) NOT NULL,
    requested_from_hash char(64) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK (expires_at > created_at),
    CHECK (NOT (consumed_at IS NOT NULL AND revoked_at IS NOT NULL))
);

CREATE INDEX password_reset_tokens_user_active_idx
    ON password_reset_tokens (user_id, expires_at)
    WHERE consumed_at IS NULL AND revoked_at IS NULL;

CREATE TABLE mfa_methods (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES users(id),
    method_type varchar(24) NOT NULL DEFAULT 'totp',
    status varchar(24) NOT NULL DEFAULT 'pending',
    encrypted_secret text,
    label varchar(80),
    created_at timestamptz NOT NULL DEFAULT now(),
    verified_at timestamptz,
    revoked_at timestamptz,
    lock_version bigint NOT NULL DEFAULT 0,
    CHECK (method_type = 'totp'),
    CHECK (status IN ('pending', 'enabled', 'revoked')),
    CHECK ((status = 'revoked') OR encrypted_secret IS NOT NULL),
    CHECK ((status <> 'enabled') OR verified_at IS NOT NULL)
);

CREATE UNIQUE INDEX mfa_methods_one_current_totp_uq
    ON mfa_methods (user_id, method_type)
    WHERE status IN ('pending', 'enabled');

CREATE TABLE recovery_codes (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES users(id),
    mfa_method_id uuid NOT NULL REFERENCES mfa_methods(id),
    code_hash char(64) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    used_at timestamptz,
    revoked_at timestamptz,
    CHECK (NOT (used_at IS NOT NULL AND revoked_at IS NOT NULL)),
    UNIQUE (mfa_method_id, code_hash)
);

CREATE INDEX recovery_codes_unused_idx
    ON recovery_codes (user_id, mfa_method_id)
    WHERE used_at IS NULL AND revoked_at IS NULL;

CREATE TABLE invitations (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    email varchar(320) NOT NULL,
    display_name varchar(160) NOT NULL,
    role_key varchar(100) NOT NULL,
    token_hash char(64) NOT NULL UNIQUE,
    status varchar(24) NOT NULL DEFAULT 'pending',
    expires_at timestamptz NOT NULL,
    invited_by uuid NOT NULL REFERENCES users(id),
    accepted_by uuid REFERENCES users(id),
    accepted_at timestamptz,
    revoked_at timestamptz,
    correlation_id varchar(128) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    lock_version bigint NOT NULL DEFAULT 0,
    CHECK (status IN ('pending', 'accepted', 'revoked', 'expired')),
    CHECK (expires_at > created_at),
    CHECK ((status <> 'accepted') OR (accepted_by IS NOT NULL AND accepted_at IS NOT NULL)),
    CHECK ((status <> 'revoked') OR revoked_at IS NOT NULL)
);

CREATE INDEX invitations_organization_status_idx ON invitations (organization_id, status, expires_at);
CREATE INDEX invitations_normalized_email_idx ON invitations (organization_id, lower(email));

CREATE TABLE authentication_events (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid REFERENCES users(id),
    event_name varchar(120) NOT NULL,
    subject_hash char(64) NOT NULL,
    remote_address_hash char(64) NOT NULL,
    correlation_id varchar(128) NOT NULL,
    occurred_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX authentication_events_user_time_idx ON authentication_events (user_id, occurred_at DESC);
CREATE INDEX authentication_events_subject_time_idx ON authentication_events (subject_hash, occurred_at DESC);

CREATE FUNCTION careos_reject_authentication_event_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'authentication_events are append-only';
END;
$$;

CREATE TRIGGER authentication_events_append_only
    BEFORE UPDATE OR DELETE ON authentication_events
    FOR EACH ROW EXECUTE FUNCTION careos_reject_authentication_event_mutation();

CREATE TABLE user_sessions (
    session_id_hash char(64) PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES users(id),
    authenticated_at timestamptz NOT NULL,
    mfa_authenticated_at timestamptz,
    recent_authentication_at timestamptz NOT NULL,
    absolute_expires_at timestamptz NOT NULL,
    revoked_at timestamptz,
    revocation_reason varchar(120),
    correlation_id varchar(128) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK (absolute_expires_at > authenticated_at),
    CHECK ((revoked_at IS NULL) = (revocation_reason IS NULL))
);

CREATE INDEX user_sessions_user_active_idx
    ON user_sessions (user_id, absolute_expires_at)
    WHERE revoked_at IS NULL;

ALTER TABLE invitations ENABLE ROW LEVEL SECURITY;
ALTER TABLE invitations FORCE ROW LEVEL SECURITY;
CREATE POLICY invitations_tenant_policy ON invitations
    USING (organization_id = nullif(current_setting('app.current_organization_id', true), '')::uuid)
    WITH CHECK (organization_id = nullif(current_setting('app.current_organization_id', true), '')::uuid);

GRANT SELECT, INSERT, UPDATE ON password_credentials TO "${applicationRole}";
GRANT SELECT, INSERT, UPDATE ON password_reset_tokens TO "${applicationRole}";
GRANT SELECT, INSERT, UPDATE ON mfa_methods TO "${applicationRole}";
GRANT SELECT, INSERT, UPDATE ON recovery_codes TO "${applicationRole}";
GRANT SELECT, INSERT, UPDATE ON invitations TO "${applicationRole}";
GRANT SELECT, INSERT, UPDATE ON user_sessions TO "${applicationRole}";
GRANT INSERT ON authentication_events TO "${applicationRole}";
