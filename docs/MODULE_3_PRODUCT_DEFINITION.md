# CareOS Module 3 patient-registry product definition

**Status:** `RECOMMENDED_CHANGES_FOR_REVIEW`
**Draft version:** `m3-product-definition-draft-2`
**Prior review artifact:** `m3-product-definition-draft-1`
**Review direction received:** `ACCEPT_WITH_CHANGES`; the exact changes below remain subject to accountable acceptance
**Module:** M3 Patient Registry (`P3-01` through `P3-16`)
**Authority:** CareOS Java + React/Node build specification, SHA-256 `2c8f9f020c7c1a21678c795df57fd8be1b659f2141ab7c8747b5dec973db0bfe`
**Accepted predecessor:** Module 2 repository completion record `M2-COMPLETION-ACCEPTANCE-20260926-01`
**Implementation authority:** `false`

## Approval boundary

This document begins the architecture and product-definition phase authorized after Module 2 completion. Draft 2 responds to the reviewer's `ACCEPT_WITH_CHANGES` direction by proposing a conservative resolution for all fifteen open decision families. It is not an implementation specification, accepted policy, clickable mockup, migration plan, permission release, or production authorization. None of the recommendations becomes an approved CareOS policy until an accountable reviewer accepts this exact draft.

The build specification requires product approval before high-fidelity clickable mockups are produced. After the decisions below are resolved, Phase 3A may assemble an exact checksum-bound review package and mockup. No M3 production migration, API, worker, permission, registry entry, generated client, or route behavior may be created until that later package receives separate accountable approval.

## Purpose and non-goals

M3 establishes an organization-scoped, duplicate-safe patient identity and consent registry. It supports registration, identity/demographic maintenance, contacts/addresses, communication preferences, identifiers, caregivers/proxies, consent/privacy, safety flags, duplicate review/merge governance, a patient summary, and an identity/audit timeline.

M3 does not:

- create appointments, encounters, clinical assessments, care plans, invoices, results, or AI sessions;
- treat portal access as a condition of patient existence;
- create a second user, role, permission, organization, facility, document, audit, outbox, notification, or export source of truth;
- perform cross-organization matching or disclose that another organization has a matching person without a separately approved lawful basis;
- auto-merge records, physically delete effective identity/history, or rewrite clinical attribution;
- implement break-glass access, a master-patient index across tenants, or a national identifier lookup without separately approved policy and provider contracts.

## Frozen dependencies and module boundary

- Reuse M1 organizations, facilities/locations where a registration source requires them, users, invitations, sessions, MFA/recent-authentication, canonical RBAC, configuration activation, audit/outbox, idempotency, history, and purpose-bound export mechanics.
- Reuse platform notification, job, private-document, promotion, signed-access, and retention ports only through published application interfaces and only where an approved M3 use case needs them.
- M3 owns patient identity, patient contact/address history, patient identifiers, preferences, caregiver/proxy relationships, consent/privacy records, safety flags, duplicate candidates, merge decisions, and registration runs.
- Patient records and user accounts remain independent. Optional patient/caregiver portal linkage goes through the existing identity invitation/linkage boundary and never gives organization membership implicitly.
- The M3 backend module must follow `patientregistry/{domain,application,infrastructure,api}`. It may consume published M1/platform capabilities but cannot write their repositories.
- Every tenant-owned M3 row carries `organization_id`, uses forced RLS, and uses composite tenant foreign keys. Human-readable identifiers and match keys are not authorization evidence.

## Actors

| Actor                         | Proposed responsibility                                                                       | Explicit boundary                                                                      |
| ----------------------------- | --------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------- |
| Registration user             | Search before create, collect supplied data, review duplicate candidates, submit registration | Cannot resolve ambiguous merge or grant proxy authority beyond approved low-risk rules |
| Patient registry reviewer     | Independently review high-risk identity changes and duplicate/merge requests                  | Cannot approve a request they made or merge across organizations                       |
| Privacy officer               | Govern consent/privacy restrictions and purpose-bound disclosure                              | Cannot alter clinical facts or silently erase lawful history                           |
| Authorized clinician          | Read minimum necessary patient identity/safety projections and propose safety flags           | Cannot manage identity merely because they can deliver care                            |
| Patient/caregiver portal user | Exercise explicitly linked self/proxy capabilities                                            | Not an organization member; authority is relationship-, purpose-, and time-bound       |
| Auditor                       | Read allow-listed purpose-bound evidence                                                      | No mutation or unrestricted sensitive values                                           |
| Worker/service identity       | Run approved matching, expiry, notification, and export tasks                                 | Exact operation/tenant/purpose only; queued work is not authority                      |
| Support operator              | Technical support under normal application permissions                                        | No automatic patient or clinical access                                                |

Role-to-permission grants remain a product/security decision. Runtime code must use canonical permission assignments rather than role-name inference.

## End-to-end user journeys

### Registration

1. Start a registration run and record organization, source, supplier relationship, purpose, and idempotency key.
2. Normalize permitted search inputs and perform organization-scoped exact/fuzzy duplicate search before creation.
3. Show match factors and confidence bands without exposing fields the actor cannot otherwise read.
4. Require the user to select an existing patient, escalate an ambiguous candidate, or give a reason to continue as new.
5. Collect identity/demographics, effective contacts/addresses, preferences, identifiers, proxy/consent/privacy information, and safety flags only when authorized and applicable.
6. Validate server-side, present a minimum-necessary review, and submit with the current revision.
7. Create or link the patient atomically with canonical audit/outbox evidence. Portal invitation is a separate optional action.

### Duplicate governance and merge

