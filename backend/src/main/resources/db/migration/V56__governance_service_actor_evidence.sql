ALTER TABLE audit_events
    DROP CONSTRAINT audit_events_actor_user_id_fkey,
    ADD COLUMN actor_kind varchar(16) NOT NULL DEFAULT 'user',
    ADD CONSTRAINT audit_events_actor_kind_check CHECK (actor_kind IN ('user','service'));

ALTER TABLE outbox_events
    DROP CONSTRAINT outbox_events_actor_user_id_fkey,
    ADD COLUMN actor_kind varchar(16) NOT NULL DEFAULT 'user',
    ADD CONSTRAINT outbox_events_actor_kind_check CHECK (actor_kind IN ('user','service'));

CREATE FUNCTION careos_project_governance_actor_kind()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    configured_kind text := coalesce(
        nullif(current_setting('app.current_actor_kind',true),''),'user');
BEGIN
    NEW.actor_kind := configured_kind;
    IF configured_kind='user' AND NOT EXISTS (
        SELECT 1 FROM users WHERE id=NEW.actor_user_id
    ) THEN
        RAISE EXCEPTION 'governance user actor is unavailable' USING ERRCODE='23503';
    ELSIF configured_kind='service' AND NOT EXISTS (
        SELECT 1 FROM service_identities
        WHERE id=NEW.actor_user_id AND organization_id=NEW.organization_id
    ) THEN
        RAISE EXCEPTION 'governance service actor is unavailable' USING ERRCODE='23503';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER audit_events_00_project_actor_kind
    BEFORE INSERT ON audit_events
    FOR EACH ROW EXECUTE FUNCTION careos_project_governance_actor_kind();

CREATE TRIGGER outbox_events_00_project_actor_kind
    BEFORE INSERT ON outbox_events
    FOR EACH ROW EXECUTE FUNCTION careos_project_governance_actor_kind();

COMMENT ON COLUMN audit_events.actor_user_id IS
    'Historical column name retained for compatibility; identifies a user or service according to actor_kind.';
COMMENT ON COLUMN outbox_events.actor_user_id IS
    'Historical column name retained for compatibility; identifies a user or service according to actor_kind.';
