# Module 2 data dictionary and validation candidate

**Artifact kind:** `data-dictionary-and-validation`  
**Status:** `CANDIDATE_FOR_APPROVAL`  
**Candidate version:** `m2-candidate-1`  
**Approval:** Not granted

## Decision baseline

This candidate defines the build specification's exact 44-table Module 2 baseline without collapsing Person, User, Workforce Member, Engagement, Practitioner, Credential, Scope, Assignment, Service Assignment, Availability, or Access. It reuses the accepted Module 1 `users`, membership/RBAC, organization hierarchy/service, document-evidence, audit/outbox, idempotency, and export foundations; it creates no second authentication or role/permission source of truth.

Values use UTF-8, Unicode NFC normalization, RFC 9562 UUIDv7 identifiers, UTC instants, IANA timezones, ISO 3166-1 alpha-2 countries, BCP 47 locales, and inclusive-start/exclusive-end ranges. API fields are `camelCase`; PostgreSQL fields are `snake_case`. Server time owns lifecycle, freshness, and revision decisions.

### Global columns, tenant links, and sensitivity

Every tenant-owned row includes `id uuid`, `organization_id uuid not null`, `status` where stateful, `lock_version bigint`, `created_at timestamptz`, `created_by uuid`, `updated_at timestamptz`, and `updated_by uuid`. Effective/versioned rows add `effective_from`, nullable `effective_to`, and `supersedes_id` where applicable. All tenant relationships use `(organization_id, id)` composite foreign keys. Every tenant table has enabled and forced RLS; application connections cannot own tables or bypass RLS.

| Class | Workforce examples | Handling |
| --- | --- | --- |
| `INTERNAL` | Workforce ID, profession, organizational assignment, work contact, availability | Tenant permission and scope; only task-relevant projections. |
| `CONFIDENTIAL` | Legal/chosen name, birth date, home contact/address, engagement terms, reasons, qualification/registration metadata | Masked list projection, explicit operation permission, no analytics/log payload. |
| `RESTRICTED` | Credential documents, verification notes, scope restrictions, suspension/offboarding evidence, match keys, access/export decisions | Purpose, narrow permission, audited detail; recent authentication/MFA where mapped; no unrestricted search. |

The candidate permits workforce members aged 18 or older only. Date of birth is collected only where required for identity/credential checks, returned as a masked age-band outside authorized detail, and never included in directory search results. A jurisdiction-specific policy may raise the threshold or remove birth-date collection, but cannot permit minors without a separately approved safeguarding design.

### Person and organization link tables (1–7)

| # / table | Candidate fields and constraints |
| --- | --- |
| 1 `person_profiles` | Stable person ID; legal given/family names 1–100, optional middle names 1–150, chosen/display name 1–150, birth date optional/restricted, pronouns optional registry key, preferred locale. No organization ID: the global person row is accessible only through an authorized organization link and security-definer operations that do not permit global browsing. Core identity correction creates evidence and increments revision. |
| 2 `person_profile_aliases` | Person, alias type `former_name, alternate_spelling, professional_name`, bounded name parts, effective range, source/verification code. Unique normalized alias per person/range; history is retained. |
| 3 `person_contacts` | Person, channel `email, phone`, use `work, personal, emergency`, normalized encrypted value, verification state, primary/preferred flags, effective range. Personal values are restricted; list projections mask values. No notification consent is inferred. |
| 4 `person_addresses` | Person, use `home, correspondence`, structured lines, locality/region/postcode/country, validation state/source, effective range. Home address is restricted and omitted from ordinary workforce profile/directory. |
| 5 `organization_person_links` | Organization, person, organization-local display label, relationship status `candidate, active, ended, merged`, source workforce member, effective range. Unique active organization/person link. This is the only normal tenant entry point to a person. |
| 6 `person_match_keys` | Organization link, key type, HMAC key version, deterministic keyed digest, optional blocking bucket, active range. Raw identity values and unhashed global indexes are forbidden. Key rotation is versioned. |
| 7 `person_merge_requests` | Organization, retained/discarded organization-person links, requester, candidate evidence digest, decision state, independent reviewer, reason, timestamps. Cross-organization merges and automatic merges are prohibited; completed decisions are immutable. Candidate 1 supports link consolidation only and does not rewrite the underlying global person without a later privacy-approved operation. |

### Workforce, engagement, and practitioner tables (8–15)

