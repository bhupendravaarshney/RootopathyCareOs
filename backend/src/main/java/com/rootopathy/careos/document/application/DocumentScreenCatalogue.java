package com.rootopathy.careos.document.application;

import com.rootopathy.careos.document.domain.DocumentScreen;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Repository-owned contract for the eleven Module 7 document/result screens. */
final class DocumentScreenCatalogue {
    private static final List<String> TITLES = List.of(
            "Document dashboard",
            "Patient document list",
            "Upload document",
            "Classification and metadata",
            "Scan status",
            "Document viewer",
            "Result inbox",
            "Result detail",
            "Acknowledge or escalate",
            "Version history",
            "Export or share intent");
    private static final Map<String, ScreenSpec> SCREENS = screens();

    private DocumentScreenCatalogue() {}

    static ScreenSpec screen(String id) {
        var screen = SCREENS.get(id);
        if (screen == null) {
            throw new DocumentException(
                    DocumentException.Reason.NOT_FOUND,
                    "The requested document or result screen does not exist.");
        }
        return screen;
    }

    static ActionSpec mutation(String screenId, String actionKey) {
        return screen(screenId).actions().stream()
                .filter(action -> action.href() == null && action.key().equals(actionKey))
                .findFirst()
                .orElseThrow(() -> new DocumentException(
                        DocumentException.Reason.NOT_FOUND,
                        "The requested document or result action does not exist."));
    }

