# CareOS Module 5 implementation plan

**Module:** M5 Encounters and episodes (`P5-01` through `P5-12`)  
**Current phase:** M5A-M5F repository construction complete; consolidated QA deferred  
**Predecessor:** M4 repository PASS through Flyway V82  
**Implementation direction:** user's standing approval to complete repository construction before consolidated QA  
**Production acceptance:** not granted

## Authoritative scope

The build specification defines: encounter dashboard, open encounter, patient/appointment context, participants, presenting concerns, clinical timeline, problems and diagnoses, orders and tasks, encounter notes, review/sign, amendment and encounter history.

The thirteen named core entities are `episodes_of_care`, `encounters`, `encounter_participants`, `encounter_status_history`, `presenting_concerns`, `clinical_problems`, `diagnoses`, `encounter_notes`, `note_versions`, `orders`, `clinical_tasks`, `encounter_signatures` and `amendments`. A dedicated `red_flag_escalations` relation is added to make the required non-silent safety workflow enforceable and reviewable.

## Conservative implementation decisions

- A confirmed M4 appointment may supply encounter provenance, patient, care context and responsible-practitioner assignment, but confirmation never starts or completes an encounter. Unscheduled encounters require the same explicit patient/context/practitioner eligibility evidence.
- An encounter belongs to one episode of care. If no open episode is supplied, opening an encounter creates one atomically. One appointment can source at most one non-error encounter.
- Lifecycle is `planned -> arrived -> in_progress -> on_hold -> in_progress -> completed`; `planned|arrived|in_progress|on_hold -> cancelled`; and any non-completed state may move to `entered_in_error` with a reason. No transition is inferred from wall-clock time.
- Participant identity, role, assignment, practitioner eligibility and registration display context are snapshotted when the participant is added. Historical snapshots are never rewritten when workforce data changes.
- Draft notes are versioned, not overwritten. A signed note version and signature are append-only. Correction creates a separately attributed amendment linked to the exact signed version; it never mutates the prior clinical text.
- The authenticated signer must resolve to the same active practitioner participant and have current point-in-time eligibility for the encounter service/facility/location. Administrative role or application access alone is not clinical signing authority.
- Recording a red flag atomically creates a critical clinical task and escalation record. Encounter completion is blocked while any red-flag escalation is unacknowledged or unresolved. Acknowledgement and resolution require attributed reasons; delete or silent dismissal is unavailable.
- Clinical content is persisted only in the clinical relations. Audit and outbox payloads contain identifiers, state, digests and policy references, never raw concern, diagnosis, order, task, note or amendment text.
- Terminology coding is accepted only as explicit code-system/code/display input. The repository does not invent a clinical terminology catalogue or claim semantic validation that is not configured.

## Dependency-ordered slices

| Slice | Screens | Scope | Exit |
| --- | --- | --- | --- |
| M5A | All | Screen/action contract, lifecycle, authorization/events, signing and red-flag rules | Versioned plan plus migration-owned catalogues |
| M5B | P5-01-P5-04 | Episode/encounter foundation, appointment context and immutable participant snapshots | Tenant/RLS, exact provenance and lifecycle attacks pass |
| M5C | P5-05-P5-08 | Concerns, timeline, problems/diagnoses, orders, tasks and red-flag escalation | Clinical payload minimization and non-silent escalation pass |
| M5D | P5-09-P5-11 | Note versions, signer eligibility, signatures and amendments | Append-only prior versions and signer-identity attacks pass |
| M5E | P5-12 | Minimum-necessary correlated encounter history | Allow-listed projection without raw audit/outbox payloads |
| M5F | All | Full backend/frontend/browser/security regression and closeout | Repository PASS; consolidated QA/target acceptance remain separate |

## Implementation result

M5A-M5E are implemented through Flyway V89, the 116-operation OpenAPI 0.44.0 boundary, the generated client and all 12 live P5 routes. The complete PostgreSQL encounter workflow and direct-write safety attacks pass, all 96 frontend unit tests pass, and every P5 route plus governed note-version interaction passes Axe/overflow checks at all five required viewports. The cross-module full-regression, image/deployment and production-activation portions of M5F remain intentionally assigned to the later consolidated QA phase; see `MODULE_5_COMPLETION_REPORT.md`.

## Required controls

- Organization-bound authorization transaction, forced RLS and composite tenant foreign keys for every tenant relation.
- UUIDv7 identifiers, strong revisions, scoped idempotency, bounded inputs and RFC 9457 failures.
- Direct database lifecycle guards for encounters, tasks, signed notes, signatures, amendments and red-flag escalation.
- Exact immutable status history, payload-minimized audit/outbox registries and correlation evidence for every material transition.
- Patient and practitioner labels limited to the care context; no raw credentials, registration numbers, identifiers or contact data in list/history projections.
- Five-viewport keyboard, Axe and overflow verification for all 12 screens.

## Activation boundary

This plan does not invent a terminology service, diagnosis authority, order fulfilment provider, laboratory/imaging integration, clinical red-flag catalogue, escalation SLA, cosignature rule, specialty-specific note template, legal attestation wording or production clinical-owner policy. The repository implements exact structural safety and explicit fail-closed extension points; those local clinical decisions remain target activation inputs and consolidated-QA topics.
