# Module 3 history, export, FHIR and retention policy candidate

**Artifact kind:** `history-and-export-policy`  
**Status:** `CANDIDATE_FOR_APPROVAL`  
**Candidate version:** `m3-candidate-1`  
**Approval:** Not granted

## Decision baseline

Patient summary, identity/evidence timeline, audit detail, reports, exports and FHIR exchange are distinct allow-listed projections. None may query arbitrary tables, expose generic before/after JSON or bypass source permissions, purpose, proxy/consent/privacy policy, field classification, tenant/RLS, assurance or retention.

M3 defines no universal retention duration, cohort threshold or FHIR release by guesswork. Missing jurisdiction/record-class schedules disable disposal; missing disclosure-control policy disables cohort reporting; missing partner/profile package disables FHIR exchange.

### Patient summary P3-13

`patient-summary-v1` contains the permitted organization-local identity header, current demographics with precision/provenance, masked primary contacts/addresses/identifiers, communication preference summary, active authority/portal state, consent/privacy consequences, active safety-flag summaries, duplicate/merge state and server-projected actions.

It distinguishes absent, unknown, unverified, withheld, ended and entered-in-error values where policy permits. It does not include raw match material, unrelated candidates, authority/consent evidence content, safety narrative, portal tokens, provider details or arbitrary audit data. Merged patient access resolves to the survivor with an explicit lineage marker and permission re-evaluation.

### Identity and evidence timeline P3-16

`patient-timeline-summary-v1` correlates only allow-listed events for one authorized patient:

- registration, temporary-identity reconciliation and lifecycle;
- identity/demographic/contact/address/identifier corrections and verification state;
- related-person, authority and portal-link decisions;
- consent/withdrawal and privacy request/decision/effective restriction;
- safety proposal/provisional/verification/acknowledgement/resolution;
- duplicate detection/disposition, merge request/decision/execution/correction case;
- authorized communication outcome, export evidence and profile-validated FHIR exchange.

Each item contains event family, safe title/state, server/effective time, permitted actor class/label, source/evidence opaque reference, policy/schema version, correlation ID and redaction marker. Raw audit/outbox payloads, search strings, match factors/score, contact/identifier values, evidence content, clinical narrative, message destination/body, export cells and FHIR content are excluded.

Default sort is effective/occurred time descending then UUID. Filters are fixed event families, safe state and bounded date range. Cursor is opaque, HMAC-signed and bound to tenant, actor/operation, patient, purpose, projection, normalized filters/sort/page size/snapshot; validity 15 minutes, default 25 and maximum 100 items. Timeline correlation never asserts causation absent an explicit source link.

Opening restricted source detail requires its own source permission, purpose and assurance and writes `patient.timeline.detail_accessed` version 1. A portal/proxy view additionally intersects current authority and privacy scope.

### Reporting

Operational dashboards use safe aggregate counts, age bands, queue SLA/status and policy freshness. They exclude names, raw/masked-small-cohort identifiers, contacts, dates of birth, free text and restriction/safety details.

| Report class           | Candidate behavior                                                                                                                                                |
| ---------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Patient-access copy    | One patient, designated-record projection under applicable access/correction policy; identity proofing and private delivery. Separate from operational analytics. |
| Registry operations    | Approved directory/duplicate/authority/consent/safety-review queue fields; row-level authorization and masked values.                                             |
| Privacy/audit evidence | Purpose-bound events and decisions; restricted detail challenge; no arbitrary payload.                                                                            |
| Analytics cohort       | Unavailable until privacy owner accepts exact inclusion, field/redaction, small-cell/disclosure-control and release-review policy.                                |
| Interoperability       | Exact approved FHIR profile/partner/purpose only; not an ad-hoc report.                                                                                           |

No unrestricted registry dump or user-selected raw column builder exists.

### Export policy

