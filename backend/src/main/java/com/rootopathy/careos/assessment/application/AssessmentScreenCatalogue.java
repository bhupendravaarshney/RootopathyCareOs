package com.rootopathy.careos.assessment.application;

import com.rootopathy.careos.assessment.domain.AssessmentScreen;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The repository-owned structural contract for the protected 27-screen COS sequence. */
final class AssessmentScreenCatalogue {
    private static final List<String> TITLES = List.of(
            "Consultation context",
            "Patient story",
            "Presenting concerns",
            "Clinical timeline",
            "Medication review",
            "Allergies and safety",
            "Investigations",
            "Vital signs",
            "Clinical examination",
            "Red-flag assessment",
            "Problem list",
            "Differential assessment",
            "ROOT360 overview",
            "PhysioCore assessment",
            "Mind and narrative",
            "Lifestyle and environment",
            "Integrative evidence review",
            "Clinical synthesis",
            "Priorities and goals",
            "Coordinated care plan",
            "Intervention safety",
            "Consent and shared decision",
            "Document review",
            "AI-assisted synthesis",
            "Clinician review and approval",
            "Monitoring and follow-up",
            "Confirm and close");
    private static final Map<String, ScreenSpec> SCREENS = screens();

    private AssessmentScreenCatalogue() {}

    static ScreenSpec screen(String id) {
        var screen = SCREENS.get(id);
        if (screen == null) {
            throw new AssessmentException(
                    AssessmentException.Reason.NOT_FOUND,
                    "The requested assessment screen does not exist.");
        }
        return screen;
    }

    static ActionSpec mutation(String screenId, String actionKey) {
        return screen(screenId).actions().stream()
                .filter(action -> action.href() == null && action.key().equals(actionKey))
                .findFirst()
                .orElseThrow(() -> new AssessmentException(
                        AssessmentException.Reason.NOT_FOUND,
                        "The requested assessment action does not exist."));
    }

    static List<AssessmentScreen.Action> projectedActions(
            ScreenSpec screen, Set<String> permissions) {
        return screen.actions().stream()
                .filter(action -> action.href() != null || permissions.contains(action.permission()))
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
            String href,
            List<AssessmentScreen.Field> fields) {
        AssessmentScreen.Action projection() {
            return new AssessmentScreen.Action(
                    key,
                    label,
                    style,
                    targetRequired,
                    ifMatchRequired,
                    reasonRequired,
                    href,
                    fields);
        }
    }

    private static Map<String, ScreenSpec> screens() {
        var screens = new LinkedHashMap<String, ScreenSpec>();
        for (var index = 0; index < TITLES.size(); index++) {
            var sequence = index + 1;
            var id = "COS-%02d".formatted(sequence);
            var actions = new ArrayList<ActionSpec>();
            if (sequence == 1) {
                actions.add(action(
                        "start-assessment",
                        "Start assessment",
                        "assessment.start",
                        true,
                        false,
                        true,
                        uuid("responsiblePractitionerId", "Responsible clinician", true)));
                actions.add(lifecycle("cancel-assessment", "Cancel assessment"));
                actions.add(lifecycle("enter-assessment-in-error", "Enter in error"));
            } else if (sequence != 24 && sequence != 25 && sequence != 27) {
                actions.add(saveResponse());
            }
            if (sequence == 8 || sequence == 26) {
                actions.add(recordMeasurement());
            }
            if (sequence == 10) {
                actions.add(recordRedFlag());
                actions.add(action(
                        "acknowledge-red-flag",
                        "Acknowledge red flag",
                        "assessment.red_flag.write",
                        true,
                        true,
                        true,
                        uuid("redFlagId", "Red flag", true),
                        uuid("practitionerId", "Acknowledging clinician", true)));
                actions.add(action(
                        "resolve-red-flag",
                        "Resolve red flag",
                        "assessment.red_flag.write",
                        true,
                        true,
                        true,
                        uuid("redFlagId", "Red flag", true),
                        uuid("practitionerId", "Resolving clinician", true)));
            }
            if (sequence == 25) {
                actions.add(action(
                        "submit-assessment-review",
                        "Submit review",
                        "assessment.review",
                        true,
                        true,
                        true,
                        uuid("reviewerPractitionerId", "Reviewing clinician", true),
                        checkbox("completenessConfirmed", "Completeness confirmed", true),
                        checkbox("sourceReviewed", "Sources reviewed", true),
                        checkbox("uncertaintyReviewed", "Uncertainty reviewed", true),
                        textarea("reviewSummary", "Review summary", true)));
                actions.add(action(
                        "sign-assessment",
                        "Sign assessment",
                        "assessment.sign",
                        true,
                        true,
                        true,
                        uuid("signerPractitionerId", "Signing clinician", true)));
                actions.add(action(
                        "amend-assessment",
                        "Add amendment",
                        "assessment.amend",
                        true,
                        true,
                        true,
                        uuid("authorPractitionerId", "Author clinician", true),
                        textarea("amendmentText", "Amendment", true),
                        text("sourceKey", "Source", true),
                        text("methodKey", "Method", true),
                        textarea("uncertainty", "Uncertainty", false)));
                actions.add(lifecycle("return-assessment-to-draft", "Return to draft"));
            }
            if (sequence == 27) {
                actions.add(lifecycle("complete-assessment", "Complete assessment"));
            }
            screens.put(
                    id,
                    new ScreenSpec(
                            id,
                            TITLES.get(index),
                            purpose(sequence),
                            "assessment.read",
                            List.copyOf(actions)));
        }
        return Map.copyOf(screens);
    }

