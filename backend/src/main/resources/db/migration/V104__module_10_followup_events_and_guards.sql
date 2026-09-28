CREATE TEMP TABLE m10_audit_seed (
    event_name varchar(160) PRIMARY KEY,
    subject_type varchar(120) NOT NULL,
    operation_key varchar(160) NOT NULL,
    required_keys text[] NOT NULL,
    allowed_keys text[] NOT NULL
) ON COMMIT DROP;

INSERT INTO m10_audit_seed VALUES
 ('followup_plan.created','followup_plan','followup.plan.create',
  ARRAY['followupPlanId','carePlanId','carePlanVersionId','patientId','status','revision'],
  ARRAY['followupPlanId','carePlanId','carePlanVersionId','patientId','status','revision']),
 ('followup.domain_added','outcome_definition','followup.domain.add',
  ARRAY['followupPlanId','outcomeDefinitionId','sequence','domain','measure','revision'],
  ARRAY['followupPlanId','outcomeDefinitionId','sequence','domain','measure','revision']),
 ('followup.rule_added','escalation_rule','followup.rule.add',
  ARRAY['followupPlanId','outcomeDefinitionId','escalationRuleId','severity','operator','revision'],
  ARRAY['followupPlanId','outcomeDefinitionId','escalationRuleId','severity','operator','revision']),
 ('followup.schedule_changed','followup_event','followup.schedule.manage',
  ARRAY['followupPlanId','followupEventId','eventType','status','revision'],
  ARRAY['followupPlanId','followupEventId','eventType','status','revision']),
 ('followup.measurement_recorded','outcome_measurement','followup.measure.record',
  ARRAY['followupPlanId','followupEventId','outcomeDefinitionId','outcomeMeasurementId','breachCount','revision'],
  ARRAY['followupPlanId','followupEventId','outcomeDefinitionId','outcomeMeasurementId','breachCount','revision']),
 ('followup.escalation_acknowledged','escalation_event','followup.escalation.acknowledge',
  ARRAY['followupPlanId','escalationEventId','clinicalTaskId','severity','status','revision'],
  ARRAY['followupPlanId','escalationEventId','clinicalTaskId','severity','status','revision']),
 ('followup.escalation_resolved','escalation_event','followup.escalation.resolve',
  ARRAY['followupPlanId','escalationEventId','clinicalTaskId','severity','status','revision'],
  ARRAY['followupPlanId','escalationEventId','clinicalTaskId','severity','status','revision']),
 ('followup.interpretation_recorded','interpretation','followup.interpretation.record',
  ARRAY['followupPlanId','followupEventId','outcomeMeasurementId','interpretationId','trend','revision'],
  ARRAY['followupPlanId','followupEventId','outcomeMeasurementId','interpretationId','trend','revision']),
 ('followup.submitted','followup_plan','followup.submit',
  ARRAY['followupPlanId','carePlanId','digest','fromStatus','status','revision'],
  ARRAY['followupPlanId','carePlanId','digest','fromStatus','status','revision']),
 ('followup.confirmed','followup_plan','followup.confirm',
  ARRAY['followupPlanId','carePlanId','digest','fromStatus','status','revision'],
  ARRAY['followupPlanId','carePlanId','digest','fromStatus','status','revision']),
 ('followup.closed','followup_plan','followup.close',
  ARRAY['followupPlanId','carePlanId','fromStatus','status','revision'],
  ARRAY['followupPlanId','carePlanId','fromStatus','status','revision']);

INSERT INTO audit_event_definitions
    (event_name,schema_version,display_name,description,subject_type,reason_required,
     required_payload_keys,allowed_payload_keys,payload_schema,status,registry_version)
SELECT event_name,1,replace(initcap(replace(event_name,'.',' ')),'_',' '),
       'CareOS Module 10 governed follow-up and outcome evidence.',subject_type,true,
       required_keys,allowed_keys,'{"type":"object","additionalProperties":false}'::jsonb,
       'active','m10-standing-direction-v1'
FROM m10_audit_seed;

INSERT INTO authorization_operation_events
    (operation_key,event_kind,event_name,schema_version,status,registry_version)
SELECT operation_key,'audit',event_name,1,'active','m10-standing-direction-v1'
FROM m10_audit_seed;

CREATE TEMP TABLE m10_outbox_seed (
    event_name varchar(180) PRIMARY KEY,
    aggregate_type varchar(120) NOT NULL,
    operation_key varchar(160) NOT NULL,
    required_keys text[] NOT NULL,
    allowed_keys text[] NOT NULL
) ON COMMIT DROP;

