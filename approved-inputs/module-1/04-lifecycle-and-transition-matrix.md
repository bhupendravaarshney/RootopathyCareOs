# Module 1 lifecycle and transition matrix candidate

**Artifact kind:** `lifecycle-and-transition-matrix`  
**Status:** `CANDIDATE_FOR_APPROVAL`  
**Candidate version:** `m1-candidate-1`  
**Approval:** Not granted

## Decision baseline

All lifecycle changes are server-owned governed operations. They reauthorize the actor and tenant in the database transaction, require a strong revision, use scoped idempotency for mutations, persist a reason where specified, and atomically commit business state plus audit/outbox evidence. Unknown transitions fail closed without success evidence.

### State models

| Aggregate                      | Candidate states                                                                       | Terminal/history rule                                                                         |
| ------------------------------ | -------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------- |
| Organization/facility          | `draft, under_review, active, suspended, closed`                                       | `closed` is terminal; reopening requires a new aggregate or explicit future policy.           |
| Identifier                     | `draft, verified, active, expired, revoked, superseded`                                | `expired, revoked, superseded` remain immutable history.                                      |
| Address/contact/responsibility | `scheduled, active, ended, superseded`                                                 | End/supersession creates a new revision; historical values are not overwritten.               |
| Unit/location                  | `draft, active, suspended, closed`                                                     | `closed` terminal; historical path retained.                                                  |
| Hours batch                    | `draft, scheduled, active, superseded, cancelled`                                      | Only draft/scheduled may cancel; active becomes superseded.                                   |
| Service                        | `draft, active, retired`                                                               | `retired` terminal; replacement is a new service/version.                                     |
| Service assignment             | `scheduled, active, suspended, ended, cancelled`                                       | End/cancel are terminal; reactivation creates a new effective assignment.                     |
| Scheme                         | `draft, active, retired`                                                               | Retired terminal.                                                                             |
| Scheme version                 | `draft, active, superseded, retired`                                                   | Content immutable once active; issued identifiers remain resolvable.                          |
| Configuration                  | `draft, validating, blocked, ready, submitted, approved, rejected, active, superseded` | `rejected` returns through a new draft revision; active only becomes superseded.              |
| Export                         | `requested, authorized, running, ready, failed, expired, disposed`                     | Artifact cannot return from expired/disposed; retry is a new job linked to the prior request. |

### Transition matrix

`RA` means recent authentication; `MC` means a distinct maker/checker. Effective times use server-validated UTC instants and the entity timezone for local rules.

