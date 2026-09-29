package com.rootopathy.careos.careplan.infrastructure;

import com.rootopathy.careos.careplan.application.CarePlanException;
import com.rootopathy.careos.careplan.application.CarePlanStore;
import com.rootopathy.careos.careplan.domain.CarePlanScreen;
import com.rootopathy.careos.shared.domain.UuidV7Generator;
import com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext;
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

/** PostgreSQL-backed Module 9 coordinated care-plan, safety and amendment workflow. */
@Repository
public class JdbcCarePlanStore implements CarePlanStore {
    private static final Set<String> PLAN_TERMINAL = Set.of(
            "revised", "completed", "cancelled");

    private final JdbcTemplate jdbc;

    public JdbcCarePlanStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Projection projection(AuthorizedTenantContext context, ScreenQuery query) {
        requireOperationScope(context);
        var rows = planRows(context, query).stream()
                .map(row -> withAllowedActions(query.screenId(), row))
                .limit(query.limit())
                .toList();
        return new Projection(
                metrics(context), columns(query.screenId()), rows, notices(query.screenId()), databaseNow());
    }

    @Override
    public Set<String> permissions(AuthorizedTenantContext context) {
        requireOperationScope(context);
        return Set.copyOf(jdbc.queryForList(
                "SELECT permission_key FROM careos_projected_interactive_permissions(?, ?)",
                String.class,
                context.organizationId(),
                context.actorId()));
    }

    @Override
    public MutationResult mutate(AuthorizedTenantContext context, MutationCommand command) {
        requireOperationScope(context);
        return switch (command.actionKey()) {
            case "create-plan" -> createPlan(context, command);
            case "add-priority" -> addPriority(context, command);
            case "add-goal" -> addGoal(context, command);
            case "add-intervention" -> addIntervention(context, command);
            case "assign-owner-task" -> assignOwnerTask(context, command);
            case "record-consent" -> recordConsent(context, command);
            case "record-interaction-review" -> recordInteractionReview(context, command);
            case "submit-plan" -> submitPlan(context, command);
            case "approve-plan" -> approvePlan(context, command);
            case "activate-plan" -> activatePlan(context, command);
            case "amend-plan" -> amendPlan(context, command);
            case "close-plan" -> closePlan(context, command);
            default -> throw notFound("The requested care-planning action does not exist.");
        };
    }

    private MutationResult createPlan(
            AuthorizedTenantContext context, MutationCommand command) {
        var patientId = fieldUuid(command, "patientId");
        var encounterId = fieldUuid(command, "encounterId");
        var practitionerId = fieldUuid(command, "responsiblePractitionerId");
        var assessmentId = optionalUuid(command, "sourceAssessmentSessionId");
        var aiReviewId = optionalUuid(command, "sourceAiReviewId");
        var title = bounded(field(command, "planTitle"), 2, 200, "planTitle");
        var clinicalSummary = bounded(
                field(command, "clinicalSummary"), 10, 4000, "clinicalSummary");
        var patientSummary = bounded(
                field(command, "patientSummary"), 10, 4000, "patientSummary");
        var planId = UuidV7Generator.randomUuid();
        var versionId = UuidV7Generator.randomUuid();
        jdbc.update(
                """
                INSERT INTO care_plans(
                    id,organization_id,patient_id,encounter_id,responsible_practitioner_id,
                    source_assessment_session_id,source_ai_review_id,plan_title,status,
                    created_by,updated_by)
                VALUES (?,?,?,?,?,?,?,?, 'draft',?,?)
                """,
                planId, context.organizationId(), patientId, encounterId, practitionerId,
                assessmentId, aiReviewId, title, context.actorId(), context.actorId());
        jdbc.update(
                """
                INSERT INTO care_plan_versions(
                    id,organization_id,care_plan_id,version_number,clinical_summary,
                    patient_summary,status,created_by,updated_by)
                VALUES (?,?,?,1,?,?,'draft',?,?)
                """,
                versionId, context.organizationId(), planId, clinicalSummary, patientSummary,
                context.actorId(), context.actorId());
        requireChanged(jdbc.update(
                """
                UPDATE care_plans SET current_version_id=?,lock_version=lock_version+1,
                    updated_at=clock_timestamp(),updated_by=?
                WHERE organization_id=? AND id=? AND lock_version=0 AND current_version_id IS NULL
                """,
                versionId, context.actorId(), context.organizationId(), planId),
                "The care plan changed before its initial version was bound.");
        var payload = map(
                "carePlanId", planId, "carePlanVersionId", versionId,
                "patientId", patientId, "encounterId", encounterId,
                "status", "draft", "revision", 1L);
        var outbox = map(
                "carePlanId", planId, "carePlanVersionId", versionId,
                "patientId", patientId, "encounterId", encounterId, "status", "draft");
        return result(
                planId, planId, "care_plan", "care_plan.created",
                "m9.care-plan-created.v1", "care_plan", planId,
                payload, outbox, 201, 1);
    }

    private MutationResult addPriority(
            AuthorizedTenantContext context, MutationCommand command) {
        var plan = draftPlan(context, command);
        var sourceType = oneOf(field(command, "sourceType"), "sourceType",
                "patient", "assessment", "encounter", "clinician");
        var sourceReference = optionalUuid(command, "sourceReferenceId");
        var codeSystem = optional(command.fields().get("problemCodeSystem"), 160, "problemCodeSystem");
        var problemCode = optional(command.fields().get("problemCode"), 160, "problemCode");
        if ((codeSystem == null) != (problemCode == null)) {
            throw invalid("problemCodeSystem and problemCode must be supplied together.");
        }
        var display = bounded(field(command, "displayText"), 2, 500, "displayText");
        var rationale = bounded(field(command, "rationale"), 10, 2000, "rationale");
        var priority = priority(field(command, "priority"));
        var sequence = nextSequence(
                "care_plan_priorities", "priority_sequence", context.organizationId(),
                plan.versionId());
        var priorityId = UuidV7Generator.randomUuid();
        jdbc.update(
                """
                INSERT INTO care_plan_priorities(
                    id,organization_id,care_plan_version_id,priority_sequence,source_type,
                    source_reference_id,problem_code_system,problem_code,display_text,rationale,
                    priority_key,status,created_by,updated_by)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,'active',?,?)
                """,
                priorityId, context.organizationId(), plan.versionId(), sequence, sourceType,
                sourceReference, codeSystem, problemCode, display, rationale, priority,
                context.actorId(), context.actorId());
        var revision = bumpDraft(context, plan);
        var audit = map(
                "carePlanId", plan.id(), "carePlanVersionId", plan.versionId(),
                "priorityId", priorityId, "sequence", sequence,
                "priority", priority, "revision", revision);
        return result(
                priorityId, plan.id(), "care_plan_priority", "care_plan.priority_added",
                null, null, null, audit, Map.of(), 201, revision);
    }

