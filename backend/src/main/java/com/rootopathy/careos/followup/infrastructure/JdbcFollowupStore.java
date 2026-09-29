package com.rootopathy.careos.followup.infrastructure;

import com.rootopathy.careos.followup.application.FollowupException;
import com.rootopathy.careos.followup.application.FollowupStore;
import com.rootopathy.careos.followup.domain.FollowupScreen;
import com.rootopathy.careos.shared.domain.UuidV7Generator;
import com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** PostgreSQL-backed Module 10 follow-up, outcome and escalation workflow. */
@Repository
public class JdbcFollowupStore implements FollowupStore {
    private static final Set<String> PLAN_TERMINAL = Set.of("completed", "cancelled");

    private final JdbcTemplate jdbc;

    public JdbcFollowupStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Projection projection(AuthorizedTenantContext context, ScreenQuery query) {
        requireOperationScope(context);
        var projected = switch (query.screenId()) {
            case "P10-04" -> eventRows(context, query);
            case "P10-05" -> escalationRows(context, query);
            case "P10-07" -> measurementRows(context, query);
            default -> planRows(context, query);
        };
        var rows = projected.stream()
                .map(row -> withAllowedActions(query.screenId(), row))
                .limit(query.limit())
                .toList();
        return new Projection(
                metrics(context), columns(query.screenId()), rows,
                notices(query.screenId()), databaseNow());
    }

    @Override
    public Set<String> permissions(AuthorizedTenantContext context) {
        requireOperationScope(context);
        return Set.copyOf(jdbc.queryForList(
                "SELECT permission_key FROM careos_projected_interactive_permissions(?, ?)",
                String.class, context.organizationId(), context.actorId()));
    }

    @Override
    public MutationResult mutate(AuthorizedTenantContext context, MutationCommand command) {
        requireOperationScope(context);
        return switch (command.actionKey()) {
            case "create-followup-plan" -> createPlan(context, command);
            case "add-domain" -> addDomain(context, command);
            case "add-rule" -> addRule(context, command);
            case "schedule-followup" -> scheduleFollowup(context, command);
            case "record-measurement" -> recordMeasurement(context, command);
            case "acknowledge-escalation" -> acknowledgeEscalation(context, command);
            case "resolve-escalation" -> resolveEscalation(context, command);
            case "record-interpretation" -> recordInterpretation(context, command);
            case "submit-followup-plan" -> submitPlan(context, command);
            case "confirm-followup-plan" -> confirmPlan(context, command);
            case "close-followup-plan" -> closePlan(context, command);
            default -> throw notFound("The requested follow-up action does not exist.");
        };
    }

    private MutationResult createPlan(
            AuthorizedTenantContext context, MutationCommand command) {
        var carePlanId = fieldUuid(command, "carePlanId");
        var versionId = fieldUuid(command, "carePlanVersionId");
        var patientId = fieldUuid(command, "patientId");
        var encounterId = fieldUuid(command, "encounterId");
        var practitionerId = fieldUuid(command, "responsiblePractitionerId");
        var title = bounded(field(command, "planTitle"), 2, 200, "planTitle");
        var purpose = bounded(field(command, "monitoringPurpose"), 10, 1000, "monitoringPurpose");
        var timezone = bounded(field(command, "timezone"), 3, 64, "timezone");
        if (!timezone.equals("UTC")
                && !timezone.matches("[A-Za-z][A-Za-z0-9_+.-]*(/[A-Za-z0-9_+.-]+)+")) {
            throw invalid("timezone must be UTC or a valid IANA timezone identifier.");
        }
        var startsOn = date(field(command, "startsOn"), "startsOn");
        var endsOn = optionalDate(command.fields().get("endsOn"), "endsOn");
        if (endsOn != null && endsOn.isBefore(startsOn)) {
            throw invalid("endsOn must not be before startsOn.");
        }
        var planId = UuidV7Generator.randomUuid();
        jdbc.update(
                """
                INSERT INTO followup_plans(
                    id,organization_id,care_plan_id,care_plan_version_id,patient_id,
                    encounter_id,responsible_practitioner_id,plan_title,monitoring_purpose,
                    timezone,starts_on,ends_on,status,created_by,updated_by)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?, 'draft',?,?)
                """,
                planId, context.organizationId(), carePlanId, versionId, patientId,
                encounterId, practitionerId, title, purpose, timezone, Date.valueOf(startsOn),
                endsOn == null ? null : Date.valueOf(endsOn), context.actorId(), context.actorId());
        var audit = map(
                "followupPlanId", planId, "carePlanId", carePlanId,
                "carePlanVersionId", versionId, "patientId", patientId,
                "status", "draft", "revision", 0L);
        var outbox = map(
                "followupPlanId", planId, "carePlanId", carePlanId,
                "carePlanVersionId", versionId, "patientId", patientId, "status", "draft");
        return result(
                planId, planId, "followup_plan", "followup_plan.created",
                "m10.followup-plan-created.v1", "followup_plan", planId,
                audit, outbox, 201, 0);
    }

    private MutationResult addDomain(
            AuthorizedTenantContext context, MutationCommand command) {
        var plan = draftPlan(context, command);
        var domainKey = code(field(command, "domainKey"), "domainKey", 2, 80);
        var domainDisplay = bounded(field(command, "domainDisplay"), 2, 200, "domainDisplay");
        var measureKey = code(field(command, "measureKey"), "measureKey", 2, 80);
        var measureDisplay = bounded(field(command, "measureDisplay"), 2, 200, "measureDisplay");
        var unit = unit(field(command, "unitCode"), "unitCode");
        var direction = oneOf(field(command, "direction"), "direction",
                "increase", "decrease", "range", "maintain");
        var lower = optionalDecimal(command.fields().get("targetLower"), "targetLower");
        var upper = optionalDecimal(command.fields().get("targetUpper"), "targetUpper");
        if (lower == null && upper == null) {
            throw invalid("At least one target bound is required.");
        }
        if (lower != null && upper != null && lower.compareTo(upper) > 0) {
            throw invalid("targetLower must not exceed targetUpper.");
        }
        var baselineRequired = bool(field(command, "baselineRequired"), "baselineRequired");
        var sequence = nextSequence(
                "outcome_definitions", "definition_sequence", context.organizationId(), plan.id());
        var definitionId = UuidV7Generator.randomUuid();
        jdbc.update(
                """
                INSERT INTO outcome_definitions(
                    id,organization_id,followup_plan_id,definition_sequence,domain_key,
                    domain_display,measure_key,measure_display,unit_code,direction_key,
                    target_lower,target_upper,baseline_required,status,created_by,updated_by)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,'active',?,?)
                """,
                definitionId, context.organizationId(), plan.id(), sequence, domainKey,
                domainDisplay, measureKey, measureDisplay, unit, direction, lower, upper,
                baselineRequired, context.actorId(), context.actorId());
        var revision = bumpDraft(context, plan);
        var audit = map(
                "followupPlanId", plan.id(), "outcomeDefinitionId", definitionId,
                "sequence", sequence, "domain", domainKey, "measure", measureKey,
                "revision", revision);
        return result(
                definitionId, plan.id(), "outcome_definition", "followup.domain_added",
                null, null, null, audit, Map.of(), 201, revision);
    }

