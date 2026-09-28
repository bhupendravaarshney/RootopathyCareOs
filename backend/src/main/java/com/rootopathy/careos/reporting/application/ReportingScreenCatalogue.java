package com.rootopathy.careos.reporting.application;

import com.rootopathy.careos.reporting.domain.ReportingScreen;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Repository-owned contract for the ten Module 12 reporting screens. */
final class ReportingScreenCatalogue {
    private static final List<String> TITLES = List.of(
            "Reporting dashboard",
            "Operational reports",
            "Clinical safety reports",
            "Outcome reports",
            "Workforce governance",
            "Access and security reports",
            "AI governance",
            "Financial reports",
            "Scheduled exports",
            "Report audit and history");
    private static final Map<String, ScreenSpec> SCREENS = screens();

    private ReportingScreenCatalogue() {}

    static ScreenSpec screen(String id) {
        var screen = SCREENS.get(id);
        if (screen == null) {
            throw new ReportingException(
                    ReportingException.Reason.NOT_FOUND,
                    "The requested reporting screen does not exist.");
        }
        return screen;
    }

    static ActionSpec mutation(String screenId, String actionKey) {
        return screen(screenId).actions().stream()
                .filter(action -> action.key().equals(actionKey))
                .findFirst()
                .orElseThrow(() -> new ReportingException(
                        ReportingException.Reason.NOT_FOUND,
                        "The requested reporting action does not exist."));
    }

    static List<ReportingScreen.Action> projectedActions(
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
            List<ReportingScreen.Field> fields) {
        ReportingScreen.Action projection() {
            return new ReportingScreen.Action(
                    key, label, style, targetRequired, ifMatchRequired, reasonRequired, fields);
        }
    }

    private static Map<String, ScreenSpec> screens() {
        var screens = new LinkedHashMap<String, ScreenSpec>();
        add(screens, 1,
                "Review aggregate report coverage, active schedules and export exceptions.");
        add(screens, 2,
                "Run bounded aggregate appointment and encounter reports without patient detail.", run());
        add(screens, 3,
                "Run bounded aggregate result-safety reports without result values or narratives.", run());
        add(screens, 4,
                "Run bounded aggregate outcome and escalation reports without measurement values.", run());
        add(screens, 5,
                "Run bounded aggregate workforce-readiness and credential-governance reports.", run());
        add(screens, 6,
                "Run bounded aggregate access and security evidence reports.", run());
        add(screens, 7,
                "Run bounded aggregate AI lifecycle and safety-governance reports.", run());
        add(screens, 8,
                "Run bounded aggregate invoice and reconciliation reports without payment references.", run());
        add(screens, 9,
                "Define governed schedules and request an expiring private export for one exact report run.",
                action("create-report-schedule", "Create schedule", "reporting.schedule.create", false, false,
                        code("scheduleCode", "Schedule code", true), reportKey(), format(), cadence(),
                        number("lookbackDays", "Lookback days", true),
                        text("timezone", "Timezone", true), dateTime("nextRunAt", "Next run", true)),
                action("pause-report-schedule", "Pause schedule", "reporting.schedule.pause", true, true),
                action("resume-report-schedule", "Resume schedule", "reporting.schedule.resume", true, true),
                action("cancel-report-schedule", "Cancel schedule", "reporting.schedule.cancel", true, true),
                action("request-report-export", "Request export", "reporting.export.create", true, true,
                        format()));
        add(screens, 10,
                "Review attributable report-run, schedule and export history without source-record content.");
        return Map.copyOf(screens);
    }

    private static ActionSpec run() {
        return action("run-report", "Run report", "reporting.run.create", false, false,
                date("periodStart", "Period start", true), date("periodEnd", "Period end", true));
    }

    private static void add(
            Map<String, ScreenSpec> target, int sequence, String purpose, ActionSpec... actions) {
        var id = "P12-%02d".formatted(sequence);
        target.put(id, new ScreenSpec(
                id, TITLES.get(sequence - 1), purpose, "reporting.read", List.of(actions)));
    }

    private static ActionSpec action(
            String key,
            String label,
            String operation,
            boolean targetRequired,
            boolean ifMatchRequired,
            ReportingScreen.Field... fields) {
        return new ActionSpec(
                key, label, operation, operation, "primary", targetRequired,
                ifMatchRequired, true, List.of(fields));
    }

    private static ReportingScreen.Field text(String key, String label, boolean required) {
        return new ReportingScreen.Field(key, label, "text", required, null, List.of());
    }

    private static ReportingScreen.Field code(String key, String label, boolean required) {
        return text(key, label, required);
    }

    private static ReportingScreen.Field date(String key, String label, boolean required) {
        return new ReportingScreen.Field(key, label, "date", required, null, List.of());
    }

    private static ReportingScreen.Field dateTime(String key, String label, boolean required) {
        return new ReportingScreen.Field(key, label, "datetime-local", required, null, List.of());
    }

    private static ReportingScreen.Field number(String key, String label, boolean required) {
        return new ReportingScreen.Field(key, label, "number", required, null, List.of());
    }

    private static ReportingScreen.Field reportKey() {
        return select("reportKey", "Report family", true,
                "operational", "Operational",
                "clinical_safety", "Clinical safety",
                "outcomes", "Outcomes",
                "workforce_governance", "Workforce governance",
                "access_security", "Access and security",
                "ai_governance", "AI governance",
                "financial", "Financial");
    }

    private static ReportingScreen.Field format() {
        return select("format", "Format", true, "csv", "CSV", "json", "JSON");
    }

    private static ReportingScreen.Field cadence() {
        return select("cadence", "Cadence", true,
                "daily", "Daily", "weekly", "Weekly", "monthly", "Monthly");
    }

    private static ReportingScreen.Field select(
            String key, String label, boolean required, String... entries) {
        var options = new ArrayList<ReportingScreen.Option>();
        for (var index = 0; index < entries.length; index += 2) {
            options.add(new ReportingScreen.Option(entries[index], entries[index + 1]));
        }
        return new ReportingScreen.Field(key, label, "select", required, null, options);
    }
}