    static List<DocumentScreen.Action> projectedActions(
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
            List<DocumentScreen.Field> fields) {
        DocumentScreen.Action projection() {
            return new DocumentScreen.Action(
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
        add(screens, 1,
                "Review document pipeline, result-safety and outstanding acknowledgement state.");
        add(screens, 2,
                "Review minimum-necessary patient-linked documents and current clean-version state.");
        add(screens, 3,
                "Upload an exact bounded file into private quarantine; replacements append a version.",
                action(
                        "upload-document",
                        "Upload document",
                        "document.upload",
                        false,
                        false,
                        true,
                        uuid("patientId", "Patient", true),
                        uuid("encounterId", "Encounter", false),
                        uuid("assessmentSessionId", "Assessment session", false),
                        uuid("replacementDocumentId", "Document to replace", false),
                        text("title", "Document title", true),
                        select(
                                "documentTypeKey",
                                "Document type",
                                true,
                                "diagnostic_report", "Diagnostic report",
                                "clinical_note", "Clinical note",
                                "correspondence", "Correspondence",
                                "consent", "Consent evidence",
                                "other", "Other"),
                        text("sourceKey", "Source", true),
                        file("file", "File", true)));
        add(screens, 4,
                "Append attributed classification, confidentiality and retention metadata.",
                action(
                        "classify-document",
                        "Append classification",
                        "document.classify",
                        true,
                        true,
                        true,
                        text("categoryKey", "Category", true),
                        select(
                                "confidentialityKey",
                                "Confidentiality",
                                true,
                                "normal", "Normal",
                                "restricted", "Restricted",
                                "very_restricted", "Very restricted"),
                        text("retentionClassKey", "Retention class", true),
                        text("sourceKey", "Classification source", true),
                        text("methodKey", "Classification method", true)));
        add(screens, 5,
                "Review exact quarantine/scan/promotion evidence and retry only failed or pending versions.",
                action(
                        "scan-document",
                        "Scan and promote",
                        "document.scan.bind",
                        true,
                        true,
                        false));
        add(screens, 6,
                "Request short-lived purpose-bound access to current clean promoted content.",
                action(
                        "access-document",
                        "Open clean document",
                        "document.access",
                        true,
                        false,
                        true,
                        select(
                                "purposeKey",
                                "Access purpose",
                                true,
                                "clinical_care", "Clinical care",
                                "result_review", "Result review",
                                "patient_request", "Patient request",
                                "security_investigation", "Security investigation")));
        add(screens, 7,
                "Review received diagnostic reports and visible abnormal or critical state.");
        add(screens, 8,
                "Record a provenance-preserving result against one exact clean promoted version.",
                action(
                        "record-diagnostic-report",
                        "Record diagnostic report",
                        "document.result.write",
                        true,
                        true,
                        true,
                        select(
                                "reportType", "Report type", true,
                                "laboratory", "Laboratory",
                                "imaging", "Imaging",
                                "other", "Other"),
                        select(
                                "reportStatus", "Report status", true,
                                "preliminary", "Preliminary",
                                "final", "Final",
                                "amended", "Amended",
                                "entered_in_error", "Entered in error"),
                        uuid("priorReportId", "Prior report", false),
                        uuid("encounterId", "Encounter", false),
                        uuid("authorPractitionerId", "Author clinician", false),
                        text("sourceKey", "Result source", true),
                        text("sourceIdentifier", "Source identifier", true),
                        datetime("issuedAt", "Issued at", true),
                        textarea("summary", "Report summary", true),
                        select(
                                "interpretationStatus", "Interpretation status", true,
                                "uninterpreted", "Uninterpreted",
                                "provisional", "Provisional",
                                "reviewed", "Reviewed",
                                "not_applicable", "Not applicable"),
                        text("testCode", "Lab test code", false),
                        text("testDisplay", "Lab test name", false),
                        text("value", "Lab result value", false),
                        text("unit", "Unit", false),
                        text("referenceRange", "Reference range", false),
                        text("modalityKey", "Imaging modality", false),
                        text("bodySite", "Body site", false),
                        textarea("finding", "Imaging finding", false),
                        textarea("impression", "Imaging impression", false),
                        select(
                                "abnormalFlag", "Abnormal flag", true,
                                "normal", "Normal",
                                "high", "High",
                                "low", "Low",
                                "abnormal", "Abnormal",
                                "critical", "Critical",
                                "unknown", "Unknown"),
                        text("methodKey", "Result method", true),
                        datetime("observedAt", "Observed at", true),
                        uuid("ownerPractitionerId", "Safety owner", false),
                        text("slaPolicyVersion", "SLA policy version", false),
                        datetime("acknowledgementDueAt", "Acknowledgement due", false),
                        textarea("flagSummary", "Flag summary", false)));
        add(screens, 9,
                "Acknowledge, escalate and resolve abnormal or critical results with attributed evidence.",
                action(
                        "acknowledge-result",
                        "Acknowledge result",
                        "document.result.review",
                        true,
                        true,
                        true,
                        uuid("reviewerPractitionerId", "Reviewing clinician", true)),
                action(
                        "resolve-result",
                        "Resolve result",
                        "document.result.review",
                        true,
                        true,
                        true,
                        uuid("reviewerPractitionerId", "Resolving clinician", true)),
                action(
                        "escalate-result",
                        "Record escalation",
                        "document.result.escalate",
                        true,
                        false,
                        true,
                        uuid("ownerPractitionerId", "Escalation owner", true),
                        select(
                                "escalationLevel", "Escalation level", true,
                                "clinical_owner", "Clinical owner",
                                "department_lead", "Department lead",
                                "emergency_pathway", "Emergency pathway"),
                        text("channelKey", "Channel", true),
                        datetime("dueAt", "Due at", true)));
        add(screens, 10,
                "Review immutable file-version, scan and classification lineage.");
        add(screens, 11,
                "Record a purpose-bound export or share intent without claiming delivery.",
                action(
                        "record-access-intent",
                        "Record export/share intent",
                        "document.intent.create",
                        true,
                        false,
                        true,
                        select(
                                "intentType", "Intent", true,
                                "export", "Export",
                                "share", "Share"),
                        text("purposeKey", "Purpose", true),
                        text("requestedFormat", "Requested format", false),
                        select(
                                "recipientType", "Recipient type", false,
                                "patient", "Patient",
                                "practitioner", "Practitioner",
                                "organization", "Organization",
                                "external_party", "External party"),
                        text("recipientReference", "Recipient reference", false)));
        return Map.copyOf(screens);
    }

    private static void add(
            Map<String, ScreenSpec> target,
            int sequence,
            String purpose,
            ActionSpec... actions) {
        var id = "P7-%02d".formatted(sequence);
        target.put(id, new ScreenSpec(
                id, TITLES.get(sequence - 1), purpose, "document.read", List.of(actions)));
    }

    private static ActionSpec action(
            String key,
            String label,
            String operation,
            boolean targetRequired,
            boolean ifMatchRequired,
            boolean reasonRequired,
            DocumentScreen.Field... fields) {
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

    private static DocumentScreen.Field text(String key, String label, boolean required) {
        return new DocumentScreen.Field(key, label, "text", required, null, List.of());
    }

    private static DocumentScreen.Field textarea(String key, String label, boolean required) {
        return new DocumentScreen.Field(key, label, "textarea", required, null, List.of());
    }

    private static DocumentScreen.Field uuid(String key, String label, boolean required) {
        return new DocumentScreen.Field(key, label, "uuid", required, null, List.of());
    }

    private static DocumentScreen.Field datetime(String key, String label, boolean required) {
        return new DocumentScreen.Field(key, label, "datetime-local", required, null, List.of());
    }

    private static DocumentScreen.Field file(String key, String label, boolean required) {
        return new DocumentScreen.Field(
                key,
                label,
                "file",
                required,
                "PDF, PNG, JPEG or plain text; maximum 20 MiB.",
                List.of());
    }

    private static DocumentScreen.Field select(
            String key, String label, boolean required, String... entries) {
        var options = new ArrayList<DocumentScreen.Option>();
        for (var index = 0; index < entries.length; index += 2) {
            options.add(new DocumentScreen.Option(entries[index], entries[index + 1]));
        }
        return new DocumentScreen.Field(key, label, "select", required, null, options);
    }
}
