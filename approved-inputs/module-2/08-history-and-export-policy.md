# Module 2 history and export policy candidate

**Artifact kind:** `history-and-export-policy`  
**Status:** `CANDIDATE_FOR_APPROVAL`  
**Candidate version:** `m2-candidate-1`  
**Approval:** Not granted

## Decision baseline

Workforce configuration history, member evidence timeline, governance/security audit, and export are separate allow-listed minimum-necessary projections. M2-26, M2-27, and M2-29 cannot query arbitrary source tables or expose generic before/after JSON. Filters, fields, joins, sorts, purposes, formats, retention, redaction, and worker identities are versioned policy.

### Workforce configuration history (M2-26)

- One workforce configuration snapshot groups controlled-registry/workflow policy versions and has an opaque UUID plus display number `WCFG-YYYY-NNNNNN`. Display numbers are never authorization or concurrency tokens.
- A history row includes parent snapshot/digest, change request/items, safe changed registry/category names, baseline/new revisions/digests, maker/checker/activator, bounded reason projection, validation/decision result/digest, effective/activation/supersession times, policy versions, and correlation ID.
- Compare exposes only approved non-secret registry fields and renders using the historical schema/version. It never includes role/permission internals, executable validation, person/member values, document/provider data, or protected free text.
- Filters: effective/activation time range maximum 366 days, status, allowed registry category, change type, actor opaque ID, exact entry/version ID, and correlation ID. Sort: activation time descending then UUID.
- Cursor is opaque, signed, organization/actor-operation/filter/sort/page-size-bound, valid 15 minutes. Page size 25 default, 100 maximum. Changed filters restart pagination.

### Workforce audit view (M2-27)

- List projection contains occurred time, safe actor label/opaque ID, exact event/version, operation, subject type/opaque ID, outcome, risk, correlation ID, and redaction marker.
- Detail adds only permitted event payload keys, decision/evidence references, protected-reason projection, registry/policy version, and recorded time. It never exposes passwords/tokens/MFA, birth date, home address/personal contact, match-key material, registration or credential number, document name/content/key/URL, reviewer free text, notification destination/provider response, or unrestricted before/after data.
- Filters: server-owned time range maximum 90 days per request, actor ID, operation, event/version, subject type/ID, outcome, risk, correlation ID, and approved event family. Unknown filters and free-form SQL-like search are rejected. Sort: occurred time descending then event UUID.
- `workforce.audit.read`, purpose, MFA, and RA <= 10 minutes are required. Opening restricted detail records `workforce.evidence.accessed` with projection/purpose/correlation. Hidden denial is used.

### Member lifecycle and evidence timeline (M2-29)

- Timeline projection `member-evidence-v1` correlates only events for one authorized workforce member: onboarding/match decision outcome, identity corrections, engagement, practitioner, qualification/registration/credential decisions, evidence state (not content), specialty/scope, assignment/service, availability version, account-link/access event reference, readiness/activation, suspension/reactivation, offboarding, expiry notification outcome, and eligibility evaluation.
- Items contain event family, safe title/state, server time/effective time, actor class and permitted label, context label, evidence/reference IDs, correlation ID, redaction marker, and schema version. Source free text and payload dumps are prohibited.
- Default ordering is effective/occurred time descending then evidence UUID. Family filter values are fixed. Maximum requested range is the member's complete retained history; page size/cursor rules match configuration history.
- Ordinary member/profile readers receive `member-timeline-summary-v1`; credential/scope/audit detail requires its separate source permission and purpose. Self view excludes reviewer notes, match candidates, internal risk, access-policy internals, and third-party identity.
- Timeline correlation is not a causal inference. UI copy says which event occurred and which explicit evidence reference connects it; it does not claim an event caused another unless the source decision records that relationship.

### Retention and correction

| Record family | Candidate default |
| --- | --- |
| Workforce member/lifecycle/engagement/assignment/activation/offboarding attribution | Seven years after offboarding/end/supersession, or longer jurisdiction/employment/clinical-record linkage schedule. |
| Qualification/registration/credential/scope decisions and eligibility evidence | Seven years after supersession/end/offboarding; longer where regulator/clinical attribution or legal hold requires. |
| Credential document content | Seven years after credential supersession/end/offboarding by candidate default; exact class is recorded at upload. Infected/invalid content is isolated/disposed under security policy, preserving non-content evidence. |
| Audit/configuration/member timeline projections | Seven years from source event; rebuilt projections cannot shorten source retention. |
| Export request/approval/access/disposal evidence | Seven years; generated artifact access 24 hours, disposal within 24 hours after expiry unless hold. |
| Delivered outbox/notification attempt evidence | One year for successful technical delivery and seven years for governed decision/dead-letter/replay evidence, without message destination/content. |

