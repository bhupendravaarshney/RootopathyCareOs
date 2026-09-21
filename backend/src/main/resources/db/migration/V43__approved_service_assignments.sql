CREATE TABLE service_assignments (
 id uuid PRIMARY KEY DEFAULT uuidv7(), organization_id uuid NOT NULL REFERENCES organizations(id),
 service_id uuid NOT NULL, facility_id uuid NOT NULL, location_id uuid, capacity integer,
 availability_notes varchar(500), prerequisites varchar(80)[] NOT NULL DEFAULT '{}',
 effective_from timestamptz NOT NULL, effective_to timestamptz, status varchar(24) NOT NULL DEFAULT 'scheduled',
 lock_version bigint NOT NULL DEFAULT 0, created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 created_by uuid NOT NULL REFERENCES users(id), updated_at timestamptz NOT NULL DEFAULT clock_timestamp(), updated_by uuid NOT NULL REFERENCES users(id),
 UNIQUE(organization_id,id), FOREIGN KEY(organization_id,service_id) REFERENCES service_definitions(organization_id,id),
 FOREIGN KEY(organization_id,facility_id) REFERENCES facilities(organization_id,id),
 FOREIGN KEY(organization_id,location_id) REFERENCES service_locations(organization_id,id),
 CHECK(capacity IS NULL OR capacity BETWEEN 1 AND 100000),
 CHECK(availability_notes IS NULL OR (availability_notes=btrim(availability_notes) AND char_length(availability_notes) BETWEEN 1 AND 500 AND availability_notes !~ '[[:cntrl:]<>]')),
 CHECK(cardinality(prerequisites)<=32 AND prerequisites<@ARRAY['appointment_required','referral_required','authorization_required','age_restriction','accessibility_review']::varchar[]),
 CHECK(isfinite(effective_from) AND (effective_to IS NULL OR effective_to>effective_from)),
 CHECK(status IN ('scheduled','active','suspended','ended','cancelled')), CHECK(lock_version>=0)
);
CREATE INDEX service_assignments_directory_idx ON service_assignments(organization_id,facility_id,status,effective_from,id);
ALTER TABLE service_assignments ENABLE ROW LEVEL SECURITY; ALTER TABLE service_assignments FORCE ROW LEVEL SECURITY;
CREATE POLICY service_assignments_tenant_policy ON service_assignments USING(organization_id=nullif(current_setting('app.current_organization_id',true),'')::uuid) WITH CHECK(organization_id=nullif(current_setting('app.current_organization_id',true),'')::uuid);
CREATE FUNCTION careos_validate_service_assignment() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE configured_organization uuid:=nullif(current_setting('app.current_organization_id',true),'')::uuid; configured_actor uuid:=nullif(current_setting('app.current_actor_id',true),'')::uuid; configured_operation text:=nullif(current_setting('app.current_operation_key',true),''); service_state text; facility_state text; location_state text; location_facility uuid;
BEGIN
 IF current_user<>'${applicationRole}' THEN RETURN NEW; END IF;
 IF NEW.organization_id IS DISTINCT FROM configured_organization OR configured_actor IS NULL OR configured_operation NOT IN ('service.assignment.manage','service.assignment.lifecycle','configuration.activate') OR NEW.updated_by IS DISTINCT FROM configured_actor OR char_length(coalesce(nullif(current_setting('app.current_authorization_reason',true),''),'')) NOT BETWEEN 10 AND 500 THEN RAISE EXCEPTION 'invalid assignment governance context' USING ERRCODE='42501'; END IF;
 IF TG_OP='INSERT' AND (configured_operation<>'service.assignment.manage' OR NEW.status<>'scheduled' OR NEW.created_by IS DISTINCT FROM configured_actor) THEN RAISE EXCEPTION 'assignments begin scheduled' USING ERRCODE='23514'; END IF;
 IF TG_OP='UPDATE' AND (NEW.id<>OLD.id OR NEW.organization_id<>OLD.organization_id OR NEW.service_id<>OLD.service_id OR NEW.facility_id<>OLD.facility_id OR NEW.location_id IS DISTINCT FROM OLD.location_id OR NEW.created_at<>OLD.created_at OR NEW.created_by<>OLD.created_by OR NEW.lock_version<>OLD.lock_version+1) THEN RAISE EXCEPTION 'invalid assignment revision' USING ERRCODE='23514'; END IF;
 IF TG_OP='UPDATE' AND configured_operation='service.assignment.manage' AND (OLD.status<>'scheduled' OR NEW.status<>'scheduled') THEN RAISE EXCEPTION 'only scheduled assignments are editable' USING ERRCODE='23514'; END IF;
 IF TG_OP='UPDATE' AND configured_operation IN ('service.assignment.lifecycle','configuration.activate') AND NOT ((OLD.status='scheduled' AND NEW.status IN ('active','cancelled')) OR (OLD.status='active' AND NEW.status IN ('suspended','ended')) OR (OLD.status='suspended' AND NEW.status='ended')) THEN RAISE EXCEPTION 'invalid assignment lifecycle transition' USING ERRCODE='23514'; END IF;
 IF TG_OP='UPDATE' AND configured_operation IN ('service.assignment.lifecycle','configuration.activate') AND (NEW.service_id<>OLD.service_id OR NEW.facility_id<>OLD.facility_id OR NEW.location_id IS DISTINCT FROM OLD.location_id OR NEW.capacity IS DISTINCT FROM OLD.capacity OR NEW.availability_notes IS DISTINCT FROM OLD.availability_notes OR NEW.prerequisites IS DISTINCT FROM OLD.prerequisites OR NEW.effective_from<>OLD.effective_from OR NEW.effective_to IS DISTINCT FROM OLD.effective_to) THEN RAISE EXCEPTION 'assignment lifecycle cannot change content' USING ERRCODE='23514'; END IF;
 SELECT status INTO service_state FROM service_definitions WHERE organization_id=NEW.organization_id AND id=NEW.service_id; SELECT status INTO facility_state FROM facilities WHERE organization_id=NEW.organization_id AND id=NEW.facility_id;
 IF NEW.status IN ('active','scheduled') AND (service_state<>'active' OR facility_state NOT IN ('under_review','active')) THEN RAISE EXCEPTION 'assignment parents are not eligible' USING ERRCODE='23514'; END IF;
 IF NEW.location_id IS NOT NULL THEN SELECT status,facility_id INTO location_state,location_facility FROM service_locations WHERE organization_id=NEW.organization_id AND id=NEW.location_id; IF location_facility IS DISTINCT FROM NEW.facility_id OR (NEW.status='active' AND location_state<>'active') THEN RAISE EXCEPTION 'assignment location is not eligible' USING ERRCODE='23514'; END IF; END IF;
 IF NEW.status IN ('scheduled','active') AND EXISTS(SELECT 1 FROM service_assignments a WHERE a.organization_id=NEW.organization_id AND a.id<>NEW.id AND a.service_id=NEW.service_id AND a.facility_id=NEW.facility_id AND a.location_id IS NOT DISTINCT FROM NEW.location_id AND a.status IN ('scheduled','active') AND tstzrange(a.effective_from,a.effective_to,'[)') && tstzrange(NEW.effective_from,NEW.effective_to,'[)')) THEN RAISE EXCEPTION 'service assignment effective range overlaps' USING ERRCODE='23P01'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER service_assignments_validate BEFORE INSERT OR UPDATE ON service_assignments FOR EACH ROW EXECUTE FUNCTION careos_validate_service_assignment();
