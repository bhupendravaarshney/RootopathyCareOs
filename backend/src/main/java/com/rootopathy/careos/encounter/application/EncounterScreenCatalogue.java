package com.rootopathy.careos.encounter.application;

import com.rootopathy.careos.encounter.domain.EncounterScreen;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class EncounterScreenCatalogue {
    private static final Map<String, ScreenSpec> SCREENS = screens();

    private EncounterScreenCatalogue() {}

    static ScreenSpec screen(String id) {
        var screen = SCREENS.get(id);
        if (screen == null) {
            throw new EncounterException(
                    EncounterException.Reason.NOT_FOUND,
                    "The requested encounter screen does not exist.");
        }
        return screen;
    }

    static ActionSpec mutation(String screenId, String actionKey) {
        return screen(screenId).actions().stream()
                .filter(action -> action.href() == null && action.key().equals(actionKey))
                .findFirst()
                .orElseThrow(() -> new EncounterException(
                        EncounterException.Reason.NOT_FOUND,
                        "The requested encounter action does not exist."));
    }

    static List<EncounterScreen.Action> projectedActions(
            ScreenSpec screen, Set<String> permissions) {
        return screen.actions().stream()
                .filter(action -> action.href() != null || permissions.contains(action.permission()))
                .map(ActionSpec::projection)
                .toList();
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
            List<EncounterScreen.Field> fields) {
        EncounterScreen.Action projection() {
            return new EncounterScreen.Action(
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
        add(
                screens,
                "P5-01",
                "Encounter dashboard",
                "Review active encounters, unresolved red flags and unsigned clinical work.",
                "encounter.dashboard.read",
                link("open-encounter", "Open encounter", "#/P5-02"),
                link("clinical-work", "Clinical work", "#/P5-08"));
        add(
                screens,
                "P5-02",
                "Open encounter",
                "Create an explicit episode and planned encounter from exact patient and care context.",
                "encounter.context.read",
                action(
                        "open-encounter",
                        "Open encounter",
                        "encounter.open",
                        false,
                        false,
                        true,
                        uuid("patientId", "Patient", true),
                        uuid("appointmentId", "Confirmed appointment", false),
                        uuid("episodeId", "Existing episode", false),
                        uuid("serviceId", "Service", false),
                        uuid("facilityId", "Facility", false),
                        uuid("locationId", "Location", false),
                        uuid("responsiblePractitionerId", "Responsible practitioner", false),
                        select(
                                "encounterType",
                                "Encounter type",
                                true,
                                "consultation",
                                "Consultation",
                                "follow_up",
                                "Follow-up",
                                "procedure",
                                "Procedure",
                                "remote",
                                "Remote"),
                        dateTime("plannedStartAt", "Planned start", true)));
        add(
                screens,
                "P5-03",
                "Patient and appointment context",
                "Review exact patient, appointment and encounter lifecycle context.",
                "encounter.context.read",
                lifecycle("mark-arrived", "Mark arrived"),
                lifecycle("start-encounter", "Start encounter"),
                lifecycle("place-on-hold", "Place on hold"),
                lifecycle("resume-encounter", "Resume encounter"),
                lifecycle("complete-encounter", "Complete encounter"),
                lifecycle("cancel-encounter", "Cancel encounter"),
                lifecycle("enter-encounter-in-error", "Enter in error"));
        add(
                screens,
                "P5-04",
                "Participants",
                "Manage immutable identity, role, assignment and eligibility snapshots.",
                "encounter.context.read",
                action(
                        "add-practitioner-participant",
                        "Add practitioner",
                        "encounter.participant.manage",
                        true,
                        true,
                        true,
                        uuid("practitionerId", "Practitioner", true),
                        select(
                                "roleKey",
                                "Encounter role",
                                true,
                                "attending",
                                "Attending",
                                "consulting",
                                "Consulting",
                                "observer",
                                "Observer")),
                action(
                        "remove-participant",
                        "Remove participant",
                        "encounter.participant.manage",
                        true,
                        true,
                        true));
        add(
                screens,
                "P5-05",
                "Presenting concerns",
                "Append attributed presenting concerns and create visible red-flag escalation when required.",
                "encounter.clinical.read",
                action(
                        "record-presenting-concern",
                        "Record concern",
                        "encounter.concern.write",
                        true,
                        true,
                        false,
                        uuid("authorPractitionerId", "Author practitioner", true),
                        select(
                                "concernKind",
                                "Concern kind",
                                true,
                                "presenting",
                                "Presenting concern",
                                "symptom",
                                "Symptom",
                                "referral_reason",
                                "Referral reason",
                                "red_flag",
                                "Red flag"),
                        textarea("description", "Clinical description", true),
                        text("onset", "Onset", false),
                        select(
                                "severityKey",
                                "Severity",
                                false,
                                "mild",
                                "Mild",
                                "moderate",
                                "Moderate",
                                "severe",
                                "Severe",
                                "critical",
                                "Critical")));
        add(
                screens,
                "P5-06",
                "Clinical timeline",
                "Review minimum-necessary correlated clinical activity in encounter order.",
                "encounter.clinical.read");
        add(
                screens,
                "P5-07",
                "Problems and diagnoses",
                "Append explicitly coded or text-only problems and diagnoses without inventing terminology authority.",
                "encounter.clinical.read",
                action(
                        "record-clinical-problem",
                        "Record problem",
                        "encounter.problem.write",
                        true,
                        true,
                        true,
                        uuid("authorPractitionerId", "Author practitioner", true),
                        text("codeSystem", "Code system", false),
                        text("codeValue", "Code", false),
                        text("displayText", "Problem", true),
                        select(
                                "clinicalStatus",
                                "Clinical status",
                                true,
                                "active",
                                "Active",
                                "inactive",
                                "Inactive",
                                "resolved",
                                "Resolved"),
                        select(
                                "verificationStatus",
                                "Verification",
                                true,
                                "provisional",
                                "Provisional",
                                "confirmed",
                                "Confirmed",
                                "refuted",
                                "Refuted")),
                action(
                        "record-diagnosis",
                        "Record diagnosis",
                        "encounter.problem.write",
                        true,
                        true,
                        true,
                        uuid("authorPractitionerId", "Author practitioner", true),
                        uuid("clinicalProblemId", "Related problem", false),
                        text("codeSystem", "Code system", false),
                        text("codeValue", "Code", false),
                        text("displayText", "Diagnosis", true),
                        select(
                                "certaintyKey",
                                "Certainty",
                                true,
                                "suspected",
                                "Suspected",
                                "provisional",
                                "Provisional",
                                "confirmed",
                                "Confirmed",
                                "refuted",
                                "Refuted"),
                        select(
                                "diagnosisType",
                                "Diagnosis type",
                                true,
                                "working",
                                "Working",
                                "differential",
                                "Differential",
                                "final",
                                "Final")));
        add(
                screens,
                "P5-08",
                "Orders and tasks",
                "Manage internal orders, attributed tasks and explicit red-flag acknowledgement.",
                "encounter.clinical.read",
                action(
                        "create-order",
                        "Create order",
                        "encounter.order.manage",
                        true,
                        true,
                        true,
                        uuid("requesterPractitionerId", "Requester practitioner", true),
                        text("orderTypeKey", "Order type", true),
                        text("codeSystem", "Code system", false),
                        text("codeValue", "Code", false),
                        text("displayText", "Order", true),
                        textarea("instructionText", "Instructions", false),
                        select(
                                "priorityKey",
                                "Priority",
                                true,
                                "routine",
                                "Routine",
                                "urgent",
                                "Urgent",
                                "stat",
                                "STAT")),
                action(
                        "create-clinical-task",
                        "Create task",
                        "encounter.task.manage",
                        true,
                        true,
                        true,
                        uuid("ownerPractitionerId", "Owner practitioner", false),
                        text("taskTypeKey", "Task type", true),
                        textarea("description", "Task", true),
                        select(
                                "priorityKey",
                                "Priority",
                                true,
                                "routine",
                                "Routine",
                                "urgent",
                                "Urgent")),
                action(
                        "progress-clinical-task",
                        "Progress task",
                        "encounter.task.manage",
                        true,
                        true,
                        true,
                        select(
                                "nextStatus",
                                "Next status",
                                true,
                                "in_progress",
                                "In progress",
                                "completed",
                                "Completed",
                                "cancelled",
                                "Cancelled",
                                "entered_in_error",
                                "Entered in error")),
                action(
                        "acknowledge-red-flag",
                        "Acknowledge red flag",
                        "encounter.red_flag.acknowledge",
                        true,
                        true,
                        true,
                        uuid("practitionerId", "Acknowledging practitioner", true)),
                action(
                        "resolve-red-flag",
                        "Resolve red flag",
                        "encounter.red_flag.acknowledge",
                        true,
                        true,
                        true,
                        uuid("practitionerId", "Resolving practitioner", true)));
        add(
                screens,
                "P5-09",
                "Encounter notes",
                "Create append-only, digest-bound draft note versions.",
                "encounter.clinical.read",
                action(
                        "save-note-version",
                        "Save note version",
                        "encounter.note.write",
                        true,
                        true,
                        false,
                        uuid("noteId", "Existing note", false),
                        uuid("authorPractitionerId", "Author practitioner", true),
                        text("noteTypeKey", "Note type", true),
                        textarea("content", "Clinical note", true),
                        checkbox("lateEntry", "Late entry", false)));
        add(
                screens,
                "P5-10",
                "Review and sign",
                "Sign the exact current note version using current practitioner identity and eligibility.",
                "encounter.clinical.read",
                action(
                        "sign-note",
                        "Sign note",
                        "encounter.note.sign",
                        true,
                        true,
                        true,
                        uuid("signerPractitionerId", "Signer practitioner", true),
                        select(
                                "signatureMeaning",
                                "Signature meaning",
                                true,
                                "author",
                                "Author",
                                "reviewer",
                                "Reviewer",
                                "cosigner",
                                "Cosigner")));
        add(
                screens,
                "P5-11",
                "Amendment",
                "Append a signed correction linked to the exact signed note version.",
                "encounter.clinical.read",
                action(
                        "amend-signed-note",
                        "Add amendment",
                        "encounter.amend",
                        true,
                        true,
                        true,
                        uuid("authorPractitionerId", "Author practitioner", true),
                        textarea("amendmentText", "Amendment", true)));
        add(
                screens,
                "P5-12",
                "Encounter history",
                "Review allow-listed lifecycle, signature and amendment evidence without raw payloads.",
                "encounter.history.read");
        return Map.copyOf(screens);
    }

    private static ActionSpec lifecycle(String key, String label) {
        return action(key, label, "encounter.lifecycle.manage", true, true, true);
    }

    private static void add(
            Map<String, ScreenSpec> screens,
            String id,
            String title,
            String purpose,
            String readOperation,
            ActionSpec... actions) {
        screens.put(id, new ScreenSpec(id, title, purpose, readOperation, List.of(actions)));
    }

    private static ActionSpec action(
            String key,
            String label,
            String operation,
            boolean targetRequired,
            boolean ifMatchRequired,
            boolean reasonRequired,
            EncounterScreen.Field... fields) {
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

    private static ActionSpec link(String key, String label, String href) {
        return new ActionSpec(key, label, "", "", "link", false, false, false, href, List.of());
    }

    private static EncounterScreen.Field uuid(String key, String label, boolean required) {
        return new EncounterScreen.Field(key, label, "uuid", required, null, List.of());
    }

    private static EncounterScreen.Field text(String key, String label, boolean required) {
        return new EncounterScreen.Field(key, label, "text", required, null, List.of());
    }

    private static EncounterScreen.Field textarea(String key, String label, boolean required) {
        return new EncounterScreen.Field(key, label, "textarea", required, null, List.of());
    }

    private static EncounterScreen.Field dateTime(String key, String label, boolean required) {
        return new EncounterScreen.Field(key, label, "datetime-local", required, null, List.of());
    }

    private static EncounterScreen.Field checkbox(String key, String label, boolean required) {
        return new EncounterScreen.Field(key, label, "checkbox", required, null, List.of());
    }

    private static EncounterScreen.Field select(
            String key, String label, boolean required, String... entries) {
        var options = new java.util.ArrayList<EncounterScreen.Option>();
        for (var index = 0; index < entries.length; index += 2) {
            options.add(new EncounterScreen.Option(entries[index], entries[index + 1]));
        }
        return new EncounterScreen.Field(key, label, "select", required, null, options);
    }
}
