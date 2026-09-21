# Module 2 authorization policy candidate

**Artifact kind:** `authorization-policy`  
**Status:** `CANDIDATE_FOR_APPROVAL`  
**Candidate version:** `m2-candidate-1`  
**Approval:** Not granted

## Decision baseline

Module 2 authorization is deny-by-default and extends—not replaces—the canonical M1 migration-owned RBAC source of truth accepted with the Module 1 baseline. Authorization evaluates one exact active operation, permission, interactive/service role, organization membership, resource scope, subject relationship, lifecycle, policy/registry version, purpose, MFA/recent-authentication state, delegation ceiling, and maker/checker rule inside the same forced-RLS tenant transaction as the work. UI visibility and organization selection are never authority.

Person, workforce, practitioner, clinical scope, organization assignment, service eligibility, application access, and account linkage are independent. Holding a regulated registration or approved clinical scope grants no login or application permission. Holding an application role grants no clinical eligibility.

### Canonical interactive roles

Existing M1 roles keep their approved meaning. Candidate 1 adds the following canonical entries to the same role registry; it creates no workforce role table.

| Role | Candidate purpose / boundary | Delegation |
| --- | --- | --- |
| `organization_owner` | Existing final-owner role; may administer M2 subject to self-target, MC, clinical qualification, purpose, and scope restrictions. | Existing M1 edges plus may delegate every non-owner M2 role organization-wide. Cannot alone promote/demote owner. |
| `organization_administrator` | Organization-wide workforce directory/profile/onboarding/assignment administration; no credential/scope decision merely by role. | May delegate workforce/HR/facility administrator and viewer/support roles within its own ceiling; not credentialing or clinical approval. |
| `workforce_administrator` | Clinical/non-clinical onboarding, member/engagement/assignment/availability/lifecycle orchestration and readiness submission. | May delegate practitioner/clinical-support access through governed M1 access flow only when explicitly granted and within scope. |
| `hr_administrator` | Personal/contact and engagement records, non-clinical onboarding, transfers/offboarding. Restricted from credential document content, scope decisions, and audit detail. | None by default. |
| `facility_administrator` | Facility-scoped member directory, assignments, availability, and non-clinical readiness. Cannot see home/private data or decide credentials/scope. | None; scope is facility and optional descendants. |
| `credentialing_officer` | Qualification/registration/credential intake, clean evidence review, independent verification, expiry follow-up. Cannot approve clinical scope unless separately holding the approver role and not the maker. | None. |
| `clinical_governance_approver` | Scope definition/read, submitted scope decision, clinical suspension/reactivation, and relevant eligibility evidence. No HR/private contact or access-administration authority. | None. |
| `practitioner` | Minimum-necessary self-profile, own credential submission, own availability, and own active assignment/scope projection. Cannot verify/approve self or assign application roles. | None. |
| `clinical_support_staff` | Minimum operational self-profile/assignment/availability and explicitly granted future care operations; no regulated-scope inference. | None. |
| `security_administrator` | Existing M1 invitation/account/access operations; may link an approved member to an account after identity proof. No workforce/clinical data beyond minimum target projection. | Existing M1 ceiling only. |
| `auditor` | Purpose-bound minimum-necessary M2 configuration/audit evidence after assurance. No business mutation. | None. |
| `export_approver` | Independent restricted-export decision; cannot request and approve the same export. | None. |
| `organization_viewer` | Active, non-restricted directory/readiness summary only. | None. |

Role combinations do not remove separation. The same human cannot act as maker and checker on one credential, scope, activation, restricted export, registry, merge, access, suspension, or offboarding decision even when holding both permissions.

### Exact permission catalogue