    private MutationResult addRule(
            AuthorizedTenantContext context, MutationCommand command) {
        var plan = draftPlan(context, command);
        var definitionId = fieldUuid(command, "outcomeDefinitionId");
        if (!belongsToPlan("outcome_definitions", definitionId, context.organizationId(), plan.id())) {
            throw conflict("The outcome definition is not part of this monitoring plan.");
        }
        var name = bounded(field(command, "ruleName"), 2, 200, "ruleName");
        var operator = oneOf(field(command, "operator"), "operator",
                "lt", "lte", "gt", "gte", "outside_range", "inside_range");
        var lower = decimal(field(command, "thresholdLower"), "thresholdLower");
        var upper = optionalDecimal(command.fields().get("thresholdUpper"), "thresholdUpper");
        if (Set.of("outside_range", "inside_range").contains(operator)) {
            if (upper == null || lower.compareTo(upper) > 0) {
                throw invalid("Range rules require an upper bound not below the lower bound.");
            }
        } else if (upper != null) {
            throw invalid("thresholdUpper is available only for range rules.");
        }
        var severity = oneOf(field(command, "severity"), "severity", "warning", "critical");
        var ownerId = fieldUuid(command, "ownerPractitionerId");
        var priority = oneOf(field(command, "taskPriority"), "taskPriority", "urgent", "critical");
        var acknowledgeMinutes = integer(
                field(command, "acknowledgeWithinMinutes"), "acknowledgeWithinMinutes", 1, 10080);
        var instruction = bounded(field(command, "instruction"), 10, 2000, "instruction");
        var sequence = nextSequence(
                "escalation_rules", "rule_sequence", context.organizationId(), plan.id());
        var ruleId = UuidV7Generator.randomUuid();
        jdbc.update(
                """
                INSERT INTO escalation_rules(
                    id,organization_id,followup_plan_id,outcome_definition_id,rule_sequence,
                    rule_name,operator_key,threshold_lower,threshold_upper,severity_key,
                    owner_practitioner_id,task_priority_key,acknowledge_within_minutes,
                    instruction_text,status,created_by,updated_by)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,'active',?,?)
                """,
                ruleId, context.organizationId(), plan.id(), definitionId, sequence, name,
                operator, lower, upper, severity, ownerId, priority, acknowledgeMinutes,
                instruction, context.actorId(), context.actorId());
        var revision = bumpDraft(context, plan);
        var audit = map(
                "followupPlanId", plan.id(), "outcomeDefinitionId", definitionId,
                "escalationRuleId", ruleId, "severity", severity,
                "operator", operator, "revision", revision);
        return result(
                ruleId, plan.id(), "escalation_rule", "followup.rule_added",
                null, null, null, audit, Map.of(), 201, revision);
    }

    private MutationResult scheduleFollowup(
            AuthorizedTenantContext context, MutationCommand command) {
        var plan = draftPlan(context, command);
        var type = oneOf(field(command, "eventType"), "eventType",
                "baseline", "scheduled", "ad_hoc");
        var scheduledFor = instant(field(command, "scheduledFor"), "scheduledFor");
        var dueAt = instant(field(command, "dueAt"), "dueAt");
        if (dueAt.isBefore(scheduledFor)) {
            throw invalid("dueAt must not be before scheduledFor.");
        }
        if (dueAt.isAfter(command.now().plusSeconds(366L * 86400L))) {
            throw invalid("dueAt must be within 366 days.");
        }
        var ownerId = fieldUuid(command, "ownerPractitionerId");
        var sequence = nextSequence(
                "followup_events", "event_sequence", context.organizationId(), plan.id());
        var eventId = UuidV7Generator.randomUuid();
        jdbc.update(
                """
                INSERT INTO followup_events(
                    id,organization_id,followup_plan_id,event_sequence,event_type,
                    scheduled_for,due_at,owner_practitioner_id,status,created_by,updated_by)
                VALUES (?,?,?,?,?,?,?,?, 'scheduled',?,?)
                """,
                eventId, context.organizationId(), plan.id(), sequence, type,
                Timestamp.from(scheduledFor), Timestamp.from(dueAt), ownerId,
                context.actorId(), context.actorId());
        var revision = bumpDraft(context, plan);
        var audit = map(
                "followupPlanId", plan.id(), "followupEventId", eventId,
                "eventType", type, "status", "scheduled", "revision", revision);
        var outbox = map(
                "followupPlanId", plan.id(), "followupEventId", eventId,
                "eventType", type, "status", "scheduled");
        return result(
                eventId, plan.id(), "followup_event", "followup.schedule_changed",
                "m10.followup-scheduled.v1", "followup_plan", plan.id(),
                audit, outbox, 201, revision);
    }

    private MutationResult recordMeasurement(
            AuthorizedTenantContext context, MutationCommand command) {
        var event = lockEvent(context, command.targetId());
        requireRevision(event.revision(), command.expectedRevision(), "follow-up event");
        if (!Set.of("scheduled", "due").contains(event.status())) {
            throw conflict("Measurements require a scheduled or due follow-up event.");
        }
        if ((event.eventType().equals("baseline") && !event.planStatus().equals("draft"))
                || (!event.eventType().equals("baseline") && !event.planStatus().equals("active"))) {
            throw conflict("The event and monitoring plan are not in a measurable state.");
        }
        var definitionId = fieldUuid(command, "outcomeDefinitionId");
        var definition = definition(context, event.planId(), definitionId);
        var value = decimal(field(command, "numericValue"), "numericValue");
        var unit = unit(field(command, "unitCode"), "unitCode");
        if (!definition.unit().equals(unit)) {
            throw conflict("The recorded unit does not match the frozen outcome definition.");
        }
        var observedAt = instant(field(command, "observedAt"), "observedAt");
        if (observedAt.isAfter(command.now().plusSeconds(5))
                || observedAt.isBefore(command.now().minusSeconds(366L * 86400L))) {
            throw invalid("observedAt must be within the permitted clinical evidence window.");
        }
        var source = code(field(command, "sourceKey"), "sourceKey", 2, 80);
        var method = code(field(command, "methodKey"), "methodKey", 2, 80);
        var practitionerId = fieldUuid(command, "recordedByPractitionerId");
        var notes = optional(command.fields().get("notes"), 2000, "notes");
        if (notes != null && notes.codePointCount(0, notes.length()) < 2) {
            throw invalid("notes must contain at least 2 characters when supplied.");
        }
        var sequence = Objects.requireNonNull(jdbc.queryForObject(
                """
                SELECT coalesce(max(measurement_sequence),0)+1 FROM outcome_measurements
                WHERE organization_id=? AND followup_event_id=? AND outcome_definition_id=?
                """,
                Integer.class, context.organizationId(), event.id(), definitionId));
        var measurementId = UuidV7Generator.randomUuid();
        jdbc.update(
                """
                INSERT INTO outcome_measurements(
                    id,organization_id,followup_plan_id,followup_event_id,
                    outcome_definition_id,measurement_sequence,numeric_value,unit_code,
                    observed_at,source_key,method_key,recorded_by_practitioner_id,
                    notes_text,status,created_by,updated_by)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,'final',?,?)
                """,
                measurementId, context.organizationId(), event.planId(), event.id(),
                definitionId, sequence, value, unit, Timestamp.from(observedAt), source,
                method, practitionerId, notes, context.actorId(), context.actorId());

        var rules = rules(context, event.planId(), definitionId);
        var breaches = rules.stream().filter(rule -> breached(rule, value)).toList();
        for (var rule : breaches) {
            createEscalation(context, command, event, measurementId, value, rule);
        }
        requireChanged(jdbc.update(
                """
                UPDATE followup_events SET status='completed',completed_at=?,
                    completed_by_practitioner_id=?,completion_reason=?,
                    lock_version=lock_version+1,updated_at=clock_timestamp(),updated_by=?
                WHERE organization_id=? AND id=? AND lock_version=? AND status IN ('scheduled','due')
                """,
                Timestamp.from(command.now()), practitionerId, command.reason(), context.actorId(),
                context.organizationId(), event.id(), event.revision()),
                "The follow-up event changed before measurement completion.");
        var revision = event.revision() + 1;
        var audit = map(
                "followupPlanId", event.planId(), "followupEventId", event.id(),
                "outcomeDefinitionId", definitionId, "outcomeMeasurementId", measurementId,
                "breachCount", breaches.size(), "revision", revision);
        String outboxEvent;
        Map<String, Object> outbox;
        if (breaches.isEmpty()) {
            outboxEvent = "m10.outcome-measurement-recorded.v1";
            outbox = map(
                    "followupPlanId", event.planId(), "followupEventId", event.id(),
                    "outcomeDefinitionId", definitionId, "outcomeMeasurementId", measurementId,
                    "breachCount", 0);
        } else {
            outboxEvent = "m10.outcome-threshold-breached.v1";
            var highest = breaches.stream().anyMatch(rule -> rule.severity().equals("critical"))
                    ? "critical" : "warning";
            outbox = map(
                    "followupPlanId", event.planId(), "outcomeMeasurementId", measurementId,
                    "breachCount", breaches.size(), "highestSeverity", highest);
        }
        return result(
                measurementId, event.planId(), "outcome_measurement",
                "followup.measurement_recorded", outboxEvent, "followup_plan", event.planId(),
                audit, outbox, 201, revision);
    }