1. Matching creates or refreshes a bounded duplicate candidate; it never merges automatically.
2. An authorized reviewer compares allow-listed field provenance and effective history.
3. A merge request names surviving and duplicate records, rationale, field-level disposition, affected references, and current revisions.
4. An independent checker approves or rejects. The database prevents self-approval, stale execution, cross-tenant records, cycles, and multiple active targets.
5. Execution preserves both identifiers and all attribution, maps future reads to the survivor, emits canonical evidence, and invalidates stale projections/exports.
6. Any later correction uses a separately governed correction decision; there is no casual “undo merge.”

### Caregiver/proxy authority

1. Record the relationship, authority source, permitted purposes/actions, effective interval, verification method, and revocation/expiry conditions.
2. Independently review authority where law/policy requires it.
3. Link or invite a user only after authority is active; user linkage and proxy authority remain separate records.
4. Re-evaluate current relationship, patient, consent/privacy restrictions, purpose, and requested action on every access.
5. Revoke/expire authority without deleting historical actions or communications.

### Consent, privacy, and safety

1. Consent is purpose-, policy-version-, subject-, grantee-, scope-, and effective-time specific.
2. Withdrawal blocks future consent-dependent processing but preserves lawful historical evidence.
3. Privacy restrictions constrain projections, exports, notifications, and portal/proxy access; they do not silently rewrite source records.
4. Safety flags require category, severity, source, verification, effective interval, visibility policy, and review/closure evidence.
5. A clinical-safety flag is never hidden solely by a communication preference or ordinary proxy request.

## Screen register and required states

Every screen requires loading, empty, no-result, denied, stale/conflict, validation, dependency-failure, success, and responsive states where applicable. Lists use server filtering/sorting and opaque cursor pagination.

| ID    | Screen                     | Required production behavior                                                                                                                                                   |
| ----- | -------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| P3-01 | Patient registry dashboard | Permission-filtered registration, duplicate, consent/privacy, proxy-expiry, identifier, and safety-review metrics/queues with freshness and safe deep links                    |
| P3-02 | Patient directory          | Minimum-necessary search/filter/sort/cursor list; masked identifiers/contacts; explicit no-result and restricted-result states                                                 |
| P3-03 | Start registration         | Create an idempotent registration run with source, supplier relationship, purpose, facility/location where approved, and initial search inputs                                 |
| P3-04 | Duplicate patient search   | Explain exact/fuzzy match factors and confidence band; select existing, escalate, or continue-new with reason; never auto-merge                                                |
| P3-05 | Identity and demographics  | Effective, provenance-aware names/demographics with verification, correction, estimated/unknown-value policy, revision conflicts, and sensitive-field projection               |
| P3-06 | Contacts and addresses     | Effective history, verification, preferred/primary rules, immutable supersession, confidentiality, and masked list values                                                      |
| P3-07 | Communication preferences  | Channel, language, accessible format, quiet-time and contact-purpose preferences separated from legal consent and provider delivery capability                                 |
| P3-08 | Identifiers                | Scheme/jurisdiction/type/value lifecycle, normalized uniqueness, verification, primary display, end/supersede/correct, and confidential projection                             |
| P3-09 | Caregivers and proxies     | Relationship and authority scope/effective dates, evidence/verification, independent review when required, user linkage/invitation, expiry/revocation                          |
| P3-10 | Consent and privacy        | Purpose/version/legal-basis consent, withdrawal/supersession, privacy restrictions, field/action consequences, and minimum-necessary evidence                                  |
| P3-11 | Clinical safety flags      | Category/severity/source/verification/effective interval, permission-safe visibility, acknowledgement/review, resolve/supersede/entered-in-error                               |
| P3-12 | Review and register        | Cross-section validation, duplicate disposition, consent/proxy/safety warnings, field provenance, exact submission revision, atomic registration result                        |
| P3-13 | Patient summary            | Canonical selected-patient projection with demographics, masked contacts/identifiers, active authority/consent/privacy/safety summaries, and server-projected actions          |
| P3-14 | Potential duplicate queue  | Risk/reason/SLA filters, bounded comparison access, assignment/lease, stale recovery, and no sensitive bulk export                                                             |
| P3-15 | Merge review               | Survivor/duplicate comparison, provenance, field-level disposition, affected-reference impact, independent approve/reject, recent-authentication, exact execution confirmation |
| P3-16 | Identity/audit timeline    | Allow-listed correlated identity, contact, identifier, relationship, consent/privacy, safety, duplicate, merge, and portal-link evidence with purpose-bound detail             |

## Form and field baseline

All values require explicit classification (`PUBLIC`, `INTERNAL`, `CONFIDENTIAL`, or `RESTRICTED`), provenance, server validation, and field-level projection rules.

| Area                | Draft field baseline                                                                                                                                                                                                                              |
| ------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Registration        | organization, source, supplied-by relationship, purpose, facility/location if applicable, started/expiry time, status, revision                                                                                                                   |
| Identity            | legal name components, preferred name, previous/alias evidence if approved, date of birth/estimated marker, deceased state/date if approved, sex-at-birth/gender/pronouns only under approved purpose/classification, provenance and verification |
| Contact/address     | type/purpose, normalized confidential value, masked display, effective interval, preferred/primary, verification method/time, provenance, supersession lineage                                                                                    |
| Preferences         | contact purpose, allowed/disallowed channel, preferred language/format, accessibility need, quiet interval/timezone, effective interval, source                                                                                                   |
| Identifier          | scheme/version, type, issuing authority/jurisdiction, normalized confidential value, masked display, issue/effective/end dates, verification, primary flag, lineage                                                                               |
| Caregiver/proxy     | relationship type, authority source/evidence, permitted purposes/actions, subject/grantee, effective interval, verification/review, user-link state, revocation reason                                                                            |
| Consent             | subject, grantor authority, purpose, policy/version/digest, scope, grantee/category, legal basis where applicable, decision/effective/expiry/withdrawal, evidence                                                                                 |
| Privacy restriction | restriction type, purpose/resource/field/channel scope, policy/version, effective interval, reason/evidence, review/ending decision                                                                                                               |
| Safety flag         | category, severity, coded statement, source/provenance, verified state, visibility class, effective/review/expiry, acknowledgement and closure evidence                                                                                           |
| Duplicate/merge     | patient pair, match factors/confidence band, detector/version, disposition/reason, survivor, field disposition, affected-reference digest, maker/checker/executor, revisions                                                                      |

