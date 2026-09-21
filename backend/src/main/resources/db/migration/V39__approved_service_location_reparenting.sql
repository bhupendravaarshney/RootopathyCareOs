CREATE TABLE service_location_parent_history (
 id uuid PRIMARY KEY DEFAULT uuidv7(),
 organization_id uuid NOT NULL REFERENCES organizations(id),
 location_id uuid NOT NULL,
 previous_parent_id uuid,
 parent_id uuid,
 effective_from timestamptz NOT NULL,
 lock_version bigint NOT NULL,
 changed_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 changed_by uuid NOT NULL REFERENCES users(id),
 FOREIGN KEY (organization_id,location_id) REFERENCES service_locations(organization_id,id),
 FOREIGN KEY (organization_id,previous_parent_id) REFERENCES service_locations(organization_id,id),
 FOREIGN KEY (organization_id,parent_id) REFERENCES service_locations(organization_id,id),
 UNIQUE (organization_id,location_id,lock_version),
 CHECK (previous_parent_id IS DISTINCT FROM parent_id),
 CHECK (isfinite(effective_from) AND isfinite(changed_at) AND lock_version>0)
);

ALTER TABLE service_location_parent_history ENABLE ROW LEVEL SECURITY;
ALTER TABLE service_location_parent_history FORCE ROW LEVEL SECURITY;
CREATE POLICY service_location_parent_history_tenant_policy ON service_location_parent_history
 USING (organization_id=nullif(current_setting('app.current_organization_id',true),'')::uuid)
 WITH CHECK (organization_id=nullif(current_setting('app.current_organization_id',true),'')::uuid);

CREATE FUNCTION careos_reject_service_location_parent_history_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 RAISE EXCEPTION 'service location parent history is append-only' USING ERRCODE='55000';
END $$;
CREATE TRIGGER service_location_parent_history_no_change BEFORE UPDATE OR DELETE ON service_location_parent_history
 FOR EACH ROW EXECUTE FUNCTION careos_reject_service_location_parent_history_change();

CREATE FUNCTION careos_require_service_location_parent_history() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.parent_id IS DISTINCT FROM OLD.parent_id AND NOT EXISTS (
  SELECT 1 FROM service_location_parent_history history
  WHERE history.organization_id=NEW.organization_id AND history.location_id=NEW.id
    AND history.previous_parent_id IS NOT DISTINCT FROM OLD.parent_id
    AND history.parent_id IS NOT DISTINCT FROM NEW.parent_id
    AND history.lock_version=NEW.lock_version
    AND history.changed_by=NEW.updated_by
 ) THEN
  RAISE EXCEPTION 'service location parent change requires immutable history' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER service_locations_require_parent_history BEFORE UPDATE ON service_locations
 FOR EACH ROW EXECUTE FUNCTION careos_require_service_location_parent_history();

INSERT INTO audit_event_definitions(event_name,schema_version,display_name,description,subject_type,reason_required,required_payload_keys,allowed_payload_keys,payload_schema,status,registry_version)
VALUES ('network.location.reparented',1,'Service location reparented','A service location parent changed with immutable history.','service_location',true,
 ARRAY['effectiveFrom','lockVersion','parentId','previousParentId','recordId'],ARRAY['effectiveFrom','lockVersion','parentId','previousParentId','recordId'],'{"type":"object"}'::jsonb,'active','m1-candidate-1');
INSERT INTO outbox_event_definitions(event_name,schema_version,description,aggregate_type,required_payload_keys,allowed_payload_keys,payload_schema,status,registry_version)
VALUES ('network.location.reparented',1,'A service location parent changed with immutable history.','service_location',
 ARRAY['effectiveFrom','lockVersion','parentId','previousParentId','recordId'],ARRAY['effectiveFrom','lockVersion','parentId','previousParentId','recordId'],'{"type":"object"}'::jsonb,'active','m1-candidate-1');
INSERT INTO authorization_operation_events(operation_key,event_kind,event_name,schema_version,status,registry_version)
VALUES ('network.structure.manage','audit','network.location.reparented',1,'active','m1-candidate-1'),
       ('network.structure.manage','outbox','network.location.reparented',1,'active','m1-candidate-1');

REVOKE ALL ON service_location_parent_history FROM PUBLIC;
GRANT SELECT,INSERT ON service_location_parent_history TO "${applicationRole}";