    private MutationResult addGoal(
            AuthorizedTenantContext context, MutationCommand command) {
        var plan = draftPlan(context, command);
        var goalType = oneOf(field(command, "goalType"), "goalType", "patient_stated", "clinical");
        var description = bounded(field(command, "description"), 10, 2000, "description");
        var measure = bounded(field(command, "measure"), 2, 500, "measure");
        var target = bounded(field(command, "target"), 2, 500, "target");
        var targetDate = optionalDate(command.fields().get("targetDate"), "targetDate");
        var goalPriority = priority(field(command, "priority"));
        var sequence = nextSequence(
                "care_plan_goals", "goal_sequence", context.organizationId(), plan.versionId());
        var goalId = UuidV7Generator.randomUuid();
        jdbc.update(
                """
                INSERT INTO care_plan_goals(
                    id,organization_id,care_plan_version_id,goal_sequence,goal_type,
                    description_text,measure_text,target_text,target_date,priority_key,
                    status,created_by,updated_by)
                VALUES (?,?,?,?,?,?,?,?,?,?,'proposed',?,?)
                """,
                goalId, context.organizationId(), plan.versionId(), sequence, goalType,
                description, measure, target, targetDate == null ? null : Date.valueOf(targetDate),
                goalPriority, context.actorId(), context.actorId());
        var revision = bumpDraft(context, plan);
        var audit = map(
                "carePlanId", plan.id(), "carePlanVersionId", plan.versionId(),
                "goalId", goalId, "sequence", sequence,
                "goalType", goalType, "revision", revision);
        return result(
                goalId, plan.id(), "care_plan_goal", "care_plan.goal_added",
                null, null, null, audit, Map.of(), 201, revision);
    }

    private MutationResult addIntervention(
            AuthorizedTenantContext context, MutationCommand command) {
        var plan = draftPlan(context, command);
        var modality = code(field(command, "modalityKey"), "modalityKey", 2, 80);
        var name = bounded(field(command, "interventionName"), 2, 300, "interventionName");
        var rationale = bounded(field(command, "rationale"), 10, 2000, "rationale");
        var interventionPriority = priority(field(command, "priority"));
        var start = date(field(command, "startDate"), "startDate");
        var review = date(field(command, "reviewDate"), "reviewDate");
        if (review.isBefore(start)) throw invalid("reviewDate must not be before startDate.");
        var stop = bounded(field(command, "stopCriteria"), 10, 2000, "stopCriteria");
        var monitoring = bounded(field(command, "monitoring"), 10, 2000, "monitoring");
        var evidence = oneOf(field(command, "evidenceStatus"), "evidenceStatus",
                "established", "limited", "uncertain", "not_assessed");
        var sequence = nextSequence(
                "care_plan_interventions", "intervention_sequence",
                context.organizationId(), plan.versionId());
        var interventionId = UuidV7Generator.randomUuid();
        jdbc.update(
                """
                INSERT INTO care_plan_interventions(
                    id,organization_id,care_plan_version_id,intervention_sequence,modality_key,
                    intervention_name,rationale,priority_key,planned_start_date,review_date,
                    stop_criteria,monitoring_instructions,evidence_status,status,
                    created_by,updated_by)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,'proposed',?,?)
                """,
                interventionId, context.organizationId(), plan.versionId(), sequence, modality,
                name, rationale, interventionPriority, Date.valueOf(start), Date.valueOf(review),
                stop, monitoring, evidence, context.actorId(), context.actorId());
        var revision = bumpDraft(context, plan);
        var audit = map(
                "carePlanId", plan.id(), "carePlanVersionId", plan.versionId(),
                "interventionId", interventionId, "sequence", sequence,
                "modality", modality, "revision", revision);
        return result(
                interventionId, plan.id(), "care_plan_intervention",
                "care_plan.intervention_added", null, null, null,
                audit, Map.of(), 201, revision);
    }

