# CareOS Module 7 completion report

**Module:** M7 Documents and results (`P7-01` through `P7-11`)  
**Repository construction status:** complete and module-focused verification passed  
**Verification date:** 28 September 2026  
**Consolidated QA:** deferred under the project's continuous execution direction  
**Production status:** not approved; provider, clinical-policy, retention and target-environment activation remain separate

## Completed scope

- V93 releases the migration-owned Module 7 permissions and operations. V94 creates the twelve required tenant-owned relations with composite tenant integrity, UUIDv7 identifiers, forced RLS and append-only evidence controls. V95 adds payload-minimized audit/outbox contracts and direct lifecycle, provenance and access-intent guards.
- `P7-01` through `P7-11` use checked tenant-authorized projections and governed actions for the dashboard, patient documents, upload, classification, scan state, viewer, result inbox/detail/review, version history and export/share intent.
- New files enter private quarantine through a bounded multipart route that verifies filename, media type, byte count and lowercase SHA-256. Replacement appends a new quarantined version and advances the current pointer without overwriting prior bytes or evidence.
- Scan completion binds the existing scanner-attestation and clean-promotion evidence boundaries. Clinical reports and viewer access require the exact clean promoted document version; unsafe or dirty versions fail closed.
- Diagnostic reports retain source identifiers, issue time, author and document-version provenance. Laboratory results retain value, units, reference range, method, observation time and abnormal flag; imaging results retain modality, body site, findings, impression and interpretation state.
- Critical flags retain policy version, deadline and owner. Acknowledgement, escalation and resolution are attributed separately; resolution before acknowledgement is rejected.
- Viewer access creates URL-free durable grant evidence and returns only a short-lived same-origin relative access path. The browser keeps the resulting link in memory and never persists a provider bearer URL.
- Export/share records an immutable purpose-bound intent only. It does not claim consent, recipient authority, export creation, delivery or external sharing.

## Repository evidence

| Gate | Result | Evidence |
| --- | --- | --- |
| Backend compilation and document verification | PASS | Java 25/Maven 3.9.11 compiles 423 production and 44 test sources. The focused catalogue/registry/lifecycle run validates and applies V1-V95 to disposable PostgreSQL 18 and passes 5/5 tests. |
| Document/result lifecycle and evidence | PASS | The integration path uploads into quarantine, appends classification, scans/promotes, records a fully sourced critical laboratory result, rejects unsafe early resolution, acknowledges, escalates and resolves, records export and signed-access intents, appends a replacement version, verifies minimized audit/outbox evidence and preserves prior immutable evidence. |
| API and generated client | PASS | OpenAPI 3.1 version 0.46.0 verifies exactly 123 operations; generated TypeScript has no drift and all 26 positive/negative API-contract cases pass, including bounded result fields and bearer-URL rejection. |
| Frontend static/unit/build | PASS | Formatting, strict typecheck, lint, the 47-source/8-feature/133-import boundary plus four negative fixtures, all 111 unit tests and the production build pass. |
| Module 7 browser/accessibility/responsive | PASS | Every P7 route passes in 5/5 Playwright projects across exact 1440, 1024, 768, 390 and 320 widths, including Axe and document-overflow checks. |
| Public catalogue | PASS | The public registry reports 133 screens: M1 23, M2 29, M3 16, M4 15, M5 12, COS 27 and P7 11. |

## Deferred QA and activation

The project-wide cross-module regression, supply-chain/image/deployment reruns and target-environment acceptance remain deferred until consolidated QA. This report does not approve production document or diagnostic-result use.

Production activation still requires accepted object-store IAM/KMS/versioning/Object-Lock/backup controls; scanner operations and signature-freshness policy; retention, legal-hold release and disposal rules; local document classifications; laboratory/imaging interfaces and terminology; critical-result thresholds, SLA and escalation ownership; access purposes and TTLs; recipient consent/authority; export formats and delivery providers; monitoring, recovery and operational sign-off. Those dependencies remain explicit fail-closed boundaries.

## Next dependency

Module 8 AI Governance may consume only authorized, purpose-bound and minimum-necessary clinical context. It must add explicit consent/input manifests, governed model/prompt/evaluation versions, draft-only outputs, uncertainty/safety evidence and clinician review without sending protected data to an unapproved provider or treating AI output as clinical truth.
