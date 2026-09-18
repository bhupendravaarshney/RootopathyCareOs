# Module 1 history and export policy candidate

**Artifact kind:** `history-and-export-policy`  
**Status:** `CANDIDATE_FOR_APPROVAL`  
**Candidate version:** `m1-candidate-1`  
**Approval:** Not granted

## Decision baseline

Configuration history, security/governance audit, and export are separate minimum-necessary projections. Neither M1-22 nor M1-23 can query arbitrary tables. All filters, fields, sorts, formats, purposes, retention rules, and worker operations are allow-listed and versioned.

### Configuration history

- One configuration version groups one logical change set and has an opaque UUID plus display number `CFG-YYYY-NNNNNN`. Display numbers are not authorization or concurrency tokens.
- The record includes parent version/digest, changed entity references, baseline/new revisions, maker/checker/activator, bounded reason, validation result/digest, decision/effective/activation/supersession times, policy versions, and correlation ID.
- Comparison supports approved non-secret fields from the data dictionary. Values are rendered using the historical schema/registry version; confidential values are masked unless the caller has the source-record permission.
- Filters: bounded effective/activation time range maximum 366 days, status, change type, actor ID, subject type/ID, and correlation ID. Sort is activation time descending then UUID. Cursor is opaque, signed, tenant/filter/sort-bound, and valid 15 minutes. Page size 25 default, 100 maximum.
- History is retained seven years after supersession/closure, subject to longer configured jurisdiction policy or legal hold. Corrections append a linked correction; activated history is never rewritten.

### Audit view

- Visible families are only the approved M1 event registry. List projection exposes occurred time, safe actor label/opaque ID, operation/event/version, subject type/opaque ID, outcome, risk, correlation, and redaction marker.
- Detail adds permitted payload fields, decision/evidence references, protected reason projection, registry version, and recorded time. It never exposes credentials, tokens, MFA/recovery material, raw contacts, provider locations, signed URLs, or unrestricted before/after records.
- Filters: time range maximum 90 days per request, actor ID, exact operation, event/version, subject type/ID, outcome, risk, and correlation ID. Unknown filters and free-form SQL-like search are rejected. Sort is occurred time descending then event UUID; cursor/page rules match history.
- `evidence.audit.read` plus MFA and RA <= 10 minutes are required. Opening a `RESTRICTED` event detail records `evidence.audit.accessed` with purpose code and correlation. Hidden denial is used.
- Audit evidence is retained seven years from record time by candidate default, extendable by jurisdiction registry or legal hold but never shortened below active investigation/approval obligations. Evidence is append-only.

### Export policy

| Area       | Candidate decision                                                                                                                                                                                                                                                                                       |
| ---------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Purposes   | `configuration_review`, `regulatory_evidence`, `security_investigation`, `data_correction`. Request requires one purpose, legal-basis registry key, bounded reason, and matching permission.                                                                                                             |
| Formats    | UTF-8 CSV with BOM optional by request and versioned JSON Lines. PDF and raw database/archive export are unsupported in candidate 1.                                                                                                                                                                     |
| Projection | Named `history-summary-v1`, `history-detail-v1`, `audit-summary-v1`, or `audit-detail-v1`. Detail projections are `RESTRICTED` and require independent export approval.                                                                                                                                  |
| Limits     | Summary 100,000 rows/250 MiB; detail 25,000 rows/100 MiB; one active job per requester/projection/tenant; filter ranges match the UI policy. Exceeding limits fails before artifact generation.                                                                                                          |
| Snapshot   | Repeatable database snapshot at server-owned `snapshotTime`; filter/projection/policy digest stored with the request. Later source changes do not alter the artifact.                                                                                                                                    |
| Assurance  | MFA and RA <= 5 minutes for request/access. Restricted detail requires a distinct `export_approver`; requester cannot approve. Access grant is GET-only, private, digest-bound, and <= 10 minutes.                                                                                                       |
| Safety     | CSV cells beginning after whitespace with `=,+,-,@` are prefixed with a single quote; CR/LF/tab/control characters are normalized or rejected by field policy. JSON Lines has schema header metadata and no executable content. Fields use locale-safe display plus canonical UTC values where relevant. |
| Artifact   | AES-256 server-side encryption or stronger approved provider control, private bucket/prefix, opaque UUID, SHA-256 digest, content type/disposition allowlist, no provider path in API/audit/logs, and URL-free access evidence.                                                                          |
| Lifecycle  | `requested -> authorized -> running -> ready -> expired -> disposed`, with `failed` terminal. Ready access expires after 24 hours. Disposal occurs within 24 hours after expiry unless legal hold; legal hold blocks deletion but never restores user access.                                            |
| Workers    | `m1-export-worker-v1` and `m1-retention-worker-v1` use disjoint exact grants, tenant-bound records, inbox/idempotency, five attempts with exponential backoff, then governed dead letter. Operator replay requires permission, RA, reason, unchanged authorization, and fresh provider readiness.        |

Export request metadata and access/disposal evidence are retained seven years; failed temporary content is removed immediately when safe. A generated artifact follows the 24-hour availability rule and cannot accidentally extend source retention. Legal hold stores provider-version evidence and requires separately governed release/disposal authority, which is not part of ordinary M1 administration.

### API and user states

- M1-22 supports history list, empty/no-result, compare selection, semantic diff, redaction, schema-retired display, cursor expiry/restart, and purpose-bound export request/status.
- M1-23 supports audit list/detail, hidden denial, RA challenge, restricted-access evidence, bounded filters/cursor, export request/approval status, unavailable/expired artifact, and correlation navigation.
- Export status polling uses bounded backoff and stops in terminal states; browser refresh recovers from server state. No artifact bytes, URLs, filters with sensitive values, or destinations enter analytics/logs/local storage.

## Verification and acceptance

- Tests cover filter/sort/cursor allowlists, cursor tampering/expiry, historical schema rendering, field redaction, restricted detail access evidence, purpose/permission/RA/MC, snapshot consistency, row/size bounds, idempotency, retries, and tenant isolation.
- Format tests attack CSV formula injection, delimiter/newline/control characters, spreadsheet interpretation, JSON schema drift, encoding, filenames, content type, and archive/active-content attempts.
- Storage tests prove private access, encryption policy, exact digest, short-lived GET-only grant, URL-free evidence, expiry, hold-blocked disposal, deletion proof, and provider drift.
- Operational acceptance includes dead-letter review, incident response, key rotation, backup/restore, retention reconciliation, and timed disposal evidence.

## Approval boundary

The purposes, projections, limits, formats, retention periods, and worker contracts are candidates. Privacy, records/legal, security, governance, operations, and product authorities must accept the exact digest and any jurisdiction extension before production export or audit access is enabled.
