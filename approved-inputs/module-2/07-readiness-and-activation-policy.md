# Module 2 readiness and activation policy candidate

**Artifact kind:** `readiness-and-activation-policy`  
**Status:** `CANDIDATE_FOR_APPROVAL`  
**Candidate version:** `m2-candidate-1`  
**Approval:** Not granted

## Decision baseline

Workforce readiness and practitioner eligibility are versioned, server-calculated results over authoritative state. The browser may display and deep-link results but cannot mark completion, select its own gate set, or assert eligibility. The server derives the `clinical` or `non_clinical` pathway from the immutable onboarding decision and current practitioner state.

Gate outcomes are `complete`, `warning`, `blocked`, and `not_applicable`. Only `blocked` prevents submission or activation; warnings require explicit maker acknowledgement. Each result records gate key/version, pathway applicability, outcome, safe reason/remediation code, evaluated member/configuration revisions and digest, evaluated/expiry times, bounded evidence references/digests, and authorized deep link. Catalogue version is `m2-readiness-v1`.

### Common activation gates

| Gate key | Evaluator and evidence | Blocking rule / deep link |
| --- | --- | --- |
| `workforce.identity.linked` | One active organization-person link, no unresolved exact duplicate/approved merge conflict, current identity revision. | Always blocked on conflict/missing. M2-04/M2-05. |
| `workforce.identity.minimum` | Required legal/chosen display fields, approved working-age rule when birth date required, valid minimum work contact. | Blocked. M2-05. |
| `workforce.member.identifier` | Unique active organization workforce identifier and eligible member lifecycle. | Blocked. M2-03/M2-21. |
| `workforce.engagement.coverage` | One eligible active/scheduled engagement covering activation instant; manager/reference valid where required. | Blocked. M2-06. |
| `workforce.assignment.primary` | At least one eligible hierarchy assignment covering activation instant, with exactly one primary where policy requires. | Blocked. M2-15. |
| `workforce.assignment.hierarchy` | M1 organization/facility/department/location all active and range-covering; no overlap or pending closure/suspension conflict. | Non-overrideable blocker. M2-15/M2-22. |
| `workforce.availability.valid` | One atomic weekly profile or explicit approved `not_required` employment category; timezone and exceptions valid. | Blocked unless genuinely not applicable. M2-18. |
| `workforce.access.intent_resolved` | Requested access is either no-account-required, pending invitation (warning), linked with valid membership/grants, or explicitly deferred. No excessive/invalid grant. | Invalid/excess grant blocked; absent optional account warning/not applicable. M2-17/M2-19. |
| `workforce.lifecycle.clear` | No suspension/offboarding, conflicting transition, stale impact, or terminal state. | Non-overrideable blocker. M2-23/M2-24. |
| `workforce.registry.active` | Exact approved profession/employment/assignment/credential/scope/notification registry versions and M1 RBAC/audit policy versions active. | Non-overrideable blocker. M2-28. |
| `workforce.configuration.integrity` | Exact active workforce snapshot, parent digest, no unresolved registry/configuration conflict, all referenced versions available. | Non-overrideable blocker. M2-26. |
| `workforce.governance.separation` | Maker/checker/subject rules satisfiable; no actor/state change invalidates submitted decisions. | Non-overrideable blocker. M2-20. |
| `workforce.platform.ready` | Database/Redis plus only platform/provider capabilities needed for the pathway and pending evidence report fresh safe readiness. | Required dependency failure blocked; unused capability not applicable. M2-20. |

### Clinical pathway gates

| Gate key | Evaluator and evidence | Blocking rule / deep link |
| --- | --- | --- |
| `practitioner.profile.active` | Active practitioner profile with current profession version. | Blocked. M2-07. |
| `practitioner.registration.current` | At least one verified, unsuspended/unrevoked, unexpired required professional registration covering activation instant and jurisdiction. | Blocked. M2-09. |
| `practitioner.qualification.complete` | Every profession/scope-required qualification is verified/current or explicitly not applicable by exact rule. | Blocked. M2-08. |
| `practitioner.credential.complete` | All mandatory credential types are verified/current, clean evidence decision exists, no terminal/suspension conflict. | Blocked. M2-10–M2-12. |
| `practitioner.specialty.valid` | Required primary specialty exactly one and effective; secondary specialties valid. | Blocked when profession/scope requires; otherwise warning/not applicable. M2-13. |
| `practitioner.scope.approved` | At least one approved current scope, activities/restrictions explicit, independent decision current. | Blocked. M2-14. |
| `practitioner.service.eligible` | Every requested active service assignment has point-in-time evidence covering registration, credential, scope, hierarchy, service, supervision, and dates. | Invalid requested assignment blocked; none requested may be warning according to role. M2-16. |
| `practitioner.supervision.resolved` | Each required supervisor is active, appropriately scoped/eligible, assigned to compatible context/range, with no circular self-supervision. | Blocked where any requirement applies. M2-14/M2-16. |
| `practitioner.expiry.horizon` | No mandatory registration/credential expires before activation or within candidate minimum 7-day activation horizon without an approved successor. | Expired/0–7 blocked; 8–30 warning; later complete. M2-25. |
| `practitioner.document.pipeline` | Every evidence document used by a decision has clean promoted evidence under an accepted scanner/policy and no disposal/hold integrity conflict. | Non-overrideable blocker. M2-10/M2-12. |

