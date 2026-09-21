# CareOS Module 2 Design System extension candidate

**Artifact kind:** `design-system`  
**Status:** `CANDIDATE_FOR_APPROVAL`  
**Candidate version:** `m2-candidate-1`  
**Approval:** Not granted

## Decision baseline

Module 2 inherits the approved CareOS Design System 1.0 tokens and component contracts from the accepted Module 1 baseline. This candidate adds workforce-specific composition rules; it does not redefine brand colors, typography, spacing, focus treatment, or the `760px` navigation breakpoint. All tokens remain CSS variables, all controls use persistent labels, and WCAG 2.2 AA remains the target.

The central content rule is explicit on every relevant screen: **clinical scope ≠ application access**. Profession, credential, assignment, service eligibility, availability, and account linkage are likewise displayed as independent facts.

### Inherited tokens and responsive contract

| Review width | Workforce layout |
| --- | --- |
| `1440` | 310px persistent navigation; four metric cards; full directories/queues; two-column forms; right-side evidence context where useful. |
| `1024` | 260px persistent navigation; two metrics per row; full or named horizontally scrollable table; stacked evidence context. |
| `768` | Persistent 260px navigation; single-column forms and approval panels; tables remain keyboard-focusable regions. |
| `390` | Modal drawer navigation; one-column content; action-heavy tables become equivalent record cards; dialogs become full-width sheets. |
| `320` | 12px gutters; stacked actions; short status text may wrap; no clipped label, touch target, evidence control, or document overflow. |

The inherited token families are `canvas`, `navigation`, `surface`, `surface-raised`, `surface-input`, `text`, `text-muted`, `focus`, `success`, `info`, `warning`, `danger`, `border`, the four-pixel spacing scale, 8/12/16/20 radii, and 44px minimum interactive targets. Status always has text and an icon or shape; color is supplementary. `prefers-reduced-motion` removes nonessential animation. Forced-colors uses system colors and preserves focus and selected state.

### Workforce component contracts

- **WorkforcePathway:** clinical/non-clinical choice with a server-derived step plan. Changing pathway after dependent data exists requires impact review; hidden practitioner steps are not client authorization.
- **PersonMatchPanel:** minimum-necessary organization-scoped candidates, field-level match reasons, `use existing`, `not the same person`, and `escalate` decisions. It never reveals a global person record or cross-organization membership.
- **MemberSummary:** stable workforce identifier, chosen display name, pathway, lifecycle, engagement, practitioner status, primary assignment, credential risk, account-link state, and server-projected actions.
- **EffectiveRecordEditor:** current and future range, timezone/date interpretation, overlap result, replacement relationship, reason, ETag conflict recovery, and explicit history preservation.
- **CredentialCard:** credential type, authority, masked number, dates, lifecycle, clean-evidence count, independent reviewer, expiry band, restrictions, and safe action projection.
- **FileDropzone:** permitted type/size guidance, keyboard file selection, local filename/size preview, upload progress, cancellation before completion, quarantine state, scan state, rejection message, and no preview until server state is `clean`. Drag and drop is optional, never the only input.
- **EvidenceViewer:** purpose prompt, clean-status proof, minimum-necessary metadata, short-lived server access, unavailable/expired states, accessible document alternative, and no provider key or signed URL in DOM history, analytics, or logs.
- **ApprovalPanel:** exact submitted revision/result digest, maker, checker eligibility, warnings, assurance freshness, reason, approve/reject/changes-requested actions, and immutable decision evidence. It never offers self-approval.
- **HierarchyAssignmentPicker:** organization/facility/department/location cascade backed by IDs and eligibility. Clearing a parent clears invalid descendants; inaccessible branches are absent rather than merely disabled.
- **WeeklyPatternEditor:** organization timezone, seven named days, multiple intervals, explicit overnight flag, copy-day action, exception list, overlap/error summary, and one atomic save operation.
- **EligibilityPanel:** server-calculated gates for registration, credential, scope, assignment, service, supervision, lifecycle, and access independence; each result has evidence, freshness, and a safe deep link.
- **ImpactReview:** additions, endings, successors, future appointments or downstream references only when authorized, blockers, warnings, effective time, reason, and atomicity statement.
- **EvidenceTimeline:** ordered lifecycle, credential, scope, assignment, access, notification, and activation evidence with family filters, correlation navigation, redaction markers, and no inferred causal claims.

### Screen layout families

| Family | Screens | Composition |
| --- | --- | --- |
| Dashboard/directory | M2-01, M2-02, M2-25 | Metrics, saved-view-free filter grid, deterministic result summary, queue/directory table and mobile cards, cursor pager. |
| Onboarding | M2-03–M2-10 | Member summary, server-derived step navigation, focused form/evidence task, validation summary, save/continue result. |
| Independent decisions | M2-11, M2-12, M2-14, M2-20 | Queue/detail split, evidence, conflict-safe claim/release where applicable, maker/checker panel, durable outcome. |
| Assignment/access | M2-15–M2-19, M2-22 | Effective editor, eligibility/impact preview, existing governed RBAC projection, weekly batch editor, account link boundary. |
| Profile/lifecycle | M2-21, M2-23, M2-24, M2-29 | Canonical summary, tabs/landmarks, impact-first governed dialog, immutable evidence timeline. |
| Governance | M2-26–M2-28 | Version/history diff, purpose-bound audit, controlled-registry versioning and approval; no generic editable key/value grid. |

### Content, privacy, and interaction

- Human names are examples only in the mockup and use conspicuous `Synthetic` labels. Production list views show the least identity needed for the task; birth date, home address, private contacts, document content, and full registration numbers are excluded unless the operation explicitly requires them.
- Profession, specialty, scope, assignment, service eligibility, application access, and user-account linkage use distinct labels and panels. The UI never says that one automatically grants another.
- Dates show the relevant organization or facility timezone. Expiry uses exact dates plus one mutually exclusive band: `61–90`, `31–60`, `8–30`, `0–7`, or `expired` days.
- Primary actions use verbs such as `Save draft`, `Submit for verification`, `Approve scope`, `Activate member`, or `Confirm offboarding`; generic `Submit` is avoided where outcome matters.
- Loading, empty, no-result, validation, denied, stale, conflict, dependency failure, success, and read-only historical states are designed for every server-backed screen. Optimistic success is forbidden for governed mutations.
- Route changes focus the H1. Error summaries focus after failed submission and link to fields. Dialogs trap focus, restore it on close, and allow Escape only when cancellation is safe. Tables and timelines are named focusable regions.

## Verification and acceptance

- Review all M2-01 through M2-29 screens at 1440, 1024, 768, 390, and 320 CSS pixels, 200% zoom, keyboard only, reduced motion, forced colors, and the supported screen-reader/browser matrix.
- Automated checks cover serious/critical Axe findings, body/document overflow, touch targets, focus order, dialog focus containment/return, labels/descriptions, error-summary links, equivalent mobile cards, and status text independent of color.
- Product and clinical-governance review must confirm that clinical scope, credential verification, and eligibility language is accurate and does not imply access or authority not present in server state.
- Security/privacy review must confirm redaction, purpose prompts, protected evidence display, and absence of cross-tenant or provider-location disclosure.

## Approval boundary

This extension and its mockup are reproducible review candidates, not approved visual or workflow authority. Approval must bind the exact package digest and identify any component exception. Until then, M2 routes remain synthetic and no production action may be enabled.