INSERT INTO m10_outbox_seed VALUES
 ('m10.followup-plan-created.v1','followup_plan','followup.plan.create',
  ARRAY['followupPlanId','carePlanId','carePlanVersionId','patientId','status'],
  ARRAY['followupPlanId','carePlanId','carePlanVersionId','patientId','status']),
 ('m10.followup-scheduled.v1','followup_plan','followup.schedule.manage',
  ARRAY['followupPlanId','followupEventId','eventType','status'],
  ARRAY['followupPlanId','followupEventId','eventType','status']),
 ('m10.outcome-measurement-recorded.v1','followup_plan','followup.measure.record',
  ARRAY['followupPlanId','followupEventId','outcomeDefinitionId','outcomeMeasurementId','breachCount'],
  ARRAY['followupPlanId','followupEventId','outcomeDefinitionId','outcomeMeasurementId','breachCount']),
 ('m10.outcome-threshold-breached.v1','followup_plan','followup.measure.record',
  ARRAY['followupPlanId','outcomeMeasurementId','breachCount','highestSeverity'],
  ARRAY['followupPlanId','outcomeMeasurementId','breachCount','highestSeverity']),
 ('m10.escalation-acknowledged.v1','followup_plan','followup.escalation.acknowledge',
  ARRAY['followupPlanId','escalationEventId','clinicalTaskId','severity','status'],
  ARRAY['followupPlanId','escalationEventId','clinicalTaskId','severity','status']),
 ('m10.escalation-resolved.v1','followup_plan','followup.escalation.resolve',
  ARRAY['followupPlanId','escalationEventId','clinicalTaskId','severity','status'],
  ARRAY['followupPlanId','escalationEventId','clinicalTaskId','severity','status']),
 ('m10.followup-confirmed.v1','followup_plan','followup.confirm',
  ARRAY['followupPlanId','carePlanId','digest','status'],
  ARRAY['followupPlanId','carePlanId','digest','status']),
 ('m10.followup-closed.v1','followup_plan','followup.close',
  ARRAY['followupPlanId','carePlanId','status'],
  ARRAY['followupPlanId','carePlanId','status']);

INSERT INTO outbox_event_definitions
    (event_name,schema_version,description,aggregate_type,required_payload_keys,
     allowed_payload_keys,payload_schema,status,registry_version)
SELECT event_name,1,'CareOS Module 10 transactional follow-up event.',aggregate_type,
       required_keys,allowed_keys,'{"type":"object","additionalProperties":false}'::jsonb,
       'active','m10-standing-direction-v1'
FROM m10_outbox_seed;

INSERT INTO authorization_operation_events
    (operation_key,event_kind,event_name,schema_version,status,registry_version)
SELECT operation_key,'outbox',event_name,1,'active','m10-standing-direction-v1'
FROM m10_outbox_seed;

CREATE FUNCTION careos_m10_followup_digest(requested_organization uuid, requested_plan uuid)
RETURNS char(64) LANGUAGE sql STABLE STRICT AS $$
    SELECT encode(sha256(convert_to(concat_ws('|',
        plan.id::text,plan.care_plan_id::text,plan.care_plan_version_id::text,
        plan.patient_id::text,plan.encounter_id::text,plan.responsible_practitioner_id::text,
        plan.plan_title,plan.monitoring_purpose,plan.timezone,plan.starts_on::text,
        coalesce(plan.ends_on::text,''),
        coalesce((SELECT string_agg(concat_ws(':',definition.definition_sequence,
                    definition.domain_key,definition.measure_key,definition.unit_code,
                    definition.direction_key,coalesce(definition.target_lower::text,''),
                    coalesce(definition.target_upper::text,''),definition.baseline_required::text),
                    '|' ORDER BY definition.definition_sequence)
                  FROM outcome_definitions definition
                  WHERE definition.organization_id=requested_organization
                    AND definition.followup_plan_id=plan.id),''),
        coalesce((SELECT string_agg(concat_ws(':',rule.rule_sequence,
                    rule.outcome_definition_id::text,rule.operator_key,
                    coalesce(rule.threshold_lower::text,''),coalesce(rule.threshold_upper::text,''),
                    rule.severity_key,rule.owner_practitioner_id::text,rule.task_priority_key,
                    rule.acknowledge_within_minutes),
                    '|' ORDER BY rule.rule_sequence)
                  FROM escalation_rules rule
                  WHERE rule.organization_id=requested_organization
                    AND rule.followup_plan_id=plan.id),''),
        coalesce((SELECT string_agg(concat_ws(':',event.event_sequence,event.event_type,
                    event.scheduled_for::text,event.due_at::text,event.owner_practitioner_id::text),
                    '|' ORDER BY event.event_sequence)
                  FROM followup_events event
                  WHERE event.organization_id=requested_organization
                    AND event.followup_plan_id=plan.id),''),
        coalesce((SELECT string_agg(concat_ws(':',measurement.outcome_definition_id::text,
                    measurement.numeric_value::text,measurement.unit_code,
                    measurement.observed_at::text,measurement.source_key,measurement.method_key),
                    '|' ORDER BY measurement.outcome_definition_id,measurement.id)
                  FROM outcome_measurements measurement
                  JOIN followup_events event
                    ON event.organization_id=measurement.organization_id
                   AND event.id=measurement.followup_event_id
                  WHERE measurement.organization_id=requested_organization
                    AND measurement.followup_plan_id=plan.id AND event.event_type='baseline'),'')),
        'UTF8')),'hex')::char(64)
    FROM followup_plans plan
    WHERE plan.organization_id=requested_organization AND plan.id=requested_plan
$$;

