# Module 1 history and export policy review brief

**Artifact kind:** `history-and-export-policy`  
**Status:** `DRAFT_NOT_APPROVED`  
**Draft version:** `review-draft-1`  
**Approval authority:** Owner decision required

## Proposed review baseline

Configuration history and audit are different projections. Configuration history explains approved business configuration revisions and effective state. Audit evidence records security/governance actions and must remain append-only and minimum necessary. Neither screen should query arbitrary tables or export more than its approved projection.

### Configuration history contract to approve

- Define one stable parent configuration version per logical change, including an atomic weekly operating-hours batch.
- Record baseline/new revision, change-item type/subject, actor, reason, decision chain, validation result, effective time, activation/supersession, and correlation without copying secrets or unnecessary field content.
- Define version numbering/display separately from opaque IDs and lock revisions.
- Specify which fields support before/after comparison, semantic formatting, redaction, large-value truncation, linked evidence, and historical registry-label resolution.
- Use server filters, deterministic sort with stable tie-breaker, opaque cursor pagination, and operation-specific allowlists.
- Preserve history across rename, reparent, closure, supersession, policy upgrade, and user/account lifecycle changes.

### Audit view contract to approve

- Define visible event families/versions, actor representation, subject/aggregate representation, organization scope, purpose, reason visibility, correlation navigation, decision evidence, and redaction.
- Approve filters for bounded time range, actor, operation, event, subject type/ID, outcome, correlation, and risk classification; reject unknown filters and unsafe free-form query.
- Require recent authentication and an approved permission/purpose where policy demands it; log access to sensitive audit detail if required.
- Resolve historical event schemas and labels by version without rewriting original evidence.
- Distinguish hidden-resource denial, explicit denial, no results, dependency failure, and expired/retired schema behavior.

### Purpose-bound export contract to approve

| Decision area     | Required policy                                                                                                                                                         |
| ----------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Authorization     | Exact permission, purpose/legal basis, scope, recent authentication, MFA, maker-checker or approval threshold, and subject restrictions.                                |
| Request           | Bounded filter snapshot, format, field projection, locale/timezone, reason, row/size limits, idempotency, and strong revision or snapshot time.                         |
| Generation        | Authorized non-interactive identity, tenant isolation, deterministic snapshot semantics, timeout/retry, resource limits, cancellation, and failure behavior.            |
| Content safety    | Minimum-necessary fields, redaction/masking, safe dates/numbers, encoding, CSV formula injection neutralization, archive rules, metadata, and no active content.        |
| Artifact security | Private storage, encryption, opaque identifier, digest, malware/content decision where relevant, short-lived read grant, no raw provider location, and access evidence. |
| Lifecycle         | Requested/authorized/running/ready/failed/expired/disposed states, retention, legal hold, expiry, disposal proof, retry/reissue, and incident response.                 |
| Auditability      | Request, approval, generation, failure, access, expiry, hold, and disposal events with correlation and policy versions.                                                 |

### Candidate format decisions

Owners should explicitly decide whether CSV, JSON, PDF, or another format is supported per projection. CSV requires formula-injection defense and encoding/delimiter rules. PDF requires accessible tagged output, font licensing, pagination, redaction, and integrity decisions. JSON requires a versioned schema and must not become an unrestricted raw database dump.

### Retention and privacy baseline

- Assign a classification, retention schedule, jurisdiction, legal-hold behavior, access group, review date, and disposal method to history rows, audit evidence, export requests, and artifacts.
- An export must not extend source-data retention by accident. Expired artifacts become inaccessible while durable request/access/disposal evidence follows its approved schedule.
- Correction, data-subject access, investigation, and legal-hold workflows must preserve attribution without rewriting append-only security evidence.
- Logs and metrics may contain bounded IDs/state/error codes, never exported content, filters containing sensitive values, signed URLs, or destinations.

## Owner decisions required

1. Approve configuration-version identity, grouping, comparison semantics, fields, redaction, filters, ordering, cursor, and retention.
2. Approve audit-event visibility, actor/subject display, reason/payload redaction, access logging, filters, detail, and historical-schema handling.
3. Approve export purpose/legal-basis vocabulary, permissions, assurance, approvals, formats, fields, limits, snapshot, localization, and formula-injection defenses.
4. Approve private artifact storage/access, encryption/key management, digest, expiry, legal hold, disposal, provider controls, and operational ownership.
5. Approve worker identity, queue/retry/dead-letter/cancellation, monitoring, user notifications, incident response, and recovery.

## Acceptance checklist

- [ ] M1-22 and M1-23 mockups, APIs, filters, cursors, permissions, events, schemas, and tests trace to this policy.
- [ ] History, audit, and export projections are explicitly distinct and minimum necessary.
- [ ] Every supported format has injection/content-safety, accessibility, localization, schema, and integrity rules.
- [ ] Export generation/access/expiry/disposal remains tenant-isolated, purpose-bound, recent-authenticated where required, and fully evidenced.
- [ ] Product, privacy, security, governance, records, legal, operations, and engineering owners record review outcomes.
- [ ] A named authority records approval evidence for the exact checksum-bound bundle.
