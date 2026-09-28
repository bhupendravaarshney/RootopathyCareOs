package com.rootopathy.careos.followup.application;

import com.rootopathy.careos.followup.domain.FollowupScreen;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Repository-owned contract for the nine Module 10 follow-up and outcomes screens. */
final class FollowupScreenCatalogue {
    private static final List<String> TITLES = List.of(
            "Monitoring dashboard",
            "Rules",
            "Domains",
            "Measures",
            "Escalation",
            "Follow-up schedule",
            "Interpretation",
            "Confirm plan",
            "Outcome timeline");
    private static final Map<String, ScreenSpec> SCREENS = screens();

    private FollowupScreenCatalogue() {}

    static ScreenSpec screen(String id) {
        var screen = SCREENS.get(id);
        if (screen == null) {
            throw new FollowupException(
                    FollowupException.Reason.NOT_FOUND,
                    "The requested follow-up screen does not exist.");
        }
        return screen;
    }

    static ActionSpec mutation(String screenId, String actionKey) {
        return screen(screenId).actions().stream()
                .filter(action -> action.key().equals(actionKey))
                .findFirst()
                .orElseThrow(() -> new FollowupException(
                        FollowupException.Reason.NOT_FOUND,
                        "The requested follow-up action does not exist."));
    }

    static List<FollowupScreen.Action> projectedActions(
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
            List<FollowupScreen.Field> fields) {
        FollowupScreen.Action projection() {
            return new FollowupScreen.Action(
                    key, label, style, targetRequired, ifMatchRequired, reasonRequired, fields);
        }
    }

    private static Map<String, ScreenSpec> screens() {
        var screens = new LinkedHashMap<String, ScreenSpec>();
        add(screens, 1, "Review active monitoring plans, due events and unresolved escalations.",
                action("create-followup-plan", "Create monitoring plan", "followup.plan.create", false, false,
                        uuid("carePlanId", "Active care plan", true),
                        uuid("carePlanVersionId", "Active plan version", true),
                        uuid("patientId", "Patient", true),
                        uuid("encounterId", "Encounter", true),
                        uuid("responsiblePractitionerId", "Responsible clinician", true),
                        text("planTitle", "Monitoring plan title", true),
                        textarea("monitoringPurpose", "Monitoring purpose", true),
                        text("timezone", "IANA timezone", true),
                        date("startsOn", "Starts on", true),
                        date("endsOn", "Ends on", false)));
        add(screens, 2, "Define exact thresholds, severity, owner, task priority and acknowledgement target.",
                action("add-rule", "Add escalation rule", "followup.rule.add", true, true,
                        uuid("outcomeDefinitionId", "Outcome measure", true),
                        text("ruleName", "Rule name", true),
                        select("operator", "Operator", true,
                                "lt", "Less than", "lte", "Less than or equal",
                                "gt", "Greater than", "gte", "Greater than or equal",
                                "outside_range", "Outside range", "inside_range", "Inside range"),
                        decimal("thresholdLower", "Threshold or lower bound", true),
                        decimal("thresholdUpper", "Upper bound", false),
                        select("severity", "Severity", true,
                                "warning", "Warning", "critical", "Critical"),
                        uuid("ownerPractitionerId", "Escalation owner", true),
                        select("taskPriority", "Task priority", true,
                                "urgent", "Urgent", "critical", "Critical"),
                        number("acknowledgeWithinMinutes", "Acknowledge within minutes", true),
                        textarea("instruction", "Escalation instruction", true)));
        add(screens, 3, "Define version-frozen outcome domains, measures, units, direction and targets.",
                action("add-domain", "Add outcome measure", "followup.domain.add", true, true,
                        text("domainKey", "Domain key", true),
                        text("domainDisplay", "Domain", true),
                        text("measureKey", "Measure key", true),
                        text("measureDisplay", "Measure", true),
                        text("unitCode", "Unit", true),
                        select("direction", "Desired direction", true,
                                "increase", "Increase", "decrease", "Decrease",
                                "range", "Within range", "maintain", "Maintain"),
                        decimal("targetLower", "Target or lower bound", false),
                        decimal("targetUpper", "Upper bound", false),
                        select("baselineRequired", "Baseline required", true,
                                "true", "Required", "false", "Not required")));
        add(screens, 4, "Record an attributed value and evaluate every applicable threshold atomically.",
                action("record-measurement", "Record measurement", "followup.measure.record", true, true,
                        uuid("outcomeDefinitionId", "Outcome measure", true),
                        decimal("numericValue", "Observed value", true),
                        text("unitCode", "Unit", true),
                        dateTime("observedAt", "Observed at", true),
                        text("sourceKey", "Source key", true),
                        text("methodKey", "Method key", true),
                        uuid("recordedByPractitionerId", "Recording clinician", true),
                        textarea("notes", "Notes", false)));
        add(screens, 5, "Acknowledge and resolve owned threshold breaches with explicit evidence.",
                action("acknowledge-escalation", "Acknowledge escalation",
                        "followup.escalation.acknowledge", true, true,
                        uuid("practitionerId", "Acknowledging owner", true)),
                action("resolve-escalation", "Resolve escalation",
                        "followup.escalation.resolve", true, true,
                        uuid("practitionerId", "Resolving owner", true)));
        add(screens, 6, "Schedule baseline and future monitoring events with accountable ownership.",
                action("schedule-followup", "Schedule follow-up", "followup.schedule.manage", true, true,
                        select("eventType", "Event type", true,
                                "baseline", "Baseline", "scheduled", "Scheduled", "ad_hoc", "Ad hoc"),
                        dateTime("scheduledFor", "Scheduled for", true),
                        dateTime("dueAt", "Due at", true),
                        uuid("ownerPractitionerId", "Event owner", true)));
        add(screens, 7, "Append clinical interpretation and recommendation to an exact completed measurement.",
                action("record-interpretation", "Record interpretation",
                        "followup.interpretation.record", true, true,
                        select("trend", "Trend", true,
                                "improving", "Improving", "stable", "Stable",
                                "worsening", "Worsening", "mixed", "Mixed",
                                "insufficient_data", "Insufficient data"),
                        textarea("interpretation", "Interpretation", true),
                        textarea("recommendation", "Recommendation", true),
                        uuid("interpretedByPractitionerId", "Interpreting clinician", true)));
        add(screens, 8, "Freeze the complete monitoring definition, then confirm it with recent MFA.",
                action("submit-followup-plan", "Submit for confirmation", "followup.submit", true, true),
                action("confirm-followup-plan", "Confirm monitoring plan", "followup.confirm", true, true));
        add(screens, 9, "Review immutable measurements, interpretations and escalation outcomes over time.",
                action("close-followup-plan", "Complete or cancel monitoring", "followup.close", true, true,
                        select("outcome", "Outcome", true,
                                "completed", "Completed", "cancelled", "Cancelled")));
        return Map.copyOf(screens);
    }

