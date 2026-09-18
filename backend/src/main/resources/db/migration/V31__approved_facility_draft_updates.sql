INSERT INTO audit_event_definitions
 (event_name,schema_version,display_name,description,subject_type,reason_required,required_payload_keys,allowed_payload_keys,payload_schema,status,registry_version)
VALUES ('facility.updated',1,'Facility updated','A facility draft was updated.','facility',true,
 ARRAY['facilityId','fromState','lockVersion','toState'],ARRAY['facilityId','fromState','lockVersion','toState'],'{"type":"object"}'::jsonb,'active','m1-candidate-1');
INSERT INTO outbox_event_definitions
 (event_name,schema_version,description,aggregate_type,required_payload_keys,allowed_payload_keys,payload_schema,status,registry_version)
VALUES ('facility.updated',1,'A facility draft was updated.','facility',
 ARRAY['facilityId','fromState','lockVersion','toState'],ARRAY['facilityId','fromState','lockVersion','toState'],'{"type":"object"}'::jsonb,'active','m1-candidate-1');
INSERT INTO authorization_operation_events
 (operation_key,event_kind,event_name,schema_version,status,registry_version)
VALUES ('network.facility.manage','audit','facility.updated',1,'active','m1-candidate-1'),
       ('network.facility.manage','outbox','facility.updated',1,'active','m1-candidate-1');