| # / table | Candidate fields and constraints |
| --- | --- |
| 8 `workforce_members` | Organization-person link, pathway `clinical, non_clinical`, stable organization-local member number, lifecycle `draft, submitted, active, suspended, offboarding, offboarded`, activated/offboarded times, current readiness reference. One live member per organization-person link; account linkage is not stored here. |
| 9 `workforce_identifiers` | Member, type registry key, authority, normalized value, masked display, issue/expiry/effective range, verification state, primary flag, supersession. Unique non-revoked value per organization/type/authority and no overlapping primary range. |
| 10 `employment_engagements` | Member, type `employee, contractor, volunteer, visiting, agency`, employment category registry key, start/end, organization membership manager reference optional, work email/phone references, payroll/external reference encrypted optional, status. Engagement gaps/overlaps follow registry policy; rehire creates a new engagement. Salary/bank/tax data is out of scope. |
| 11 `practitioner_profiles` | Member, profession registry entry/version, practitioner lifecycle `draft, active, suspended, ended`, regulated flag, clinical title, primary registration reference optional. Exists only for clinical pathway; never grants access, scope, assignment, or eligibility. |
| 12 `professional_registrations` | Practitioner, regulator registry/version, registration type, encrypted normalized number plus masked display, jurisdiction, issue/valid-from/expiry dates, state `draft, evidence_pending, submitted, verified, suspended, revoked, expired, superseded`, authority status code, decision reference. Renewal supersedes; number and authority become immutable after verification. |
| 13 `qualifications` | Member/practitioner, qualification registry/version, awarding body, country, awarded date, optional expiry, result/classification bounded text, state, supersedes ID, verification reference. Correction/supersession preserves prior evidence. |
| 14 `practitioner_specialties` | Practitioner, specialty registry/version, designation `primary, secondary`, effective range, evidence/decision reference. At most one overlapping primary specialty; a specialty does not itself grant scope. |
| 15 `practitioner_credentials` | Practitioner/member, credential type registry/version, issuer, issue/expiry, lifecycle, risk tier, supersedes ID, submission/review timestamps, current verification. One logical credential may have versions; verified content is immutable. |

### Credential evidence and decisions (16–18)

| # / table | Candidate fields and constraints |
| --- | --- |
| 16 `credential_documents` | Credential/version, platform document evidence IDs, purpose `credential_verification`, declared media type/name/size/digest, quarantine/promotion state, retention class, uploaded actor/time. It stores no object key or signed URL. Only clean promoted evidence may be reviewed. |
| 17 `credential_scan_attempts` | Credential document, platform scan-attestation reference, attempt number, scanner identity/version/signature version, start/end, outcome `clean, infected, invalid, unavailable, failed`, bounded failure code. Append-only; timeout/error/stale signatures never mean clean. |
| 18 `credential_verifications` | Credential revision/digest, reviewer, decision `verified, rejected, more_information_required, returned_for_correction`, decision/reason code, protected note reference, evidence IDs/digests, policy/registry versions, decided time. Submitter/uploader cannot verify; decisions are append-only. |

### Scope and eligibility definition tables (19–24)

| # / table | Candidate fields and constraints |
| --- | --- |
| 19 `scope_definitions` | Organization catalogue definition linked to profession/specialty/service registry versions, name/code, jurisdiction, lifecycle, owner, effective range. It describes a template, not a practitioner's approval. |
| 20 `scope_requirements` | Scope definition/version, requirement type `registration, qualification, credential, specialty, supervision, training`, registry reference, mandatory flag, validity window, evidence rule. Ordered and immutable after definition activation. |
| 21 `scopes_of_practice` | Practitioner, scope definition/version, lifecycle, effective range, submitted revision/result digest, maker/checker decision, suspension/end reason. One active overlapping scope per practitioner/definition/context. |
| 22 `scope_activities` | Scope, controlled activity registry/version, service/context constraints, supervision mode, effective range. Activity codes are exact allow-listed entries, not free text. |
| 23 `scope_restrictions` | Scope, restriction registry/version, bounded display text, supervision/setting/volume constraints, effective range, imposed/released decision references. Restricted detail is purpose-audited. |
| 24 `workforce_assignments` | Member, facility required, department/location optional, assignment type/position registry versions, primary flag, effective range, lifecycle, predecessor/successor references. Hierarchy must be tenant-consistent and eligible for the entire range; prohibited overlaps use exclusion constraints. |