| Aggregate / transition                                        | Candidate guard and assurance                                                                                                                                 | Compensation                                                                               |
| ------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------ |
| Organization `draft -> under_review`                          | Profile, identifiers, governance coverage, at least one final owner, reason, fresh readiness result.                                                          | Withdraw to `draft` before decision with reason.                                           |
| Organization `under_review -> active`                         | MC, RA 5 minutes, exact submitted revision/result, no blocker, approval unexpired.                                                                            | No destructive rollback; suspend or create forward-repair configuration.                   |
| Organization `active <-> suspended`                           | Owner plus security/governance approver, RA 5 minutes, reason, downstream access impact; reactivation reruns readiness.                                       | Reverse through separately evidenced transition.                                           |
| Organization `active/suspended -> closed`                     | MC, RA 5 minutes, no active child/access/assignment conflict, future effective time at least 24h unless incident authority; reason.                           | Terminal; forward migration/new organization only.                                         |
| Identifier `draft -> verified -> active`                      | Verification authority/evidence, uniqueness, effective range; activation may be bundled in configuration approval.                                            | Revoke or supersede; never edit active value.                                              |
| Identifier `active -> expired/revoked/superseded`             | Server expiry or authorized reason/evidence; primary replacement required where policy says.                                                                  | New identifier revision.                                                                   |
| Address/contact/responsibility `scheduled -> active -> ended` | Valid range, no prohibited overlap, one primary per type; required governance handoff has no gap.                                                             | Superseding scheduled revision.                                                            |
| Facility `draft -> under_review -> active`                    | Complete profile/address/timezone, hierarchy valid, approved hours/service policy, readiness, MC and RA on activation.                                        | Suspend or forward repair.                                                                 |
| Facility `active <-> suspended`                               | Impact preview, reason, no unsafe scheduling outcome; reactivation revalidates descendants/services.                                                          | Evidenced reverse transition.                                                              |
| Facility/unit/location `active/suspended -> closed`           | Descendants/assignments ended or coordinated, no future active dependency, reason.                                                                            | Terminal.                                                                                  |
| Unit/location `draft -> active` or reparent                   | Parent eligible, same tenant/facility, no cycle, depth <= 8, effective impact preview, strong revision.                                                       | New reparent revision; historical path retained.                                           |
| Hours `draft -> scheduled/active`                             | Atomic interval/holiday validation, timezone/DST check, no overlap, exact target revision. Future start becomes scheduled.                                    | Cancel scheduled or supersede active with a new batch.                                     |
| Service `draft -> active -> retired`                          | Code/owner/coding valid; retirement requires assignment/consumer impact review and reason.                                                                    | New replacement service, not reactivation.                                                 |
| Assignment `scheduled -> active <-> suspended -> ended`       | Parent lifecycle/eligibility, range/no overlap, capacity rules, reason for suspension/end.                                                                    | New assignment for ended record.                                                           |
| Scheme/version `draft -> active`                              | Safe pattern, preview, sequence ownership, one active version, MC, RA 5 minutes.                                                                              | Activate superseding version; never change issued format.                                  |
| Configuration `draft -> validating -> blocked/ready`          | One parent active version, serialized validation, immutable typed result bound to revision/digest.                                                            | Correct draft and run a new validation.                                                    |
| Configuration `ready -> submitted -> approved/rejected`       | Fresh result <= 15 minutes; maker submits; distinct checker with approval permission, RA 5 minutes, reason. Any relevant change invalidates result/approval.  | Rejection creates new draft revision.                                                      |
| Configuration `approved -> active`                            | Approval <= 30 minutes, same version/revision/digest, effective time, provider readiness, activator authorized and not maker; atomic pointer/evidence commit. | Prior active remains on failure; after success use forward repair or emergency suspension. |
| Export `requested -> authorized`                              | Purpose/legal basis, bounded filters/projection, request permission, RA 5 minutes; restricted export needs distinct approver.                                 | Deny/expire; no artifact.                                                                  |
| Export `authorized -> running -> ready`                       | Authorized worker identity, tenant-bound snapshot, safe format, digest/private object, access expiry.                                                         | Failure terminal; retry creates linked job.                                                |
| Export `ready -> expired -> disposed`                         | 24-hour access expiry, retention/hold evaluation, deletion proof; hold blocks disposal but not access expiry.                                                 | Disposal is terminal.                                                                      |

### Cross-cutting transition and concurrency rules

- One mutation uses one stable operation key, permission, request digest, and expected revision. Exact idempotent replay returns the original response; a changed request under the same key is a conflict.
- Maker and checker must be distinct active human actors. A service identity cannot approve, and an actor cannot approve a transition targeting its own access where subject separation applies.
- RA uses a server-recorded authentication instant; the strictest operation window wins. MFA is required for approval, final-owner, security administration, activation, and export authorization.
- Scheduled transitions are executed only by an exact allow-listed service identity and revalidate tenant, state, dependencies, policy version, and effective time. Retries are idempotent; exhausted work moves to a governed dead-letter state without changing business state.
- Closure and suspension never cascade silently. The impact result lists blocking children and coordinated transitions; all committed child changes belong to one configuration version and transaction boundary where atomicity is required.
- Effective ranges are `[from,to)`. Adjacent ranges are allowed; prohibited overlaps are rejected in PostgreSQL. Server time owns decisions and a maximum client clock skew of two minutes is tolerated only for display/input validation, never authorization.

## Verification and acceptance

- Unit and database tests enumerate every allowed edge and reject every unlisted edge, stale revision, changed replay, wrong actor, self-approval, expired approval, overlap, cycle, cross-tenant link, and terminal-state mutation.
- HTTP tests assert permission/denial mode, RA/MFA/MC challenges, field/conflict problems, safe replay, and exact event mapping.
- Worker tests cover duplicate delivery, lease expiry, restart, dependency outage, dead letter, and operator-authorized replay without double transition.
- UI states show impact, reason, assurance, validation freshness, decision chain, failure recovery, and immutable completion evidence.

## Approval boundary

This is the candidate lifecycle vocabulary and assurance model. No migration, operation, scheduled worker, or enabled UI transition may treat it as production authority until the exact package is approved.
