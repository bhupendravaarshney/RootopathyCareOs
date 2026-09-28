# CareOS Module 10 implementation plan

**Module:** M10 Follow-up and Outcomes (`P10-01` through `P10-09`)  
**Current phase:** repository construction complete; module-focused verification passed  
**Predecessor:** M9 repository PASS through Flyway V101  
**Implementation direction:** user's standing approval to complete repository construction before consolidated QA  
**Production acceptance:** not granted

## Authoritative scope

The build specification defines nine screens: monitoring dashboard, rules, domains, measures, escalation, follow-up schedule, interpretation, confirm plan and outcome timeline.

The required model contains follow-up plans/events, outcome definitions/measurements, escalation rules/events and interpretations. A threshold breach must create an owned escalation task and retain acknowledgement evidence.

## Conservative implementation decisions

- One follow-up plan binds the exact active Module 9 care plan/version, verified patient, active encounter and responsible eligible practitioner. Care-plan revision or version drift blocks confirmation rather than silently rebasing monitoring.
- A follow-up plan follows `draft -> review -> active -> completed | cancelled`. Submission freezes an exact digest; confirmation is a separate eligible-clinician decision requiring explicit reason, recent authentication and MFA.
- Every outcome definition is version-bound and records one domain, measure, unit, direction, target and baseline requirement. Definitions and measurements are append-only clinical evidence.
- Escalation rules bind an exact outcome definition and record operator, threshold/range, severity, accountable owner, task priority and acknowledgement target. Unsupported or ambiguous operators fail closed.
- Recording a measurement evaluates current rules inside the same authorized database transaction. Every breach atomically creates an escalation event and an owned Module 5 clinical task with exact follow-up/rule/measurement provenance.
- Escalation acknowledgement and resolution are distinct one-way transitions with actor, time and reason. Closing a task alone cannot acknowledge or resolve the escalation, and acknowledgement cannot erase breach evidence.
- Follow-up events retain scheduled/due/completed/missed/cancelled lifecycle, owner and timing. Measurements require the exact event and definition to belong to the same plan and patient.
- Interpretations are attributed append-only evidence linked to exact measurement/event state. They cannot rewrite measurements or close an escalation implicitly.
- Audit/outbox payloads carry identifiers, states, severity and digests only; they exclude measurement narratives, interpretation text and unrestricted clinical content.

## Repository model

Module 10 adds `followup_plans`, `followup_events`, `outcome_definitions`, `outcome_measurements`, `escalation_rules`, `escalation_events` and `interpretations`. It reuses the existing `clinical_tasks` aggregate for breach ownership and acknowledgement work, adding exact follow-up/escalation provenance rather than creating a competing task store.

All tenant-owned rows use composite organization foreign keys, forced RLS, UUIDv7 identifiers and server-owned timestamps. Confirmed plan definitions/rules/schedules, all measurements, escalation creation/acknowledgement evidence and interpretations are immutable or one-way state guarded.

## Dependency-ordered slices

| Slice | Screens | Scope | Exit |
| --- | --- | --- | --- |
| M10A | All | Exact screen/action contract, lifecycle, authorization, minimization and care-plan binding | Versioned plan and migration-owned catalogues |
| M10B | P10-01-P10-04 | Follow-up plan, domains/definitions, rules, baseline and outcome measurements | Wrong-plan, stale-revision, invalid-unit/operator and cross-tenant attacks fail |
| M10C | P10-05-P10-06 | Atomic threshold evaluation, owned escalation task/evidence and follow-up scheduling | Every breach has owner/task; acknowledgement is attributable and one-way |
| M10D | P10-07-P10-09 | Append-only interpretation, exact-digest confirmation and immutable outcome timeline | Plan drift or unresolved critical escalation blocks completion |
| M10E | All | OpenAPI/client, all nine live routes, backend/frontend/browser/security gates and closeout | Repository PASS; consolidated QA/target acceptance remain separate |

## Activation boundary

This plan does not approve outcome domains, measures, units, instruments, reference ranges, clinically meaningful change, threshold operators/values, severity, follow-up cadence, escalation response time, notification delivery, interpretation wording or patient communication. The repository supplies fail-closed versioned mechanics; target clinical, privacy, legal, operational and deployment acceptance remains separate.

## Construction checkpoint

M10A-M10E completed on 28 September 2026. V102-V104, the governed backend lifecycle, OpenAPI 0.49.0/generated client, all nine live P10 routes, focused PostgreSQL tests, frontend static/unit/build gates and the five-width Playwright/Axe/overflow gate pass. See `MODULE_10_COMPLETION_REPORT.md` for the exact evidence and remaining activation boundary.