    private MutationResult assignOwnerTask(
            AuthorizedTenantContext context, MutationCommand command) {
        var plan = draftPlan(context, command);
        var interventionId = fieldUuid(command, "interventionId");
        var ownerId = fieldUuid(command, "ownerPractitionerId");
        var responsibility = bounded(
                field(command, "responsibility"), 10, 1000, "responsibility");
        var start = date(field(command, "startDate"), "startDate");
        var review = date(field(command, "reviewDate"), "reviewDate");
        if (review.isBefore(start)) throw invalid("reviewDate must not be before startDate.");
        var taskDescription = bounded(
                field(command, "taskDescription"), 2, 2000, "taskDescription");
        var taskPriority = oneOf(field(command, "taskPriority"), "taskPriority",
                "routine", "urgent", "critical");
        var dueAt = instant(field(command, "dueAt"), "dueAt");
        if (!dueAt.isAfter(command.now()) || dueAt.isAfter(command.now().plusSeconds(366L * 86400L))) {
            throw invalid("dueAt must be in the future and within 366 days.");
        }
        var belongs = jdbc.queryForObject(
                """
                SELECT count(*)=1 FROM care_plan_interventions
                WHERE organization_id=? AND id=? AND care_plan_version_id=?
                """,
                Boolean.class, context.organizationId(), interventionId, plan.versionId());
        if (!Boolean.TRUE.equals(belongs)) {
            throw conflict("The intervention is not part of the current draft version.");
        }
        var assignmentId = UuidV7Generator.randomUuid();
        jdbc.update(
                """
                INSERT INTO intervention_assignments(
                    id,organization_id,care_plan_version_id,intervention_id,
                    owner_practitioner_id,responsibility_text,assigned_start_date,review_date,
                    status,created_by,updated_by)
                VALUES (?,?,?,?,?,?,?,?,'assigned',?,?)
                """,
                assignmentId, context.organizationId(), plan.versionId(), interventionId,
                ownerId, responsibility, Date.valueOf(start), Date.valueOf(review),
                context.actorId(), context.actorId());
        var taskId = UuidV7Generator.randomUuid();
        jdbc.update(
                """
                INSERT INTO clinical_tasks(
                    id,organization_id,encounter_id,patient_id,task_type_key,
                    description_text,priority_key,owner_practitioner_id,
                    requires_acknowledgement,status,care_plan_id,care_plan_version_id,
                    intervention_assignment_id,due_at,created_by,updated_by)
                VALUES (?,?,?,?,'care_plan_intervention',?,?,?,?, 'open',?,?,?,?,?,?)
                """,
                taskId, context.organizationId(), plan.encounterId(), plan.patientId(),
                taskDescription, taskPriority, ownerId, true,
                plan.id(), plan.versionId(), assignmentId, Timestamp.from(dueAt),
                context.actorId(), context.actorId());
        var revision = bumpDraft(context, plan);
        var audit = map(
                "carePlanId", plan.id(), "carePlanVersionId", plan.versionId(),
                "interventionId", interventionId, "assignmentId", assignmentId,
                "clinicalTaskId", taskId, "revision", revision);
        var outbox = map(
                "carePlanId", plan.id(), "interventionId", interventionId,
                "assignmentId", assignmentId, "clinicalTaskId", taskId);
        return result(
                assignmentId, plan.id(), "intervention_assignment",
                "care_plan.assignment_recorded", "m9.care-plan-task-assigned.v1",
                "care_plan", plan.id(), audit, outbox, 201, revision);
    }

