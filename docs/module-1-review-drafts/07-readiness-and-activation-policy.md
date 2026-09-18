# Module 1 readiness and activation policy review brief

**Artifact kind:** `readiness-and-activation-policy`  
**Status:** `DRAFT_NOT_APPROVED`  
**Draft version:** `review-draft-1`  
**Approval authority:** Owner decision required

## Proposed review baseline

Readiness is a server-calculated, versioned projection over authoritative state and evidence. The browser may display and deep-link a result but must never author completion. Each gate definition needs a stable key/version, label, description, severity, applicability, exact evaluator, evidence references, freshness window, deep link, owner, remediation, override rule, and activation effect.

The provisional M1-05/M1-06 reference exposes profile, effective-owner, and activation-policy gates plus bounded facility/membership counts. Those values prove the mechanism only and must be accepted, replaced, or expanded.

### Candidate readiness catalogue for review

| Gate family               | Candidate questions and evidence                                                                                                                                                 |
| ------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Organization identity     | Required legal/display identity, country/jurisdiction, timezone/locale, lifecycle, registration identifiers, verification evidence, and current revision.                        |
| Contacts and governance   | Required address/contact types, verified primary channels, clinical/privacy/security/billing responsibility coverage, effective dates, and escalation data.                      |
| Ownership and access      | At least one eligible indefinite effective owner, approved administrator scope, MFA enforcement, invitation/access exceptions, final-owner protection, and no expired approvals. |
| Facilities                | Minimum/maximum facility requirement, required facility fields, address/contact, lifecycle, hierarchy integrity, and closure/suspension exceptions.                              |
| Departments and locations | Required hierarchy/location coverage, no cycles/orphans, valid parent lifecycle, physical/virtual requirements, and effective ranges.                                            |
| Operating hours           | Required targets, complete weekly coverage decision, timezone/DST validity, no prohibited overlaps, holiday exceptions, and fresh atomic batch.                                  |
| Services                  | Approved active catalogue entries, clinical owner/coding requirements, facility/location assignment eligibility, effective dates, and no conflicts.                              |
| Identifier schemes        | Required scheme scopes, validated immutable active version, concurrency/sequence readiness, and no ambiguous active versions.                                                    |
| Configuration integrity   | No stale baseline, unresolved blockers, invalid references, pending conflicting changes, failed provider dependency, or out-of-date validation result.                           |
| Governance evidence       | Required reasons, clean evidence, policy versions, approval separation, audit/outbox registry mapping, and retention/access configuration.                                       |

### Gate result contract

Each result should expose only approved minimum-necessary data:

- gate key and definition version;
- outcome such as candidate `complete`, `warning`, `blocked`, or `not_applicable` vocabulary;
- stable reason/remediation code and safe display text;
- evaluated state revision and server evaluation time;
- freshness expiry or stale marker;
- bounded evidence references and authorized deep link;
- override status/evidence only if override is approved.

### Candidate activation sequence for review

1. A caller with the approved permission requests a typed validation run for one exact configuration version and revision.
2. The server evaluates every applicable gate and persists immutable results/evidence.
3. Submission is allowed only for a fresh result with no blocking outcome and no changed baseline.
4. An independent authorized checker reviews the exact submitted version, decision reason, and evidence.
5. Approval expires if not activated within the approved window or if relevant state changes.
6. Activation revalidates version, lock revision, checker separation, approval, effective time, and required provider readiness in one governed transaction.
7. Business state, active-version pointer, audit, outbox, and idempotency outcome commit atomically.
8. Failure leaves the prior active configuration intact; rollback versus forward repair follows a separately approved policy.

### Override baseline proposed for review

No override should exist by implication. Owners must enumerate overrideable gate keys, eligible roles, justification/evidence, independent approval, maximum duration, notification/escalation, display, audit/outbox events, re-evaluation, and expiry behavior. Security, final-owner, tenant isolation, invalid schema, missing authorization/event policy, or corrupted evidence should be considered non-overrideable unless a formal risk authority explicitly decides otherwise.

## Owner decisions required

1. Approve the exact gate catalogue, definition versions, applicability, severity/outcome vocabulary, evaluators, evidence, remediation, and deep links.
2. Approve freshness windows and which state/event changes invalidate each result or approval.
3. Approve submit/check/approve/reject/expire/activate actor permissions, maker-checker separation, recent authentication, MFA, reason, and timing.
4. Approve every overrideable and non-overrideable gate plus duration, escalation, independent approval, and evidence rules.
5. Approve activation effective-time, failure, partial-provider, rollback/forward-repair, notification, and incident procedures.

## Acceptance checklist

- [ ] Every gate is deterministic, server-calculated, versioned, tenant-authorized, evidence-backed, and linked to a named owner.
- [ ] Freshness and invalidation cover all relevant mutations, policy changes, clocks, provider dependencies, and concurrency races.
- [ ] Submission and activation bind the exact version/revision/result and enforce distinct maker-checker actors where required.
- [ ] Overrides are explicit, bounded, independently authorized, visible, expiring, audited, and impossible for prohibited invariants.
- [ ] Product, security, privacy, governance, operations, database, and service owners record review outcomes.
- [ ] A named authority records approval evidence for the exact checksum-bound bundle.
