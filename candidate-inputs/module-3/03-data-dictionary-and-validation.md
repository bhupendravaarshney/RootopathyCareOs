# Module 3 data dictionary and validation candidate

**Artifact kind:** `data-dictionary-and-validation`  
**Status:** `CANDIDATE_FOR_APPROVAL`  
**Candidate version:** `m3-candidate-1`  
**Approval:** Not granted

## Decision baseline

This candidate defines the build specification's exact **14 core entity families** for an organization-scoped Patient Registry. It reuses M1 users, sessions, MFA, organizations, facilities, canonical RBAC/configuration/audit/outbox/idempotency/export and platform document/notification/job capabilities. It creates no second authentication, membership, permission, audit, notification, document or export source of truth.

A patient can exist without a portal account. Patient contact, related-person fact, proxy authority and portal linkage are independent. Search happens before create, duplicate matching never auto-merges, and all supplied data carries provenance and verification.

### Common representation

- PostgreSQL uses `snake_case`; APIs use `camelCase`. IDs are RFC 9562 UUIDv7. Server times are UTC `timestamptz`; user-entered civil dates retain precision/certainty; display uses an approved IANA timezone.
- Tenant-owned records have `organization_id`, `id`, status where applicable, `lock_version`, created/updated server time and actor, provenance/source, classification and policy/schema version. Composite `(organization_id, id)` references are mandatory.
- Forced RLS applies to every tenant table; runtime roles cannot own tables, bypass RLS or mutate migration-owned registries. Exact operation context is required for guarded transitions.
- Effective ranges are inclusive-start/exclusive-end. Overlap rules use database constraints where expressible and transaction guards otherwise. Effective or referenced records are ended, superseded, corrected, merged or entered in error—not physically deleted.

### Classification

| Class          | Examples                                                                                                                            | Baseline handling                                                                                    |
| -------------- | ----------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------- |
| `INTERNAL`     | organization-local opaque ID, lifecycle, safe queue age/status                                                                      | Tenant/resource permission and purpose; safe telemetry only.                                         |
| `CONFIDENTIAL` | names, date of birth, address, contact, preferences, ordinary relationship                                                          | Masked/minimum projection, explicit source permission, excluded from logs/events.                    |
| `RESTRICTED`   | national/provider identifier, authority/consent evidence, privacy restriction, safety flag, match material, merge comparison/reason | Narrow operation and field policy, purpose, access evidence, MFA/recent authentication where mapped. |

Raw identifier/contact values, evidence content, tokens, match keys, free-text clinical detail, protected reasons, signed URLs and provider coordinates are prohibited in routes, cursors, logs, analytics, audit/outbox payloads and notification metadata.

### Core entities 1–4: patient identity and contact

| # / entity              | Candidate fields and invariants                                                                                                                                                                                                                                                                                                                                                                                                                                                                     |
| ----------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1 `patient_profiles`    | Organization-local patient number; lifecycle; official/usual/former/temporary display facts with provenance; birth-date value, precision and certainty; deceased state/date precision; optional separately classified administrative recorded sex, gender identity, pronouns/name-to-use references; merge target; temporary-identity reason; current revision. One organization only. `inactive` is operational, `deceased` is a verified fact, `merged` resolves to exactly one acyclic survivor. |
| 2 `patient_identifiers` | Patient; scheme/version; issuer/jurisdiction/type; encrypted normalized value where recovery is allowed; masked display; HMAC lookup key version/digest; issue/effective/end dates; verification; primary flag; supersedes/corrects lineage. Active uniqueness follows the exact scheme scope. At most one overlapping primary per patient/scheme/context.                                                                                                                                          |
| 3 `patient_contacts`    | Patient; channel/use/purpose; encrypted normalized value; masked display; effective interval; preferred/primary; verification method/time/source; confidentiality; supersession. A verified contact is not notification consent and is not portal identity proofing.                                                                                                                                                                                                                                |
| 4 `patient_addresses`   | Patient; type/use/purpose; structured lines/locality/region/postcode/country; optional validation result/source; confidentiality; effective interval; preferred/primary; provenance/supersession. Search and list projections exclude unrestricted raw address.                                                                                                                                                                                                                                     |