    private void createEscalation(
            AuthorizedTenantContext context,
            MutationCommand command,
            EventRecord event,
            UUID measurementId,
            BigDecimal value,
            RuleRecord rule) {
        var taskId = UuidV7Generator.randomUuid();
        var dueAt = command.now().plusSeconds(rule.acknowledgeWithinMinutes() * 60L);
        var description = bounded(
                "Outcome threshold breached: " + rule.name() + ". " + rule.instruction(),
                2, 2000, "generated escalation task");
        jdbc.update(
                """
                INSERT INTO clinical_tasks(
                    id,organization_id,encounter_id,patient_id,task_type_key,
                    description_text,priority_key,owner_practitioner_id,
                    requires_acknowledgement,status,due_at,followup_plan_id,
                    outcome_measurement_id,escalation_rule_id,created_by,updated_by)
                VALUES (?,?,?,?,'followup_escalation',?,?,?,true,'open',?,?,?,?,?,?)
                """,
                taskId, context.organizationId(), event.encounterId(), event.patientId(),
                description, rule.priority(), rule.ownerId(), Timestamp.from(dueAt),
                event.planId(), measurementId, rule.id(), context.actorId(), context.actorId());
        var escalationId = UuidV7Generator.randomUuid();
        jdbc.update(
                """
                INSERT INTO escalation_events(
                    id,organization_id,followup_plan_id,outcome_measurement_id,
                    outcome_definition_id,escalation_rule_id,clinical_task_id,severity_key,
                    observed_value,threshold_snapshot,owner_practitioner_id,triggered_at,
                    status,created_by,updated_by)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,'open',?,?)
                """,
                escalationId, context.organizationId(), event.planId(), measurementId,
                rule.definitionId(), rule.id(), taskId, rule.severity(), value,
                thresholdSnapshot(rule), rule.ownerId(), Timestamp.from(command.now()),
                context.actorId(), context.actorId());
    }

    private MutationResult acknowledgeEscalation(
            AuthorizedTenantContext context, MutationCommand command) {
        var escalation = lockEscalation(context, command.targetId());
        requireRevision(escalation.revision(), command.expectedRevision(), "escalation");
        if (!escalation.status().equals("open")) {
            throw conflict("Only an open escalation can be acknowledged.");
        }
        var practitionerId = fieldUuid(command, "practitionerId");
        if (!practitionerId.equals(escalation.ownerId())) {
            throw conflict("Only the assigned escalation owner can acknowledge this escalation.");
        }
        requireChanged(jdbc.update(
                """
                UPDATE clinical_tasks SET status='acknowledged',acknowledged_at=?,
                    acknowledged_by_practitioner_id=?,acknowledgement_reason=?,
                    lock_version=lock_version+1,updated_at=clock_timestamp(),updated_by=?
                WHERE organization_id=? AND id=? AND lock_version=? AND status='open'
                """,
                Timestamp.from(command.now()), practitionerId, command.reason(), context.actorId(),
                context.organizationId(), escalation.taskId(), escalation.taskRevision()),
                "The owned escalation task changed before acknowledgement.");
        requireChanged(jdbc.update(
                """
                UPDATE escalation_events SET status='acknowledged',acknowledged_at=?,
                    acknowledged_by_practitioner_id=?,acknowledgement_reason=?,
                    lock_version=lock_version+1,updated_at=clock_timestamp(),updated_by=?
                WHERE organization_id=? AND id=? AND lock_version=? AND status='open'
                """,
                Timestamp.from(command.now()), practitionerId, command.reason(), context.actorId(),
                context.organizationId(), escalation.id(), escalation.revision()),
                "The escalation changed before acknowledgement.");
        var revision = escalation.revision() + 1;
        var audit = escalationPayload(escalation, "acknowledged", revision);
        var outbox = escalationPayload(escalation, "acknowledged", null);
        return result(
                escalation.id(), escalation.planId(), "escalation_event",
                "followup.escalation_acknowledged", "m10.escalation-acknowledged.v1",
                "followup_plan", escalation.planId(), audit, outbox, 200, revision);
    }

    private MutationResult resolveEscalation(
            AuthorizedTenantContext context, MutationCommand command) {
        var escalation = lockEscalation(context, command.targetId());
        requireRevision(escalation.revision(), command.expectedRevision(), "escalation");
        if (!escalation.status().equals("acknowledged")) {
            throw conflict("Only an acknowledged escalation can be resolved.");
        }
        var practitionerId = fieldUuid(command, "practitionerId");
        if (!practitionerId.equals(escalation.ownerId())) {
            throw conflict("Only the assigned escalation owner can resolve this escalation.");
        }
        requireChanged(jdbc.update(
                """
                UPDATE clinical_tasks SET status='completed',completed_at=?,completion_reason=?,
                    lock_version=lock_version+1,updated_at=clock_timestamp(),updated_by=?
                WHERE organization_id=? AND id=? AND lock_version=? AND status='acknowledged'
                """,
                Timestamp.from(command.now()), command.reason(), context.actorId(),
                context.organizationId(), escalation.taskId(), escalation.taskRevision()),
                "The owned escalation task changed before resolution.");
        requireChanged(jdbc.update(
                """
                UPDATE escalation_events SET status='resolved',resolved_at=?,
                    resolved_by_practitioner_id=?,resolution_reason=?,
                    lock_version=lock_version+1,updated_at=clock_timestamp(),updated_by=?
                WHERE organization_id=? AND id=? AND lock_version=? AND status='acknowledged'
                """,
                Timestamp.from(command.now()), practitionerId, command.reason(), context.actorId(),
                context.organizationId(), escalation.id(), escalation.revision()),
                "The escalation changed before resolution.");
        var revision = escalation.revision() + 1;
        var audit = escalationPayload(escalation, "resolved", revision);
        var outbox = escalationPayload(escalation, "resolved", null);
        return result(
                escalation.id(), escalation.planId(), "escalation_event",
                "followup.escalation_resolved", "m10.escalation-resolved.v1",
                "followup_plan", escalation.planId(), audit, outbox, 200, revision);
    }

    private Map<String, Object> escalationPayload(
            EscalationRecord escalation, String status, Long revision) {
        return map(
                "followupPlanId", escalation.planId(), "escalationEventId", escalation.id(),
                "clinicalTaskId", escalation.taskId(), "severity", escalation.severity(),
                "status", status, "revision", revision);
    }

    private MutationResult recordInterpretation(
            AuthorizedTenantContext context, MutationCommand command) {
        var measurement = lockMeasurement(context, command.targetId());
        requireRevision(measurement.revision(), command.expectedRevision(), "measurement");
        if (!measurement.planStatus().equals("active") || !measurement.eventStatus().equals("completed")) {
            throw conflict("Interpretation requires a completed measurement on an active plan.");
        }
        var trend = oneOf(field(command, "trend"), "trend",
                "improving", "stable", "worsening", "mixed", "insufficient_data");
        var interpretation = bounded(
                field(command, "interpretation"), 10, 4000, "interpretation");
        var recommendation = bounded(
                field(command, "recommendation"), 10, 2000, "recommendation");
        var practitionerId = fieldUuid(command, "interpretedByPractitionerId");
        var sequence = Objects.requireNonNull(jdbc.queryForObject(
                """
                SELECT coalesce(max(interpretation_sequence),0)+1 FROM interpretations
                WHERE organization_id=? AND followup_plan_id=?
                """,
                Integer.class, context.organizationId(), measurement.planId()));
        var interpretationId = UuidV7Generator.randomUuid();
        jdbc.update(
                """
                INSERT INTO interpretations(
                    id,organization_id,followup_plan_id,followup_event_id,
                    outcome_measurement_id,interpretation_sequence,trend_key,
                    interpretation_text,recommendation_text,interpreted_by_practitioner_id,
                    interpreted_at,reason,status,created_by,updated_by)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,'final',?,?)
                """,
                interpretationId, context.organizationId(), measurement.planId(),
                measurement.eventId(), measurement.id(), sequence, trend, interpretation,
                recommendation, practitionerId, Timestamp.from(command.now()), command.reason(),
                context.actorId(), context.actorId());
        var audit = map(
                "followupPlanId", measurement.planId(), "followupEventId", measurement.eventId(),
                "outcomeMeasurementId", measurement.id(), "interpretationId", interpretationId,
                "trend", trend, "revision", 0L);
        return result(
                interpretationId, measurement.planId(), "interpretation",
                "followup.interpretation_recorded", null, null, null,
                audit, Map.of(), 201, 0);
    }