CREATE FUNCTION careos_guard_m10_plan()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE op text:=nullif(current_setting('app.current_operation_key',true),'');
BEGIN
    IF current_user<>'${applicationRole}' THEN RETURN NEW; END IF;
    IF TG_OP='INSERT' THEN
        IF op<>'followup.plan.create' OR NEW.status<>'draft'
           OR NEW.plan_digest IS NOT NULL OR NEW.frozen_at IS NOT NULL
           OR NEW.submitted_at IS NOT NULL OR NEW.confirmed_at IS NOT NULL
           OR NOT EXISTS (
                SELECT 1 FROM care_plans care_plan
                JOIN care_plan_versions version
                  ON version.organization_id=care_plan.organization_id
                 AND version.id=care_plan.current_version_id
                JOIN encounters encounter
                  ON encounter.organization_id=care_plan.organization_id
                 AND encounter.id=care_plan.encounter_id
                JOIN practitioner_profiles practitioner
                  ON practitioner.organization_id=care_plan.organization_id
                 AND practitioner.id=NEW.responsible_practitioner_id
                WHERE care_plan.organization_id=NEW.organization_id
                  AND care_plan.id=NEW.care_plan_id AND care_plan.status='active'
                  AND care_plan.current_version_id=NEW.care_plan_version_id
                  AND version.status='active' AND version.content_digest IS NOT NULL
                  AND care_plan.patient_id=NEW.patient_id AND care_plan.encounter_id=NEW.encounter_id
                  AND care_plan.responsible_practitioner_id=NEW.responsible_practitioner_id
                  AND encounter.patient_id=NEW.patient_id
                  AND encounter.status IN ('arrived','in_progress','on_hold')
                  AND practitioner.status='active' AND practitioner.lifecycle_state='active') THEN
            RAISE EXCEPTION 'invalid Module 10 follow-up plan creation' USING ERRCODE='23514';
        END IF;
    ELSE
        IF NEW.care_plan_id IS DISTINCT FROM OLD.care_plan_id
           OR NEW.care_plan_version_id IS DISTINCT FROM OLD.care_plan_version_id
           OR NEW.patient_id IS DISTINCT FROM OLD.patient_id
           OR NEW.encounter_id IS DISTINCT FROM OLD.encounter_id
           OR NEW.responsible_practitioner_id IS DISTINCT FROM OLD.responsible_practitioner_id
           OR NEW.plan_title IS DISTINCT FROM OLD.plan_title
           OR NEW.monitoring_purpose IS DISTINCT FROM OLD.monitoring_purpose
           OR NEW.timezone IS DISTINCT FROM OLD.timezone
           OR NEW.starts_on IS DISTINCT FROM OLD.starts_on
           OR NEW.ends_on IS DISTINCT FROM OLD.ends_on THEN
            RAISE EXCEPTION 'Module 10 follow-up identity is immutable' USING ERRCODE='23514';
        END IF;
        IF op IN ('followup.domain.add','followup.rule.add','followup.schedule.manage') THEN
            IF OLD.status<>'draft' OR NEW.status<>'draft'
               OR NEW.plan_digest IS DISTINCT FROM OLD.plan_digest
               OR NEW.frozen_at IS DISTINCT FROM OLD.frozen_at THEN
                RAISE EXCEPTION 'Module 10 plan configuration is frozen outside draft' USING ERRCODE='23514';
            END IF;
        ELSIF op='followup.submit' THEN
            IF OLD.status<>'draft' OR NEW.status<>'review'
               OR NEW.plan_digest<>careos_m10_followup_digest(NEW.organization_id,NEW.id)
               OR NEW.frozen_at NOT BETWEEN clock_timestamp()-interval '5 seconds' AND clock_timestamp()+interval '5 seconds'
               OR NEW.submitted_at IS DISTINCT FROM NEW.frozen_at
               OR NEW.submitted_by IS DISTINCT FROM nullif(current_setting('app.current_actor_id',true),'')::uuid THEN
                RAISE EXCEPTION 'invalid Module 10 submission' USING ERRCODE='23514';
            END IF;
        ELSIF op='followup.confirm' THEN
            IF OLD.status<>'review' OR NEW.status<>'active'
               OR NEW.plan_digest IS DISTINCT FROM OLD.plan_digest
               OR NEW.frozen_at IS DISTINCT FROM OLD.frozen_at
               OR NEW.plan_digest<>careos_m10_followup_digest(NEW.organization_id,NEW.id)
               OR NEW.confirmed_by_practitioner_id<>NEW.responsible_practitioner_id
               OR NEW.confirmed_at NOT BETWEEN clock_timestamp()-interval '5 minutes' AND clock_timestamp()+interval '5 seconds'
               OR NEW.recent_authentication_at<clock_timestamp()-interval '10 minutes'
               OR NEW.recent_authentication_at>clock_timestamp()+interval '5 seconds'
               OR NEW.mfa_authenticated_at<clock_timestamp()-interval '10 minutes'
               OR NEW.mfa_authenticated_at>clock_timestamp()+interval '5 seconds'
               OR NOT EXISTS (
                    SELECT 1 FROM care_plans care_plan
                    JOIN care_plan_versions version
                      ON version.organization_id=care_plan.organization_id
                     AND version.id=care_plan.current_version_id
                    JOIN practitioner_profiles practitioner
                      ON practitioner.organization_id=care_plan.organization_id
                     AND practitioner.id=NEW.confirmed_by_practitioner_id
                    WHERE care_plan.organization_id=NEW.organization_id
                      AND care_plan.id=NEW.care_plan_id AND care_plan.status='active'
                      AND care_plan.current_version_id=NEW.care_plan_version_id
                      AND version.status='active'
                      AND practitioner.status='active' AND practitioner.lifecycle_state='active')
               OR EXISTS (
                    SELECT 1 FROM escalation_events escalation
                    WHERE escalation.organization_id=NEW.organization_id
                      AND escalation.followup_plan_id=NEW.id
                      AND escalation.severity_key='critical' AND escalation.status<>'resolved') THEN
                RAISE EXCEPTION 'invalid Module 10 clinician confirmation' USING ERRCODE='23514';
            END IF;
        ELSIF op='followup.close' THEN
            IF NOT ((OLD.status='active' AND NEW.status='completed')
                    OR (OLD.status IN ('draft','review') AND NEW.status='cancelled'))
               OR (NEW.status='completed' AND EXISTS (
                    SELECT 1 FROM escalation_events escalation
                    WHERE escalation.organization_id=NEW.organization_id
                      AND escalation.followup_plan_id=NEW.id AND escalation.status<>'resolved')) THEN
                RAISE EXCEPTION 'invalid Module 10 follow-up closure' USING ERRCODE='23514';
            END IF;
        ELSIF op NOT IN ('followup.measure.record','followup.escalation.acknowledge',
                         'followup.escalation.resolve','followup.interpretation.record') THEN
            RAISE EXCEPTION 'invalid Module 10 follow-up transition' USING ERRCODE='23514';
        END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER followup_plans_lifecycle_guard
    BEFORE INSERT OR UPDATE ON followup_plans
    FOR EACH ROW EXECUTE FUNCTION careos_guard_m10_plan();