    private MutationResult recordConsent(
            AuthorizedTenantContext context, MutationCommand command) {
        var plan = draftPlan(context, command);
        var status = oneOf(field(command, "consentStatus"), "consentStatus",
                "granted", "not_required", "refused", "withdrawn");
        var reference = optional(
                command.fields().get("consentReference"), 240, "consentReference");
        if (status.equals("granted") && reference == null) {
            throw invalid("consentReference is required when consent is granted.");
        }
        if (!status.equals("granted") && reference != null) {
            throw invalid("consentReference is available only when consent is granted.");
        }
        var preferences = bounded(field(command, "preferences"), 2, 3000, "preferences");
        var communication = optional(
                command.fields().get("communicationNeeds"), 1000, "communicationNeeds");
        var practitionerId = fieldUuid(command, "recordedByPractitionerId");
        var version = jdbc.queryForObject(
                """
                SELECT coalesce(max(consent_version),0)+1 FROM plan_consents
                WHERE organization_id=? AND care_plan_version_id=?
                """,
                Integer.class, context.organizationId(), plan.versionId());
        var consentId = UuidV7Generator.randomUuid();
        jdbc.update(
                """
                INSERT INTO plan_consents(
                    id,organization_id,care_plan_version_id,consent_version,consent_status,
                    consent_reference,preferences_text,communication_needs,
                    recorded_by_practitioner_id,recorded_at,reason,created_by,updated_by)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                consentId, context.organizationId(), plan.versionId(), version, status,
                reference, preferences, communication, practitionerId,
                Timestamp.from(command.now()), command.reason(), context.actorId(), context.actorId());
        var revision = bumpDraft(context, plan);
        var audit = map(
                "carePlanId", plan.id(), "carePlanVersionId", plan.versionId(),
                "planConsentId", consentId, "consentVersion", version,
                "consentStatus", status, "revision", revision);
        return result(
                consentId, plan.id(), "plan_consent", "care_plan.consent_recorded",
                null, null, null, audit, Map.of(), 201, revision);
    }

    private MutationResult recordInteractionReview(
            AuthorizedTenantContext context, MutationCommand command) {
        var plan = draftPlan(context, command);
        var modalities = codes(field(command, "modalities"), "modalities", 1, 32);
        var findings = bounded(
                field(command, "interactionFindings"), 10, 4000, "interactionFindings");
        var outcome = oneOf(field(command, "safetyOutcome"), "safetyOutcome",
                "clear", "needs_changes", "unsafe");
        var reviewerId = fieldUuid(command, "reviewedByPractitionerId");
        var digest = planDigest(context.organizationId(), plan.versionId());
        var version = jdbc.queryForObject(
                """
                SELECT coalesce(max(review_version),0)+1 FROM interaction_reviews
                WHERE organization_id=? AND care_plan_version_id=?
                """,
                Integer.class, context.organizationId(), plan.versionId());
        var reviewId = UuidV7Generator.randomUuid();
        jdbc.update(
                """
                INSERT INTO interaction_reviews(
                    id,organization_id,care_plan_version_id,review_version,
                    reviewed_version_digest,modalities,interaction_findings,safety_outcome,
                    reviewed_by_practitioner_id,reviewed_at,reason,created_by,updated_by)
                VALUES (?,?,?,?,?,string_to_array(?,','),?,?,?,?,?,?,?)
                """,
                reviewId, context.organizationId(), plan.versionId(), version, digest,
                String.join(",", modalities), findings, outcome, reviewerId,
                Timestamp.from(command.now()), command.reason(), context.actorId(), context.actorId());
        var revision = bumpDraft(context, plan);
        var audit = map(
                "carePlanId", plan.id(), "carePlanVersionId", plan.versionId(),
                "interactionReviewId", reviewId, "reviewVersion", version,
                "digest", digest, "safetyOutcome", outcome, "revision", revision);
        return result(
                reviewId, plan.id(), "interaction_review", "care_plan.interaction_reviewed",
                null, null, null, audit, Map.of(), 201, revision);
    }

    private MutationResult submitPlan(
            AuthorizedTenantContext context, MutationCommand command) {
        var plan = draftPlan(context, command);
        var digest = planDigest(context.organizationId(), plan.versionId());
        requireChanged(jdbc.update(
                """
                UPDATE care_plan_versions SET content_digest=?,status='review',
                    frozen_at=clock_timestamp(),lock_version=lock_version+1,
                    updated_at=clock_timestamp(),updated_by=?
                WHERE organization_id=? AND id=? AND lock_version=? AND status='draft'
                """,
                digest, context.actorId(), context.organizationId(), plan.versionId(),
                plan.versionRevision()),
                "The care-plan version changed before review submission.");
        requireChanged(jdbc.update(
                """
                UPDATE care_plans SET status='review',lock_version=lock_version+1,
                    updated_at=clock_timestamp(),updated_by=?
                WHERE organization_id=? AND id=? AND lock_version=? AND status='draft'
                """,
                context.actorId(), context.organizationId(), plan.id(), plan.revision()),
                "The care plan changed before review submission.");
        var revision = plan.revision() + 1;
        var audit = map(
                "carePlanId", plan.id(), "carePlanVersionId", plan.versionId(),
                "digest", digest, "fromStatus", "draft", "status", "review",
                "revision", revision);
        var outbox = map(
                "carePlanId", plan.id(), "carePlanVersionId", plan.versionId(),
                "digest", digest, "status", "review");
        return result(
                plan.id(), plan.id(), "care_plan", "care_plan.submitted",
                "m9.care-plan-submitted.v1", "care_plan", plan.id(),
                audit, outbox, 200, revision);
    }

    private MutationResult approvePlan(
            AuthorizedTenantContext context, MutationCommand command) {
        var plan = lockPlan(context, command.targetId());
        requireRevision(plan.revision(), command.expectedRevision());
        if (!plan.status().equals("review") || !plan.versionStatus().equals("review")
                || plan.versionDigest() == null) {
            throw conflict("Only an exact submitted care-plan version can be approved.");
        }
        if (command.recentAuthenticationAt() == null || command.mfaAuthenticatedAt() == null) {
            throw conflict("Recent authenticated MFA is required to approve a care plan.");
        }
        var approverId = fieldUuid(command, "approverPractitionerId");
        var approvalId = UuidV7Generator.randomUuid();
        jdbc.update(
                """
                INSERT INTO care_plan_approvals(
                    id,organization_id,care_plan_id,care_plan_version_id,
                    approved_version_digest,approver_practitioner_id,decision,
                    recent_authentication_at,mfa_authenticated_at,approved_at,reason,
                    created_by,updated_by)
                VALUES (?,?,?,?,?,?,'approved',?,?,?,?,?,?)
                """,
                approvalId, context.organizationId(), plan.id(), plan.versionId(),
                plan.versionDigest(), approverId,
                Timestamp.from(command.recentAuthenticationAt()),
                Timestamp.from(command.mfaAuthenticatedAt()), Timestamp.from(command.now()),
                command.reason(), context.actorId(), context.actorId());
        requireChanged(jdbc.update(
                """
                UPDATE care_plan_versions SET status='approved',lock_version=lock_version+1,
                    updated_at=clock_timestamp(),updated_by=?
                WHERE organization_id=? AND id=? AND lock_version=? AND status='review'
                """,
                context.actorId(), context.organizationId(), plan.versionId(),
                plan.versionRevision()),
                "The care-plan version changed before approval.");
        requireChanged(jdbc.update(
                """
                UPDATE care_plans SET status='approved',lock_version=lock_version+1,
                    updated_at=clock_timestamp(),updated_by=?
                WHERE organization_id=? AND id=? AND lock_version=? AND status='review'
                """,
                context.actorId(), context.organizationId(), plan.id(), plan.revision()),
                "The care plan changed before approval.");
        var revision = plan.revision() + 1;
        var audit = map(
                "carePlanId", plan.id(), "carePlanVersionId", plan.versionId(),
                "carePlanApprovalId", approvalId, "digest", plan.versionDigest(),
                "status", "approved", "revision", revision);
        var outbox = map(
                "carePlanId", plan.id(), "carePlanVersionId", plan.versionId(),
                "digest", plan.versionDigest(), "status", "approved");
        return result(
                approvalId, plan.id(), "care_plan_approval", "care_plan.approved",
                "m9.care-plan-approved.v1", "care_plan", plan.id(),
                audit, outbox, 200, revision);
    }

    private MutationResult activatePlan(
            AuthorizedTenantContext context, MutationCommand command) {
        var plan = lockPlan(context, command.targetId());
        requireRevision(plan.revision(), command.expectedRevision());
        if (!plan.status().equals("approved") || !plan.versionStatus().equals("approved")
                || !hasApproval(context.organizationId(), plan)) {
            throw conflict("The exact care-plan version must be approved before activation.");
        }
        requireChanged(jdbc.update(
                """
                UPDATE care_plan_versions SET status='active',lock_version=lock_version+1,
                    updated_at=clock_timestamp(),updated_by=?
                WHERE organization_id=? AND id=? AND lock_version=? AND status='approved'
                """,
                context.actorId(), context.organizationId(), plan.versionId(),
                plan.versionRevision()),
                "The care-plan version changed before activation.");
        requireChanged(jdbc.update(
                """
                UPDATE care_plans SET status='active',lock_version=lock_version+1,
                    updated_at=clock_timestamp(),updated_by=?
                WHERE organization_id=? AND id=? AND lock_version=? AND status='approved'
                """,
                context.actorId(), context.organizationId(), plan.id(), plan.revision()),
                "The care plan changed before activation.");
        var revision = plan.revision() + 1;
        var audit = map(
                "carePlanId", plan.id(), "carePlanVersionId", plan.versionId(),
                "fromStatus", "approved", "status", "active", "revision", revision);
        var outbox = map(
                "carePlanId", plan.id(), "carePlanVersionId", plan.versionId(),
                "status", "active");
        return result(
                plan.id(), plan.id(), "care_plan", "care_plan.activated",
                "m9.care-plan-activated.v1", "care_plan", plan.id(),
                audit, outbox, 200, revision);
    }

    private MutationResult amendPlan(
            AuthorizedTenantContext context, MutationCommand command) {
        var plan = lockPlan(context, command.targetId());
        requireRevision(plan.revision(), command.expectedRevision());
        if (!plan.status().equals("active") || !plan.versionStatus().equals("active")
                || plan.versionDigest() == null) {
            throw conflict("Only an active exact care-plan version can be amended.");
        }
        var practitionerId = fieldUuid(command, "amendedByPractitionerId");
        if (!practitionerId.equals(plan.responsiblePractitionerId())) {
            throw conflict("Only the responsible clinician can create a successor amendment.");
        }
        var summary = bounded(
                field(command, "amendmentSummary"), 10, 2000, "amendmentSummary");
        var clinicalSummary = bounded(
                field(command, "clinicalSummary"), 10, 4000, "clinicalSummary");
        var patientSummary = bounded(
                field(command, "patientSummary"), 10, 4000, "patientSummary");
        var successorId = UuidV7Generator.randomUuid();
        var successorVersionId = UuidV7Generator.randomUuid();
        jdbc.update(
                """
                INSERT INTO care_plans(
                    id,organization_id,patient_id,encounter_id,responsible_practitioner_id,
                    source_assessment_session_id,source_ai_review_id,supersedes_care_plan_id,
                    plan_title,status,created_by,updated_by)
                VALUES (?,?,?,?,?,?,?,?,?,'draft',?,?)
                """,
                successorId, context.organizationId(), plan.patientId(), plan.encounterId(),
                plan.responsiblePractitionerId(), plan.sourceAssessmentId(), plan.sourceAiReviewId(),
                plan.id(), plan.title(), context.actorId(), context.actorId());
        jdbc.update(
                """
                INSERT INTO care_plan_versions(
                    id,organization_id,care_plan_id,version_number,clinical_summary,
                    patient_summary,status,created_by,updated_by)
                VALUES (?,?,?,?,?,?,'draft',?,?)
                """,
                successorVersionId, context.organizationId(), successorId,
                plan.versionNumber() + 1, clinicalSummary, patientSummary,
                context.actorId(), context.actorId());
        requireChanged(jdbc.update(
                """
                UPDATE care_plans SET current_version_id=?,lock_version=lock_version+1,
                    updated_at=clock_timestamp(),updated_by=?
                WHERE organization_id=? AND id=? AND lock_version=0 AND current_version_id IS NULL
                """,
                successorVersionId, context.actorId(), context.organizationId(), successorId),
                "The successor care plan changed before version binding.");
        var amendmentId = UuidV7Generator.randomUuid();
        jdbc.update(
                """
                INSERT INTO care_plan_amendments(
                    id,organization_id,prior_care_plan_id,prior_version_id,
                    prior_version_digest,successor_care_plan_id,successor_version_id,
                    amendment_summary,amended_at,amended_by_practitioner_id,reason,
                    created_by,updated_by)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                amendmentId, context.organizationId(), plan.id(), plan.versionId(),
                plan.versionDigest(), successorId, successorVersionId, summary,
                Timestamp.from(command.now()), practitionerId, command.reason(),
                context.actorId(), context.actorId());
        requireChanged(jdbc.update(
                """
                UPDATE care_plan_versions SET status='superseded',lock_version=lock_version+1,
                    updated_at=clock_timestamp(),updated_by=?
                WHERE organization_id=? AND id=? AND lock_version=? AND status='active'
                """,
                context.actorId(), context.organizationId(), plan.versionId(),
                plan.versionRevision()),
                "The prior care-plan version changed before amendment.");
        requireChanged(jdbc.update(
                """
                UPDATE care_plans SET status='revised',lock_version=lock_version+1,
                    updated_at=clock_timestamp(),updated_by=?
                WHERE organization_id=? AND id=? AND lock_version=? AND status='active'
                """,
                context.actorId(), context.organizationId(), plan.id(), plan.revision()),
                "The prior care plan changed before amendment.");
        var audit = map(
                "carePlanId", plan.id(), "carePlanVersionId", plan.versionId(),
                "successorCarePlanId", successorId, "successorVersionId", successorVersionId,
                "carePlanAmendmentId", amendmentId, "digest", plan.versionDigest(),
                "status", "revised", "revision", plan.revision() + 1);
        var outbox = map(
                "carePlanId", plan.id(), "successorCarePlanId", successorId,
                "successorVersionId", successorVersionId, "status", "revised");
        return result(
                amendmentId, successorId, "care_plan_amendment", "care_plan.amended",
                "m9.care-plan-amended.v1", "care_plan", plan.id(),
                audit, outbox, 201, 1);
    }