### Service, availability, and access scope tables (25–29)

| # / table | Candidate fields and constraints |
| --- | --- |
| 25 `practitioner_service_assignments` | Practitioner, M1 service and facility/location context, scope ID, supervision assignment optional, effective range, state `scheduled, active, suspended, ended, cancelled`, eligibility evidence digest. Parent service, assignment, registration, credential, and scope must cover the range. |
| 26 `availability_profiles` | Member, organization/facility context, IANA timezone, version, lifecycle/effective range, atomic batch revision. Availability is a working pattern, not appointment capacity or a booking promise. |
| 27 `availability_periods` | Profile version, ISO weekday, local start/end minute, explicit `ends_next_day`, optional location/type registry. Intervals within a batch cannot overlap after overnight expansion. |
| 28 `availability_exceptions` | Profile, local date/range, `unavailable` or replacement intervals, reason code, timezone snapshot. DST ambiguous/nonexistent local times are rejected unless the policy supplies an explicit offset choice; candidate 1 rejects them. |
| 29 `access_assignment_scopes` | Existing M1 access-assignment ID plus workforce member and optional facility/department/location scope, effective range, grant request/approval references. It narrows an existing canonical RBAC grant and cannot grant a role/permission by itself. Clinical scope and access remain independent. |

### Readiness, activation, lifecycle, configuration, export, and registries (30–44)

| # / table | Candidate fields and constraints |
| --- | --- |
| 30 `workforce_readiness_runs` | Member, pathway, exact member/configuration revision and digest, gate catalogue version, state, requested/completed/expiry times, blocker/warning counts, result digest. Completed runs are immutable and fresh for 15 minutes. |
| 31 `workforce_readiness_results` | Run, gate key/version, outcome `complete, warning, blocked, not_applicable`, safe reason/remediation code, evidence type/ID/digest, deep-link screen. Unique gate per run; no raw restricted evidence. |
| 32 `workforce_activation_requests` | Member/revision/result digest, maker, submitted reason, checker decision, activator, policy version, requested/decision/expiry/activation times, state. Approval is fresh for 30 minutes and cannot outlive readiness. |
| 33 `workforce_offboarding_requests` | Member, engagement end, effective time, reason/category registry, impact digest, maker/checker, access/assignment/service actions, handover reference, state. No patient/clinical narrative is copied. |
| 34 `workforce_lifecycle_transitions` | Member, from/to state, effective time, reason code/protected reference, source request, actor/service identity, revision, correlation. Append-only authoritative lifecycle evidence. |
| 35 `workforce_configuration_snapshots` | Immutable snapshot of controlled registry and workflow policy versions, parent snapshot, digest, maker/checker/activator, effective/superseded times. No credential/person values. |
| 36 `workforce_configuration_change_requests` | Snapshot parent, state, summary/reason, maker/checker/activator, validation/decision digests and expiry, requested effective time. Uses the M1 configuration approval pattern but a distinct workforce aggregate. |
| 37 `workforce_configuration_change_items` | Change request, registry/definition target type and ID, baseline/new revision/digest, change type, ordered safe field names. No secret/document/person content. |
| 38 `workforce_export_jobs` | Requester, purpose/legal-basis key, projection, filters/sort digest, snapshot time, format, state, row/size limits, artifact opaque ID/digest, ready/expiry/disposal, approval and policy versions. No provider key/URL. |
| 39 `credential_legal_holds` | Credential/document evidence, hold type/reference, authority, imposed/released actor/time/reason, state. Append-only decisions; hold blocks disposal but never restores access or changes credential eligibility. |
| 40 `practitioner_eligibility_evidence` | Practitioner/service/context/time, evaluator/catalogue versions, registration/credential/scope/assignment/supervision evidence IDs and digests, outcome/reasons, evaluated/expiry time. Immutable point-in-time evidence; never rewrites historical care. |
| 41 `workforce_notification_deliveries` | Member/credential/request reference, template/version, milestone, channel, recipient opaque reference, purpose, attempt, provider opaque ID, state/timestamps/failure code. No credential number, document, restriction, or clinical detail in message content/evidence. |
| 42 `workforce_registry_definitions` | Stable registry key, category, owner, value schema, lifecycle, review cadence. Canonical M1 role/permission definitions are explicitly excluded. |
| 43 `workforce_registry_entries` | Definition, stable entry key/code, display label, jurisdiction/context, lifecycle. Content changes occur only through versions. |
| 44 `workforce_registry_versions` | Entry, immutable versioned fields/digest, effective range, maker/checker decision, activated/superseded times. Referenced versions cannot be deleted. |

