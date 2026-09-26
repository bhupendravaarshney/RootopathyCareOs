# Module 3 readiness and activation policy candidate

**Artifact kind:** `readiness-and-activation-policy`  
**Status:** `CANDIDATE_FOR_APPROVAL`  
**Candidate version:** `m3-candidate-1`  
**Approval:** Not granted

## Decision baseline

M3 uses server-calculated, versioned validation results for patient registration and capability-specific activation. A missing local policy disables only its dependent capability; it does not prevent clinically necessary creation of a minimum safe patient identity and never becomes a permissive fallback.

There is **no readiness override** for tenant isolation, identity collision, duplicate disposition, invalid/unknown identifier schemes, authority ambiguity, consent-required use, privacy restriction, safety eligibility, merge separation, proofing, legal hold or profile validation. Warning acknowledgement cannot convert a blocker.

### Result contract

`patient-registration-v1` evaluates one organization, registration run revision and proposed patient result. The immutable result includes evaluator/schema version, organization/run/actor, source and purpose, run revision, active policy/configuration versions, ordered gates, outcome/evidence references, warnings, validation instant, expiry and canonical result digest.

Gate outcomes are exactly `complete`, `warning`, `blocked` or `not_applicable`. Unknown/missing evaluators produce `blocked`, never `complete`. Results expire after 15 minutes and immediately on a material change. Submission binds the exact digest; the browser cannot supply or recalculate it.

### Registration gates

| Ordered gate key                | Complete evidence                                                                                                                  | Blocking/warning behavior and deep link                                                                                                 |
| ------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------- |
| `patient.registration.context`  | Active organization, permitted source/purpose, creator/resume authority, unexpired run and optional eligible facility.             | Any failure blocked; P3-03.                                                                                                             |
| `patient.identity.minimum`      | Internal identifier and provenance plus a name representation or explicit temporary/unnamed state; valid date precision/certainty. | Missing/fabricated/invalid data blocked; P3-05.                                                                                         |
| `patient.identity.provenance`   | Supplier/source and per-attribute verification state recorded; no unsupported assurance claim.                                     | Required source missing blocked; incomplete optional verification warning; P3-05.                                                       |
| `patient.duplicate.search`      | Fresh organization-scoped search with approved detector/version and safe factor digest.                                            | Search absent/stale/unavailable blocked; P3-04.                                                                                         |
| `patient.duplicate.disposition` | Existing selected, no-match/new reason, independent exception, or urgent temporary path; confirmed identifier conflict resolved.   | Unresolved conflict/ambiguous candidate blocked; lower-band reviewed per policy; P3-04/P3-14.                                           |
| `patient.identifier.valid`      | Every supplied identifier uses active scheme/version/issuer, passes checks and scoped uniqueness; primary rules valid.             | Any supplied invalid/collision blocked; no identifier may be warning/not applicable under registration policy; P3-08.                   |
| `patient.contact.address.valid` | Supplied values valid, normalized/versioned, provenance/effective/primary rules consistent.                                        | Invalid supplied value blocked; absent optional value not applicable; P3-06.                                                            |
| `patient.preference.valid`      | Supplied preferences have known purpose/channel/language/timezone/format and do not claim consent.                                 | Invalid supplied preference blocked; absent not applicable; P3-07.                                                                      |
| `patient.proxy.valid`           | Each proposed authority has known local-law catalogue, grantor/grantee/scope/evidence/time and required review.                    | Relationship-only or ambiguous authority blocked for activation of that authority; patient registration may complete without it; P3-09. |
| `patient.consent_privacy.valid` | Supplied directives/restrictions use known policy/purpose/action/data/actor and authorized grantor/decision.                       | Invalid supplied record blocked; absent handled by purpose policy, never inferred; P3-10.                                               |
| `patient.safety.valid`          | Each proposed flag uses active clinical catalogue, eligible author/verifier path, source reference, severity/visibility/SLA.       | Invalid flag blocked; unavailable catalogue blocks flag activation, not minimum identity; P3-11.                                        |
| `patient.registration.review`   | All sections reviewed, warnings acknowledged by code, exact patient/result preview and current revision.                           | Missing acknowledgement or stale section blocked; P3-12.                                                                                |
| `patient.platform.integrity`    | Database/RLS/audit/outbox/idempotency available; only required configured platform capabilities report safe readiness.             | Core evidence unavailable blocked; unused notification/document/FHIR provider not applicable.                                           |

### Urgent temporary identity path

Urgent care does not bypass identity safety. The path requires an eligible registration actor, urgent reason code, server-generated temporary patient identifier, visible temporary state, minimum provenance, no guessed existing patient selection, duplicate search result if available, and immediate reconciliation queue item. If matching is unavailable, `patient.duplicate.search` records an explicit dependency failure accepted only by the governed urgent path and creates highest-priority follow-up evidence; it never produces a no-match result.

Routine portal invitation, bulk export and external exchange are prohibited while identity remains temporary unless an exact separate policy permits a minimum safe action.

### Capability activation gates

These gates do not all block basic patient registration. They gate only the named capability.

