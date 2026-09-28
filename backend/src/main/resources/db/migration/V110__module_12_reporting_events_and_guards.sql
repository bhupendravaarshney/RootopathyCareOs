CREATE TEMP TABLE m12_event_seed (
    event_name varchar(160) PRIMARY KEY,
    subject_type varchar(120) NOT NULL,
    operation_key varchar(160) NOT NULL
) ON COMMIT DROP;

INSERT INTO m12_event_seed VALUES
 ('reporting.run.completed','report_run','reporting.run.create'),
 ('reporting.schedule.created','report_schedule','reporting.schedule.create'),
 ('reporting.schedule.paused','report_schedule','reporting.schedule.pause'),
 ('reporting.schedule.resumed','report_schedule','reporting.schedule.resume'),
 ('reporting.schedule.cancelled','report_schedule','reporting.schedule.cancel'),
 ('reporting.export.requested','report_export','reporting.export.create'),
 ('reporting.export.completed','report_export','reporting.export.complete');

INSERT INTO audit_event_definitions
    (event_name,schema_version,display_name,description,subject_type,reason_required,
     required_payload_keys,allowed_payload_keys,payload_schema,status,registry_version)
SELECT event_name,1,replace(initcap(replace(event_name,'.',' ')),'_',' '),
       'CareOS Module 12 governed reporting evidence.',subject_type,true,
       ARRAY['artifactId','artifactType','fromState','toState','revision'],
       ARRAY['artifactId','artifactType','fromState','toState','revision'],
       '{"type":"object","additionalProperties":false}'::jsonb,
       'active','m12-standing-direction-v1'
FROM m12_event_seed;

INSERT INTO authorization_operation_events
    (operation_key,event_kind,event_name,schema_version,status,registry_version)
SELECT operation_key,'audit',event_name,1,'active','m12-standing-direction-v1'
FROM m12_event_seed;

INSERT INTO outbox_event_definitions
    (event_name,schema_version,description,aggregate_type,required_payload_keys,
     allowed_payload_keys,payload_schema,status,registry_version)
VALUES
    ('m12.reporting-artifact-changed.v1',1,
     'A governed aggregate reporting artifact changed.','reporting_artifact',
     ARRAY['artifactId','artifactType','fromState','toState','revision'],
     ARRAY['artifactId','artifactType','fromState','toState','revision'],
     '{"type":"object","additionalProperties":false}'::jsonb,
     'active','m12-standing-direction-v1');

INSERT INTO authorization_operation_events
    (operation_key,event_kind,event_name,schema_version,status,registry_version)
SELECT permission_key,'outbox','m12.reporting-artifact-changed.v1',1,
       'active','m12-standing-direction-v1'
FROM authorization_permissions
WHERE registry_version='m12-standing-direction-v1'
  AND permission_key<>'reporting.read';

CREATE FUNCTION careos_m12_report_digest(requested_organization uuid, requested_run uuid)
RETURNS char(64) LANGUAGE sql STABLE STRICT AS $$
    SELECT encode(sha256(convert_to(concat_ws('|',
        run.id::text,run.report_key,run.period_start::text,run.period_end::text,
        run.purpose_key,run.parameters_digest,
        coalesce((SELECT string_agg(concat_ws(':',metric.metric_sequence::text,
            metric.metric_key,metric.metric_value::text),',' ORDER BY metric.metric_sequence)
            FROM report_run_metrics metric
            WHERE metric.organization_id=run.organization_id
              AND metric.report_run_id=run.id),'')
    ),'UTF8')),'hex')::char(64)
    FROM report_runs run
    WHERE run.organization_id=requested_organization AND run.id=requested_run
$$;

