# Module 3 lifecycle and transition matrix candidate

**Artifact kind:** `lifecycle-and-transition-matrix`  
**Status:** `CANDIDATE_FOR_APPROVAL`  
**Candidate version:** `m3-candidate-1`  
**Approval:** Not granted

## Decision baseline

Every transition binds organization, actor, purpose, exact operation, source/target state, resource revision, effective time, reason, idempotency, assurance, policy versions, maker/checker/executor separation where required, database invariant, audit/outbox version and the complete user-visible response. UI controls and queued work are never authority.

There is no automatic patient merge, routine unmerge, inferred proxy authority, retroactive consent withdrawal or physical deletion of effective/referenced identity history. There is **no M3 break-glass**.

### Registration run

| From                               | Operation / permission                                 | To                 | Required checks and evidence                                                                       |
| ---------------------------------- | ------------------------------------------------------ | ------------------ | -------------------------------------------------------------------------------------------------- |
| none                               | start / `patient.registration.start`                   | `collecting`       | Source, supplier relationship, purpose, optional facility, idempotency; 24-hour inactivity expiry. |
| `collecting`                       | save / creator or `patient.registration.manage`        | `collecting`       | Strong revision, field validation, provenance; no completion claim.                                |
| `collecting`                       | search / `patient.duplicate.search`                    | `duplicate_review` | Organization-scoped bounded factors, approved detector, safe explanations, search evidence.        |
| `duplicate_review`                 | select existing / allowed registration action          | `ready`            | Exact patient/candidate revisions, permission to read selected projection, disposition/reason.     |
| `duplicate_review`                 | continue new / independent review when required        | `ready`            | No unresolved confirmed identifier conflict; exact exception authority; no auto-merge.             |
| `collecting` or `duplicate_review` | urgent temporary identity / governed urgent permission | `ready`            | Urgent-care reason, minimum safe identity, visible temporary state, immediate reconciliation task. |
| `ready`                            | validate / `patient.registration.manage`               | `ready`            | All applicable sections, duplicate disposition, policy gates and immutable validation digest.      |
| `ready`                            | submit / `patient.registration.submit`                 | `submitted`        | Fresh validation, exact revision/digest, idempotency, purpose and current permissions.             |
| `submitted`                        | atomic register / constrained application operation    | `completed`        | Patient/link created once, all effective children/evidence/audit/outbox commit together.           |
| nonterminal                        | expire/abandon                                         | `abandoned`        | Server time, expiry/inactivity, safe staging retention; cannot reopen.                             |
| nonterminal                        | reject                                                 | `rejected`         | Authorized reason/evidence; cannot reopen.                                                         |

Registration failure leaves no partially active patient. Portal invitation is always a later separate action.

### Patient profile

| From                                       | Operation                          | To                 | Rule                                                                                                      |
| ------------------------------------------ | ---------------------------------- | ------------------ | --------------------------------------------------------------------------------------------------------- |
| none                                       | create draft through registration  | `draft`            | Internal identifier, provenance and name or explicit temporary/unnamed state.                             |
| `draft`                                    | submit review when policy requires | `pending_review`   | Exact revision, validation and duplicate disposition.                                                     |
| `draft`/`pending_review`                   | activate                           | `active`           | No unresolved blocking identity conflict; approved required catalogues.                                   |
| `active`                                   | mark inactive                      | `inactive`         | Operational reason/effective time; history/access retained; not deceased.                                 |
| `inactive`                                 | reactivate                         | `active`           | Current identity review and reason; stale conflicts rejected.                                             |
| `active`/`inactive`                        | record verified death              | `deceased`         | Source/verification and date precision/certainty; revoke/adjust routine portal/communications per policy. |
| non-merged                                 | merge execution                    | `merged`           | Exact accepted merge decision; one acyclic survivor; redirect future reads.                               |
| `draft`/`pending_review`/eligible `active` | enter in error                     | `entered_in_error` | Record never represented intended patient; attributed care requires correction/disentanglement instead.   |

Identity and demographic changes append provenance-aware effective versions. A correction does not erase the supplied value or its historical use.

