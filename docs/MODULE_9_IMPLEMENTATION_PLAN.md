# CareOS Module 9 implementation plan

**Module:** M9 Care Planning (`P9-01` through `P9-12`)  
**Current phase:** M9A-M9F repository construction complete; module-focused verification passed  
**Predecessor:** M8 repository PASS through Flyway V98  
**Implementation direction:** user's standing approval to complete repository construction before consolidated QA  
**Production acceptance:** not granted

## Authoritative scope

The build specification defines twelve screens: care plan dashboard, create coordinated plan, problems/priorities, goals, interventions, modality coordination, owners/tasks, consent/preferences, safety/interaction review, clinician approval, patient summary, and plan versions/amendments.

The required model contains care plans and versions, goals, interventions, intervention assignments, clinical tasks, plan consents/preferences and interaction reviews. Every intervention must retain rationale, owner, priority, start, review, stop criteria and monitoring. Cross-modality interaction/safety review is explicit, patient goals/preferences remain visible and versioned, and later threshold breaches must create owned escalation tasks and acknowledgement evidence through Module 10.

## Conservative implementation decisions

- A plan belongs to one organization, verified patient and active encounter, and names one eligible responsible practitioner. Source assessment and accepted AI review references are optional provenance only; an AI draft can never become a plan automatically.
- The baseline lifecycle is `draft -> review -> approved -> active -> revised | completed | cancelled`. Submission freezes an exact version digest. Approval and activation are separate attributed actions; approval requires an eligible responsible clinician, reason, recent authentication and MFA.
- A material amendment creates a new draft successor plan/version and marks the prior active plan `revised`. It never overwrites an approved or active version. The amendment records prior/successor version digests, author, time and reason.
- Problems/priorities, goals, interventions, assignments/tasks, consent/preferences and interaction reviews are append-only version children. Draft correction appends replacement evidence; submitted content cannot be edited in place.
- Each intervention requires bounded rationale, priority, planned start, review date, stop criteria and monitoring instructions before review submission. Every intervention also requires an accountable assignment and open clinical task.
- Cross-modality review records the exact version digest, modalities considered, interaction findings, safety outcome and reviewer. `needs_changes`, `unsafe`, stale or missing reviews block approval.
- Patient-stated goals and preferences are explicitly labeled. Consent refusal or withdrawal remains visible and blocks approval/activation; no client-side control can override it.
- Plan summaries expose minimum-necessary clinical content. Audit/outbox payloads carry identifiers, states and digests only, never goal, preference, rationale, intervention, task or safety narrative.
- Physical deletion remains limited to unreferenced drafts. Approved, active, revised, completed, cancelled or clinically attributed evidence is corrected through an amendment/successor.

## Repository model

Module 9 adds `care_plans`, `care_plan_versions`, `care_plan_priorities`, `care_plan_goals`, `care_plan_interventions`, `intervention_assignments`, `plan_consents`, `interaction_reviews`, `care_plan_approvals` and `care_plan_amendments`. It extends the existing Module 5 `clinical_tasks` relation with exact care-plan/version/intervention-assignment provenance instead of creating a competing task aggregate.

All tenant-owned rows use composite organization foreign keys, forced RLS, UUIDv7 identifiers and server-owned timestamps. Submitted versions and their children, approvals and amendments are immutable. Database guards independently enforce lifecycle, exact version/digest, responsible-clinician eligibility, consent, safety-review and task/owner completeness.

## Dependency-ordered slices

| Slice | Screens | Scope | Exit |
| --- | --- | --- | --- |
| M9A | All | Exact screen/action contract, lifecycle, authorization, provenance and activation boundaries | Versioned plan and migration-owned catalogues |
| M9B | P9-01-P9-04 | Plan creation, immutable version, problems/priorities and patient/clinical goals | Wrong-patient, wrong-encounter and stale-revision attacks fail |
| M9C | P9-05-P9-07 | Complete interventions, modality coordination, accountable assignments and clinical tasks | Missing rationale/owner/dates/stop/monitoring or task evidence blocks review |
| M9D | P9-08-P9-10 | Versioned consent/preferences, exact interaction review, submit, clinician approval and activation | Refused consent, unsafe/stale review, ineligible reviewer or missing MFA fails closed |
| M9E | P9-11-P9-12 | Minimum-necessary patient summary, successor amendments and immutable lineage | Prior approved content cannot be overwritten |
| M9F | All | OpenAPI/client, all twelve live routes, backend/frontend/browser/security gates and closeout | Repository PASS; consolidated QA/target acceptance remain separate |

## Activation boundary

This plan does not approve diagnosis/problem terminology, care-plan templates, modality catalogue, intervention evidence, scope-of-practice policy, interaction rules, consent wording, patient delivery, task notification, review cadence or clinical escalation thresholds. The repository supplies bounded free-text/code references and fail-closed review rules; target clinical, legal, privacy, operational and deployment acceptance remains separate.

## Completion checklist

- [x] V99 releases the exact migration-owned Module 9 permission, operation and event catalogues.
- [x] V100 creates the ten governed care-plan relations and extends the existing clinical-task model with exact plan/version/assignment provenance.
- [x] V101 adds payload-minimized events and database lifecycle, version-digest, completeness, consent, safety, ownership/task, clinician-eligibility and amendment guards.
- [x] `P9-01` through `P9-12` use runtime-validated server projections and expose only server-projected actions.
- [x] Submission freezes an exact digest; eligible-clinician approval requires current consent, a clear exact-version interaction review, complete intervention ownership/tasks, reason, recent authentication and MFA.
- [x] Material amendment creates a reason-bound successor draft without overwriting the approved or active predecessor.
- [x] OpenAPI 3.1 version 0.48.0 verifies exactly 127 operations, and all 30 contract-verifier cases pass.
- [x] The generated client has no drift; formatting, strict typecheck, lint, the 54-source/10-feature/155-import boundary, all 121 frontend unit tests and the production build pass.
- [x] The focused backend gate compiles 441 production and 48 test sources, applies V1-V101 to PostgreSQL 18 and passes 5/5 catalogue, registry and lifecycle tests.
- [x] Every Module 9 route passes the focused Playwright/Axe/overflow gate at 1440, 1024, 768, 390 and 320 pixels.

See `MODULE_9_COMPLETION_REPORT.md` for the verified repository boundary and the activation work that deliberately remains open.