INSERT INTO audit_event_definitions(event_name,schema_version,display_name,description,subject_type,reason_required,required_payload_keys,allowed_payload_keys,payload_schema,status,registry_version)
SELECT name,1,'Service assignment changed','A governed service assignment changed.','service_assignment',true,ARRAY['assignmentId','effectiveFrom','fromState','serviceId','targetId','toState'],ARRAY['assignmentId','effectiveFrom','fromState','serviceId','targetId','toState'],'{"type":"object"}'::jsonb,'active','m1-candidate-1' FROM unnest(ARRAY['service.assignment.scheduled','service.assignment.activated','service.assignment.suspended','service.assignment.ended','service.assignment.cancelled']) name;
INSERT INTO outbox_event_definitions(event_name,schema_version,description,aggregate_type,required_payload_keys,allowed_payload_keys,payload_schema,status,registry_version)
SELECT name,1,'A governed service assignment changed.','service_assignment',ARRAY['assignmentId','effectiveFrom','fromState','serviceId','targetId','toState'],ARRAY['assignmentId','effectiveFrom','fromState','serviceId','targetId','toState'],'{"type":"object"}'::jsonb,'active','m1-candidate-1' FROM unnest(ARRAY['service.assignment.scheduled','service.assignment.activated','service.assignment.suspended','service.assignment.ended','service.assignment.cancelled']) name;
INSERT INTO authorization_operations(operation_key,permission_key,display_name,description,mutation,denial_mode,reason_required,recent_authentication_required,recent_authentication_max_age_seconds,maximum_future_skew_seconds,maker_checker_required,status,registry_version,mfa_required) VALUES
 ('service.assignment.read','service.assignment.read','Read service assignments','Read active and scheduled service assignments.',false,'hidden',false,false,NULL,NULL,false,'active','m1-candidate-1',false),
 ('service.assignment.manage','service.assignment.manage','Manage service assignments','Create and update scheduled service assignments.',true,'explicit',true,false,NULL,NULL,false,'active','m1-candidate-1',false),
 ('service.assignment.lifecycle','service.assignment.lifecycle','Manage assignment lifecycle','Activate, suspend, end, or cancel assignments.',true,'explicit',true,true,600,5,false,'active','m1-candidate-1',true);
INSERT INTO authorization_operation_events(operation_key,event_kind,event_name,schema_version,status,registry_version)
SELECT CASE WHEN name='service.assignment.scheduled' THEN 'service.assignment.manage' ELSE 'service.assignment.lifecycle' END,kind,name,1,'active','m1-candidate-1' FROM unnest(ARRAY['audit','outbox']) kind CROSS JOIN unnest(ARRAY['service.assignment.scheduled','service.assignment.activated','service.assignment.suspended','service.assignment.ended','service.assignment.cancelled']) name;
REVOKE ALL ON service_assignments FROM PUBLIC; GRANT SELECT,INSERT,UPDATE ON service_assignments TO "${applicationRole}";