For `non_clinical`, every `practitioner.*` gate is present in the result as `not_applicable` with pathway evidence; it is not silently omitted or client-hidden. A later conversion to clinical is a governed pathway change that creates the clinical steps and invalidates readiness.

### Eligibility evaluator

Activation readiness is not clinical eligibility. `m2-eligibility-v1` evaluates one practitioner, service, facility/location context, activity, and instant/range. It requires:

1. active organization and M1 service/context;
2. active workforce member, engagement, practitioner profile, and compatible hierarchy assignment;
3. current verified registration, qualifications, and mandatory credentials;
4. approved scope containing the activity/context with all restrictions satisfied;
5. current supervision relationship where required;
6. current service assignment covering the requested context/range;
7. no effective suspension, revocation, offboarding, expiry, registry invalidation, or dependency uncertainty.

The result is `eligible`, `ineligible`, or `indeterminate`. `indeterminate` fails closed for future assignment/signing. Evidence stores exact source IDs/revisions/digests and policy versions, not raw document/person data. Reevaluation after a material event changes prospective eligibility and never rewrites historical clinical attribution.

### Freshness and invalidation

- Completed readiness is fresh for 15 minutes. Checker approval is fresh for 30 minutes and never outlives readiness. Eligibility evidence is valid only through the earliest authoritative dependency expiry/change; interactive consumers re-evaluate at the action instant where safety requires.
- Any material mutation to person identity, member/pathway, engagement, practitioner, qualification, registration, credential/evidence/scan, specialty, scope, assignment, service assignment, availability, account/access, membership/MFA, organization/service/hierarchy, controlled registry, active policy/configuration snapshot, required provider readiness, hold/retention state, or system-clock integrity invalidates affected results immediately.
- Transaction-owned changes write invalidation in the same commit. External/provider state is rechecked before decision/activation. Missing/failed invalidation consumption causes activation and safety-relevant eligibility use to fail closed.
- Result digest covers organization, member/pathway, member and active-configuration revisions, catalogue/policy/registry versions, ordered gates/outcomes/evidence digests, evaluation instant, and expiry. The client cannot supply a replacement digest.

### Submission, decision, and activation

1. Authorized maker requests validation for one exact member revision. Runs serialize per member and reuse an exact completed idempotent request.
2. Server derives the pathway and evaluates every common and clinical/not-applicable gate, persisting immutable results and digest. Evaluator failure produces failure evidence, not a ready result.
3. Maker submits only a fresh zero-blocker result, records a bounded reason, and acknowledges named warnings. Submission locks the digest/revision.
4. Distinct MFA-authenticated checker with `workforce.activation.approve` and RA <= 5 minutes approves or rejects. Checker cannot be the subject or maker. Rejection records a reason.
5. Any time expiry or material change invalidates the decision; it is never refreshed silently.
6. Authorized activator with `workforce.activation.execute`, who is not maker or subject, rechecks tenant, live permission/scope, separation, member/revisions/digest, result and approval freshness, effective time, registries, provider readiness, and every non-overrideable gate.
7. Member activation, request state, lifecycle transition, current readiness reference, idempotency response, audit, outbox, and initial eligibility invalidations/evaluations commit atomically.
8. Failure leaves the member non-active and prior state unchanged. Correction uses a new run/submission; committed activation is corrected through suspension/offboarding/forward repair, never rollback deletion.

### Expiry automation

Registration and credential expiry items occupy exactly one bucket at an evaluation date: `61–90`, `31–60`, `8–30`, `0–7`, or `expired` days. Earlier than 90 days is not in an actionable bucket. Scheduler milestones are exact `90, 60, 30, 7, 0`; each `(credential, version, milestone, policy)` is unique and idempotent. Candidate 1 sends approved minimal email notices only after channel/recipient policy and records delivery separately. Notification failure does not change the expiry or eligibility result.

At expiry, future eligibility becomes ineligible and affected readiness invalidates. Existing historical care/attribution remains unchanged. An approved superseding credential becomes effective only under its own decision and dates; renewal does not extend the predecessor in place.

### Overrides

Candidate 1 defines **no readiness or eligibility override** and no break-glass clinical eligibility. Warning acknowledgement cannot convert a blocker. Tenant isolation, identity conflict, working age, hierarchy, registration/credential expiry, clean evidence, clinical scope, supervision, authorization, maker/checker, registry/schema, document integrity, and lifecycle gates are non-overrideable.

## Verification and acceptance

- Deterministic tests cover every common/clinical/non-clinical outcome, exact expiry boundary, deep link, evidence minimization, and stable remediation code.
- Integration tests cover invalidation in the source transaction, changed state during evaluation/approval/activation, stale baseline, clock/freshness boundaries, provider outage, concurrent run/activation, replay, and rollback.
- Eligibility tests enumerate profession/service/context matrices, restrictions, supervision, effective ranges, renewal/supersession, historical point-in-time results, and fail-closed indeterminate cases.
- Authorization/UI tests prove actor separation, subject restrictions, MFA/RA, hidden tenant/resource behavior, route-state recovery, and that M2-01/M2-20/M2-21/M2-25 display rather than calculate results.

## Approval boundary

The gate catalogues, timing values, expiry horizon, eligibility logic, and no-override choice are candidates requiring workforce, credentialing, clinical governance, privacy, security, operations, and product approval. No workforce member or practitioner may be production-activated from this package alone.
