CREATE TABLE service_locations (
 id uuid PRIMARY KEY DEFAULT uuidv7(),
 organization_id uuid NOT NULL REFERENCES organizations(id),
 facility_id uuid NOT NULL,
 unit_id uuid,
 parent_id uuid,
 address_id uuid,
 location_code varchar(32) NOT NULL,
 location_type varchar(24) NOT NULL,
 name varchar(120) NOT NULL,
 virtual_service_type varchar(80),
 capacity integer,
 accessibility_notes varchar(500),
 effective_from timestamptz NOT NULL,
 effective_to timestamptz,
 status varchar(24) NOT NULL DEFAULT 'draft',
 lock_version bigint NOT NULL DEFAULT 0,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 created_by uuid NOT NULL REFERENCES users(id),
 updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 updated_by uuid NOT NULL REFERENCES users(id),
 UNIQUE (organization_id,id),
 UNIQUE (organization_id,facility_id,location_code),
 FOREIGN KEY (organization_id,facility_id) REFERENCES facilities(organization_id,id),
 FOREIGN KEY (organization_id,unit_id) REFERENCES organization_units(organization_id,id),
 FOREIGN KEY (organization_id,parent_id) REFERENCES service_locations(organization_id,id),
 FOREIGN KEY (organization_id,address_id) REFERENCES organization_addresses(organization_id,id),
 CHECK (location_code ~ '^[A-Z0-9][A-Z0-9_-]{1,31}$'),
 CHECK (location_type IN ('physical','virtual')),
 CHECK (name=btrim(name) AND name=normalize(name,NFC) AND char_length(name) BETWEEN 2 AND 120 AND name !~ '[[:cntrl:]<>]'),
 CHECK ((location_type='physical' AND address_id IS NOT NULL AND virtual_service_type IS NULL)
     OR (location_type='virtual' AND address_id IS NULL AND virtual_service_type IS NOT NULL)),
 CHECK (virtual_service_type IS NULL OR (virtual_service_type=btrim(virtual_service_type)
     AND virtual_service_type=normalize(virtual_service_type,NFC)
     AND char_length(virtual_service_type) BETWEEN 2 AND 80
     AND virtual_service_type !~ '[[:cntrl:]<>]')),
 CHECK (capacity IS NULL OR capacity BETWEEN 1 AND 100000),
 CHECK (accessibility_notes IS NULL OR (accessibility_notes=btrim(accessibility_notes)
     AND accessibility_notes=normalize(accessibility_notes,NFC)
     AND char_length(accessibility_notes) BETWEEN 1 AND 500
     AND accessibility_notes !~ '[[:cntrl:]<>]')),
 CHECK (status IN ('draft','active','suspended','closed')),
 CHECK (isfinite(effective_from) AND (effective_to IS NULL OR (isfinite(effective_to) AND effective_to>effective_from))),
 CHECK (parent_id IS NULL OR parent_id<>id),
 CHECK (lock_version>=0),
 CHECK (isfinite(created_at) AND isfinite(updated_at) AND updated_at>=created_at)
);

CREATE INDEX service_locations_directory_idx
 ON service_locations(organization_id,facility_id,status,name,id);

ALTER TABLE service_locations ENABLE ROW LEVEL SECURITY;
ALTER TABLE service_locations FORCE ROW LEVEL SECURITY;
CREATE POLICY service_locations_tenant_policy ON service_locations
 USING (organization_id=nullif(current_setting('app.current_organization_id',true),'')::uuid)
 WITH CHECK (organization_id=nullif(current_setting('app.current_organization_id',true),'')::uuid);

CREATE FUNCTION careos_validate_service_location_write() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE configured_organization uuid:=nullif(current_setting('app.current_organization_id',true),'')::uuid;
 configured_actor uuid:=nullif(current_setting('app.current_actor_id',true),'')::uuid;
 configured_operation text:=nullif(current_setting('app.current_operation_key',true),'');
 configured_reason text:=nullif(current_setting('app.current_authorization_reason',true),'');
 referenced_facility uuid; hierarchy_depth integer;
