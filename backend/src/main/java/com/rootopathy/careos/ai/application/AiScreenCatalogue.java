package com.rootopathy.careos.ai.application;

import com.rootopathy.careos.ai.domain.AiScreen;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Repository-owned contract for the ten Module 8 AI assistance screens. */
final class AiScreenCatalogue {
    private static final List<String> TITLES = List.of(
            "AI session launcher",
            "Purpose and consent check",
            "Input selection",
            "Transcription and extraction",
            "Draft summary",
            "Clinical suggestion panel",
            "Safety and uncertainty flags",
            "Source and provenance viewer",
            "Clinician review and approval",
            "AI session history");
    private static final Map<String, ScreenSpec> SCREENS = screens();

    private AiScreenCatalogue() {}

    static ScreenSpec screen(String id) {
        var screen = SCREENS.get(id);
        if (screen == null) {
            throw new AiException(AiException.Reason.NOT_FOUND, "The requested AI screen does not exist.");
        }
        return screen;
    }

    static ActionSpec mutation(String screenId, String actionKey) {
        return screen(screenId).actions().stream()
                .filter(action -> action.key().equals(actionKey))
                .findFirst()
                .orElseThrow(() -> new AiException(
                        AiException.Reason.NOT_FOUND, "The requested AI action does not exist."));
    }

    static List<AiScreen.Action> projectedActions(ScreenSpec screen, Set<String> permissions) {
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
            List<AiScreen.Field> fields) {
        AiScreen.Action projection() {
            return new AiScreen.Action(
                    key, label, style, targetRequired, ifMatchRequired, reasonRequired, fields);
        }
    }

    private static Map<String, ScreenSpec> screens() {
        var screens = new LinkedHashMap<String, ScreenSpec>();
        add(screens, 1, "Launch a draft AI session without invoking a model.",
                action("launch-session", "Launch AI session", "ai.session.launch", false, false,
                        uuid("patientId", "Patient", true),
                        uuid("encounterId", "Encounter", true),
                        select("sessionType", "AI task", true,
                                "transcription", "Transcription",
                                "extraction", "Extraction",
                                "summary", "Draft summary",
                                "clinical_suggestion", "Clinical suggestion")));
        add(screens, 2, "Record explicit purpose, legal basis, consent and minimum-necessary confirmation.",
                action("record-purpose-consent", "Authorize purpose and consent", "ai.consent.record", true, true,
                        select("purposeKey", "Purpose", true,
                                "clinical_documentation", "Clinical documentation",
                                "clinical_review", "Clinical review",
                                "care_coordination", "Care coordination"),
                        text("legalBasisKey", "Legal basis", true),
                        select("consentStatus", "Consent status", true,
                                "granted", "Granted",
                                "not_required", "Not required by approved policy"),
                        text("consentReference", "Consent evidence reference", false),
                        select("minimumNecessaryConfirmed", "Minimum necessary", true,
                                "true", "Confirmed")));
        add(screens, 3, "Approve exact minimum-necessary source references and digests.",
                action("select-input", "Approve input manifest", "ai.input.select", true, true,
                        select("sourceType", "Source type", true,
                                "encounter", "Encounter",
                                "assessment", "Assessment",
                                "document", "Document",
                                "diagnostic_report", "Diagnostic report"),
                        uuid("sourceId", "Source record", true),
                        number("sourceRevision", "Source revision", true),
                        text("sourceDigest", "Source SHA-256", true),
                        text("dataCategories", "Data categories", true),
                        textarea("selectionReason", "Selection justification", true)));
        add(screens, 4, "Create a versioned processing contract; the provider remains fail closed by default.",
                action("request-processing", "Request processing", "ai.process.request", true, true,
                        text("parameters", "Bounded parameters", false)));
        add(screens, 5, "Review and append a clinician-edited version of the visibly labeled draft.",
                action("edit-output", "Append clinician edit", "ai.output.edit", true, true,
                        textarea("content", "Edited draft", true),
                        textarea("editSummary", "Edit summary", true)));
        add(screens, 6, "Review draft clinical suggestions; no suggestion is accepted automatically.");
        add(screens, 7, "Review visible uncertainty and safety flags with escalation evidence.",
                action("acknowledge-safety-flag", "Acknowledge flag", "ai.safety.review", true, true,
                        uuid("safetyFlagId", "Safety flag", true)),
                action("resolve-safety-flag", "Resolve flag", "ai.safety.review", true, true,
                        uuid("safetyFlagId", "Safety flag", true)));
        add(screens, 8, "Trace every draft claim to an exact approved input-manifest item.");
        add(screens, 9, "Explicitly accept or reject the exact latest draft version.",
                action("decide-output", "Record clinician decision", "ai.review.decide", true, true,
                        select("decision", "Decision", true,
                                "accepted", "Accept",
                                "rejected", "Reject"),
                        uuid("reviewerPractitionerId", "Reviewing clinician", true)));
        add(screens, 10, "Review immutable AI session, job, output, safety and decision history.",
                action("cancel-session", "Cancel session", "ai.session.cancel", true, true));
        return Map.copyOf(screens);
    }

    private static void add(
            Map<String, ScreenSpec> target, int sequence, String purpose, ActionSpec... actions) {
        var id = "P8-%02d".formatted(sequence);
        target.put(id, new ScreenSpec(id, TITLES.get(sequence - 1), purpose, "ai.read", List.of(actions)));
    }

    private static ActionSpec action(
            String key,
            String label,
            String operation,
            boolean targetRequired,
            boolean ifMatchRequired,
            AiScreen.Field... fields) {
        return new ActionSpec(
                key, label, operation, operation, "primary", targetRequired, ifMatchRequired, true,
                List.of(fields));
    }

    private static AiScreen.Field text(String key, String label, boolean required) {
        return new AiScreen.Field(key, label, "text", required, null, List.of());
    }

    private static AiScreen.Field textarea(String key, String label, boolean required) {
        return new AiScreen.Field(key, label, "textarea", required, null, List.of());
    }

    private static AiScreen.Field uuid(String key, String label, boolean required) {
        return new AiScreen.Field(key, label, "uuid", required, null, List.of());
    }

    private static AiScreen.Field number(String key, String label, boolean required) {
        return new AiScreen.Field(key, label, "number", required, null, List.of());
    }

    private static AiScreen.Field select(
            String key, String label, boolean required, String... entries) {
        var options = new ArrayList<AiScreen.Option>();
        for (var index = 0; index < entries.length; index += 2) {
            options.add(new AiScreen.Option(entries[index], entries[index + 1]));
        }
        return new AiScreen.Field(key, label, "select", required, null, options);
    }
}