CREATE FUNCTION careos_guard_m10_definition()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF current_user<>'${applicationRole}' THEN RETURN NEW; END IF;
    IF NOT EXISTS (
        SELECT 1 FROM followup_plans plan
        WHERE plan.organization_id=NEW.organization_id AND plan.id=NEW.followup_plan_id
          AND plan.status='draft') THEN
        RAISE EXCEPTION 'outcome definitions require the current draft follow-up plan' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER outcome_definitions_plan_guard BEFORE INSERT ON outcome_definitions
    FOR EACH ROW EXECUTE FUNCTION careos_guard_m10_definition();

CREATE FUNCTION careos_guard_m10_rule()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF current_user<>'${applicationRole}' THEN RETURN NEW; END IF;
    IF NOT EXISTS (
        SELECT 1 FROM outcome_definitions definition
        JOIN followup_plans plan
          ON plan.organization_id=definition.organization_id
         AND plan.id=definition.followup_plan_id
        JOIN practitioner_profiles practitioner
          ON practitioner.organization_id=plan.organization_id
         AND practitioner.id=NEW.owner_practitioner_id
        WHERE definition.organization_id=NEW.organization_id
          AND definition.id=NEW.outcome_definition_id
          AND definition.followup_plan_id=NEW.followup_plan_id
          AND plan.status='draft'
          AND practitioner.status='active' AND practitioner.lifecycle_state='active') THEN
        RAISE EXCEPTION 'invalid Module 10 escalation rule ownership or definition' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER escalation_rules_definition_guard BEFORE INSERT ON escalation_rules
    FOR EACH ROW EXECUTE FUNCTION careos_guard_m10_rule();

CREATE FUNCTION careos_guard_m10_event()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE op text:=nullif(current_setting('app.current_operation_key',true),'');
BEGIN
    IF current_user<>'${applicationRole}' THEN RETURN NEW; END IF;
    IF TG_OP='INSERT' THEN
        IF op<>'followup.schedule.manage' OR NEW.status<>'scheduled'
           OR NOT EXISTS (
                SELECT 1 FROM followup_plans plan
                JOIN practitioner_profiles practitioner
                  ON practitioner.organization_id=plan.organization_id
                 AND practitioner.id=NEW.owner_practitioner_id
                WHERE plan.organization_id=NEW.organization_id
                  AND plan.id=NEW.followup_plan_id AND plan.status='draft'
                  AND practitioner.status='active' AND practitioner.lifecycle_state='active') THEN
            RAISE EXCEPTION 'invalid Module 10 follow-up event' USING ERRCODE='23514';
        END IF;
    ELSE
        IF NEW.followup_plan_id IS DISTINCT FROM OLD.followup_plan_id
           OR NEW.event_sequence IS DISTINCT FROM OLD.event_sequence
           OR NEW.event_type IS DISTINCT FROM OLD.event_type
           OR NEW.scheduled_for IS DISTINCT FROM OLD.scheduled_for
           OR NEW.due_at IS DISTINCT FROM OLD.due_at
           OR NEW.owner_practitioner_id IS DISTINCT FROM OLD.owner_practitioner_id THEN
            RAISE EXCEPTION 'Module 10 follow-up event identity is immutable' USING ERRCODE='23514';
        END IF;
        IF op='followup.measure.record' THEN
            IF OLD.status NOT IN ('scheduled','due') OR NEW.status<>'completed'
               OR NEW.completed_at NOT BETWEEN clock_timestamp()-interval '5 minutes' AND clock_timestamp()+interval '5 seconds'
               OR NEW.completed_by_practitioner_id IS NULL THEN
                RAISE EXCEPTION 'invalid measured follow-up event completion' USING ERRCODE='23514';
            END IF;
        ELSIF op='followup.schedule.manage' THEN
            IF NOT ((OLD.status='scheduled' AND NEW.status IN ('due','missed','cancelled'))
                    OR (OLD.status='due' AND NEW.status IN ('missed','cancelled'))) THEN
                RAISE EXCEPTION 'invalid Module 10 event lifecycle' USING ERRCODE='23514';
            END IF;
        ELSE
            RAISE EXCEPTION 'invalid Module 10 event operation' USING ERRCODE='23514';
        END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER followup_events_lifecycle_guard BEFORE INSERT OR UPDATE ON followup_events
    FOR EACH ROW EXECUTE FUNCTION careos_guard_m10_event();

