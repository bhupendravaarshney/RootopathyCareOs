CREATE TABLE organization_units (
 id uuid PRIMARY KEY DEFAULT uuidv7(),
 organization_id uuid NOT NULL REFERENCES organizations(id),
 facility_id uuid NOT NULL,
 parent_id uuid,
 unit_code varchar(32) NOT NULL,
 unit_type varchar(24) NOT NULL,
 name varchar(120) NOT NULL,
 effective_from timestamptz NOT NULL,
 effective_to timestamptz,
 status varchar(24) NOT NULL DEFAULT 'draft',
 lock_version bigint NOT NULL DEFAULT 0,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 created_by uuid NOT NULL REFERENCES users(id),
 updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 updated_by uuid NOT NULL REFERENCES users(id),
 UNIQUE (organization_id,id), UNIQUE (organization_id,facility_id,unit_code),
 FOREIGN KEY (organization_id,facility_id) REFERENCES facilities(organization_id,id),
 FOREIGN KEY (organization_id,parent_id) REFERENCES organization_units(organization_id,id),
 CHECK (unit_code ~ '^[A-Z0-9][A-Z0-9_-]{1,31}$'),
 CHECK (unit_type IN ('department','unit')),
 CHECK (name=btrim(name) AND char_length(name) BETWEEN 2 AND 120 AND name !~ '[[:cntrl:]<>]'),
 CHECK (status IN ('draft','active','suspended','closed')),
 CHECK (isfinite(effective_from) AND (effective_to IS NULL OR (isfinite(effective_to) AND effective_to>effective_from))),
 CHECK (parent_id IS NULL OR parent_id<>id), CHECK (lock_version>=0),
 CHECK (isfinite(created_at) AND isfinite(updated_at) AND updated_at>=created_at)
);
CREATE INDEX organization_units_directory_idx ON organization_units(organization_id,facility_id,status,name,id);

ALTER TABLE organization_units ENABLE ROW LEVEL SECURITY;
ALTER TABLE organization_units FORCE ROW LEVEL SECURITY;
CREATE POLICY organization_units_tenant_policy ON organization_units
 USING (organization_id=nullif(current_setting('app.current_organization_id',true),'')::uuid)
 WITH CHECK (organization_id=nullif(current_setting('app.current_organization_id',true),'')::uuid);

CREATE FUNCTION careos_validate_organization_unit_write() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE configured_organization uuid:=nullif(current_setting('app.current_organization_id',true),'')::uuid;
 configured_actor uuid:=nullif(current_setting('app.current_actor_id',true),'')::uuid;
 configured_operation text:=nullif(current_setting('app.current_operation_key',true),'');
 configured_reason text:=nullif(current_setting('app.current_authorization_reason',true),'');
 parent_facility uuid; hierarchy_depth integer;
BEGIN
 IF current_user<>'${applicationRole}' THEN RETURN NEW; END IF;
 IF NEW.organization_id IS DISTINCT FROM configured_organization OR configured_actor IS NULL
  OR configured_operation IS DISTINCT FROM 'network.facility.manage'
  OR char_length(coalesce(configured_reason,'')) NOT BETWEEN 10 AND 500
  OR NEW.updated_by IS DISTINCT FROM configured_actor THEN
   RAISE EXCEPTION 'invalid organization unit governance context' USING ERRCODE='42501';
 END IF;
 IF TG_OP='INSERT' AND (NEW.status<>'draft' OR NEW.created_by IS DISTINCT FROM configured_actor) THEN
   RAISE EXCEPTION 'invalid organization unit draft evidence' USING ERRCODE='23514';
 END IF;
 IF TG_OP='UPDATE' AND (OLD.status<>'draft' OR NEW.status<>'draft' OR NEW.id<>OLD.id OR NEW.organization_id<>OLD.organization_id OR NEW.facility_id<>OLD.facility_id
  OR NEW.created_at<>OLD.created_at OR NEW.created_by<>OLD.created_by OR NEW.lock_version<>OLD.lock_version+1) THEN
   RAISE EXCEPTION 'invalid organization unit revision' USING ERRCODE='23514';
 END IF;
 IF NEW.parent_id IS NOT NULL THEN
  SELECT facility_id INTO parent_facility FROM organization_units WHERE organization_id=NEW.organization_id AND id=NEW.parent_id;
  IF parent_facility IS NULL OR parent_facility<>NEW.facility_id THEN RAISE EXCEPTION 'organization unit parent must belong to the same facility' USING ERRCODE='23514'; END IF;
  WITH RECURSIVE ancestors(id,parent_id,depth) AS (
   SELECT id,parent_id,1 FROM organization_units WHERE organization_id=NEW.organization_id AND id=NEW.parent_id
   UNION ALL SELECT p.id,p.parent_id,a.depth+1 FROM organization_units p JOIN ancestors a ON p.id=a.parent_id WHERE p.organization_id=NEW.organization_id AND a.depth<9)
  SELECT coalesce(max(depth),0) INTO hierarchy_depth FROM ancestors;
  IF EXISTS(WITH RECURSIVE ancestors(id,parent_id) AS (SELECT id,parent_id FROM organization_units WHERE organization_id=NEW.organization_id AND id=NEW.parent_id UNION ALL SELECT p.id,p.parent_id FROM organization_units p JOIN ancestors a ON p.id=a.parent_id WHERE p.organization_id=NEW.organization_id) SELECT 1 FROM ancestors WHERE id=NEW.id)
    THEN RAISE EXCEPTION 'organization unit hierarchy cycle' USING ERRCODE='23514'; END IF;
  IF hierarchy_depth>=8 THEN RAISE EXCEPTION 'organization unit hierarchy depth exceeds 8' USING ERRCODE='23514'; END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER organization_units_validate_write BEFORE INSERT OR UPDATE ON organization_units FOR EACH ROW EXECUTE FUNCTION careos_validate_organization_unit_write();

INSERT INTO audit_event_definitions(event_name,schema_version,display_name,description,subject_type,reason_required,required_payload_keys,allowed_payload_keys,payload_schema,status,registry_version)
VALUES ('network.unit.changed',1,'Organization unit changed','An organization unit lifecycle record changed.','organization_unit',true,
 ARRAY['changeType','fromState','lockVersion','parentId','recordId','toState'],ARRAY['changeType','fromState','lockVersion','parentId','recordId','toState'],'{"type":"object"}'::jsonb,'active','m1-candidate-1');
INSERT INTO outbox_event_definitions(event_name,schema_version,description,aggregate_type,required_payload_keys,allowed_payload_keys,payload_schema,status,registry_version)
VALUES ('network.unit.changed',1,'An organization unit lifecycle record changed.','organization_unit',
 ARRAY['changeType','fromState','lockVersion','parentId','recordId','toState'],ARRAY['changeType','fromState','lockVersion','parentId','recordId','toState'],'{"type":"object"}'::jsonb,'active','m1-candidate-1');
INSERT INTO authorization_operation_events(operation_key,event_kind,event_name,schema_version,status,registry_version)
VALUES ('network.facility.manage','audit','network.unit.changed',1,'active','m1-candidate-1'),('network.facility.manage','outbox','network.unit.changed',1,'active','m1-candidate-1');

REVOKE ALL ON organization_units FROM PUBLIC;
GRANT SELECT,INSERT,UPDATE ON organization_units TO "${applicationRole}";