### Identifier, contact, address and preference

- Identifier: `draft -> active -> ended | superseded | revoked | entered_in_error`. Activation validates exact scheme/version/issuer/uniqueness. Correction creates a successor; confirmed collision routes to duplicate review.
- Contact/address: `draft -> active -> ended | superseded | entered_in_error`. Primary/preferred changes serialize and retain effective history. Verification expiry changes assurance, not the historical value.
- Preference: `draft -> active -> ended | superseded | entered_in_error`. It never changes consent/legal authority or provider delivery truth.

### Relationship, proxy authority and portal link

| Aggregate                   | States                                             | Transition rules |
| --------------------------- | -------------------------------------------------- | ---------------- |
| Related-person relationship | `active -> ended                                   | corrected`       | Relationship/provenance only; no access consequence by itself. |
| Authority grant             | `proposed -> pending_review -> active -> suspended | revoked          | expired                                                        | superseded`                                                                                                                                                                | Known local-law policy, grantor/grantee, exact scope/evidence/time; independent checker where catalogue requires. Ambiguous authority fails closed. |
| Portal link                 | `proposed -> proofing -> active -> revoked         | expired          | compromised`                                                   | One patient/user/self proof or active proxy authority; one-use invitation; MFA; recovery of equivalent strength. Revocation/authority expiry ends new access and sessions. |

Relationship, authority and portal link never share a state column or implicit cascade that hides which fact changed. Emergency proxy access is unavailable.

### Consent and privacy

| Aggregate             | States                                | High-impact behavior |
| --------------------- | ------------------------------------- | -------------------- |
| Consent               | `draft -> active -> withdrawn         | expired              | superseded         | entered_in_error`                                                                                                                                          | Activation binds directive/derivative, grantor authority, policy/purpose/action/data/actor/time. Withdrawal is prospective, invalidates future consent-dependent use and retains prior lawful evidence. |
| Restriction request   | `proposed -> under_review -> accepted | rejected             | changes_requested` | Qualified privacy decision and reason; request alone does not silently change projections.                                                                 |
| Effective restriction | `active -> ended                      | superseded           | entered_in_error`  | Server projection enforcement across patient, proxy, portal, message, export and FHIR. Source truth remains intact. No free-text exception or break-glass. |

Unknown/ambiguous policy version, purpose, actor, action, data class or conflict routes to privacy review and denies the dependent activity.

### Safety flag

| From                     | Operation                  | To                                 | Rule                                                                                                            |
| ------------------------ | -------------------------- | ---------------------------------- | --------------------------------------------------------------------------------------------------------------- |
| none                     | propose                    | `proposed`                         | Approved category, concise code, severity, source-detail reference, author eligibility and proposed visibility. |
| `proposed`               | urgent provisional display | `provisional`                      | Critical/high policy permits; visibly unverified; verification deadline starts.                                 |
| `proposed`/`provisional` | verify                     | `active`                           | Eligible verifier; high severity author/checker separation; exact revision/source.                              |
| `active`                 | acknowledge                | `active`                           | Actor/workflow/time evidence; acknowledgement does not resolve the fact.                                        |
| `active`                 | resolve                    | `resolved`                         | Reason, clinical eligibility and effective time; history remains.                                               |
| nonterminal              | correct                    | `superseded` or `entered_in_error` | Linked successor or error evidence; never silent edit/delete.                                                   |

Critical verification has a proposed ceiling of four hours, high one business day and standard five business days, subject to clinical-owner acceptance/tightening. Missing catalogue keeps the capability unavailable.

### Duplicate candidate and queue lease

Candidate lifecycle is `open -> under_review -> dismissed | merge_requested | resolved`. Claiming creates a short opaque lease bound to reviewer and revision; lease expiry returns the candidate to the queue without changing evidence. A dismissal binds detector/version, factor digest, both patient revisions and reason. Material source or detector change reopens or creates a fresh candidate.