| Capability                | Required active policy/evidence                                                                                                      | Missing/invalid behavior                                                     |
| ------------------------- | ------------------------------------------------------------------------------------------------------------------------------------ | ---------------------------------------------------------------------------- |
| Identifier type           | Exact scheme/version, issuer/jurisdiction, normalization/check/uniqueness/mask/evidence policy.                                      | Identifier capture/lookup unavailable.                                       |
| Numeric duplicate scoring | Detector/version, governed calibration set/result, approved thresholds/bands, monitor/SLA owner.                                     | Only approved deterministic rules or matching unavailable; no guessed score. |
| Proxy authority           | Jurisdiction/authority/minor/capacity/safeguarding catalogue and reviewer eligibility.                                               | Relationship may be recorded; authority/portal proxy unavailable.            |
| Consent-dependent purpose | Purpose/action/data/actor policy, grantor rules, directive format and conflict evaluator.                                            | Dependent use denied.                                                        |
| Privacy restriction       | Restriction/exception/projection catalogue and privacy-owner decision.                                                               | Requested restriction remains pending; no free-text override.                |
| Safety flag               | Category/severity/code/visibility/author/verifier/SLA/acknowledgement catalogue.                                                     | Flag activation unavailable; source clinical record remains authoritative.   |
| Patient/proxy portal      | Proofing/MFA/recovery/invitation/session policy plus self or active authority evidence.                                              | Link/invitation/recovery unavailable.                                        |
| Consumer communication    | Purpose/channel authority, preference, verified destination, quiet-time, accessible template/version and provider readiness.         | Message suppressed/fails closed; clinical escalation unaffected.             |
| Disposal/residency        | Jurisdiction/record-class schedule, hosting/backup residency acceptance, disposal authority and legal-hold workflow.                 | Disposal and unaccepted production hosting unavailable.                      |
| Cohort/bulk export        | Projection/purpose/field/redaction/disclosure-control/approval/retention/access policy.                                              | Cohort/bulk export unavailable.                                              |
| FHIR exchange             | Partner, purpose, base release, implementation-guide package/version, terminology, identifier/security-label mapping and validation. | FHIR import/export unavailable; no generic server.                           |

### Merge readiness

`patient-merge-impact-v1` is requested only for an open candidate and exact pair. It includes patient/source revisions, identity/identifier/contact/authority/consent/privacy/safety conflicts, affected-reference family counts, field dispositions, survivor rationale, clinical-activity-since-anomaly marker, policy versions, blockers/warnings, evaluation time/expiry and digest.

The result expires after 15 minutes or any patient/reference/policy change. A decision is valid 30 minutes and never outlives the result. Required gates include same organization, distinct non-merged patients, no cycles/active targets, current candidate/detector evidence, eligible survivor, every field disposition, complete reference inventory, clinical-impact review route, maker/checker eligibility and platform atomicity. No gate is overrideable.

### Freshness and invalidation

Material changes include patient identity/demographics/lifecycle, identifier/contact/address/preference, match factors/detector/calibration, duplicate disposition, proxy authority/portal link, consent/privacy, safety flag, downstream references, organization/facility policy, canonical permissions/membership/session assurance, active catalogue/configuration, provider readiness, retention/hold or system-clock integrity.

- In-transaction changes write invalidation with the source change. External changes are rechecked before high-impact use.
- Missing/failed invalidation consumption causes dependent submission/decision/execution to fail closed.
- A refreshed result is a new immutable run/digest; no approval silently follows it.
- Historical clinical attribution and completed lawful actions retain the evidence/version used at their action instant.

### Submission and registration

1. Authorized actor requests validation for one exact unexpired run revision; idempotent exact replay returns the same result.
2. Server evaluates every ordered gate and persists the result/digest. Evaluator error becomes blocked failure evidence.
3. Actor reviews exact patient projection, duplicate disposition and named warnings, then submits the fresh digest/revision with idempotency.
4. Server reauthorizes and rechecks current policy, run, source data and result freshness.
5. New patient or existing-patient link plus effective child facts, registration completion, idempotency, audit and outbox commit atomically.
6. Failure leaves the run retryable or rejected according to problem code and creates no partial patient success.

### Jobs and notifications

Candidate worker milestones cover registration expiry/reconciliation, authority/consent/flag review expiry, duplicate SLA, portal invitation expiry, authorized communication, export and retention. Jobs are tenant-derived, schema/version allow-listed, leased, idempotent, bounded-retry and dead-lettered with safe evidence. Each worker reauthorizes the effect from current state; a queued job is not authority.

Provider unavailability never turns into verified contact, delivered clinical escalation, completed export, applied FHIR resource or disposed data. Operational dashboards show safe counts/ages/status only.

## Verification and acceptance

- Evaluator tests prove exact order/count/version/outcome vocabulary, canonical digest, freshness, every invalidator and unknown-evaluator denial.
- Registration tests cover no-match, exact conflict, ambiguous match, temporary urgent identity, partial/unknown data, optional capabilities and atomic failure.
- Capability tests prove one missing catalogue disables only its dependent operation and cannot enable fallback behavior.
- Merge tests cover stale source/reference changes, self-decision, expiry, cycle/concurrency, clinical-impact flag and no partial execution.
- Worker tests cover leases, replay, changed content, retry/dead letter, provider failure, tenant isolation and current-authority recheck.

## Approval boundary

Gate keys, result versions, freshness limits, capability dependencies and worker behavior are candidates. They do not activate a patient workflow, policy catalogue, worker or provider. Production implementation awaits exact-package approval.