Legal hold is versioned, authority/reason/evidence based, independently releasable, and blocks disposal only. It never restores user access, makes a credential eligible, or changes lifecycle. Corrections append a linked version/event; verified, approved, activated, and historical attribution records are never overwritten. Jurisdiction policy may lengthen these defaults but cannot silently shorten an active obligation.

### Export policy

| Area | Candidate decision |
| --- | --- |
| Purposes | `workforce_operations`, `credentialing_review`, `regulatory_evidence`, `security_investigation`, `employment_record_request`, `data_correction`. One exact purpose, legal-basis registry key, bounded reason, source permission, and projection permission are required. |
| Formats | UTF-8 CSV (optional BOM) and versioned JSON Lines. PDF, spreadsheet workbook, ZIP/archive, raw database, document bundle, and credential-document bytes are unsupported in candidate 1. |
| Summary projections | `workforce-directory-summary-v1`, `credential-expiry-summary-v1`, `workforce-configuration-summary-v1`, `workforce-audit-summary-v1`, `member-timeline-summary-v1`. They exclude restricted values and need no independent checker beyond source permission/assurance. |
| Restricted projections | `credential-decision-detail-v1`, `scope-decision-detail-v1`, `workforce-audit-detail-v1`, `member-evidence-detail-v1`. They require a distinct `workforce.export.approve` checker; requester cannot approve. Person/contact detail and document content have no ordinary export projection. |
| Limits | Summary: 100,000 rows/250 MiB. Restricted: 25,000 rows/100 MiB. Member evidence: one member and 25,000 rows. One active job per requester/projection/tenant. Exceeding bounds fails before artifact generation. |
| Snapshot | Repeatable database snapshot at server-owned `snapshotTime`; exact normalized filters/sort/projection/field/redaction/policy digests persisted. Later changes never alter an artifact. |
| Assurance | MFA and RA <= 5 minutes for request/access; restricted MC approval fresh 30 minutes. A GET-only, actor/tenant/export/digest-bound access grant lasts at most 10 minutes. |
| Artifact | Private encrypted object, opaque UUID, SHA-256 digest, allow-listed content type/disposition and safe filename. Provider bucket/key/version/URL and encryption internals never enter API, logs, analytics, audit, or outbox. |
| Lifecycle | `requested -> authorized -> running -> ready -> expired -> disposed`, with terminal `failed`. Ready access expires after 24 hours. Hold blocks deletion but never extends access. |
| Workers | `m2-export-worker-v1` and `m2-retention-worker-v1` have disjoint exact grants, tenant-bound records, inbox/idempotency, bounded retry (five attempts with exponential backoff), dead-letter owner, and governed replay. |

CSV cells whose first non-whitespace character is `=`, `+`, `-`, or `@` are prefixed with a single quote. CR/LF/tab and other controls are normalized or rejected per field policy. JSON Lines includes one schema header and canonical UTC values. Formulae, HTML, hyperlinks, macros, embedded objects, raw JSON payload columns, and dynamic column names are prohibited. Export filenames contain only safe organization-neutral type and server date, never a person/member name.

### UI and API states

- M2-26: loading, empty/no-result, list, cursor expiry/restart, detail, semantic compare with added/removed/changed labels, redaction, retired schema display, denied, conflict/stale, and export request/status.
- M2-27: bounded filter/list/detail, hidden denial, purpose/RA challenge, restricted-access evidence, correlation navigation, cursor expiry, export request/approval, unavailable/expired artifact, and failure recovery.
- M2-29: member summary/timeline, family filter, minimum projection, restricted deep-link challenge, redacted/retired evidence, cursor recovery, and no-result/error states.
- Export polling uses bounded backoff, respects retry guidance, and stops at terminal state. Refresh recovers entirely from server state. Browser storage, analytics, and URLs never contain filters with sensitive values, artifact tokens, or destinations.

## Verification and acceptance

- Projection tests prove exact field allowlists, source-permission joins, redaction, self/role/scope variants, historical schema rendering, no arbitrary query, and restricted-detail access evidence.
- Filter/sort/cursor tests cover allowlists, normalization, tenant/actor/operation binding, tampering, expiry, page stability, and range/size bounds.
- Export tests cover purpose/legal basis/RA/MFA/MC, repeatable snapshot, idempotency, row/size failure, CSV formula injection, delimiter/newline/control/encoding/filename/content-type attacks, JSON schema drift, and absence of document bytes.
- Storage/retention tests prove private encryption policy, digest, short-lived GET-only grant, URL-free evidence, expiry, hold-blocked disposal, deletion proof, dead letter/replay, and reconciliation after provider failure.

## Approval boundary

The projections, purposes, limits, formats, retention periods, redactions, and worker contracts are candidates. Privacy/records/legal, HR, credentialing, clinical governance, security, operations, accessibility, and product authorities must accept the exact digest and jurisdiction extensions before production history, audit, timeline, or export is enabled.