Raw identifier/contact values, free-text clinical details, uploaded evidence, secrets, tokens, and provider URLs must never appear in audit/outbox payloads, URLs, logs, notification parameters, cursor text, or analytics labels.

## Core data model

The build specification names the following 14 M3-owned entity families. Exact columns, constraints, indexes, and supporting tables remain subject to the approved input package.

| Entity                         | Responsibility and key relationships                                                                                                                                                         |
| ------------------------------ | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `patient_profiles`             | Organization-scoped patient aggregate, lifecycle, canonical display fields, merge target, revision and provenance                                                                            |
| `patient_identifiers`          | Effective verified identifiers with scheme/jurisdiction, normalized uniqueness and supersession lineage                                                                                      |
| `patient_contacts`             | Effective confidential contact history and masked projection metadata                                                                                                                        |
| `patient_addresses`            | Effective address history, use/purpose, verification and lineage                                                                                                                             |
| `communication_preferences`    | Purpose/channel/language/format preferences separated from consent and delivery state                                                                                                        |
| `caregiver_relationships`      | Patient-to-person relationship facts only; separate supporting authority-grant and optional portal-user-link records carry scoped authority, verification, effective interval and revocation |
| `patient_consents`             | Versioned purpose/scope decisions, grantor authority, legal basis/evidence and withdrawal/supersession                                                                                       |
| `privacy_restrictions`         | Versioned field/resource/purpose/channel limitations and effective consequences                                                                                                              |
| `patient_safety_flags`         | Governed clinical-safety facts, visibility, review and lifecycle evidence                                                                                                                    |
| `patient_match_keys`           | HMAC/normalized organization-scoped match material; never a browsable identity index                                                                                                         |
| `patient_duplicate_candidates` | Bounded detected patient pair, factors, detector version, risk band and disposition                                                                                                          |
| `patient_merge_requests`       | Proposed survivor/duplicate, field disposition, impact digest, reason, maker and expiry                                                                                                      |
| `patient_merge_decisions`      | Independent approval/rejection and exact request/revision/digest binding                                                                                                                     |
| `patient_registration_runs`    | Idempotent staged registration state, duplicate disposition, validation result and completion link                                                                                           |

Required database invariants include UUIDv7 identifiers, server timestamps, non-null tenant linkage, `lock_version`, bounded statuses/reasons, no prohibited effective overlap, immutable decisions/evidence, composite tenant foreign keys, forced RLS, restricted runtime grants, append-only history, and direct-SQL lifecycle guards. Effective and referenced records are ended, superseded, corrected, merged, or entered in error rather than deleted.

## Draft lifecycle model

| Aggregate                 | Proposed states                                                                                                 | Recommended rule / local activation gate                                                                                                                                                            |
| ------------------------- | --------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Registration run          | `collecting -> duplicate_review -> ready -> submitted -> completed`; `abandoned/rejected` terminal alternatives | Default to 24-hour inactivity expiry; only the creator or `patient.registration.manage` may resume before expiry; abandoned/rejected runs never reopen                                              |
| Patient profile           | `draft -> pending_review -> active`; later `inactive/deceased/merged/entered_in_error` outcomes                 | Inactive is an operational selection restriction; deceased is a verified fact with communication/access consequences; merged redirects to the survivor; entered-in-error is not a deletion shortcut |
| Identifier                | `draft -> active -> ended/superseded/revoked/entered_in_error`                                                  | Scheme catalogue controls uniqueness; correction preserves the original and lineage                                                                                                                 |
| Caregiver/proxy authority | `proposed -> pending_review -> active -> suspended/revoked/expired/superseded`                                  | Local-law authority catalogue controls checker/evidence; the underlying relationship is not authority                                                                                               |
| Consent                   | `draft -> active -> withdrawn/expired/superseded/entered_in_error`                                              | Approved purpose/legal-basis policy controls activation; withdrawal is prospective and invalidates future consent-dependent use                                                                     |
| Privacy restriction       | `proposed -> active -> ended/superseded/entered_in_error`                                                       | Approved projection policy controls consequences; no emergency/break-glass override exists in M3                                                                                                    |
| Safety flag               | `proposed -> provisional/active -> resolved/superseded/entered_in_error`                                        | Clinical catalogue controls eligibility, severity, provisional limit, acknowledgement/review SLA and visibility                                                                                     |
| Duplicate candidate       | `open -> under_review -> dismissed/merge_requested/resolved`                                                    | Calibrated explainable bands only; dismissals reopen after detector or material-source change                                                                                                       |
| Merge request             | `draft -> submitted -> approved/rejected/expired -> executed`                                                   | Independent maker/checker plus constrained executor; correction/disentanglement replaces routine unmerge                                                                                            |

Every transition must bind source state, target state, exact operation, tenant/resource scope, reason, revision, idempotency, effective time, MFA/recent-authentication, maker/checker/executor separation, database invariant, audit/outbox event, and user-visible result.