| Area                   | Candidate decision                                                                                                                                                                                                                                             |
| ---------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Purposes               | `patient_access`, `registry_operations`, `identity_quality_review`, `privacy_evidence`, `clinical_safety_review`, `legal_disclosure`, `approved_interoperability`. Exact legal-basis/policy key, reason, source permission and projection permission required. |
| Summary projections    | `patient-directory-summary-v1`, `duplicate-queue-summary-v1`, `authority-review-summary-v1`, `consent-privacy-summary-v1`, `safety-review-summary-v1`, `patient-timeline-summary-v1`.                                                                          |
| Restricted projections | `patient-identity-detail-v1`, `merge-impact-detail-v1`, `authority-decision-detail-v1`, `consent-privacy-detail-v1`, `patient-timeline-detail-v1`; requester and checker differ.                                                                               |
| Formats                | UTF-8 CSV with safe cells and versioned JSON Lines. PDF/designated-record formats require separate accepted projection. Raw database, spreadsheet workbook/macros, archive and evidence-document bundle are unavailable.                                       |
| Snapshot               | Repeatable database snapshot with server-owned `snapshotTime`; normalized filter/sort/field/redaction/purpose/policy digests persisted. Later changes do not alter artifact bytes.                                                                             |
| Limits                 | Each projection has approved row/byte bounds and one active job per requester/projection/tenant. Restricted export cannot exceed one patient unless bulk policy explicitly permits. Missing bound denies.                                                      |
| Assurance              | MFA and RA <= 5 minutes for restricted request/decision/access; independent decision fresh 30 minutes.                                                                                                                                                         |
| Artifact               | Private encrypted object, opaque ID, digest, allow-listed media/disposition and safe filename. Signed GET access is actor/tenant/export/digest bound and at most one hour; recommended 10 minutes. URLs/keys never persist in evidence/logs.                   |
| Lifecycle              | `requested -> pending_approval                                                                                                                                                                                                                                 | authorized -> running -> ready -> expired -> disposed`, terminal `rejected | failed`; access expiry never equals retention. Hold blocks deletion but not access expiry. |

CSV cells whose first non-whitespace character is `=`, `+`, `-` or `@` are prefixed with a single quote. CR/LF/tab and controls are normalized or rejected per field policy. Dynamic columns, formulas, HTML, links, macros, embedded objects and raw JSON payload columns are prohibited. Filenames contain a safe projection key and server date, never a patient name/identifier.

### Retention, residency and legal hold

The active matrix is versioned by deployment jurisdiction, record class, lifecycle and legal basis. Every record/artifact stores its applicable policy/version/class. The candidate deliberately supplies no universal number.

| Record family                                                             | Required schedule decision                                                                                                               |
| ------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------- |
| Patient identity/demographic/contact/address/identifier and merge lineage | Retention after inactive/deceased/merged and any clinical/legal linkage; correction history preserved.                                   |
| Abandoned registration staging                                            | Short approved duration with minimum data; exclude from operational/analytics use; preserve separately required duplicate/link evidence. |
| Authority, consent and privacy decisions/evidence                         | Effective/expired/withdrawn/revoked plus applicable rights/disclosure limitation period.                                                 |
| Safety flags and acknowledgements                                         | Clinical-record linkage schedule; resolution/error preserves source/decision lineage.                                                    |
| Duplicate/merge evidence                                                  | Identity-quality, clinical-impact and medico-legal schedule; no casual unmerge deletion.                                                 |
| Audit/outbox/inbox/export/FHIR evidence                                   | Security/privacy/accountability schedule without retaining prohibited payload.                                                           |
| Generated export artifact                                                 | Short separate artifact duration; access expires earlier; evidence retained under its own schedule.                                      |

Until the exact schedule and production storage/backup residency are accepted, disposal and that production hosting arrangement remain unavailable. This fail-closed hold is temporary governance behavior, not a claim that indefinite retention is lawful.

Legal hold is exact record/artifact/version, authority, reason and evidence based. It suspends disposal across primary data and governed backups but does not restore access, alter consent/privacy, make a flag active or extend a signed URL. The platform currently supports monotonic hold enablement; hold release/final disposal require separately authorized workflows and evidence.