CREATE FUNCTION careos_guard_m10_measurement()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF current_user<>'${applicationRole}' THEN RETURN NEW; END IF;
    IF NEW.observed_at<clock_timestamp()-interval '365 days'
       OR NEW.observed_at>clock_timestamp()+interval '5 seconds'
       OR NOT EXISTS (
            SELECT 1 FROM followup_plans plan
            JOIN followup_events event
              ON event.organization_id=plan.organization_id AND event.followup_plan_id=plan.id
            JOIN outcome_definitions definition
              ON definition.organization_id=plan.organization_id AND definition.followup_plan_id=plan.id
            JOIN practitioner_profiles practitioner
              ON practitioner.organization_id=plan.organization_id
             AND practitioner.id=NEW.recorded_by_practitioner_id
            WHERE plan.organization_id=NEW.organization_id AND plan.id=NEW.followup_plan_id
              AND event.id=NEW.followup_event_id AND event.status IN ('scheduled','due')
              AND definition.id=NEW.outcome_definition_id
              AND definition.unit_code=NEW.unit_code
              AND ((event.event_type='baseline' AND plan.status='draft')
                   OR (event.event_type<>'baseline' AND plan.status='active'))
              AND practitioner.status='active' AND practitioner.lifecycle_state='active') THEN
        RAISE EXCEPTION 'invalid Module 10 outcome measurement provenance' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER outcome_measurements_evidence_guard BEFORE INSERT ON outcome_measurements
    FOR EACH ROW EXECUTE FUNCTION careos_guard_m10_measurement();

CREATE FUNCTION careos_guard_m10_escalation()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE op text:=nullif(current_setting('app.current_operation_key',true),'');
BEGIN
    IF current_user<>'${applicationRole}' THEN RETURN NEW; END IF;
    IF TG_OP='INSERT' THEN
        IF op<>'followup.measure.record' OR NEW.status<>'open'
           OR NOT EXISTS (
                SELECT 1 FROM outcome_measurements measurement
                JOIN escalation_rules rule
                  ON rule.organization_id=measurement.organization_id
                 AND rule.followup_plan_id=measurement.followup_plan_id
                 AND rule.outcome_definition_id=measurement.outcome_definition_id
                JOIN clinical_tasks task
                  ON task.organization_id=measurement.organization_id
                 AND task.id=NEW.clinical_task_id
                WHERE measurement.organization_id=NEW.organization_id
                  AND measurement.id=NEW.outcome_measurement_id
                  AND measurement.followup_plan_id=NEW.followup_plan_id
                  AND measurement.outcome_definition_id=NEW.outcome_definition_id
                  AND rule.id=NEW.escalation_rule_id
                  AND rule.severity_key=NEW.severity_key
                  AND rule.owner_practitioner_id=NEW.owner_practitioner_id
                  AND task.followup_plan_id=NEW.followup_plan_id
                  AND task.outcome_measurement_id=measurement.id
                  AND task.escalation_rule_id=rule.id
                  AND task.owner_practitioner_id=NEW.owner_practitioner_id
                  AND task.status='open' AND task.requires_acknowledgement) THEN
            RAISE EXCEPTION 'invalid Module 10 threshold-breach evidence' USING ERRCODE='23514';
        END IF;
    ELSE
        IF NEW.followup_plan_id IS DISTINCT FROM OLD.followup_plan_id
           OR NEW.outcome_measurement_id IS DISTINCT FROM OLD.outcome_measurement_id
           OR NEW.outcome_definition_id IS DISTINCT FROM OLD.outcome_definition_id
           OR NEW.escalation_rule_id IS DISTINCT FROM OLD.escalation_rule_id
           OR NEW.clinical_task_id IS DISTINCT FROM OLD.clinical_task_id
           OR NEW.severity_key IS DISTINCT FROM OLD.severity_key
           OR NEW.observed_value IS DISTINCT FROM OLD.observed_value
           OR NEW.threshold_snapshot IS DISTINCT FROM OLD.threshold_snapshot
           OR NEW.owner_practitioner_id IS DISTINCT FROM OLD.owner_practitioner_id
           OR NEW.triggered_at IS DISTINCT FROM OLD.triggered_at THEN
            RAISE EXCEPTION 'Module 10 breach evidence is immutable' USING ERRCODE='23514';
        END IF;
        IF op='followup.escalation.acknowledge' THEN
            IF OLD.status<>'open' OR NEW.status<>'acknowledged'
               OR NEW.acknowledged_by_practitioner_id<>NEW.owner_practitioner_id
               OR NEW.acknowledged_at NOT BETWEEN clock_timestamp()-interval '5 minutes' AND clock_timestamp()+interval '5 seconds'
               OR NEW.resolved_at IS NOT NULL
               OR NOT EXISTS (
                    SELECT 1 FROM clinical_tasks task
                    WHERE task.organization_id=NEW.organization_id
                      AND task.id=NEW.clinical_task_id AND task.status='acknowledged'
                      AND task.acknowledged_by_practitioner_id=NEW.owner_practitioner_id) THEN
                RAISE EXCEPTION 'invalid Module 10 escalation acknowledgement' USING ERRCODE='23514';
            END IF;
        ELSIF op='followup.escalation.resolve' THEN
            IF OLD.status<>'acknowledged' OR NEW.status<>'resolved'
               OR NEW.acknowledged_at IS DISTINCT FROM OLD.acknowledged_at
               OR NEW.acknowledged_by_practitioner_id IS DISTINCT FROM OLD.acknowledged_by_practitioner_id
               OR NEW.acknowledgement_reason IS DISTINCT FROM OLD.acknowledgement_reason
               OR NEW.resolved_by_practitioner_id<>NEW.owner_practitioner_id
               OR NEW.resolved_at NOT BETWEEN clock_timestamp()-interval '5 minutes' AND clock_timestamp()+interval '5 seconds'
               OR NOT EXISTS (
                    SELECT 1 FROM clinical_tasks task
                    WHERE task.organization_id=NEW.organization_id
                      AND task.id=NEW.clinical_task_id AND task.status='completed'
                      AND task.completed_at IS NOT NULL) THEN
                RAISE EXCEPTION 'invalid Module 10 escalation resolution' USING ERRCODE='23514';
            END IF;
        ELSE
            RAISE EXCEPTION 'invalid Module 10 escalation transition' USING ERRCODE='23514';
        END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER escalation_events_lifecycle_guard BEFORE INSERT OR UPDATE ON escalation_events
    FOR EACH ROW EXECUTE FUNCTION careos_guard_m10_escalation();

