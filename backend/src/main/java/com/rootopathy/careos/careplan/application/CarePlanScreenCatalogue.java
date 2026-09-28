package com.rootopathy.careos.careplan.application;

import com.rootopathy.careos.careplan.domain.CarePlanScreen;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Repository-owned contract for the twelve Module 9 care-planning screens. */
final class CarePlanScreenCatalogue {
    private static final List<String> TITLES = List.of(
            "Care plan dashboard",
            "Create coordinated plan",
            "Problems and priorities",
            "Goals",
            "Interventions",
            "Modality coordination",
            "Owners and tasks",
            "Consent and preferences",
            "Safety and interaction review",
            "Clinician approval",
            "Patient summary",
            "Plan versions and amendments");
    private static final Map<String, ScreenSpec> SCREENS = screens();

    private CarePlanScreenCatalogue() {}

    static ScreenSpec screen(String id) {
        var screen = SCREENS.get(id);
        if (screen == null) {
            throw new CarePlanException(
                    CarePlanException.Reason.NOT_FOUND,
                    "The requested care-planning screen does not exist.");
        }
        return screen;
    }

    static ActionSpec mutation(String screenId, String actionKey) {
        return screen(screenId).actions().stream()
                .filter(action -> action.key().equals(actionKey))
                .findFirst()
                .orElseThrow(() -> new CarePlanException(
                        CarePlanException.Reason.NOT_FOUND,
                        "The requested care-planning action does not exist."));
    }

    static List<CarePlanScreen.Action> projectedActions(
            ScreenSpec screen, Set<String> permissions) {
        return screen.actions().stream()
                .filter(action -> permissions.contains(action.permission()))
                .map(ActionSpec::projection)
                .toList();
    }

    static List<String> titles() {
        return TITLES;
    }

    record ScreenSpec(
            String id,
            String title,
            String purpose,
            String readOperation,
            List<ActionSpec> actions) {}

    record ActionSpec(
            String key,
            String label,
            String operation,
            String permission,
            String style,
            boolean targetRequired,
            boolean ifMatchRequired,
            boolean reasonRequired,
            List<CarePlanScreen.Field> fields) {
        CarePlanScreen.Action projection() {
            return new CarePlanScreen.Action(
                    key, label, style, targetRequired, ifMatchRequired, reasonRequired, fields);
        }
    }

