ALTER TABLE evidence_export_jobs
  ADD COLUMN artifact_content_type varchar(80),
  ADD COLUMN artifact_filename varchar(160),
  ADD COLUMN policy_digest varchar(64) NOT NULL DEFAULT '9a0de3cf0389b578bf1f68ea06aa63fadfe774ef529689bc029c8a71e3e947de',
  ADD COLUMN legal_hold boolean NOT NULL DEFAULT false,
  ADD COLUMN next_attempt_at timestamptz,
  ADD COLUMN worker_id varchar(80),
  ADD COLUMN lease_expires_at timestamptz,
  ADD COLUMN dead_lettered_at timestamptz;

UPDATE evidence_export_jobs SET snapshot_time=created_at WHERE snapshot_time IS NULL;
ALTER TABLE evidence_export_jobs ALTER COLUMN snapshot_time SET NOT NULL;

ALTER TABLE evidence_export_jobs ADD CONSTRAINT evidence_export_artifact_metadata_ck CHECK (
  (artifact_content_type IS NULL AND artifact_filename IS NULL)
  OR (artifact_content_type IN ('text/csv','application/x-ndjson')
      AND artifact_filename ~ '^careos-(history|audit)-[0-9a-f-]{36}\.(csv|jsonl)$'));
ALTER TABLE evidence_export_jobs ADD CONSTRAINT evidence_export_worker_metadata_ck CHECK (
  (worker_id IS NULL OR worker_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{1,79}$')
  AND (failure_code IS NULL OR failure_code ~ '^[a-z][a-z0-9_]*([.:-][a-z0-9_]+)*$')
  AND (lease_expires_at IS NULL OR isfinite(lease_expires_at))
  AND (next_attempt_at IS NULL OR isfinite(next_attempt_at))
  AND (dead_lettered_at IS NULL OR isfinite(dead_lettered_at)));
ALTER TABLE evidence_export_jobs ADD CONSTRAINT evidence_export_policy_digest_ck CHECK (
  policy_digest='9a0de3cf0389b578bf1f68ea06aa63fadfe774ef529689bc029c8a71e3e947de');

CREATE TABLE evidence_export_snapshot_rows (
  organization_id uuid NOT NULL,
  export_id uuid NOT NULL,
  ordinal integer NOT NULL,
  row_data jsonb NOT NULL,
  PRIMARY KEY(organization_id,export_id,ordinal),
  FOREIGN KEY(organization_id,export_id)
    REFERENCES evidence_export_jobs(organization_id,id) ON DELETE CASCADE,
  CHECK(ordinal BETWEEN 1 AND 100001),
  CHECK(jsonb_typeof(row_data)='object' AND pg_column_size(row_data)<=1048576)
);
ALTER TABLE evidence_export_snapshot_rows ENABLE ROW LEVEL SECURITY;
ALTER TABLE evidence_export_snapshot_rows FORCE ROW LEVEL SECURITY;
CREATE POLICY evidence_export_snapshot_rows_tenant ON evidence_export_snapshot_rows
  USING(organization_id=nullif(current_setting('app.current_organization_id',true),'')::uuid)
  WITH CHECK(organization_id=nullif(current_setting('app.current_organization_id',true),'')::uuid);

CREATE FUNCTION careos_validate_evidence_export_snapshot_row() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE op text:=nullif(current_setting('app.current_operation_key',true),'');
 actor uuid:=nullif(current_setting('app.current_actor_id',true),'')::uuid;
 tenant uuid:=nullif(current_setting('app.current_organization_id',true),'')::uuid;
 candidate evidence_export_snapshot_rows%ROWTYPE;
BEGIN
 IF TG_OP='DELETE' THEN candidate:=OLD; ELSE candidate:=NEW; END IF;
 IF current_user<>'${applicationRole}' THEN RETURN candidate; END IF;
 IF candidate.organization_id IS DISTINCT FROM tenant OR actor IS NULL THEN
  RAISE EXCEPTION 'invalid evidence export snapshot tenant context' USING ERRCODE='42501';
 END IF;
 IF TG_OP='INSERT' AND (op<>'evidence.export.request' OR NOT EXISTS(
   SELECT 1 FROM evidence_export_jobs jobs
   WHERE jobs.organization_id=candidate.organization_id AND jobs.id=candidate.export_id
     AND jobs.requester_id=actor AND jobs.status IN ('requested','authorized')
 )) THEN RAISE EXCEPTION 'invalid evidence export snapshot insertion' USING ERRCODE='42501'; END IF;
 IF TG_OP='DELETE' AND (op NOT IN ('evidence.export.approve','evidence.export.generate') OR NOT EXISTS(
   SELECT 1 FROM evidence_export_jobs jobs
   WHERE jobs.organization_id=candidate.organization_id AND jobs.id=candidate.export_id
     AND ((op='evidence.export.approve' AND jobs.status='denied')
       OR (op='evidence.export.generate' AND jobs.status IN ('ready','failed')))
 )) THEN RAISE EXCEPTION 'invalid evidence export snapshot disposal' USING ERRCODE='42501'; END IF;
 RETURN candidate;
END $$;
CREATE TRIGGER evidence_export_snapshot_rows_validate
BEFORE INSERT OR DELETE ON evidence_export_snapshot_rows
FOR EACH ROW EXECUTE FUNCTION careos_validate_evidence_export_snapshot_row();

ALTER TABLE evidence_export_accesses
  ADD CONSTRAINT evidence_export_access_purpose_ck CHECK (
    purpose_code IN ('configuration_review','regulatory_evidence','security_investigation','data_correction')),
  ADD CONSTRAINT evidence_export_access_digest_ck CHECK (artifact_digest ~ '^[0-9a-f]{64}$'),
  ADD CONSTRAINT evidence_export_access_correlation_ck CHECK (
    correlation_id ~ '^[A-Za-z0-9._:-]{1,128}$');

-- The approved grant matrix permits an auditor to request an export and requires access to be
-- exercised by that same requester after authorization.  Pairing the access permission with the
-- existing requester_id database check makes that path usable without broadening access to any
-- other actor's artifact.
INSERT INTO authorization_role_permissions(role_key,permission_key)
VALUES ('auditor','evidence.export.access')
ON CONFLICT (role_key,permission_key) DO NOTHING;

-- Candidate 1 contains the exact event key evidence.export.disposal_requested. Keep every
-- registry, evidence, operation-binding, and consumer-inbox boundary on the same syntax so the
-- approved event can be registered, emitted, and consumed without weakening the allow-list/FK
-- checks that remain authoritative.
ALTER TABLE audit_event_definitions
  DROP CONSTRAINT audit_event_definitions_event_name_check,
  ADD CONSTRAINT audit_event_definitions_event_name_check
    CHECK (event_name ~ '^[a-z][a-z0-9]*([._:-][a-z0-9]+)*$');
ALTER TABLE outbox_event_definitions
  DROP CONSTRAINT outbox_event_definitions_event_name_check,
  ADD CONSTRAINT outbox_event_definitions_event_name_check
    CHECK (event_name ~ '^[a-z][a-z0-9]*([._:-][a-z0-9]+)*$');
ALTER TABLE audit_events
  DROP CONSTRAINT audit_events_event_name_check,
  ADD CONSTRAINT audit_events_event_name_check
    CHECK (event_name ~ '^[a-z][a-z0-9]*([._:-][a-z0-9]+)*$');
ALTER TABLE outbox_events
  DROP CONSTRAINT outbox_events_event_name_check,
  ADD CONSTRAINT outbox_events_event_name_check
    CHECK (event_name ~ '^[a-z][a-z0-9]*([._:-][a-z0-9]+)*$');
ALTER TABLE authorization_operation_events
  DROP CONSTRAINT authorization_operation_events_event_name_check,
  ADD CONSTRAINT authorization_operation_events_event_name_check
    CHECK (event_name ~ '^[a-z][a-z0-9]*([._:-][a-z0-9]+)*$');
ALTER TABLE outbox_consumer_definitions
  DROP CONSTRAINT outbox_consumer_definitions_event_name_check,
  ADD CONSTRAINT outbox_consumer_definitions_event_name_check
    CHECK (event_name ~ '^[a-z][a-z0-9]*([._:-][a-z0-9]+)*$');
ALTER TABLE consumer_inbox_records
  DROP CONSTRAINT consumer_inbox_records_event_name_check,
  ADD CONSTRAINT consumer_inbox_records_event_name_check
    CHECK (event_name ~ '^[a-z][a-z0-9]*([._:-][a-z0-9]+)*$');

INSERT INTO authorization_permissions(permission_key,display_name,description,status,registry_version,scope,risk_class)
VALUES
 ('evidence.export.worker','Generate evidence exports','Generate one independently authorized bounded export artifact.','active','m1-candidate-1','organization','high'),
 ('evidence.export.retention','Expire and dispose evidence exports','Expire access and dispose eligible export artifacts.','active','m1-candidate-1','organization','high')
ON CONFLICT (permission_key) DO NOTHING;

INSERT INTO authorization_operations(operation_key,permission_key,display_name,description,mutation,denial_mode,reason_required,recent_authentication_required,recent_authentication_max_age_seconds,maximum_future_skew_seconds,maker_checker_required,status,registry_version,mfa_required)
VALUES
 ('evidence.export.generate','evidence.export.worker','Generate evidence export','Generate one independently authorized bounded export artifact.',true,'hidden',false,false,NULL,NULL,false,'active','m1-candidate-1',false),
 ('evidence.export.expire','evidence.export.retention','Expire evidence export','End access to an expired export artifact.',true,'hidden',false,false,NULL,NULL,false,'active','m1-candidate-1',false),
 ('evidence.export.dispose','evidence.export.retention','Dispose evidence export','Delete an expired export artifact after hold recheck.',true,'hidden',false,false,NULL,NULL,false,'active','m1-candidate-1',false)
ON CONFLICT (operation_key) DO NOTHING;

INSERT INTO audit_event_definitions(event_name,schema_version,display_name,description,subject_type,reason_required,required_payload_keys,allowed_payload_keys,payload_schema,status,registry_version)
VALUES
 ('evidence.export.completed',1,'Evidence export completed','A bounded private export artifact was completed.','evidence_export',false,ARRAY['artifactDigest','exportId','expiryTime','rowCount','state'],ARRAY['artifactDigest','exportId','expiryTime','rowCount','state'],'{"type":"object"}','active','m1-candidate-1'),
 ('evidence.export.failed',1,'Evidence export failed','Evidence export generation reached a governed failure.','evidence_export',false,ARRAY['exportId','failureCode','state'],ARRAY['exportId','failureCode','state'],'{"type":"object"}','active','m1-candidate-1'),
 ('evidence.export.accessed',1,'Evidence export accessed','A digest-bound private export access grant was issued.','evidence_export',true,ARRAY['artifactDigest','exportId','expiryTime','state'],ARRAY['artifactDigest','exportId','expiryTime','state'],'{"type":"object"}','active','m1-candidate-1'),
 ('evidence.export.expired',1,'Evidence export expired','Evidence export user access expired.','evidence_export',false,ARRAY['artifactDigest','exportId','expiryTime','state'],ARRAY['artifactDigest','exportId','expiryTime','state'],'{"type":"object"}','active','m1-candidate-1'),
 ('evidence.export.disposed',1,'Evidence export disposed','An expired export artifact was deleted with digest evidence.','evidence_export',false,ARRAY['artifactDigest','exportId','expiryTime','state'],ARRAY['artifactDigest','exportId','expiryTime','state'],'{"type":"object"}','active','m1-candidate-1')
ON CONFLICT (event_name,schema_version) DO NOTHING;

INSERT INTO outbox_event_definitions(event_name,schema_version,description,aggregate_type,required_payload_keys,allowed_payload_keys,payload_schema,status,registry_version)
VALUES
 ('evidence.export.expired',1,'Evidence export user access expired.','evidence_export',ARRAY['artifactDigest','exportId','expiryTime','state'],ARRAY['artifactDigest','exportId','expiryTime','state'],'{"type":"object"}','active','m1-candidate-1'),
 ('evidence.export.disposal_requested',1,'An expired artifact is eligible for hold-aware disposal.','evidence_export',ARRAY['artifactDigest','exportId','expiryTime'],ARRAY['artifactDigest','exportId','expiryTime'],'{"type":"object"}','active','m1-candidate-1')
ON CONFLICT (event_name,schema_version) DO NOTHING;

INSERT INTO authorization_operation_events(operation_key,event_kind,event_name,schema_version,status,registry_version)
VALUES
 ('evidence.export.generate','audit','evidence.export.completed',1,'active','m1-candidate-1'),
 ('evidence.export.generate','audit','evidence.export.failed',1,'active','m1-candidate-1'),
 ('evidence.export.access','audit','evidence.export.accessed',1,'active','m1-candidate-1'),
 ('evidence.export.expire','audit','evidence.export.expired',1,'active','m1-candidate-1'),
 ('evidence.export.expire','outbox','evidence.export.expired',1,'active','m1-candidate-1'),
 ('evidence.export.expire','outbox','evidence.export.disposal_requested',1,'active','m1-candidate-1'),
 ('evidence.export.dispose','audit','evidence.export.disposed',1,'active','m1-candidate-1')
ON CONFLICT DO NOTHING;

INSERT INTO authorization_operation_events(operation_key,event_kind,event_name,schema_version,status,registry_version)
VALUES ('evidence.export.request','outbox','evidence.export.authorized',1,'active','m1-candidate-1')
ON CONFLICT DO NOTHING;

INSERT INTO outbox_consumer_definitions(
  consumer_key,event_name,schema_version,description,status,registry_version)
VALUES
 ('m1-export-worker-v1','evidence.export.authorized',1,'Generate one bounded independently authorized evidence export.','active','m1-candidate-1'),
 ('m1-retention-worker-v1','evidence.export.disposal_requested',1,'Recheck legal hold and dispose one expired digest-bound artifact.','active','m1-candidate-1')
ON CONFLICT (consumer_key,event_name,schema_version) DO NOTHING;

CREATE OR REPLACE FUNCTION careos_validate_evidence_export() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE op text:=nullif(current_setting('app.current_operation_key',true),''); actor uuid:=nullif(current_setting('app.current_actor_id',true),'')::uuid; tenant uuid:=nullif(current_setting('app.current_organization_id',true),'')::uuid;
BEGIN
 IF current_user<>'${applicationRole}' THEN RETURN NEW; END IF;
 IF NEW.organization_id IS DISTINCT FROM tenant OR actor IS NULL OR op NOT IN ('evidence.export.request','evidence.export.approve','evidence.export.access','evidence.export.generate','evidence.export.expire','evidence.export.dispose') THEN RAISE EXCEPTION 'invalid export governance context' USING ERRCODE='42501'; END IF;
 IF TG_OP='INSERT' AND (op<>'evidence.export.request' OR NEW.requester_id<>actor OR NEW.status NOT IN ('requested','authorized') OR (NEW.status='authorized' AND NEW.projection LIKE '%-detail-%') OR abs(extract(epoch FROM (NEW.snapshot_time-clock_timestamp())))>5) THEN RAISE EXCEPTION 'invalid export request' USING ERRCODE='23514'; END IF;
 IF TG_OP='UPDATE' AND (NEW.id<>OLD.id OR NEW.organization_id<>OLD.organization_id OR NEW.requester_id<>OLD.requester_id OR NEW.projection<>OLD.projection OR NEW.format<>OLD.format OR NEW.filter_json<>OLD.filter_json OR NEW.filter_digest<>OLD.filter_digest OR NEW.policy_digest<>OLD.policy_digest OR NEW.purpose_code<>OLD.purpose_code OR NEW.legal_basis_key<>OLD.legal_basis_key OR NEW.reason<>OLD.reason OR NEW.correlation_id<>OLD.correlation_id OR NEW.created_at<>OLD.created_at OR NEW.snapshot_time<>OLD.snapshot_time OR NEW.legal_hold<>OLD.legal_hold OR NEW.lock_version<>OLD.lock_version+1) THEN RAISE EXCEPTION 'export request metadata is immutable' USING ERRCODE='23514'; END IF;
 IF TG_OP='UPDATE' AND op='evidence.export.approve' AND NOT (OLD.status='requested' AND NEW.status IN ('authorized','denied') AND NEW.approver_id=actor AND NEW.approver_id<>NEW.requester_id AND NEW.approval_id IS NOT NULL) THEN RAISE EXCEPTION 'invalid export decision' USING ERRCODE='23514'; END IF;
 IF TG_OP='UPDATE' AND op<>'evidence.export.approve' AND ROW(NEW.approval_id,NEW.approver_id,NEW.decision_reason) IS DISTINCT FROM ROW(OLD.approval_id,OLD.approver_id,OLD.decision_reason) THEN RAISE EXCEPTION 'export approval evidence is immutable' USING ERRCODE='23514'; END IF;
 IF TG_OP='UPDATE' AND op='evidence.export.approve' AND ROW(NEW.attempt_count,NEW.worker_id,NEW.lease_expires_at,NEW.next_attempt_at,NEW.dead_lettered_at,NEW.row_count,NEW.byte_count,NEW.artifact_digest,NEW.artifact_reference,NEW.artifact_content_type,NEW.artifact_filename,NEW.ready_at,NEW.expires_at,NEW.disposed_at,NEW.failure_code) IS DISTINCT FROM ROW(OLD.attempt_count,OLD.worker_id,OLD.lease_expires_at,OLD.next_attempt_at,OLD.dead_lettered_at,OLD.row_count,OLD.byte_count,OLD.artifact_digest,OLD.artifact_reference,OLD.artifact_content_type,OLD.artifact_filename,OLD.ready_at,OLD.expires_at,OLD.disposed_at,OLD.failure_code) THEN RAISE EXCEPTION 'export decision cannot alter generation evidence' USING ERRCODE='23514'; END IF;
 IF TG_OP='UPDATE' AND op='evidence.export.generate' AND NOT (
   (OLD.status='authorized' AND NEW.status='running' AND NEW.attempt_count=OLD.attempt_count+1 AND NEW.worker_id IS NOT NULL AND NEW.lease_expires_at>clock_timestamp() AND ROW(NEW.row_count,NEW.byte_count,NEW.artifact_digest,NEW.artifact_reference,NEW.artifact_content_type,NEW.artifact_filename,NEW.ready_at,NEW.expires_at,NEW.disposed_at) IS NOT DISTINCT FROM ROW(OLD.row_count,OLD.byte_count,OLD.artifact_digest,OLD.artifact_reference,OLD.artifact_content_type,OLD.artifact_filename,OLD.ready_at,OLD.expires_at,OLD.disposed_at))
   OR (OLD.status='running' AND NEW.status='ready' AND NEW.attempt_count=OLD.attempt_count AND NEW.worker_id IS NULL AND NEW.lease_expires_at IS NULL AND NEW.next_attempt_at IS NULL AND NEW.dead_lettered_at IS NULL AND NEW.disposed_at IS NOT DISTINCT FROM OLD.disposed_at)
   OR (OLD.status='running' AND NEW.status IN ('authorized','failed') AND NEW.attempt_count=OLD.attempt_count AND NEW.worker_id IS NULL AND NEW.lease_expires_at IS NULL AND NEW.failure_code IS NOT NULL AND ROW(NEW.row_count,NEW.byte_count,NEW.artifact_digest,NEW.artifact_reference,NEW.artifact_content_type,NEW.artifact_filename,NEW.ready_at,NEW.expires_at,NEW.disposed_at) IS NOT DISTINCT FROM ROW(OLD.row_count,OLD.byte_count,OLD.artifact_digest,OLD.artifact_reference,OLD.artifact_content_type,OLD.artifact_filename,OLD.ready_at,OLD.expires_at,OLD.disposed_at))
  ) THEN RAISE EXCEPTION 'invalid export generation transition' USING ERRCODE='23514'; END IF;
 IF TG_OP='UPDATE' AND op='evidence.export.expire' AND NOT (OLD.status='ready' AND NEW.status='expired' AND OLD.expires_at<=clock_timestamp()) THEN RAISE EXCEPTION 'invalid export expiry transition' USING ERRCODE='23514'; END IF;
 IF TG_OP='UPDATE' AND op='evidence.export.dispose' AND NOT (OLD.status='expired' AND NEW.status='disposed' AND NOT OLD.legal_hold AND NEW.disposed_at IS NOT NULL) THEN RAISE EXCEPTION 'invalid export disposal transition' USING ERRCODE='23514'; END IF;
 IF TG_OP='UPDATE' AND op IN ('evidence.export.expire','evidence.export.dispose') AND ROW(NEW.attempt_count,NEW.worker_id,NEW.lease_expires_at,NEW.next_attempt_at,NEW.dead_lettered_at,NEW.row_count,NEW.byte_count,NEW.artifact_digest,NEW.artifact_reference,NEW.artifact_content_type,NEW.artifact_filename,NEW.ready_at,NEW.expires_at,NEW.failure_code) IS DISTINCT FROM ROW(OLD.attempt_count,OLD.worker_id,OLD.lease_expires_at,OLD.next_attempt_at,OLD.dead_lettered_at,OLD.row_count,OLD.byte_count,OLD.artifact_digest,OLD.artifact_reference,OLD.artifact_content_type,OLD.artifact_filename,OLD.ready_at,OLD.expires_at,OLD.failure_code) THEN RAISE EXCEPTION 'export retention cannot alter artifact evidence' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;

CREATE FUNCTION careos_validate_evidence_export_access() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE op text:=nullif(current_setting('app.current_operation_key',true),''); actor uuid:=nullif(current_setting('app.current_actor_id',true),'')::uuid; tenant uuid:=nullif(current_setting('app.current_organization_id',true),'')::uuid; correlation text:=nullif(current_setting('app.current_correlation_id',true),'');
BEGIN
 IF current_user<>'${applicationRole}' THEN RETURN NEW; END IF;
 IF op<>'evidence.export.access' OR NEW.organization_id IS DISTINCT FROM tenant OR NEW.actor_id IS DISTINCT FROM actor OR NEW.correlation_id IS DISTINCT FROM correlation OR NOT EXISTS(
  SELECT 1 FROM evidence_export_jobs jobs
  WHERE jobs.organization_id=NEW.organization_id AND jobs.id=NEW.export_id
    AND jobs.requester_id=actor AND jobs.status='ready' AND jobs.expires_at>clock_timestamp()
    AND jobs.artifact_digest=NEW.artifact_digest
 ) THEN RAISE EXCEPTION 'invalid evidence export access record' USING ERRCODE='42501'; END IF;
 RETURN NEW;
END $$;

CREATE TRIGGER evidence_export_accesses_validate BEFORE INSERT ON evidence_export_accesses
FOR EACH ROW EXECUTE FUNCTION careos_validate_evidence_export_access();

GRANT SELECT,INSERT,UPDATE ON evidence_export_jobs TO "${applicationRole}";
GRANT SELECT,INSERT,DELETE ON evidence_export_snapshot_rows TO "${applicationRole}";

-- Exception replacement windows use the same half-open, midnight-wrapping semantics as weekly
-- windows. Keep the invariant in PostgreSQL as well as at the API boundary so alternate writers
-- cannot create an ambiguous local schedule.
CREATE FUNCTION careos_reject_hours_exception_overlap() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE new_start integer:=NEW.start_minute;
 new_end integer:=NEW.end_minute+CASE WHEN NEW.ends_next_day THEN 1440 ELSE 0 END;
BEGIN
 IF NOT EXISTS(
  SELECT 1 FROM operating_hours_exceptions e
  WHERE e.organization_id=NEW.organization_id AND e.id=NEW.exception_id AND NOT e.closed
 ) THEN RAISE EXCEPTION 'operating-hours exception does not accept replacement intervals' USING ERRCODE='23514'; END IF;
 IF EXISTS(
  SELECT 1 FROM operating_hours_exception_intervals i
  WHERE i.organization_id=NEW.organization_id AND i.exception_id=NEW.exception_id AND i.id<>NEW.id
    AND (
      int4range(new_start,new_end,'[)') && int4range(i.start_minute,i.end_minute+CASE WHEN i.ends_next_day THEN 1440 ELSE 0 END,'[)')
      OR int4range(new_start-1440,new_end-1440,'[)') && int4range(i.start_minute,i.end_minute+CASE WHEN i.ends_next_day THEN 1440 ELSE 0 END,'[)')
      OR int4range(new_start+1440,new_end+1440,'[)') && int4range(i.start_minute,i.end_minute+CASE WHEN i.ends_next_day THEN 1440 ELSE 0 END,'[)')
    )
 ) THEN RAISE EXCEPTION 'operating-hours exception intervals overlap' USING ERRCODE='23P01'; END IF;
 RETURN NEW;
END $$;

CREATE TRIGGER operating_hours_exception_intervals_no_overlap
BEFORE INSERT OR UPDATE ON operating_hours_exception_intervals
FOR EACH ROW EXECUTE FUNCTION careos_reject_hours_exception_overlap();
