# Module 3 authorization policy candidate

**Artifact kind:** `authorization-policy`  
**Status:** `CANDIDATE_FOR_APPROVAL`  
**Candidate version:** `m3-candidate-1`  
**Approval:** Not granted

## Decision baseline

M3 reuses the canonical M1 migration-owned users, organization membership, roles, permissions, resource scopes, sessions, MFA, recent-authentication and delegation safeguards. Candidate permission names below reserve exact review vocabulary; this candidate does not register or grant them. Runtime authorization is deny-by-default and runs inside an organization-bound transaction with forced RLS.

Patient ID, identifier knowledge, relationship, portal link, UI visibility, match score, consent row, queue lease, export job and service message are never authority. Every read/mutation evaluates actor/service identity, active organization context, exact permission, resource scope, purpose, patient lifecycle, field policy, proxy authority where applicable, consent/privacy consequence, assurance, revision and current policy versions.

### Candidate permission catalogue

| Family                        | Candidate permissions                                                                                                                                     | Boundary                                                                                                     |
| ----------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------ |
| Registry                      | `patient.dashboard.read`, `patient.directory.read`, `patient.profile.read`, `patient.profile.manage`                                                      | Directory is minimum necessary; manage does not imply identifier, proxy, privacy, safety or merge authority. |
| Registration                  | `patient.registration.start`, `patient.registration.manage`, `patient.registration.submit`                                                                | Creator may resume their unexpired run; submit requires duplicate disposition and fresh validation.          |
| Duplicate/merge               | `patient.duplicate.search`, `patient.duplicate.review`, `patient.merge.request`, `patient.merge.decide`, `patient.merge.execute`                          | Search is bounded; no auto-merge; maker/checker separation; execute is exact constrained authority.          |
| Contact/preference/identifier | `patient.contact.read`, `patient.contact.manage`, `patient.preference.manage`, `patient.identifier.manage`                                                | Raw-value reveal is separate field policy; preference is not consent.                                        |
| Proxy                         | `patient.proxy.read`, `patient.proxy.request`, `patient.proxy.decide`, `patient.proxy.revoke`                                                             | Relationship is not authority; local-law catalogue controls decisions; proxy never gains staff permission.   |
| Consent/privacy               | `patient.consent.read`, `patient.consent.manage`, `patient.privacy.read`, `patient.privacy.manage`                                                        | Purpose/policy-specific; source directive/evidence may require document permission.                          |
| Safety                        | `patient.safety_flag.read`, `patient.safety_flag.propose`, `patient.safety_flag.verify`, `patient.safety_flag.acknowledge`, `patient.safety_flag.resolve` | Category/severity/role eligibility; source clinical detail remains separately protected.                     |
| Timeline/export               | `patient.timeline.read`, `patient.timeline.detail`, `patient.export.request`, `patient.export.decide`, `patient.export.access`                            | Allow-listed projection/purpose only; restricted/bulk requester != checker.                                  |
| Portal operations             | `patient.portal_link.request`, `patient.portal_link.decide`, `patient.portal_link.revoke`, `patient.portal_link.recover`                                  | Staff operation does not itself link; proofing/authority/MFA rules still apply.                              |

Role templates may propose grants only through the separately governed M1 release process. Code cannot authorize by role name, screen ID or permission-string prefix.

### Staff access profiles

| Actor                     | Candidate baseline                                                                                           | Explicit denial                                                                                                              |
| ------------------------- | ------------------------------------------------------------------------------------------------------------ | ---------------------------------------------------------------------------------------------------------------------------- |
| Registration user         | Search, registration staging, permitted demographics/contact/preference, submit; safe duplicate explanations | Merge decision/execution, proxy/legal determination, privacy exception, clinical flag verification, unrestricted raw export. |
| Patient registry reviewer | Assigned duplicate detail, identity correction, merge request/decision according to separation               | Cross-organization lookup, self-approval, unrelated clinical content, portal impersonation.                                  |
| Privacy officer           | Consent/privacy/authority policy decisions and purpose-bound evidence                                        | Altering identity/clinical facts, silent redaction, unrestricted support browse.                                             |
| Eligible clinician        | Patient identity needed for care, approved safety flags, propose/verify/acknowledge within catalogue         | Identifier administration or patient merge solely because care access exists.                                                |
| Auditor                   | Allow-listed timeline/audit projection and accepted export purpose                                           | Mutation, raw secrets/tokens/match keys, unrestricted before/after payload.                                                  |
| Support operator          | Safe technical status/correlation only by default                                                            | Patient content, proxy/portal recovery bypass, break-glass.                                                                  |
| Service identity          | Exact worker operation, tenant record and schema/event version                                               | Interactive permission, wildcard tenant/resource, new authority inferred from queued work.                                   |

### Field projection