## Draft API families

All protected routes remain below `/api/v1/organizations/{organizationId}/patients/...`; IDs are opaque UUIDv7 values and never authority.

- Dashboard, directory and patient summary reads with allow-listed filters, stable sort, bounded cursor and field projection.
- Registration run create/read/update/validate/submit and duplicate-search/disposition actions.
- Effective identity/demographic, contact/address, preference and identifier read/mutation families with strong revisions.
- Caregiver/proxy request/review/activate/revoke/link/invite actions.
- Consent grant/withdraw/supersede and privacy-restriction request/activate/end actions.
- Safety-flag propose/verify/acknowledge/resolve/correct actions.
- Duplicate queue claim/release, merge request, independent decision and exact execution.
- Purpose-bound timeline/detail/export operations using signed, filter-bound cursors and short-lived private artifacts.

Create/upload/approval/execution actions require scoped `Idempotency-Key`; revision-bound mutations require strong `If-Match`; all failures use RFC 9457 Problems with stable field pointers and correlation IDs. The browser must structurally validate generated OpenAPI responses before state entry and never optimistically declare governed success.

## Candidate permission families

These names reserve review vocabulary only; they do not authorize registry entries or grants.

- `patient.dashboard.read`, `patient.directory.read`, `patient.profile.read`, `patient.profile.manage`
- `patient.registration.start`, `patient.registration.manage`, `patient.registration.submit`
- `patient.duplicate.search`, `patient.duplicate.review`, `patient.merge.request`, `patient.merge.decide`, `patient.merge.execute`
- `patient.contact.read`, `patient.contact.manage`, `patient.preference.manage`, `patient.identifier.manage`
- `patient.proxy.read`, `patient.proxy.request`, `patient.proxy.decide`, `patient.proxy.revoke`
- `patient.consent.read`, `patient.consent.manage`, `patient.privacy.read`, `patient.privacy.manage`
- `patient.safety_flag.read`, `patient.safety_flag.propose`, `patient.safety_flag.verify`, `patient.safety_flag.resolve`
- `patient.timeline.read`, `patient.audit.read`, `patient.export.request`, `patient.export.access`

Directory/profile projections, confidential identifiers/contacts, proxy authority, consent/privacy, safety flags, merge comparison, timeline detail, and exports require separate field/action decisions. High-risk decisions must use recent authentication plus MFA and deny self-decision. Service identities receive only exact worker operations and never interactive wildcard permissions.

## Candidate audit/outbox vocabulary

Canonical events should be version 1, migration-owned, and activated only with approved payload schemas. Candidate families include:

- `patient.registration.started|validated|completed|abandoned`
- `patient.profile.created|updated|status_changed`
- `patient.contact.changed`, `patient.address.changed`, `patient.preference.changed`, `patient.identifier.changed`
- `patient.proxy.requested|activated|revoked|expired`
- `patient.consent.granted|withdrawn|superseded`, `patient.privacy_restriction.activated|ended`
- `patient.safety_flag.proposed|verified|acknowledged|resolved|corrected`
- `patient.duplicate.detected|dismissed`, `patient.merge.requested|approved|rejected|executed`
- `patient.portal_link.requested|completed|revoked`
- `patient.export.requested|completed|accessed|expired|disposed`

Required metadata includes event/version, organization, actor/service identity, patient/aggregate ID, operation, purpose, reason code, correlation/causation, revision, source/target state, policy/configuration version, idempotency scope and server time. Payloads use IDs, classifications, booleans, counts, reason codes and digests—not raw sensitive values.

## Jobs and notifications

Candidate job families are registration expiry/cleanup, duplicate rescoring, proxy/consent/identifier review or expiry, safety-review reminders, export generation/disposal, and notification retry/dead-letter handling. Every worker reauthorizes current tenant/purpose/policy and rechecks the target revision before its effect. A queued job is not authority.

Notifications require current consent/preference, approved purpose/template/channel, minimum parameters, recipient resolution at delivery time, provider idempotency, acknowledgement/retry evidence, and safe failure handling. No clinical-safety obligation is satisfied solely by an email/SMS attempt.

## Security, privacy, and clinical-safety controls

- Search and duplicate comparison are minimum-necessary and organization-scoped; enumeration, wildcard search and response-size abuse are bounded.
- Match keys use approved normalization plus keyed hashing where appropriate; raw values are never general lookup indexes.
- Sensitive fields are encrypted or tokenized where required, projected by purpose/permission, masked in lists, and excluded from logs/events.
- Merge execution serializes both patient aggregates, proves exact revisions and impact digest, preserves immutable lineage, and invalidates stale caches/exports.
- Consent is not a universal substitute for legal basis; privacy/legal owners must approve purpose catalogues and withdrawal behavior.
- Proxy authority is checked on every action and cannot exceed the patient's/current legal authority or survive revocation/expiry.
- Safety flags are clinically governed, cannot be casually hidden by privacy/preferences, and require safe correction rather than deletion.
- Break-glass, cross-organization matching, national identity lookup, biometric matching, and bulk registry export remain unavailable unless separately approved.

## History, export, reporting, and FHIR

- P3-16 is an allow-listed correlated evidence projection, not a raw audit-table browser.
- Detail and exports require explicit purpose, filter/scope, recent authentication where sensitive, field policy, row/cell injection safety, immutable request snapshot, private short-lived access and access audit.
- Operational metrics use safe counts/ages/statuses; never raw names, contacts, identifiers, free text or small-cohort disclosures.
- Candidate FHIR mapping: `patient_profiles` -> Patient; caregiver relationships -> RelatedPerson and Patient.contact where appropriate; patient consents -> Consent; safety flags -> Flag; provenance/decisions -> Provenance/AuditEvent. FHIR remains a versioned external representation rather than the internal schema. No FHIR endpoint activates until an exact base release, implementation-guide package/version, identifier systems, extensions, security labels, consent semantics and merge/link behavior are approved and validated.

