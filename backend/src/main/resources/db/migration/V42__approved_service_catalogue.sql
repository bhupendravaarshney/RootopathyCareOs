CREATE TABLE service_definitions (
 id uuid PRIMARY KEY DEFAULT uuidv7(), organization_id uuid NOT NULL REFERENCES organizations(id),
 service_code varchar(32) NOT NULL, display_name varchar(120) NOT NULL, clinical_name varchar(160),
 description varchar(1000), coding_system varchar(120), coding_code varchar(120),
 owner_responsibility_id uuid, status varchar(24) NOT NULL DEFAULT 'draft', retirement_reason varchar(500),
 lock_version bigint NOT NULL DEFAULT 0, created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 created_by uuid NOT NULL REFERENCES users(id), updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 updated_by uuid NOT NULL REFERENCES users(id), UNIQUE(organization_id,id), UNIQUE(organization_id,service_code),
 FOREIGN KEY(organization_id,owner_responsibility_id) REFERENCES organization_governance_responsibilities(organization_id,id),
 CHECK(service_code ~ '^[A-Z0-9][A-Z0-9_-]{1,31}$'),
 CHECK(display_name=btrim(display_name) AND display_name=normalize(display_name,NFC) AND char_length(display_name) BETWEEN 2 AND 120 AND display_name !~ '[[:cntrl:]<>]'),
 CHECK(clinical_name IS NULL OR (clinical_name=btrim(clinical_name) AND clinical_name=normalize(clinical_name,NFC) AND char_length(clinical_name) BETWEEN 2 AND 160 AND clinical_name !~ '[[:cntrl:]<>]')),
 CHECK(description IS NULL OR (description=btrim(description) AND description=normalize(description,NFC) AND char_length(description) BETWEEN 1 AND 1000 AND description !~ '[[:cntrl:]<>]')),
 CHECK((coding_system IS NULL)=(coding_code IS NULL)), CHECK(coding_system IS NULL OR (char_length(coding_system) BETWEEN 1 AND 120 AND char_length(coding_code) BETWEEN 1 AND 120)),
 CHECK(clinical_name IS NULL OR owner_responsibility_id IS NOT NULL), CHECK(status IN ('draft','active','retired')),
 CHECK(retirement_reason IS NULL OR char_length(retirement_reason) BETWEEN 10 AND 500), CHECK(lock_version>=0)
);
ALTER TABLE service_definitions ENABLE ROW LEVEL SECURITY; ALTER TABLE service_definitions FORCE ROW LEVEL SECURITY;
CREATE POLICY service_definitions_tenant_policy ON service_definitions USING(organization_id=nullif(current_setting('app.current_organization_id',true),'')::uuid) WITH CHECK(organization_id=nullif(current_setting('app.current_organization_id',true),'')::uuid);
CREATE FUNCTION careos_validate_service_definition() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE configured_organization uuid:=nullif(current_setting('app.current_organization_id',true),'')::uuid; configured_actor uuid:=nullif(current_setting('app.current_actor_id',true),'')::uuid; configured_operation text:=nullif(current_setting('app.current_operation_key',true),''); owner_valid boolean;
BEGIN
 IF current_user<>'${applicationRole}' THEN RETURN NEW; END IF;
 IF NEW.organization_id IS DISTINCT FROM configured_organization OR configured_actor IS NULL OR configured_operation NOT IN ('service.catalog.manage','service.catalog.lifecycle','configuration.activate') OR NEW.updated_by IS DISTINCT FROM configured_actor OR char_length(coalesce(nullif(current_setting('app.current_authorization_reason',true),''),'')) NOT BETWEEN 10 AND 500 THEN RAISE EXCEPTION 'invalid service governance context' USING ERRCODE='42501'; END IF;
 IF TG_OP='INSERT' AND (configured_operation<>'service.catalog.manage' OR NEW.status<>'draft' OR NEW.created_by IS DISTINCT FROM configured_actor) THEN RAISE EXCEPTION 'services begin as drafts' USING ERRCODE='23514'; END IF;
 IF TG_OP='UPDATE' AND (NEW.id<>OLD.id OR NEW.organization_id<>OLD.organization_id OR NEW.created_at<>OLD.created_at OR NEW.created_by<>OLD.created_by OR NEW.lock_version<>OLD.lock_version+1) THEN RAISE EXCEPTION 'invalid service revision' USING ERRCODE='23514'; END IF;
 IF TG_OP='UPDATE' AND configured_operation='service.catalog.manage' AND (OLD.status<>'draft' OR NEW.status<>'draft') THEN RAISE EXCEPTION 'only service drafts are editable' USING ERRCODE='23514'; END IF;
 IF TG_OP='UPDATE' AND configured_operation IN ('service.catalog.lifecycle','configuration.activate') AND (NOT ((OLD.status='draft' AND NEW.status='active') OR (OLD.status='active' AND NEW.status='retired')) OR NEW.service_code<>OLD.service_code OR NEW.display_name<>OLD.display_name OR NEW.clinical_name IS DISTINCT FROM OLD.clinical_name OR NEW.description IS DISTINCT FROM OLD.description OR NEW.coding_system IS DISTINCT FROM OLD.coding_system OR NEW.coding_code IS DISTINCT FROM OLD.coding_code OR NEW.owner_responsibility_id IS DISTINCT FROM OLD.owner_responsibility_id) THEN RAISE EXCEPTION 'invalid service lifecycle transition' USING ERRCODE='23514'; END IF;
 IF NEW.owner_responsibility_id IS NOT NULL THEN SELECT EXISTS(SELECT 1 FROM organization_governance_responsibilities r WHERE r.organization_id=NEW.organization_id AND r.id=NEW.owner_responsibility_id AND r.responsibility_type='clinical' AND r.status='active' AND r.effective_from<=clock_timestamp() AND (r.effective_to IS NULL OR r.effective_to>clock_timestamp())) INTO owner_valid; IF NOT owner_valid THEN RAISE EXCEPTION 'service owner must be an active clinical responsibility' USING ERRCODE='23514'; END IF; END IF;
 IF NEW.status='retired' AND char_length(coalesce(NEW.retirement_reason,'')) NOT BETWEEN 10 AND 500 THEN RAISE EXCEPTION 'service retirement requires reason' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER service_definitions_validate BEFORE INSERT OR UPDATE ON service_definitions FOR EACH ROW EXECUTE FUNCTION careos_validate_service_definition();
