# Module 2 lifecycle and transition matrix candidate

**Artifact kind:** `lifecycle-and-transition-matrix`  
**Status:** `CANDIDATE_FOR_APPROVAL`  
**Candidate version:** `m2-candidate-1`  
**Approval:** Not granted

## Decision baseline

Every Module 2 transition is a server-owned governed operation. The operation locks and reauthorizes the tenant, actor, permission, scope, subject, assurance, and exact current revision in the same transaction; validates the transition and effective impact; consumes one scoped idempotency key; and commits business state, decision/evidence, audit, and required outbox atomically. Unknown transitions and partial cascades fail closed.

`RA` means recent primary authentication, `MFA` a fresh factor assertion, and `MC` distinct human maker/checker. Effective ranges are `[from,to)`. Completed decisions and historical versions are immutable.

### State models

| Aggregate | Candidate states | Terminal/history rule |
| --- | --- | --- |
| Match/onboarding case | `started, matching, match_decided, drafting, ready, submitted, activated, cancelled` | Submitted/activated match and pathway evidence is immutable; cancellation retains reason. |
| Person merge request | `draft, submitted, approved, rejected, executed, cancelled` | `executed/rejected/cancelled` terminal; never automatic or cross-organization. |
| Workforce member | `draft, submitted, active, suspended, offboarding, offboarded` | `offboarded` terminal for the member; rehire adds an engagement and a governed reactivation/new member decision per policy, never erases history. |
| Engagement | `draft, scheduled, active, suspended, ended, cancelled` | End/cancel terminal; rehire creates a new engagement. |
| Practitioner profile | `draft, active, suspended, ended` | End terminal; profile state does not mutate access. |
| Qualification | `draft, submitted, verified, rejected, returned_for_correction, superseded` | Verified/rejected/superseded immutable; correction or renewal creates a version. |
| Registration | `draft, evidence_pending, submitted, verified, suspended, revoked, expired, superseded` | Revoked/expired/superseded immutable; renewal supersedes. |
| Credential | `draft, evidence_pending, scanning, submitted, in_review, verified, rejected, more_information_required, returned_for_correction, suspended, revoked, expired, superseded` | Decisions append; verified content immutable; renewal supersedes. |
| Credential document | `intent_created, uploading, quarantined, scanning, clean, infected, invalid, failed, disposed` | Only `clean` is reviewable/promotable; infected/invalid/failed never preview; disposal retains proof. |
| Scope of practice | `draft, submitted, in_review, approved, rejected, changes_requested, suspended, superseded, ended` | Approved content immutable; change creates a superseding version. End/supersede terminal. |
| Workforce assignment | `draft, scheduled, active, suspended, ended, cancelled` | End/cancel terminal; transfer creates a successor and ends/schedules predecessor atomically. |
| Practitioner service assignment | `draft, scheduled, active, suspended, ended, cancelled` | End/cancel terminal; eligibility loss suspends/ends prospectively without rewriting historical care. |
| Availability profile | `draft, scheduled, active, superseded, cancelled` | Atomic batch; active content is immutable and replaced by a version. |
| Access grant request | Uses existing M1 governed access request/approval/execution states | The canonical M1 membership/RBAC records remain authoritative; workforce tables only narrow scope/link evidence. |
| Readiness run | `requested, running, complete, failed, expired, invalidated` | Results immutable; change/timeout creates invalidation evidence and a new run. |
| Activation request | `draft, submitted, approved, rejected, expired, invalidated, activated` | Decision bound to exact result digest; only `activated` mutates member lifecycle. |
| Offboarding request | `draft, impact_reviewed, submitted, approved, scheduled, executing, completed, failed, cancelled` | Completed immutable; failed remains reviewable and resumes only through an authorized retry/reconciliation record. |
| Registry change | `draft, validating, ready, submitted, approved, rejected, active, superseded, cancelled` | Active versions immutable; canonical RBAC is outside this lifecycle. |
| Notification delivery | `planned, queued, sending, delivered, failed, suppressed, cancelled` | Attempts append; delivery does not prove receipt or credential compliance. |
| Export | `requested, authorized, running, ready, failed, expired, disposed` | Failure terminal; retry is linked new job. Expired/disposed cannot regain access. |

### Onboarding and identity transitions

| Transition | Guards and assurance | Atomic result / compensation |
| --- | --- | --- |
| Start clinical/non-clinical pathway | `workforce.member.create`, explicit pathway, organization active, idempotency. | Creates onboarding case only; no person, account, access, or practitioner authority yet. |
| `started -> matching` | Minimum identity fields, purpose, privacy notice version. | Server computes organization-scoped candidates and records match-run digest; no global results. |
| Decide `use_existing` | Candidate still visible/eligible, exact match-run digest, reason when weak/ambiguous, RA <= 10m for restricted details. | Links the authorized organization person; no merge or overwrite. |
| Decide `create_new` | Fresh run, no exact blocker, explicit possible-match dispositions, reason. | Creates person plus organization link and match keys atomically. Later duplicate correction uses merge request. |
| Submit merge | Same organization, two accessible links, retained/discarded choice, impact digest, reason, MFA + RA <= 5m. | Creates request; no identity mutation. |
| Approve/execute merge | MC; checker differs from requester and affected account where applicable; fresh impact. | Organization links/references consolidate transactionally, history preserved; failure leaves both intact. |
| Save personal/contact proposal | Exact member revision, field validation, restricted-field permission, reason for correction. | Person and link changes plus provenance/evidence commit; access/account unchanged. |
| Create/schedule engagement | Valid type/range/manager, no prohibited overlap, manager is eligible organization member, expected revision. | Engagement version and evidence commit. Rehire is new engagement. |

