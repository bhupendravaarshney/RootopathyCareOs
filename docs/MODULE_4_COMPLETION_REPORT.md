# CareOS Module 4 completion report

**Module:** M4 Appointments (`P4-01` through `P4-15`)  
**Repository status:** complete and verified  
**Verification date:** 26 September 2026  
**Production status:** not approved; target-environment activation remains separate

## Completed scope

- V79 releases the migration-owned scheduling permissions and operations; V80 adds the 13-table scheduling model; V81 adds audit/outbox registries and database lifecycle guards; V82 forward-repairs the shared idempotency operation-key constraint for registered underscore-bearing operations.
- All scheduling relations use organization-scoped composite integrity and forced row-level security. Slot holds use database time, expire after five minutes, and compete atomically so only one request can acquire a slot.
- `P4-01` through `P4-15` use live tenant-authorized projections and governed actions for dashboards, calendars, directories, request collection, patient/context/practitioner selection, slot holding, review, deferred payment evidence, confirmation, rescheduling, cancellation, no-show, waitlist and timeline views.
- Confirmation re-evaluates the patient, service context and practitioner eligibility for the exact appointment instant. Rescheduling preserves appointment identity and prior-slot evidence. Cancellation and no-show require reason and policy references.
- Audit/outbox projections are allow-listed and payload-minimized. Direct SQL cannot bypass slot, request, appointment or status-history lifecycle rules.
- Reminder delivery, automated waitlist offers, external calendar synchronization, portal/proxy self-scheduling and financial charging remain explicitly unavailable until their later policy/provider modules and target credentials exist.

## Repository evidence

| Gate | Result | Evidence |
| --- | --- | --- |
| Backend clean verification | PASS | Java 25/Maven 3.9.11 compiled 399 production and 38 test sources, validated/applied V1-V82 to disposable PostgreSQL 18, passed 234 tests in 38 suites with zero failures/errors/skips, enforced the architecture rules and packaged the bootable JAR. |
| Scheduling lifecycle | PASS | The integration path exercises all 14 mutation types, exact booking eligibility, concurrent same-slot competition, reschedule, cancellation, no-show, waitlist, audit/outbox counts and direct-SQL guard rejection. |
| API and generated client | PASS | OpenAPI 3.1 version 0.43.0 verifies exactly 114 operations; generated TypeScript has no drift and all 18 API-contract positive/negative cases pass. |
| Frontend static/unit/build | PASS | Formatting, strict typecheck, lint, the 35-source/5-feature/94-import boundary plus four negative fixtures, all 90 unit tests and the production build pass. |
| Browser/accessibility/responsive | PASS | All 135 Playwright cases pass across exact 1440, 1024, 768, 390 and 320 projects, including all 15 P4 routes and governed P4-11 confirmation context. |
| Repository contracts | PASS | The public catalogue reports 110 screens (M1 23, M2 29, M3 16, M4 15, COS 27), and the complete contract/security batch passes 110/110. |

## Deferred activation and QA

The repository implementation is complete, but this report is not target-environment or production acceptance. The consolidated QA pass may refine presentation and behavior through forward changes. Production still requires accepted provider contracts, worker identities, consent/destination policy, cancellation/no-show policy catalogues, monitoring, backup/restore, deployment evidence and operational ownership.

## Next dependency

Module 5 Encounters may now consume confirmed appointments as optional encounter provenance. Appointment confirmation does not itself create, start, sign or complete an encounter; those transitions belong to M5.