    private static Map<String, ScreenSpec> screens() {
        var screens = new LinkedHashMap<String, ScreenSpec>();
        add(screens, 1, "Review coordinated-plan readiness, ownership, safety and lifecycle state.");
        add(screens, 2, "Create a draft plan bound to one verified patient, encounter and responsible clinician.",
                action("create-plan", "Create coordinated plan", "care-plan.create", false, false,
                        uuid("patientId", "Patient", true),
                        uuid("encounterId", "Encounter", true),
                        uuid("responsiblePractitionerId", "Responsible clinician", true),
                        uuid("sourceAssessmentSessionId", "Source assessment", false),
                        uuid("sourceAiReviewId", "Accepted AI review", false),
                        text("planTitle", "Plan title", true),
                        textarea("clinicalSummary", "Clinical summary", true),
                        textarea("patientSummary", "Patient-facing summary", true)));
        add(screens, 3, "Append sourced problems and explicit priorities to the current draft version.",
                action("add-priority", "Add problem or priority", "care-plan.priority.add", true, true,
                        select("sourceType", "Source", true,
                                "patient", "Patient stated",
                                "assessment", "Assessment",
                                "encounter", "Encounter",
                                "clinician", "Clinician review"),
                        uuid("sourceReferenceId", "Source record", false),
                        text("problemCodeSystem", "Problem code system", false),
                        text("problemCode", "Problem code", false),
                        text("displayText", "Problem or priority", true),
                        textarea("rationale", "Prioritization rationale", true),
                        select("priority", "Priority", true,
                                "routine", "Routine", "important", "Important",
                                "urgent", "Urgent", "critical", "Critical")));
        add(screens, 4, "Append measurable clinical and patient-stated goals.",
                action("add-goal", "Add goal", "care-plan.goal.add", true, true,
                        select("goalType", "Goal source", true,
                                "patient_stated", "Patient stated", "clinical", "Clinical"),
                        textarea("description", "Goal", true),
                        text("measure", "Measure", true),
                        text("target", "Target", true),
                        date("targetDate", "Target date", false),
                        select("priority", "Priority", true,
                                "routine", "Routine", "important", "Important",
                                "urgent", "Urgent", "critical", "Critical")));
        add(screens, 5, "Append complete interventions with rationale, timing, stop criteria and monitoring.",
                action("add-intervention", "Add intervention", "care-plan.intervention.add", true, true,
                        text("modalityKey", "Modality", true),
                        text("interventionName", "Intervention", true),
                        textarea("rationale", "Rationale", true),
                        select("priority", "Priority", true,
                                "routine", "Routine", "important", "Important",
                                "urgent", "Urgent", "critical", "Critical"),
                        date("startDate", "Planned start", true),
                        date("reviewDate", "Review date", true),
                        textarea("stopCriteria", "Stop criteria", true),
                        textarea("monitoring", "Monitoring", true),
                        select("evidenceStatus", "Evidence status", true,
                                "established", "Established", "limited", "Limited",
                                "uncertain", "Uncertain", "not_assessed", "Not assessed")));
        add(screens, 6, "Review cross-modality sequencing, ownership and coordination without hiding uncertainty.");
        add(screens, 7, "Assign every intervention to an eligible owner and create an attributable clinical task.",
                action("assign-owner-task", "Assign owner and task", "care-plan.assignment.manage", true, true,
                        uuid("interventionId", "Intervention", true),
                        uuid("ownerPractitionerId", "Owner", true),
                        textarea("responsibility", "Responsibility", true),
                        date("startDate", "Assigned start", true),
                        date("reviewDate", "Review date", true),
                        textarea("taskDescription", "Task", true),
                        select("taskPriority", "Task priority", true,
                                "routine", "Routine", "urgent", "Urgent", "critical", "Critical"),
                        dateTime("dueAt", "Due at", true)));
        add(screens, 8, "Append visible patient consent, preferences and communication needs.",
                action("record-consent", "Record consent and preferences", "care-plan.consent.record", true, true,
                        select("consentStatus", "Consent status", true,
                                "granted", "Granted", "not_required", "Not required by policy",
                                "refused", "Refused", "withdrawn", "Withdrawn"),
                        text("consentReference", "Consent evidence reference", false),
                        textarea("preferences", "Patient preferences", true),
                        textarea("communicationNeeds", "Communication needs", false),
                        uuid("recordedByPractitionerId", "Recording clinician", true)));
        add(screens, 9, "Record an exact-version cross-modality interaction and safety review.",
                action("record-interaction-review", "Record safety review", "care-plan.safety.review", true, true,
                        text("modalities", "Modalities reviewed", true),
                        textarea("interactionFindings", "Interaction findings", true),
                        select("safetyOutcome", "Safety outcome", true,
                                "clear", "Clear", "needs_changes", "Needs changes", "unsafe", "Unsafe"),
                        uuid("reviewedByPractitionerId", "Reviewing clinician", true)));
        add(screens, 10, "Freeze, approve and activate the exact complete plan version with accountable review.",
                action("submit-plan", "Submit for review", "care-plan.submit", true, true),
                action("approve-plan", "Approve plan", "care-plan.approve", true, true,
                        uuid("approverPractitionerId", "Approving clinician", true)),
                action("activate-plan", "Activate approved plan", "care-plan.activate", true, true));
        add(screens, 11, "Present the current approved plan in bounded patient-facing language.");
        add(screens, 12, "Review immutable versions and create a reason-bound successor amendment.",
                action("amend-plan", "Create successor amendment", "care-plan.amend", true, true,
                        uuid("amendedByPractitionerId", "Amending clinician", true),
                        textarea("amendmentSummary", "Amendment summary", true),
                        textarea("clinicalSummary", "Revised clinical summary", true),
                        textarea("patientSummary", "Revised patient summary", true)),
                action("close-plan", "Complete or cancel plan", "care-plan.close", true, true,
                        select("outcome", "Outcome", true,
                                "completed", "Completed", "cancelled", "Cancelled")));
        return Map.copyOf(screens);
    }

    private static void add(
            Map<String, ScreenSpec> target, int sequence, String purpose, ActionSpec... actions) {
        var id = "P9-%02d".formatted(sequence);
        target.put(id, new ScreenSpec(
                id, TITLES.get(sequence - 1), purpose, "care-plan.read", List.of(actions)));
    }

    private static ActionSpec action(
            String key,
            String label,
            String operation,
            boolean targetRequired,
            boolean ifMatchRequired,
            CarePlanScreen.Field... fields) {
        return new ActionSpec(
                key, label, operation, operation, "primary", targetRequired, ifMatchRequired, true,
                List.of(fields));
    }

    private static CarePlanScreen.Field text(String key, String label, boolean required) {
        return new CarePlanScreen.Field(key, label, "text", required, null, List.of());
    }

    private static CarePlanScreen.Field textarea(String key, String label, boolean required) {
        return new CarePlanScreen.Field(key, label, "textarea", required, null, List.of());
    }

    private static CarePlanScreen.Field uuid(String key, String label, boolean required) {
        return new CarePlanScreen.Field(key, label, "uuid", required, null, List.of());
    }

    private static CarePlanScreen.Field date(String key, String label, boolean required) {
        return new CarePlanScreen.Field(key, label, "date", required, null, List.of());
    }

    private static CarePlanScreen.Field dateTime(String key, String label, boolean required) {
        return new CarePlanScreen.Field(key, label, "datetime-local", required, null, List.of());
    }

    private static CarePlanScreen.Field select(
            String key, String label, boolean required, String... entries) {
        var options = new ArrayList<CarePlanScreen.Option>();
        for (var index = 0; index < entries.length; index += 2) {
            options.add(new CarePlanScreen.Option(entries[index], entries[index + 1]));
        }
        return new CarePlanScreen.Field(key, label, "select", required, null, options);
    }
}