`confirmed_identifier_conflict` blocks ordinary new-record completion; `high_review` and `possible_review` route according to approved queue policy. `below_display_threshold` is not presented as proof of uniqueness. Every operating day has queue triage; urgent overlay risk receives immediate assignment.

### Merge request, decision and execution

1. Maker with `patient.merge.request` selects survivor/duplicate, explicitly dispositions fields, requests a current downstream reference inventory and submits exact patient revisions plus impact digest.
2. Request moves `draft -> submitted`. Any source revision, impact or policy change makes it stale; it is never refreshed silently.
3. Distinct MFA/recent-auth checker with `patient.merge.decide` compares minimum-necessary provenance and approves or rejects. Checker cannot be maker; decision is immutable and expires.
4. Constrained executor with only `patient.merge.execute` consumes the exact approval once. It locks both aggregates in stable order, rechecks tenant, permissions, revisions, digest, no cycles/targets and every affected reference.
5. Execution atomically moves request `approved -> executed`, marks duplicate `merged`, sets the survivor link, preserves all identifiers/provenance/attribution, updates permitted references and writes audit/outbox/invalidation evidence.
6. Failure leaves both profiles and references unchanged. There is no partial merge or retry with changed content under the old idempotency key.
7. Mistake remediation is a new governed correction/disentanglement case with health-information and affected clinical review. It is not `executed -> draft` and not a deletion-based unmerge.

FHIR output maps lineage to the selected profile's `Patient.link` `replaced-by`/`replaces` semantics; it does not make FHIR the merge authority.

### Communication, export and FHIR work

- Notification: `planned -> authorized -> queued -> delivered | failed | dead_lettered | suppressed`. Queueing is not contact authority and provider acceptance is not clinical delivery/escalation.
- Export: `requested -> pending_approval | authorized -> running -> ready -> expired -> disposed`, terminal `rejected | failed`. Restricted/bulk requester and checker differ; access grants are short-lived and separately audited.
- FHIR exchange: `received -> validated -> authorized -> applied | rejected | failed` or `requested -> authorized -> generated -> delivered | failed`. Unknown profile/terminology/tenant/purpose denies before application/delivery.

### Transition assurance matrix

| Transition class                   | MFA / recent auth                                       | Separation                                                     | Idempotency / revision                                      |
| ---------------------------------- | ------------------------------------------------------- | -------------------------------------------------------------- | ----------------------------------------------------------- |
| Ordinary draft edits               | Current session; step-up only for raw restricted reveal | None unless policy says                                        | Strong revision; idempotency for create/save batch.         |
| Authority/consent/privacy decision | MFA and RA <= 10 minutes                                | Maker/checker where local catalogue requires; no self-decision | Exact request/policy/evidence digest.                       |
| Critical safety verification       | MFA and RA <= 10 minutes                                | High severity author != verifier                               | Exact flag/source revision.                                 |
| Merge request/decision/execution   | MFA and RA <= 5 minutes for decision/execution          | maker != checker; constrained executor; subject-independent    | Exact pair/revisions/impact/decision; one-use execution.    |
| Portal link/recovery/revocation    | MFA and RA <= 5 minutes                                 | Proofing agent cannot bypass evidence policy                   | One-use link/recovery evidence; current authority revision. |
| Restricted/bulk export             | MFA and RA <= 5 minutes                                 | requester != checker                                           | Immutable snapshot/projection/filter digest.                |

## Verification and acceptance

- State-machine unit/property tests cover every allowed and forbidden edge, terminal-state behavior, time boundaries, replay, stale revisions and unavailable policy.
- PostgreSQL tests attack direct state changes, self-approval, decision reuse, pair reversal, cycles, concurrent merge execution, tenant crossing, overlap and immutable evidence.
- HTTP tests prove permissions, purpose, assurance, separation, idempotent replay/conflict and exact RFC 9457 failures without side effects.
- Worker tests prove authority re-evaluation, transactional inbox/outbox, lease expiry, retry/dead-letter bounds and no queued-work authority.

## Approval boundary

These lifecycles and assurance limits are candidate decisions. The exact package digest requires separate acceptance before any production state, transition guard, permission, event, worker or route is created.
