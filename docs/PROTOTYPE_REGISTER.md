# Clickable prototype register

The frontend exposes a session-gated shell with server-backed organization switching, no-polling idle/absolute deadline lock and resume revalidation, workspace search/tabs, responsive navigation and previous/next prototype traversal.

## Administration workspace

`M1-01` Login; `M1-02` Invitation; `M1-03` MFA; `M1-04` Organization selector; `M1-05` Administration dashboard; `M1-06` Setup checklist; `M1-07` Organization profile; `M1-08` Registration and identifiers; `M1-09` Addresses and contacts; `M1-10` International settings; `M1-11` Governance contacts; `M1-12` Facilities; `M1-13` Facility wizard; `M1-14` Departments and units; `M1-15` Locations; `M1-16` Operating hours; `M1-17` Service catalogue; `M1-18` Facility services; `M1-19` Identifier schemes; `M1-20` Administrator access; `M1-21` Review and activate; `M1-22` Configuration history; `M1-23` Audit log.

M1-01; governed M1-02 invitation issue/revocation; pending-challenge, authenticated self-service, and organization-scoped maker-checker variants of M1-03; and M1-04 call the checked server identity/organization client. Public `#/forgot-password`, token-bearing `#/reset-password`, and token-bearing `#/accept-invitation` routes support the backend's recovery/account-link flows without adding registered module screens. These are disabled-in-production Phase 0 reference identity states, not completion of the M1 administration module or approval of its final policy/design.

## Workforce workspace

`M2-01` Workforce dashboard; `M2-02` Workforce directory; `M2-03` Add workforce member; `M2-04` Duplicate and person search; `M2-05` Personal and contact information; `M2-06` Engagement and employment details; `M2-07` Practitioner profile; `M2-08` Qualifications; `M2-09` Professional registrations and licences; `M2-10` Credential document upload; `M2-11` Credential verification queue; `M2-12` Credential review detail; `M2-13` Specialties; `M2-14` Scope of practice; `M2-15` Organization/facility/department/location assignments; `M2-16` Practitioner service assignments; `M2-17` Application roles and permissions; `M2-18` Availability and working pattern; `M2-19` Invitation and account access; `M2-20` Review and activate; `M2-21` Workforce member profile; `M2-22` Edit and transfer assignment; `M2-23` Suspend and reactivate; `M2-24` Offboarding; `M2-25` Expiring credentials dashboard; `M2-26` Workforce configuration history; `M2-27` Workforce audit log; `M2-28` Controlled registries; `M2-29` Lifecycle and evidence timeline.

## Clinical workspace

`COS-01` through `COS-27` are routed as a continuous clinician-in-the-loop journey. The included clinical screens are new synthetic placeholders based on the build blueprint. They must be reconciled screen-by-screen with the protected original COS source assets before any production implementation or visual freeze.

## Prototype limitation

Login, session-expiry convergence, password recovery, governed invitations/account linking, MFA challenge/enrollment/recovery-code self-service, maker-checker administrative reset, recent authentication, organization selection/switching, and logout use foundation APIs. Other buttons demonstrate navigation, validation, and local interaction states only; they do not claim production persistence. The Spring Boot registry API independently verifies the 79-screen contract.