Names are supporting effective child records within the patient aggregate unless the approved implementation specification introduces a separate table. Supplied display spelling is preserved; search normalization never overwrites it.

### Core entities 5–9: preference, authority, consent, privacy and safety

| # / entity                    | Candidate fields and invariants                                                                                                                                                                                                                                                                                                                                                                                          |
| ----------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| 5 `communication_preferences` | Patient; purpose; channel; allow/deny/prefer decision; language/BCP 47; accessible format; quiet interval and timezone; source; effective interval. Preference evaluation is independent from legal/consent authority and provider deliverability.                                                                                                                                                                       |
| 6 `caregiver_relationships`   | Patient; related-person opaque reference and relationship fact; provenance/effective interval. Separate supporting `patient_authority_grants` record grantor/grantee, authority type/source/evidence, jurisdiction, purpose/action/data scope, verification/review, effective interval and revocation. Separate `patient_portal_links` bind an active authority/self proof to a user. Relationship alone grants nothing. |
| 7 `patient_consents`          | Patient/subject; grantor and authority evidence; decision; purpose; grantee/role; action; data scope; policy/version/digest; regulatory/legal basis where applicable; source directive document/reference; derivative marker; effective/expiry/withdrawal/supersession; verification. Withdrawal is prospective and immutable.                                                                                           |
| 8 `privacy_restrictions`      | Patient; request; accountable decision; restriction type; field/resource/purpose/channel scope; policy/version; effective interval; reason/evidence reference; projection consequence; ending/supersession. Source facts remain unchanged. No M3 break-glass field or operator override exists.                                                                                                                          |
| 9 `patient_safety_flags`      | Patient; governed category; severity; concise coded statement; source-detail reference; provisional/verified state; author/verifier; visibility; effective/review/expiry; acknowledgement evidence; resolution/supersession/error lineage. It cannot replace detailed allergy/condition/order-checking records or function as a message.                                                                                 |

Authority grants and portal links are supporting tables inside the caregiver/proxy entity family and remain organization-bound. Exact supporting-table inventory is frozen only in the later implementation specification; the semantic separation is mandatory.

### Core entities 10–14: matching, merge and registration

| # / entity                        | Candidate fields and invariants                                                                                                                                                                                                                                                                                                                                                 |
| --------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 10 `patient_match_keys`           | Patient; permitted factor type; normalization/detector version; organization-derived HMAC key version/digest; blocking bucket; effective interval. No raw value or global index. Key rotation retains versioned equality behavior.                                                                                                                                              |
| 11 `patient_duplicate_candidates` | Ordered patient pair; factor/result digest; detector/version; band `confirmed_identifier_conflict, high_review, possible_review, below_display_threshold`; score only when calibrated; queue priority/SLA; lease/assignment; source revisions; disposition/reason; dismissed detector/material-source snapshot; reopen evidence. Unique active unordered pair.                  |
| 12 `patient_merge_requests`       | Survivor and duplicate; source revisions; requested field dispositions; downstream-reference inventory/digest; reason; maker; submission/expiry; state. Cross-organization, self-pair, cycle and second active target are rejected in database/transaction guards.                                                                                                              |
| 13 `patient_merge_decisions`      | Exact request/revision/impact digest; independent checker; approve/reject decision; reason; MFA/recent-auth evidence reference; expiry; executor binding; execution/correction evidence. Append-only and consumable once.                                                                                                                                                       |
| 14 `patient_registration_runs`    | Registration source/supplier relationship/purpose/facility; creator; lifecycle; idempotency; current step; staged minimum data; duplicate search/disposition and detector version; validation digest; expiry; completed patient. Default inactivity expiry 24 hours. Abandoned/rejected runs never reopen; only creator or `patient.registration.manage` resumes before expiry. |

Urgent registration creates a clearly temporary patient identity with a server-issued identifier and immediate reconciliation task. It never selects an ambiguous existing record merely to bypass duplicate review.

### Demographic and date rules

