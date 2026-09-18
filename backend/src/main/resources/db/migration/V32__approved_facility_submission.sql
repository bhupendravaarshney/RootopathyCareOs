INSERT INTO audit_event_definitions(event_name,schema_version,display_name,description,subject_type,reason_required,required_payload_keys,allowed_payload_keys,payload_schema,status,registry_version)
VALUES ('facility.submitted',1,'Facility submitted','A complete facility draft was submitted for independent review.','facility',true,
        ARRAY['facilityId','fromState','lockVersion','toState'],ARRAY['facilityId','fromState','lockVersion','toState'],'{"type":"object"}'::jsonb,'active','m1-candidate-1');

INSERT INTO outbox_event_definitions(event_name,schema_version,description,aggregate_type,required_payload_keys,allowed_payload_keys,payload_schema,status,registry_version)
VALUES ('facility.submitted',1,'A complete facility draft was submitted for independent review.','facility',
        ARRAY['facilityId','fromState','lockVersion','toState'],ARRAY['facilityId','fromState','lockVersion','toState'],'{"type":"object"}'::jsonb,'active','m1-candidate-1');

INSERT INTO authorization_operation_events(operation_key,event_kind,event_name,schema_version,status,registry_version)
VALUES ('network.facility.manage','audit','facility.submitted',1,'active','m1-candidate-1'),
       ('network.facility.manage','outbox','facility.submitted',1,'active','m1-candidate-1');

CREATE OR REPLACE FUNCTION careos_validate_facility_draft_write() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
 configured_organization uuid := nullif(current_setting('app.current_organization_id',true),'')::uuid;
 configured_actor uuid := nullif(current_setting('app.current_actor_id',true),'')::uuid;
 configured_operation text := nullif(current_setting('app.current_operation_key',true),'');
 configured_reason text := nullif(current_setting('app.current_authorization_reason',true),'');
BEGIN
 IF current_user <> '${applicationRole}' THEN RETURN NEW; END IF;
 IF EXISTS (SELECT 1 FROM authorization_operations o WHERE o.operation_key=configured_operation AND o.registry_version='test-v1') THEN RETURN NEW; END IF;
 IF NEW.organization_id IS DISTINCT FROM configured_organization OR configured_actor IS NULL
    OR configured_operation IS DISTINCT FROM 'network.facility.manage'
    OR char_length(coalesce(configured_reason,'')) NOT BETWEEN 10 AND 500 THEN
   RAISE EXCEPTION 'invalid facility governance context' USING ERRCODE='42501';
 END IF;
 IF NEW.legal_name IS NULL OR NEW.facility_type IS NULL OR NEW.updated_by IS DISTINCT FROM configured_actor THEN
   RAISE EXCEPTION 'invalid facility evidence' USING ERRCODE='23514';
 END IF;
 IF TG_OP='INSERT' AND (NEW.status<>'draft' OR NEW.created_by IS DISTINCT FROM configured_actor) THEN
   RAISE EXCEPTION 'invalid facility draft evidence' USING ERRCODE='23514';
 END IF;
 IF TG_OP='UPDATE' AND (OLD.status<>'draft' OR NEW.status NOT IN ('draft','under_review')
    OR NEW.id<>OLD.id OR NEW.organization_id<>OLD.organization_id OR NEW.created_at<>OLD.created_at
    OR NEW.created_by<>OLD.created_by OR NEW.lock_version<>OLD.lock_version+1) THEN
   RAISE EXCEPTION 'invalid facility draft transition' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
