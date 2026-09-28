CREATE TABLE report_runs (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    report_key varchar(40) NOT NULL,
    period_start timestamptz NOT NULL,
    period_end timestamptz NOT NULL,
    purpose_key varchar(80) NOT NULL,
    parameters_digest char(64) NOT NULL,
    metric_count integer NOT NULL DEFAULT 0,
    snapshot_digest char(64),
    completed_at timestamptz,
    status varchar(24) NOT NULL DEFAULT 'building',
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    UNIQUE (organization_id,id),
    CHECK (report_key IN (
        'operational','clinical_safety','outcomes','workforce_governance',
        'access_security','ai_governance','financial')),
    CHECK (period_end>period_start AND period_end-period_start<=interval '366 days'),
    CHECK (purpose_key~'^[a-z][a-z0-9_]{1,79}$'),
    CHECK (parameters_digest~'^[0-9a-f]{64}$'),
    CHECK (metric_count BETWEEN 0 AND 100),
    CHECK (snapshot_digest IS NULL OR snapshot_digest~'^[0-9a-f]{64}$'),
    CHECK (status IN ('building','completed','failed')),
    CHECK ((status='completed')=(completed_at IS NOT NULL)),
    CHECK ((status='completed')=(snapshot_digest IS NOT NULL)),
    CHECK (status<>'completed' OR metric_count>0),
    CHECK (lock_version>=0)
);

CREATE INDEX report_runs_type_period_idx
    ON report_runs(organization_id,report_key,period_end DESC,id);

CREATE TABLE report_run_metrics (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    report_run_id uuid NOT NULL,
    metric_sequence integer NOT NULL,
    metric_key varchar(80) NOT NULL,
    metric_value bigint NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'final',
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    UNIQUE (organization_id,id),
    UNIQUE (organization_id,report_run_id,metric_sequence),
    UNIQUE (organization_id,report_run_id,metric_key),
    FOREIGN KEY (organization_id,report_run_id) REFERENCES report_runs(organization_id,id),
    CHECK (metric_sequence BETWEEN 1 AND 100),
    CHECK (metric_key~'^[a-z][a-z0-9_]{1,79}$'),
    CHECK (metric_value>=0),
    CHECK (status='final'),
    CHECK (lock_version=0)
);

CREATE TABLE report_schedules (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    schedule_code varchar(64) NOT NULL,
    report_key varchar(40) NOT NULL,
    format_key varchar(16) NOT NULL,
    cadence varchar(24) NOT NULL,
    lookback_days integer NOT NULL,
    timezone varchar(80) NOT NULL,
    purpose_key varchar(80) NOT NULL,
    parameters_digest char(64) NOT NULL,
    next_run_at timestamptz NOT NULL,
    last_transition_at timestamptz NOT NULL,
    last_transition_by uuid NOT NULL,
    last_transition_reason varchar(500) NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'active',
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    UNIQUE (organization_id,id),
    UNIQUE (organization_id,schedule_code),
    CHECK (schedule_code~'^[A-Z0-9][A-Z0-9_.-]{3,63}$'),
    CHECK (report_key IN (
        'operational','clinical_safety','outcomes','workforce_governance',
        'access_security','ai_governance','financial')),
    CHECK (format_key IN ('csv','json')),
    CHECK (cadence IN ('daily','weekly','monthly')),
    CHECK (lookback_days BETWEEN 1 AND 366),
    CHECK (timezone='UTC' OR timezone~'^[A-Za-z_]+(?:/[A-Za-z0-9_+.-]+)+$'),
    CHECK (purpose_key~'^[a-z][a-z0-9_]{1,79}$'),
    CHECK (parameters_digest~'^[0-9a-f]{64}$'),
    CHECK (char_length(btrim(last_transition_reason)) BETWEEN 10 AND 500),
    CHECK (status IN ('active','paused','cancelled')),
    CHECK (lock_version>=0)
);

CREATE INDEX report_schedules_due_idx
    ON report_schedules(organization_id,next_run_at,id) WHERE status='active';