## Synthetic review scenarios

1. No-match adult registration with verified contact and no portal account.
2. Exact identifier match requiring selection of an existing patient.
3. Ambiguous name/date-of-birth/contact match escalated without disclosing unauthorized fields.
4. Newborn/unknown-data scenario to resolve partial-date and guardian policy.
5. Proxy request that expires and loses access immediately while history remains.
6. Purpose-specific consent withdrawal affecting future communication/export only.
7. Privacy restriction suppressing a field from directory/export but retaining lawful source history.
8. High-severity safety flag with clinical review and correction.
9. Independent two-person merge with stale-revision rejection and preserved attribution.
10. Cross-tenant guessed patient/duplicate/merge/timeline identifiers denied by HTTP and direct SQL.

All examples must use conspicuously synthetic identities and must never resemble real patient data.

## Acceptance-test baseline

- Domain/unit tests for normalization, realistic/partial dates, effective intervals, provenance, state transitions, authority, consent withdrawal, privacy consequences, safety review, duplicate scoring bands and merge field disposition.
- PostgreSQL/Testcontainers tests for V1-forward clean migration, exact schema, UUIDv7 defaults, forced RLS, composite FKs, restricted grants, missing-context/cross-tenant denial, immutable decisions/history, overlap/uniqueness, maker-checker, merge serialization and direct-SQL attacks.
- HTTP/security tests for every permission, projection, guessed ID, field classification, search bound, revision/idempotency, self-decision, recent-auth/MFA and minimum-necessary denial.
- Frontend unit tests for every screen state, runtime contract rejection, filters/cursors, forms/error focus, comparison/confirmation, no optimistic success and organization/session invalidation.
- Playwright/Axe/overflow coverage at 1440, 1024, 768, 390 and 320 for all P3 screens and critical registration, duplicate, proxy, consent/privacy, safety, merge and timeline paths.
- Complete M1/M2 regression, generated-client drift, architecture, build, dependency/security, secret/path, image, test-isolation and synthetic-data checks.

## Recommended conservative decision set

The recommendations below are proposed CareOS defaults, not claims about the law in every deployment jurisdiction. A required catalogue or policy that has not been approved makes only its dependent capability unavailable; it must never trigger a permissive fallback. Mockups may show the fail-closed and policy-unavailable states while the local catalogues are prepared.

### 1. Patient scope

- Keep patient identity, search, match keys, duplicate detection and merge strictly organization-scoped. Facilities within one organization may use the same patient identity subject to purpose and field projection.
- Do not create a global `Person`, enterprise master-patient index across tenants, cross-organization search, match disclosure or identity graph in M3.
- Treat an external record or identifier as provenance supplied to the current organization, not evidence that another CareOS tenant contains the person.
- Require a separate privacy, legal, security and interoperability package before any future federation or cross-organization matching capability.

**Recommended resolution:** accept organization-only identity for M3. Cross-organization linkage remains out of scope and unavailable.

### 2. Demographic model

- Never require fabricated values. An activatable patient needs an internal organization-scoped patient identifier, lifecycle state, provenance, and either at least one name representation or an explicit unnamed/temporary-identity state. Date of birth is stored as a value plus separate precision and certainty (`exact`, `partial`, `estimated`, or `unknown`); missing components must not be invented.
- Model legal/official, usual/preferred, former and temporary names with effective periods and provenance. Search may normalize a separate match representation but never replace the supplied display value.
- Support newborns with a temporary identifier/name state, birth order where applicable, and a later governed identity correction/merge. Support deceased state with date precision/certainty. Defer prenatal/fetal patient profiles until a separate maternal-fetal identity and jurisdiction policy is approved.
- Treat `inactive` as an operational state, not a synonym for deceased. A verified deceased state preserves the record, stops new routine invitations/communications, and follows approved post-death access rules. `entered_in_error` is allowed only for a record that never represented the intended patient; attributed care requires governed correction/disentanglement instead of concealment.
- Keep administrative recorded sex, gender identity, pronouns, name-to-use and any sex parameter for clinical use as distinct concepts. They are optional, purpose-bound, provenance-aware and independently restricted. A clinical sex parameter belongs to its clinical context and must not be inferred from a demographic field.
- Permit `unknown`, `not asked` and, where policy permits, `declined to answer` without converting them into clinical facts. Terminology and requiredness are versioned configuration, not hard-coded universal values.

**Recommended resolution:** adopt the structured unknown/partial/estimated model; support newborn and deceased records; defer prenatal records; approve local terminology before activating sensitive demographic collection.

### 3. Identity proofing and verification

- Separate patient-record verification from digital-account identity proofing. A clinically necessary patient record may exist without a portal account or proofed digital identity.
- Record verification per attribute using `unverified`, `self_or_source_attested`, `evidence_checked`, or `authoritative_source_verified`, with method, source class, actor, time, effective/expiry time and provenance. Do not claim a NIST Identity Assurance Level unless the complete implemented process conforms to that level.
- For portal linkage, perform documented risk-based resolution, evidence validation and applicant verification. Knowledge-based verification and biometric matching are unavailable in M3. Provide an attended exception path for people who cannot use the normal digital process.
- Retain only the minimum evidence metadata needed. If an evidence image is legally required, place it behind the existing quarantine/scan/private-document/retention boundary; never store it in the patient row, logs or events.
- Re-verify after a material identifier conflict, suspected compromise, high-risk linkage recovery or policy expiry. Corrections supersede prior assertions and preserve provenance.

