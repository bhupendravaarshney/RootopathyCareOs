# Clickable prototype register

The frontend exposes a session-gated shell with server-backed organization switching, no-polling idle/absolute deadline lock and resume revalidation, workspace search/tabs, responsive navigation and previous/next prototype traversal. The shared identity and workspace frames use the approved font stack, keyboard-visible hash-safe skip navigation, and deterministic route-heading focus while preserving focused problem summaries. Local identity failures use a focused summary with exact invalid-field association and hash-safe field navigation.

## Administration workspace

`M1-01` Login; `M1-02` Invitation; `M1-03` MFA; `M1-04` Organization selector; `M1-05` Administration dashboard; `M1-06` Setup checklist; `M1-07` Organization profile; `M1-08` Registration and identifiers; `M1-09` Addresses and contacts; `M1-10` International settings; `M1-11` Governance contacts; `M1-12` Facilities; `M1-13` Facility wizard; `M1-14` Departments and units; `M1-15` Locations; `M1-16` Operating hours; `M1-17` Service catalogue; `M1-18` Facility services; `M1-19` Identifier schemes; `M1-20` Administrator access; `M1-21` Review and activate; `M1-22` Configuration history; `M1-23` Audit log.

M1-01 through M1-04 call the checked server identity/organization client. M1-05/M1-06 render exact-catalogue readiness, M1-07 governs the approved profile, M1-08 governs identifiers, and M1-09 governs effective address history and masked contacts. M1-10 reads immutable international-settings history and schedules one future version. M1-11 assigns the four required governance responsibilities from eligible memberships or verified contacts, projects masked escalation channels, and preserves gap-free replacement history. M1-20 calls the authorized membership-read/change client. V20-V29 bind these approved slices. Public recovery/token routes remain outside the registered module screens; M1B still lacks facility-scoped grants, separately authorized invitation inspection/resend/delivery behavior, and explicit owner/target-environment acceptance.

## Workforce workspace

`M2-01` Workforce dashboard; `M2-02` Workforce directory; `M2-03` Add workforce member; `M2-04` Duplicate and person search; `M2-05` Personal and contact information; `M2-06` Engagement and employment details; `M2-07` Practitioner profile; `M2-08` Qualifications; `M2-09` Professional registrations and licences; `M2-10` Credential document upload; `M2-11` Credential verification queue; `M2-12` Credential review detail; `M2-13` Specialties; `M2-14` Scope of practice; `M2-15` Organization/facility/department/location assignments; `M2-16` Practitioner service assignments; `M2-17` Application roles and permissions; `M2-18` Availability and working pattern; `M2-19` Invitation and account access; `M2-20` Review and activate; `M2-21` Workforce member profile; `M2-22` Edit and transfer assignment; `M2-23` Suspend and reactivate; `M2-24` Offboarding; `M2-25` Expiring credentials dashboard; `M2-26` Workforce configuration history; `M2-27` Workforce audit log; `M2-28` Controlled registries; `M2-29` Lifecycle and evidence timeline.

## Clinical workspace

`COS-01` through `COS-27` are routed as a continuous clinician-in-the-loop journey. The included clinical screens are new synthetic placeholders based on the build blueprint. They must be reconciled screen-by-screen with the protected original COS source assets before any production implementation or visual freeze.

## Prototype limitation

Login, session-expiry convergence, password recovery, governed invitations/account linking, MFA challenge/mandatory enrollment/recovery-code self-service, maker-checker administrative reset, recent authentication, organization selection/switching, logout, M1-05/M1-06 live readiness, exact approved M1-07 profile read/update, governed M1-08 identifiers, governed M1-09 addresses/masked contacts, and M1-20 membership administration use foundation APIs. The readiness response is not a persisted configuration validation or activation result. Other buttons demonstrate navigation, validation, and local interaction states only; they do not claim production persistence. The Spring Boot registry API independently verifies the 79-screen contract.

The approved Module 1 inputs, per-screen implementation gap, architecture, and delivery slices are recorded in `MODULE_1_IMPLEMENTATION_PLAN.md`. Generic M1 templates are not implementations of the approved mockups and must be replaced screen-by-screen through the defined slices.
