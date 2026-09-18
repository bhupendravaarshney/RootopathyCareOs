# Module 1 lifecycle and transition review brief

**Artifact kind:** `lifecycle-and-transition-matrix`  
**Status:** `DRAFT_NOT_APPROVED`  
**Draft version:** `review-draft-1`  
**Approval authority:** Owner decision required

## Proposed review baseline

The names below are candidate vocabulary for structured review, not an accepted state model. Owners must approve or replace every state and transition. The final matrix must name the operation, source states, target state, actor permission, scope, reason, recent-authentication rule, maker-checker rule, effective-time behavior, guards, side effects, audit/outbox events, idempotency, concurrency, and reversal/compensation path.

| Aggregate                            | Candidate states to review                                                                             | Required transition decisions                                                                                                                                                  |
| ------------------------------------ | ------------------------------------------------------------------------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| Organization                         | `draft`, `under_review`, `active`, `suspended`, `closed`                                               | Initial provisioning/owner ceremony, submit, independent activation, suspend/reactivate, close, correction, whether reopening is allowed, and downstream access impact.        |
| Organization identifier              | `draft`, `verified`, `active`, `expired`, `revoked`, `superseded`                                      | Verification authority/evidence, activation, expiry automation, revocation reason, correction versus supersession, and duplicate resolution.                                   |
| Address/contact                      | `scheduled`, `active`, `ended`, `superseded`                                                           | Primary designation, future effective change, overlap, verification, correction, end, and historical preservation.                                                             |
| Governance responsibility            | `scheduled`, `active`, `ended`                                                                         | Required coverage, person/account eligibility, overlap or handoff, escalation when missing, and visibility changes.                                                            |
| Facility                             | `draft`, `under_review`, `active`, `suspended`, `closed`                                               | Wizard save/resume, readiness, activation, suspend/reactivate, closure impact, future effective closure, and immutable history.                                                |
| Department/unit/location             | `draft`, `active`, `suspended`, `closed`                                                               | Parent assignment/reparenting, cycle/depth guards, activation dependency, closure with descendants/assignments, and historical path.                                           |
| Operating-hours batch                | `draft`, `scheduled`, `active`, `superseded`, `cancelled`                                              | Atomic weekly/holiday batch save, overlap/DST validation, future activation, correction, supersession, and downstream scheduling impact.                                       |
| Service definition                   | `draft`, `active`, `retired`                                                                           | Coding/owner validation, activation, update/version decision, retirement impact, and whether reactivation is allowed.                                                          |
| Service assignment                   | `scheduled`, `active`, `suspended`, `ended`, `cancelled`                                               | Eligibility, facility/location dependency, overlap, capacity/availability, future activation/end, suspension/reactivation, and conflict handling.                              |
| Identifier scheme                    | `draft`, `active`, `retired`                                                                           | Validation, independent activation decision, single-active-version rule, retirement, and whether an unused draft may be deleted.                                               |
| Identifier scheme version            | `draft`, `active`, `superseded`, `retired`                                                             | Immutable rule content after activation, sequence ownership, concurrency, effective switch, supersession, and issued-ID compatibility.                                         |
| Configuration version/change request | `draft`, `validating`, `blocked`, `ready`, `submitted`, `approved`, `rejected`, `active`, `superseded` | One logical change/one parent version, validation freshness, submit, maker-checker separation, rejection/rework, activation time, stale baseline, and rollback/forward repair. |
| Export job                           | `requested`, `authorized`, `running`, `ready`, `failed`, `expired`, `disposed`                         | Purpose/recent-auth checks, snapshot semantics, cancellation, retry, artifact expiry, access evidence, retention, legal hold, and disposal.                                    |

### Transition record required for every row

| Attribute            | Required decision                                                                                                               |
| -------------------- | ------------------------------------------------------------------------------------------------------------------------------- |
| Stable operation key | Exact permission/operation registry binding and protected route/use case.                                                       |
| From/to              | Allowed source set, one target, terminal-state rules, and no-op/replay semantics.                                               |
| Actor and scope      | Eligible role/permission, tenant/facility scope, subject restrictions, delegation ceiling, and final-owner safeguards.          |
| Assurance            | Reason, recent authentication, MFA, maker-checker separation, approval expiry, and evidence requirements.                       |
| Guards               | Field completeness, dependencies, effective-range overlap, hierarchy, readiness, freshness, and external-provider requirements. |
| Time                 | Server-owned decision time, requested effective time, timezone/DST semantics, scheduling, expiry, and maximum skew.             |
| Concurrency          | Strong revision/ETag, lock strategy, idempotency key/replay, and concurrent-conflict outcome.                                   |
| Evidence             | Audit/outbox mapping, payload schema, correlation, changed fields, decision record, and retained validation results.            |
| Compensation         | Whether reversal exists, who authorizes it, what remains immutable, and whether forward repair is mandatory.                    |

### Cross-cutting invariants proposed for review

- No transition trusts organization selection or a client-authored status; tenant authorization and current state are revalidated transactionally.
- Unknown state/transition combinations fail closed and create no business, audit, outbox, or idempotency success evidence.
- Maker-checker means distinct authenticated actors; the checker cannot be the target where subject separation applies.
- Terminal and historical rows are not destructively rewritten. Corrections create explicit revisions or supersession lineage.
- Effective ranges use one approved boundary convention and are protected from prohibited overlap in PostgreSQL.
- Parent closure/suspension evaluates children, assignments, access, scheduling, and history before commit.
- Activation uses a fresh typed validation result bound to the exact candidate version and lock revision.

## Owner decisions required

1. Approve the state vocabulary, stable keys, terminal states, and display labels independently for every aggregate.
2. Approve every transition, guard, reason, assurance level, maker-checker separation, effective-time rule, and reversal policy.
3. Decide cascade versus block versus coordinated-transition behavior for hierarchy, service assignment, access, and closure impacts.
4. Approve timeout/expiry/freshness windows, scheduled-transition processing, worker identity, retries, dead-letter handling, and operator recovery.
5. Decide where invariants live in PostgreSQL, application transactions, provider adapters, or multiple layers.

## Acceptance checklist

- [ ] Every lifecycle mutation in M1-01 through M1-23 maps to exactly one reviewed transition row.
- [ ] Each row maps to approved permission, operation, API, validation, audit, outbox, UI, and test identifiers.
- [ ] Invalid, stale, duplicate, concurrent, expired, cross-tenant, and wrong-actor paths have explicit outcomes.
- [ ] Effective-time, hierarchy, overlap, final-owner, maker-checker, and historical-preservation invariants are testable at the database boundary.
- [ ] Product, security, privacy, governance, operations, API, and database owners record review outcomes.
- [ ] A named authority records approval evidence for the exact checksum-bound bundle.