| Family | Exact candidate keys |
| --- | --- |
| Dashboard/directory | `workforce.dashboard.read`, `workforce.directory.read`, `workforce.member.read` |
| Onboarding/person | `workforce.member.create`, `workforce.member.manage`, `workforce.person.match`, `workforce.person.restricted_read`, `workforce.person.correct`, `workforce.person.merge.request`, `workforce.person.merge.approve`, `workforce.person.merge.execute` |
| Engagement | `workforce.engagement.read`, `workforce.engagement.manage`, `workforce.engagement.lifecycle` |
| Practitioner | `workforce.practitioner.read`, `workforce.practitioner.manage`, `workforce.practitioner.lifecycle` |
| Qualifications/registrations | `credential.qualification.read`, `credential.qualification.manage`, `credential.registration.read`, `credential.registration.manage`, `credential.registration.lifecycle` |
| Credential/evidence | `credential.record.read`, `credential.record.manage`, `credential.document.upload`, `credential.document.read`, `credential.review.queue`, `credential.review.decide`, `credential.lifecycle` |
| Specialty/scope | `practitioner.specialty.read`, `practitioner.specialty.manage`, `practitioner.scope.read`, `practitioner.scope.manage`, `practitioner.scope.submit`, `practitioner.scope.approve`, `practitioner.scope.lifecycle` |
| Assignments/services | `workforce.assignment.read`, `workforce.assignment.manage`, `workforce.assignment.lifecycle`, `practitioner.service_assignment.read`, `practitioner.service_assignment.manage`, `practitioner.service_assignment.lifecycle`, `practitioner.eligibility.read` |
| Availability | `workforce.availability.read`, `workforce.availability.manage` |
| Access/account | Existing M1 `access.membership.*`, `access.invitation.*`, owner-transfer and MFA-reset permissions; candidate adds `workforce.account_link.read`, `workforce.account_link.request` only. These keys cannot grant roles directly. |
| Activation/lifecycle | `workforce.readiness.read`, `workforce.validation.run`, `workforce.activation.submit`, `workforce.activation.approve`, `workforce.activation.execute`, `workforce.lifecycle.suspend`, `workforce.lifecycle.reactivate`, `workforce.offboarding.request`, `workforce.offboarding.approve`, `workforce.offboarding.execute` |
| Governance | `workforce.expiry.read`, `workforce.expiry.escalate`, `workforce.history.read`, `workforce.audit.read`, `workforce.export.request`, `workforce.export.approve`, `workforce.export.access`, `workforce.registry.read`, `workforce.registry.manage`, `workforce.registry.approve`, `workforce.registry.activate`, `workforce.timeline.read` |

### Baseline grants

`O` means organization scope; `F` facility scope and permitted descendants; `S` self only. A row is still constrained by operation policy and field projection.

| Role | Baseline grants |
| --- | --- |
| Owner | All candidate keys at `O`, except credential/scope decisions require the same competence/qualification configured for those operations and all separation rules still apply. |
| Organization administrator | Dashboard/directory/member/onboarding/engagement/assignment/availability/account-link/readiness/history/registry-read at `O`; activation submit but not approve/execute by default. |
| Workforce administrator | Dashboard/directory/member/onboarding/engagement/practitioner draft/assignment/service-assignment/availability/readiness/submit/lifecycle request/timeline at assigned scope; no credential review or scope approval. |
| HR administrator | Directory, restricted person, member, engagement, non-clinical assignment, availability, lifecycle/offboarding request at assigned scope; no clinical evidence/scope/audit/export. |
| Facility administrator | Dashboard/directory/member summary/assignment/availability/readiness at `F`; no private person, credential document, clinical scope decision, role assignment, or organization-wide export. |
| Credentialing officer | Credential/qualification/registration read/manage/upload/review/lifecycle plus minimum member/practitioner/assignment context at assigned scope; no access or scope approval. |
| Clinical governance approver | Practitioner/specialty/scope/eligibility read and scope approve/lifecycle plus clean verified-credential summary; document bytes only when separately granted for the exact review purpose. |
| Practitioner | Own member/practitioner/credential submission/scope projection/assignment/availability/timeline at `S`; no reviewer/approval/lifecycle keys. |
| Clinical support staff | Own member/assignment/availability/timeline at `S`. |
| Auditor | History/audit/timeline read at `O` after purpose/assurance; source-field redaction still applies. |
| Export approver | Restricted export approval evidence only; no source browse permission inferred. |
| Viewer | Dashboard/directory/member summary/readiness at its assigned scope, excluding private fields. |

All grants are explicit migration rows. Wildcards, role-name inference, client-provided permissions, unknown registry versions, retired entries, missing resource scopes, or an absent active policy deny.

### Operation assurance and denial