    private static void add(
            Map<String, ScreenSpec> target, int sequence, String purpose, ActionSpec... actions) {
        var id = "P10-%02d".formatted(sequence);
        target.put(id, new ScreenSpec(
                id, TITLES.get(sequence - 1), purpose, "followup.read", List.of(actions)));
    }

    private static ActionSpec action(
            String key,
            String label,
            String operation,
            boolean targetRequired,
            boolean ifMatchRequired,
            FollowupScreen.Field... fields) {
        return new ActionSpec(
                key, label, operation, operation, "primary", targetRequired, ifMatchRequired, true,
                List.of(fields));
    }

    private static FollowupScreen.Field text(String key, String label, boolean required) {
        return new FollowupScreen.Field(key, label, "text", required, null, List.of());
    }

    private static FollowupScreen.Field textarea(String key, String label, boolean required) {
        return new FollowupScreen.Field(key, label, "textarea", required, null, List.of());
    }

    private static FollowupScreen.Field uuid(String key, String label, boolean required) {
        return new FollowupScreen.Field(key, label, "uuid", required, null, List.of());
    }

    private static FollowupScreen.Field date(String key, String label, boolean required) {
        return new FollowupScreen.Field(key, label, "date", required, null, List.of());
    }

    private static FollowupScreen.Field dateTime(String key, String label, boolean required) {
        return new FollowupScreen.Field(key, label, "datetime-local", required, null, List.of());
    }

    private static FollowupScreen.Field decimal(String key, String label, boolean required) {
        return new FollowupScreen.Field(key, label, "number", required, null, List.of());
    }

    private static FollowupScreen.Field number(String key, String label, boolean required) {
        return new FollowupScreen.Field(key, label, "number", required, null, List.of());
    }

    private static FollowupScreen.Field select(
            String key, String label, boolean required, String... entries) {
        var options = new ArrayList<FollowupScreen.Option>();
        for (var index = 0; index < entries.length; index += 2) {
            options.add(new FollowupScreen.Option(entries[index], entries[index + 1]));
        }
        return new FollowupScreen.Field(key, label, "select", required, null, options);
    }
}