    private MutationResult closePlan(
            AuthorizedTenantContext context, MutationCommand command) {
        var plan = lockPlan(context, command.targetId());
        requireRevision(plan.revision(), command.expectedRevision());
        if (PLAN_TERMINAL.contains(plan.status())) {
            throw conflict("The care plan is already terminal.");
        }
        var outcome = oneOf(field(command, "outcome"), "outcome", "completed", "cancelled");
        if (outcome.equals("completed") && !plan.status().equals("active")) {
            throw conflict("Only an active care plan can be completed.");
        }
        requireChanged(jdbc.update(
                """
                UPDATE care_plan_versions SET status=?,lock_version=lock_version+1,
                    updated_at=clock_timestamp(),updated_by=?
                WHERE organization_id=? AND id=? AND lock_version=? AND status=?
                """,
                outcome, context.actorId(), context.organizationId(), plan.versionId(),
                plan.versionRevision(), plan.versionStatus()),
                "The care-plan version changed before closure.");
        requireChanged(jdbc.update(
                """
                UPDATE care_plans SET status=?,lock_version=lock_version+1,
                    updated_at=clock_timestamp(),updated_by=?
                WHERE organization_id=? AND id=? AND lock_version=? AND status=?
                """,
                outcome, context.actorId(), context.organizationId(), plan.id(),
                plan.revision(), plan.status()),
                "The care plan changed before closure.");
        var revision = plan.revision() + 1;
        var audit = map(
                "carePlanId", plan.id(), "carePlanVersionId", plan.versionId(),
                "fromStatus", plan.status(), "status", outcome, "revision", revision);
        var outbox = map(
                "carePlanId", plan.id(), "carePlanVersionId", plan.versionId(),
                "status", outcome);
        return result(
                plan.id(), plan.id(), "care_plan", "care_plan.closed",
                "m9.care-plan-closed.v1", "care_plan", plan.id(),
                audit, outbox, 200, revision);
    }