- Patient activation requires the internal identifier, lifecycle/provenance and at least one name representation or explicit unnamed/temporary state. No fabricated name, date or gender/sex value.
- Civil date is `(value, precision, certainty)`: precision `year`, `month`, or `day`; certainty `exact`, `estimated`, or `unknown`. Unknown has no synthetic value. Full dates must be calendar-valid and not implausibly future. Estimated/partial matching uses an explicit detector rule.
- Newborns support temporary name state, birth order where relevant and guardian-source provenance. Prenatal/fetal patient profiles are unavailable. Deceased state never deletes identity/history and follows approved post-death access/communication rules.
- Administrative recorded sex, gender identity, pronouns, name-to-use and sex parameter for clinical use are distinct versioned concepts. Clinical sex parameters are contextual clinical data and cannot be inferred from demographic values.

### Identifier and matching rules

- Each identifier scheme version defines issuer, jurisdiction, normalization, check rule, uniqueness scope, display mask, evidence and lifecycle. Unknown/inactive scheme versions deny activation.
- Search normalization is Unicode/script/locale aware and versioned. Email/domain and phone/address handling require approved libraries/policy. Phonetic/transliteration rules are explicit factors, never replacements for original values.
- Matching starts with deterministic and explainable probabilistic factors. Numeric thresholds remain disabled until approved calibration evidence exists. ML, biometric, national/provider lookup and cross-organization matching are unavailable.
- Candidate explanation returns factor categories and safe comparisons only if the caller could read those fields independently. Query size, result count, fuzziness and rate are bounded to prevent enumeration.

### Validation catalogue

| Area                 | Required server validation                                                                                                                                     |
| -------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Tenant/context       | Authorized actor, active organization/purpose, forced RLS context, composite tenant relationships, hidden cross-tenant denial.                                 |
| Registration         | Source/purpose, creator/resume authority, expiry, current step, search-before-create, duplicate disposition, validation digest and exact revision.             |
| Demographics         | Required state rather than fabricated value, partial/estimated date validity, newborn/deceased rules, vocabulary/version and provenance.                       |
| Contact/address      | Channel/use format, normalization/version, effective overlap, primary/preferred uniqueness, verification and confidentiality.                                  |
| Identifier           | Active scheme version, issuer/jurisdiction, check rule, scoped uniqueness, encryption/HMAC material, primary overlap and correction lineage.                   |
| Proxy                | Relationship separate from active authority; grantor eligibility, evidence, scope, effective time, local-law policy and no authority escalation.               |
| Consent/privacy      | Known policy/purpose/action/data/actor, grantor authority, time, directive/derivative relationship, restriction consequence and prospective withdrawal.        |
| Safety               | Approved category/severity, author/verifier eligibility, source reference, provisional SLA, visibility, acknowledgement and no unsupported narrative.          |
| Duplicate/merge      | Calibrated detector, exact pair/revisions, safe explanation, maker/checker/executor separation, impact digest, survivor rule, cycles/concurrency and lineage.  |
| Portal/communication | Proofing and active authority, MFA, verified destination, purpose preference plus legal authority plus provider availability, template/version and quiet time. |
| Export/FHIR          | Approved projection/profile, purpose, fields/security labels, tenant, snapshot, validation, provenance, artifact bounds and access/retention policy.           |

All HTTP validation uses RFC 9457 Problems with stable codes and JSON pointers. Browser validation is advisory only. Strong revisions use ETag/If-Match; retryable creates, submissions, decisions and execution use actor/tenant/operation-scoped idempotency.

## Verification and acceptance

- Migration tests must prove exact entity/supporting-table inventory, UUIDv7 defaults, forced RLS, composite FKs, restricted grants, no missing tenant policy, direct-SQL transition guards and V1-forward migration.
- Domain/database tests cover partial dates, unknown values, effective overlap, scheme uniqueness, match-key secrecy, pair ordering, detector calibration gate, no auto-merge, cycle/concurrency rejection, immutable evidence and correction lineage.
- HTTP/security tests cover every field projection, guessed ID, query bound, source permission, proxy/consent/privacy consequence, recent-auth/MFA, stale revision, idempotency and cross-tenant attack.
- Property/fuzz tests cover Unicode normalization, identifier/phone/email/date parsing, cursor/signature input, spreadsheet cells and bounded free text without emitting sensitive values.

## Approval boundary

This dictionary is a candidate semantic contract. Exact physical columns, indexes and supporting tables are frozen only by a later accepted implementation specification. Local scheme, terminology, proofing, proxy, consent, privacy, safety, retention, export and FHIR catalogues must be accepted before their dependent capabilities activate.