### Controlled registry categories

Candidate 1 permits only: profession, specialty, qualification type, regulator, registration type, credential type/risk tier, scope activity/restriction/requirement, employment category, assignment type/position, supervision mode, offboarding reason, and notification milestone/template metadata. It expressly excludes application roles, permissions, operations, audit events, free-form executable validation, MIME allowlists, and provider destinations; those remain separate migration-owned security/platform registries.

### Validation catalogue

Stable RFC 9457 codes use the `m2.<aggregate>.<condition>` namespace and JSON Pointer field paths.

| Code family | Enforcement / response |
| --- | --- |
| `m2.field.required`, `.length`, `.format`, `.enum` | Browser guidance, authoritative service validation, database `NOT NULL`/`CHECK`/registry FK. HTTP 400. |
| `m2.person.match_required`, `.decision_required` | Onboarding cannot create a person/member until a fresh organization-scoped match run has an explicit decision. HTTP 409. |
| `m2.person.possible_duplicate` | Returns opaque candidates and permitted match reasons only; never global/cross-tenant identity. HTTP 409 or decision-required response. |
| `m2.working_age.ineligible` | Birth date present and under approved threshold; hidden outside authorized flow. HTTP 422. |
| `m2.effective.overlap`, `.coverage_gap` | PostgreSQL range exclusion/serialized invariant plus preview. HTTP 409. |
| `m2.hierarchy.invalid`, `.parent_ineligible` | Same-tenant composite FKs and M1 hierarchy eligibility. HTTP 409/404 according to hidden policy. |
| `m2.credential.evidence_not_clean`, `.self_review`, `.expired` | Document/decision database guards plus service validation. HTTP 409. |
| `m2.scope.self_approval`, `.requirements_unmet` | Maker/checker and exact evidence/result digest. HTTP 409/403. |
| `m2.eligibility.blocked`, `.stale` | Server evaluator bound to context/time/catalogue/revisions. HTTP 409. |
| `m2.lifecycle.invalid_transition`, `.impact_changed` | Transition registry, locked aggregate, exact impact digest. HTTP 409. |
| `m2.revision.stale` | Strong ETag/`If-Match` and `lock_version`. HTTP 412 with current ETag only if read remains authorized. |
| `m2.idempotency.conflict` | Actor/tenant/operation key plus canonical request digest. HTTP 409; exact replay returns original result. |
| `m2.document.invalid`, `.scan_unavailable`, `.infected` | Platform document pipeline; failures never produce clean/previewable evidence. HTTP 400/422/503. |
| `m2.authorization.denied` | Live membership, permission, scope, assurance, tenant transaction, forced RLS. Hidden 404 or explicit 403 per operation. |
| `m2.dependency.unavailable` | Fail-closed platform/provider readiness with safe correlation and bounded retry advice. HTTP 503. |

Search is server-side, parameterized, NFC/case normalized as field policy allows, 2–100 characters, and limited to permitted member number/name/work contact/regulated identifier variants. Exact sensitive values use keyed match functions, never broad `LIKE`. Sorts and filters are operation-specific allowlists. Cursors are opaque, signed, tenant/actor-operation/filter/sort/page-size-bound, and expire after 15 minutes.

## Verification and acceptance

- Migration tests must account for all 44 named tables, standard columns, forced RLS, composite tenant links, effective-range exclusions, append-only evidence, immutable decisions, and direct-SQL/cross-tenant attacks.
- Contract/API tests cover normalization, validation codes, minimum-necessary projections, hidden resources, ETags, idempotency, cursor tampering, unsupported filters/sorts, document states, and exact date/time boundaries.
- Property/state tests cover match-key collision isolation, hierarchy/effective-range combinations, weekly interval expansion, expiry buckets, renewal/supersession, and point-in-time eligibility.
- Every visible field and derived value in M2-01 through M2-29 must trace to one dictionary field/evaluator or be marked presentation-only. No production hard-coded record is permitted.

## Approval boundary

These fields, classifications, enumerations, constraints, working-age choice, registry categories, and validation codes are candidates. Data, HR/credentialing, privacy/records, clinical governance, security, API/database, and product authorities must accept the exact package digest before migrations or runtime behavior use them.