Patient access, amendment, restriction or erasure requests route through the applicable law/policy decision. They do not directly mutate/delete the record. Corrections append linked evidence and, where required, disagreement/decision information.

### FHIR interoperability

FHIR is an external representation, not the internal database schema. `m3-candidate-1` defines semantic mapping only; no endpoint activates without an exact partner/jurisdiction base release plus implementation-guide package/version.

| CareOS concept                     | Candidate semantic mapping                                              | Key constraint                                                                                                |
| ---------------------------------- | ----------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------- |
| Patient profile/identifier/contact | `Patient`                                                               | Profile-approved identifiers/extensions; partial dates preserved; internal security/provenance not flattened. |
| Contact person vs caregiver        | `Patient.contact` or `RelatedPerson` as profile/use requires            | Relationship/related person is not proxy authority; portal access enforced by CareOS.                         |
| Consent directive/derivative       | `Consent`                                                               | Policy context, purpose/action/data/actor/time; derivative identified; resource alone does not enforce.       |
| Safety flag                        | `Flag`                                                                  | Concise priority item with supporting-detail link; not full clinical record or message.                       |
| Provenance/decision                | `Provenance`, `AuditEvent` where profile permits                        | Minimum necessary; no internal raw audit dump.                                                                |
| Merge lineage                      | `Patient.link` `replaced-by`/`replaces` where selected profile supports | CareOS approved merge is authority; no cross-organization Person graph.                                       |

Every adapter configuration pins partner, purpose, base release, implementation-guide NPM package/version/digest, supported profiles/interactions, terminology/value-set versions, identifier systems, extensions, security-label mapping, consent/privacy behavior, validation engine/version, endpoint trust, residency and retry/reconciliation ownership.

Inbound flow validates signature/channel where applicable, tenant/partner, content size/type, bundle/resource/profile/cardinality/invariants/terminology, identifier system, provenance, idempotency/conditional semantics and CareOS authorization before mapping. Unknown profile/version/terminology/security label rejects; it never enters the patient aggregate as an untyped extension.

Outbound flow reauthorizes source/field/purpose/consent/privacy at generation time, uses an immutable snapshot, validates the produced profile, records provenance/content digest and delivers through the approved channel. A FHIR Consent or security label communicates policy but never replaces CareOS access enforcement.

Purpose-specific endpoints only; broad generic search, `_include` traversal, bulk export, cross-organization match and generic FHIR server are unavailable. Conditional create/update is used only where the approved profile/partner contract defines safe organization-scoped identifiers and idempotency.

### Worker and access behavior

Export/FHIR/retention workers use disjoint exact operations, tenant-bound records, inbox/idempotency, bounded retry/dead letter and governed replay. Refresh or retry re-evaluates authority, source revisions, privacy/consent, profile/policy and hold. Browser polling uses bounded backoff and stops at terminal state. Browser storage, URLs and telemetry never contain export filters with sensitive values, access tokens or patient identity.

## Verification and acceptance

- Projection tests prove source permission joins, field allowlists, redaction/withheld behavior, self/proxy variants and no arbitrary query/raw payload.
- Cursor/filter tests cover normalization, operation/tenant/actor/patient/purpose binding, tamper/expiry and stable snapshot paging.
- Export tests cover purpose/legal basis, assurance/separation, repeatable snapshot, bounds, CSV/encoding/filename/content-type attacks, private artifact, short URL, access evidence and disposal/hold.
- Retention tests cover missing-schedule denial, residency gate, eligibility, hold across artifacts/backups, no access extension and deletion attestation/reconciliation.
- FHIR conformance tests use the exact pinned validator/profile/terminology packages and attack unknown profiles, cross-tenant identifiers, conditional replay, security labels, consent/privacy, malformed bundles and provenance drift.

## Approval boundary

These projections, purposes, export formats, mappings and policy requirements are candidates. No retention duration, cohort threshold, FHIR base release/profile, partner or production provider is approved by this package unless explicitly bound in a later deployment decision. Production behavior awaits exact candidate approval.
