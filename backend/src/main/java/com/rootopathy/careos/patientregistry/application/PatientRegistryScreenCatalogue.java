package com.rootopathy.careos.patientregistry.application;

import com.rootopathy.careos.patientregistry.domain.PatientRegistryScreen;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class PatientRegistryScreenCatalogue {
    private static final Map<String, ScreenSpec> SCREENS = screens();

    private PatientRegistryScreenCatalogue() {}

    static ScreenSpec screen(String id) {
        var screen = SCREENS.get(id);
        if (screen == null) {
            throw new PatientRegistryException(
                    PatientRegistryException.Reason.NOT_FOUND,
                    "The requested patient-registry screen does not exist.");
        }
        return screen;
    }

    static ActionSpec mutation(String screenId, String actionKey) {
        return screen(screenId).actions().stream()
                .filter(action -> action.key().equals(actionKey) && action.href() == null)
                .findFirst()
                .orElseThrow(() -> new PatientRegistryException(
                        PatientRegistryException.Reason.NOT_FOUND,
                        "The requested patient-registry action does not exist."));
    }

    static List<PatientRegistryScreen.Action> projectedActions(
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
            List<PatientRegistryScreen.Field> fields) {
        PatientRegistryScreen.Action projection() {
            return new PatientRegistryScreen.Action(
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
                "P3-01",
                "Patient registry dashboard",
                "Review organization-local patient activity, registration and duplicate work.",
                "patient.dashboard.read",
                link("start-registration", "Start registration", "#/P3-03"),
                link("review-duplicates", "Review duplicate queue", "#/P3-14"));
        add(
                screens,
                "P3-02",
                "Patient directory",
                "Search minimum-necessary organization-local patient records.",
                "patient.directory.read",
                link("start-registration", "Start registration", "#/P3-03"));
        add(
                screens,
                "P3-03",
                "Start patient registration",
                "Create an expiring governed registration run before collecting patient data.",
                "patient.registration.start",
                action(
                        "start-registration",
                        "Start registration",
                        "patient.registration.start",
                        false,
                        false,
                        true,
                        text("registrationSource", "Registration source", true),
                        text("supplierRelationshipKey", "Supplier relationship", false),
                        text("purposeKey", "Purpose", true),
                        uuid("facilityId", "Facility", false),
                        bool("urgent", "Urgent temporary pathway", true),
                        text("urgentReasonCode", "Urgent reason code", false)));
        add(
                screens,
                "P3-04",
                "Duplicate search",
                "Search before create and record an explicit duplicate disposition.",
                "patient.duplicate.search",
                action(
                        "search-duplicates",
                        "Run bounded search",
                        "patient.duplicate.search",
                        true,
                        true,
                        false,
                        text("officialName", "Official or supplied name", false),
                        date("birthDate", "Birth date", false),
                        text("contact", "Contact value", false),
                        text("identifier", "Identifier value", false)),
                action(
                        "record-registration-disposition",
                        "Record disposition",
                        "patient.registration.manage",
                        true,
                        true,
                        true,
                        select(
                                "dispositionCode",
                                "Disposition",
                                true,
                                "create_new",
                                "Create a new patient",
                                "use_existing",
                                "Use an existing patient",
                                "escalate_review",
                                "Escalate for review",
                                "urgent_temporary",
                                "Create urgent temporary identity"),
                        uuid("selectedPatientId", "Selected existing patient", false),
                        text("officialGivenName", "Official given name", false),
                        text("officialFamilyName", "Official family name", false),
                        text("nameToUse", "Name to use", false),
                        text("temporaryReasonCode", "Temporary identity reason", false),
                        text("provenanceCode", "Provenance code", true)));
        add(
                screens,
                "P3-05",
                "Identity and demographics",
                "Maintain patient identity with partial-date and provenance semantics.",
                "patient.profile.read",
                action(
                        "correct-identity",
                        "Save identity correction",
                        "patient.profile.manage",
                        true,
                        true,
                        true,
                        text("officialGivenName", "Official given name", false),
                        text("officialFamilyName", "Official family name", false),
                        text("nameToUse", "Name to use", false),
                        select(
                                "nameState",
                                "Name state",
                                true,
                                "provided",
                                "Provided",
                                "temporary",
                                "Temporary",
                                "unnamed",
                                "Unnamed",
                                "unknown",
                                "Unknown"),
                        date("birthDate", "Birth date", false),
                        select(
                                "birthDatePrecision",
                                "Birth-date precision",
                                false,
                                "year",
                                "Year",
                                "month",
                                "Month",
                                "day",
                                "Day"),
                        select(
                                "birthDateCertainty",
                                "Birth-date certainty",
                                true,
                                "exact",
                                "Exact",
                                "estimated",
                                "Estimated",
                                "unknown",
                                "Unknown"),
                        text("administrativeSexCode", "Administrative recorded sex code", false),
                        text("genderIdentityCode", "Gender identity code", false),
                        text("pronounsCode", "Pronouns code", false),
                        text("provenanceCode", "Provenance code", true)));
        add(
                screens,
                "P3-06",
                "Contacts and addresses",
                "Maintain protected contact and address records without inferring consent.",
                "patient.contact.read",
                action(
                        "add-contact",
                        "Add contact",
                        "patient.contact.manage",
                        true,
                        true,
                        true,
                        select(
                                "channel",
                                "Channel",
                                true,
                                "email",
                                "Email",
                                "phone",
                                "Phone",
                                "sms",
                                "SMS",
                                "other",
                                "Other"),
                        text("contactUse", "Use", true),
                        text("purposeKey", "Purpose", true),
                        text("value", "Contact value", true),
                        bool("primary", "Primary", true),
                        bool("preferred", "Preferred", true),
                        bool("confidential", "Confidential", true),
                        text("provenanceCode", "Provenance code", true)),
                action(
                        "add-address",
                        "Add address",
                        "patient.contact.manage",
                        true,
                        true,
                        true,
                        text("addressUse", "Address use", true),
                        text("purposeKey", "Purpose", true),
                        text("line1", "Address line 1", true),
                        text("line2", "Address line 2", false),
                        text("locality", "Locality", true),
                        text("region", "Region", false),
                        text("postalCode", "Postal code", false),
                        text("countryCode", "Country code", true),
                        bool("primary", "Primary", true),
                        bool("preferred", "Preferred", true),
                        bool("confidential", "Confidential", true),
                        text("provenanceCode", "Provenance code", true)));
        add(
                screens,
                "P3-07",
                "Communication preferences",
                "Record preferences independently from consent and provider availability.",
                "patient.profile.read",
                action(
                        "set-communication-preference",
                        "Set preference",
                        "patient.preference.manage",
                        true,
                        true,
                        true,
                        text("purposeKey", "Purpose", true),
                        select(
                                "channel",
                                "Channel",
                                true,
                                "email",
                                "Email",
                                "phone",
                                "Phone",
                                "sms",
                                "SMS",
                                "postal",
                                "Postal",
                                "portal",
                                "Portal",
                                "other",
                                "Other"),
                        select(
                                "decision",
                                "Preference",
                                true,
                                "allow",
                                "Allow",
                                "deny",
                                "Deny",
                                "prefer",
                                "Prefer"),
                        text("languageTag", "Language tag", false),
                        text("accessibleFormatKey", "Accessible format", false),
                        text("provenanceCode", "Provenance code", true)));
        add(
                screens,
                "P3-08",
                "Patient identifiers",
                "Review masked identifiers; activation remains closed until a local scheme is approved.",
                "patient.profile.read");
        add(
                screens,
                "P3-09",
                "Caregivers and proxies",
                "Record relationship facts separately from proxy authority and portal linkage.",
                "patient.proxy.read",
                action(
                        "add-caregiver-relationship",
                        "Add relationship fact",
                        "patient.profile.manage",
                        true,
                        true,
                        true,
                        uuid("relatedPersonReference", "Related-person reference", true),
                        uuid("relatedPatientId", "Related patient", false),
                        text("relationshipTypeKey", "Relationship type", true),
                        text("displayLabel", "Display label", true),
                        text("provenanceCode", "Provenance code", true)));
        add(
                screens,
                "P3-10",
                "Consent and privacy",
                "Review directives and restrictions without treating consent as a universal legal basis.",
                "patient.consent.read");
        add(
                screens,
                "P3-11",
                "Clinical safety flags",
                "Review concise governed flags; detailed clinical records remain separate.",
                "patient.safety_flag.read");
        add(
                screens,
                "P3-12",
                "Review and register",
                "Validate the exact registration revision and complete it atomically.",
                "patient.registration.manage",
                action(
                        "validate-registration",
                        "Run registration validation",
                        "patient.registration.manage",
                        true,
                        true,
                        true),
                action(
                        "submit-registration",
                        "Register patient",
                        "patient.registration.submit",
                        true,
                        true,
                        true));
        add(
                screens,
                "P3-13",
                "Patient summary",
                "Review the canonical minimum-necessary patient record and lifecycle.",
                "patient.profile.read",
                action(
                        "change-patient-lifecycle",
                        "Change lifecycle",
                        "patient.profile.manage",
                        true,
                        true,
                        true,
                        select(
                                "newState",
                                "New state",
                                true,
                                "active",
                                "Active",
                                "inactive",
                                "Inactive",
                                "entered_in_error",
                                "Entered in error"),
                        text("reasonCode", "Reason code", true)));
        add(
                screens,
                "P3-14",
                "Duplicate review queue",
                "Claim and disposition explainable organization-local duplicate candidates.",
                "patient.duplicate.review",
                action(
                        "claim-duplicate",
                        "Claim review",
                        "patient.duplicate.review",
                        true,
                        true,
                        true),
                action(
                        "disposition-duplicate",
                        "Record disposition",
                        "patient.duplicate.review",
                        true,
                        true,
                        true,
                        select(
                                "dispositionCode",
                                "Disposition",
                                true,
                                "not_duplicate",
                                "Not a duplicate",
                                "same_patient_no_merge",
                                "Same patient; do not merge",
                                "insufficient_evidence",
                                "Insufficient evidence"),
                        text("reasonCode", "Reason code", true)));
        add(
                screens,
                "P3-15",
                "Merge review",
                "Request, independently decide and execute an exact impact-bound merge.",
                "patient.duplicate.review",
                action(
                        "request-patient-merge",
                        "Request merge",
                        "patient.merge.request",
                        true,
                        true,
                        true,
                        uuid("survivorPatientId", "Surviving patient", true),
                        uuid("duplicatePatientId", "Duplicate patient", true),
                        text("reasonCode", "Reason code", true)),
                action(
                        "decide-patient-merge",
                        "Record independent decision",
                        "patient.merge.decide",
                        true,
                        true,
                        true,
                        select(
                                "decisionCode",
                                "Decision",
                                true,
                                "approve",
                                "Approve",
                                "reject",
                                "Reject"),
                        text("reasonCode", "Reason code", true)),
                action(
                        "execute-patient-merge",
                        "Execute approved merge",
                        "patient.merge.execute",
                        true,
                        true,
                        true,
                        uuid("decisionId", "Approved decision", true)));
        add(
                screens,
                "P3-16",
                "Identity and audit timeline",
                "Review allow-listed patient identity evidence without raw audit payloads.",
                "patient.timeline.read");
        return Map.copyOf(screens);
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
            PatientRegistryScreen.Field... fields) {
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

    private static PatientRegistryScreen.Field text(String key, String label, boolean required) {
        return field(key, label, "text", required, List.of());
    }

    private static PatientRegistryScreen.Field date(String key, String label, boolean required) {
        return field(key, label, "date", required, List.of());
    }

    private static PatientRegistryScreen.Field uuid(String key, String label, boolean required) {
        return field(key, label, "uuid", required, List.of());
    }

    private static PatientRegistryScreen.Field bool(String key, String label, boolean required) {
        return select(key, label, required, "false", "No", "true", "Yes");
    }

    private static PatientRegistryScreen.Field select(
            String key, String label, boolean required, String... valueLabels) {
        var options = new java.util.ArrayList<PatientRegistryScreen.Option>();
        for (var index = 0; index < valueLabels.length; index += 2) {
            options.add(new PatientRegistryScreen.Option(valueLabels[index], valueLabels[index + 1]));
        }
        return field(key, label, "select", required, options);
    }

    private static PatientRegistryScreen.Field field(
            String key,
            String label,
            String inputType,
            boolean required,
            List<PatientRegistryScreen.Option> options) {
        return new PatientRegistryScreen.Field(key, label, inputType, required, null, options);
    }
}
