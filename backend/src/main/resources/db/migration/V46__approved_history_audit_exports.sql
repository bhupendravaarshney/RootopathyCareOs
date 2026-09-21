ALTER TABLE audit_events ADD COLUMN operation_key varchar(180) NOT NULL DEFAULT 'legacy.unknown';
CREATE FUNCTION careos_project_audit_operation() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN NEW.operation_key:=coalesce(nullif(current_setting('app.current_operation_key',true),''),'legacy.unknown');RETURN NEW;END $$;
CREATE TRIGGER audit_events_project_operation BEFORE INSERT ON audit_events FOR EACH ROW EXECUTE FUNCTION careos_project_audit_operation();

INSERT INTO audit_event_definitions(event_name,schema_version,display_name,description,subject_type,reason_required,required_payload_keys,allowed_payload_keys,payload_schema,status,registry_version)
VALUES('evidence.audit.accessed',1,'Audit detail accessed','A purpose-bound audit detail projection was opened.','audit_event',true,ARRAY['eventId','purposeCode','risk'],ARRAY['eventId','purposeCode','risk'],'{"type":"object"}','active','m1-candidate-1');

CREATE TABLE evidence_export_jobs (
 id uuid PRIMARY KEY DEFAULT uuidv7(), organization_id uuid NOT NULL REFERENCES organizations(id), requester_id uuid NOT NULL REFERENCES users(id),
 projection varchar(40) NOT NULL, format varchar(16) NOT NULL, filter_json jsonb NOT NULL, filter_digest varchar(64) NOT NULL,
 purpose_code varchar(40) NOT NULL, legal_basis_key varchar(80) NOT NULL, reason varchar(500) NOT NULL,
 status varchar(24) NOT NULL DEFAULT 'requested', approval_id uuid, approver_id uuid REFERENCES users(id), decision_reason varchar(500),
 snapshot_time timestamptz, row_count integer, byte_count bigint, artifact_digest varchar(64), artifact_reference varchar(200),
 ready_at timestamptz, expires_at timestamptz, disposed_at timestamptz, failure_code varchar(80), attempt_count integer NOT NULL DEFAULT 0,
 lock_version bigint NOT NULL DEFAULT 0, correlation_id varchar(128) NOT NULL,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(), updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(organization_id,id), CHECK(projection IN ('history-summary-v1','history-detail-v1','audit-summary-v1','audit-detail-v1')),
 CHECK(format IN ('csv','jsonl')), CHECK(jsonb_typeof(filter_json)='object' AND pg_column_size(filter_json)<=32768),
 CHECK(filter_digest~'^[0-9a-f]{64}$'), CHECK(purpose_code IN ('configuration_review','regulatory_evidence','security_investigation','data_correction')),
 CHECK(legal_basis_key~'^[a-z][a-z0-9._:-]{1,79}$'),
 CHECK(reason=btrim(reason) AND char_length(reason) BETWEEN 10 AND 500 AND reason !~ '[[:cntrl:]<>]'),
 CHECK(status IN ('requested','authorized','denied','running','ready','expired','disposed','failed')),
 CHECK(approver_id IS NULL OR approver_id<>requester_id), CHECK(row_count IS NULL OR row_count>=0), CHECK(byte_count IS NULL OR byte_count>=0),
 CHECK(artifact_digest IS NULL OR artifact_digest~'^[0-9a-f]{64}$'), CHECK(attempt_count BETWEEN 0 AND 5), CHECK(lock_version>=0),
 CHECK((status='ready' AND ready_at IS NOT NULL AND expires_at=ready_at+interval '24 hours' AND artifact_digest IS NOT NULL AND artifact_reference IS NOT NULL) OR status<>'ready')
);
CREATE UNIQUE INDEX evidence_export_one_active_idx ON evidence_export_jobs(organization_id,requester_id,projection) WHERE status IN ('requested','authorized','running','ready');
CREATE TABLE evidence_export_accesses (
 id uuid PRIMARY KEY DEFAULT uuidv7(), organization_id uuid NOT NULL, export_id uuid NOT NULL, actor_id uuid NOT NULL REFERENCES users(id),
 purpose_code varchar(40) NOT NULL, artifact_digest varchar(64) NOT NULL, accessed_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 correlation_id varchar(128) NOT NULL, UNIQUE(organization_id,id), FOREIGN KEY(organization_id,export_id) REFERENCES evidence_export_jobs(organization_id,id)
);
ALTER TABLE evidence_export_jobs ENABLE ROW LEVEL SECURITY;ALTER TABLE evidence_export_jobs FORCE ROW LEVEL SECURITY;
ALTER TABLE evidence_export_accesses ENABLE ROW LEVEL SECURITY;ALTER TABLE evidence_export_accesses FORCE ROW LEVEL SECURITY;
CREATE POLICY evidence_export_jobs_tenant ON evidence_export_jobs USING(organization_id=nullif(current_setting('app.current_organization_id',true),'')::uuid) WITH CHECK(organization_id=nullif(current_setting('app.current_organization_id',true),'')::uuid);
CREATE POLICY evidence_export_accesses_tenant ON evidence_export_accesses USING(organization_id=nullif(current_setting('app.current_organization_id',true),'')::uuid) WITH CHECK(organization_id=nullif(current_setting('app.current_organization_id',true),'')::uuid);
CREATE FUNCTION careos_validate_evidence_export() RETURNS trigger LANGUAGE plpgsql AS $$ DECLARE op text:=nullif(current_setting('app.current_operation_key',true),''); actor uuid:=nullif(current_setting('app.current_actor_id',true),'')::uuid; tenant uuid:=nullif(current_setting('app.current_organization_id',true),'')::uuid; BEGIN IF current_user<>'${applicationRole}' THEN RETURN NEW; END IF; IF NEW.organization_id IS DISTINCT FROM tenant OR actor IS NULL OR op NOT IN ('evidence.export.request','evidence.export.approve','evidence.export.access') THEN RAISE EXCEPTION 'invalid export governance context' USING ERRCODE='42501'; END IF; IF TG_OP='INSERT' AND (op<>'evidence.export.request' OR NEW.requester_id<>actor OR NEW.status<>'requested') THEN RAISE EXCEPTION 'invalid export request' USING ERRCODE='23514'; END IF; IF TG_OP='UPDATE' AND (NEW.id<>OLD.id OR NEW.organization_id<>OLD.organization_id OR NEW.requester_id<>OLD.requester_id OR NEW.projection<>OLD.projection OR NEW.format<>OLD.format OR NEW.filter_json<>OLD.filter_json OR NEW.filter_digest<>OLD.filter_digest OR NEW.purpose_code<>OLD.purpose_code OR NEW.legal_basis_key<>OLD.legal_basis_key OR NEW.reason<>OLD.reason OR NEW.correlation_id<>OLD.correlation_id OR NEW.created_at<>OLD.created_at OR NEW.lock_version<>OLD.lock_version+1) THEN RAISE EXCEPTION 'export request metadata is immutable' USING ERRCODE='23514'; END IF; IF TG_OP='UPDATE' AND op='evidence.export.approve' AND NOT (OLD.status='requested' AND NEW.status IN ('authorized','denied') AND NEW.approver_id=actor AND NEW.approver_id<>NEW.requester_id AND NEW.approval_id IS NOT NULL) THEN RAISE EXCEPTION 'invalid export decision' USING ERRCODE='23514'; END IF; RETURN NEW; END $$;
CREATE TRIGGER evidence_export_jobs_validate BEFORE INSERT OR UPDATE ON evidence_export_jobs FOR EACH ROW EXECUTE FUNCTION careos_validate_evidence_export();
INSERT INTO audit_event_definitions(event_name,schema_version,display_name,description,subject_type,reason_required,required_payload_keys,allowed_payload_keys,payload_schema,status,registry_version) SELECT name,1,'Evidence export changed','A purpose-bound evidence export changed.','evidence_export',true,ARRAY['approvalId','exportId','filterDigest','format','projection','purposeCode'],ARRAY['approvalId','exportId','filterDigest','format','projection','purposeCode'],'{"type":"object"}'::jsonb,'active','m1-candidate-1' FROM unnest(ARRAY['evidence.export.requested','evidence.export.authorized','evidence.export.denied']) name;
INSERT INTO outbox_event_definitions(event_name,schema_version,description,aggregate_type,required_payload_keys,allowed_payload_keys,payload_schema,status,registry_version) VALUES('evidence.export.authorized',1,'An evidence export was independently authorized.','evidence_export',ARRAY['exportId','filterDigest','format','projection','purposeCode'],ARRAY['exportId','filterDigest','format','projection','purposeCode'],'{"type":"object"}','active','m1-candidate-1');
INSERT INTO authorization_operations(operation_key,permission_key,display_name,description,mutation,denial_mode,reason_required,recent_authentication_required,recent_authentication_max_age_seconds,maximum_future_skew_seconds,maker_checker_required,status,registry_version,mfa_required) VALUES
('evidence.history.read','evidence.history.read','Read configuration history','Read the minimum-necessary configuration history projection.',false,'hidden',false,false,NULL,NULL,false,'active','m1-candidate-1',false),
('evidence.audit.read','evidence.audit.read','Read audit evidence','Read bounded redacted audit evidence.',false,'hidden',false,true,600,5,false,'active','m1-candidate-1',true),
('evidence.export.request','evidence.export.request','Request evidence export','Request a purpose-bound bounded export.',true,'hidden',true,true,300,5,false,'active','m1-candidate-1',true),
('evidence.export.approve','evidence.export.approve','Decide evidence export','Independently authorize or deny a restricted export.',true,'hidden',true,true,300,5,false,'active','m1-candidate-1',true),
('evidence.export.access','evidence.export.access','Access evidence export','Access a ready digest-bound export artifact.',true,'hidden',true,true,300,5,false,'active','m1-candidate-1',true);
INSERT INTO authorization_operation_events(operation_key,event_kind,event_name,schema_version,status,registry_version)
VALUES('evidence.audit.read','audit','evidence.audit.accessed',1,'active','m1-candidate-1');
INSERT INTO authorization_operation_events(operation_key,event_kind,event_name,schema_version,status,registry_version) VALUES
('evidence.export.request','audit','evidence.export.requested',1,'active','m1-candidate-1'),
('evidence.export.approve','audit','evidence.export.authorized',1,'active','m1-candidate-1'),
('evidence.export.approve','outbox','evidence.export.authorized',1,'active','m1-candidate-1'),
('evidence.export.approve','audit','evidence.export.denied',1,'active','m1-candidate-1');
REVOKE ALL ON evidence_export_jobs,evidence_export_accesses FROM PUBLIC;GRANT SELECT,INSERT,UPDATE ON evidence_export_jobs TO "${applicationRole}";GRANT SELECT,INSERT ON evidence_export_accesses TO "${applicationRole}";