    private MutationResult submitPlan(
            AuthorizedTenantContext context, MutationCommand command) {
        var plan = lockPlan(context, command.targetId());
        requireRevision(plan.revision(), command.expectedRevision(), "monitoring plan");
        if (!plan.status().equals("draft")) {
            throw conflict("Only a complete draft monitoring plan can be submitted.");
        }
        var digest = planDigest(context.organizationId(), plan.id());
        requireChanged(jdbc.update(
                """
                UPDATE followup_plans SET status='review',plan_digest=?,frozen_at=?,
                    submitted_at=?,submitted_by=?,lock_version=lock_version+1,
                    updated_at=clock_timestamp(),updated_by=?
                WHERE organization_id=? AND id=? AND lock_version=? AND status='draft'
                """,
                digest, Timestamp.from(command.now()), Timestamp.from(command.now()),
                context.actorId(), context.actorId(), context.organizationId(), plan.id(),
                plan.revision()),
                "The monitoring plan changed before submission.");
        var revision = plan.revision() + 1;
        var audit = map(
                "followupPlanId", plan.id(), "carePlanId", plan.carePlanId(),
                "digest", digest, "fromStatus", "draft", "status", "review",
                "revision", revision);
        return result(
                plan.id(), plan.id(), "followup_plan", "followup.submitted",
                null, null, null, audit, Map.of(), 200, revision);
    }

    private MutationResult confirmPlan(
            AuthorizedTenantContext context, MutationCommand command) {
        var plan = lockPlan(context, command.targetId());
        requireRevision(plan.revision(), command.expectedRevision(), "monitoring plan");
        if (!plan.status().equals("review")) {
            throw conflict("Only a submitted monitoring plan can be confirmed.");
        }
        if (command.recentAuthenticationAt() == null || command.mfaAuthenticatedAt() == null) {
            throw conflict("Recent authentication and MFA are required to confirm monitoring.");
        }
        requireChanged(jdbc.update(
                """
                UPDATE followup_plans SET status='active',confirmed_at=?,
                    confirmed_by_practitioner_id=?,recent_authentication_at=?,
                    mfa_authenticated_at=?,confirmation_reason=?,
                    lock_version=lock_version+1,updated_at=clock_timestamp(),updated_by=?
                WHERE organization_id=? AND id=? AND lock_version=? AND status='review'
                """,
                Timestamp.from(command.now()), plan.responsiblePractitionerId(),
                Timestamp.from(command.recentAuthenticationAt()),
                Timestamp.from(command.mfaAuthenticatedAt()), command.reason(), context.actorId(),
                context.organizationId(), plan.id(), plan.revision()),
                "The monitoring plan changed before confirmation.");
        var revision = plan.revision() + 1;
        var audit = map(
                "followupPlanId", plan.id(), "carePlanId", plan.carePlanId(),
                "digest", plan.digest(), "fromStatus", "review", "status", "active",
                "revision", revision);
        var outbox = map(
                "followupPlanId", plan.id(), "carePlanId", plan.carePlanId(),
                "digest", plan.digest(), "status", "active");
        return result(
                plan.id(), plan.id(), "followup_plan", "followup.confirmed",
                "m10.followup-confirmed.v1", "followup_plan", plan.id(),
                audit, outbox, 200, revision);
    }

    private MutationResult closePlan(
            AuthorizedTenantContext context, MutationCommand command) {
        var plan = lockPlan(context, command.targetId());
        requireRevision(plan.revision(), command.expectedRevision(), "monitoring plan");
        var outcome = oneOf(field(command, "outcome"), "outcome", "completed", "cancelled");
        if ((outcome.equals("completed") && !plan.status().equals("active"))
                || (outcome.equals("cancelled")
                        && !Set.of("draft", "review").contains(plan.status()))) {
            throw conflict("The requested monitoring-plan closure is not valid from its current state.");
        }
        requireChanged(jdbc.update(
                """
                UPDATE followup_plans SET status=?,lock_version=lock_version+1,
                    updated_at=clock_timestamp(),updated_by=?
                WHERE organization_id=? AND id=? AND lock_version=? AND status=?
                """,
                outcome, context.actorId(), context.organizationId(), plan.id(),
                plan.revision(), plan.status()),
                "The monitoring plan changed before closure.");
        var revision = plan.revision() + 1;
        var audit = map(
                "followupPlanId", plan.id(), "carePlanId", plan.carePlanId(),
                "fromStatus", plan.status(), "status", outcome, "revision", revision);
        var outbox = map(
                "followupPlanId", plan.id(), "carePlanId", plan.carePlanId(),
                "status", outcome);
        return result(
                plan.id(), plan.id(), "followup_plan", "followup.closed",
                "m10.followup-closed.v1", "followup_plan", plan.id(),
                audit, outbox, 200, revision);
    }

    private List<FollowupScreen.Row> planRows(
            AuthorizedTenantContext context, ScreenQuery query) {
        return jdbc.query(
                """
                SELECT plan.id,plan.care_plan_id,plan.care_plan_version_id,plan.patient_id,
                       plan.encounter_id,plan.responsible_practitioner_id,plan.plan_title,
                       plan.monitoring_purpose,plan.timezone,plan.starts_on,plan.ends_on,
                       plan.plan_digest,plan.status,plan.lock_version,patient.patient_number,
                       (SELECT count(*) FROM outcome_definitions definition
                        WHERE definition.organization_id=plan.organization_id
                          AND definition.followup_plan_id=plan.id) definition_count,
                       (SELECT count(*) FROM escalation_rules rule
                        WHERE rule.organization_id=plan.organization_id
                          AND rule.followup_plan_id=plan.id) rule_count,
                       (SELECT count(*) FROM followup_events event
                        WHERE event.organization_id=plan.organization_id
                          AND event.followup_plan_id=plan.id) event_count,
                       (SELECT count(*) FROM outcome_measurements measurement
                        WHERE measurement.organization_id=plan.organization_id
                          AND measurement.followup_plan_id=plan.id) measurement_count,
                       (SELECT count(*) FROM escalation_events escalation
                        WHERE escalation.organization_id=plan.organization_id
                          AND escalation.followup_plan_id=plan.id
                          AND escalation.status<>'resolved') open_escalation_count,
                       (SELECT count(*) FROM interpretations interpretation
                        WHERE interpretation.organization_id=plan.organization_id
                          AND interpretation.followup_plan_id=plan.id) interpretation_count
                FROM followup_plans plan
                JOIN patient_profiles patient
                  ON patient.organization_id=plan.organization_id AND patient.id=plan.patient_id
                WHERE plan.organization_id=?
                  AND (?::uuid IS NULL OR plan.patient_id=?::uuid)
                  AND (?::uuid IS NULL OR plan.encounter_id=?::uuid)
                  AND (?::uuid IS NULL OR plan.id=?::uuid)
                  AND (?::text IS NULL OR plan.status=?::text)
                  AND (?::text IS NULL OR plan.plan_title ILIKE '%%'||?::text||'%%')
                ORDER BY plan.updated_at DESC,plan.id
                LIMIT ?
                """,
                (resultSet, rowNumber) -> row(
                        query.screenId(), resultSet.getObject("id", UUID.class),
                        resultSet.getObject("patient_id", UUID.class),
                        resultSet.getObject("encounter_id", UUID.class),
                        resultSet.getObject("care_plan_id", UUID.class),
                        resultSet.getObject("care_plan_version_id", UUID.class),
                        resultSet.getObject("id", UUID.class), null, null, null, null, null,
                        resultSet.getString("status"), resultSet.getLong("lock_version"),
                        values(
                                "patient", maskPatientNumber(resultSet.getString("patient_number")),
                                "plan", resultSet.getString("plan_title"),
                                "purpose", resultSet.getString("monitoring_purpose"),
                                "timezone", resultSet.getString("timezone"),
                                "startsOn", resultSet.getObject("starts_on").toString(),
                                "endsOn", string(resultSet.getObject("ends_on")),
                                "responsibleClinician", resultSet.getObject("responsible_practitioner_id").toString(),
                                "domains", Long.toString(resultSet.getLong("definition_count")),
                                "rules", Long.toString(resultSet.getLong("rule_count")),
                                "events", Long.toString(resultSet.getLong("event_count")),
                                "measurements", Long.toString(resultSet.getLong("measurement_count")),
                                "openEscalations", Long.toString(resultSet.getLong("open_escalation_count")),
                                "interpretations", Long.toString(resultSet.getLong("interpretation_count")),
                                "digest", safe(resultSet.getString("plan_digest"), "Draft not frozen"))),
                context.organizationId(),
                query.patientId(), query.patientId(),
                query.encounterId(), query.encounterId(),
                query.followupPlanId(), query.followupPlanId(),
                query.status(), query.status(), query.search(), query.search(), query.limit());
    }

