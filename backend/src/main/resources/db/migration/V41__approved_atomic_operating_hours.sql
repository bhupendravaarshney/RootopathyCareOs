CREATE TABLE operating_hours_batches (
 id uuid PRIMARY KEY DEFAULT uuidv7(), organization_id uuid NOT NULL REFERENCES organizations(id),
 target_type varchar(24) NOT NULL, target_id uuid NOT NULL, timezone varchar(80) NOT NULL,
 effective_from timestamptz NOT NULL, effective_to timestamptz, status varchar(24) NOT NULL DEFAULT 'draft',
 lock_version bigint NOT NULL DEFAULT 0, created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 created_by uuid NOT NULL REFERENCES users(id), updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 updated_by uuid NOT NULL REFERENCES users(id), UNIQUE(organization_id,id),
 CHECK(target_type IN ('facility','location')), CHECK(timezone=btrim(timezone) AND char_length(timezone) BETWEEN 1 AND 80),
 CHECK(status IN ('draft','scheduled','active','superseded','cancelled')),
 CHECK(isfinite(effective_from) AND (effective_to IS NULL OR effective_to>effective_from)), CHECK(lock_version>=0)
);
CREATE UNIQUE INDEX operating_hours_one_current_target_uq ON operating_hours_batches(organization_id,target_type,target_id)
 WHERE status='active';
CREATE TABLE operating_hours_intervals (
 id uuid PRIMARY KEY DEFAULT uuidv7(), organization_id uuid NOT NULL, batch_id uuid NOT NULL,
 weekday smallint NOT NULL, start_minute smallint NOT NULL, end_minute smallint NOT NULL,
 ends_next_day boolean NOT NULL DEFAULT false,
 FOREIGN KEY(organization_id,batch_id) REFERENCES operating_hours_batches(organization_id,id) ON DELETE CASCADE,
 CHECK(weekday BETWEEN 1 AND 7), CHECK(start_minute BETWEEN 0 AND 1439), CHECK(end_minute BETWEEN 0 AND 1439),
 CHECK((NOT ends_next_day AND end_minute>start_minute) OR (ends_next_day AND end_minute<=start_minute))
);
CREATE TABLE operating_hours_exceptions (
 id uuid PRIMARY KEY DEFAULT uuidv7(), organization_id uuid NOT NULL, batch_id uuid NOT NULL,
 local_date date NOT NULL, closed boolean NOT NULL, label varchar(120) NOT NULL, reason_code varchar(80) NOT NULL,
 FOREIGN KEY(organization_id,batch_id) REFERENCES operating_hours_batches(organization_id,id) ON DELETE CASCADE,
 UNIQUE(organization_id,id), UNIQUE(organization_id,batch_id,local_date),
 CHECK(label=btrim(label) AND char_length(label) BETWEEN 1 AND 120 AND label !~ '[[:cntrl:]<>]'),
 CHECK(reason_code ~ '^[a-z][a-z0-9_]*(\.[a-z0-9_]+)+$')
);
CREATE TABLE operating_hours_exception_intervals (
 id uuid PRIMARY KEY DEFAULT uuidv7(), organization_id uuid NOT NULL, exception_id uuid NOT NULL,
 start_minute smallint NOT NULL, end_minute smallint NOT NULL, ends_next_day boolean NOT NULL DEFAULT false,
 FOREIGN KEY(organization_id,exception_id) REFERENCES operating_hours_exceptions(organization_id,id) ON DELETE CASCADE,
 CHECK(start_minute BETWEEN 0 AND 1439), CHECK(end_minute BETWEEN 0 AND 1439),
 CHECK((NOT ends_next_day AND end_minute>start_minute) OR (ends_next_day AND end_minute<=start_minute))
);
ALTER TABLE operating_hours_batches ENABLE ROW LEVEL SECURITY; ALTER TABLE operating_hours_batches FORCE ROW LEVEL SECURITY;
ALTER TABLE operating_hours_intervals ENABLE ROW LEVEL SECURITY; ALTER TABLE operating_hours_intervals FORCE ROW LEVEL SECURITY;
ALTER TABLE operating_hours_exceptions ENABLE ROW LEVEL SECURITY; ALTER TABLE operating_hours_exceptions FORCE ROW LEVEL SECURITY;
ALTER TABLE operating_hours_exception_intervals ENABLE ROW LEVEL SECURITY; ALTER TABLE operating_hours_exception_intervals FORCE ROW LEVEL SECURITY;
CREATE POLICY operating_hours_batches_tenant_policy ON operating_hours_batches USING(organization_id=nullif(current_setting('app.current_organization_id',true),'')::uuid) WITH CHECK(organization_id=nullif(current_setting('app.current_organization_id',true),'')::uuid);
CREATE POLICY operating_hours_intervals_tenant_policy ON operating_hours_intervals USING(organization_id=nullif(current_setting('app.current_organization_id',true),'')::uuid) WITH CHECK(organization_id=nullif(current_setting('app.current_organization_id',true),'')::uuid);
CREATE POLICY operating_hours_exceptions_tenant_policy ON operating_hours_exceptions USING(organization_id=nullif(current_setting('app.current_organization_id',true),'')::uuid) WITH CHECK(organization_id=nullif(current_setting('app.current_organization_id',true),'')::uuid);
CREATE POLICY operating_hours_exception_intervals_tenant_policy ON operating_hours_exception_intervals USING(organization_id=nullif(current_setting('app.current_organization_id',true),'')::uuid) WITH CHECK(organization_id=nullif(current_setting('app.current_organization_id',true),'')::uuid);