### Credentialing transitions

| Transition | Guards and assurance | Atomic result / compensation |
| --- | --- | --- |
| Create practitioner profile | Clinical pathway, profession registry active, no conflicting current profile. | Draft only; no eligibility/access. |
| Qualification `draft -> submitted` | Required metadata and evidence references; exact revision. | Submission locks reviewed fields; return creates correction version. |
| Registration create/renew | Active regulator/type versions, normalized uniqueness, dates coherent, evidence purpose/retention. | Renewal creates linked successor; predecessor remains current until effective decision. |
| Create upload intent | Credential edit permission, declared type/size/MIME/extension allowlist, tenant/purpose, idempotency. | Opaque one-use intent; no provider key disclosed. |
| Upload `-> quarantined -> scanning` | Byte count and SHA-256 match; private quarantine; exact scanner work record. | Scan is asynchronous/idempotent. Timeout, unavailable, or stale definitions fail closed. |
| Scan `-> clean` | Accepted scanner/version/signature freshness and independent stream digest. | Platform promotion/evidence commits before credential document becomes reviewable. |
| Scan `-> infected/invalid/failed` | Exact attestation/failure code. | Isolate/cleanup per platform policy, visible safe status, security evidence; never preview. |
| Credential `submitted -> in_review` | All required evidence clean, exact revision/digest, no terminal predecessor conflict. | Queue item becomes claimable; claim lease is not a decision. |
| Review `-> verified/rejected/more_information_required/returned_for_correction` | Reviewer has permission/scope, is not uploader/submitter/subject, MFA + RA <= 10m, evidence still clean/current, exact digest, reason. | Immutable verification plus credential state, audit/outbox, eligibility invalidation. More-information/correction returns through a new revision. |
| Verified `-> suspended/revoked/expired` | Authorized authority evidence or deterministic expiry, impact preview, reason; manual suspend/revoke MFA + RA <= 5m. | Prospective eligibility invalidated; past care/evidence unchanged. Renewal/supersession is forward-only. |

### Scope, assignment, availability, and access transitions

| Transition | Guards and assurance | Atomic result / compensation |
| --- | --- | --- |
| Scope `draft -> submitted` | Exact active definition/version, requirements result, proposed activities/restrictions/range, maker reason. | Immutable submission digest; changes invalidate and require resubmission. |
| Scope `submitted -> in_review -> approved/rejected/changes_requested` | Clinical governance approver, MC, MFA + RA <= 5m, all required registration/credential evidence verified/current, exact digest. | Decision and eligibility invalidation commit. Approval cannot grant application access or an assignment. |
| Scope `approved -> suspended/ended/superseded` | Clinical governance permission, impact preview, reason, MFA + RA <= 5m; successor rules for supersession. | Prospective service eligibility recalculates; historical attribution retained. |
| Assignment `draft/scheduled -> active` | Eligible M1 hierarchy for entire range, engagement coverage, no prohibited overlap, exact revision. | Assignment only; no role or clinical scope is inferred. |
| Transfer assignment | Source active/scheduled, successor context/range eligible, fresh downstream impact, reason, idempotency. | End/schedule predecessor and create successor in one transaction. No gap/overlap unless registry explicitly permits. |
| Assignment suspend/reactivate/end | Impact preview, reason, hierarchy/engagement checks; MFA + RA <= 10m for urgent suspension. | State/effective range/evidence and eligibility invalidation commit. |
| Service assignment activate | Active practitioner, registration, credentials, approved scope, organization assignment and M1 service/context all cover instant/range; supervision requirement resolved. | Stores exact eligibility-evidence digest. Access remains separate. |
| Service eligibility loss | Authoritative dependency change/expiry event, server reevaluation. | Future eligibility and affected service assignment become blocked/suspended according to policy; no historical rewrite. |
| Save weekly availability | Entire seven-day profile, timezone, intervals/exceptions, no overlap/DST ambiguity, expected profile revision. | One parent version and all children commit atomically; partial day saves prohibited. |
| Request/approve/execute access | Existing M1 canonical role, permission, delegation, final-owner, target separation, MFA/RA/MC policy. Workforce scope can only narrow the grant. | Canonical M1 membership/access mutation plus link evidence. No duplicate role tables. |
| Link account/invite | Explicit existing-user match or one-use invitation; target consent/acceptance; role delegation policy; no automatic link from member creation. | Link/membership occurs only through the M1 identity flow. Decline/expiry leaves member valid without login. |

### Activation, suspension, reactivation, and offboarding

