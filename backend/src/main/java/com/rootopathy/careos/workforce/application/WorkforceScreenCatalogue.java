package com.rootopathy.careos.workforce.application;

import com.rootopathy.careos.workforce.domain.WorkforceScreen;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class WorkforceScreenCatalogue {
    private static final Map<String, ScreenSpec> SCREENS = screens();

    private WorkforceScreenCatalogue() {}

    static ScreenSpec screen(String id) {
        var screen = SCREENS.get(id);
        if (screen == null) {
            throw new WorkforceException(
                    WorkforceException.Reason.NOT_FOUND, "The requested workforce screen does not exist.");
        }
        return screen;
    }

    static ActionSpec mutation(String screenId, String actionKey) {
        return screen(screenId).actions().stream()
                .filter(action -> action.key().equals(actionKey)
                        && action.href() == null
                        && !action.key().equals("upload-document")
                        && !action.key().equals("access-export"))
                .findFirst()
                .orElseThrow(() -> new WorkforceException(
                        WorkforceException.Reason.NOT_FOUND,
                        "The requested workforce action does not exist."));
    }

    static List<WorkforceScreen.Action> projectedActions(ScreenSpec screen, Set<String> permissions) {
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
            List<WorkforceScreen.Field> fields) {
        WorkforceScreen.Action projection() {
            return new WorkforceScreen.Action(
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
        add(screens, "M2-01", "Workforce dashboard", "Review workforce readiness, credentials and exceptions.", "workforce.dashboard.read",
                link("start-onboarding", "Start onboarding", "#/M2-03"));
        add(screens, "M2-02", "Workforce directory", "Search and filter persisted workforce records.", "workforce.directory.read",
                link("add-member", "Add workforce member", "#/M2-03"),
                action("request-export", "Request directory export", "workforce.export.request", false, false, true,
                        select("projection", "Projection", true, "workforce-directory-summary-v1", "Directory summary"),
                        select("format", "Format", true, "csv", "CSV", "jsonl", "JSON Lines"),
                        exportPurpose(), text("legalBasisKey", "Legal-basis registry key", true)));
        add(screens, "M2-03", "Add workforce member", "Start a clinical or non-clinical onboarding pathway.", "workforce.member.read",
                action("start-onboarding", "Start pathway", "workforce.member.create", false, false, true,
                        select("pathway", "Pathway", true, "clinical", "Clinical", "non_clinical", "Non-clinical"),
                        text("legalGivenName", "Legal given name", true),
                        text("legalFamilyName", "Legal family name", true),
                        text("displayName", "Display name", true),
                        date("birthDate", "Date of birth", false),
                        date("proposedStartDate", "Proposed start date", false),
                        select("accountAccessIntent", "Account access intent", true,
                                "deferred", "Decide later", "existing_user", "Existing user", "invitation", "Invitation", "none_required", "No account required")));
        add(screens, "M2-04", "Duplicate and person search", "Match an existing person before creating a record.", "workforce.person.match",
                action("record-match-decision", "Record match decision", "workforce.person.match", true, true, true,
                        select("decisionCode", "Decision", true, "different_person", "Create new person", "same_person", "Use existing person"),
                        text("matchRunId", "Match run ID", true),
                        text("candidateReference", "Candidate reference", true)));
        add(screens, "M2-05", "Personal and contact information", "Capture governed identity and contact details.", "workforce.person.restricted_read",
                action("save-identity", "Save identity proposal", "workforce.person.correct", true, true, true,
                        text("legalGivenName", "Legal given name", true),
                        text("legalFamilyName", "Legal family name", true),
                        text("displayName", "Display name", true),
                        date("birthDate", "Date of birth", false),
                        text("preferredLocale", "Preferred locale", false),
                        text("workEmail", "Work email", false),
                        text("workPhone", "Work phone", false),
                        text("provenanceCode", "Provenance code", true)));
        add(screens, "M2-06", "Engagement and employment details", "Create an effective-dated engagement.", "workforce.engagement.read",
                action("save-engagement", "Save engagement", "workforce.engagement.manage", true, false, true,
                        select("engagementType", "Engagement type", true, "employee", "Employee", "contractor", "Contractor", "volunteer", "Volunteer", "visiting", "Visiting", "agency", "Agency"),
                        text("employmentCategoryKey", "Employment category key", true),
                        datetime("effectiveFrom", "Effective from", true),
                        datetime("effectiveTo", "Effective to", false)),
                action("activate-engagement", "Activate engagement", "workforce.engagement.lifecycle", true, true, true));
        add(screens, "M2-07", "Practitioner profile", "Define profession without granting access or eligibility.", "workforce.practitioner.read",
                action("save-practitioner", "Save practitioner draft", "workforce.practitioner.manage", true, false, true,
                        uuid("professionEntryId", "Profession entry", true),
                        uuid("professionVersionId", "Profession version", true),
                        text("clinicalTitle", "Clinical title", true),
                        bool("regulated", "Regulated profession", true),
                        datetime("effectiveFrom", "Effective from", true)),
                action("activate-practitioner", "Activate practitioner profile", "workforce.practitioner.lifecycle", true, true, true));
        add(screens, "M2-08", "Qualifications", "Create, verify and supersede qualifications.", "credential.qualification.read",
                action("add-qualification", "Add qualification", "credential.qualification.manage", true, false, true,
                        uuid("qualificationEntryId", "Qualification entry", true),
                        uuid("qualificationVersionId", "Qualification version", true),
                        text("awardingBody", "Awarding body", true),
                        text("countryCode", "Country code", true),
                        date("awardedOn", "Awarded on", true),
                        date("expiresOn", "Expires on", false),
                        uuid("supersedesId", "Corrected or renewed qualification", false)),
                action("submit-qualification", "Submit qualification", "credential.qualification.manage", true, true, true),
                action("decide-qualification", "Record qualification decision", "credential.qualification.manage", true, true, true,
                        select("decisionCode", "Decision", true, "verified", "Verify", "rejected", "Reject", "returned_for_correction", "Return for correction")));
        add(screens, "M2-09", "Professional registrations and licences", "Manage renewal, suspension and revocation history.", "credential.registration.read",
                action("add-registration", "Add registration", "credential.registration.manage", true, false, true,
                        uuid("regulatorEntryId", "Regulator entry", true),
                        uuid("regulatorVersionId", "Regulator version", true),
                        uuid("registrationTypeEntryId", "Registration type entry", true),
                        uuid("registrationTypeVersionId", "Registration type version", true),
                        text("registrationNumber", "Registration number", true),
                        text("jurisdictionCountry", "Jurisdiction country", true),
                        date("validFrom", "Valid from", true),
                        date("expiresOn", "Expires on", false),
                        uuid("supersedesId", "Registration being renewed", false)),
                action("submit-registration", "Submit registration", "credential.registration.manage", true, true, true),
                action("verify-registration", "Verify registration", "credential.registration.lifecycle", true, true, true),
                action("suspend-registration", "Suspend registration", "credential.registration.lifecycle", true, true, true,
                        text("reasonCode", "Authority reason code", true),
                        uuid("authorityEvidenceId", "Authority evidence reference", true),
                        datetime("effectiveTime", "Effective time", true)),
                action("revoke-registration", "Revoke registration", "credential.registration.lifecycle", true, true, true,
                        text("reasonCode", "Authority reason code", true),
                        uuid("authorityEvidenceId", "Authority evidence reference", true),
                        datetime("effectiveTime", "Effective time", true)));
        add(screens, "M2-10", "Credential document upload", "Upload evidence into private quarantine and scanning.", "credential.record.read",
                action("create-credential", "Create credential record", "credential.record.manage", true, false, true,
                        uuid("credentialTypeEntryId", "Credential type entry", true),
                        uuid("credentialTypeVersionId", "Credential type version", true),
                        text("issuer", "Issuer", true),
                        date("issuedOn", "Issued on", false),
                        date("expiresOn", "Expires on", false),
                        select("riskTier", "Risk tier", true, "low", "Low", "moderate", "Moderate", "high", "High", "critical", "Critical"),
                        uuid("supersedesId", "Credential being corrected or renewed", false)),
                action("upload-document", "Upload evidence document", "credential.document.upload", true, false, true,
                        text("retentionClass", "Retention class", true)),
                action("submit-credential", "Submit credential", "credential.record.manage", true, true, true));
        add(screens, "M2-11", "Credential verification queue", "Prioritize submitted credentials by SLA and risk.", "credential.review.queue",
                action("claim-review", "Claim next eligible review", "credential.review.claim", true, true, false));
        add(screens, "M2-12", "Credential review detail", "Review clean evidence and record an independent decision.", "credential.document.read",
                action("decide-credential", "Record decision", "credential.review.decide", true, true, true,
                        select("decisionCode", "Decision", true, "verified", "Verify", "rejected", "Reject", "more_information_required", "Request information", "returned_for_correction", "Return for correction"),
                        text("decisionReasonCode", "Reason code", true)),
                action("suspend-credential", "Suspend verified credential", "credential.lifecycle", true, true, true,
                        text("reasonCode", "Authority reason code", true),
                        uuid("authorityEvidenceId", "Authority evidence reference", true),
                        datetime("effectiveTime", "Effective time", true)),
                action("revoke-credential", "Revoke credential", "credential.lifecycle", true, true, true,
                        text("reasonCode", "Authority reason code", true),
                        uuid("authorityEvidenceId", "Authority evidence reference", true),
                        datetime("effectiveTime", "Effective time", true)),
                action("request-export", "Request credential decision export", "workforce.export.request", false, false, true,
                        select("projection", "Projection", true, "credential-decision-detail-v1", "Credential decision detail"),
                        select("format", "Format", true, "csv", "CSV", "jsonl", "JSON Lines"),
                        exportPurpose(), text("legalBasisKey", "Legal-basis registry key", true)));
        add(screens, "M2-13", "Specialties", "Maintain effective primary and secondary specialties.", "practitioner.specialty.read",
                action("add-specialty", "Add specialty", "practitioner.specialty.manage", true, false, true,
                        uuid("specialtyEntryId", "Specialty entry", true),
                        uuid("specialtyVersionId", "Specialty version", true),
                        select("designation", "Designation", true, "primary", "Primary", "secondary", "Secondary"),
                        uuid("supersedesId", "Specialty being replaced", false),
                        datetime("effectiveFrom", "Effective from", true),
                        datetime("effectiveTo", "Effective to", false)),
                action("activate-specialty", "Activate specialty", "practitioner.specialty.manage", true, true, true),
                action("end-specialty", "End specialty", "practitioner.specialty.manage", true, true, true,
                        datetime("effectiveTime", "Effective time", true)));
        add(screens, "M2-14", "Scope of practice", "Version and independently approve clinical boundaries.", "practitioner.scope.read",
                action("save-scope", "Save scope draft", "practitioner.scope.manage", true, false, true,
                        uuid("scopeDefinitionId", "Existing scope definition", false),
                        text("definitionCode", "New definition code", false),
                        text("definitionName", "New definition name", false),
                        uuid("professionEntryId", "Profession entry for new definition", false),
                        uuid("professionVersionId", "Profession version for new definition", false),
                        uuid("specialtyEntryId", "Specialty entry for new definition", false),
                        uuid("specialtyVersionId", "Specialty version for new definition", false),
                        uuid("definitionServiceId", "Service for new definition", false),
                        text("jurisdictionCountry", "Jurisdiction country", false),
                        text("jurisdictionRegion", "Jurisdiction region", false),
                        text("requirements", "New definition requirements (type,entry,version,mandatory,validity-days; separated by |)", false),
                        text("activities", "Activities (entry,version,service,facility,location,supervision-entry,supervision-version; separated by |)", true),
                        text("restrictions", "Restrictions (entry,version,display text; separated by |)", false),
                        uuid("supersedesId", "Approved scope being replaced", false),
                        datetime("effectiveFrom", "Effective from", true),
                        datetime("effectiveTo", "Effective to", false)),
                action("submit-scope", "Submit scope for approval", "practitioner.scope.submit", true, true, true),
                action("decide-scope", "Record scope decision", "practitioner.scope.approve", true, true, true,
                        select("decisionCode", "Decision", true, "approved", "Approve", "rejected", "Reject", "changes_requested", "Request changes")),
                action("suspend-scope", "Suspend approved scope", "practitioner.scope.lifecycle", true, true, true,
                        text("reasonCode", "Clinical governance reason code", true),
                        datetime("effectiveTime", "Effective time", true)),
                action("end-scope", "End scope", "practitioner.scope.lifecycle", true, true, true,
                        text("reasonCode", "Clinical governance reason code", true),
                        datetime("effectiveTime", "Effective time", true)),
                action("request-export", "Request scope decision export", "workforce.export.request", false, false, true,
                        select("projection", "Projection", true, "scope-decision-detail-v1", "Scope decision detail"),
                        select("format", "Format", true, "csv", "CSV", "jsonl", "JSON Lines"),
                        exportPurpose(), text("legalBasisKey", "Legal-basis registry key", true)));
        add(screens, "M2-15", "Organization, facility, department and location assignments", "Create validated effective assignments.", "workforce.assignment.read",
                action("save-assignment", "Save assignment", "workforce.assignment.manage", true, false, true,
                        uuid("facilityId", "Facility", true),
                        uuid("organizationUnitId", "Department or unit", false),
                        uuid("locationId", "Location", false),
                        uuid("assignmentTypeEntryId", "Assignment type entry", true),
                        uuid("assignmentTypeVersionId", "Assignment type version", true),
                        uuid("positionEntryId", "Position entry", false),
                        uuid("positionVersionId", "Position version", false),
                        bool("primaryAssignment", "Primary assignment", true),
                        datetime("effectiveFrom", "Effective from", true),
                        datetime("effectiveTo", "Effective to", false)),
                action("activate-assignment", "Activate assignment", "workforce.assignment.lifecycle", true, true, true));
        add(screens, "M2-16", "Practitioner service assignments", "Assign eligible services in facility context.", "practitioner.service_assignment.read",
                action("create-service-assignment", "Create service assignment", "practitioner.service_assignment.manage", true, false, true,
                        uuid("serviceId", "Service", true), uuid("facilityId", "Facility", true),
                        uuid("locationId", "Location", false), uuid("scopeOfPracticeId", "Approved scope", true),
                        uuid("activityEntryId", "Controlled activity", true),
                        uuid("supervisorPractitionerId", "Supervisor", false), datetime("effectiveFrom", "Effective from", true),
                        datetime("effectiveTo", "Effective to", false)),
                action("activate-service-assignment", "Activate service assignment", "practitioner.service_assignment.lifecycle", true, true, true),
                action("reactivate-service-assignment", "Re-evaluate and reactivate service assignment", "practitioner.service_assignment.lifecycle", true, true, true,
                        text("reasonCode", "Reason code", true), datetime("effectiveTime", "Effective time", true)),
                action("suspend-service-assignment", "Suspend service assignment", "practitioner.service_assignment.lifecycle", true, true, true,
                        text("reasonCode", "Reason code", true), datetime("effectiveTime", "Effective time", true)),
                action("end-service-assignment", "End service assignment", "practitioner.service_assignment.lifecycle", true, true, true,
                        text("reasonCode", "Reason code", true), datetime("effectiveTime", "Effective time", true)),
                action("cancel-service-assignment", "Cancel service assignment", "practitioner.service_assignment.lifecycle", true, true, true,
                        text("reasonCode", "Reason code", true), datetime("effectiveTime", "Effective time", true)));
        add(screens, "M2-17", "Application roles and permissions", "Grant governed software access without clinical eligibility.", "workforce.account_link.read",
                link("manage-access", "Open governed access administration", "#/M1-20"));
        add(screens, "M2-18", "Availability and working pattern", "Save weekly availability as one governed batch.", "workforce.availability.read",
                action("save-availability", "Save weekly pattern", "workforce.availability.manage", true, false, true,
                        text("timezone", "Timezone", true), datetime("effectiveFrom", "Effective from", true),
                        text("weeklyIntervals", "Weekly intervals (day,start,end,next-day; separated by |)", false),
                        text("exceptions", "Exceptions (start,end,type,reason,replacements; separated by |)", false),
                        bool("notRequired", "Availability not required", true)),
                action("activate-availability", "Activate availability", "workforce.availability.manage", true, true, true));
        add(screens, "M2-19", "Invitation and account access", "Link a user or send an expiring invitation.", "workforce.account_link.read",
                action("link-existing-account", "Link existing account", "workforce.account_link.request", true, true, true,
                        uuid("membershipId", "Active organization membership", true),
                        uuid("facilityId", "Facility scope", false),
                        uuid("organizationUnitId", "Department or unit scope", false),
                        uuid("locationId", "Location scope", false),
                        datetime("effectiveFrom", "Effective from", true),
                        datetime("effectiveTo", "Effective to", false)),
                link("manage-invitations", "Open governed invitations", "#/M1-02"));
        add(screens, "M2-20", "Review and activate", "Validate readiness and independently activate workforce.", "workforce.readiness.read",
                action("run-readiness", "Run readiness", "workforce.validation.run", true, false, false),
                action("submit-activation", "Submit for activation", "workforce.activation.submit", true, true, true,
                        text("submittedReasonCode", "Submission reason code", true),
                        text("warningAcknowledgements", "Warning gate keys (comma-separated)", false)),
                action("approve-activation", "Approve activation", "workforce.activation.approve", true, true, true,
                        select("decisionCode", "Decision", true, "approved", "Approve", "rejected", "Reject")),
                action("execute-activation", "Activate workforce member", "workforce.activation.execute", true, true, true));
        add(screens, "M2-21", "Workforce member profile", "View the canonical selected-member record.", "workforce.member.read",
                link("open-timeline", "Open evidence timeline", "#/M2-29"));
        add(screens, "M2-22", "Edit and transfer assignment", "Transfer assignments with impact review.", "workforce.assignment.read",
                action("transfer-assignment", "Transfer assignment", "workforce.assignment.lifecycle", true, true, true,
                        uuid("successorFacilityId", "Successor facility", true),
                        uuid("successorOrganizationUnitId", "Successor department or unit", false),
                        uuid("successorLocationId", "Successor location", false),
                        datetime("effectiveTime", "Effective time", true)),
                action("suspend-assignment", "Suspend assignment", "workforce.assignment.lifecycle", true, true, true,
                        text("reasonCode", "Reason code", true), datetime("effectiveTime", "Effective time", true)),
                action("reactivate-assignment", "Reactivate assignment", "workforce.assignment.lifecycle", true, true, true,
                        text("reasonCode", "Reason code", true), datetime("effectiveTime", "Effective time", true)),
                action("end-assignment", "End assignment", "workforce.assignment.lifecycle", true, true, true,
                        text("reasonCode", "Reason code", true), datetime("effectiveTime", "Effective time", true)),
                action("cancel-assignment", "Cancel scheduled assignment", "workforce.assignment.lifecycle", true, true, true,
                        text("reasonCode", "Reason code", true), datetime("effectiveTime", "Effective time", true)));
        add(screens, "M2-23", "Suspend and reactivate", "Control lifecycle transitions with reason and evidence.", "workforce.member.read",
                action("suspend-member", "Suspend member", "workforce.lifecycle.suspend", true, true, true,
                        text("categoryCode", "Suspension category", true), datetime("effectiveTime", "Effective time", true)),
                action("reactivate-member", "Reactivate member", "workforce.lifecycle.reactivate", true, true, true,
                        text("categoryCode", "Reactivation category", true), datetime("effectiveTime", "Effective time", true),
                        uuid("readinessRunId", "Fresh reactivation readiness run", true),
                        text("warningAcknowledgements", "Warning gate keys (comma-separated)", false)));
        add(screens, "M2-24", "Offboarding", "End access and assignments while preserving attribution.", "workforce.member.read",
                action("request-offboarding", "Request offboarding", "workforce.offboarding.request", true, false, true,
                        datetime("engagementEndAt", "Final working time", true), datetime("effectiveAt", "Effective time", true),
                        uuid("reasonEntryId", "Offboarding reason entry", true), uuid("reasonVersionId", "Offboarding reason version", true),
                        select("accessAction", "Access timing", true, "revoke_at_effective", "Revoke at effective time", "revoke_immediately", "Revoke immediately", "none", "No access action")),
                action("approve-offboarding", "Approve and schedule offboarding", "workforce.offboarding.approve", true, true, true));
        add(screens, "M2-25", "Expiring credentials dashboard", "Monitor expiry risk and escalation.", "workforce.expiry.read",
                action("escalate-expiry", "Escalate expiry", "workforce.expiry.escalate", true, true, true,
                        select("milestone", "Milestone", true, "90", "90 days", "60", "60 days", "30", "30 days", "7", "7 days", "0", "Expiry date", "expired", "Expired")),
                action("request-export", "Request expiry export", "workforce.export.request", false, false, true,
                        select("projection", "Projection", true, "credential-expiry-summary-v1", "Credential expiry summary"),
                        select("format", "Format", true, "csv", "CSV", "jsonl", "JSON Lines"),
                        exportPurpose(), text("legalBasisKey", "Legal-basis registry key", true)));
        add(screens, "M2-26", "Workforce configuration history", "Compare workforce configuration changes.", "workforce.history.read",
                action("request-export", "Request summary export", "workforce.export.request", false, false, true,
                        select("projection", "Projection", true, "workforce-configuration-summary-v1", "Configuration summary"),
                        select("format", "Format", true, "csv", "CSV", "jsonl", "JSON Lines"),
                        exportPurpose(), text("legalBasisKey", "Legal-basis registry key", true)),
                action("access-export", "Access ready export", "workforce.export.access", true, true, true,
                        exportPurpose()));
        add(screens, "M2-27", "Workforce audit log", "Filter and export purpose-bound audit evidence.", "workforce.audit.read",
                action("request-export", "Request audit export", "workforce.export.request", false, false, true,
                        select("projection", "Projection", true, "workforce-audit-summary-v1", "Audit summary", "workforce-audit-detail-v1", "Restricted audit detail"),
                        select("format", "Format", true, "csv", "CSV", "jsonl", "JSON Lines"),
                        exportPurpose(), text("legalBasisKey", "Legal-basis registry key", true)),
                action("approve-export", "Authorize restricted export", "workforce.export.approve", true, true, true),
                action("deny-export", "Deny restricted export", "workforce.export.approve", true, true, true),
                action("access-export", "Access ready export", "workforce.export.access", true, true, true,
                        exportPurpose()));
        add(screens, "M2-28", "Controlled registries", "Govern versioned workforce catalogues while reusing the RBAC source of truth.", "workforce.registry.read",
                action("create-registry-change", "Create registry change", "workforce.registry.manage", false, false, true,
                        text("registryKey", "Registry key", true), text("displayName", "Registry name", true),
                        select("category", "Category", true,
                                "profession", "Profession", "specialty", "Specialty",
                                "qualification_type", "Qualification type", "regulator", "Regulator",
                                "registration_type", "Registration type", "credential_type", "Credential type",
                                "credential_risk_tier", "Credential risk tier", "scope_activity", "Scope activity",
                                "scope_restriction", "Scope restriction", "scope_requirement", "Scope requirement",
                                "employment_category", "Employment category", "assignment_type", "Assignment type",
                                "position", "Position", "supervision_mode", "Supervision mode",
                                "offboarding_reason", "Offboarding reason", "notification_milestone", "Notification milestone",
                                "notification_template_metadata", "Notification template metadata",
                                "export_legal_basis", "Export legal basis"),
                        text("entryKey", "Entry key", true), text("code", "Code", true), text("entryLabel", "Entry label", true)),
                action("approve-registry-change", "Approve registry change", "workforce.registry.approve", true, true, true),
                action("activate-registry-change", "Activate registry change", "workforce.registry.activate", true, true, true));
        add(screens, "M2-29", "Lifecycle and evidence timeline", "Correlate lifecycle, decisions and evidence.", "workforce.timeline.read",
                action("request-export", "Request timeline export", "workforce.export.request", false, false, true,
                        select("projection", "Projection", true, "member-timeline-summary-v1", "Member timeline summary", "member-evidence-detail-v1", "Restricted member evidence detail"),
                        select("format", "Format", true, "csv", "CSV", "jsonl", "JSON Lines"),
                        exportPurpose(), text("legalBasisKey", "Legal-basis registry key", true)),
                action("access-export", "Access ready export", "workforce.export.access", true, true, true,
                        exportPurpose()));
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
            WorkforceScreen.Field... fields) {
        return new ActionSpec(
                key,
                label,
                operation,
                operationPermission(operation),
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

    private static String operationPermission(String operation) {
        return switch (operation) {
            case "credential.review.claim" -> "credential.review.queue";
            default -> operation;
        };
    }

    private static WorkforceScreen.Field text(String key, String label, boolean required) {
        return field(key, label, "text", required, List.of());
    }

    private static WorkforceScreen.Field date(String key, String label, boolean required) {
        return field(key, label, "date", required, List.of());
    }

    private static WorkforceScreen.Field datetime(String key, String label, boolean required) {
        return field(key, label, "datetime-local", required, List.of());
    }

    private static WorkforceScreen.Field uuid(String key, String label, boolean required) {
        return field(key, label, "uuid", required, List.of());
    }

    private static WorkforceScreen.Field bool(String key, String label, boolean required) {
        return select(key, label, required, "true", "Yes", "false", "No");
    }

    private static WorkforceScreen.Field select(
            String key, String label, boolean required, String... valueLabels) {
        var options = new java.util.ArrayList<WorkforceScreen.Option>();
        for (var index = 0; index < valueLabels.length; index += 2) {
            options.add(new WorkforceScreen.Option(valueLabels[index], valueLabels[index + 1]));
        }
        return field(key, label, "select", required, options);
    }

    private static WorkforceScreen.Field exportPurpose() {
        return select(
                "purposeKey",
                "Purpose",
                true,
                "workforce_operations", "Workforce operations",
                "credentialing_review", "Credentialing review",
                "regulatory_evidence", "Regulatory evidence",
                "security_investigation", "Security investigation",
                "employment_record_request", "Employment record request",
                "data_correction", "Data correction");
    }

    private static WorkforceScreen.Field field(
            String key,
            String label,
            String type,
            boolean required,
            List<WorkforceScreen.Option> options) {
        return new WorkforceScreen.Field(key, label, type, required, null, options);
    }
}