CREATE FUNCTION careos_guard_m10_interpretation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF current_user<>'${applicationRole}' THEN RETURN NEW; END IF;
    IF NEW.interpreted_at<clock_timestamp()-interval '5 minutes'
       OR NEW.interpreted_at>clock_timestamp()+interval '5 seconds'
       OR NOT EXISTS (
            SELECT 1 FROM outcome_measurements measurement
            JOIN followup_events event
              ON event.organization_id=measurement.organization_id
             AND event.id=measurement.followup_event_id
            JOIN followup_plans plan
              ON plan.organization_id=measurement.organization_id
             AND plan.id=measurement.followup_plan_id
            JOIN practitioner_profiles practitioner
              ON practitioner.organization_id=plan.organization_id
             AND practitioner.id=NEW.interpreted_by_practitioner_id
            WHERE measurement.organization_id=NEW.organization_id
              AND measurement.id=NEW.outcome_measurement_id
              AND measurement.followup_plan_id=NEW.followup_plan_id
              AND event.id=NEW.followup_event_id AND event.status='completed'
              AND plan.status='active' AND measurement.observed_at<=NEW.interpreted_at
              AND practitioner.status='active' AND practitioner.lifecycle_state='active') THEN
        RAISE EXCEPTION 'invalid Module 10 interpretation provenance' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER interpretations_evidence_guard BEFORE INSERT ON interpretations
    FOR EACH ROW EXECUTE FUNCTION careos_guard_m10_interpretation();

CREATE FUNCTION careos_guard_m10_submission()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF current_user<>'${applicationRole}' OR NEW.status<>'review' THEN RETURN NEW; END IF;
    IF NOT EXISTS (
            SELECT 1 FROM outcome_definitions definition
            WHERE definition.organization_id=NEW.organization_id
              AND definition.followup_plan_id=NEW.id)
       OR EXISTS (
            SELECT 1 FROM outcome_definitions definition
            WHERE definition.organization_id=NEW.organization_id
              AND definition.followup_plan_id=NEW.id
              AND (NOT EXISTS (
                    SELECT 1 FROM escalation_rules rule
                    WHERE rule.organization_id=definition.organization_id
                      AND rule.followup_plan_id=definition.followup_plan_id
                      AND rule.outcome_definition_id=definition.id)
                   OR (definition.baseline_required AND NOT EXISTS (
                    SELECT 1 FROM outcome_measurements measurement
                    JOIN followup_events event
                      ON event.organization_id=measurement.organization_id
                     AND event.id=measurement.followup_event_id
                    WHERE measurement.organization_id=definition.organization_id
                      AND measurement.followup_plan_id=definition.followup_plan_id
                      AND measurement.outcome_definition_id=definition.id
                      AND event.event_type='baseline'))))
       OR NOT EXISTS (
            SELECT 1 FROM followup_events event
            WHERE event.organization_id=NEW.organization_id
              AND event.followup_plan_id=NEW.id AND event.event_type='scheduled'
              AND event.status='scheduled' AND event.scheduled_for>clock_timestamp()) THEN
        RAISE EXCEPTION 'follow-up plan lacks a definition, baseline, rule or future schedule' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER followup_plans_submission_completeness_guard
    BEFORE UPDATE ON followup_plans
    FOR EACH ROW EXECUTE FUNCTION careos_guard_m10_submission();