CREATE TABLE report_exports (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    report_run_id uuid NOT NULL,
    report_key varchar(40) NOT NULL,
    format_key varchar(16) NOT NULL,
    purpose_key varchar(80) NOT NULL,
    filters_digest char(64) NOT NULL,
    requested_at timestamptz NOT NULL,
    requested_by uuid NOT NULL,
    expires_at timestamptz NOT NULL,
    artifact_reference varchar(240),
    artifact_digest char(64),
    artifact_filename varchar(180),
    artifact_content_type varchar(120),
    row_count integer,
    byte_count bigint,
    generated_at timestamptz,
    failure_code varchar(80),
    status varchar(24) NOT NULL DEFAULT 'requested',
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    UNIQUE (organization_id,id),
    FOREIGN KEY (organization_id,report_run_id) REFERENCES report_runs(organization_id,id),
    CHECK (report_key IN (
        'operational','clinical_safety','outcomes','workforce_governance',
        'access_security','ai_governance','financial')),
    CHECK (format_key IN ('csv','json')),
    CHECK (purpose_key~'^[a-z][a-z0-9_]{1,79}$'),
    CHECK (filters_digest~'^[0-9a-f]{64}$'),
    CHECK (expires_at>requested_at AND expires_at<=requested_at+interval '1 hour'),
    CHECK ((artifact_reference IS NULL)=(artifact_digest IS NULL)),
    CHECK ((artifact_reference IS NULL)=(artifact_filename IS NULL)),
    CHECK ((artifact_reference IS NULL)=(artifact_content_type IS NULL)),
    CHECK ((artifact_reference IS NULL)=(row_count IS NULL)),
    CHECK ((artifact_reference IS NULL)=(byte_count IS NULL)),
    CHECK (artifact_reference IS NULL OR char_length(artifact_reference) BETWEEN 1 AND 240),
    CHECK (artifact_digest IS NULL OR artifact_digest~'^[0-9a-f]{64}$'),
    CHECK (artifact_filename IS NULL OR
           (artifact_filename~'^careos-report-[0-9a-f-]{36}\.(csv|json)$'
            AND artifact_filename!~'[\\/[:cntrl:]]')),
    CHECK (artifact_content_type IS NULL OR artifact_content_type IN ('text/csv','application/json')),
    CHECK (row_count IS NULL OR row_count BETWEEN 0 AND 100000),
    CHECK (byte_count IS NULL OR byte_count BETWEEN 1 AND 262144000),
    CHECK (failure_code IS NULL OR failure_code~'^[a-z][a-z0-9_]{1,79}$'),
    CHECK (status IN ('requested','ready','failed','expired')),
    CHECK ((status='ready')=(generated_at IS NOT NULL)),
    CHECK ((status='ready')=(artifact_reference IS NOT NULL)),
    CHECK ((status='failed')=(failure_code IS NOT NULL)),
    CHECK (lock_version>=0)
);

CREATE INDEX report_exports_status_idx
    ON report_exports(organization_id,status,expires_at,id);

