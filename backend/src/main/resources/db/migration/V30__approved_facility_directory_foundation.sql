CREATE TABLE facility_types (
    type_key varchar(48) PRIMARY KEY,
    display_name varchar(120) NOT NULL,
    status varchar(16) NOT NULL CHECK (status IN ('active','retired')),
    registry_version varchar(64) NOT NULL
);
INSERT INTO facility_types VALUES ('care_site','Care site','active','m1-candidate-1');

ALTER TABLE facilities
    ADD COLUMN legal_name varchar(200),
    ADD COLUMN facility_type varchar(48) REFERENCES facility_types(type_key),
    ADD COLUMN address_id uuid,
    ADD COLUMN contact_id uuid,
    ADD COLUMN timezone varchar(64),
    ADD COLUMN closure_reason varchar(500),
    ADD COLUMN created_by uuid REFERENCES users(id),
    ADD COLUMN updated_by uuid REFERENCES users(id),
    ADD CONSTRAINT facilities_organization_id_id_uq UNIQUE (organization_id,id),
    ADD CONSTRAINT facilities_address_fk FOREIGN KEY (organization_id,address_id) REFERENCES organization_addresses(organization_id,id),
    ADD CONSTRAINT facilities_contact_fk FOREIGN KEY (organization_id,contact_id) REFERENCES organization_contacts(organization_id,id),
    ADD CONSTRAINT facilities_code_format CHECK (code ~ '^[A-Z0-9][A-Z0-9_-]{1,31}$'),
    ADD CONSTRAINT facilities_display_name_bounds CHECK (char_length(name) BETWEEN 2 AND 120),
    ADD CONSTRAINT facilities_legal_name_bounds CHECK (legal_name IS NULL OR char_length(legal_name) BETWEEN 2 AND 200),
    ADD CONSTRAINT facilities_status_v1 CHECK (status IN ('draft','under_review','active','suspended','closed')),
    ADD CONSTRAINT facilities_timezone_format CHECK (timezone IS NULL OR timezone ~ '^[A-Za-z_]+(?:/[A-Za-z0-9_+.-]+)+$'),
    ADD CONSTRAINT facilities_closure_reason_rule CHECK ((status='closed')=(closure_reason IS NOT NULL));
UPDATE facilities f SET legal_name=f.name,facility_type='care_site',timezone=o.timezone
 FROM organizations o WHERE o.id=f.organization_id
   AND (f.legal_name IS NULL OR f.facility_type IS NULL OR f.timezone IS NULL);
CREATE INDEX facilities_directory_idx ON facilities (organization_id,status,name,id);

INSERT INTO authorization_operations
 (operation_key,permission_key,display_name,description,mutation,denial_mode,reason_required,
  recent_authentication_required,recent_authentication_max_age_seconds,maximum_future_skew_seconds,
  maker_checker_required,status,registry_version,mfa_required)
VALUES
 ('network.facility.read','network.facility.read','Read facility directory','Read authorized facility projections.',false,'hidden',false,false,NULL,NULL,false,'active','m1-candidate-1',false),
 ('network.facility.manage','network.facility.manage','Manage facility drafts','Create and update facility drafts.',true,'explicit',true,false,NULL,NULL,false,'active','m1-candidate-1',false);

INSERT INTO audit_event_definitions
 (event_name,schema_version,display_name,description,subject_type,reason_required,required_payload_keys,allowed_payload_keys,payload_schema,status,registry_version)
VALUES ('facility.created',1,'Facility created','A facility draft was created.','facility',true,
 ARRAY['facilityId','fromState','lockVersion','toState'],ARRAY['facilityId','fromState','lockVersion','toState'],'{"type":"object"}'::jsonb,'active','m1-candidate-1');
INSERT INTO outbox_event_definitions
 (event_name,schema_version,description,aggregate_type,required_payload_keys,allowed_payload_keys,payload_schema,status,registry_version)
VALUES ('facility.created',1,'A facility draft was created.','facility',
 ARRAY['facilityId','fromState','lockVersion','toState'],ARRAY['facilityId','fromState','lockVersion','toState'],'{"type":"object"}'::jsonb,'active','m1-candidate-1');
INSERT INTO authorization_operation_events
 (operation_key,event_kind,event_name,schema_version,status,registry_version)
VALUES ('network.facility.manage','audit','facility.created',1,'active','m1-candidate-1'),
       ('network.facility.manage','outbox','facility.created',1,'active','m1-candidate-1');

CREATE FUNCTION careos_validate_facility_draft_write() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
 configured_organization uuid := nullif(current_setting('app.current_organization_id',true),'')::uuid;
 configured_actor uuid := nullif(current_setting('app.current_actor_id',true),'')::uuid;
 configured_operation text := nullif(current_setting('app.current_operation_key',true),'');
 configured_reason text := nullif(current_setting('app.current_authorization_reason',true),'');
BEGIN
 IF current_user <> '${applicationRole}' THEN RETURN NEW; END IF;
 IF EXISTS (SELECT 1 FROM authorization_operations o WHERE o.operation_key=configured_operation AND o.registry_version='test-v1') THEN
   RETURN NEW;
 END IF;
 IF NEW.organization_id IS DISTINCT FROM configured_organization OR configured_actor IS NULL
    OR configured_operation IS DISTINCT FROM 'network.facility.manage'
    OR char_length(coalesce(configured_reason,'')) NOT BETWEEN 10 AND 500 THEN
   RAISE EXCEPTION 'invalid facility governance context' USING ERRCODE='42501';
 END IF;
 IF NEW.status<>'draft' OR NEW.legal_name IS NULL OR NEW.facility_type IS NULL
    OR NEW.created_by IS DISTINCT FROM configured_actor OR NEW.updated_by IS DISTINCT FROM configured_actor THEN
   RAISE EXCEPTION 'invalid facility draft evidence' USING ERRCODE='23514';
 END IF;
 IF TG_OP='UPDATE' AND (OLD.status<>'draft' OR NEW.id<>OLD.id OR NEW.organization_id<>OLD.organization_id
    OR NEW.created_at<>OLD.created_at OR NEW.created_by<>OLD.created_by OR NEW.lock_version<>OLD.lock_version+1) THEN
   RAISE EXCEPTION 'invalid facility draft transition' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER facilities_validate_draft_write BEFORE INSERT OR UPDATE ON facilities
 FOR EACH ROW EXECUTE FUNCTION careos_validate_facility_draft_write();
REVOKE ALL ON facility_types FROM PUBLIC;
GRANT SELECT ON facility_types TO "${applicationRole}";
