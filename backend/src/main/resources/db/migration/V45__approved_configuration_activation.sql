CREATE SEQUENCE configuration_display_number_seq;
CREATE TABLE configuration_versions (
 id uuid PRIMARY KEY DEFAULT uuidv7(), organization_id uuid NOT NULL REFERENCES organizations(id),
 display_number varchar(24) NOT NULL, parent_version_id uuid, parent_digest varchar(64), baseline_revision bigint NOT NULL,
 baseline_digest varchar(64) NOT NULL, change_summary varchar(500) NOT NULL, reason varchar(500) NOT NULL,
 requested_effective_at timestamptz NOT NULL, status varchar(24) NOT NULL DEFAULT 'draft', lock_version bigint NOT NULL DEFAULT 0,
 maker_id uuid NOT NULL REFERENCES users(id), submitted_at timestamptz, approved_at timestamptz, activated_at timestamptz,
 superseded_at timestamptz, correlation_id varchar(128) NOT NULL, created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 updated_at timestamptz NOT NULL DEFAULT clock_timestamp(), updated_by uuid NOT NULL REFERENCES users(id),
 UNIQUE(organization_id,id), UNIQUE(organization_id,display_number),
 FOREIGN KEY(organization_id,parent_version_id) REFERENCES configuration_versions(organization_id,id),
 CHECK(display_number~'^CFG-[0-9]{4}-[0-9]{6}$'), CHECK(baseline_revision>=0),
 CHECK(parent_digest IS NULL OR parent_digest~'^[0-9a-f]{64}$'), CHECK(baseline_digest~'^[0-9a-f]{64}$'),
 CHECK(change_summary=btrim(change_summary) AND char_length(change_summary) BETWEEN 2 AND 500 AND change_summary !~ '[[:cntrl:]<>]'),
 CHECK(reason=btrim(reason) AND char_length(reason) BETWEEN 10 AND 500 AND reason !~ '[[:cntrl:]<>]'),
 CHECK(status IN ('draft','validated','submitted','approved','rejected','active','superseded','failed')), CHECK(lock_version>=0)
);
CREATE UNIQUE INDEX configuration_one_active_idx ON configuration_versions(organization_id) WHERE status='active';
CREATE TABLE configuration_change_items (
 id uuid PRIMARY KEY DEFAULT uuidv7(), organization_id uuid NOT NULL, configuration_id uuid NOT NULL,
 subject_type varchar(60) NOT NULL, subject_id uuid NOT NULL, baseline_revision bigint, new_revision bigint NOT NULL,
 change_type varchar(40) NOT NULL, created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(organization_id,id), UNIQUE(organization_id,configuration_id,subject_type,subject_id),
 FOREIGN KEY(organization_id,configuration_id) REFERENCES configuration_versions(organization_id,id),
 CHECK(subject_type~'^[a-z][a-z0-9_]{1,59}$' AND change_type IN ('created','updated','activated','retired','closed','revoked')),
 CHECK((baseline_revision IS NULL OR baseline_revision>=0) AND new_revision>=0)
);
CREATE TABLE configuration_validation_results (
 id uuid PRIMARY KEY DEFAULT uuidv7(), organization_id uuid NOT NULL, configuration_id uuid NOT NULL,
 configuration_revision bigint NOT NULL, result_digest varchar(64) NOT NULL, parent_digest varchar(64),
 gate_catalogue_version varchar(80) NOT NULL, gates jsonb NOT NULL, blocker_count integer NOT NULL, warning_count integer NOT NULL,
 evaluated_at timestamptz NOT NULL DEFAULT clock_timestamp(), expires_at timestamptz NOT NULL, invalidated_at timestamptz,
 invalidation_code varchar(80), evaluated_by uuid NOT NULL REFERENCES users(id),
 UNIQUE(organization_id,id), UNIQUE(organization_id,configuration_id,configuration_revision,result_digest),
 FOREIGN KEY(organization_id,configuration_id) REFERENCES configuration_versions(organization_id,id),
 CHECK(configuration_revision>=0 AND result_digest~'^[0-9a-f]{64}$'), CHECK(parent_digest IS NULL OR parent_digest~'^[0-9a-f]{64}$'),
 CHECK(jsonb_typeof(gates)='array' AND pg_column_size(gates)<=262144), CHECK(blocker_count>=0 AND warning_count>=0),
 CHECK(expires_at=evaluated_at+interval '15 minutes'), CHECK((invalidated_at IS NULL)=(invalidation_code IS NULL))
);
CREATE TABLE configuration_approvals (
 id uuid PRIMARY KEY DEFAULT uuidv7(), organization_id uuid NOT NULL, configuration_id uuid NOT NULL, validation_result_id uuid NOT NULL,
 result_digest varchar(64) NOT NULL, maker_id uuid NOT NULL REFERENCES users(id), checker_id uuid NOT NULL REFERENCES users(id),
 decision varchar(24) NOT NULL, decision_code varchar(80) NOT NULL, reason varchar(500) NOT NULL,
 decided_at timestamptz NOT NULL DEFAULT clock_timestamp(), expires_at timestamptz NOT NULL, invalidated_at timestamptz,
 policy_version varchar(80) NOT NULL, UNIQUE(organization_id,id),
 FOREIGN KEY(organization_id,configuration_id) REFERENCES configuration_versions(organization_id,id),
 FOREIGN KEY(organization_id,validation_result_id) REFERENCES configuration_validation_results(organization_id,id),
 CHECK(result_digest~'^[0-9a-f]{64}$' AND maker_id<>checker_id), CHECK(decision IN ('approved','rejected')),
 CHECK(reason=btrim(reason) AND char_length(reason) BETWEEN 10 AND 500 AND reason !~ '[[:cntrl:]<>]'), CHECK(expires_at=decided_at+interval '30 minutes')
);
CREATE TABLE configuration_activation_runs (
 id uuid PRIMARY KEY DEFAULT uuidv7(), organization_id uuid NOT NULL, configuration_id uuid NOT NULL, approval_id uuid NOT NULL,
 activator_id uuid NOT NULL REFERENCES users(id), result_digest varchar(64) NOT NULL, effective_from timestamptz NOT NULL,
 previous_version_id uuid, status varchar(24) NOT NULL, failure_code varchar(80), started_at timestamptz NOT NULL DEFAULT clock_timestamp(), completed_at timestamptz,
 correlation_id varchar(128) NOT NULL, UNIQUE(organization_id,id),
 FOREIGN KEY(organization_id,configuration_id) REFERENCES configuration_versions(organization_id,id),
 FOREIGN KEY(organization_id,approval_id) REFERENCES configuration_approvals(organization_id,id),
 FOREIGN KEY(organization_id,previous_version_id) REFERENCES configuration_versions(organization_id,id),
 CHECK(result_digest~'^[0-9a-f]{64}$' AND status IN ('running','activated','failed')),
 CHECK((status='running' AND completed_at IS NULL) OR (status<>'running' AND completed_at IS NOT NULL)),
 CHECK((status='failed')=(failure_code IS NOT NULL))
);
ALTER TABLE configuration_versions ENABLE ROW LEVEL SECURITY;ALTER TABLE configuration_versions FORCE ROW LEVEL SECURITY;
ALTER TABLE configuration_change_items ENABLE ROW LEVEL SECURITY;ALTER TABLE configuration_change_items FORCE ROW LEVEL SECURITY;
ALTER TABLE configuration_validation_results ENABLE ROW LEVEL SECURITY;ALTER TABLE configuration_validation_results FORCE ROW LEVEL SECURITY;
ALTER TABLE configuration_approvals ENABLE ROW LEVEL SECURITY;ALTER TABLE configuration_approvals FORCE ROW LEVEL SECURITY;
ALTER TABLE configuration_activation_runs ENABLE ROW LEVEL SECURITY;ALTER TABLE configuration_activation_runs FORCE ROW LEVEL SECURITY;
CREATE POLICY configuration_versions_tenant ON configuration_versions USING(organization_id=nullif(current_setting('app.current_organization_id',true),'')::uuid) WITH CHECK(organization_id=nullif(current_setting('app.current_organization_id',true),'')::uuid);
CREATE POLICY configuration_change_items_tenant ON configuration_change_items USING(organization_id=nullif(current_setting('app.current_organization_id',true),'')::uuid) WITH CHECK(organization_id=nullif(current_setting('app.current_organization_id',true),'')::uuid);
CREATE POLICY configuration_validation_results_tenant ON configuration_validation_results USING(organization_id=nullif(current_setting('app.current_organization_id',true),'')::uuid) WITH CHECK(organization_id=nullif(current_setting('app.current_organization_id',true),'')::uuid);
CREATE POLICY configuration_approvals_tenant ON configuration_approvals USING(organization_id=nullif(current_setting('app.current_organization_id',true),'')::uuid) WITH CHECK(organization_id=nullif(current_setting('app.current_organization_id',true),'')::uuid);
CREATE POLICY configuration_activation_runs_tenant ON configuration_activation_runs USING(organization_id=nullif(current_setting('app.current_organization_id',true),'')::uuid) WITH CHECK(organization_id=nullif(current_setting('app.current_organization_id',true),'')::uuid);
CREATE FUNCTION careos_validate_configuration_version() RETURNS trigger LANGUAGE plpgsql AS $$ DECLARE op text:=nullif(current_setting('app.current_operation_key',true),''); actor uuid:=nullif(current_setting('app.current_actor_id',true),'')::uuid; tenant uuid:=nullif(current_setting('app.current_organization_id',true),'')::uuid; BEGIN IF current_user<>'${applicationRole}' THEN RETURN NEW; END IF; IF NEW.organization_id IS DISTINCT FROM tenant OR actor IS NULL OR NEW.updated_by IS DISTINCT FROM actor OR op NOT IN ('configuration.validation.run','configuration.submit','configuration.approve','configuration.activate') THEN RAISE EXCEPTION 'invalid configuration governance context' USING ERRCODE='42501'; END IF; IF TG_OP='INSERT' AND (op<>'configuration.validation.run' OR NEW.status<>'draft' OR NEW.maker_id<>actor) THEN RAISE EXCEPTION 'invalid configuration creation' USING ERRCODE='23514'; END IF; IF TG_OP='UPDATE' AND (NEW.id<>OLD.id OR NEW.organization_id<>OLD.organization_id OR NEW.display_number<>OLD.display_number OR NEW.parent_version_id IS DISTINCT FROM OLD.parent_version_id OR NEW.parent_digest IS DISTINCT FROM OLD.parent_digest OR NEW.baseline_revision<>OLD.baseline_revision OR NEW.baseline_digest<>OLD.baseline_digest OR NEW.change_summary<>OLD.change_summary OR NEW.reason<>OLD.reason OR NEW.requested_effective_at<>OLD.requested_effective_at OR NEW.maker_id<>OLD.maker_id OR NEW.created_at<>OLD.created_at OR NEW.lock_version<>OLD.lock_version+1) THEN RAISE EXCEPTION 'configuration content is immutable after creation' USING ERRCODE='23514'; END IF; IF TG_OP='UPDATE' AND NOT ((op='configuration.validation.run' AND OLD.status IN ('draft','validated') AND NEW.status='validated') OR (op='configuration.submit' AND OLD.status='validated' AND NEW.status='submitted') OR (op='configuration.approve' AND OLD.status='submitted' AND NEW.status IN ('approved','rejected')) OR (op='configuration.activate' AND OLD.status='approved' AND NEW.status='active') OR (op='configuration.activate' AND OLD.status='active' AND NEW.status='superseded')) THEN RAISE EXCEPTION 'invalid configuration transition' USING ERRCODE='23514'; END IF; RETURN NEW; END $$;
CREATE TRIGGER configuration_versions_validate BEFORE INSERT OR UPDATE ON configuration_versions FOR EACH ROW EXECUTE FUNCTION careos_validate_configuration_version();
INSERT INTO audit_event_definitions(event_name,schema_version,display_name,description,subject_type,reason_required,required_payload_keys,allowed_payload_keys,payload_schema,status,registry_version) VALUES
('configuration.validation.completed',1,'Configuration validation completed','A typed configuration validation completed.','configuration',true,ARRAY['blockerCount','configurationId','expiresAt','resultDigest','resultId','warningCount'],ARRAY['blockerCount','configurationId','expiresAt','resultDigest','resultId','warningCount'],'{"type":"object"}','active','m1-candidate-1'),
('configuration.submitted',1,'Configuration submitted','A validated configuration was submitted.','configuration',true,ARRAY['configurationId','lockVersion','resultDigest','resultId'],ARRAY['configurationId','lockVersion','resultDigest','resultId'],'{"type":"object"}','active','m1-candidate-1'),
('configuration.approved',1,'Configuration approved','A configuration was independently approved.','configuration',true,ARRAY['approvalId','configurationId','decisionCode','resultDigest'],ARRAY['approvalId','configurationId','decisionCode','resultDigest'],'{"type":"object"}','active','m1-candidate-1'),
('configuration.rejected',1,'Configuration rejected','A configuration was independently rejected.','configuration',true,ARRAY['approvalId','configurationId','decisionCode','resultDigest'],ARRAY['approvalId','configurationId','decisionCode','resultDigest'],'{"type":"object"}','active','m1-candidate-1'),
('configuration.activated',1,'Configuration activated','An approved configuration was activated.','configuration',true,ARRAY['configurationId','effectiveFrom','lockVersion','previousVersionId','resultDigest'],ARRAY['configurationId','effectiveFrom','lockVersion','previousVersionId','resultDigest'],'{"type":"object"}','active','m1-candidate-1');
INSERT INTO outbox_event_definitions(event_name,schema_version,description,aggregate_type,required_payload_keys,allowed_payload_keys,payload_schema,status,registry_version) SELECT event_name,1,display_name,'configuration',required_payload_keys,allowed_payload_keys,'{"type":"object"}'::jsonb,'active','m1-candidate-1' FROM audit_event_definitions WHERE event_name IN ('configuration.validation.completed','configuration.submitted','configuration.approved','configuration.rejected','configuration.activated');
INSERT INTO authorization_operations(operation_key,permission_key,display_name,description,mutation,denial_mode,reason_required,recent_authentication_required,recent_authentication_max_age_seconds,maximum_future_skew_seconds,maker_checker_required,status,registry_version,mfa_required) VALUES
('configuration.readiness.read','configuration.readiness.read','Read configuration activation','Read versioned validation and activation state.',false,'hidden',false,false,NULL,NULL,false,'active','m1-candidate-1',false),
('configuration.validation.run','configuration.validation.run','Run configuration validation','Persist a typed immutable validation result.',true,'explicit',true,false,NULL,NULL,false,'active','m1-candidate-1',false),
('configuration.submit','configuration.submit','Submit configuration','Submit a fresh blocker-free result.',true,'explicit',true,false,NULL,NULL,false,'active','m1-candidate-1',false),
('configuration.approve','configuration.approve','Decide configuration','Independently approve or reject an exact result digest.',true,'hidden',true,true,300,5,false,'active','m1-candidate-1',true),
('configuration.activate','configuration.activate','Activate configuration','Atomically activate a fresh independently approved configuration.',true,'hidden',true,true,300,5,false,'active','m1-candidate-1',true);
INSERT INTO authorization_operation_events(operation_key,event_kind,event_name,schema_version,status,registry_version) SELECT CASE event_name WHEN 'configuration.validation.completed' THEN 'configuration.validation.run' WHEN 'configuration.submitted' THEN 'configuration.submit' WHEN 'configuration.approved' THEN 'configuration.approve' WHEN 'configuration.rejected' THEN 'configuration.approve' ELSE 'configuration.activate' END,kind,event_name,1,'active','m1-candidate-1' FROM unnest(ARRAY['audit','outbox']) kind CROSS JOIN unnest(ARRAY['configuration.validation.completed','configuration.submitted','configuration.approved','configuration.rejected','configuration.activated']) event_name;
REVOKE ALL ON configuration_versions,configuration_change_items,configuration_validation_results,configuration_approvals,configuration_activation_runs FROM PUBLIC;GRANT SELECT,INSERT,UPDATE ON configuration_versions TO "${applicationRole}";GRANT SELECT,INSERT ON configuration_change_items,configuration_validation_results,configuration_approvals,configuration_activation_runs TO "${applicationRole}";GRANT USAGE,SELECT ON configuration_display_number_seq TO "${applicationRole}";
