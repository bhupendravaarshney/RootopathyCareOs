CREATE TABLE organization_unit_parent_history (
 id uuid PRIMARY KEY DEFAULT uuidv7(), organization_id uuid NOT NULL REFERENCES organizations(id),
 unit_id uuid NOT NULL, previous_parent_id uuid, parent_id uuid, effective_from timestamptz NOT NULL,
 lock_version bigint NOT NULL, changed_at timestamptz NOT NULL DEFAULT clock_timestamp(), changed_by uuid NOT NULL REFERENCES users(id),
 FOREIGN KEY(organization_id,unit_id) REFERENCES organization_units(organization_id,id),
 FOREIGN KEY(organization_id,previous_parent_id) REFERENCES organization_units(organization_id,id),
 FOREIGN KEY(organization_id,parent_id) REFERENCES organization_units(organization_id,id),
 UNIQUE(organization_id,unit_id,lock_version), CHECK(previous_parent_id IS DISTINCT FROM parent_id),
 CHECK(isfinite(effective_from) AND isfinite(changed_at) AND lock_version>0)
);
ALTER TABLE organization_unit_parent_history ENABLE ROW LEVEL SECURITY;
ALTER TABLE organization_unit_parent_history FORCE ROW LEVEL SECURITY;
CREATE POLICY organization_unit_parent_history_tenant_policy ON organization_unit_parent_history
 USING(organization_id=nullif(current_setting('app.current_organization_id',true),'')::uuid)
 WITH CHECK(organization_id=nullif(current_setting('app.current_organization_id',true),'')::uuid);
CREATE FUNCTION careos_reject_organization_unit_parent_history_change() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'organization unit parent history is append-only' USING ERRCODE='55000'; END $$;
CREATE TRIGGER organization_unit_parent_history_no_change BEFORE UPDATE OR DELETE ON organization_unit_parent_history FOR EACH ROW EXECUTE FUNCTION careos_reject_organization_unit_parent_history_change();

INSERT INTO audit_event_definitions(event_name,schema_version,display_name,description,subject_type,reason_required,required_payload_keys,allowed_payload_keys,payload_schema,status,registry_version)
VALUES ('network.unit.reparented',1,'Organization unit reparented','An organization unit parent changed with immutable history.','organization_unit',true,
 ARRAY['effectiveFrom','lockVersion','parentId','previousParentId','recordId'],ARRAY['effectiveFrom','lockVersion','parentId','previousParentId','recordId'],'{"type":"object"}'::jsonb,'active','m1-candidate-1');
INSERT INTO outbox_event_definitions(event_name,schema_version,description,aggregate_type,required_payload_keys,allowed_payload_keys,payload_schema,status,registry_version)
VALUES ('network.unit.reparented',1,'An organization unit parent changed with immutable history.','organization_unit',
 ARRAY['effectiveFrom','lockVersion','parentId','previousParentId','recordId'],ARRAY['effectiveFrom','lockVersion','parentId','previousParentId','recordId'],'{"type":"object"}'::jsonb,'active','m1-candidate-1');
INSERT INTO authorization_operation_events(operation_key,event_kind,event_name,schema_version,status,registry_version)
VALUES ('network.facility.manage','audit','network.unit.reparented',1,'active','m1-candidate-1'),('network.facility.manage','outbox','network.unit.reparented',1,'active','m1-candidate-1');
REVOKE ALL ON organization_unit_parent_history FROM PUBLIC;
GRANT SELECT,INSERT ON organization_unit_parent_history TO "${applicationRole}";