    private List<FollowupScreen.Row> eventRows(
            AuthorizedTenantContext context, ScreenQuery query) {
        return jdbc.query(
                """
                SELECT event.id,event.followup_plan_id,event.event_type,event.scheduled_for,
                       event.due_at,event.owner_practitioner_id,event.status,event.lock_version,
                       plan.care_plan_id,plan.care_plan_version_id,plan.patient_id,
                       plan.encounter_id,plan.plan_title,plan.status plan_status,
                       patient.patient_number,
                       (SELECT count(*) FROM outcome_measurements measurement
                        WHERE measurement.organization_id=event.organization_id
                          AND measurement.followup_event_id=event.id) measurement_count
                FROM followup_events event
                JOIN followup_plans plan
                  ON plan.organization_id=event.organization_id AND plan.id=event.followup_plan_id
                JOIN patient_profiles patient
                  ON patient.organization_id=plan.organization_id AND patient.id=plan.patient_id
                WHERE event.organization_id=?
                  AND (?::uuid IS NULL OR plan.patient_id=?::uuid)
                  AND (?::uuid IS NULL OR plan.encounter_id=?::uuid)
                  AND (?::uuid IS NULL OR plan.id=?::uuid)
                  AND (?::text IS NULL OR event.status=?::text)
                  AND (?::text IS NULL OR plan.plan_title ILIKE '%%'||?::text||'%%')
                ORDER BY event.scheduled_for DESC,event.id
                LIMIT ?
                """,
                (resultSet, rowNumber) -> row(
                        query.screenId(), resultSet.getObject("id", UUID.class),
                        resultSet.getObject("patient_id", UUID.class),
                        resultSet.getObject("encounter_id", UUID.class),
                        resultSet.getObject("care_plan_id", UUID.class),
                        resultSet.getObject("care_plan_version_id", UUID.class),
                        resultSet.getObject("followup_plan_id", UUID.class),
                        resultSet.getObject("id", UUID.class), null, null, null, null,
                        resultSet.getString("status"), resultSet.getLong("lock_version"),
                        values(
                                "patient", maskPatientNumber(resultSet.getString("patient_number")),
                                "plan", resultSet.getString("plan_title"),
                                "planStatus", resultSet.getString("plan_status"),
                                "eventType", resultSet.getString("event_type"),
                                "scheduledFor", resultSet.getTimestamp("scheduled_for").toInstant().toString(),
                                "dueAt", resultSet.getTimestamp("due_at").toInstant().toString(),
                                "owner", resultSet.getObject("owner_practitioner_id").toString(),
                                "measurements", Long.toString(resultSet.getLong("measurement_count")))),
                context.organizationId(), query.patientId(), query.patientId(),
                query.encounterId(), query.encounterId(), query.followupPlanId(),
                query.followupPlanId(), query.status(), query.status(), query.search(),
                query.search(), query.limit());
    }

    private List<FollowupScreen.Row> escalationRows(
            AuthorizedTenantContext context, ScreenQuery query) {
        return jdbc.query(
                """
                SELECT escalation.id,escalation.followup_plan_id,
                       escalation.outcome_measurement_id,escalation.outcome_definition_id,
                       escalation.clinical_task_id,escalation.severity_key,
                       escalation.observed_value,escalation.threshold_snapshot,
                       escalation.owner_practitioner_id,escalation.triggered_at,
                       escalation.status,escalation.lock_version,
                       plan.care_plan_id,plan.care_plan_version_id,plan.patient_id,
                       plan.encounter_id,plan.plan_title,patient.patient_number
                FROM escalation_events escalation
                JOIN followup_plans plan
                  ON plan.organization_id=escalation.organization_id
                 AND plan.id=escalation.followup_plan_id
                JOIN patient_profiles patient
                  ON patient.organization_id=plan.organization_id AND patient.id=plan.patient_id
                WHERE escalation.organization_id=?
                  AND (?::uuid IS NULL OR plan.patient_id=?::uuid)
                  AND (?::uuid IS NULL OR plan.encounter_id=?::uuid)
                  AND (?::uuid IS NULL OR plan.id=?::uuid)
                  AND (?::text IS NULL OR escalation.status=?::text)
                  AND (?::text IS NULL OR plan.plan_title ILIKE '%%'||?::text||'%%')
                ORDER BY escalation.triggered_at DESC,escalation.id
                LIMIT ?
                """,
                (resultSet, rowNumber) -> row(
                        query.screenId(), resultSet.getObject("id", UUID.class),
                        resultSet.getObject("patient_id", UUID.class),
                        resultSet.getObject("encounter_id", UUID.class),
                        resultSet.getObject("care_plan_id", UUID.class),
                        resultSet.getObject("care_plan_version_id", UUID.class),
                        resultSet.getObject("followup_plan_id", UUID.class), null,
                        resultSet.getObject("outcome_definition_id", UUID.class),
                        resultSet.getObject("outcome_measurement_id", UUID.class),
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("clinical_task_id", UUID.class),
                        resultSet.getString("status"), resultSet.getLong("lock_version"),
                        values(
                                "patient", maskPatientNumber(resultSet.getString("patient_number")),
                                "plan", resultSet.getString("plan_title"),
                                "severity", resultSet.getString("severity_key"),
                                "observedValue", resultSet.getBigDecimal("observed_value").toPlainString(),
                                "threshold", resultSet.getString("threshold_snapshot"),
                                "owner", resultSet.getObject("owner_practitioner_id").toString(),
                                "triggeredAt", resultSet.getTimestamp("triggered_at").toInstant().toString())),
                context.organizationId(), query.patientId(), query.patientId(),
                query.encounterId(), query.encounterId(), query.followupPlanId(),
                query.followupPlanId(), query.status(), query.status(), query.search(),
                query.search(), query.limit());
    }

    private List<FollowupScreen.Row> measurementRows(
            AuthorizedTenantContext context, ScreenQuery query) {
        return jdbc.query(
                """
                SELECT measurement.id,measurement.followup_plan_id,measurement.followup_event_id,
                       measurement.outcome_definition_id,measurement.numeric_value,
                       measurement.unit_code,measurement.observed_at,measurement.source_key,
                       measurement.method_key,measurement.status,measurement.lock_version,
                       definition.measure_display,plan.care_plan_id,plan.care_plan_version_id,
                       plan.patient_id,plan.encounter_id,plan.plan_title,plan.status plan_status,
                       patient.patient_number,
                       (SELECT count(*) FROM interpretations interpretation
                        WHERE interpretation.organization_id=measurement.organization_id
                          AND interpretation.outcome_measurement_id=measurement.id) interpretation_count
                FROM outcome_measurements measurement
                JOIN outcome_definitions definition
                  ON definition.organization_id=measurement.organization_id
                 AND definition.id=measurement.outcome_definition_id
                JOIN followup_plans plan
                  ON plan.organization_id=measurement.organization_id
                 AND plan.id=measurement.followup_plan_id
                JOIN patient_profiles patient
                  ON patient.organization_id=plan.organization_id AND patient.id=plan.patient_id
                WHERE measurement.organization_id=?
                  AND (?::uuid IS NULL OR plan.patient_id=?::uuid)
                  AND (?::uuid IS NULL OR plan.encounter_id=?::uuid)
                  AND (?::uuid IS NULL OR plan.id=?::uuid)
                  AND (?::text IS NULL OR measurement.status=?::text)
                  AND (?::text IS NULL OR plan.plan_title ILIKE '%%'||?::text||'%%')
                ORDER BY measurement.observed_at DESC,measurement.id
                LIMIT ?
                """,
                (resultSet, rowNumber) -> row(
                        query.screenId(), resultSet.getObject("id", UUID.class),
                        resultSet.getObject("patient_id", UUID.class),
                        resultSet.getObject("encounter_id", UUID.class),
                        resultSet.getObject("care_plan_id", UUID.class),
                        resultSet.getObject("care_plan_version_id", UUID.class),
                        resultSet.getObject("followup_plan_id", UUID.class),
                        resultSet.getObject("followup_event_id", UUID.class),
                        resultSet.getObject("outcome_definition_id", UUID.class),
                        resultSet.getObject("id", UUID.class), null, null,
                        resultSet.getString("status"), resultSet.getLong("lock_version"),
                        values(
                                "patient", maskPatientNumber(resultSet.getString("patient_number")),
                                "plan", resultSet.getString("plan_title"),
                                "planStatus", resultSet.getString("plan_status"),
                                "measure", resultSet.getString("measure_display"),
                                "value", resultSet.getBigDecimal("numeric_value").toPlainString(),
                                "unit", resultSet.getString("unit_code"),
                                "observedAt", resultSet.getTimestamp("observed_at").toInstant().toString(),
                                "source", resultSet.getString("source_key"),
                                "method", resultSet.getString("method_key"),
                                "interpretations", Long.toString(resultSet.getLong("interpretation_count")))),
                context.organizationId(), query.patientId(), query.patientId(),
                query.encounterId(), query.encounterId(), query.followupPlanId(),
                query.followupPlanId(), query.status(), query.status(), query.search(),
                query.search(), query.limit());
    }