| Operation group | Denial | Required controls |
| --- | --- | --- |
| Dashboard/directory/minimum profile | Hidden `404` for tenant/resource; explicit unsupported filter `400` | Current membership, exact read permission/scope, minimum projection, signed cursor. No RA. |
| Restricted person/contact detail | Hidden `404` | Explicit restricted-read purpose; RA <= 10m; sensitive detail access audited. |
| Draft onboarding/engagement/assignment/availability | Hidden resource then explicit action `403` | Permission/scope, expected revision, idempotency, reason where correcting/effective change; overlap/hierarchy checks. |
| Match/create/merge | Hidden candidates | Purpose, fresh match digest; merge request MFA + RA <= 5m and MC; no cross-organization query. |
| Credential upload | Hidden credential | Upload permission, purpose/type/size allowlist, idempotency; private quarantine and fail-closed scanning. No document read grant. |
| Credential evidence preview/review | Hidden `404` | Clean evidence, explicit document-read/review purpose; MFA + RA <= 10m; uploader/submitter/subject separation; access audited. |
| Credential verification/lifecycle | Explicit decision denial after safe context | MFA + RA <= 5m, exact revision/evidence digest, independent reviewer, reason, impact and current authority evidence. |
| Scope submit/decision/lifecycle | Hidden scope then explicit action denial | Submitter/checker separation; approver competence/scope; MFA + RA <= 5m; exact requirement/result digest and reason. |
| Service assignment/eligibility | Hidden parent | Exact context and point-in-time server eligibility; expected revision/idempotency. No client eligibility claim. |
| Account link/invitation/access | Existing M1 hidden target policy | Existing M1 delegation/final-owner/MFA/RA/MC operations. Workforce account-link request does not itself mutate access. |
| Activation/suspend/reactivate/offboard | Hidden member then explicit action denial | MFA + RA <= 5m, reason, exact readiness/impact digest, MC where specified, subject separation, idempotency. |
| Audit/timeline restricted detail | Hidden `404` | Purpose, MFA + RA <= 10m, field permission; access itself audited. |
| Export request/access | Hidden `404` | Purpose/legal basis/reason, MFA + RA <= 5m; restricted projection MC; access grant <= 10m. |
| Controlled-registry decision/activation | Explicit action denial | Exact allowed category, impact/result digest, MC, MFA + RA <= 5m; active versions immutable. |

### Self-service, subject access, and field policy

- `practitioner` and `clinical_support_staff` self projection is based on the authenticated user-to-workforce link and active organization membership, never a client-provided member ID. It omits verification notes, protected reasons, match keys, HR-only engagement fields, other candidates, and access policy internals.
- A subject may upload evidence and request correction but cannot mark it clean, verified, approved, active, or deleted. A subject cannot view internal reviewer notes unless a separately approved data-access/correction process authorizes them; that legal workflow is outside ordinary M2 operations.
- Directory projections expose display name, member number, pathway, profession where relevant, work assignment, lifecycle, and safe readiness/expiry indicators. Birth date, home address, personal contact, full identifier/registration numbers, document metadata, scope restrictions, and account security are excluded.
- Facility scope is a narrowing constraint. An organization-wide operation may not be inferred from a facility grant, and a workforce assignment does not itself create an access scope.

### Service identities and emergency access

| Identity | Exact purpose |
| --- | --- |
| `m2-credential-scan-coordinator-v1` | Consume tenant-bound credential-document scan work and bind platform scan evidence; cannot decide credentials or read unrelated documents. |
| `m2-eligibility-evaluator-v1` | Recalculate exact practitioner/context eligibility from authoritative references; cannot mutate source records or grant access. |
| `m2-expiry-scheduler-v1` | Create mutually exclusive expiry milestones and notification work; cannot decide or renew credentials. |
| `m2-notification-worker-v1` | Deliver an authorized minimum-necessary template to an opaque recipient reference; cannot browse members. |
| `m2-offboarding-worker-v1` | Execute only an approved, due, tenant-bound offboarding plan using exact child operation grants. |
| `m2-export-worker-v1` / `m2-retention-worker-v1` | Generate authorized workforce export and dispose expired artifacts under separate grants. |
| `m2-outbox-publisher-v1` | Publish only allow-listed M2 event versions to configured destinations; no business mutation. |

Service identities are non-interactive, tenant-discovery disabled, purpose-specific, credential-rotated, and cannot approve or use browser sessions. Candidate 1 defines no general break-glass role. Urgent suspension uses the ordinary explicit operation and assurance; production support elevation requires a future separately approved, time-limited incident policy and cannot bypass RLS, evidence, or self-decision rules.

## Verification and acceptance

- Migration/registry tests assert exact roles, permissions, grants, scopes, delegations, operations, assurance windows, denial modes, service identities, policy version, and disabled-by-default candidate release.
- Authorization attacks cover wildcard/role-name inference, stale membership, forged workforce link, self ID, facility-to-organization escalation, cross-tenant person/document IDs, self-review, maker/checker collision, role combination, final-owner bypass, expired assurance, service-identity crossover, and direct SQL.
- Every action on M2-01 through M2-29 maps to one exact operation and server-projected availability; hidden/disabled behavior matches this policy without trusting the browser.
- Field-policy tests compare each role/scope projection and prove restricted accesses create the required purpose-bound evidence.

## Approval boundary

This catalogue is a candidate additive release. It activates no role, permission, grant, operation, service identity, or endpoint. Workforce, clinical governance, security, privacy, and product authorities must approve its exact digest before migration-owned production activation.