    private List<CarePlanScreen.Row> planRows(
            AuthorizedTenantContext context, ScreenQuery query) {
        return jdbc.query(
                """
                SELECT plan.id,plan.patient_id,plan.encounter_id,plan.plan_title,plan.status,
                       plan.lock_version,plan.responsible_practitioner_id,
                       plan.source_assessment_session_id,plan.source_ai_review_id,
                       plan.supersedes_care_plan_id,version.id version_id,
                       version.version_number,version.clinical_summary,version.patient_summary,
                       version.content_digest,version.status version_status,
                       patient.patient_number,
                       (SELECT count(*) FROM care_plan_priorities priority
                        WHERE priority.organization_id=plan.organization_id
                          AND priority.care_plan_version_id=version.id) priority_count,
                       (SELECT count(*) FROM care_plan_goals goal
                        WHERE goal.organization_id=plan.organization_id
                          AND goal.care_plan_version_id=version.id) goal_count,
                       (SELECT count(*) FROM care_plan_interventions intervention
                        WHERE intervention.organization_id=plan.organization_id
                          AND intervention.care_plan_version_id=version.id) intervention_count,
                       (SELECT count(*) FROM intervention_assignments assignment
                        WHERE assignment.organization_id=plan.organization_id
                          AND assignment.care_plan_version_id=version.id) assignment_count,
                       (SELECT count(*) FROM clinical_tasks task
                        WHERE task.organization_id=plan.organization_id
                          AND task.care_plan_id=plan.id
                          AND task.status IN ('open','in_progress','acknowledged')) open_task_count,
                       (SELECT intervention.id FROM care_plan_interventions intervention
                        WHERE intervention.organization_id=plan.organization_id
                          AND intervention.care_plan_version_id=version.id
                        ORDER BY intervention.intervention_sequence DESC LIMIT 1) intervention_id,
                       (SELECT task.id FROM clinical_tasks task
                        WHERE task.organization_id=plan.organization_id AND task.care_plan_id=plan.id
                        ORDER BY task.created_at DESC LIMIT 1) clinical_task_id,
                       (SELECT consent.consent_status FROM plan_consents consent
                        WHERE consent.organization_id=plan.organization_id
                          AND consent.care_plan_version_id=version.id
                        ORDER BY consent.consent_version DESC LIMIT 1) consent_status,
                       (SELECT review.safety_outcome FROM interaction_reviews review
                        WHERE review.organization_id=plan.organization_id
                          AND review.care_plan_version_id=version.id
                        ORDER BY review.review_version DESC LIMIT 1) safety_outcome,
                       (SELECT count(*) FROM care_plan_approvals approval
                        WHERE approval.organization_id=plan.organization_id
                          AND approval.care_plan_id=plan.id) approval_count,
                       (SELECT successor.id FROM care_plans successor
                        WHERE successor.organization_id=plan.organization_id
                          AND successor.supersedes_care_plan_id=plan.id LIMIT 1) successor_id
                FROM care_plans plan
                JOIN care_plan_versions version
                  ON version.organization_id=plan.organization_id
                 AND version.id=plan.current_version_id
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
                        query.screenId(),
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("patient_id", UUID.class),
                        resultSet.getObject("encounter_id", UUID.class),
                        resultSet.getObject("version_id", UUID.class),
                        resultSet.getObject("intervention_id", UUID.class),
                        resultSet.getObject("clinical_task_id", UUID.class),
                        resultSet.getString("status"),
                        resultSet.getLong("lock_version"),
                        values(
                                "plan", resultSet.getString("plan_title"),
                                "patient", maskPatientNumber(resultSet.getString("patient_number")),
                                "encounter", resultSet.getObject("encounter_id").toString(),
                                "version", Integer.toString(resultSet.getInt("version_number")),
                                "versionStatus", resultSet.getString("version_status"),
                                "responsibleClinician", resultSet.getObject("responsible_practitioner_id").toString(),
                                "priorities", Long.toString(resultSet.getLong("priority_count")),
                                "goals", Long.toString(resultSet.getLong("goal_count")),
                                "interventions", Long.toString(resultSet.getLong("intervention_count")),
                                "assignedInterventions", Long.toString(resultSet.getLong("assignment_count")),
                                "openTasks", Long.toString(resultSet.getLong("open_task_count")),
                                "consent", safe(resultSet.getString("consent_status"), "Not recorded"),
                                "interactionSafety", safe(resultSet.getString("safety_outcome"), "Not reviewed"),
                                "approval", resultSet.getLong("approval_count") > 0 ? "Approved" : "Not approved",
                                "digest", safe(resultSet.getString("content_digest"), "Draft not frozen"),
                                "clinicalSummary", resultSet.getString("clinical_summary"),
                                "patientSummary", resultSet.getString("patient_summary"),
                                "sourceAssessment", string(resultSet.getObject("source_assessment_session_id")),
                                "sourceAiReview", string(resultSet.getObject("source_ai_review_id")),
                                "supersedes", string(resultSet.getObject("supersedes_care_plan_id")),
                                "successor", string(resultSet.getObject("successor_id")))),
                context.organizationId(),
                query.patientId(), query.patientId(),
                query.encounterId(), query.encounterId(),
                query.carePlanId(), query.carePlanId(),
                query.status(), query.status(),
                query.search(), query.search(), query.limit());
    }

    private List<CarePlanScreen.Metric> metrics(AuthorizedTenantContext context) {
        var values = jdbc.queryForMap(
                """
                SELECT (SELECT count(*) FROM care_plans WHERE organization_id=?) plans,
                       (SELECT count(*) FROM care_plans WHERE organization_id=? AND status='draft') drafts,
                       (SELECT count(*) FROM care_plans WHERE organization_id=? AND status='review') review,
                       (SELECT count(*) FROM care_plans WHERE organization_id=? AND status='active') active,
                       (SELECT count(*) FROM clinical_tasks WHERE organization_id=?
                            AND care_plan_id IS NOT NULL
                            AND status IN ('open','in_progress','acknowledged')) open_tasks
                """,
                context.organizationId(), context.organizationId(), context.organizationId(),
                context.organizationId(), context.organizationId());
        return List.of(
                metric("plans", "Care plans", number(values.get("plans")), "neutral"),
                metric("drafts", "Draft plans", number(values.get("drafts")), "warning"),
                metric("review", "Awaiting approval", number(values.get("review")), "warning"),
                metric("active", "Active plans", number(values.get("active")), "success"),
                metric("openTasks", "Open plan tasks", number(values.get("open_tasks")), "danger"));
    }

    private static List<CarePlanScreen.Column> columns(String screenId) {
        return switch (screenId) {
            case "P9-03" -> List.of(column("patient", "Patient"), column("plan", "Plan"),
                    column("priorities", "Priorities"), column("versionStatus", "Version state"));
            case "P9-04" -> List.of(column("patient", "Patient"), column("plan", "Plan"),
                    column("goals", "Goals"), column("versionStatus", "Version state"));
            case "P9-05", "P9-06" -> List.of(column("patient", "Patient"), column("plan", "Plan"),
                    column("interventions", "Interventions"),
                    column("assignedInterventions", "Assigned"),
                    column("interactionSafety", "Safety"));
            case "P9-07" -> List.of(column("patient", "Patient"), column("plan", "Plan"),
                    column("assignedInterventions", "Owners"), column("openTasks", "Open tasks"));
            case "P9-08" -> List.of(column("patient", "Patient"), column("plan", "Plan"),
                    column("consent", "Consent"), column("version", "Version"));
            case "P9-09" -> List.of(column("patient", "Patient"), column("plan", "Plan"),
                    column("interactionSafety", "Safety outcome"), column("digest", "Reviewed version"));
            case "P9-10" -> List.of(column("patient", "Patient"), column("plan", "Plan"),
                    column("versionStatus", "Version state"), column("approval", "Approval"));
            case "P9-11" -> List.of(column("patient", "Patient"), column("plan", "Plan"),
                    column("patientSummary", "Patient summary"), column("consent", "Consent"));
            case "P9-12" -> List.of(column("patient", "Patient"), column("plan", "Plan"),
                    column("version", "Version"), column("supersedes", "Prior plan"),
                    column("successor", "Successor plan"));
            default -> List.of(column("patient", "Patient"), column("plan", "Plan"),
                    column("version", "Version"), column("responsibleClinician", "Responsible clinician"));
        };
    }

    private static List<CarePlanScreen.Notice> notices(String screenId) {
        var notices = new ArrayList<CarePlanScreen.Notice>();
        notices.add(notice(
                "warning", "Clinical approval is explicit",
                "A draft or AI-assisted suggestion is not an active care plan. The exact complete version requires responsible-clinician approval."));
        notices.add(notice(
                "neutral", "Local clinical policy remains separate",
                "Terminology, modality, scope, interaction and consent catalogues must be approved before production activation."));
        if (Set.of("P9-05", "P9-06", "P9-07", "P9-09", "P9-10").contains(screenId)) {
            notices.add(notice(
                    "danger", "No ownerless or unsafe intervention",
                    "Every intervention requires rationale, accountable ownership, a task, monitoring, stop criteria and a current clear interaction review."));
        }
        return List.copyOf(notices);
    }

    private static CarePlanScreen.Row withAllowedActions(
            String screenId, CarePlanScreen.Row row) {
        var actions = new ArrayList<String>();
        switch (screenId) {
            case "P9-03" -> { if (row.status().equals("draft")) actions.add("add-priority"); }
            case "P9-04" -> { if (row.status().equals("draft")) actions.add("add-goal"); }
            case "P9-05" -> { if (row.status().equals("draft")) actions.add("add-intervention"); }
            case "P9-07" -> { if (row.status().equals("draft")) actions.add("assign-owner-task"); }
            case "P9-08" -> { if (row.status().equals("draft")) actions.add("record-consent"); }
            case "P9-09" -> { if (row.status().equals("draft")) actions.add("record-interaction-review"); }
            case "P9-10" -> {
                if (row.status().equals("draft")) actions.add("submit-plan");
                else if (row.status().equals("review")) actions.add("approve-plan");
                else if (row.status().equals("approved")) actions.add("activate-plan");
            }
            case "P9-12" -> {
                if (row.status().equals("active")) actions.add("amend-plan");
                if (!PLAN_TERMINAL.contains(row.status())) actions.add("close-plan");
            }
            default -> { /* Read-only screen or target-free creation action. */ }
        }
        return new CarePlanScreen.Row(
                row.id(), row.patientId(), row.encounterId(), row.carePlanVersionId(),
                row.interventionId(), row.clinicalTaskId(), row.status(), row.revision(),
                row.etag(), row.values(), List.copyOf(actions));
    }

    private static CarePlanScreen.Row row(
            String screenId,
            UUID id,
            UUID patientId,
            UUID encounterId,
            UUID versionId,
            UUID interventionId,
            UUID clinicalTaskId,
            String status,
            long revision,
            Map<String, String> values) {
        return new CarePlanScreen.Row(
                id, patientId, encounterId, versionId, interventionId, clinicalTaskId,
                status, revision, "\"m9:" + screenId + ":" + id + ":" + revision + "\"",
                values, List.of());
    }

    private PlanRecord draftPlan(
            AuthorizedTenantContext context, MutationCommand command) {
        var plan = lockPlan(context, command.targetId());
        requireRevision(plan.revision(), command.expectedRevision());
        if (!plan.status().equals("draft") || !plan.versionStatus().equals("draft")) {
            throw conflict("Care-plan content can be appended only to the current draft version.");
        }
        return plan;
    }

    private PlanRecord lockPlan(AuthorizedTenantContext context, UUID carePlanId) {
        if (carePlanId == null) throw invalid("A care-plan target is required.");
        var rows = jdbc.query(
                """
                SELECT plan.id,plan.patient_id,plan.encounter_id,
                       plan.responsible_practitioner_id,plan.source_assessment_session_id,
                       plan.source_ai_review_id,plan.plan_title,plan.status,plan.lock_version,
                       version.id version_id,version.version_number,version.status version_status,
                       version.content_digest,version.lock_version version_revision
                FROM care_plans plan
                JOIN care_plan_versions version
                  ON version.organization_id=plan.organization_id
                 AND version.id=plan.current_version_id
                WHERE plan.organization_id=? AND plan.id=? FOR UPDATE OF plan,version
                """,
                (resultSet, rowNumber) -> new PlanRecord(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("patient_id", UUID.class),
                        resultSet.getObject("encounter_id", UUID.class),
                        resultSet.getObject("responsible_practitioner_id", UUID.class),
                        resultSet.getObject("source_assessment_session_id", UUID.class),
                        resultSet.getObject("source_ai_review_id", UUID.class),
                        resultSet.getString("plan_title"),
                        resultSet.getString("status"),
                        resultSet.getLong("lock_version"),
                        resultSet.getObject("version_id", UUID.class),
                        resultSet.getInt("version_number"),
                        resultSet.getString("version_status"),
                        resultSet.getString("content_digest"),
                        resultSet.getLong("version_revision")),
                context.organizationId(), carePlanId);
        if (rows.isEmpty()) throw notFound("The care plan is unavailable.");
        return rows.getFirst();
    }

    private long bumpDraft(AuthorizedTenantContext context, PlanRecord plan) {
        requireChanged(jdbc.update(
                """
                UPDATE care_plans SET lock_version=lock_version+1,
                    updated_at=clock_timestamp(),updated_by=?
                WHERE organization_id=? AND id=? AND lock_version=? AND status='draft'
                """,
                context.actorId(), context.organizationId(), plan.id(), plan.revision()),
                "The care plan changed before draft evidence was appended.");
        return plan.revision() + 1;
    }

    private int nextSequence(
            String table, String column, UUID organizationId, UUID versionId) {
        if (!Set.of("care_plan_priorities", "care_plan_goals", "care_plan_interventions")
                .contains(table)) {
            throw new IllegalArgumentException("Unsupported care-plan sequence table.");
        }
        var sql = "SELECT coalesce(max(" + column + "),0)+1 FROM " + table
                + " WHERE organization_id=? AND care_plan_version_id=?";
        return Objects.requireNonNull(jdbc.queryForObject(
                sql, Integer.class, organizationId, versionId));
    }

    private String planDigest(UUID organizationId, UUID versionId) {
        var digest = jdbc.queryForObject(
                "SELECT careos_m9_plan_digest(?,?)", String.class, organizationId, versionId);
        if (digest == null || !digest.matches("[0-9a-f]{64}")) {
            throw conflict("The care-plan version digest could not be calculated.");
        }
        return digest;
    }

    private boolean hasApproval(UUID organizationId, PlanRecord plan) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                """
                SELECT count(*)=1 FROM care_plan_approvals
                WHERE organization_id=? AND care_plan_id=? AND care_plan_version_id=?
                  AND approved_version_digest=? AND decision='approved'
                """,
                Boolean.class, organizationId, plan.id(), plan.versionId(), plan.versionDigest()));
    }

    private void requireOperationScope(AuthorizedTenantContext context) {
        var bound = Boolean.TRUE.equals(jdbc.queryForObject(
                """
                SELECT nullif(current_setting('app.current_organization_id',true),'')::uuid=?
                   AND nullif(current_setting('app.current_actor_id',true),'')::uuid=?
                   AND nullif(current_setting('app.current_operation_key',true),'') IS NOT NULL
                """,
                Boolean.class,
                context.organizationId(), context.actorId()));
        if (!bound) {
            throw notFound("The care-plan resource is unavailable or is not assigned to this account.");
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

    private static UUID optionalUuid(MutationCommand command, String key) {
        var value = command.fields().get(key);
        if (value == null || value.isBlank()) return null;
        try {
            return UUID.fromString(value.strip());
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

    private static String priority(String value) {
        return oneOf(value, "priority", "routine", "important", "urgent", "critical");
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

    private static List<String> codes(
            String value, String field, int minimum, int maximum) {
        var entries = java.util.Arrays.stream(value.split(","))
                .map(String::strip)
                .filter(entry -> !entry.isEmpty())
                .peek(entry -> {
                    if (!entry.matches("[a-z][a-z0-9_.:-]{1,79}")) {
                        throw invalid(field + " contains an invalid key.");
                    }
                })
                .distinct()
                .sorted()
                .toList();
        if (entries.size() < minimum || entries.size() > maximum) {
            throw invalid(field + " must contain " + minimum + " to " + maximum + " keys.");
        }
        return entries;
    }

    private static String oneOf(String value, String field, String... options) {
        for (var option : options) if (option.equals(value)) return value;
        throw invalid(field + " contains an unsupported value.");
    }

    private static void requireRevision(long actual, Long expected) {
        if (expected == null) {
            throw new CarePlanException(
                    CarePlanException.Reason.PRECONDITION_REQUIRED,
                    "A strong care-plan revision is required.");
        }
        if (actual != expected) {
            throw new CarePlanException(
                    CarePlanException.Reason.STALE,
                    "The care plan changed; reload before continuing.");
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
            UUID carePlanId,
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
                subjectId, carePlanId, subjectType, auditEvent, outboxEvent, aggregateType,
                outboxAggregateId, audit, outbox, statusCode, revision);
    }

    private static CarePlanScreen.Column column(String key, String label) {
        return new CarePlanScreen.Column(key, label);
    }

    private static CarePlanScreen.Metric metric(
            String key, String label, long value, String tone) {
        return new CarePlanScreen.Metric(key, label, value, tone);
    }

    private static CarePlanScreen.Notice notice(String tone, String title, String detail) {
        return new CarePlanScreen.Notice(tone, title, detail);
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

    private static CarePlanException invalid(String message) {
        return new CarePlanException(CarePlanException.Reason.INVALID, message);
    }

    private static CarePlanException notFound(String message) {
        return new CarePlanException(CarePlanException.Reason.NOT_FOUND, message);
    }

    private static CarePlanException conflict(String message) {
        return new CarePlanException(CarePlanException.Reason.CONFLICT, message);
    }

    private record PlanRecord(
            UUID id,
            UUID patientId,
            UUID encounterId,
            UUID responsiblePractitionerId,
            UUID sourceAssessmentId,
            UUID sourceAiReviewId,
            String title,
            String status,
            long revision,
            UUID versionId,
            int versionNumber,
            String versionStatus,
            String versionDigest,
            long versionRevision) {}
}