    private List<FollowupScreen.Metric> metrics(AuthorizedTenantContext context) {
        var values = jdbc.queryForMap(
                """
                SELECT (SELECT count(*) FROM followup_plans WHERE organization_id=?) plans,
                       (SELECT count(*) FROM followup_plans WHERE organization_id=? AND status='active') active,
                       (SELECT count(*) FROM followup_events WHERE organization_id=?
                            AND status IN ('scheduled','due')) pending_events,
                       (SELECT count(*) FROM outcome_measurements WHERE organization_id=?) measurements,
                       (SELECT count(*) FROM escalation_events WHERE organization_id=?
                            AND status<>'resolved') open_escalations
                """,
                context.organizationId(), context.organizationId(), context.organizationId(),
                context.organizationId(), context.organizationId());
        return List.of(
                metric("plans", "Monitoring plans", number(values.get("plans")), "neutral"),
                metric("active", "Active plans", number(values.get("active")), "success"),
                metric("pendingEvents", "Pending follow-ups", number(values.get("pending_events")), "warning"),
                metric("measurements", "Measurements", number(values.get("measurements")), "neutral"),
                metric("openEscalations", "Open escalations", number(values.get("open_escalations")), "danger"));
    }

    private static List<FollowupScreen.Column> columns(String screenId) {
        return switch (screenId) {
            case "P10-02" -> List.of(column("patient", "Patient"), column("plan", "Plan"),
                    column("rules", "Rules"), column("openEscalations", "Open escalations"));
            case "P10-03" -> List.of(column("patient", "Patient"), column("plan", "Plan"),
                    column("domains", "Measures"), column("measurements", "Measurements"));
            case "P10-04" -> List.of(column("patient", "Patient"), column("plan", "Plan"),
                    column("eventType", "Event"), column("scheduledFor", "Scheduled"),
                    column("measurements", "Measurements"));
            case "P10-05" -> List.of(column("patient", "Patient"), column("plan", "Plan"),
                    column("severity", "Severity"), column("observedValue", "Observed"),
                    column("threshold", "Threshold"), column("owner", "Owner"));
            case "P10-06" -> List.of(column("patient", "Patient"), column("plan", "Plan"),
                    column("events", "Events"), column("timezone", "Timezone"));
            case "P10-07" -> List.of(column("patient", "Patient"), column("plan", "Plan"),
                    column("measure", "Measure"), column("value", "Value"),
                    column("observedAt", "Observed"), column("interpretations", "Interpretations"));
            case "P10-08" -> List.of(column("patient", "Patient"), column("plan", "Plan"),
                    column("domains", "Measures"), column("rules", "Rules"),
                    column("events", "Events"), column("digest", "Frozen digest"));
            case "P10-09" -> List.of(column("patient", "Patient"), column("plan", "Plan"),
                    column("measurements", "Measurements"), column("interpretations", "Interpretations"),
                    column("openEscalations", "Open escalations"));
            default -> List.of(column("patient", "Patient"), column("plan", "Plan"),
                    column("purpose", "Monitoring purpose"), column("responsibleClinician", "Owner"));
        };
    }

    private static List<FollowupScreen.Notice> notices(String screenId) {
        var notices = new ArrayList<FollowupScreen.Notice>();
        notices.add(notice(
                "warning", "Exact active care-plan binding",
                "Monitoring is bound to one active care plan and version; submission freezes definitions, rules, schedule and required baseline evidence."));
        notices.add(notice(
                "neutral", "Measurement interpretation remains clinical",
                "Threshold automation creates owned work but does not replace attributable clinical interpretation or local policy."));
        if (Set.of("P10-04", "P10-05", "P10-08", "P10-09").contains(screenId)) {
            notices.add(notice(
                    "danger", "Every breach creates owned work",
                    "A matching threshold breach atomically creates an escalation record and acknowledgement-required clinical task."));
        }
        return List.copyOf(notices);
    }

    private static FollowupScreen.Row withAllowedActions(
            String screenId, FollowupScreen.Row row) {
        var actions = new ArrayList<String>();
        switch (screenId) {
            case "P10-02" -> { if (row.status().equals("draft")) actions.add("add-rule"); }
            case "P10-03" -> { if (row.status().equals("draft")) actions.add("add-domain"); }
            case "P10-04" -> {
                if (Set.of("scheduled", "due").contains(row.status())) {
                    actions.add("record-measurement");
                }
            }
            case "P10-05" -> {
                if (row.status().equals("open")) actions.add("acknowledge-escalation");
                else if (row.status().equals("acknowledged")) actions.add("resolve-escalation");
            }
            case "P10-06" -> { if (row.status().equals("draft")) actions.add("schedule-followup"); }
            case "P10-07" -> {
                if (row.status().equals("final") && "active".equals(row.values().get("planStatus"))) {
                    actions.add("record-interpretation");
                }
            }
            case "P10-08" -> {
                if (row.status().equals("draft")) actions.add("submit-followup-plan");
                else if (row.status().equals("review")) actions.add("confirm-followup-plan");
            }
            case "P10-09" -> {
                if (!PLAN_TERMINAL.contains(row.status())) actions.add("close-followup-plan");
            }
            default -> { /* Dashboard creation is target-free. */ }
        }
        return new FollowupScreen.Row(
                row.id(), row.patientId(), row.encounterId(), row.carePlanId(),
                row.carePlanVersionId(), row.followupPlanId(), row.followupEventId(),
                row.outcomeDefinitionId(), row.outcomeMeasurementId(),
                row.escalationEventId(), row.clinicalTaskId(), row.status(), row.revision(),
                row.etag(), row.values(), List.copyOf(actions));
    }

    private static FollowupScreen.Row row(
            String screenId,
            UUID id,
            UUID patientId,
            UUID encounterId,
            UUID carePlanId,
            UUID carePlanVersionId,
            UUID followupPlanId,
            UUID followupEventId,
            UUID outcomeDefinitionId,
            UUID outcomeMeasurementId,
            UUID escalationEventId,
            UUID clinicalTaskId,
            String status,
            long revision,
            Map<String, String> values) {
        return new FollowupScreen.Row(
                id, patientId, encounterId, carePlanId, carePlanVersionId, followupPlanId,
                followupEventId, outcomeDefinitionId, outcomeMeasurementId, escalationEventId,
                clinicalTaskId, status, revision,
                "\"m10:" + screenId + ":" + id + ":" + revision + "\"",
                values, List.of());
    }

    private PlanRecord draftPlan(
            AuthorizedTenantContext context, MutationCommand command) {
        var plan = lockPlan(context, command.targetId());
        requireRevision(plan.revision(), command.expectedRevision(), "monitoring plan");
        if (!plan.status().equals("draft")) {
            throw conflict("Monitoring configuration can be appended only while the plan is draft.");
        }
        return plan;
    }

