# CareOS Module 6 implementation plan

**Module:** M6 Clinical assessment and COS preservation (`COS-01` through `COS-27`)  
**Current phase:** M6A repository construction in progress; consolidated QA deferred  
**Predecessor:** M5 repository PASS through Flyway V89  
**Implementation direction:** user's standing approval to complete repository construction before consolidated QA  
**Production acceptance:** not granted

## Authoritative scope

The build specification requires the 27 protected COS screens to retain their exact source-package names, copy, sequence, components and navigation. It also requires a verified patient banner, responsible-clinician context, ordered step navigation and completion state, visible conflict-aware autosave, completeness/source/uncertainty review, and immutable clinician sign-off.

The twelve named core entities are `assessment_sessions`, `assessment_sections`, `assessment_responses`, `response_versions`, `clinical_narratives`, `assessment_instruments`, `instrument_versions`, `measurements`, `red_flags`, `assessment_reviews`, `assessment_signatures` and `assessment_amendments`.

## Protected-source boundary

The original protected COS source package is not present in the repository. The current 27 route names and order are therefore preserved without visual redesign, but they cannot be represented as source-verified visual acceptance. The runtime exposes this status explicitly. It does not invent missing clinical copy, instruments, scoring, local red-flag policy, attestation wording or ROOTOPATHY interpretations.

Repository construction may proceed safely because the structural, tenancy, lifecycle, provenance, versioning, review and signing controls are independently enforceable. Production activation remains fail closed until the protected source package and local clinical policy are imported, reviewed and approved.

## Conservative implementation decisions

- An assessment belongs to one active M5 encounter and its verified patient. The responsible clinician must be an active eligible practitioner participant in that encounter; application access alone is not clinical authority.
- Starting an assessment creates exactly 27 ordered section records. Section responses are draft-safe and versioned; autosave appends a new response version and never overwrites prior clinical content.
- Each response or narrative records source, method, optional unit, interpretation status, uncertainty and reviewer context. Audit and outbox payloads contain identifiers, state and digests, never raw clinical text.
- Instruments and instrument versions are migration-owned/read-only extension points. No questionnaire, scale, score, threshold or clinical interpretation is fabricated in the absence of an approved catalogue.
- Every measurement records purpose, baseline context, source, method, unit/scale, cadence, owner and action threshold. There is no composite cure score.
- A red flag remains visibly raised until an attributed acknowledgement and resolution are recorded. Review, signing and completion are blocked while a red flag is unresolved.
- Review binds completeness, provenance and uncertainty attestations to the current assessment revision. Signing requires recent authentication, MFA, a reason, the authenticated eligible clinician and an exact immutable review/signature version.
- Amendment appends an attributed correction linked to the signed version. It never changes a prior response, review or signature.
- `COS-24` preserves its place in the protected sequence but exposes no AI synthesis mutation before the separately governed M8 AI boundary exists.

## Lifecycle

`in_progress -> in_review -> signed -> amended -> completed` is the normal path. `in_review -> in_progress` returns a draft for correction. A non-final session may become `cancelled` or `entered_in_error` only through a governed lifecycle action with a reason. No transition is inferred from elapsed time, navigation or autosave.

## Dependency-ordered slices

| Slice | Screens | Scope | Exit |
| --- | --- | --- | --- |
| M6A | All | Contract, source-package boundary, authorization, lifecycle and event policy | Versioned plan and migration-owned operation catalogue |
| M6B | COS-01-COS-07 | Session/context foundation and append-only sourced response versions | Tenant, encounter and author eligibility attacks pass |
| M6C | COS-08-COS-18 | Measurements, red flags, narratives and explicit ROOTOPATHY interpretation status | Provenance and non-silent safety controls pass |
| M6D | COS-19-COS-27 | Goals/plan evidence, review, signature, monitoring, amendment and closeout | Immutable review/sign/amend path passes |
| M6E | All | Live API/client/screens, visible autosave state and ordered COS navigation | All 27 routes use governed server projections |
| M6F | All | Full backend/frontend/browser/security regression and closeout | Repository PASS; consolidated QA/target acceptance remain separate |

## Required controls

- Organization-bound authorization transaction, forced RLS and composite tenant foreign keys for every tenant relation.
- UUIDv7 identifiers, strong revisions, scoped idempotency, bounded inputs and RFC 9457 failures.
- Direct database lifecycle and append-only guards for sessions, response versions, narratives, measurements, reviews, signatures and amendments.
- Exact patient/encounter/practitioner correlation, immutable signer identity, source/method/uncertainty evidence and payload-minimized audit/outbox events.
- Conflict-aware ETags for autosave and every governed mutation; no silent last-write-wins behavior.
- Five-viewport keyboard, Axe and overflow verification for all 27 screens during consolidated QA.

## Activation boundary

This plan does not constitute approval of the protected COS source assets, clinical instruments, terminology services, red-flag thresholds/escalation SLAs, measurement cadence, reviewer/signature policy, legal attestation text, ROOTOPATHY clinical claims or AI use. Those inputs remain explicit target-activation dependencies. Until supplied, the repository preserves the workflow shape, provenance and safety controls while unavailable clinical behavior remains disabled.