| Transition | Guards and assurance | Atomic result / compensation |
| --- | --- | --- |
| Run readiness | Exact member/pathway/configuration revisions and gate versions; serialized per member. | Immutable results/digest, fresh 15 minutes. Evaluation failure produces no ready result. |
| `ready -> submitted` | Zero blockers, warning acknowledgement, fresh result, maker permission/reason/idempotency. | Activation request bound to exact digest/revision. |
| `submitted -> approved/rejected` | Distinct checker, activation approval permission, MFA + RA <= 5m, result still fresh/current. | Immutable decision; approval expires after 30 minutes and never outlives readiness. |
| `approved -> active` | Activator not maker, exact digest/revision, live authorization, all gates/dependencies rechecked. | Member activation, lifecycle evidence, idempotency, audit/outbox commit atomically. Prior draft remains on failure. |
| `active -> suspended` | Suspension category/reason, effective time, impact preview, workforce lifecycle permission, MFA + RA <= 5m; clinical suspension also requires clinical-governance authority. | Member/practitioner and prospective eligibility state changes are coordinated; access is suspended only by an explicit approved access action, never silently. |
| `suspended -> active` | Cause resolved with evidence, fresh readiness, distinct approval where suspension was clinical/security/credential-related, MFA + RA <= 5m. | Governed reactivation; no deletion of suspension evidence. |
| Start offboarding | Active/suspended member, engagement end/effective time, category/reason, server impact across assignments/services/access/account/jobs. | Draft request plus exact impact digest. No immediate mutation. |
| Approve/schedule offboarding | MC for privileged access, clinical responsibilities, or immediate action; MFA + RA <= 5m; handover/blockers resolved. | Authorized schedule and immutable plan. |
| Execute offboarding | Effective time reached, impact digest or permitted recalculation accepted, service identity exact grant. | Ends engagement/assignments/service assignments, revokes/schedules organization access and sessions through M1 operations, marks member offboarded, preserves attribution; all required state/evidence is atomic or a failed reconciliation record leaves unsafe access blocked. |

### Configuration, notification, and export transitions

| Transition | Guards and assurance | Atomic result / compensation |
| --- | --- | --- |
| Registry draft -> submitted | Allowed category/schema, owner, impact/reference analysis, exact parent snapshot, successful validation <= 15m. | Immutable submission/change digest. |
| Registry submitted -> approved -> active | MC, category-qualified checker, MFA + RA <= 5m, approval <= 30m, no unresolved referenced-value impact. | New version/snapshot activates; prior becomes superseded. No in-place active edit. |
| Plan/send notification | Exact milestone/template/channel consent policy, minimum-necessary recipient reference, deduplication key. | Attempt/delivery evidence. Provider failure does not alter credential/member state. |
| Export requested -> authorized | Purpose/legal basis, bounded projection/filter, MFA + RA <= 5m; restricted detail MC. | Authorization only; no artifact yet. |
| Export authorized -> running -> ready | Exact worker identity, repeatable snapshot, row/size bounds, safe format, private object and digest. | Failure terminal; retry is a linked job. |
| Export ready -> expired -> disposed | Access window 24h, legal-hold check, object deletion proof. | Hold blocks disposal but never extends access. Disposal terminal. |

### Cross-cutting concurrency and recovery

- Every mutation carries `Idempotency-Key`; revisioned mutation also carries a strong `If-Match`. Exact replay returns the original status/body/evidence; changed request under the same actor/tenant/operation/key is `m2.idempotency.conflict`.
- MC actors are distinct active humans. A service identity cannot approve. A subject cannot verify its own credential, approve its own scope, or approve an access/offboarding decision affecting itself. Where staffing cannot satisfy separation, the operation remains blocked; candidate 1 defines no override.
- All impact previews have a digest and short expiry. Execution re-evaluates authoritative state; changed impact fails with a new preview rather than silently broadening the action.
- Jobs establish organization, actor/service, purpose, operation, and correlation context before SQL. Leases do not grant business authority. Duplicate delivery is suppressed by inbox/idempotency; exhausted work enters an owned dead-letter state.
- Database constraints protect tenant links, immutable decisions, append-only evidence, overlaps, predecessor/successor lineage, and exact operation context even if application code is bypassed.

## Verification and acceptance

- State-machine tests enumerate every allowed edge and reject every unlisted edge, terminal mutation, stale revision, changed replay, self-decision, expired decision, changed impact, wrong scope, and cross-tenant/direct-SQL attempt.
- Transaction tests prove atomic match/create, renewal/supersession, review decision, transfer, weekly availability, activation, suspension/reactivation, offboarding, registry activation, audit/outbox, and rollback behavior.
- Worker tests cover duplicate delivery, lease loss, restart, clock boundary, dependency outage, fail-closed scanner, notification deduplication, expiry milestones, dead letter, and governed replay.
- UI/browser acceptance covers assurance challenges, reason/impact dialogs, conflict recovery without lost input, immutable completion evidence, and failure states without optimistic success.

## Approval boundary

This matrix is a candidate workflow policy. No endpoint, database trigger, scheduled worker, or enabled production action may use it until workforce/HR, credentialing, clinical governance, security, privacy, operations, and product authorities approve the exact package digest.