    private PlanRecord lockPlan(AuthorizedTenantContext context, UUID followupPlanId) {
        if (followupPlanId == null) throw invalid("A monitoring-plan target is required.");
        var rows = jdbc.query(
                """
                SELECT id,care_plan_id,care_plan_version_id,patient_id,encounter_id,
                       responsible_practitioner_id,plan_title,status,plan_digest,lock_version
                FROM followup_plans
                WHERE organization_id=? AND id=? FOR UPDATE
                """,
                (resultSet, rowNumber) -> new PlanRecord(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("care_plan_id", UUID.class),
                        resultSet.getObject("care_plan_version_id", UUID.class),
                        resultSet.getObject("patient_id", UUID.class),
                        resultSet.getObject("encounter_id", UUID.class),
                        resultSet.getObject("responsible_practitioner_id", UUID.class),
                        resultSet.getString("plan_title"), resultSet.getString("status"),
                        resultSet.getString("plan_digest"), resultSet.getLong("lock_version")),
                context.organizationId(), followupPlanId);
        if (rows.isEmpty()) throw notFound("The monitoring plan is unavailable.");
        return rows.getFirst();
    }

    private EventRecord lockEvent(AuthorizedTenantContext context, UUID eventId) {
        if (eventId == null) throw invalid("A follow-up event target is required.");
        var rows = jdbc.query(
                """
                SELECT event.id,event.followup_plan_id,event.event_type,event.status,
                       event.lock_version,plan.patient_id,plan.encounter_id,plan.status plan_status
                FROM followup_events event
                JOIN followup_plans plan
                  ON plan.organization_id=event.organization_id AND plan.id=event.followup_plan_id
                WHERE event.organization_id=? AND event.id=? FOR UPDATE OF event
                """,
                (resultSet, rowNumber) -> new EventRecord(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("followup_plan_id", UUID.class),
                        resultSet.getObject("patient_id", UUID.class),
                        resultSet.getObject("encounter_id", UUID.class),
                        resultSet.getString("event_type"), resultSet.getString("status"),
                        resultSet.getString("plan_status"), resultSet.getLong("lock_version")),
                context.organizationId(), eventId);
        if (rows.isEmpty()) throw notFound("The follow-up event is unavailable.");
        return rows.getFirst();
    }

    private EscalationRecord lockEscalation(
            AuthorizedTenantContext context, UUID escalationId) {
        if (escalationId == null) throw invalid("An escalation target is required.");
        var rows = jdbc.query(
                """
                SELECT escalation.id,escalation.followup_plan_id,escalation.clinical_task_id,
                       escalation.owner_practitioner_id,escalation.severity_key,
                       escalation.status,escalation.lock_version,
                       task.lock_version task_revision
                FROM escalation_events escalation
                JOIN clinical_tasks task
                  ON task.organization_id=escalation.organization_id
                 AND task.id=escalation.clinical_task_id
                WHERE escalation.organization_id=? AND escalation.id=?
                FOR UPDATE OF escalation,task
                """,
                (resultSet, rowNumber) -> new EscalationRecord(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("followup_plan_id", UUID.class),
                        resultSet.getObject("clinical_task_id", UUID.class),
                        resultSet.getObject("owner_practitioner_id", UUID.class),
                        resultSet.getString("severity_key"), resultSet.getString("status"),
                        resultSet.getLong("lock_version"), resultSet.getLong("task_revision")),
                context.organizationId(), escalationId);
        if (rows.isEmpty()) throw notFound("The escalation is unavailable.");
        return rows.getFirst();
    }

    private MeasurementRecord lockMeasurement(
            AuthorizedTenantContext context, UUID measurementId) {
        if (measurementId == null) throw invalid("A measurement target is required.");
        var rows = jdbc.query(
                """
                SELECT measurement.id,measurement.followup_plan_id,
                       measurement.followup_event_id,measurement.lock_version,
                       plan.status plan_status,event.status event_status
                FROM outcome_measurements measurement
                JOIN followup_plans plan
                  ON plan.organization_id=measurement.organization_id
                 AND plan.id=measurement.followup_plan_id
                JOIN followup_events event
                  ON event.organization_id=measurement.organization_id
                 AND event.id=measurement.followup_event_id
                WHERE measurement.organization_id=? AND measurement.id=?
                """,
                (resultSet, rowNumber) -> new MeasurementRecord(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("followup_plan_id", UUID.class),
                        resultSet.getObject("followup_event_id", UUID.class),
                        resultSet.getString("plan_status"), resultSet.getString("event_status"),
                        resultSet.getLong("lock_version")),
                context.organizationId(), measurementId);
        if (rows.isEmpty()) throw notFound("The outcome measurement is unavailable.");
        return rows.getFirst();
    }

    private DefinitionRecord definition(
            AuthorizedTenantContext context, UUID planId, UUID definitionId) {
        var rows = jdbc.query(
                """
                SELECT id,unit_code FROM outcome_definitions
                WHERE organization_id=? AND followup_plan_id=? AND id=?
                """,
                (resultSet, rowNumber) -> new DefinitionRecord(
                        resultSet.getObject("id", UUID.class), resultSet.getString("unit_code")),
                context.organizationId(), planId, definitionId);
        if (rows.isEmpty()) throw conflict("The outcome definition is not part of this monitoring plan.");
        return rows.getFirst();
    }

    private List<RuleRecord> rules(
            AuthorizedTenantContext context, UUID planId, UUID definitionId) {
        return jdbc.query(
                """
                SELECT id,outcome_definition_id,rule_name,operator_key,threshold_lower,
                       threshold_upper,severity_key,owner_practitioner_id,task_priority_key,
                       acknowledge_within_minutes,instruction_text
                FROM escalation_rules
                WHERE organization_id=? AND followup_plan_id=?
                  AND outcome_definition_id=? AND status='active'
                ORDER BY rule_sequence,id
                """,
                (resultSet, rowNumber) -> new RuleRecord(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("outcome_definition_id", UUID.class),
                        resultSet.getString("rule_name"), resultSet.getString("operator_key"),
                        resultSet.getBigDecimal("threshold_lower"),
                        resultSet.getBigDecimal("threshold_upper"),
                        resultSet.getString("severity_key"),
                        resultSet.getObject("owner_practitioner_id", UUID.class),
                        resultSet.getString("task_priority_key"),
                        resultSet.getInt("acknowledge_within_minutes"),
                        resultSet.getString("instruction_text")),
                context.organizationId(), planId, definitionId);
    }

    private long bumpDraft(AuthorizedTenantContext context, PlanRecord plan) {
        requireChanged(jdbc.update(
                """
                UPDATE followup_plans SET lock_version=lock_version+1,
                    updated_at=clock_timestamp(),updated_by=?
                WHERE organization_id=? AND id=? AND lock_version=? AND status='draft'
                """,
                context.actorId(), context.organizationId(), plan.id(), plan.revision()),
                "The monitoring plan changed before draft evidence was appended.");
        return plan.revision() + 1;
    }

    private int nextSequence(
            String table, String column, UUID organizationId, UUID planId) {
        if (!Set.of("outcome_definitions", "escalation_rules", "followup_events").contains(table)) {
            throw new IllegalArgumentException("Unsupported follow-up sequence table.");
        }
        var sql = "SELECT coalesce(max(" + column + "),0)+1 FROM " + table
                + " WHERE organization_id=? AND followup_plan_id=?";
        return Objects.requireNonNull(jdbc.queryForObject(
                sql, Integer.class, organizationId, planId));
    }