CREATE FUNCTION careos_m12_csv_safe_cell(value text)
RETURNS text LANGUAGE sql IMMUTABLE AS $$
    SELECT CASE
        WHEN value IS NULL THEN ''
        WHEN value~E'^[[:space:]]*[=+@-]' OR value~E'^[\t\r\n]' THEN ''''||value
        ELSE value
    END
$$;

DO $$
DECLARE table_name text;
BEGIN
    FOREACH table_name IN ARRAY ARRAY[
        'report_runs','report_run_metrics','report_schedules','report_exports'
    ] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',table_name);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',table_name);
        EXECUTE format(
            'CREATE POLICY %I ON %I USING (organization_id=nullif(current_setting(''app.current_organization_id'',true),'''')::uuid) WITH CHECK (organization_id=nullif(current_setting(''app.current_organization_id'',true),'''')::uuid)',
            table_name||'_tenant_policy',table_name);
    END LOOP;
END $$;

CREATE FUNCTION careos_validate_m12_tenant_write()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    configured_organization uuid:=nullif(current_setting('app.current_organization_id',true),'')::uuid;
    configured_actor uuid:=nullif(current_setting('app.current_actor_id',true),'')::uuid;
    configured_operation text:=nullif(current_setting('app.current_operation_key',true),'');
    allowed_operations text[];
BEGIN
    IF current_user<>'${applicationRole}' THEN RETURN NEW; END IF;
    allowed_operations:=CASE TG_TABLE_NAME
        WHEN 'report_runs' THEN ARRAY['reporting.run.create']
        WHEN 'report_run_metrics' THEN ARRAY['reporting.run.create']
        WHEN 'report_schedules' THEN ARRAY[
            'reporting.schedule.create','reporting.schedule.pause',
            'reporting.schedule.resume','reporting.schedule.cancel']
        WHEN 'report_exports' THEN ARRAY['reporting.export.create','reporting.export.complete']
        ELSE ARRAY[]::text[]
    END;
    IF NEW.organization_id IS DISTINCT FROM configured_organization
       OR configured_actor IS NULL OR configured_operation IS NULL
       OR NOT configured_operation=ANY(allowed_operations)
       OR NOT EXISTS (
            SELECT 1 FROM authorization_operations operation
            JOIN authorization_registry_releases release
              ON release.registry_version=operation.registry_version AND release.status='active'
            WHERE operation.operation_key=configured_operation
              AND operation.registry_version='m12-standing-direction-v1'
              AND operation.status='active') THEN
        RAISE EXCEPTION 'invalid Module 12 operation-bound tenant write context' USING ERRCODE='42501';
    END IF;
    IF TG_OP='INSERT' THEN
        IF NEW.created_by IS DISTINCT FROM configured_actor
           OR NEW.updated_by IS DISTINCT FROM configured_actor OR NEW.lock_version<>0 THEN
            RAISE EXCEPTION 'invalid Module 12 creation evidence' USING ERRCODE='23514';
        END IF;
    ELSE
        IF NEW.id IS DISTINCT FROM OLD.id OR NEW.organization_id IS DISTINCT FROM OLD.organization_id
           OR NEW.created_at IS DISTINCT FROM OLD.created_at OR NEW.created_by IS DISTINCT FROM OLD.created_by
           OR NEW.updated_by IS DISTINCT FROM configured_actor OR NEW.lock_version<>OLD.lock_version+1
           OR NEW.updated_at<OLD.updated_at OR NEW.updated_at>clock_timestamp()+interval '5 seconds' THEN
            RAISE EXCEPTION 'invalid Module 12 revision evidence' USING ERRCODE='23514';
        END IF;
    END IF;
    RETURN NEW;
END $$;

DO $$
DECLARE table_name text;
BEGIN
    FOREACH table_name IN ARRAY ARRAY[
        'report_runs','report_run_metrics','report_schedules','report_exports'
    ] LOOP
        EXECUTE format(
            'CREATE TRIGGER %I BEFORE INSERT OR UPDATE ON %I FOR EACH ROW EXECUTE FUNCTION careos_validate_m12_tenant_write()',
            table_name||'_write_guard',table_name);
    END LOOP;
END $$;

CREATE FUNCTION careos_reject_m12_metric_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Module 12 report metrics are append-only' USING ERRCODE='42501';
END $$;

CREATE TRIGGER report_run_metrics_immutable
    BEFORE UPDATE OR DELETE ON report_run_metrics
    FOR EACH ROW EXECUTE FUNCTION careos_reject_m12_metric_mutation();

CREATE FUNCTION careos_reject_m12_delete()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Module 12 reporting evidence cannot be deleted' USING ERRCODE='42501';
END $$;

DO $$
DECLARE table_name text;
BEGIN
    FOREACH table_name IN ARRAY ARRAY['report_runs','report_schedules','report_exports'] LOOP
        EXECUTE format(
            'CREATE TRIGGER %I BEFORE DELETE ON %I FOR EACH ROW EXECUTE FUNCTION careos_reject_m12_delete()',
            table_name||'_no_delete',table_name);
    END LOOP;
END $$;

REVOKE ALL ON report_runs,report_run_metrics,report_schedules,report_exports FROM PUBLIC;
GRANT SELECT,INSERT,UPDATE ON report_runs,report_schedules,report_exports TO "${applicationRole}";
GRANT SELECT,INSERT ON report_run_metrics TO "${applicationRole}";

COMMENT ON TABLE report_runs IS
    'Immutable aggregate-only report snapshots; source-row content is prohibited.';
COMMENT ON TABLE report_exports IS
    'Purpose-bound exact-run export lifecycle with private opaque artifact metadata and no public URL.';
COMMENT ON FUNCTION careos_m12_csv_safe_cell(text) IS
    'Neutralizes spreadsheet formula prefixes before RFC-style CSV quoting.';