**Recommended resolution:** use CareOS verification states for clinical data and a separately approved, NIST SP 800-63A-4-informed process for portal linkage; do not equate the two.

### 4. Identifier policy

- Maintain an allow-listed, versioned scheme catalogue containing jurisdiction, issuer, normalization, check rules, uniqueness scope, display mask, evidence requirement and lifecycle rules.
- Enforce active uniqueness only within the scheme's declared scope, normally `(organization, scheme version, issuer, normalized value)`. A scheme explicitly declared non-unique cannot be used as sole proof of identity.
- Store recoverable values encrypted where display is required, organization-derived HMAC match material for equality lookup, and masked values in list/search projections. Raw values stay out of URLs, cursors, logs, events and analytics.
- Permit at most one active primary identifier per patient and scheme/context. Correct, end, revoke or supersede identifiers; do not overwrite history.
- Disable national-identifier and third-party provider lookup until the exact jurisdiction, contract, lawful basis, failure behavior and provider security review are approved.

**Recommended resolution:** approve only locally configured schemes and local equality checks for M3; external identifier lookup remains unavailable.

### 5. Duplicate policy

- Require organization-scoped search before create. Use versioned, explainable normalization and match factors drawn only from fields the caller is allowed to use. Do not expose hidden raw values in explanations.
- Start with deterministic and explainable probabilistic rules. Do not activate machine-learning, biometric or external referential matching until separately validated and approved.
- Use four outcomes: `confirmed_identifier_conflict`, `high_review`, `possible_review`, and `below_display_threshold`. A confirmed active unique-identifier collision blocks ordinary new-record completion until the existing record is selected or an independent reviewer approves a reasoned exception. No band auto-merges patients.
- Never delay urgent care while identity is ambiguous. Create an explicitly temporary, highly visible patient identity through the governed urgent-registration path and route it for immediate reconciliation; do not attach care to a guessed existing record.
- Calibrate numeric weights and thresholds against an approved synthetic or appropriately governed validation set. Until calibrated, production duplicate matching is unavailable rather than silently using guessed scores.
- Triage the error queue every operating day. Confirmed conflicts and high-risk overlays receive immediate assignment, high-review cases have a recommended one-business-day ceiling, and possible-review cases have a recommended five-business-day ceiling; a clinical owner may require tighter limits.
- Bind dismissals to the detector version, factors, revisions and reason. Reopen when source data, a relevant identifier, algorithm version or material evidence changes. Monitor false-positive, false-negative, duplicate and overlay rates.

**Recommended resolution:** approve manual, explainable, daily-governed duplicate review with no auto-merge and no unvalidated scoring.

### 6. Merge policy

- Require a maker/checker workflow: the requester and independent approver are different recent-authenticated, MFA-verified humans; execution is a constrained service operation bound to the exact approved patients, revisions, survivor, field dispositions and impact digest.
- Select the survivor through an approved rule considering continuity, verified identifiers and downstream clinical references. Never choose solely by creation time and never automatically overwrite a survivor field.
- Lock both aggregates, prevent tenant crossing, cycles and concurrent targets, and explicitly disposition every conflicting field and downstream reference. Preserve source identifiers, provenance, attribution and immutable evidence.
- Represent external FHIR merge lineage with `Patient.link` `replaced-by`/`replaces` semantics when the selected profile supports them.
- Do not offer routine unmerge. A mistaken merge enters a governed correction/disentanglement process involving health-information management and affected clinical owners, with new evidence and review of care delivered while the records were conflated.
- Perform post-execution clinical-impact review whenever either record contains care activity after the suspected duplicate/overlay began.

**Recommended resolution:** approve two-person, revision-bound, lineage-preserving merge; correction is a separate governed process, not an undo button.

### 7. Caregiver and proxy authority

- Keep patient contact, related-person/caregiver relationship, legal authority and portal-user linkage as separate records. Relationship alone never grants authority.
- Authority records must name the grantor and grantee, authority source/type, permitted purposes/actions/data scope, evidence/verification, effective interval, jurisdiction and revocation/expiry conditions.
- Determine parent, minor, guardian, capacity, personal-representative, safeguarding and endangerment rules from applicable local law and qualified policy. Do not hard-code a universal age or assume that every parent can see every minor record.
- Patient-delegated access cannot exceed actions the patient may delegate. Proxies never acquire organization membership, patient-merge authority or unrestricted support access.
- Re-evaluate active authority and restrictions on every request. Revocation/expiry immediately prevents new access and revokes active portal sessions/tokens while preserving historical actions.
- Do not implement proxy emergency access in M3. Any future emergency path belongs to a separately approved break-glass design.

**Recommended resolution:** approve explicit, scoped, effective-dated authority with local-law rules; deny proxy access when authority is absent, ambiguous or expired.

### 8. Consent policy

- Treat consent as one policy input, not a universal lawful basis. Each record binds subject, grantor authority, decision, purpose, actor/grantee, action, data scope, policy/version/digest, legal or regulatory basis where applicable, effective period and evidence.
- Preserve the executed human-readable consent directive or an immutable reference to it, plus a computable derivative clearly identified as such.
- If a use case requires consent, absent, ambiguous, expired or withdrawn consent denies that use. If another legal basis applies, processing still requires an explicit approved rule; the system must not infer one.
- Withdrawal is prospective. It blocks future consent-dependent activity, invalidates derived access/caches/jobs, and preserves prior lawful actions and evidence. It does not silently delete records or undo completed disclosures.
- Resolve conflicts and multiple active directives through one versioned policy evaluator. An unknown purpose, action, actor, data class or policy version denies and routes to privacy review.

