package com.rootopathy.careos.prototype.application;

import com.rootopathy.careos.prototype.domain.PrototypeScreen;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

public class PrototypeRegistry {
    private static final String[] M1 = {
        "Login", "Invitation", "MFA", "Organization selector", "Administration dashboard", "Setup checklist",
        "Organization profile", "Registration and identifiers", "Addresses and contacts", "International settings",
        "Governance contacts", "Facilities", "Facility wizard", "Departments and units", "Locations",
        "Operating hours", "Service catalogue", "Facility services", "Identifier schemes", "Administrator access",
        "Review and activate", "Configuration history", "Audit log"
    };

    private static final String[] M2 = {
        "Workforce dashboard", "Workforce directory", "Add workforce member", "Duplicate and person search",
        "Personal and contact information", "Engagement and employment details", "Practitioner profile",
        "Qualifications", "Professional registrations and licences", "Credential document upload",
        "Credential verification queue", "Credential review detail", "Specialties", "Scope of practice",
        "Organization, facility, department and location assignments", "Practitioner service assignments",
        "Application roles and permissions", "Availability and working pattern", "Invitation and account access",
        "Review and activate", "Workforce member profile", "Edit and transfer assignment", "Suspend and reactivate",
        "Offboarding", "Expiring credentials dashboard", "Workforce configuration history", "Workforce audit log",
        "Controlled registries", "Lifecycle and evidence timeline"
    };

    private static final String[] COS = {
        "Consultation context", "Patient story", "Presenting concerns", "Clinical timeline", "Medication review",
        "Allergies and safety", "Investigations", "Vital signs", "Clinical examination", "Red-flag assessment",
        "Problem list", "Differential assessment", "ROOT360 overview", "PhysioCore assessment", "Mind and narrative",
        "Lifestyle and environment", "Integrative evidence review", "Clinical synthesis", "Priorities and goals",
        "Coordinated care plan", "Intervention safety", "Consent and shared decision", "Document review",
        "AI-assisted synthesis", "Clinician review and approval", "Monitoring and follow-up", "Confirm and close"
    };

    public List<PrototypeScreen> all() {
        var screens = new ArrayList<PrototypeScreen>();
        add(screens, "M1", M1, "Administration workspace");
        add(screens, "M2", M2, "Workforce workspace");
        add(screens, "COS", COS, "Clinical workspace");
        return List.copyOf(screens);
    }

    private void add(List<PrototypeScreen> target, String prefix, String[] titles, String purpose) {
        IntStream.range(0, titles.length).forEach(index -> target.add(new PrototypeScreen(
                "%s-%02d".formatted(prefix, index + 1), prefix, titles[index], purpose, "prototype")));
    }
}
