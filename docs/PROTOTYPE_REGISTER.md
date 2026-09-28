# Clickable prototype register

The frontend exposes a session-gated shell with server-backed organization switching, no-polling idle/absolute deadline lock and resume revalidation, workspace search/tabs, responsive navigation and previous/next prototype traversal. The shared identity and workspace frames use the approved font stack, keyboard-visible hash-safe skip navigation, and deterministic route-heading focus while preserving focused problem summaries. Local identity failures use a focused summary with exact invalid-field association and hash-safe field navigation.

## Administration workspace

`M1-01` Login; `M1-02` Invitation; `M1-03` MFA; `M1-04` Organization selector; `M1-05` Administration dashboard; `M1-06` Setup checklist; `M1-07` Organization profile; `M1-08` Registration and identifiers; `M1-09` Addresses and contacts; `M1-10` International settings; `M1-11` Governance contacts; `M1-12` Facilities; `M1-13` Facility wizard; `M1-14` Departments and units; `M1-15` Locations; `M1-16` Operating hours; `M1-17` Service catalogue; `M1-18` Facility services; `M1-19` Identifier schemes; `M1-20` Administrator access; `M1-21` Review and activate; `M1-22` Configuration history; `M1-23` Audit log.

M1-01 through M1-04 call the checked server identity/organization client. M1-05/M1-06 render exact-catalogue readiness, M1-07 governs the approved profile, M1-08 governs identifiers, and M1-09 governs effective address history and masked contacts. M1-10 reads immutable international-settings history and schedules one future version. M1-11 assigns the four required governance responsibilities from eligible memberships or verified contacts, projects masked escalation channels, and preserves gap-free replacement history. M1-20 calls the authorized membership-read/change client. V20-V29 bind these approved slices. Public recovery/token routes remain outside the registered module screens; M1B still lacks facility-scoped grants, separately authorized invitation inspection/resend/delivery behavior, and explicit owner/target-environment acceptance.

## Workforce workspace

`M2-01` Workforce dashboard; `M2-02` Workforce directory; `M2-03` Add workforce member; `M2-04` Duplicate and person search; `M2-05` Personal and contact information; `M2-06` Engagement and employment details; `M2-07` Practitioner profile; `M2-08` Qualifications; `M2-09` Professional registrations and licences; `M2-10` Credential document upload; `M2-11` Credential verification queue; `M2-12` Credential review detail; `M2-13` Specialties; `M2-14` Scope of practice; `M2-15` Organization/facility/department/location assignments; `M2-16` Practitioner service assignments; `M2-17` Application roles and permissions; `M2-18` Availability and working pattern; `M2-19` Invitation and account access; `M2-20` Review and activate; `M2-21` Workforce member profile; `M2-22` Edit and transfer assignment; `M2-23` Suspend and reactivate; `M2-24` Offboarding; `M2-25` Expiring credentials dashboard; `M2-26` Workforce configuration history; `M2-27` Workforce audit log; `M2-28` Controlled registries; `M2-29` Lifecycle and evidence timeline.

## Patient registry workspace

`P3-01` Patient registry dashboard; `P3-02` Patient directory; `P3-03` Start patient registration; `P3-04` Duplicate search; `P3-05` Identity and demographics; `P3-06` Contacts and addresses; `P3-07` Communication preferences; `P3-08` Patient identifiers; `P3-09` Caregivers and proxies; `P3-10` Consent and privacy; `P3-11` Clinical safety flags; `P3-12` Review and register; `P3-13` Patient summary; `P3-14` Duplicate review queue; `P3-15` Merge review; `P3-16` Identity and audit timeline.

## Scheduling workspace

`P4-01` Scheduling dashboard; `P4-02` Calendar; `P4-03` Appointment directory; `P4-04` New appointment; `P4-05` Patient selection; `P4-06` Service, facility and location; `P4-07` Eligible clinician selection; `P4-08` Slot selection; `P4-09` Appointment review; `P4-10` Payment requirement; `P4-11` Confirmation; `P4-12` Reschedule; `P4-13` Cancel or no-show; `P4-14` Waitlist; `P4-15` Appointment timeline.

## Encounter workspace

`P5-01` Encounter dashboard; `P5-02` Open encounter; `P5-03` Patient and appointment context; `P5-04` Participants; `P5-05` Presenting concerns; `P5-06` Clinical timeline; `P5-07` Problems and diagnoses; `P5-08` Orders and tasks; `P5-09` Encounter notes; `P5-10` Review and sign; `P5-11` Amendment; `P5-12` Encounter history.

P3, P4 and P5 use runtime-validated server projections and expose only server-authorized actions. Missing local policy/provider catalogues remain explicit unavailable states rather than synthetic success.

## Clinical workspace

`COS-01` through `COS-27` are routed as a continuous clinician-in-the-loop journey backed by runtime-validated server projections and governed actions. The workflow requires verified patient and responsible-clinician context, preserves 27-step progress, versions sourced responses, captures measurements and red-flag handling, and enforces review, MFA-backed signature and amendment boundaries. The protected original COS source assets are still absent, so the current implementation is not source-verified visual acceptance and must be reconciled screen-by-screen before a production visual freeze.

## Documents and results workspace

`P7-01` Document dashboard; `P7-02` Patient document list; `P7-03` Upload document; `P7-04` Classification and metadata; `P7-05` Scan status; `P7-06` Document viewer; `P7-07` Result inbox; `P7-08` Result detail; `P7-09` Acknowledge or escalate; `P7-10` Version history; `P7-11` Export or share intent.