**Recommended resolution:** approve purpose/action/data/time-specific consent with prospective withdrawal and deny-by-default evaluation for consent-dependent uses.

### 9. Privacy restrictions

- Model a restriction request, accountable decision and effective restriction separately. Apply accepted restrictions in one server-side projection/policy layer across directory, profile, proxy, portal, notification, export and integration paths.
- Restrictions alter visibility or allowed use; they never rewrite the source fact. Redactions must be explicit in the response so an authorized user can distinguish `not present` from `withheld` where policy permits that distinction.
- Support and operations personnel receive no patient-content access by default. Any approved support access requires exact purpose, field allow-list, time bound, recent authentication and immutable access evidence.
- Legal exceptions must be named, versioned policy branches with eligible actors and evidence—not a free-text operator override.
- Keep break-glass unavailable in M3. Clinical-safety access that cannot tolerate restriction requires a separately approved legal/clinical/security workflow before implementation.

**Recommended resolution:** approve deny-by-default, purpose-bound projections with no M3 break-glass or implicit support override.

### 10. Safety flags

- Use a flag only for concise, high-priority information that must influence an interaction before detailed record review. Link to the underlying clinical or administrative record instead of duplicating narrative detail.
- Start with four governed categories: identity safety, clinical safety, safeguarding, and accessibility/environmental need. Exclude financial status, subjective character labels and unverified allegations from prominent clinical flags.
- A clinical flag requires an eligible clinician author or verifier; an identity-safety flag requires a trained registry reviewer. Author and verifier must differ for high-severity flags. Every flag carries source, coded statement, severity, visibility class, effective/review/expiry time and correction lineage.
- A potentially urgent flag may appear as visibly `provisional` while independent verification is pending. The recommended ceiling is four hours for critical verification, one business day for high, and five business days for standard; the clinical-safety owner must approve or tighten these limits before activation.
- Critical flags require acknowledgement before the relevant governed workflow completes. Resolved, superseded and entered-in-error flags retain history. Flags are not messages, task escalation or substitutes for normal allergy/order checking.

**Recommended resolution:** approve concise, linked, role-verified flags with a clinically approved severity/SLA catalogue; without that catalogue the flag capability remains unavailable.

### 11. Portal linkage and invitation

- Keep patient existence independent from portal eligibility. Permit self-linkage only after approved identity proofing; permit proxy linkage only through an active authority record covering the requested portal actions.
- Reuse the platform's one-use invitation mechanics without creating organization membership. Patient/proxy invitations should use a 24-hour-or-shorter policy even though the platform hard limit is seven days, bind the intended subject/link/purpose and reveal no patient information before proofing.
- Require MFA for all patient/proxy portal access and recent authentication for relationship changes, recovery and high-impact actions. Recovery must meet equivalent proofing strength and cannot use security questions or staff discretion alone.
- Do not infer parental access from age or surname. Guardian/minor/capacity flows remain unavailable until the local proxy policy is active.
- Revocation, merge, suspected compromise, death-related policy change or authority expiry invalidates relevant sessions, invitations and cached projections.

**Recommended resolution:** approve one-use, proofing-bound, MFA-protected portal linkage; no implicit guardian, membership or recovery bypass.

### 12. Communication policy

- Evaluate three independent conditions for every message: channel preference, lawful/consent authority for the purpose, and current provider deliverability. A positive preference alone authorizes nothing.
- Store preferences by purpose and channel with language, accessible format, timezone and quiet interval. Emergency or safety-critical clinical escalation is a separate clinician workflow and is never considered delivered merely because an email/SMS job was accepted.
- Default email/SMS/push content to a generic notice with a protected portal link; do not place diagnoses, safety flags, identifiers, consent details or other sensitive content in the message or provider metadata.
- Fail closed when the destination is unverified, the provider is unavailable, authority changed, quiet-time policy cannot be evaluated, or the template/version is not allow-listed. Record safe delivery evidence and support accessible alternatives.

**Recommended resolution:** approve purpose/channel-specific preferences separated from consent and delivery; keep clinical escalation outside consumer notification.

### 13. Retention, residency and legal hold

- Use a versioned policy matrix by jurisdiction, record class, lifecycle and legal basis. Do not embed one global retention duration in code.
- Until the applicable matrix is approved, refuse disposal and refuse production activation in a hosting/residency arrangement that has not been accepted. This conservative hold is temporary governance behavior, not an indefinite-retention policy.
- Minimize abandoned registration data, prevent it from operational/analytics use and purge it only under an approved short-duration schedule after required duplicate/link evidence is separated. Never guess the duration.
- Preserve merge lineage, identity corrections, consent/proxy decisions, safety-flag history and audit evidence for their approved schedules. A patient request routes to the applicable access/correction/restriction/erasure decision; it does not directly delete the legal record.
- A legal hold suspends disposal across primary data, artifacts and governed backups. Hold release and final disposal require separately authorized, evidenced workflows; M3 must not weaken the platform's one-way hold-enablement boundary.

**Recommended resolution:** approve policy-driven retention and residency gates with no guessed duration; disposal and production hosting stay unavailable until local schedules/providers are accepted.

### 14. Export and reporting

- Separate patient-access copies, operational reports, privacy/audit evidence, analytics cohorts and interoperability exports; each receives its own purpose, permission, fields, approval and retention rule.
- Require an allow-listed template/dataset, organization and immutable query snapshot, field classification/redaction, reason, recent authentication for sensitive data, and independent approval for bulk or restricted exports.
- Generate asynchronously into private storage, protect against spreadsheet formula injection, use bounded one-hour-or-less signed access under the existing platform maximum, audit every grant/access, and dispose of the artifact on its approved schedule.
- No unrestricted registry dump or ad-hoc selection of raw sensitive columns. Cohort reporting remains unavailable until a privacy owner approves a small-cell/disclosure-control rule; do not invent a universal numeric threshold.
- Do not place raw identifiers, contacts, free text or security/safety details in dashboard telemetry or operational metrics.