    private boolean belongsToPlan(
            String table, UUID id, UUID organizationId, UUID planId) {
        if (!table.equals("outcome_definitions")) {
            throw new IllegalArgumentException("Unsupported follow-up ownership table.");
        }
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT count(*)=1 FROM outcome_definitions WHERE organization_id=? AND id=? AND followup_plan_id=?",
                Boolean.class, organizationId, id, planId));
    }

    private String planDigest(UUID organizationId, UUID planId) {
        var digest = jdbc.queryForObject(
                "SELECT careos_m10_followup_digest(?,?)", String.class, organizationId, planId);
        if (digest == null || !digest.matches("[0-9a-f]{64}")) {
            throw conflict("The monitoring-plan digest could not be calculated.");
        }
        return digest;
    }

    private static boolean breached(RuleRecord rule, BigDecimal value) {
        return switch (rule.operator()) {
            case "lt" -> value.compareTo(rule.lower()) < 0;
            case "lte" -> value.compareTo(rule.lower()) <= 0;
            case "gt" -> value.compareTo(rule.lower()) > 0;
            case "gte" -> value.compareTo(rule.lower()) >= 0;
            case "outside_range" -> value.compareTo(rule.lower()) < 0
                    || value.compareTo(rule.upper()) > 0;
            case "inside_range" -> value.compareTo(rule.lower()) >= 0
                    && value.compareTo(rule.upper()) <= 0;
            default -> throw new IllegalStateException("Unsupported stored escalation operator.");
        };
    }

    private static String thresholdSnapshot(RuleRecord rule) {
        return rule.upper() == null
                ? rule.operator() + " " + rule.lower().toPlainString()
                : rule.operator() + " " + rule.lower().toPlainString()
                        + ".." + rule.upper().toPlainString();
    }

    private void requireOperationScope(AuthorizedTenantContext context) {
        var bound = Boolean.TRUE.equals(jdbc.queryForObject(
                """
                SELECT nullif(current_setting('app.current_organization_id',true),'')::uuid=?
                   AND nullif(current_setting('app.current_actor_id',true),'')::uuid=?
                   AND nullif(current_setting('app.current_operation_key',true),'') IS NOT NULL
                """,
                Boolean.class, context.organizationId(), context.actorId()));
        if (!bound) {
            throw notFound("The follow-up resource is unavailable or is not assigned to this account.");
        }
    }

    private Instant databaseNow() {
        return Objects.requireNonNull(jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class))
                .toInstant();
    }

    private static String field(MutationCommand command, String key) {
        var value = command.fields().get(key);
        if (value == null || value.isBlank()) throw invalid(key + " is required.");
        return value.strip();
    }

    private static UUID fieldUuid(MutationCommand command, String key) {
        try {
            return UUID.fromString(field(command, key));
        } catch (IllegalArgumentException exception) {
            throw invalid(key + " must be a UUID.");
        }
    }

    private static String bounded(String value, int minimum, int maximum, String field) {
        if (value == null) throw invalid(field + " is required.");
        var normalized = value.strip();
        var length = normalized.codePointCount(0, normalized.length());
        if (length < minimum || length > maximum) {
            throw invalid(field + " must contain " + minimum + " to " + maximum + " characters.");
        }
        return normalized;
    }

    private static String optional(String value, int maximum, String field) {
        if (value == null || value.isBlank()) return null;
        return bounded(value, 1, maximum, field);
    }

    private static String code(String value, String field, int minimum, int maximum) {
        var normalized = bounded(value, minimum, maximum, field);
        if (!normalized.matches("[a-z][a-z0-9_.:-]*")) {
            throw invalid(field + " has an invalid format.");
        }
        return normalized;
    }

    private static String unit(String value, String field) {
        var normalized = bounded(value, 1, 80, field);
        if (!normalized.matches("[A-Za-z0-9%][A-Za-z0-9%/_.:-]*")) {
            throw invalid(field + " has an invalid format.");
        }
        return normalized;
    }

    private static BigDecimal decimal(String value, String field) {
        try {
            var parsed = new BigDecimal(value.strip());
            if (parsed.precision() > 18 || Math.max(parsed.scale(), 0) > 6) {
                throw invalid(field + " must fit numeric(18,6).");
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw invalid(field + " must be a decimal number.");
        }
    }

    private static BigDecimal optionalDecimal(String value, String field) {
        return value == null || value.isBlank() ? null : decimal(value, field);
    }

    private static int integer(String value, String field, int minimum, int maximum) {
        try {
            var parsed = Integer.parseInt(value.strip());
            if (parsed < minimum || parsed > maximum) throw invalid(field + " is outside its range.");
            return parsed;
        } catch (NumberFormatException exception) {
            throw invalid(field + " must be a whole number.");
        }
    }

    private static boolean bool(String value, String field) {
        if (value.equals("true")) return true;
        if (value.equals("false")) return false;
        throw invalid(field + " must be true or false.");
    }

    private static LocalDate date(String value, String field) {
        try {
            return LocalDate.parse(value);
        } catch (RuntimeException exception) {
            throw invalid(field + " must be an ISO local date.");
        }
    }

    private static LocalDate optionalDate(String value, String field) {
        return value == null || value.isBlank() ? null : date(value.strip(), field);
    }

    private static Instant instant(String value, String field) {
        try {
            if (value.endsWith("Z") || value.matches(".*[+-][0-9]{2}:[0-9]{2}$")) {
                return OffsetDateTime.parse(value).toInstant();
            }
            return LocalDateTime.parse(value).toInstant(ZoneOffset.UTC);
        } catch (RuntimeException exception) {
            throw invalid(field + " must be an ISO date-time.");
        }
    }

    private static String oneOf(String value, String field, String... options) {
        for (var option : options) if (option.equals(value)) return value;
        throw invalid(field + " contains an unsupported value.");
    }

    private static void requireRevision(long actual, Long expected, String target) {
        if (expected == null) {
            throw new FollowupException(
                    FollowupException.Reason.PRECONDITION_REQUIRED,
                    "A strong " + target + " revision is required.");
        }
        if (actual != expected) {
            throw new FollowupException(
                    FollowupException.Reason.STALE,
                    "The " + target + " changed; reload before continuing.");
        }
    }

    private static void requireChanged(int changed, String message) {
        if (changed != 1) throw conflict(message);
    }

    private static Map<String, Object> map(Object... entries) {
        var values = new LinkedHashMap<String, Object>();
        for (var index = 0; index < entries.length; index += 2) {
            if (entries[index + 1] != null) values.put((String) entries[index], entries[index + 1]);
        }
        return Map.copyOf(values);
    }

    private static Map<String, String> values(String... entries) {
        var values = new LinkedHashMap<String, String>();
        for (var index = 0; index < entries.length; index += 2) {
            values.put(entries[index], safe(entries[index + 1], "Not recorded"));
        }
        return Map.copyOf(values);
    }

    private static MutationResult result(
            UUID subjectId,
            UUID followupPlanId,
            String subjectType,
            String auditEvent,
            String outboxEvent,
            String aggregateType,
            UUID outboxAggregateId,
            Map<String, Object> audit,
            Map<String, Object> outbox,
            int statusCode,
            long revision) {
        return new MutationResult(
                subjectId, followupPlanId, subjectType, auditEvent, outboxEvent, aggregateType,
                outboxAggregateId, audit, outbox, statusCode, revision);
    }

    private static FollowupScreen.Column column(String key, String label) {
        return new FollowupScreen.Column(key, label);
    }

    private static FollowupScreen.Metric metric(
            String key, String label, long value, String tone) {
        return new FollowupScreen.Metric(key, label, value, tone);
    }

    private static FollowupScreen.Notice notice(String tone, String title, String detail) {
        return new FollowupScreen.Notice(tone, title, detail);
    }

    private static String maskPatientNumber(String value) {
        if (value == null || value.length() <= 4) return "••••";
        return "••••" + value.substring(value.length() - 4);
    }

    private static String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String string(Object value) {
        return value == null ? "Not recorded" : value.toString();
    }

    private static long number(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private static FollowupException invalid(String message) {
        return new FollowupException(FollowupException.Reason.INVALID, message);
    }

    private static FollowupException notFound(String message) {
        return new FollowupException(FollowupException.Reason.NOT_FOUND, message);
    }

    private static FollowupException conflict(String message) {
        return new FollowupException(FollowupException.Reason.CONFLICT, message);
    }

    private record PlanRecord(
            UUID id,
            UUID carePlanId,
            UUID carePlanVersionId,
            UUID patientId,
            UUID encounterId,
            UUID responsiblePractitionerId,
            String title,
            String status,
            String digest,
            long revision) {}

    private record EventRecord(
            UUID id,
            UUID planId,
            UUID patientId,
            UUID encounterId,
            String eventType,
            String status,
            String planStatus,
            long revision) {}

    private record EscalationRecord(
            UUID id,
            UUID planId,
            UUID taskId,
            UUID ownerId,
            String severity,
            String status,
            long revision,
            long taskRevision) {}

    private record MeasurementRecord(
            UUID id,
            UUID planId,
            UUID eventId,
            String planStatus,
            String eventStatus,
            long revision) {}

    private record DefinitionRecord(UUID id, String unit) {}

    private record RuleRecord(
            UUID id,
            UUID definitionId,
            String name,
            String operator,
            BigDecimal lower,
            BigDecimal upper,
            String severity,
            UUID ownerId,
            String priority,
            int acknowledgeWithinMinutes,
            String instruction) {}
}
