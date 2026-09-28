# CareOS Module 8 completion report

**Module:** M8 AI assistance and governance (`P8-01` through `P8-10`)  
**Repository construction status:** complete and module-focused verification passed  
**Verification date:** 28 September 2026  
**Consolidated QA:** deferred under the project's continuous execution direction  
**Production status:** not approved; provider, model, prompt, policy, clinical-use and target-environment activation remain separate

## Completed scope

- V96 releases the migration-owned Module 8 permissions, operations and events. V97 creates 17 governed AI relations with composite tenant integrity, UUIDv7 identifiers, forced RLS and append-only evidence controls. V98 adds payload-minimized audit/outbox contracts and direct lifecycle, manifest, processing, provenance, safety, review and retention guards.
- `P8-01` through `P8-10` use checked tenant-authorized projections and governed actions for session launch, purpose/consent, minimum-necessary input selection, processing, draft output, clinical suggestions, safety/uncertainty flags, provenance, clinician review and immutable history.
- Every session binds one verified patient and active encounter, a bounded clinical purpose, consent/legal-basis evidence and an immutable typed input manifest. Only references, exact source revisions/digests and justifications enter the job contract; unrestricted clinical narratives are excluded from audit, outbox and generic queue payloads.
- Processing binds an exact active model release, prompt release, evaluation/sign-off, output schema and parameter digest. The production processing port fails closed by default. A failed attempt is preserved and an explicit resubmission appends a new job contract and attempt against the same approved manifest.
- Provider results are always visibly draft. Unknown schemas, release drift, unsupported citations and mismatched manifest provenance fail closed. Output, usage, retry/failure and retention evidence is immutable and separately attributable.
- Clinician edits append an output version and retain citation lineage. Acceptance is never preselected and requires an eligible clinician, explicit reason, exact revision and recent MFA.
- Uncertainty and safety flags remain visible. Critical or emergency flags require attributed escalation evidence and block acceptance until resolution.
- AI output cannot directly mutate an encounter, assessment, diagnosis, care plan or document. Adoption remains a separately authorized action in the owning clinical module.

## Repository evidence

| Gate | Result | Evidence |
| --- | --- | --- |
| Backend compilation and AI verification | PASS | Java 25/Maven 3.9.11 compiles 433 production and 46 test sources. The focused catalogue/registry/lifecycle run validates and applies V1-V98 to disposable PostgreSQL 18 and passes 5/5 tests. |
| AI lifecycle and governance evidence | PASS | The integration path records purpose/consent and an exact manifest, exercises fail-closed processing and explicit retry, ingests a governed draft with citations and safety evidence, appends an attributed clinician edit with retained provenance, rejects unsafe early acceptance, resolves escalation, accepts with recent MFA and verifies minimized immutable history. |
| API and generated client | PASS | OpenAPI 3.1 version 0.47.0 verifies exactly 125 operations; generated TypeScript has no drift and all 28 positive/negative API-contract cases pass, including the exact Module 8 screen/action and bounded-field contract. |
| Frontend static/unit/build | PASS | Formatting, strict typecheck, lint, the 51-source/9-feature/146-import boundary plus four negative fixtures, all 116 unit tests and the production build pass. |
| Module 8 browser/accessibility/responsive | PASS | Every P8 route passes in 5/5 Playwright projects across exact 1440, 1024, 768, 390 and 320 widths, including Axe and overflow checks. |
| Public catalogue | PASS | The public registry reports 143 screens: M1 23, M2 29, M3 16, M4 15, M5 12, COS 27, P7 11 and P8 10. |

## Deferred QA and activation

The project-wide cross-module regression, supply-chain/image/deployment reruns and target-environment acceptance remain deferred until consolidated QA. This report does not approve production AI or clinical use.

Production activation still requires an accountable clinical use case; approved purpose, consent/legal-basis and minimum-necessary rules; provider, region and data-residency/training controls; model, prompt, schema and evaluation releases; safety thresholds and escalation ownership; token/cost budgets; retention/deletion evidence; Python-service authentication, isolation, deployment, monitoring and recovery; adversarial/clinical validation; and privacy, security, clinical, financial and operational sign-off. Those dependencies remain explicit fail-closed boundaries.

## Next dependency

Module 9 Care Planning may consume only authorized source-of-truth clinical records. It must preserve versioned plans, measurable goals, governed interventions/tasks, patient preferences/consent, safety/interaction review and attributed approval/amendment evidence without treating an AI draft as an approved care plan.
