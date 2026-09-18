# Module 1 readiness and activation policy candidate

**Artifact kind:** `readiness-and-activation-policy`  
**Status:** `CANDIDATE_FOR_APPROVAL`  
**Candidate version:** `m1-candidate-1`  
**Approval:** Not granted

## Decision baseline

Readiness is a versioned, server-calculated projection over authoritative tenant state. The browser can display, filter, and deep-link results but cannot mark a gate complete. Candidate 1 uses outcomes `complete`, `warning`, `blocked`, and `not_applicable`; only `blocked` prevents submission/activation, while warnings require explicit acknowledgement in the submit reason.

Every result carries gate key/version, outcome, reason/remediation code, evaluated configuration revision/digest, evaluated/expiry times, bounded evidence references, and an authorized deep link. The catalogue version is `m1-readiness-v1`.

### Gate catalogue

| Gate key                                   | Candidate evaluator and evidence                                                                                                                      | Blocking rule / deep link                                                                                     |
| ------------------------------------------ | ----------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------- |
| `organization.profile.complete`            | Required legal/display name, type, country, timezone, locale, eligible lifecycle, current revision.                                                   | Missing/invalid is blocked. M1-07.                                                                            |
| `organization.identifier.primary_verified` | At least one current verified primary registration identifier for configured jurisdiction; no duplicate/overlap.                                      | Blocked when jurisdiction policy requires it; otherwise warning. M1-08.                                       |
| `organization.contact.coverage`            | Current registered address and one verified primary operational contact.                                                                              | Missing registered address is blocked; unverified contact warning for draft and blocked at activation. M1-09. |
| `organization.governance.coverage`         | One effective primary clinical, privacy, security, and billing responsibility with escalation channel.                                                | Any required gap blocked. M1-11.                                                                              |
| `access.final_owner`                       | At least one active indefinite owner, no pending operation would remove the last owner.                                                               | Always blocked; non-overrideable. M1-20.                                                                      |
| `access.mfa_enforced`                      | MFA enabled for every owner/admin/approver/security/auditor/export approver; no expired reset approval.                                               | Always blocked; non-overrideable. M1-03/M1-20.                                                                |
| `network.facility.minimum`                 | At least one submitted/active facility with complete address/timezone and no closure conflict.                                                        | Blocked for organization activation. M1-12/M1-13.                                                             |
| `network.hierarchy.valid`                  | No orphan/cycle/depth violation; every active unit/location has eligible active ancestors and valid effective range.                                  | Always blocked; non-overrideable. M1-14/M1-15.                                                                |
| `network.hours.valid`                      | Every active facility has one current atomic hours batch; no overlap and DST/timezone rules pass.                                                     | Blocked unless facility is explicitly non-operational; then not applicable. M1-16.                            |
| `service.catalogue.active`                 | At least one eligible active service with code/owner requirements.                                                                                    | Blocked for care-provider/network types; not applicable for administrative type. M1-17.                       |
| `service.assignment.valid`                 | Every active service intended for delivery has a valid facility/location assignment and no overlap/parent conflict.                                   | Blocked when delivery is declared; otherwise warning. M1-18.                                                  |
| `identifier.scheme.active`                 | Every required scope has exactly one validated active immutable scheme version and sequence owner.                                                    | Blocked where identifiers will be issued; otherwise not applicable. M1-19.                                    |
| `configuration.integrity`                  | Exact parent active version, no stale baseline, pending conflicting change, unknown registry, invalid reference, or uncommitted provider requirement. | Always blocked; non-overrideable. M1-21.                                                                      |
| `governance.registry.active`               | Exact approved role/permission/operation, audit/outbox, readiness, retention, and export registry versions are production-eligible.                   | Always blocked; non-overrideable. M1-21.                                                                      |
| `platform.dependencies.ready`              | Database/Redis and only the provider capabilities required by the candidate operation report fresh safe readiness.                                    | Required dependency failure blocked; optional unused capabilities not applicable. M1-21.                      |

### Freshness and invalidation

- A completed validation result is fresh for 15 minutes. An approval is fresh for 30 minutes from checker decision and never outlives its validation result.
- Any mutation to an evaluated entity, membership/role/MFA state, registry/policy version, parent active configuration, required provider readiness, legal hold/retention rule, or system clock anomaly invalidates the affected result and approval immediately.
- The result digest covers tenant, configuration ID/revision, parent digest, catalogue versions, ordered gate keys/outcomes/evidence digests, and evaluation time. The client cannot submit a digest it did not receive.
- Invalidation is transaction-bound for repository mutations and reconciled before activation for external provider state. A failed invalidation consumer makes activation fail closed.

### Submission and activation

1. An authorized editor requests validation for one exact configuration revision. Concurrent validation serializes per configuration.
2. The server evaluates every applicable gate and persists immutable results and digest. Failed evaluation creates failure evidence but no ready result.
3. A maker submits only a fresh result with zero blockers, records a bounded reason, and acknowledges warnings.
4. A distinct MFA-authenticated checker with RA <= 5 minutes approves or rejects the exact digest. Rejection records a reason and ends that submission.
5. Approval expires on time or invalidation. It is never silently refreshed.
6. An authorized activator who is not the maker revalidates authorization, separation, tenant, revisions, digest, freshness, effective time, registries, and dependencies in one governed transaction.
7. The active-version pointer, supersession, business revisions, idempotency response, audit, and outbox evidence commit atomically.
8. Any failure leaves the prior active configuration intact. Once activation commits, correction is a forward-repair configuration; emergency suspension follows the separate incident policy.

### Overrides

Candidate 1 defines **no readiness override**. Warning acknowledgement is not an override and cannot convert a blocker. This deliberately excludes bypasses for tenant isolation, final-owner, MFA, schema/registry, hierarchy, overlap, authorization, evidence, freshness, provider integrity, or corrupted state. A later override design requires a new policy version, risk authority, exact allowlist, independent approval, duration/expiry, escalation, visible evidence, and tests.

## Verification and acceptance

- Deterministic evaluator tests cover complete/warning/blocked/not-applicable outcomes, authorized deep links, minimum-necessary evidence, and stable remediation codes.
- Integration tests prove transaction-bound invalidation, stale baseline, changed state during evaluation/approval, clock/freshness boundaries, dependency outage, concurrent validation/activation, exact replay, and rollback.
- Authorization tests prove maker/checker/activator separation, MFA/RA, hidden tenant/resource behavior, service-identity constraints, and cross-tenant/direct-SQL denial.
- M1-05 and M1-06 display this catalogue; M1-21 owns validation/submission/decision/activation states and never computes completion locally.

## Approval boundary

The catalogue and timing values are implementation candidates only. No organization or configuration can be production-activated until the exact policy, registry versions, provider requirements, and package digest are approved and production preflight accepts them.