CREATE FUNCTION careos_guard_m12_report_run()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    operation text:=nullif(current_setting('app.current_operation_key',true),'');
BEGIN
    IF current_user<>'${applicationRole}' THEN RETURN NEW; END IF;
    IF TG_OP='INSERT' THEN
        IF operation<>'reporting.run.create' OR NEW.status<>'building'
           OR NEW.metric_count<>0 OR NEW.snapshot_digest IS NOT NULL
           OR NEW.completed_at IS NOT NULL THEN
            RAISE EXCEPTION 'report runs begin as empty building snapshots' USING ERRCODE='23514';
        END IF;
        RETURN NEW;
    END IF;
    IF operation<>'reporting.run.create' OR OLD.status<>'building' OR NEW.status<>'completed'
       OR ROW(NEW.report_key,NEW.period_start,NEW.period_end,NEW.purpose_key,NEW.parameters_digest)
          IS DISTINCT FROM
          ROW(OLD.report_key,OLD.period_start,OLD.period_end,OLD.purpose_key,OLD.parameters_digest)
       OR NEW.metric_count<>(SELECT count(*) FROM report_run_metrics metric
                             WHERE metric.organization_id=NEW.organization_id
                               AND metric.report_run_id=NEW.id)
       OR NEW.snapshot_digest IS DISTINCT FROM careos_m12_report_digest(NEW.organization_id,NEW.id)
       OR NEW.completed_at IS NULL OR NEW.completed_at>clock_timestamp()+interval '5 seconds' THEN
        RAISE EXCEPTION 'report completion requires an exact immutable aggregate snapshot' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER report_runs_lifecycle_guard
    BEFORE INSERT OR UPDATE ON report_runs
    FOR EACH ROW EXECUTE FUNCTION careos_guard_m12_report_run();

CREATE FUNCTION careos_guard_m12_report_metric()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    run_key text;
    run_status text;
    allowed_keys text[];
BEGIN
    IF current_user<>'${applicationRole}' THEN RETURN NEW; END IF;
    SELECT report_key,status INTO run_key,run_status
      FROM report_runs
     WHERE organization_id=NEW.organization_id AND id=NEW.report_run_id
     FOR UPDATE;
    allowed_keys:=CASE run_key
        WHEN 'operational' THEN ARRAY[
            'appointment_total','appointment_confirmed','appointment_no_show','encounter_completed']
        WHEN 'clinical_safety' THEN ARRAY[
            'result_flag_total','result_flag_open','critical_flag_open','overdue_flag_open']
        WHEN 'outcomes' THEN ARRAY[
            'measurement_total','escalation_total','escalation_open','followup_active']
        WHEN 'workforce_governance' THEN ARRAY[
            'workforce_active','workforce_suspended','credential_expiring','credential_attention']
        WHEN 'access_security' THEN ARRAY[
            'audit_event_total','distinct_actor_total','sensitive_operation_total']
        WHEN 'ai_governance' THEN ARRAY[
            'ai_session_total','ai_session_failed','safety_flag_open','safety_flag_critical']
        WHEN 'financial' THEN ARRAY[
            'invoice_total','invoice_open','outstanding_balance_minor','reconciliation_exception']
        ELSE ARRAY[]::text[]
    END;
    IF run_status IS DISTINCT FROM 'building' OR NOT NEW.metric_key=ANY(allowed_keys) THEN
        RAISE EXCEPTION 'report metric is not allowed for this aggregate snapshot' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER report_run_metrics_catalogue_guard
    BEFORE INSERT ON report_run_metrics
    FOR EACH ROW EXECUTE FUNCTION careos_guard_m12_report_metric();

CREATE FUNCTION careos_guard_m12_schedule()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    operation text:=nullif(current_setting('app.current_operation_key',true),'');
    actor uuid:=nullif(current_setting('app.current_actor_id',true),'')::uuid;
    reason text:=nullif(current_setting('app.current_authorization_reason',true),'');