-- Extend the shared clinical-task aggregate for atomic outcome-escalation work.
CREATE OR REPLACE FUNCTION careos_validate_m5_tenant_write()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    configured_organization uuid:=nullif(current_setting('app.current_organization_id',true),'')::uuid;
    configured_actor uuid:=nullif(current_setting('app.current_actor_id',true),'')::uuid;
    configured_operation text:=nullif(current_setting('app.current_operation_key',true),'');
    allowed_operations text[];
    expected_registry text;
BEGIN
    IF current_user<>'${applicationRole}' THEN RETURN NEW; END IF;
    allowed_operations:=CASE TG_TABLE_NAME
        WHEN 'episodes_of_care' THEN ARRAY['encounter.open','encounter.lifecycle.manage']
        WHEN 'encounters' THEN ARRAY['encounter.open','encounter.lifecycle.manage']
        WHEN 'encounter_participants' THEN ARRAY['encounter.open','encounter.participant.manage']
        WHEN 'encounter_status_history' THEN ARRAY['encounter.open','encounter.lifecycle.manage']
        WHEN 'presenting_concerns' THEN ARRAY['encounter.concern.write']
        WHEN 'clinical_problems' THEN ARRAY['encounter.problem.write']
        WHEN 'diagnoses' THEN ARRAY['encounter.problem.write']
        WHEN 'encounter_notes' THEN ARRAY['encounter.note.write','encounter.note.sign','encounter.amend']
        WHEN 'note_versions' THEN ARRAY['encounter.note.write']
        WHEN 'orders' THEN ARRAY['encounter.order.manage']
        WHEN 'clinical_tasks' THEN ARRAY[
            'encounter.concern.write','encounter.task.manage','encounter.red_flag.acknowledge',
            'care-plan.assignment.manage','followup.measure.record',
            'followup.escalation.acknowledge','followup.escalation.resolve']
        WHEN 'encounter_signatures' THEN ARRAY['encounter.note.sign']
        WHEN 'amendments' THEN ARRAY['encounter.amend']
        WHEN 'red_flag_escalations' THEN ARRAY['encounter.concern.write','encounter.red_flag.acknowledge']
        ELSE ARRAY[]::text[]
    END;
    expected_registry:=CASE
        WHEN configured_operation='care-plan.assignment.manage' THEN 'm9-standing-direction-v1'
        WHEN configured_operation LIKE 'followup.%' THEN 'm10-standing-direction-v1'
        ELSE 'm5-standing-direction-v1' END;
    IF NEW.organization_id IS DISTINCT FROM configured_organization
       OR configured_actor IS NULL OR configured_operation IS NULL
       OR NOT configured_operation=ANY(allowed_operations)
       OR NOT EXISTS (
            SELECT 1 FROM authorization_operations operation
            JOIN authorization_registry_releases release
              ON release.registry_version=operation.registry_version AND release.status='active'
            WHERE operation.operation_key=configured_operation
              AND operation.registry_version=expected_registry
              AND operation.status='active') THEN
        RAISE EXCEPTION 'invalid Module 5/9/10 operation-bound tenant write context' USING ERRCODE='42501';
    END IF;
    IF TG_OP='INSERT' THEN
        IF NEW.created_by IS DISTINCT FROM configured_actor
           OR NEW.updated_by IS DISTINCT FROM configured_actor OR NEW.lock_version<>0 THEN
            RAISE EXCEPTION 'invalid Module 5/9/10 creation evidence' USING ERRCODE='23514';
        END IF;
    ELSE
        IF NEW.id IS DISTINCT FROM OLD.id OR NEW.organization_id IS DISTINCT FROM OLD.organization_id
           OR NEW.created_at IS DISTINCT FROM OLD.created_at OR NEW.created_by IS DISTINCT FROM OLD.created_by
           OR NEW.updated_by IS DISTINCT FROM configured_actor OR NEW.lock_version<>OLD.lock_version+1
           OR NEW.updated_at<OLD.updated_at OR NEW.updated_at>clock_timestamp()+interval '5 seconds' THEN
            RAISE EXCEPTION 'invalid Module 5/9/10 revision evidence' USING ERRCODE='23514';
        END IF;
    END IF;
    RETURN NEW;
END $$;