CREATE FUNCTION careos_validate_hours_batch() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE configured_organization uuid:=nullif(current_setting('app.current_organization_id',true),'')::uuid;
 configured_actor uuid:=nullif(current_setting('app.current_actor_id',true),'')::uuid;
 configured_operation text:=nullif(current_setting('app.current_operation_key',true),''); target_found boolean;
BEGIN
 IF current_user<>'${applicationRole}' THEN RETURN NEW; END IF;
 IF NEW.organization_id IS DISTINCT FROM configured_organization OR configured_actor IS NULL
  OR configured_operation NOT IN ('network.hours.manage','configuration.activate') OR NEW.updated_by IS DISTINCT FROM configured_actor
  OR char_length(coalesce(nullif(current_setting('app.current_authorization_reason',true),''),'')) NOT BETWEEN 10 AND 500
  THEN RAISE EXCEPTION 'invalid operating-hours governance context' USING ERRCODE='42501'; END IF;
 IF TG_OP='INSERT' AND (NEW.created_by IS DISTINCT FROM configured_actor OR NEW.status<>'draft') THEN RAISE EXCEPTION 'operating-hours batches begin as drafts' USING ERRCODE='23514'; END IF;
 IF TG_OP='UPDATE' AND (NEW.id<>OLD.id OR NEW.organization_id<>OLD.organization_id OR NEW.target_type<>OLD.target_type OR NEW.target_id<>OLD.target_id OR NEW.created_at<>OLD.created_at OR NEW.created_by<>OLD.created_by OR NEW.lock_version<>OLD.lock_version+1) THEN RAISE EXCEPTION 'invalid operating-hours revision' USING ERRCODE='23514'; END IF;
 IF TG_OP='UPDATE' AND NOT ((OLD.status='draft' AND NEW.status IN ('scheduled','active')) OR (OLD.status='scheduled' AND NEW.status IN ('active','cancelled')) OR (OLD.status='active' AND NEW.status='superseded')) THEN RAISE EXCEPTION 'invalid operating-hours transition' USING ERRCODE='23514'; END IF;
 SELECT CASE WHEN NEW.target_type='facility' THEN EXISTS(SELECT 1 FROM facilities f WHERE f.organization_id=NEW.organization_id AND f.id=NEW.target_id)
             ELSE EXISTS(SELECT 1 FROM service_locations l WHERE l.organization_id=NEW.organization_id AND l.id=NEW.target_id) END INTO target_found;
 IF NOT target_found THEN RAISE EXCEPTION 'operating-hours target is unavailable' USING ERRCODE='23514'; END IF;
 PERFORM clock_timestamp() AT TIME ZONE NEW.timezone;
 RETURN NEW;
END $$;
CREATE TRIGGER operating_hours_batches_validate BEFORE INSERT OR UPDATE ON operating_hours_batches FOR EACH ROW EXECUTE FUNCTION careos_validate_hours_batch();

