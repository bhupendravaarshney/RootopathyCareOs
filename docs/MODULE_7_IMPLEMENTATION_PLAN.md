# CareOS Module 7 implementation plan

**Module:** M7 Documents and results (`P7-01` through `P7-11`)  
**Current phase:** M7A-M7F repository construction complete  
**Predecessor:** M6 repository PASS through Flyway V92  
**Implementation direction:** user's standing approval to complete repository construction before consolidated QA  
**Production acceptance:** not granted

**Completion evidence:** [Module 7 completion report](MODULE_7_COMPLETION_REPORT.md)

## Authoritative scope

The build specification defines eleven screens: document dashboard, patient document list, upload, classification/metadata, scan status, viewer, result inbox, result detail, acknowledgement/escalation, version history, and export/share intent.

The twelve named core entities are `documents`, `document_versions`, `document_links`, `document_classifications`, `document_scan_attempts`, `diagnostic_reports`, `lab_results`, `imaging_results`, `result_flags`, `result_reviews`, `result_escalations` and `document_access_intents`.

The mandatory rules are that every untrusted upload enters private quarantine; result values retain source, units, reference ranges, abnormal flags and provenance; critical results have acknowledgement/escalation workflow and SLA evidence; and replacement creates a new immutable file version rather than overwriting evidence.

## Conservative implementation decisions

- A document is organization-owned and is linked explicitly to one verified patient. Optional encounter and assessment links must belong to that same patient and organization.
- The browser uploads an exact bounded binary with filename, media type, declared byte count and lowercase SHA-256. The server validates the signature/digest and invokes the existing private-quarantine boundary before binding the business version.
- A document version uses the same opaque document/version identifiers as platform quarantine, scan, promotion, signed-access and retention evidence. Business state cannot claim `clean` without the exact clean scan and promotion proof.
- Document metadata and classification changes append attributed classification records. Prior versions, classifications, scan attempts and result evidence are never rewritten.
- Diagnostic reports link to one exact clean promoted document version. Lab values preserve textual value, source, method, unit, reference range and abnormal interpretation. Imaging results preserve source, modality, body site, finding/impression provenance and interpretation status.
- A critical result flag records the policy version, detection time, acknowledgement deadline, owner and state. Acknowledgement and escalation are separate append-only evidence. Critical flags remain visibly open until acknowledged and resolved; overdue state is calculated from database time.
- The document viewer obtains only a short-lived, purpose-bound access grant for the current clean promoted version. Bearer provider URLs remain memory-only and are never written to application tables, audit payloads, outbox events or browser storage.
- Export/share creates an immutable intent only. It does not claim recipient authority, consent, delivery, export generation or external sharing until those later policies/providers are configured.
- Storage, scanning, promotion, signed access, retention, notification, scheduler and external lab/imaging providers stay disabled by default. Their absence is a visible unavailable state, never synthetic success.

## Lifecycles

Document versions follow `quarantined -> scanning -> clean | infected | scan_failed`. A retry appends a scan attempt; a replacement appends a new `quarantined` version and supersedes only the current pointer, never the bytes or evidence of an earlier version.

Diagnostic reports follow `preliminary -> final -> amended | entered_in_error`. Result flags follow `open -> acknowledged -> resolved`; an `open` critical flag may also append one or more escalation records without silently closing the flag.

## Dependency-ordered slices

| Slice | Screens | Scope | Exit |
| --- | --- | --- | --- |
| M7A | All | Screen/action contract, authorization, lifecycle, provenance and provider boundaries | Versioned plan and migration-owned operation catalogue |
| M7B | P7-01-P7-05 | Document/version/link/classification model plus quarantine and scan evidence binding | Tenant, digest, immutable-version and unsafe-file attacks pass |
| M7C | P7-06 | Purpose-bound clean-document viewer over promotion and signed-access proof | No dirty object or durable bearer URL can escape |
| M7D | P7-07-P7-09 | Diagnostic report, lab/imaging result, flags, SLA, acknowledgement and escalation | Critical-result workflow and direct-SQL attacks pass |
| M7E | P7-10-P7-11 | Version history and immutable export/share intents | Prior evidence preserved; no unapproved delivery claim |
| M7F | All | OpenAPI/client, all eleven live routes, backend/frontend/browser/security gates and closeout | Repository PASS; consolidated QA/target acceptance remain separate |

## Required controls

- Organization-bound authorization transactions, forced RLS and composite tenant foreign keys for every tenant relation.
- UUIDv7 identifiers, strong revisions, scoped idempotency, bounded multipart uploads, exact digests and RFC 9457 failures.
- Append-only database guards for versions, links, classifications, scan attempts, reports/results, review/escalation evidence and access intents.
- Exact patient/encounter/assessment correlation and clean-promotion proof before clinical result binding or signed viewing.
- Payload-minimized audit/outbox events containing identifiers, lifecycle state and digests, never file bytes, result narrative, bearer URLs or unrestricted values.
- Five-viewport keyboard, Axe and overflow verification for every P7 route.

## Activation boundary

This plan does not approve a production object store, scanner, KMS/IAM policy, signed-access domain, retention schedule, legal-hold release/disposal process, critical-result SLA, laboratory/imaging interface, terminology catalogue, recipient consent rule, export format or sharing provider. Repository construction uses explicit configurable policy snapshots and fail-closed adapters; production activation requires owner/provider/clinical/security acceptance and target-environment evidence.

## Completion checklist

- [x] Add V93-V95 authorization, the twelve required forced-RLS relations, composite tenant integrity, append-only evidence guards and payload-minimized audit/outbox definitions.
- [x] Implement private bounded multipart upload, quarantine, classification, scan/promotion, clean-document access, result provenance, critical-result acknowledgement/escalation/resolution, immutable replacement history and export/share intents.
- [x] Publish OpenAPI 0.46.0 with exactly 123 operations, regenerate the checked TypeScript boundary and add strict runtime validators.
- [x] Replace P7-01 through P7-11 with live governed projections/actions, including browser-side SHA-256 upload verification and memory-only relative access links.
- [x] Validate/apply V1-V95 on disposable PostgreSQL 18 and pass the focused 5/5 document catalogue, registry and complete lifecycle tests.
- [x] Pass generated drift, formatting, strict typecheck, lint, the 47-source/8-feature/133-import frontend boundary, four architecture attack fixtures, all 111 frontend unit tests, the production build and all 26 API-contract cases.
- [x] Pass all eleven P7 routes across exact 1440, 1024, 768, 390 and 320 Playwright/Axe/overflow projects (5/5).
- [x] Preserve consolidated cross-module QA, production provider/policy activation and target-environment acceptance as separate work.