BEGIN
    IF current_user<>'${applicationRole}' THEN RETURN NEW; END IF;
    IF TG_OP='INSERT' THEN
        IF operation<>'reporting.schedule.create' OR NEW.status<>'active'
           OR NEW.last_transition_by IS DISTINCT FROM actor
           OR NEW.last_transition_reason IS DISTINCT FROM reason
           OR NEW.last_transition_at>clock_timestamp()+interval '5 seconds'
           OR NEW.next_run_at<=NEW.created_at THEN
            RAISE EXCEPTION 'report schedule creation evidence is invalid' USING ERRCODE='23514';
        END IF;
        RETURN NEW;
    END IF;
    IF ROW(NEW.schedule_code,NEW.report_key,NEW.format_key,NEW.cadence,
           NEW.lookback_days,NEW.timezone,NEW.purpose_key,NEW.parameters_digest)
       IS DISTINCT FROM
       ROW(OLD.schedule_code,OLD.report_key,OLD.format_key,OLD.cadence,
           OLD.lookback_days,OLD.timezone,OLD.purpose_key,OLD.parameters_digest)
       OR NEW.last_transition_by IS DISTINCT FROM actor
       OR NEW.last_transition_reason IS DISTINCT FROM reason
       OR NEW.last_transition_at<=OLD.last_transition_at
       OR NEW.last_transition_at>clock_timestamp()+interval '5 seconds' THEN
        RAISE EXCEPTION 'report schedule identity and transition evidence are immutable' USING ERRCODE='23514';
    END IF;
    IF operation='reporting.schedule.pause' THEN
        IF OLD.status<>'active' OR NEW.status<>'paused'
           OR NEW.next_run_at IS DISTINCT FROM OLD.next_run_at THEN
            RAISE EXCEPTION 'only an active report schedule may be paused' USING ERRCODE='23514';
        END IF;
    ELSIF operation='reporting.schedule.resume' THEN
        IF OLD.status<>'paused' OR NEW.status<>'active' OR NEW.next_run_at<=NEW.updated_at THEN
            RAISE EXCEPTION 'only a paused report schedule may resume with a future run' USING ERRCODE='23514';
        END IF;
    ELSIF operation='reporting.schedule.cancel' THEN
        IF OLD.status NOT IN ('active','paused') OR NEW.status<>'cancelled'
           OR NEW.next_run_at IS DISTINCT FROM OLD.next_run_at THEN
            RAISE EXCEPTION 'only a live report schedule may be cancelled' USING ERRCODE='23514';
        END IF;
    ELSE
        RAISE EXCEPTION 'invalid report schedule lifecycle operation' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER report_schedules_lifecycle_guard
    BEFORE INSERT OR UPDATE ON report_schedules
    FOR EACH ROW EXECUTE FUNCTION careos_guard_m12_schedule();

CREATE FUNCTION careos_guard_m12_export()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    operation text:=nullif(current_setting('app.current_operation_key',true),'');
    actor uuid:=nullif(current_setting('app.current_actor_id',true),'')::uuid;
    source_key text;
    source_purpose text;
    source_status text;
BEGIN
    IF current_user<>'${applicationRole}' THEN RETURN NEW; END IF;
    IF TG_OP='INSERT' THEN
        SELECT report_key,purpose_key,status INTO source_key,source_purpose,source_status
          FROM report_runs
         WHERE organization_id=NEW.organization_id AND id=NEW.report_run_id;
        IF operation<>'reporting.export.create' OR NEW.status<>'requested'
           OR source_status IS DISTINCT FROM 'completed'
           OR NEW.report_key IS DISTINCT FROM source_key
           OR NEW.purpose_key IS DISTINCT FROM source_purpose
           OR NEW.requested_by IS DISTINCT FROM actor
           OR NEW.artifact_reference IS NOT NULL OR NEW.generated_at IS NOT NULL
           OR NEW.failure_code IS NOT NULL THEN
            RAISE EXCEPTION 'report export must bind one exact completed aggregate run' USING ERRCODE='23514';
        END IF;
        RETURN NEW;
    END IF;
    IF operation<>'reporting.export.complete' OR OLD.status<>'requested'
       OR NEW.status NOT IN ('ready','failed')
       OR ROW(NEW.report_run_id,NEW.report_key,NEW.format_key,NEW.purpose_key,
              NEW.filters_digest,NEW.requested_at,NEW.requested_by,NEW.expires_at)
          IS DISTINCT FROM
          ROW(OLD.report_run_id,OLD.report_key,OLD.format_key,OLD.purpose_key,
              OLD.filters_digest,OLD.requested_at,OLD.requested_by,OLD.expires_at)
       OR NEW.updated_at>=NEW.expires_at THEN
        RAISE EXCEPTION 'report export completion evidence is invalid' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER report_exports_lifecycle_guard
    BEFORE INSERT OR UPDATE ON report_exports
    FOR EACH ROW EXECUTE FUNCTION careos_guard_m12_export();