CREATE FUNCTION careos_reject_hours_overlap() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE new_start integer; new_end integer;
BEGIN
 new_start:=(NEW.weekday-1)*1440+NEW.start_minute; new_end:=(NEW.weekday-1)*1440+NEW.end_minute+CASE WHEN NEW.ends_next_day THEN 1440 ELSE 0 END;
 IF EXISTS(SELECT 1 FROM operating_hours_intervals i WHERE i.organization_id=NEW.organization_id AND i.batch_id=NEW.batch_id AND i.id<>NEW.id
   AND (int4range(new_start,new_end,'[)') && int4range((i.weekday-1)*1440+i.start_minute,(i.weekday-1)*1440+i.end_minute+CASE WHEN i.ends_next_day THEN 1440 ELSE 0 END,'[)')
     OR int4range(new_start-10080,new_end-10080,'[)') && int4range((i.weekday-1)*1440+i.start_minute,(i.weekday-1)*1440+i.end_minute+CASE WHEN i.ends_next_day THEN 1440 ELSE 0 END,'[)')
     OR int4range(new_start+10080,new_end+10080,'[)') && int4range((i.weekday-1)*1440+i.start_minute,(i.weekday-1)*1440+i.end_minute+CASE WHEN i.ends_next_day THEN 1440 ELSE 0 END,'[)')))
 THEN RAISE EXCEPTION 'operating-hours intervals overlap' USING ERRCODE='23P01'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER operating_hours_intervals_no_overlap BEFORE INSERT OR UPDATE ON operating_hours_intervals FOR EACH ROW EXECUTE FUNCTION careos_reject_hours_overlap();

INSERT INTO audit_event_definitions(event_name,schema_version,display_name,description,subject_type,reason_required,required_payload_keys,allowed_payload_keys,payload_schema,status,registry_version)
SELECT name,1,'Operating hours changed','An atomic operating-hours batch changed.','operating_hours_batch',true,
 ARRAY['batchId','effectiveFrom','fromState','targetId','targetType','toState'],ARRAY['batchId','effectiveFrom','fromState','targetId','targetType','toState'],'{"type":"object"}'::jsonb,'active','m1-candidate-1'
FROM unnest(ARRAY['network.hours.scheduled','network.hours.activated','network.hours.superseded','network.hours.cancelled']) name;
INSERT INTO outbox_event_definitions(event_name,schema_version,description,aggregate_type,required_payload_keys,allowed_payload_keys,payload_schema,status,registry_version)
SELECT name,1,'An atomic operating-hours batch changed.','operating_hours_batch',ARRAY['batchId','effectiveFrom','fromState','targetId','targetType','toState'],ARRAY['batchId','effectiveFrom','fromState','targetId','targetType','toState'],'{"type":"object"}'::jsonb,'active','m1-candidate-1'
FROM unnest(ARRAY['network.hours.scheduled','network.hours.activated','network.hours.superseded','network.hours.cancelled']) name;
INSERT INTO authorization_operations(operation_key,permission_key,display_name,description,mutation,denial_mode,reason_required,recent_authentication_required,recent_authentication_max_age_seconds,maximum_future_skew_seconds,maker_checker_required,status,registry_version,mfa_required)
VALUES ('network.hours.read','network.hours.read','Read operating hours','Read active and scheduled operating-hours projections.',false,'hidden',false,false,NULL,NULL,false,'active','m1-candidate-1',false),
 ('network.hours.manage','network.hours.manage','Manage operating hours','Atomically manage operating-hours batches and exceptions.',true,'explicit',true,false,NULL,NULL,false,'active','m1-candidate-1',false);
INSERT INTO authorization_operation_events(operation_key,event_kind,event_name,schema_version,status,registry_version)
SELECT 'network.hours.manage',kind,name,1,'active','m1-candidate-1' FROM unnest(ARRAY['audit','outbox']) kind CROSS JOIN unnest(ARRAY['network.hours.scheduled','network.hours.activated','network.hours.superseded','network.hours.cancelled']) name;
REVOKE ALL ON operating_hours_batches,operating_hours_intervals,operating_hours_exceptions,operating_hours_exception_intervals FROM PUBLIC;
GRANT SELECT,INSERT,UPDATE ON operating_hours_batches TO "${applicationRole}";
GRANT SELECT,INSERT,DELETE ON operating_hours_intervals,operating_hours_exceptions,operating_hours_exception_intervals TO "${applicationRole}";
