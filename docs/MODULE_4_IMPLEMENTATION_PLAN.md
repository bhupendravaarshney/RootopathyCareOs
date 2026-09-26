# CareOS Module 4 implementation plan

**Module:** M4 Appointments (`P4-01` through `P4-15`)
**Current phase:** M4F complete; repository verification passed 26 September 2026
**Predecessor:** M3 repository PASS through Flyway V78
**Implementation direction:** user's standing approval to continue without routine review pauses
**Production acceptance:** not granted

## Scope

The authoritative build specification defines these screens: scheduling dashboard, calendar, appointment directory, new appointment, patient selection, service/facility/location, eligible clinician selection, slot selection, appointment review, payment requirement, confirmation, reschedule, cancel/no-show, waitlist and appointment timeline.

The exact entity families are schedules, slots, appointment requests, appointments, participants, status history, assignments, waitlist entries, reminders, cancellations, no-show decisions, payment requirements and external calendar links.

## Safe implementation decisions

- CareOS remains the scheduling source of truth. Messaging, payment and calendar providers can only consume or contribute bounded evidence; they cannot directly change appointment state.
- Slots use UTC instants plus an IANA timezone snapshot. Holds are short technical leases, expire using database time and are acquired atomically.
- Confirmation re-evaluates patient state, service/facility/location state and exact practitioner service eligibility for the appointment instant. A stale schedule-time result cannot authorize booking.
- One active booking can consume a slot. PostgreSQL exclusion/unique constraints and state guards protect against application bypass and concurrent double booking.
- Rescheduling keeps the same appointment identity, releases the previous slot, books a new eligible slot and appends immutable before/after status evidence.
- Cancellation and no-show transitions require explicit reason and record a versioned policy reference. No fee, refund, insurer or payment outcome is inferred before Module 11 policy exists.
- Payment is represented as `deferred_to_billing` for the repository implementation. No charge, authorization, refund or financial promise occurs in M4.
- Waitlist entries are internal requests. Automated offers, reminders and external calendar synchronization remain unavailable until authorized worker identities, templates, consent/destination rules and provider contracts exist.
- Portal/proxy self-scheduling remains unavailable until the M3 authority and proofing catalogue is active. Current actions are staff-authorized only.

## Dependency-ordered slices

| Slice | Screens | Scope | Exit |
| --- | --- | --- | --- |
| M4A | All | Screen/action contract, state model, permissions/events and explicit provider boundaries | Versioned plan and migration-owned catalogues |
| M4B | P4-01-P4-03 | Dashboard, calendar and directory projections | Tenant/RLS, bounded filters and safe paging |
| M4C | P4-04-P4-09 | Request, patient/context/practitioner selection, slots, atomic hold and review | Eligibility-at-instant plus concurrent hold attacks pass |
| M4D | P4-10-P4-11 | Non-financial payment requirement evidence and confirmation | Atomic slot consumption, patient/clinician checks and audit/outbox pass |
| M4E | P4-12-P4-15 | Reschedule, cancel/no-show, waitlist and timeline | Immutable lineage, policy reference and fail-closed workers/providers pass |
| M4F | All | Full backend/frontend/browser/security regression and status closeout | Repository PASS; later QA/target acceptance remains separate |

All six slices are complete at the repository boundary. The exact evidence and deferred activation boundary are recorded in [Module 4 completion report](MODULE_4_COMPLETION_REPORT.md).

## State model

- Schedule: `draft -> active -> retired`.
- Slot: `available -> held -> booked`; an expired `held` slot returns to effective `available`; `available|held -> blocked` is administrative and reason-bound.
- Appointment request: `collecting -> held -> confirmed`, or `collecting|held -> abandoned|expired`.
- Appointment: `confirmed -> cancelled|no_show`; reschedule retains `confirmed` and increments revision while appending exact old/new slot evidence. Encounter/completion state belongs to M5.
- Waitlist: `waiting -> offered -> accepted|expired|withdrawn`; automatic offering remains disabled until an authorized worker exists.

## Required controls

- Organization-bound authorization transaction and forced RLS on every tenant table.
- Composite tenant foreign keys, UUIDv7 identifiers, strong revisions, scoped idempotency and RFC 9457 errors.
- Database-time leases and direct-SQL guards for holds, booking, reschedule, cancellation and no-show.
- Minimum-necessary patient/practitioner labels; no raw contact, clinical, payment or provider payload in audit/outbox/timeline projections.
- Exact audit/outbox allowlists and immutable status history for every material appointment transition.
- Five-viewport keyboard, Axe and overflow verification for all 15 screens.

## Activation boundary

This plan authorizes repository implementation under the user's standing direction. It does not invent cancellation fees, refunds, notification consent, WhatsApp/SMS templates, calendar-provider trust, payment-provider behavior, patient portal/proxy authority, local SLA thresholds or production worker credentials. Those behaviors remain visibly fail closed until their later modules and target policies exist.

## Verification result

| Gate | Result |
| --- | --- |
| Java 25/Maven clean verification, V1-V82 and 234 backend tests | PASS |
| OpenAPI 0.43.0, 114 operations, generated-client drift and 18 contract tests | PASS |
| Frontend format/type/lint, 90 unit tests and production build | PASS |
| 135 five-viewport Playwright/Axe/overflow cases | PASS |
| 110-screen registry and 110 repository contract/security tests | PASS |

The next construction slice is Module 5 Encounters. Consolidated owner QA and target-environment acceptance remain deferred as directed.
