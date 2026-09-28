# CareOS Module 12 implementation plan

**Module:** M12 Reporting (`P12-01` through `P12-10`)  
**Current phase:** M12A-M12E repository construction complete; module-focused verification passed and consolidated QA deferred  
**Predecessor:** M11 repository PASS through Flyway V107  
**Implementation direction:** user's standing approval to complete repository construction before consolidated QA  
**Production acceptance:** not granted

## Authoritative scope

The build specification defines ten screens: reporting dashboard, operational reports, clinical safety reports, outcome reports, workforce governance, access/security reports, AI governance, financial reports, scheduled exports and report audit/history.

Every projection must enforce tenant authorization and minimum-necessary fields. Sensitive export requests require an explicit purpose, recent authentication and MFA, while generated artifacts must remain private and expire. CSV serialization must neutralize spreadsheet formulas.

## Conservative implementation decisions

- Reports contain aggregate counters and monetary totals only. They never copy patient names, contact details, narratives, result values, payment references, credentials, prompts or document contents into the reporting store.
- Seven fixed report families are supported: operational, clinical safety, outcomes, workforce governance, access/security, AI governance and financial governance. A screen fixes its report family and purpose; clients cannot substitute an unrestricted table or column selection.
- A report run uses a bounded UTC period of at most 366 days. The server computes its metrics, freezes metric rows and completes the run with a deterministic digest in one governed transaction.
- Report schedules contain a fixed report family, CSV/JSON format, cadence, bounded lookback, next-run instant and exact purpose. They are definitions only until an accepted scheduler/worker identity is deployed; the repository does not pretend that an unavailable worker executed them.
- Export requests bind an exact completed report run, format, purpose, filter digest, requester and one-hour-or-less artifact expiry. They contain no public URL or bearer token. Private artifact generation and delivery fail closed until an accepted storage/worker adapter is activated.
- CSV cells are neutralized when their first effective character could invoke a spreadsheet formula, then RFC-style quoted. The same guard exists at the database boundary for future workers.
- Runs, metrics and export requests are append-only evidence. Schedule state changes require strong revisions, reasons and current authorization; cancelled schedules cannot resume.
- Audit/outbox payloads contain identifiers, artifact types, state and revision only. They exclude report values and all source-record content.

## Repository model

Module 12 adds `report_runs`, `report_run_metrics`, `report_schedules` and `report_exports`. All relations are tenant-owned, forced-RLS, UUIDv7 keyed and operation-bound. Completed run snapshots and export requests are immutable; schedule transitions are version checked.

## Dependency-ordered slices

| Slice | Screens | Scope | Exit |
| --- | --- | --- | --- |
| M12A | All | Fixed report catalogue, purpose mapping, permissions and minimum-necessary output contract | Versioned plan and migration-owned authorization catalogue |
| M12B | P12-01-P12-08 | Authorized live aggregates and immutable bounded report snapshots | Cross-tenant and row-detail disclosure paths fail closed |
| M12C | P12-09 | Governed schedules and exact-run export requests | Recent-auth/MFA, lifecycle, format, expiry and formula-safety controls pass |
| M12D | P12-10 | Attributable report/export history and minimized audit/outbox evidence | History is tenant scoped and source content is absent |
| M12E | All | OpenAPI/client, ten live routes, backend/frontend/browser gates and closeout | Repository PASS; worker/storage activation and consolidated QA remain separate |

## Activation boundary

This plan does not approve report definitions beyond the fixed aggregate catalogue, legal bases, retention periods, scheduling infrastructure, recipient lists, cross-tenant analytics, data-warehouse replication, object storage, download grants, accounting interpretation or regulatory submissions. The repository supplies fail-closed mechanics; target privacy, clinical safety, finance, security, operations and deployment acceptance remains separate.

## Completion evidence

All five slices are complete at the repository boundary through V110. The focused backend run passes 8/8 tests against fresh PostgreSQL 18; OpenAPI 0.51.0 verifies 133 operations and 36/36 contract cases; all 136 frontend unit tests and static/build gates pass; and every P12 route passes the five exact browser viewport/Axe/overflow projects. See `MODULE_12_COMPLETION_REPORT.md` for the implemented and fail-closed boundary.
