# Module 1 data dictionary and validation candidate

**Artifact kind:** `data-dictionary-and-validation`  
**Status:** `CANDIDATE_FOR_APPROVAL`  
**Candidate version:** `m1-candidate-1`  
**Approval:** Not granted

## Decision baseline

The candidate uses UTF-8, Unicode NFC normalization, RFC 9562 UUIDv7 identifiers, UTC instants, IANA timezones, ISO 3166-1 alpha-2 country codes, BCP 47 locales, and inclusive-start/exclusive-end effective ranges. API names are `camelCase`; PostgreSQL names are `snake_case`. Blank strings normalize to null only for optional fields. Control characters other than tab/newline in bounded free text are rejected.

### Global columns and sensitivity classifications

Every tenant-owned row has `id uuid`, `organization_id uuid not null`, `status`, `lock_version bigint`, `created_at timestamptz`, `created_by uuid`, `updated_at timestamptz`, and `updated_by uuid`; historical rows add `supersedes_id`, `effective_from`, and nullable `effective_to`. Tenant links use `(organization_id, id)` composite foreign keys and forced RLS. Server time owns timestamps and revisions.

| Classification | Candidate handling                                                                                                                                                        |
| -------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `PUBLIC`       | Approved organization display information; still tenant-authorized for mutation.                                                                                          |
| `INTERNAL`     | Operational configuration visible to authorized members. Excluded from public responses.                                                                                  |
| `CONFIDENTIAL` | Contact, governance, reason, and evidence metadata. Minimum-necessary projection and masked list views.                                                                   |
| `RESTRICTED`   | Security decisions, export authorization, provider references, and sensitive audit payload. Explicit permission, recent authentication where mapped, no application logs. |

No M1 field contains credentials, MFA secrets, session values, clinical notes, patient data, signed URLs, or raw provider locations. Reasons are `CONFIDENTIAL`, 10-500 Unicode characters, NFC-normalized, control-character-free, and never copied to outbox payloads unless an event schema explicitly permits a bounded reason code.

### Entity dictionary

| Entity                      | Candidate fields and constraints                                                                                                                                                                                                                                                                                                                                                   |
| --------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `organization`              | `legal_name` 2-200 required; `display_name` 2-120 required; `trading_name` 2-160 optional; `organization_type` in `care_provider, care_network, administrative`; `country_code` required; `timezone` required IANA; `locale` required BCP 47; lifecycle status. Names are trimmed/NFC and reject markup/control characters.                                                        |
| `organization_identifier`   | `identifier_type` registry key; `assigning_authority` 2-160; `value_normalized` 1-128; jurisdiction country optional; `verification_status`; evidence reference optional; `is_primary`; issue/expiry/effective range; supersession. Unique `(organization_id, type, authority, value_normalized)` across non-revoked history and no overlapping primary identifier per type.       |
| `organization_address`      | `address_type` in `registered, postal, service, billing`; structured lines (1-120 each, maximum 4), locality/region 1-100, postcode 1-24, country required; validation status/source; primary flag; effective range. No geocode is stored in M1 candidate 1.                                                                                                                       |
| `organization_contact`      | `channel` in `email, phone, web`; `purpose` registry key; normalized value maximum 254 email/32 E.164 phone/2048 HTTPS URL; verification state; primary/preferred flags; effective range. Raw value is `CONFIDENTIAL`; list projection is masked except approved public contacts.                                                                                                  |
| `international_setting`     | One versioned row containing country, IANA timezone, BCP 47 locale, language, ISO currency, week start `MONDAY..SUNDAY`, and effective time. Display patterns come from locale libraries and are not stored as arbitrary format strings.                                                                                                                                           |
| `governance_responsibility` | `responsibility_type` in `clinical, privacy, security, billing`; either linked active membership or external contact reference, never both; escalation email/phone optional; effective range. Exactly one active primary responsibility per type is required for activation.                                                                                                       |
| `facility`                  | `facility_code` uppercase `[A-Z0-9][A-Z0-9_-]{1,31}` unique per organization; legal/display name bounds as organization; `facility_type` registry key; optional organization address/contact references; timezone inherits organization unless an explicit IANA override; lifecycle status and closure reason.                                                                     |
| `organization_unit`         | `unit_code` same code rule and unique within facility; `unit_type` in `department, unit`; name 2-120; facility required; parent unit optional within same facility; maximum hierarchy depth 8; effective range/lifecycle. Cycles and active child of inactive parent are rejected.                                                                                                 |
| `service_location`          | `location_code` unique within facility; type `physical, virtual`; name 2-120; facility required; unit/parent optional; physical location requires address reference, virtual requires bounded `virtual_service_type` but no meeting URL; capacity integer 1-100000 optional; accessibility notes maximum 500 `INTERNAL`.                                                           |
| `operating_hours_batch`     | Target type/id, timezone, version/status/effective range. Child intervals use ISO weekday, local start/end minute, explicit `ends_next_day`, and no overlap. Holiday exceptions use local date, `closed` or replacement intervals, label 1-120, and reason code. A batch is saved/activated atomically.                                                                            |
| `service_definition`        | `service_code` uppercase code unique per organization; display name 2-120; clinical name 2-160 optional; description maximum 1000; coding system/code optional pair; active governance-contact owner required for clinical services; lifecycle and retirement reason.                                                                                                              |
| `service_assignment`        | Service, facility, optional location, capacity/availability metadata, status/effective range, prerequisites registry keys, and reason. Active/scheduled ranges for the same service/target cannot overlap. Parent service/facility/location must be lifecycle eligible throughout the range.                                                                                       |
| `identifier_scheme`         | Stable `scheme_key`, scope in `organization, facility, service`; description and lifecycle. Child immutable version stores prefix maximum 20, regex pattern maximum 200 from a safe allowlist, alphabet, optional check-digit algorithm registry key, sequence start/increment, padding 1-20, preview samples, effective time, and lifecycle. One active version per scheme/scope. |
| `configuration_version`     | UUID, human display number, parent active version, baseline revision/digest, status, change summary, reason, requested effective time, validation result, submitter/checker/activator evidence, and activation/supersession times. Change items reference entity/type/revisions rather than copying secrets.                                                                       |
| `configuration_validation`  | Configuration version/revision, gate-definition version, outcome, safe reason/remediation code, evidence references, evaluated/expiry time, and result digest. Immutable after completion.                                                                                                                                                                                         |
| `configuration_approval`    | Exact candidate/result digest, maker/checker IDs, decision/reason, decided/expiry time, policy version, and status. Immutable; expiry or invalidation creates evidence rather than rewriting the decision.                                                                                                                                                                         |
| `history_export_job`        | Requester, permission/purpose/legal-basis key, filter snapshot digest, format, field projection, locale/timezone, state, snapshot time, counts/size, artifact opaque ID/digest, ready/expiry/disposal time, failure code, and policy versions. Provider key/URL and exported content are never exposed.                                                                            |

