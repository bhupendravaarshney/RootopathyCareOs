DO $$
BEGIN
    IF current_setting('server_version_num')::integer < 180000 THEN
        RAISE EXCEPTION 'CareOS UUIDv7 defaults require PostgreSQL 18 or newer';
    END IF;
END;
$$;

ALTER TABLE users ALTER COLUMN id SET DEFAULT uuidv7();
ALTER TABLE organizations ALTER COLUMN id SET DEFAULT uuidv7();
ALTER TABLE organization_memberships ALTER COLUMN id SET DEFAULT uuidv7();
ALTER TABLE facilities ALTER COLUMN id SET DEFAULT uuidv7();
ALTER TABLE audit_events ALTER COLUMN id SET DEFAULT uuidv7();
ALTER TABLE outbox_events ALTER COLUMN id SET DEFAULT uuidv7();
ALTER TABLE idempotency_records ALTER COLUMN id SET DEFAULT uuidv7();
ALTER TABLE password_reset_tokens ALTER COLUMN id SET DEFAULT uuidv7();
ALTER TABLE mfa_methods ALTER COLUMN id SET DEFAULT uuidv7();
ALTER TABLE recovery_codes ALTER COLUMN id SET DEFAULT uuidv7();
ALTER TABLE invitations ALTER COLUMN id SET DEFAULT uuidv7();
ALTER TABLE authentication_events ALTER COLUMN id SET DEFAULT uuidv7();

COMMENT ON COLUMN users.id IS
    'UUIDv7 for new database-generated records; pre-V13 identifiers remain valid history.';
COMMENT ON COLUMN organizations.id IS
    'UUIDv7 for new database-generated records; pre-V13 identifiers remain valid history.';
COMMENT ON COLUMN organization_memberships.id IS
    'UUIDv7 for new database-generated records; pre-V13 identifiers remain valid history.';
COMMENT ON COLUMN facilities.id IS
    'UUIDv7 for new database-generated records; pre-V13 identifiers remain valid history.';
COMMENT ON COLUMN audit_events.id IS
    'UUIDv7 for new database-generated records; pre-V13 identifiers remain valid history.';
COMMENT ON COLUMN outbox_events.id IS
    'UUIDv7 for new database-generated records; pre-V13 identifiers remain valid history.';
COMMENT ON COLUMN idempotency_records.id IS
    'UUIDv7 for new database-generated records; pre-V13 identifiers remain valid history.';
COMMENT ON COLUMN password_reset_tokens.id IS
    'UUIDv7 for new database-generated records; pre-V13 identifiers remain valid history.';
COMMENT ON COLUMN mfa_methods.id IS
    'UUIDv7 for new database-generated records; pre-V13 identifiers remain valid history.';
COMMENT ON COLUMN recovery_codes.id IS
    'UUIDv7 for new database-generated records; pre-V13 identifiers remain valid history.';
COMMENT ON COLUMN invitations.id IS
    'UUIDv7 for new database-generated records; pre-V13 identifiers remain valid history.';
COMMENT ON COLUMN authentication_events.id IS
    'UUIDv7 for new database-generated records; pre-V13 identifiers remain valid history.';
