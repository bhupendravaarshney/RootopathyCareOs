# CareOS Module 3 Design System extension candidate

**Artifact kind:** `design-system`  
**Status:** `CANDIDATE_FOR_APPROVAL`  
**Candidate version:** `m3-candidate-1`  
**Approval:** Not granted

## Decision baseline

Module 3 inherits CareOS Design System 1.0, the accepted M1 shell/session behavior and the established M2 review patterns. This candidate adds patient-registry compositions without changing brand tokens, the `760px` navigation breakpoint, persistent labels, 44px targets, keyboard behavior or the WCAG 2.2 AA target.

Every patient surface communicates five boundaries consistently: **patient ≠ portal account**, **relationship ≠ proxy authority**, **preference ≠ consent**, **candidate match ≠ confirmed identity**, and **flag ≠ complete clinical record**. Status text never relies on color alone. Screens use conspicuously synthetic identities: **no real patient data** is permitted.

### Responsive contract

| Review width | Required composition                                                                                                     |
| ------------ | ------------------------------------------------------------------------------------------------------------------------ |
| `1440`       | 310px persistent navigation, four metrics per row, two-column forms/comparisons and full governed tables.                |
| `1024`       | 260px navigation, two metrics per row, stacked evidence context and named horizontally scrollable tables.                |
| `768`        | Persistent navigation, single-column forms, compact comparison panels and keyboard-focusable data regions.               |
| `390`        | Modal navigation drawer, record cards replacing action-heavy table rows, stacked actions and full-width dialog sheet.    |
| `320`        | 12px gutters, wrapping labels/status, single-column comparisons and no clipped field, token, dialog or timeline content. |

At 200% zoom content reflows without losing the source/value/provenance relationship. `prefers-reduced-motion` removes shimmer and transitions. Forced-colors preserves focus, selected state, status boundaries and withheld/redacted markers.

### Patient-registry component contracts

- **PatientIdentityHeader:** synthetic display label, organization-local patient number, lifecycle, temporary/deceased/merged state, assurance summary and server-projected actions. It never displays a portal-user role or treats an identifier as authorization.
- **IdentityConfidence:** per-attribute provenance and `unverified`, `self_or_source_attested`, `evidence_checked` or `authoritative_source_verified` state. It does not claim a NIST IAL for ordinary patient data.
- **PartialDateInput:** value, precision and certainty are independent. Year/year-month/full-date, estimated and unknown states never invent missing components.
- **SensitiveField:** persistent field label, classification, purpose explanation, masked value, withheld/not-present distinction where permitted, reveal challenge and immutable access-evidence notice.
- **DuplicateCandidateCard:** organization-scoped candidate, explainable match factors, confidence band, data-quality conflicts and allowed dispositions. It never reveals cross-organization existence and never offers automatic merge.
- **MergeComparison:** survivor/duplicate columns, field provenance, explicit per-field disposition, affected-reference digest, stale-revision warning, maker/checker status and constrained execution confirmation.
- **AuthorityCard:** related-person fact separate from authority grant and portal link; shows grantor, grantee, source, purpose/action/data scope, evidence, effective dates and revocation.
- **ConsentDirective:** human-readable source reference, computable derivative label, policy/version, purpose/action/data/actor/time, decision and prospective withdrawal consequence.
- **PrivacyProjection:** accepted restriction, affected fields/resources/purposes/channels, source-fact preservation, redaction behavior and no M3 break-glass statement.
- **SafetyFlag:** concise coded statement, category/severity, provisional/verified state, author/verifier eligibility, effective/review/expiry time, acknowledgement and link to source detail. It is never a free-text patient note or messaging mechanism.
- **RegistrationStepper:** authoritative sequence for search, identity, contact, preferences, identifier, proxy/consent/privacy/safety, review and atomic registration. Urgent temporary identity is a distinct governed path.
- **PolicyUnavailable:** names the missing local catalogue, affected capability and accountable owner. It never turns missing policy into an enabled control.
- **EvidenceTimeline:** correlated allow-listed identity evidence with source family, time, actor class, reason/provenance marker, redaction and purpose-bound detail link; no raw audit payload.

### Screen composition

| Family              | Screens      | Primary composition                                                                                                          |
| ------------------- | ------------ | ---------------------------------------------------------------------------------------------------------------------------- |
| Registry overview   | P3-01, P3-02 | Minimum-necessary metrics, filters, cursor directory, masked values, permission-safe cards and policy freshness.             |
| Registration        | P3-03–P3-12  | Stepper, duplicate-first search, provenance-aware forms, separate authority/consent/preferences, warnings and atomic review. |
| Patient record      | P3-13, P3-16 | Canonical summary, withheld markers, active authority/consent/privacy/safety summaries and evidence timeline.                |
| Identity governance | P3-14, P3-15 | Leased duplicate queue, comparison, independent decision, impact digest, exact execution confirmation and no casual unmerge. |

### Interaction and content rules

- Every screen supports loading, empty, no-result, denied, validation, stale, conflict, dependency-failure and success states. Governed mutation success appears only from a structurally valid server response; optimistic success is prohibited.
- Route changes focus the H1. Validation focuses an error summary linking to fields. Dialogs trap focus, close on Escape only when cancellation is safe and return focus to the trigger.
- Tables have a named focusable scroll region and equivalent mobile `record-cards`. Cursor expiry restarts from a visible snapshot message; filter changes reset paging.
- Masking is stable enough to recognize a value without enabling enumeration. Copy controls are absent for restricted raw values unless the exact purpose policy permits them.
- Labels use neutral language: `potential duplicate`, `authority not established`, `withheld by policy`, `entered in error`, and `needs verification`. The UI does not label a person fraudulent, dangerous or non-compliant.
- Critical clinical workflow acknowledgement is distinct from reading a flag. Consumer notification delivery never appears as successful clinical escalation.
- Synthetic examples use labels such as `Synthetic Patient A`, opaque IDs and `.invalid` destinations. No realistic free-text clinical narrative appears.

## Verification and acceptance

- Review P3-01 through P3-16 at 1440, 1024, 768, 390 and 320 CSS pixels, 200% zoom, keyboard only, reduced motion, forced colors and the supported screen-reader/browser matrix.
- Automated checks cover serious/critical Axe findings, body/document overflow, navigation drawer, focus movement/return, labels, descriptions, error linkage, mobile card equivalence, dialog semantics and non-color status.
- Health-information and privacy reviewers confirm masking, authority/consent/restriction language, withheld/not-present behavior and absence of patient enumeration.
- Clinical-safety reviewers confirm provisional/verified flag distinction, acknowledgement, concise content and source-detail separation.
- Security reviewers confirm no browser persistence, remote resource, real mutation, raw secret/identifier, signed URL or provider detail.

## Approval boundary

This extension and the interactive mockup are candidate review material only. They do not approve a local terminology, matching threshold, authority, consent, privacy, safety, retention, export or interoperability catalogue. Production implementation requires acceptance of the exact candidate package digest and separate approval evidence.