    private static String purpose(int sequence) {
        return switch (sequence) {
            case 1 -> "Verify patient, encounter and responsible-clinician context before starting the protected COS sequence.";
            case 10 -> "Record sourced red-flag assessment evidence with visible acknowledgement and resolution state.";
            case 24 -> "Preserve the protected AI-assisted synthesis step; runtime synthesis remains unavailable until Module 8 governance exists.";
            case 25 -> "Review completeness, sources and uncertainty, then bind an immutable eligible-clinician signature or amendment.";
            case 26 -> "Record purpose-bound outcome measures without producing a composite cure score.";
            case 27 -> "Confirm the signed assessment, resolved safety state and explicit lifecycle closeout.";
            default -> "Capture versioned, sourced and attributed clinical assessment evidence for this protected COS step.";
        };
    }

    private static ActionSpec saveResponse() {
        return action(
                "save-section-response",
                "Save response",
                "assessment.response.write",
                true,
                true,
                false,
                uuid("authorPractitionerId", "Author clinician", true),
                text("responseKey", "Response key", true),
                textarea("content", "Clinical response", true),
                text("sourceKey", "Source", true),
                text("methodKey", "Method", true),
                text("unit", "Unit", false),
                select(
                        "interpretationStatus",
                        "Interpretation status",
                        true,
                        "uninterpreted",
                        "Uninterpreted",
                        "provisional",
                        "Provisional",
                        "reviewed",
                        "Reviewed",
                        "not_applicable",
                        "Not applicable"),
                textarea("uncertainty", "Uncertainty", false));
    }

    private static ActionSpec recordMeasurement() {
        return action(
                "record-measurement",
                "Record measurement",
                "assessment.measurement.write",
                true,
                true,
                false,
                uuid("recordedByPractitionerId", "Recording clinician", true),
                uuid("ownerPractitionerId", "Measure owner", true),
                text("measurementKey", "Measurement key", true),
                textarea("purpose", "Purpose", true),
                checkbox("baseline", "Baseline", false),
                text("value", "Value", true),
                text("sourceKey", "Source", true),
                text("methodKey", "Method", true),
                text("unitScale", "Unit or scale", true),
                text("cadence", "Cadence", true),
                textarea("actionThreshold", "Action threshold", true),
                select(
                        "interpretationStatus",
                        "Interpretation status",
                        true,
                        "uninterpreted",
                        "Uninterpreted",
                        "provisional",
                        "Provisional",
                        "reviewed",
                        "Reviewed",
                        "not_applicable",
                        "Not applicable"));
    }

    private static ActionSpec recordRedFlag() {
        return action(
                "record-red-flag",
                "Raise red flag",
                "assessment.red_flag.write",
                true,
                true,
                true,
                uuid("raisedByPractitionerId", "Raising clinician", true),
                uuid("ownerPractitionerId", "Safety owner", true),
                select(
                        "severityKey",
                        "Severity",
                        true,
                        "moderate",
                        "Moderate",
                        "severe",
                        "Severe",
                        "critical",
                        "Critical"),
                textarea("summary", "Red-flag summary", true),
                text("sourceKey", "Source", true),
                text("methodKey", "Method", true));
    }

    private static ActionSpec lifecycle(String key, String label) {
        return action(key, label, "assessment.lifecycle.manage", true, true, true);
    }

    private static ActionSpec action(
            String key,
            String label,
            String operation,
            boolean targetRequired,
            boolean ifMatchRequired,
            boolean reasonRequired,
            AssessmentScreen.Field... fields) {
        return new ActionSpec(
                key,
                label,
                operation,
                operation,
                "primary",
                targetRequired,
                ifMatchRequired,
                reasonRequired,
                null,
                List.of(fields));
    }

    private static AssessmentScreen.Field uuid(String key, String label, boolean required) {
        return new AssessmentScreen.Field(key, label, "uuid", required, null, List.of());
    }

    private static AssessmentScreen.Field text(String key, String label, boolean required) {
        return new AssessmentScreen.Field(key, label, "text", required, null, List.of());
    }

    private static AssessmentScreen.Field textarea(String key, String label, boolean required) {
        return new AssessmentScreen.Field(key, label, "textarea", required, null, List.of());
    }

    private static AssessmentScreen.Field checkbox(String key, String label, boolean required) {
        return new AssessmentScreen.Field(key, label, "checkbox", required, null, List.of());
    }

    private static AssessmentScreen.Field select(
            String key, String label, boolean required, String... entries) {
        var options = new ArrayList<AssessmentScreen.Option>();
        for (var index = 0; index < entries.length; index += 2) {
            options.add(new AssessmentScreen.Option(entries[index], entries[index + 1]));
        }
        return new AssessmentScreen.Field(key, label, "select", required, null, options);
    }
}
