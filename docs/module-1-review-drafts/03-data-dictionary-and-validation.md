# Module 1 data dictionary and validation review brief

**Artifact kind:** `data-dictionary-and-validation`  
**Status:** `DRAFT_NOT_APPROVED`  
**Draft version:** `review-draft-1`  
**Approval authority:** Owner decision required

## Proposed review baseline

The final dictionary must define every stored, derived, displayed, filtered, sorted, imported, audited, and exported field. Each definition needs a stable key, business meaning, data type, required/default/null semantics, normalization, bounds, allowed values, uniqueness scope, sensitivity, authorization scope, mutability by lifecycle state, effective-time rules, provenance, retention/redaction, API representation, and stable validation error code.

The existing organization reference aggregate proves only `organization_id`, legal name, display name, ISO-like country code, IANA timezone, lifecycle status, `lock_version`, actor, and timestamps. Its current bounds are reference evidence, not final product approval.

### Candidate entity families for owner review

| Family                                | Minimum questions the approved dictionary must answer                                                                                                                                     |
| ------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Organization profile                  | Legal/display/trading identity, organization type, country/jurisdiction, timezone, locale, lifecycle, registration status, governance status, and correction/version semantics.           |
| Organization identifiers              | Identifier type, assigning authority, value, jurisdiction, verification/evidence, primary flag, issue/expiry/effective range, uniqueness scope, supersession, and display/redaction.      |
| Addresses                             | Address type, structured lines, locality/region/postcode/country, physical/postal/service use, geospatial decision, validation source, primary rule, effective range, and sensitivity.    |
| Contacts                              | Channel/type, normalized value, purpose, availability, primary/preferred rule, verification, consent relevance, effective range, sensitivity, and masking.                                |
| International settings                | Country, timezone, locale, language, date/time/number/currency formats, week start, DST behavior, defaulting, and effective-change impact.                                                |
| Governance contacts                   | Clinical/privacy/security/billing responsibility type, linked person/account or external contact decision, escalation channels, effective range, required coverage, and visibility.       |
| Facilities                            | Stable code, legal/display name, type, parent organization, lifecycle, address/contact links, timezone exception decision, readiness, external identifiers, and closure reason.           |
| Departments and units                 | Type, name/code, facility, parent, hierarchy depth, effective range, lifecycle, reparenting constraints, descendant impact, and historical path.                                          |
| Locations                             | Physical/virtual type, facility/department/parent, name/code, address or virtual endpoint metadata, capacity, accessibility, lifecycle, and effective range.                              |
| Operating hours and holidays          | Target scope, local weekday/date, start/end, overnight representation, timezone, DST policy, exception/holiday type, closure reason, effective range, and atomic batch identity.          |
| Services                              | Stable code, display/clinical name, description, coding system, clinical owner, lifecycle, eligibility metadata, sensitivity, and retirement reason.                                      |
| Facility/location service assignments | Service, facility/location, effective range, lifecycle, capacity/availability, prerequisites, exclusions, reason, conflict rules, and supersession.                                       |
| Identifier schemes                    | Scheme key/scope, immutable version, prefix, pattern, alphabet, check digit, sequence/allocation strategy, preview, concurrency boundary, activation, supersession, and retirement.       |
| Configuration and activation evidence | Parent version, change items, baseline/new revision, actor/reason, validation run/result, submitted/approved/activated times, maker/checker, effective time, and superseded version.      |
| History, audit, and export jobs       | Filter snapshot, purpose/legal basis, requester, authorization evidence, format, field projection, redaction, row limits, state, digest, expiry, retention, and download/access evidence. |

### Global persistence baseline to approve or replace

- All new identifiers use RFC 9562 UUIDv7 through the shared generator or a verified PostgreSQL 18 default.
- Every tenant-owned record has a non-null `organization_id`, forced RLS, and composite tenant foreign keys for cross-record links.
- Mutable aggregates define `lock_version`, created/updated actor and server-owned timestamps, constrained state, explicit reason where governed, and strong ETag mapping.
- Historical/evidence records are append-only or superseded; cascade deletion cannot erase attribution or effective history.
- Effective ranges use one canonical inclusive/exclusive convention, server time, timezone-aware instants, overlap constraints, and explicit open-ended representation.
- Free text has explicit Unicode normalization, control-character rejection, length, sensitivity, search/index, logging, export, and HTML/rendering rules.
- Controlled terms use migration-owned versioned registries rather than runtime free-form administration unless an approved workflow explicitly says otherwise.

### Validation catalogue shape

For every rule, the approved bundle should record:

| Field       | Required content                                                                                                                |
| ----------- | ------------------------------------------------------------------------------------------------------------------------------- |
| Rule key    | Stable machine-readable key and owner.                                                                                          |
| Scope       | Entity, field combination, lifecycle states, jurisdiction, and effective dates.                                                 |
| Condition   | Exact deterministic rule and normalization order.                                                                               |
| Enforcement | Browser guidance, API validation, PostgreSQL constraint/trigger, or combination.                                                |
| Failure     | HTTP status, RFC 9457 code/title/detail policy, field pointer, and safe correlation behavior.                                   |
| Conflict    | Whether the case is validation, stale revision, duplicate, overlap, hierarchy cycle, lifecycle conflict, or dependency failure. |
| Evidence    | Whether the failed/successful evaluation is audited, retained in activation results, or excluded from logs.                     |

## Owner decisions required

1. Approve canonical field names, meanings, types, requiredness, lengths, formats, enumerations, normalization, and API/storage mappings.
2. Approve jurisdiction-specific registration, address, contact, locale, identifier, retention, privacy, and correction rules.
3. Approve uniqueness, effective-range overlap, hierarchy, primary-designation, and supersession constraints for every family.
4. Classify sensitivity, minimum-necessary projections, masking/redaction, audit visibility, logging, search, export, and retention for every field.
5. Decide which reference organization fields and bounds are accepted, replaced, or split into versioned child records.

## Acceptance checklist

- [ ] Every M1 screen field and every API/schema/database field traces to one dictionary entry.
- [ ] Every validation rule has a stable error code and named enforcement layers with no browser-only integrity rule.
- [ ] Tenant, actor, revision, effective-time, lifecycle, reason, history, sensitivity, and retention conventions are complete.
- [ ] PostgreSQL constraints/indexes and API filter/sort access paths can be derived without guessing.
- [ ] Privacy, security, clinical-governance, data, API, and database owners record review outcomes.
- [ ] A named authority records approval evidence for the exact checksum-bound bundle.