**Recommended resolution:** approve purpose-bound, template-based private exports; disable unrestricted bulk and unsafeguarded cohort reporting.

### 15. FHIR and interoperability

- Follow the build specification's page-27 boundary: FHIR is an external representation, explicit mappings and terminology are versioned, resources are profile-validated, provenance is recorded, and a broad generic server is not exposed initially.
- Do not select a universal FHIR release by guesswork. Each active adapter must pin an exact base release plus implementation-guide package/version required by its jurisdiction or partner. With no approved profile package, FHIR import/export remains unavailable while the rest of M3 may proceed.
- Baseline semantic mappings are Patient, RelatedPerson/Patient.contact, Consent, Flag, Provenance and AuditEvent. Do not use Person to create a cross-organization identity graph in M3.
- Map merges using the selected profile's `Patient.link` semantics; preserve CareOS stable identifiers, source provenance and security labels. Validate every inbound/outbound resource and reject unknown profiles, terminology versions or tenant context.
- Consent resources and security labels describe policy; they do not enforce access by themselves. All inbound/outbound actions still pass CareOS tenant, purpose, permission, consent/privacy and minimum-necessary authorization.
- Use purpose-specific endpoints and idempotent conditional behavior only where the approved profile supports it. Keep generic search, bulk export and cross-organization matching disabled.

**Recommended resolution:** approve profile-first, version-pinned adapters with no generic FHIR server; the exact first profile/version remains an integration activation prerequisite rather than a guessed M3 schema choice.

## Required accountable approvals and activation gates

| Owner                                    | Must approve before the dependent capability activates                                                                                                     |
| ---------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Product owner                            | This exact decision set, supported workflows, screen behavior and deferrals                                                                                |
| Health-information/data-governance owner | Demographic requiredness, identifier schemes, matching calibration, queue SLA, survivor and correction/disentanglement rules                               |
| Privacy/legal owner for each deployment  | Proxy/minor/capacity authority, consent purposes/legal bases, restriction exceptions, retention/residency, communications, exports and disclosure controls |
| Clinical-safety owner                    | Sensitive clinical-demographic use, safety-flag categories/severity/visibility/SLA, acknowledgement and clinical-impact review                             |
| Security/identity owner                  | Portal proofing, MFA/recovery, evidence handling, identifier protection, support access and provider controls                                              |
| Interoperability owner                   | Exact FHIR release, implementation-guide package/version, terminology, identifier systems, mappings, validation and partner contract                       |

Acceptance of this product definition may authorize high-fidelity synthetic mockups. It does not activate a locally gated capability whose catalogue is absent, and it does not authorize production implementation.

## Standards and evidence basis

These sources inform the recommendation but do not replace deployment-specific law, clinical governance or partner requirements:

- CareOS complete build specification, page 27: FHIR is an external representation; use versioned mappings/profiles, validation and provenance, and do not initially expose a broad generic server.
- [HL7 FHIR Patient](https://hl7.org/fhir/R5/patient-definitions.html), [RelatedPerson](https://hl7.org/fhir/R5/relatedperson.html), [Consent](https://hl7.org/fhir/R5/consent.html), [Flag](https://hl7.org/fhir/R5/flag.html) and [FHIR datatypes](https://hl7.org/fhir/R5/datatypes.html) for merge links, relationship/contact separation, policy-context consent, concise flags and partial-date representation.
- [HL7 Gender Harmony guidance](https://hl7.org/xprod/ig/uv/gender-harmony/) for keeping gender identity, recorded sex or gender, sex parameter for clinical use, pronouns and name-to-use semantically distinct.
- [NIST SP 800-63A-4](https://pages.nist.gov/800-63-4/sp800-63a.html) for digital identity proofing, evidence validation, identity verification, exception handling and data minimization. It informs portal linkage only and is not a label for ordinary patient registration.
- [HealthIT.gov SAFER Patient Identification Guide](https://healthit.gov/wp-content/uploads/2025/01/Safer-Guide-6.-Patient-Identification-Final.pdf) for search-before-create, daily duplicate/error-queue governance, temporary identities and remediation of duplicates, overlaps and overlays.
- [HHS minimum-necessary guidance](https://www.hhs.gov/hipaa/for-professionals/privacy/guidance/minimum-necessary-requirement/index.html) and [personal-representative guidance](https://www.hhs.gov/hipaa/for-professionals/privacy/guidance/personal-representatives/index.html) as US examples of purpose-limited access and authority derived from applicable law. They are not treated as universal jurisdiction rules.

## Review outcome

The prior `ACCEPT_WITH_CHANGES` direction has been incorporated as `m3-product-definition-draft-2`, but the proposed changes are not yet accepted. An accountable reviewer should identify their role and return one of:

- `ACCEPT_RECOMMENDED_CHANGES_FOR_MOCKUP` — accepts this exact draft for synthetic high-fidelity mockup preparation only;
- `ACCEPT_WITH_FURTHER_CHANGES` — lists exact changes and their accountable owner; or
- `REJECT` — states the conflicting requirement.

Even `ACCEPT_RECOMMENDED_CHANGES_FOR_MOCKUP` does not authorize a production migration, permission, registry entry, API, worker, generated client or live P3 screen. Those require a later checksum-bound M3 input package and separate approval evidence.