CREATE OR REPLACE FUNCTION careos_guard_m5_task()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE op text:=nullif(current_setting('app.current_operation_key',true),'');
BEGIN
    IF current_user<>'${applicationRole}' THEN RETURN NEW; END IF;
    IF NEW.followup_plan_id IS NOT NULL THEN
        IF TG_OP='INSERT' THEN
            IF op<>'followup.measure.record' OR NEW.status<>'open'
               OR NEW.owner_practitioner_id IS NULL OR NOT NEW.requires_acknowledgement
               OR NEW.due_at<=clock_timestamp()
               OR NOT EXISTS (
                    SELECT 1 FROM outcome_measurements measurement
                    JOIN escalation_rules rule
                      ON rule.organization_id=measurement.organization_id
                     AND rule.followup_plan_id=measurement.followup_plan_id
                     AND rule.outcome_definition_id=measurement.outcome_definition_id
                    JOIN followup_plans plan
                      ON plan.organization_id=measurement.organization_id
                     AND plan.id=measurement.followup_plan_id
                    WHERE measurement.organization_id=NEW.organization_id
                      AND measurement.id=NEW.outcome_measurement_id
                      AND rule.id=NEW.escalation_rule_id
                      AND plan.id=NEW.followup_plan_id
                      AND plan.patient_id=NEW.patient_id AND plan.encounter_id=NEW.encounter_id
                      AND rule.owner_practitioner_id=NEW.owner_practitioner_id
                      AND plan.status IN ('draft','active')) THEN
                RAISE EXCEPTION 'invalid Module 10 escalation task' USING ERRCODE='23514';
            END IF;
        ELSIF op='followup.escalation.acknowledge' THEN
            IF OLD.status<>'open' OR NEW.status<>'acknowledged'
               OR NEW.acknowledged_by_practitioner_id<>NEW.owner_practitioner_id
               OR NEW.acknowledged_at NOT BETWEEN clock_timestamp()-interval '5 minutes' AND clock_timestamp()+interval '5 seconds'
               OR NEW.completed_at IS NOT NULL THEN
                RAISE EXCEPTION 'invalid Module 10 escalation-task acknowledgement' USING ERRCODE='23514';
            END IF;
        ELSIF op='followup.escalation.resolve' THEN
            IF OLD.status<>'acknowledged' OR NEW.status<>'completed'
               OR NEW.acknowledged_at IS DISTINCT FROM OLD.acknowledged_at
               OR NEW.acknowledged_by_practitioner_id IS DISTINCT FROM OLD.acknowledged_by_practitioner_id
               OR NEW.acknowledgement_reason IS DISTINCT FROM OLD.acknowledgement_reason
               OR NEW.completed_at NOT BETWEEN clock_timestamp()-interval '5 minutes' AND clock_timestamp()+interval '5 seconds' THEN
                RAISE EXCEPTION 'invalid Module 10 escalation-task resolution' USING ERRCODE='23514';
            END IF;
        ELSE
            RAISE EXCEPTION 'invalid Module 10 escalation-task operation' USING ERRCODE='23514';
        END IF;
    ELSIF NEW.care_plan_id IS NOT NULL THEN
        IF TG_OP<>'INSERT' OR op<>'care-plan.assignment.manage' OR NEW.status<>'open'
           OR NEW.owner_practitioner_id IS NULL OR NOT NEW.requires_acknowledgement
           OR NEW.due_at<=clock_timestamp()
           OR NOT EXISTS (
                SELECT 1 FROM intervention_assignments assignment
                JOIN care_plan_versions version
                  ON version.organization_id=assignment.organization_id
                 AND version.id=assignment.care_plan_version_id
                JOIN care_plans plan
                  ON plan.organization_id=version.organization_id AND plan.id=version.care_plan_id
                WHERE assignment.organization_id=NEW.organization_id
                  AND assignment.id=NEW.intervention_assignment_id
                  AND assignment.care_plan_version_id=NEW.care_plan_version_id
                  AND assignment.owner_practitioner_id=NEW.owner_practitioner_id
                  AND plan.id=NEW.care_plan_id AND plan.patient_id=NEW.patient_id
                  AND plan.encounter_id=NEW.encounter_id
                  AND plan.status='draft' AND version.status='draft') THEN
            RAISE EXCEPTION 'invalid Module 9 care-plan clinical task' USING ERRCODE='23514';
        END IF;
    ELSIF TG_OP='INSERT' THEN
        IF op NOT IN ('encounter.concern.write','encounter.task.manage') OR NEW.status<>'open'
           OR (op='encounter.concern.write' AND
               (NOT NEW.requires_acknowledgement OR NEW.priority_key<>'critical' OR NEW.source_concern_id IS NULL)) THEN
            RAISE EXCEPTION 'invalid Module 5 clinical task creation' USING ERRCODE='23514';
        END IF;
    ELSIF NEW.source_concern_id IS NOT NULL THEN
        IF op<>'encounter.red_flag.acknowledge'
           OR NOT ((OLD.status='open' AND NEW.status='acknowledged')
                OR (OLD.status='acknowledged' AND NEW.status='completed')) THEN
            RAISE EXCEPTION 'red-flag tasks require the explicit acknowledgement workflow' USING ERRCODE='23514';
        END IF;
    ELSIF op<>'encounter.task.manage'
       OR NOT ((OLD.status='open' AND NEW.status IN ('in_progress','completed','cancelled','entered_in_error'))
            OR (OLD.status='in_progress' AND NEW.status IN ('completed','cancelled','entered_in_error'))) THEN
        RAISE EXCEPTION 'invalid Module 5 clinical task transition' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;

CREATE FUNCTION careos_reject_m10_delete()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Module 10 clinical evidence cannot be deleted' USING ERRCODE='42501';
END $$;

CREATE TRIGGER followup_plans_no_delete BEFORE DELETE ON followup_plans
    FOR EACH ROW EXECUTE FUNCTION careos_reject_m10_delete();
CREATE TRIGGER followup_events_no_delete BEFORE DELETE ON followup_events
    FOR EACH ROW EXECUTE FUNCTION careos_reject_m10_delete();
CREATE TRIGGER escalation_events_no_delete BEFORE DELETE ON escalation_events
    FOR EACH ROW EXECUTE FUNCTION careos_reject_m10_delete();

COMMENT ON TABLE clinical_tasks IS
    'Shared encounter, care-plan and follow-up escalation task aggregate with exact source provenance and accountable ownership.';