P7 routes use runtime-validated server projections and governed mutations for bounded digest-checked upload, immutable versions, attributed classification, scan/promotion evidence, clean-document access, diagnostic provenance, result review/escalation and immutable access intents. Production providers and policy catalogues remain explicit unavailable activation dependencies.

## AI assistance and governance workspace

`P8-01` AI session launcher; `P8-02` Purpose and consent check; `P8-03` Input selection; `P8-04` Transcription and extraction; `P8-05` Draft summary; `P8-06` Clinical suggestion panel; `P8-07` Safety and uncertainty flags; `P8-08` Source and provenance viewer; `P8-09` Clinician review and approval; `P8-10` AI session history.

P8 routes use runtime-validated server projections and governed mutations for bounded purpose/consent evidence, immutable minimum-necessary input manifests, exact model/prompt/evaluation/job versions, draft-only outputs, citations, visible uncertainty/safety evidence, append-only clinician edits and explicit recent-MFA accept/reject decisions. The processing adapter is unavailable by default, and AI output cannot directly change an owning clinical record.

## Care planning workspace

`P9-01` Care plan dashboard; `P9-02` Create coordinated plan; `P9-03` Problems and priorities; `P9-04` Goals; `P9-05` Interventions; `P9-06` Modality coordination; `P9-07` Owners and tasks; `P9-08` Consent and preferences; `P9-09` Safety and interaction review; `P9-10` Clinician approval; `P9-11` Patient summary; `P9-12` Plan versions and amendments.

P9 routes use runtime-validated server projections and governed mutations for immutable plan versions, sourced priorities and goals, complete interventions, accountable owners/tasks, visible consent/preferences, exact-version safety review, separate approval/activation and successor amendments. Clinical catalogues, interaction policy, consent wording, escalation thresholds, task delivery and patient communication remain explicit activation dependencies.

## Follow-up and outcomes workspace

`P10-01` Monitoring dashboard; `P10-02` Rules; `P10-03` Domains; `P10-04` Measures; `P10-05` Escalation; `P10-06` Follow-up schedule; `P10-07` Interpretation; `P10-08` Confirm plan; `P10-09` Outcome timeline.

P10 routes use runtime-validated server projections and governed mutations for exact-care-plan monitoring, version-bound outcome definitions and rules, append-only measurements, follow-up scheduling, atomic threshold evaluation, owned clinical-task escalation, distinct acknowledgement/resolution, attributed interpretation and exact-digest recent-MFA confirmation. Outcome catalogues, instruments, ranges, thresholds, cadence, notification delivery and patient communication remain explicit activation dependencies.

## Billing and payments workspace

`P11-01` Billing dashboard; `P11-02` Price books; `P11-03` Packages; `P11-04` Estimate; `P11-05` Invoice; `P11-06` Payment; `P11-07` Payment link; `P11-08` Refund or adjustment; `P11-09` Claims; `P11-10` Reconciliation; `P11-11` Financial audit or export.

P11 routes use runtime-validated server projections and governed mutations for versioned pricing and packages, exact patient estimates, immutable invoice lines, card-data-free payment intents, append-only settlement/refund/adjustment evidence, claims and remittances, explicit reconciliation and purpose-bound export requests. Financial status never changes clinical completion. Provider credentials/callback verification, tax and accounting policy, payer formats, export workers and artifact delivery remain fail-closed activation dependencies.

## Reporting workspace

`P12-01` Reporting dashboard; `P12-02` Operational reports; `P12-03` Clinical safety reports; `P12-04` Outcome reports; `P12-05` Workforce governance; `P12-06` Access and security reports; `P12-07` AI governance; `P12-08` Financial reports; `P12-09` Scheduled exports; `P12-10` Report audit and history.

P12 routes use runtime-validated server projections and governed mutations for seven fixed aggregate report families, bounded immutable snapshots, versioned schedule definitions, exact-run expiring export requests and attributable audit/history. Row-level source content is excluded and CSV formula prefixes are neutralized. Approved report definitions, legal bases, suppression/retention policy, scheduler/worker identity, private artifact storage/access and delivery remain fail-closed activation dependencies.

## Prototype limitation

Login, session-expiry convergence, password recovery, governed invitations/account linking, MFA challenge/mandatory enrollment/recovery-code self-service, maker-checker administrative reset, recent authentication, organization selection/switching, logout, and all M1/M2/P3/P4/P5/COS/P7/P8/P9/P10/P11/P12 live routes and their checked actions use foundation APIs. COS clinical data remains subject to deployment authorization, approved instruments/policy catalogues and source-package reconciliation; document/result activation remains subject to accepted storage/scanner/access/retention, laboratory/imaging and critical-result policies; AI activation remains subject to approved provider/model/prompt/evaluation, consent, safety, retention and clinical-use controls; care-plan/follow-up/billing/reporting activation remains subject to approved terminology, policy, provider, payment, payer, tax, accounting, report-definition, scheduler, storage, export-worker and delivery inputs. Unavailable dependencies fail closed instead of producing synthetic success. The Spring Boot registry API independently verifies the 185-screen contract.

The approved Module 1 inputs, per-screen implementation gap, architecture, and delivery slices are recorded in `MODULE_1_IMPLEMENTATION_PLAN.md`. Generic M1 templates are not implementations of the approved mockups and must be replaced screen-by-screen through the defined slices.