BEGIN
 IF current_user<>'${applicationRole}' THEN RETURN NEW; END IF;
 IF NEW.organization_id IS DISTINCT FROM configured_organization OR configured_actor IS NULL
  OR configured_operation IS DISTINCT FROM 'network.structure.manage'
  OR char_length(coalesce(configured_reason,'')) NOT BETWEEN 10 AND 500
  OR NEW.updated_by IS DISTINCT FROM configured_actor THEN
   RAISE EXCEPTION 'invalid service location governance context' USING ERRCODE='42501';
 END IF;
 IF TG_OP='INSERT' AND (NEW.status<>'draft' OR NEW.created_by IS DISTINCT FROM configured_actor) THEN
   RAISE EXCEPTION 'invalid service location draft evidence' USING ERRCODE='23514';
 END IF;
 IF TG_OP='UPDATE' AND (OLD.status<>'draft' OR NEW.status<>'draft' OR NEW.id<>OLD.id
  OR NEW.organization_id<>OLD.organization_id OR NEW.facility_id<>OLD.facility_id
  OR NEW.created_at<>OLD.created_at OR NEW.created_by<>OLD.created_by
  OR NEW.lock_version<>OLD.lock_version+1) THEN
   RAISE EXCEPTION 'invalid service location revision' USING ERRCODE='23514';
 END IF;
 IF NEW.unit_id IS NOT NULL THEN
  SELECT facility_id INTO referenced_facility FROM organization_units
   WHERE organization_id=NEW.organization_id AND id=NEW.unit_id;
  IF referenced_facility IS NULL OR referenced_facility<>NEW.facility_id THEN
   RAISE EXCEPTION 'service location unit must belong to the same facility' USING ERRCODE='23514';
  END IF;
 END IF;
 IF NEW.parent_id IS NOT NULL THEN
  SELECT facility_id INTO referenced_facility FROM service_locations
   WHERE organization_id=NEW.organization_id AND id=NEW.parent_id;
  IF referenced_facility IS NULL OR referenced_facility<>NEW.facility_id THEN
   RAISE EXCEPTION 'service location parent must belong to the same facility' USING ERRCODE='23514';
  END IF;
  IF EXISTS(WITH RECURSIVE ancestors(id,parent_id) AS (
    SELECT id,parent_id FROM service_locations WHERE organization_id=NEW.organization_id AND id=NEW.parent_id
    UNION ALL SELECT p.id,p.parent_id FROM service_locations p JOIN ancestors a ON p.id=a.parent_id
     WHERE p.organization_id=NEW.organization_id)
    SELECT 1 FROM ancestors WHERE id=NEW.id) THEN
   RAISE EXCEPTION 'service location hierarchy cycle' USING ERRCODE='23514';
  END IF;
  WITH RECURSIVE ancestors(id,parent_id,depth) AS (
   SELECT id,parent_id,1 FROM service_locations WHERE organization_id=NEW.organization_id AND id=NEW.parent_id
   UNION ALL SELECT p.id,p.parent_id,a.depth+1 FROM service_locations p JOIN ancestors a ON p.id=a.parent_id
    WHERE p.organization_id=NEW.organization_id AND a.depth<9)
  SELECT coalesce(max(depth),0) INTO hierarchy_depth FROM ancestors;
  IF hierarchy_depth>=8 THEN
   RAISE EXCEPTION 'service location hierarchy depth exceeds 8' USING ERRCODE='23514';
  END IF;
 END IF;
 RETURN NEW;
END $$;

CREATE TRIGGER service_locations_validate_write BEFORE INSERT OR UPDATE ON service_locations
 FOR EACH ROW EXECUTE FUNCTION careos_validate_service_location_write();

INSERT INTO audit_event_definitions(event_name,schema_version,display_name,description,subject_type,reason_required,required_payload_keys,allowed_payload_keys,payload_schema,status,registry_version)
VALUES ('network.location.changed',1,'Service location changed','A service location lifecycle record changed.','service_location',true,
 ARRAY['changeType','fromState','lockVersion','parentId','recordId','toState'],ARRAY['changeType','fromState','lockVersion','parentId','recordId','toState'],'{"type":"object"}'::jsonb,'active','m1-candidate-1');
INSERT INTO outbox_event_definitions(event_name,schema_version,description,aggregate_type,required_payload_keys,allowed_payload_keys,payload_schema,status,registry_version)
VALUES ('network.location.changed',1,'A service location lifecycle record changed.','service_location',
 ARRAY['changeType','fromState','lockVersion','parentId','recordId','toState'],ARRAY['changeType','fromState','lockVersion','parentId','recordId','toState'],'{"type":"object"}'::jsonb,'active','m1-candidate-1');
INSERT INTO authorization_operations
 (operation_key,permission_key,display_name,description,mutation,denial_mode,reason_required,
  recent_authentication_required,recent_authentication_max_age_seconds,maximum_future_skew_seconds,
  maker_checker_required,status,registry_version,mfa_required)
VALUES ('network.structure.read','network.structure.read','Read organization structure',
 'Read authorized department, unit, and location projections.',false,'hidden',false,false,NULL,NULL,false,
 'active','m1-candidate-1',false),
 ('network.structure.manage','network.structure.manage','Manage organization structure',
 'Create and update department, unit, and location drafts.',true,'explicit',true,false,NULL,NULL,false,
 'active','m1-candidate-1',false);
INSERT INTO authorization_operation_events(operation_key,event_kind,event_name,schema_version,status,registry_version)
VALUES ('network.structure.manage','audit','network.location.changed',1,'active','m1-candidate-1'),
       ('network.structure.manage','outbox','network.location.changed',1,'active','m1-candidate-1');

REVOKE ALL ON service_locations FROM PUBLIC;
GRANT SELECT,INSERT,UPDATE ON service_locations TO "${applicationRole}";