INSERT INTO audit_event_definitions(event_name,schema_version,display_name,description,subject_type,reason_required,required_payload_keys,allowed_payload_keys,payload_schema,status,registry_version)
SELECT name,1,'Service definition changed','A governed service definition changed.','service_definition',true,ARRAY['fromState','lockVersion','serviceId','toState'],ARRAY['fromState','lockVersion','serviceId','toState'],'{"type":"object"}'::jsonb,'active','m1-candidate-1' FROM unnest(ARRAY['service.definition.created','service.definition.updated','service.definition.activated','service.definition.retired']) name;
INSERT INTO outbox_event_definitions(event_name,schema_version,description,aggregate_type,required_payload_keys,allowed_payload_keys,payload_schema,status,registry_version)
SELECT name,1,'A governed service definition changed.','service_definition',ARRAY['fromState','lockVersion','serviceId','toState'],ARRAY['fromState','lockVersion','serviceId','toState'],'{"type":"object"}'::jsonb,'active','m1-candidate-1' FROM unnest(ARRAY['service.definition.created','service.definition.updated','service.definition.activated','service.definition.retired']) name;
INSERT INTO authorization_operations(operation_key,permission_key,display_name,description,mutation,denial_mode,reason_required,recent_authentication_required,recent_authentication_max_age_seconds,maximum_future_skew_seconds,maker_checker_required,status,registry_version,mfa_required) VALUES
 ('service.catalog.read','service.catalog.read','Read service catalogue','Read authorized service definitions.',false,'hidden',false,false,NULL,NULL,false,'active','m1-candidate-1',false),
 ('service.catalog.manage','service.catalog.manage','Manage service drafts','Create and update service definition drafts.',true,'explicit',true,false,NULL,NULL,false,'active','m1-candidate-1',false),
 ('service.catalog.lifecycle','service.catalog.lifecycle','Manage service lifecycle','Activate and retire governed service definitions.',true,'explicit',true,true,600,5,false,'active','m1-candidate-1',true);
INSERT INTO authorization_operation_events(operation_key,event_kind,event_name,schema_version,status,registry_version)
SELECT CASE WHEN name IN ('service.definition.created','service.definition.updated') THEN 'service.catalog.manage' ELSE 'service.catalog.lifecycle' END,kind,name,1,'active','m1-candidate-1' FROM unnest(ARRAY['audit','outbox']) kind CROSS JOIN unnest(ARRAY['service.definition.created','service.definition.updated','service.definition.activated','service.definition.retired']) name;
REVOKE ALL ON service_definitions FROM PUBLIC; GRANT SELECT,INSERT,UPDATE ON service_definitions TO "${applicationRole}";