### Validation catalogue

Stable problem codes use `m1.<entity>.<condition>` and RFC 9457 responses. Field violations use JSON Pointer paths.

| Code family                                          | Enforcement and response                                                                                      |
| ---------------------------------------------------- | ------------------------------------------------------------------------------------------------------------- |
| `m1.field.required`, `.length`, `.format`, `.enum`   | Browser guidance plus service validation; database `NOT NULL`, `CHECK`, registry FK where possible. HTTP 400. |
| `m1.duplicate`                                       | Normalized unique/exclusion constraint plus safe preflight. HTTP 409 without disclosing a hidden record.      |
| `m1.effective.overlap`                               | PostgreSQL range/exclusion or serialized trigger and service validation. HTTP 409.                            |
| `m1.hierarchy.cycle`, `.depth`, `.parent_ineligible` | Recursive database check in the authorized transaction plus service preview. HTTP 409.                        |
| `m1.lifecycle.invalid_transition`                    | State-transition registry/service and database invariant. HTTP 409.                                           |
| `m1.revision.stale`                                  | Strong ETag/`If-Match` and `lock_version`. HTTP 412 with current ETag only when read permission remains.      |
| `m1.authorization.denied`                            | Server/database authorization. Hidden operations return 404; explicit operations return 403. No field detail. |
| `m1.readiness.blocked`, `.stale`                     | Server gate evaluator bound to candidate revision. HTTP 409.                                                  |
| `m1.idempotency.conflict`                            | Actor/tenant/operation key and request digest. HTTP 409; exact replay returns the original result.            |
| `m1.dependency.unavailable`                          | Fail-closed adapter/readiness check. HTTP 503 with bounded retry guidance and correlation ID.                 |

Filters and sorts are operation-specific allowlists. Text search is normalized, length-bounded to 2-100 characters, parameterized, and cannot search `RESTRICTED` fields. Cursor values are opaque, signed, tenant/operation/filter-bound, and expire after 15 minutes.

## Verification and acceptance

- Migration tests cover all constraints, forced RLS, composite links, overlap, hierarchy, lifecycle, append-only history, and cross-tenant/direct-SQL attacks.
- API tests cover normalization, every stable validation code, hidden-resource behavior, strong concurrency, idempotency, filter/sort rejection, and minimum-necessary projections.
- Screen fields in M1-01 through M1-23 trace to this dictionary and to one API/storage or explicitly derived definition.
- Jurisdiction-specific extensions use a versioned registry and cannot weaken tenant, evidence, security, or retention invariants.

## Approval boundary

These names, bounds, classifications, enumerations, and retention hooks are the candidate implementation contract. They remain non-authorizing until data, privacy, security, clinical-governance, API, database, and product authorities accept the exact package digest.