| Projection                        | Included                                                                                                                                                                                           | Excluded / additional challenge                                                                                                  |
| --------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------- |
| `patient-directory-summary-v1`    | Synthetic-safe display name, masked local patient number, age band/partial-date-safe display where approved, lifecycle, masked primary contact, safe flag/restriction indicator, projected actions | Full date of birth, raw contact/address/identifier, proxy/consent detail, flag narrative, match factors, cross-tenant existence. |
| `patient-profile-summary-v1`      | Current permitted demographics, masked contacts/identifiers, authority/consent/privacy/safety summaries, provenance/verification labels                                                            | Withheld fields explicitly marked; source evidence and restricted reasons need source permission/purpose.                        |
| `patient-duplicate-comparison-v1` | Only approved matching fields, safe side-by-side provenance, factor categories, conflicts, source revisions                                                                                        | Unrelated clinical record, hidden proxy/contact values, raw HMAC/score internals, other organizations.                           |
| `patient-merge-impact-v1`         | Pair, safe field dispositions, reference family/count, blockers/warnings, revisions/digest                                                                                                         | Raw downstream content, unrestricted actor/audit payload, records outside exact pair.                                            |
| `patient-proxy-summary-v1`        | Related person label, authority type/scope/effective state, verification and portal-link state                                                                                                     | Evidence content, unrelated patient/representative data, actions outside scope.                                                  |
| `patient-timeline-summary-v1`     | Allow-listed event family/title/state/time/actor class/reference/redaction marker                                                                                                                  | Raw audit/outbox, source free text, secrets, contact/identifier/evidence content.                                                |

Field classification and purpose policy are server-side. A response omits or marks withheld data according to policy; the browser never receives a value merely to hide it with CSS. Opening restricted detail creates its own access event.

### Proxy and portal authorization

1. Resolve the authenticated user and active patient or related-person portal link without granting organization membership.
2. For self access, validate current linkage/proofing state, patient lifecycle, purpose/action, consent/privacy and session/MFA policy.
3. For proxy access, independently load current relationship, active authority grant, exact permitted purposes/actions/data classes, effective time, verification and local-law policy. Relationship alone denies.
4. Intersect authority with patient-delegable rights, portal product capability and field restrictions. The narrowest set wins; unknown/ambiguous policy denies.
5. Re-evaluate on every request. Revocation, expiry, merge, compromise or policy change invalidates sessions/tokens/caches.
6. Never grant staff operations, organization membership, identity/merge administration or unrestricted history/export to a proxy.

There is no M3 emergency proxy path and no M3 break-glass. A later emergency design requires separate legal, clinical and security approval.

### Consent and privacy evaluation

- The evaluator receives organization, actor/proxy, patient, purpose, action, data classes, channel, time and policy versions. It first requires a recognized legal-basis rule, then applies any required consent and accepted restrictions.
- Consent-required use with no unambiguous active directive denies. A non-consent legal basis must be explicitly configured; it is never inferred from absence.
- Withdrawal invalidates future dependent access/jobs/caches but does not erase lawful historical evidence. Restriction changes projection/use, not the source fact.
- Legal exceptions are named branches with eligible actors, evidence, time and exact affected data. Free-text override is forbidden.

### Assurance and separation

| Action                                                                   | Minimum candidate assurance                                             |
| ------------------------------------------------------------------------ | ----------------------------------------------------------------------- |
| Ordinary masked directory/profile read                                   | Active session and current membership/permission/purpose.               |
| Raw restricted-field reveal or source evidence                           | MFA; RA <= 10 minutes; exact purpose; access evidence.                  |
| Proxy/consent/privacy decision, critical flag verification               | MFA; RA <= 10 minutes; independent checker where catalogue requires.    |
| Merge decision/execution, portal recovery/relink, restricted/bulk export | MFA; RA <= 5 minutes; exact digest/revision; required actor separation. |
| Patient/proxy portal access                                              | MFA; current proof/link/authority; no organization membership.          |

The server returns an assurance challenge without partially disclosing the protected data. After step-up, the original operation is reauthorized from current state.

### Search, cursor and denial behavior

- Directory and duplicate search require allow-listed literal/fuzzy fields, minimum query quality, capped result count/page size, rate limit and stable server sort. Broad wildcard enumeration is rejected.
- Cursors are opaque HMAC-signed values bound to operation, tenant, actor class, normalized filters, purpose, field projection, sort, page size, snapshot and expiry. No sensitive input appears in cursor text.
- Guessed/cross-tenant IDs use hidden denial where disclosure would reveal existence. A known but withheld field uses a policy-safe withheld marker only when allowed.
- Authorization/provider/policy ambiguity fails closed without turning a `403/404/503` into evidence about the patient or restriction.

### Service identities and workers

- Matching refresh worker: read approved match material and write bounded candidate evidence only; cannot merge.
- Expiry worker: end expired authority/consent/links, invalidate dependent access and write evidence; cannot create new grants.
- Notification worker: process one authorized message attempt and re-evaluate destination/template/provider policy; cannot choose purpose.
- Export worker: read exact authorized snapshot/projection and write private artifact; cannot approve or access it.
- Retention worker: dispose exact eligible artifact/record under current schedule and legal-hold state; cannot release holds.
- FHIR adapter: process one approved partner/profile/purpose/tenant operation; cannot generic-search or cross-organizational match.

Each service credential is separately provisioned/rotated, exact-operation-scoped, non-interactive, tenant-bound and monitored. Inbox/outbox receipt does not transfer caller authority.

## Verification and acceptance

- Permission tests deny every operation without exact canonical grant and prove no role-name/prefix/client-action inference.
- Projection tests compare every actor/purpose/self/proxy/restriction variant and prove withheld values never reach the browser.
- Assurance tests cover absent/stale MFA/RA, step-up recovery, permission/authority changes during challenge and no partial response.
- Maker/checker/executor tests cover self-decision, actor reuse, target conflict, stale digest, decision expiry/replay and service wildcard attempts.
- Search/cursor tests cover enumeration bounds, unsafe fuzzy inputs, signing/binding/tamper/expiry and cross-tenant guessed identifiers.

## Approval boundary

Permission names, projections, assurances, purposes and service identities are candidates only. They do not create registry entries or grants. Exact-package approval is required before production authorization behavior is implemented.
