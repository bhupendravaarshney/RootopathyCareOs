# CareOS Module 12 completion report

**Module:** M12 Reporting (`P12-01` through `P12-10`)  
**Repository construction status:** complete and module-focused verification passed  
**Verification date:** 28 September 2026  
**Consolidated QA:** deferred under the project's continuous execution direction  
**Production status:** not approved; report definitions, legal bases, retention, scheduler/worker identity, private artifact storage/delivery and target-environment acceptance remain separate

## Completed scope

- V108 releases the migration-owned Module 12 roles, permissions and operations. V109 creates four forced-RLS reporting relations with operation-bound writes and append-only run, metric and export evidence. V110 adds payload-minimized audit/outbox contracts, exact metric catalogues, deterministic run digests and database lifecycle guards.
- `P12-01` through `P12-10` use checked tenant-authorized projections and governed actions for the dashboard, operational, clinical-safety, outcome, workforce, access/security, AI-governance and financial reports, scheduled exports and report audit/history.
- Seven fixed report families produce aggregate counters or monetary totals only. Runs use a bounded UTC period of at most 366 days and freeze their exact metric set and digest atomically; row-level patient, workforce, clinical, payment, prompt, credential and document content is excluded.
- Schedule definitions bind a fixed family, purpose, format, cadence and bounded lookback. Pause, resume and cancellation use strong revisions and explicit reasons. No scheduler execution is claimed while an accepted worker identity is unavailable.
- Export requests bind an exact completed run, purpose, requester, format, filter digest and an expiry of no more than one hour. They contain no public URL or bearer token; artifact generation and private delivery remain unavailable until accepted worker/storage adapters exist.
- Spreadsheet-formula neutralization is enforced in both Java and PostgreSQL for future CSV workers. Sensitive scheduling/export actions require current authorization, recent authentication and MFA, and minimized audit/outbox evidence contains no report values or source content.

## Repository evidence

| Gate | Result | Evidence |
| --- | --- | --- |
| Backend compilation and reporting verification | PASS | Java 25/Maven 3.9.11 compiles 466 production and 55 test sources. The focused catalogue/CSV/registry/lifecycle run validates and applies V1-V110 to disposable PostgreSQL 18 and passes 8/8 tests. |
| Reporting lifecycle and governance evidence | PASS | The integration path freezes exact aggregate metrics, verifies digest/count integrity and the 366-day bound, exercises schedule create/pause/resume/cancel, creates an exact-run expiring export request, verifies database CSV neutralization and rejects immutable-evidence mutation. |
| API and generated client | PASS | OpenAPI 3.1 version 0.51.0 verifies exactly 133 operations; generated TypeScript has no drift and all 36 positive/negative API-contract cases pass, including exact Module 12 screen/action/query bounds. |
| Frontend static/unit/build | PASS | Formatting, strict typecheck, lint, the 63-source/13-feature/179-import boundary plus four negative fixtures, all 136 unit tests and the production build pass. |
| Module 12 browser/accessibility/responsive | PASS | Every P12 route passes in 5/5 Playwright projects across exact 1440, 1024, 768, 390 and 320 widths, including Axe and overflow checks. |
| Public catalogue | PASS | The public registry reports 185 screens: M1 23, M2 29, M3 16, M4 15, M5 12, COS 27, P7 11, P8 10, P9 12, P10 9, P11 11 and P12 10. |

## Deferred QA and activation

The project-wide cross-module regression, supply-chain/image/deployment reruns and target-environment acceptance remain deferred until consolidated QA. This report is repository-construction evidence, not authority to schedule, generate, deliver or use reports for clinical, financial, employment, security or regulatory decisions in production.

Production activation still requires approved metric definitions and owners, legal bases and purposes, suppression/minimum-cohort rules where applicable, time zones and calendars, retention, recipients, output schemas, private artifact storage and access, scheduler/worker service identity, replay/recovery, monitoring and accountable clinical-safety, workforce, finance, privacy, security, legal, operations and deployment sign-off. Cross-tenant analytics and arbitrary query/report builders remain out of scope and fail closed.

## Next dependency

Module 13 Integrations and FHIR must add authenticated, versioned, replay-safe adapter boundaries without treating a successful transport or syntactically valid FHIR resource as authority to change CareOS source records.
